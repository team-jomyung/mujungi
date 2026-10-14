package kr.mujoong.realtime.web;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.ORIGIN;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.WebSocketHandshakeException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.HeartbeatAck;
import kr.mujoong.contract.ws.message.HelloOk;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.WsError;
import kr.mujoong.realtime.gateway.DeviceConnection;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.gateway.DeviceConnectionRow;
import kr.mujoong.realtime.gateway.DisconnectChecker;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.gateway.StatusReporter;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.ManualTime;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기기 WSS 끝점 시험 (U1 3.5 Step 35 · BR3.6 · BR3.7 · BR4.7 · BR4.8 · SD-7 · SCD-2 · RD-4 · J55 · J123 · J125 · OD-8 · R3-4 · 편차 (j)(l)(s) ·
 * AC2.2.5 · AC6.2.5 · AC6.2.8 ~ 6.2.11 · NFR4.1 · NFR9.4 · NFR9.9).
 *
 * <p>운영 끝점 {@code /ws/device}에 실제 서버(무작위 포트) · 실제 DB · Core 계약 대역 · 기기 시뮬레이터(JDK WebSocket)로 붙는다. 시간 값은
 * 운영 값이다: verify-token 1초 · HELLO 5초 · 끊김 40초. 5초 · 40초 시한은 실제로 기다리지 않고 수동 시계({@link ManualTime})를 앞당긴다
 * (NFR6.2). verify-token 1초 제한만 실제 HTTP 지연으로 넘긴다.
 */
@Tag("u1")
@Tag("db")
class DeviceGatewayIT {

    private static final Map<String, String> PRODUCTION_TIMES = Map.of(
            "mujoong.internal-http.verify-token-timeout", "1s",
            "mujoong.timing.device.hello-timeout", "5s",
            "mujoong.timing.device.disconnect", "40s");
    private static final String VERIFY = "/internal/devices/verify-token";
    private static final String REPORT = "/internal/devices/{deviceId}/connection";

    private RealtimeItEnvironment env;
    private LogCapture logs;

    @AfterEach
    void stop() {
        try {
            if (env != null) {
                env.close();
            }
        } finally {
            if (logs != null) {
                logs.close();
            }
        }
    }

    @Test
    void theOriginMustMatchExactlyAndAMissingOrNullOriginIsRefusedWith403() {
        start(Map.of());

        for (String origin : new String[] {"https://evil.invalid", null, "null", ORIGIN + "/"}) {
            DeviceSimulatorClient.Connection refused = DeviceSimulatorClient.connect(env.app.deviceUri(), origin,
                    env.deviceToken, WAIT);
            assertThat(refused).isInstanceOf(DeviceSimulatorClient.Refused.class);
            Throwable cause = ((DeviceSimulatorClient.Refused) refused).cause();
            assertThat(cause).isInstanceOf(WebSocketHandshakeException.class);
            assertThat(((WebSocketHandshakeException) cause).getResponse().statusCode()).isEqualTo(403);
        }
        assertThat(logs.events(EventCode.SECURITY_ORIGIN_DENIED)).isNotEmpty().allSatisfy(event ->
                assertThat(LogCapture.attrs(event)).containsEntry("channel", "WSS").containsEntry("statusCode", 403));
        // 거절된 업그레이드는 Core에 묻지도 않는다
        assertThat(env.core.requests("POST", VERIFY)).isEmpty();
        assertThat(env.connectDevice().isClosed()).isFalse();
    }

    @Test
    void aMissingCookieOrARejectedTokenCloses4401AndEveryConnectionIsVerifiedAgain() {
        start(Map.of());

        DeviceSimulatorClient noCookie = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, null, WAIT);
        assertThat(noCookie.awaitClose(WAIT)).contains(CloseCode.UNAUTHORIZED.code());
        assertThat(env.core.requests("POST", VERIFY)).isEmpty();

        env.verifyRoute.respond(StubResponse.error(401, kr.mujoong.contract.error.ErrorCode.INVALID_DEVICE_TOKEN));
        DeviceSimulatorClient released = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, env.deviceToken, WAIT);
        assertThat(released.awaitClose(WAIT)).contains(CloseCode.UNAUTHORIZED.code());
        List<Map<String, Object>> failures = logs.events(EventCode.SECURITY_AUTH_FAILED);
        assertThat(failures).hasSize(2);
        assertThat(failures.get(0).get("level")).isEqualTo("INFO");
        assertThat(failures.get(1).get("level")).isEqualTo("WARN");
        assertThat(LogCapture.attrs(failures.get(1))).containsEntry("kind", "DEVICE_TOKEN").containsEntry("statusCode", 4401);

        // (j) 결과를 캐시하지 않는다: 연결 두 번 = 확인 두 번
        env.verifyRoute.respond(StubResponse.json(200, env.verifiedBody()));
        env.core.clearRequests();
        env.connectDevice().closeWith(CloseCode.DEVICE_OFF.code());
        env.connectDevice();
        assertThat(env.core.requests("POST", VERIFY)).hasSize(2).allSatisfy(request ->
                assertThat(request.json().get("deviceToken").asString()).isEqualTo(env.deviceToken));
        assertThat(logs.lines()).noneMatch(line -> line.contains(env.deviceToken));
    }

    @Test
    void coreAnsweringAfterOneSecondCloses1013AndWithinOneSecondPasses() {
        start(Map.of());

        env.verifyRoute.respond(StubResponse.json(200, env.verifiedBody()).delayed(Duration.ofMillis(1_400)));
        DeviceSimulatorClient slow = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, env.deviceToken, WAIT);
        slow.sendHello();
        assertThat(slow.awaitClose(WAIT)).contains(CloseCode.TRY_AGAIN_LATER.code());
        assertThat(slow.received(WsMessageType.HELLO_OK)).isEmpty();

        env.verifyRoute.respond(StubResponse.json(200, env.verifiedBody()).delayed(Duration.ofMillis(500)));
        DeviceSimulatorClient intime = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, env.deviceToken, WAIT);
        // 확인 결과보다 먼저 온 HELLO는 기다렸다가 처리한다(SD-7 5단계)
        HelloOk ok = intime.hello(WAIT);
        assertThat(ok.connectionId()).isNotNull();
        assertThat(ok.heartbeatIntervalMs()).isPositive();
    }

    @Test
    void helloWithinFiveSecondsPassesAndAfterFiveSecondsTheConnectionCloses4408() {
        ManualTime time = new ManualTime();
        startWith(Map.of(), time);

        DeviceSimulatorClient patient = openVerified(time);
        time.advance(Duration.ofMillis(4_900));
        assertThat(patient.awaitClose(Duration.ofMillis(300))).isEmpty();
        HelloOk ok = patient.hello(WAIT);
        awaitTrue(() -> env.ledger().map(row -> ok.connectionId().equals(row.connectionId())).orElse(false),
                "원장 교체");

        DeviceSimulatorClient silent = openVerified(time);
        time.advance(Duration.ofMillis(5_100));
        assertThat(silent.awaitClose(WAIT)).contains(CloseCode.HELLO_TIMEOUT.code());
        // HELLO 없이 닫힌 연결은 원장을 건드리지 않는다(BR3.7)
        RealtimeItEnvironment.sleep(200);
        assertThat(env.ledger().orElseThrow().connectionId()).isEqualTo(ok.connectionId());
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(patient.isClosed()).isFalse();
    }

    @Test
    void aMalformedHelloOversizedTextAndWrongAudioFramesClose4400() {
        start(Map.of());

        DeviceSimulatorClient badHello = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, env.deviceToken, WAIT);
        badHello.sendRaw("{\"type\":\"HELLO\",\"seq\":1,\"sentAt\":\"2026-10-15T09:00:00Z\",\"payload\":{}}");
        assertThat(badHello.awaitClose(WAIT)).contains(CloseCode.PROTOCOL_ERROR.code());

        DeviceSimulatorClient oversized = env.connectDevice();
        oversized.sendRaw(heartbeatPaddedTo(8 * 1024 + 1));
        assertThat(oversized.awaitClose(WAIT)).contains(CloseCode.PROTOCOL_ERROR.code());

        DeviceSimulatorClient wrongAudio = env.connectDevice();
        byte[] frame = new byte[966];
        frame[0] = 0x01;
        wrongAudio.sendBinary(frame);
        assertThat(wrongAudio.awaitClose(WAIT)).contains(CloseCode.PROTOCOL_ERROR.code());

        DeviceSimulatorClient serverFrame = env.connectDevice();
        byte[] output = new byte[4805];
        output[0] = 0x02;
        serverFrame.sendBinary(output);
        assertThat(serverFrame.awaitClose(WAIT)).contains(CloseCode.PROTOCOL_ERROR.code());
    }

    @Test
    void unknownTypesGetAnErrorWhileIgnoredMessagesKeepTheConnectionAndHeartbeatsAreAcked() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();

        long hbSeq = device.heartbeat(25L);
        WsEnvelope ack = device.await(envelope -> envelope.payload() instanceof HeartbeatAck heartbeatAck
                && heartbeatAck.hbSeq() == hbSeq, WAIT);
        assertThat(((HeartbeatAck) ack.payload()).serverTime()).isNotNull();

        device.sendRaw("{\"type\":\"SOMETHING_NEW\",\"seq\":900,\"sentAt\":\"" + Instant.now() + "\",\"payload\":{}}");
        WsEnvelope error = device.await(WsMessageType.ERROR, WAIT);
        assertThat(((WsError) error.payload()).code()).isEqualTo(WsError.Code.UNKNOWN_TYPE);
        assertThat(((WsError) error.payload()).refSeq()).isEqualTo(900L);

        device.deviceState();
        device.playbackMark(UUID.randomUUID(), UUID.randomUUID(), PlaybackMark.Status.PROGRESS);
        long next = device.heartbeat(25L);
        device.await(envelope -> envelope.payload() instanceof HeartbeatAck heartbeatAck
                && heartbeatAck.hbSeq() == next, WAIT);
        assertThat(device.received(WsMessageType.ERROR)).hasSize(1);
        assertThat(device.isClosed()).isFalse();
    }

    @Test
    void theHeartbeatSentAtGivesAClockOffsetWithinHalfTheRoundTripAndWithoutItTheTurnIsMarkedMissing() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID connectionId = env.ledger().orElseThrow().connectionId();

        device.clockSkew(Duration.ofSeconds(5)).heartbeat(40L);
        awaitTrue(() -> connection(connectionId).clockOffsetMs() != null, "clockOffsetMs");
        assertThat(connection(connectionId).clockOffsetMs()).isBetween(5_000L - 20L, 5_000L + 20L);
        assertThat(connection(connectionId).rttMs()).isEqualTo(40L);

        device.heartbeatWithoutSentAt(40L);
        awaitTrue(() -> connection(connectionId).clockOffsetMs() == null, "offset 비움");
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(command.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitTrue(() -> env.app.bean(ActivitySessionRepository.class).find(sessionId)
                .map(row -> row.status() == SessionStatus.ACTIVE).orElse(false), "ACTIVE");
        env.provider.emitTurn("item-1", "안녕", "resp-1", "안녕 반가워", List.of(new byte[4800]));
        WsEnvelope begin = device.await(WsMessageType.OUTPUT_BEGIN, WAIT);
        device.playbackMark(sessionId, ((OutputBegin) begin.payload()).playbackId(), PlaybackMark.Status.STARTED);

        awaitTrue(() -> logs.last(EventCode.LATENCY_TURN).isPresent(), "LATENCY_TURN");
        assertThat(LogCapture.attrs(logs.last(EventCode.LATENCY_TURN).orElseThrow()))
                .containsEntry("unobservedReason", "CLOCK_OFFSET_MISSING");
    }

    @Test
    void aSecondConnectionTakesOverOnlyAtHelloOkAndTheFirstIsClosedWith4001() {
        start(Map.of());
        DeviceSimulatorClient first = env.connectDevice();
        UUID firstId = env.ledger().orElseThrow().connectionId();

        env.core.clearRequests();
        DeviceSimulatorClient second = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, env.deviceToken, WAIT);
        env.core.awaitRequests("POST", VERIFY, 1, WAIT);
        // 확인만 통과하고 HELLO가 없으면 옛 연결은 그대로다
        assertThat(first.awaitClose(Duration.ofMillis(300))).isEmpty();
        assertThat(env.ledger().orElseThrow().connectionId()).isEqualTo(firstId);

        HelloOk ok = second.hello(WAIT);
        assertThat(env.ledger().orElseThrow().connectionId()).isEqualTo(ok.connectionId());
        assertThat(first.awaitClose(WAIT)).contains(CloseCode.REPLACED.code());
        assertThat(env.app.bean(SocketRegistry.class).isLive(env.deviceId, ok.connectionId())).isTrue();
        assertThat(env.app.bean(SocketRegistry.class).isLive(env.deviceId, firstId)).isFalse();
        // 4001로 닫힌 옛 연결은 새 연결의 상태를 덮지 않는다(BR3.7 조건부 기록)
        RealtimeItEnvironment.sleep(300);
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
    }

    @Test
    void aFailedHandoverClosesOnlyTheNewConnectionWith1011AndNeverRequests4001() throws SQLException {
        start(Map.of("mujoong.limits.db-lock-timeout", "200ms"));
        DeviceSimulatorClient first = env.connectDevice();
        UUID firstId = env.ledger().orElseThrow().connectionId();

        try (Connection lock = env.db.dataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (PreparedStatement select = lock.prepareStatement(
                    "select device_id from realtime.realtime_device_connection where device_id = ? for update")) {
                select.setObject(1, env.deviceId);
                select.executeQuery().close();
            }
            DeviceSimulatorClient second = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, env.deviceToken,
                    WAIT);
            second.sendHello();
            assertThat(second.awaitClose(WAIT)).contains(CloseCode.SERVER_ERROR.code());
            assertThat(second.received(WsMessageType.HELLO_OK)).isEmpty();
            lock.rollback();
        }

        // SCD-2: 옛 연결 · 레지스트리 · 원장은 그대로이고 4001은 요청되지 않았다
        assertThat(first.awaitClose(Duration.ofMillis(300))).isEmpty();
        assertThat(env.app.bean(SocketRegistry.class).isLive(env.deviceId, firstId)).isTrue();
        assertThat(env.ledger().orElseThrow().connectionId()).isEqualTo(firstId);
        long hbSeq = first.heartbeat(10L);
        first.await(envelope -> envelope.payload() instanceof HeartbeatAck heartbeatAck
                && heartbeatAck.hbSeq() == hbSeq, WAIT);
    }

    @Test
    void deviceCloseCodesDriveTheLedgerAndAClosedSocketMakesTheNextStart423() {
        start(Map.of());

        env.connectDevice().closeWith(CloseCode.DEVICE_OFF.code());
        awaitLedger(ConnectionState.OFFLINE);
        env.connectDevice().closeWith(CloseCode.GOING_AWAY.code());
        awaitLedger(ConnectionState.OFFLINE);

        // TCP만 끊긴 소켓(1006)은 원장을 바꾸지 않지만 살아 있는 소켓이 아니므로 시작은 423이다(RD-4 · NFR4.1)
        DeviceSimulatorClient dropped = env.connectDevice();
        UUID droppedId = env.ledger().orElseThrow().connectionId();
        dropped.close();
        awaitTrue(() -> !env.app.bean(SocketRegistry.class).isLive(env.deviceId, droppedId), "소켓 레지스트리에서 빠짐");
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
        InternalApi.Reply start = env.api().post("/internal/sessions",
                InternalApi.startBody(UUID.randomUUID(), env.guardianId, env.childId, env.deviceId));
        assertThat(start.status()).isEqualTo(423);
        assertThat(start.code()).isEqualTo("DEVICE_NOT_CONNECTED");
    }

    @Test
    void fortySecondsOfSilenceDisconnectsTheLedgerAndTheNextHeartbeatRecovers() {
        ManualTime time = new ManualTime();
        startWith(Map.of(), time);
        DeviceSimulatorClient device = env.connectDevice();

        time.advance(Duration.ofSeconds(39));
        RealtimeItEnvironment.sleep(800);
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);

        time.advance(Duration.ofSeconds(2));
        awaitLedger(ConnectionState.DISCONNECTED);
        // 소켓이 열린 채 무음이어도 같다 — 같은 연결의 HEARTBEAT로 복귀(J55 · BR4.8)
        assertThat(device.isClosed()).isFalse();
        device.heartbeat(15L);
        awaitLedger(ConnectionState.CONNECTED);
        awaitTrue(() -> time.clock.now().equals(env.ledger().orElseThrow().lastSeenAt()), "last_seen_at 갱신(J125)");
    }

    /**
     * 경주 (A)의 결정적 재현(3.6 수정 J390 · BR4.8 ② · RD-4): 기록기 기기 칸(J125 · 200ms 배치)이 시험 동안 한 번도 쓰이지 않게 flush 간격을
     * 넓혀 두면, 복귀 UPDATE가 last_seen_at을 올리지 않는 한 끊김 점검자는 복귀 직후의 CONNECTED 행을 여전히 '40초 무음'으로 보고 다시
     * DISCONNECTED로 되돌린다(flip-back). 사본이 끊김을 안 뒤에 HEARTBEAT를 보내 경주 (B)와 섞이지 않게 한다.
     */
    @Test
    void recoveryRaisesLastSeenAtSoTheDisconnectCheckDoesNotFlipItBack() {
        ManualTime time = new ManualTime();
        startWith(Map.of("mujoong.timing.recorder.flush-interval", "1h"), time);
        DeviceSimulatorClient device = env.connectDevice();
        UUID connectionId = env.ledger().orElseThrow().connectionId();
        Instant connectedAt = time.clock.now();

        time.advance(Duration.ofSeconds(41));
        awaitLedger(ConnectionState.DISCONNECTED);
        awaitTrue(() -> connection(connectionId).knownState() == ConnectionState.DISCONNECTED, "사본 DISCONNECTED");
        device.heartbeat(15L);
        awaitLedger(ConnectionState.CONNECTED);

        // 기록기 배치는 아직 쓰지 않았으므로 last_seen_at을 올린 것은 복귀 UPDATE 자신뿐이다
        DeviceConnectionRow recovered = env.ledger().orElseThrow();
        assertThat(recovered.lastSeenAt()).isAfter(connectedAt).isEqualTo(time.clock.now());
        // 복귀 직후 돈 끊김 점검(주기 점검과 같은 호출)은 아무것도 되돌리지 않는다
        assertThat(env.app.bean(DisconnectChecker.class).runOnce()).isZero();
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
        // 주기 점검(200ms)이 두 번 넘게 지나도 같다
        RealtimeItEnvironment.sleep(500);
        assertThat(transitions()).containsExactly("OFFLINE->CONNECTED", "CONNECTED->DISCONNECTED",
                "DISCONNECTED->CONNECTED");
    }

    /**
     * 경주 (B)의 뒷부분(3.6 수정 J391): 사본만 원장보다 앞선 상황(끊김 트랜잭션이 사본을 맞춘 뒤 되돌아간 경우)에서 온 HEARTBEAT는 복귀
     * UPDATE가 0행이다. 그때 원장을 다시 읽어 사본을 맞추지 않으면 사본은 영영 DISCONNECTED로 남고(원장은 CONNECTED) 다음 HEARTBEAT마다
     * 헛된 복귀 작업만 뜬다. 0행 복귀는 상태 변화 · 이벤트 · DEVICE_CONNECTION 줄을 만들지 않는다.
     */
    @Test
    void aHeartbeatWhoseRecoveryUpdatesNothingResyncsTheCopyFromTheLedger() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID connectionId = env.ledger().orElseThrow().connectionId();

        connection(connectionId).knownState(ConnectionState.DISCONNECTED);
        device.heartbeat(15L);

        awaitTrue(() -> connection(connectionId).knownState() == ConnectionState.CONNECTED, "사본 재동기화");
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
        RealtimeItEnvironment.sleep(200);
        assertThat(transitions()).containsExactly("OFFLINE->CONNECTED");
    }

    /**
     * 원인 분석의 탐침(3.6 수정 J390 · J391): 기록기 flush 간격을 끊김 점검 주기(200ms)보다 훨씬 넓혀(1초) 복귀 ~ last_seen_at 배치 사이를 키우고,
     * 원장에서 끊김을 본 즉시(사본을 기다리지 않고) HEARTBEAT 하나를 보내는 라운드를 되풀이한다. 복귀 UPDATE가 last_seen_at을 올리지 않으면
     * 라운드의 약 8할에서 다음 점검이 복귀를 되돌린다(flush 300ms 탐침에서는 점검보다 배치가 늦은 라운드만, 20 중 6). 라운드마다 HEARTBEAT 하나로
     * 복귀하고 그 뒤 점검 두 번이 지나도 복귀 상태여야 하며, DEVICE_CONNECTION 전이는 라운드 수만큼의 끊김 · 복귀 쌍뿐이어야 한다(flip-back이
     * 있으면 끊김 줄이 더 많다). U3(BR4.8 ①): 40초 무음도 끊김 한 건이라 5분 창 안 두 번째 라운드부터 복귀 상태는 CONNECTED가 아니라
     * UNSTABLE이다 — 복귀 상태가 어느 쪽이든 되돌아가지 않는 것이 이 시험의 핵심이다.
     */
    @Test
    void repeatedSilenceRoundsRecoverWithASingleHeartbeatAndNeverFlipBack() {
        ManualTime time = new ManualTime();
        startWith(Map.of("mujoong.timing.recorder.flush-interval", "1s"), time);
        DeviceSimulatorClient device = env.connectDevice();
        int rounds = 10;

        for (int round = 1; round <= rounds; round++) {
            time.advance(Duration.ofSeconds(41));
            awaitLedger(ConnectionState.DISCONNECTED);
            device.heartbeat(15L);
            // U3-R1: 창(5분) 안 끊김이 두 건부터면 UNSTABLE로 복귀한다(41초 간격이라 열 라운드 모두 창 안이다)
            ConnectionState recovered = round == 1 ? ConnectionState.CONNECTED : ConnectionState.UNSTABLE;
            awaitLedger(recovered);
            RealtimeItEnvironment.sleep(450);
            assertThat(env.ledger().orElseThrow().state()).as("라운드 %d 복귀 뒤 유지", round).isEqualTo(recovered);
            // 41초 간격 무음 끊김 중 창(5분) 안에 든 수: 앞선 라운드 k는 41k초 > 41 × round − 300초일 때만 남는다
            int current = round;
            long inWindow = java.util.stream.IntStream.rangeClosed(1, round).filter(k -> 41L * k > 41L * current - 300L)
                    .count();
            assertThat(env.ledger().orElseThrow().recentDisconnects()).as("라운드 %d 창 안 끊김 횟수", round)
                    .isEqualTo((int) inWindow);
        }
        List<String> transitions = transitions();
        assertThat(transitions).filteredOn(transition -> transition.endsWith("->DISCONNECTED")).hasSize(rounds);
        assertThat(transitions).filteredOn(transition -> transition.startsWith("DISCONNECTED->")).hasSize(rounds);
        assertThat(transitions).filteredOn("DISCONNECTED->CONNECTED"::equals).hasSize(1);
        assertThat(transitions).filteredOn("DISCONNECTED->UNSTABLE"::equals).hasSize(rounds - 1);
        assertThat(device.isClosed()).isFalse();
    }

    @Test
    void statusReportsIncludeOfflineRowsAndARoundSkipsTheRestAfterThreeFailures() {
        start(Map.of());
        env.connectDevice().closeWith(CloseCode.DEVICE_OFF.code());
        awaitLedger(ConnectionState.OFFLINE);

        awaitTrue(() -> env.core.requests("PUT", REPORT).stream().anyMatch(this::offlineReport), "OFFLINE 보고");
        RecordedRequest report = env.core.requests("PUT", REPORT).stream().filter(this::offlineReport).findFirst()
                .orElseThrow();
        assertThat(report.schemaErrors()).isEmpty();
        assertThat(report.path()).isEqualTo("/internal/devices/" + env.deviceId + "/connection");
        assertThat(report.json().get("reportedAt").asString()).isNotBlank();

        addLedgerRows(4);
        env.connectionRoute.respond(StubResponse.error(503, kr.mujoong.contract.error.ErrorCode.SERVICE_NOT_READY));
        StatusReporter.Round round = env.app.bean(StatusReporter.class).runOnce();
        assertThat(round).isEqualTo(new StatusReporter.Round(0, 3, 2));
        env.connectionRoute.respond(StubResponse.json(204, ""));
        assertThat(env.app.bean(StatusReporter.class).runOnce()).isEqualTo(new StatusReporter.Round(5, 0, 0));
    }

    @Test
    void whileNotReadyANewUpgradeIsAcceptedAndThenClosedWith1013() {
        start(Map.of("mujoong.health.db-check-interval", "1h"));
        ReadinessState readiness = env.app.bean(ReadinessState.class);
        for (int i = 0; i < 3; i++) {
            readiness.recordDbCheck(false);
        }

        DeviceSimulatorClient refused = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, env.deviceToken, WAIT);
        assertThat(refused.awaitClose(WAIT)).contains(CloseCode.TRY_AGAIN_LATER.code());
        assertThat(env.core.requests("POST", VERIFY)).isEmpty();

        readiness.recordDbCheck(true);
        assertThat(env.connectDevice().isClosed()).isFalse();
    }

    // ------------------------------------------------------------------ 보조

    private void start(Map<String, String> extra) {
        Map<String, String> args = new HashMap<>(PRODUCTION_TIMES);
        args.putAll(extra);
        env = RealtimeItEnvironment.start(args, null);
        logs = LogCapture.attach();
    }

    private void startWith(Map<String, String> extra, ManualTime time) {
        Map<String, String> args = new HashMap<>(PRODUCTION_TIMES);
        args.putAll(extra);
        env = RealtimeItEnvironment.startWith(args, time);
        logs = LogCapture.attach();
        assertThat(env.app.bean(MujoongClock.class)).isSameAs(time.clock);
    }

    /** 확인(verify-token)까지 끝나고 HELLO 타이머가 걸린 연결. */
    private DeviceSimulatorClient openVerified(ManualTime time) {
        int verified = env.core.requests("POST", VERIFY).size();
        int pending = time.safety.pendingCount();
        DeviceSimulatorClient device = DeviceSimulatorClient.open(env.app.deviceUri(), ORIGIN, env.deviceToken, WAIT);
        env.core.awaitRequests("POST", VERIFY, verified + 1, WAIT);
        awaitTrue(() -> time.safety.pendingCount() > pending, "HELLO 타이머");
        return device;
    }

    private DeviceConnection connection(UUID connectionId) {
        return env.app.bean(SocketRegistry.class).byConnectionId(connectionId).orElseThrow();
    }

    private void awaitLedger(ConnectionState state) {
        awaitTrue(() -> env.ledger().map(row -> row.state() == state).orElse(false), "원장 " + state);
    }

    /** DEVICE_CONNECTION 줄의 전이("from->to", 남긴 순서). 원인 분석의 탐침이 모은 것과 같은 자료다(J390 · J391). */
    private List<String> transitions() {
        return logs.events(EventCode.DEVICE_CONNECTION).stream().map(LogCapture::attrs)
                .map(attrs -> attrs.get("from") + "->" + attrs.get("to")).toList();
    }

    private boolean offlineReport(RecordedRequest request) {
        return request.body().contains("\"state\":\"OFFLINE\"");
    }

    /** 다른 기기의 원장 행(재시작 뒤 남은 행과 같은 모양)을 더한다. */
    private void addLedgerRows(int count) {
        Instant now = env.app.bean(MujoongClock.class).now();
        DeviceConnectionRepository repository = env.app.bean(DeviceConnectionRepository.class);
        new TransactionTemplate(env.app.bean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID deviceId = UUID.randomUUID();
                UUID connectionId = UUID.randomUUID();
                repository.handover(deviceId, connectionId, UUID.randomUUID(), UUID.randomUUID(), now);
                repository.markClosed(deviceId, connectionId, now);
            }
        });
        assertThat(repository.findAll()).hasSize(count + 1);
    }

    /** sentAt이 있는 HEARTBEAT 봉투를 패딩 필드로 정확히 {@code bytes}바이트로 만든다(모르는 필드는 무시되므로 크기만 문제다). */
    private static String heartbeatPaddedTo(int bytes) {
        String head = "{\"type\":\"HEARTBEAT\",\"seq\":50,\"sentAt\":\"" + Instant.now()
                + "\",\"payload\":{\"hbSeq\":50,\"lastRttMs\":10},\"pad\":\"";
        String tail = "\"}";
        String text = head + "x".repeat(bytes - head.length() - tail.length()) + tail;
        assertThat(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(bytes);
        Map<?, ?> parsed = JsonCodec.shared().read(text, Map.class);
        assertThat(parsed.containsKey("pad")).isTrue();
        return text;
    }
}
