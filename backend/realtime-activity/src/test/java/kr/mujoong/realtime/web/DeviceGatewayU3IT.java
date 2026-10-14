package kr.mujoong.realtime.web;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.HelloOk;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.gateway.DeviceConnection;
import kr.mujoong.realtime.gateway.DeviceDisconnectRepository;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.EndRequestHandler;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionEventRepository;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.session.SessionViewQuery;
import kr.mujoong.realtime.testing.ManualTime;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 기기 WSS 끝점의 U3 시험(design.md 4 · 5절 · BR4.8 ① · U3-R1 · U3-J2 ~ J10 · U3-J18 · AC2.1.4 · AC2.2.5 · AC6.2.4 · AC3.2.10(b)).
 *
 * <p>실제 서버 · DB · Core 대역 · 가짜 공급자 · 기기 시뮬레이터로 불안정 판정(왕복 · 5분 안 2회 끊김), HELLO 이어가기(세션 유지 · 끊김 구간 ·
 * 새 연결의 입력 중계), 이어가기 없는 재접속(START_SESSION 재송신), 미확인 명령 재전달, DEVICE_STATE · PROGRESS 처리와 끼어들기를 본다. 세션
 * 시한은 재접속이 시한과 섞이지 않게 운영값으로 둔다.
 */
@Tag("u3")
@Tag("db")
class DeviceGatewayU3IT {

    private static final Map<String, String> PRODUCTION_SESSION_TIMES = Map.of(
            "mujoong.timing.session.device-ack-timeout", "20s",
            "mujoong.timing.session.closing-confirm", "30s",
            "mujoong.timing.device.disconnect", "40s");
    private static final String EVENTS = "/internal/events";
    private static final String REPORT = "/internal/devices/{deviceId}/connection";
    private static final byte[] PCM = new byte[48_000];

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
    void aSlowHeartbeatRoundTripMarksTheLedgerUnstableAndANormalOneRecovers() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID connectionId = env.ledger().orElseThrow().connectionId();

        device.heartbeat(1_500L);
        awaitLedger(ConnectionState.UNSTABLE);
        awaitTrue(() -> connection(connectionId).knownState() == ConnectionState.UNSTABLE, "사본 UNSTABLE");
        awaitTrue(() -> env.core.requests("POST", EVENTS).stream().anyMatch(request ->
                request.body().contains("\"state\":\"UNSTABLE\"")), "DeviceStatusChanged UNSTABLE");

        // 기준(1초)과 같은 값은 정상 왕복이다
        device.heartbeat(1_001L);
        RealtimeItEnvironment.sleep(300);
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.UNSTABLE);
        device.heartbeat(1_000L);
        awaitLedger(ConnectionState.CONNECTED);
        awaitTrue(() -> connection(connectionId).knownState() == ConnectionState.CONNECTED, "사본 CONNECTED");
        // 전이 로그는 커밋 뒤에 쓰인다(사본은 커밋 전) — 로그가 나올 때까지 기다린 뒤 순서를 본다
        awaitTrue(() -> transitions().size() >= 3, "UNSTABLE->CONNECTED 로그");
        assertThat(transitions()).containsExactly("OFFLINE->CONNECTED", "CONNECTED->UNSTABLE", "UNSTABLE->CONNECTED");
        assertThat(device.isClosed()).isFalse();
    }

    @Test
    void twoDropsInsideTheWindowMakeTheReconnectUnstableUntilTheWindowHasPassed() {
        ManualTime time = new ManualTime();
        // 원장 무음 점검이 시계 앞당김에 걸리지 않게 끊김 시한은 넉넉히 둔다(이 시험은 끊김 횟수만 본다)
        startWith(Map.of("mujoong.timing.device.disconnect", "1h"), time);
        DeviceSimulatorClient first = env.connectDevice();
        // 의도된 닫기(「끄기」 1000 · 페이지 이탈 1001)는 네트워크 끊김이 아니다(U3-J2)
        first.closeWith(CloseCode.DEVICE_OFF.code());
        awaitLedger(ConnectionState.OFFLINE);
        DeviceSimulatorClient leaving = env.connectDevice();
        leaving.closeWith(CloseCode.GOING_AWAY.code());
        awaitLedger(ConnectionState.OFFLINE);
        RealtimeItEnvironment.sleep(300);
        assertThat(disconnects()).isEmpty();

        DeviceSimulatorClient dropped = env.connectDevice();
        dropped.close();
        awaitTrue(() -> disconnects().size() == 1, "끊김 사건 1(TCP 끊김)");
        assertThat(disconnects().get(0).kind()).isEqualTo(DeviceDisconnectRepository.Kind.CLOSED);
        assertThat(disconnects().get(0).closeCode()).isIn(CloseCode.ABNORMAL_CLOSURE.code(), CloseCode.SERVER_ERROR.code());
        // 끊김 한 번으로는 불안정이 아니다(TCP만 끊긴 연결의 원장은 40초 점검이 정한다)
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);

        time.advance(Duration.ofSeconds(30));
        DeviceSimulatorClient second = env.connectDevice();
        assertThat(env.ledger().orElseThrow().recentDisconnects()).isEqualTo(1);
        second.close();
        awaitTrue(() -> disconnects().size() == 2, "끊김 사건 2(TCP 끊김)");
        // 두 번째 끊김이 창 안이면 남은 원장 행이 바로 UNSTABLE이 된다
        awaitLedger(ConnectionState.UNSTABLE);

        time.advance(Duration.ofSeconds(30));
        RealtimeItEnvironment.Connected third = env.connectDevice(null);
        assertThat(third.helloOk().session()).isNull();
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.UNSTABLE);
        assertThat(env.ledger().orElseThrow().recentDisconnects()).isEqualTo(2);
        assertThat(connection(third.helloOk().connectionId()).knownState()).isEqualTo(ConnectionState.UNSTABLE);
        awaitTrue(() -> env.core.requests("PUT", REPORT).stream().anyMatch(request ->
                request.body().contains("\"state\":\"UNSTABLE\"") && request.body().contains("\"recentDisconnects\":2")),
                "UNSTABLE 상태 보고");
        // 창 안에서는 정상 왕복이 와도 그대로다
        third.device().heartbeat(50L);
        RealtimeItEnvironment.sleep(300);
        assertThat(env.ledger().orElseThrow().state()).isEqualTo(ConnectionState.UNSTABLE);

        time.advance(Duration.ofMinutes(5));
        third.device().heartbeat(50L);
        awaitLedger(ConnectionState.CONNECTED);
        assertThat(env.ledger().orElseThrow().recentDisconnects()).isZero();
        awaitTrue(() -> disconnects().isEmpty(), "창 밖 사건 정리");
        assertThat(third.device().isClosed()).isFalse();
    }

    @Test
    void aReconnectWithResumeKeepsTheActiveSessionClosesTheGapAndRelaysTheNewConnectionsAudio() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);
        int statusVersion = session(sessionId).statusVersion();

        device.close();
        awaitTrue(() -> gaps(sessionId).size() == 1 && gaps(sessionId).get(0).isOpen(), "끊김 구간 열림");

        RealtimeItEnvironment.Connected again = env.connectDevice(sessionId);
        assertThat(again.helloOk().session()).isEqualTo(new HelloOk.SessionInfo(sessionId, View.LISTENING, true));
        again.device().await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING,
                WAIT);
        awaitTrue(() -> gaps(sessionId).size() == 1 && !gaps(sessionId).get(0).isOpen(), "끊김 구간 닫힘");
        RealtimeItEnvironment.sleep(200);
        assertThat(again.device().received(WsMessageType.START_SESSION)).as("이어가기에는 START_SESSION이 없다").isEmpty();
        assertThat(session(sessionId).status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session(sessionId).statusVersion()).isEqualTo(statusVersion);

        // 새 연결의 입력 프레임이 공급자로 간다
        int appended = env.provider.received("session.input_audio.append").size();
        for (int i = 0; i < 3; i++) {
            again.device().sendAudio(voice());
        }
        assertThat(env.provider.await("session.input_audio.append", appended + 3, WAIT)).hasSizeGreaterThanOrEqualTo(appended + 3);

        SessionViewQuery.View view = env.app.bean(SessionViewQuery.class).view(sessionId, 0).orElseThrow();
        assertThat(view.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.kind()).isEqualTo(SessionGapRepository.Kind.DISCONNECT);
            assertThat(gap.endedAt()).isAfterOrEqualTo(gap.startedAt());
        });
        assertThat(sessionEvents(sessionId)).extracting(SessionEvent::getEventType).contains("DEVICE_LINK_REPLACED");
    }

    @Test
    void aReconnectWithoutResumeGetsStartSessionAgainWithTheSameOpIdAndItsLateReadyDoesNotMoveTheSession() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitStatus(sessionId, SessionStatus.ACTIVE);
        int statusVersion = session(sessionId).statusVersion();

        device.close();
        RealtimeItEnvironment.Connected fresh = env.connectDevice(null);
        assertThat(fresh.helloOk().session()).isEqualTo(new HelloOk.SessionInfo(sessionId, View.LISTENING, false));
        WsEnvelope resent = fresh.device().await(WsMessageType.START_SESSION, WAIT);
        assertThat(resent.opId()).isEqualTo(start.opId());
        assertThat(resent.sessionId()).isEqualTo(sessionId);
        fresh.device().await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING,
                WAIT);

        fresh.device().ack(resent.opId(), sessionId);
        fresh.device().deviceReady(sessionId, true, true);
        RealtimeItEnvironment.sleep(300);
        assertThat(session(sessionId).status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session(sessionId).statusVersion()).isEqualTo(statusVersion);
        assertThat(fresh.device().received(WsMessageType.ERROR)).isEmpty();
    }

    @Test
    void anUnackedClosingPlaybackIsResentOnTheNewLinkAndTheSessionEndsConfirmed() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        assertThat(end(sessionId)).isEqualTo(EndOutcome.CLOSING);
        WsEnvelope play = device.await(WsMessageType.PLAY_APPROVED_CONTENT, WAIT);
        device.await(WsMessageType.STOP_OUTPUT, WAIT);
        // ACK 없이 끊긴다
        device.close();

        RealtimeItEnvironment.Connected again = env.connectDevice(sessionId);
        assertThat(again.helloOk().session()).isEqualTo(new HelloOk.SessionInfo(sessionId, View.CLOSING, true));
        WsEnvelope replay = again.device().await(WsMessageType.PLAY_APPROVED_CONTENT, WAIT);
        assertThat(replay.opId()).isEqualTo(play.opId());
        assertThat(((PlayApprovedContent) replay.payload()).playbackId())
                .isEqualTo(((PlayApprovedContent) play.payload()).playbackId());
        again.device().await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.CLOSING,
                WAIT);
        RealtimeItEnvironment.sleep(200);
        assertThat(again.device().received(WsMessageType.STOP_OUTPUT)).as("낡은 STOP_OUTPUT은 다시 보내지 않는다").isEmpty();

        again.device().ack(replay.opId(), sessionId);
        again.device().playbackMark(sessionId, ((PlayApprovedContent) replay.payload()).playbackId(),
                PlaybackMark.Status.COMPLETED);
        WsEnvelope close = again.device().await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        assertThat(((CloseTransport) close.payload()).reason()).isEqualTo(CloseTransport.Reason.GUARDIAN_STOP);
        again.device().voiceSessionClosed(sessionId);
        awaitStatus(sessionId, SessionStatus.ENDED);
        assertThat(session(sessionId).confirmed()).isTrue();
        assertThat(again.device().received(WsMessageType.PLAY_APPROVED_CONTENT)).hasSize(1);
    }

    @Test
    void deviceStateAndProgressMarksAreProcessedAndALocalVoiceStopIsRecordedAsABargeIn() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);
        RealtimeMetrics metrics = env.app.bean(RealtimeMetrics.class);

        device.deviceState(new DeviceState(DeviceState.Mic.LOST, null, null, null));
        awaitTrue(() -> sessionEvents(sessionId).stream().anyMatch(event -> event.getEventType().equals("DEVICE_MIC_LOST")),
                "DEVICE_MIC_LOST");
        assertThat(metrics.count(RealtimeMetrics.DEVICE_STATE, "field", "mic", "value", "lost")).isEqualTo(1.0);
        device.deviceState(new DeviceState(DeviceState.Mic.OK, DeviceState.Player.OK, null, DeviceState.WakeLock.RELEASED));
        awaitTrue(() -> sessionEvents(sessionId).stream().anyMatch(event -> event.getEventType().equals("DEVICE_MIC_OK")),
                "DEVICE_MIC_OK");
        assertThat(metrics.count(RealtimeMetrics.DEVICE_STATE, "field", "wake_lock", "value", "released")).isEqualTo(1.0);
        UUID connectionId = env.ledger().orElseThrow().connectionId();
        assertThat(connection(connectionId).knownDeviceState()).isEqualTo(new DeviceState(DeviceState.Mic.OK,
                DeviceState.Player.OK, null, DeviceState.WakeLock.RELEASED));

        env.provider.emitTurn("item-1", "공룡 이야기", "resp-1", "공룡은 아주 오래전에 살았어", List.of(PCM));
        WsEnvelope begin = device.await(WsMessageType.OUTPUT_BEGIN, WAIT);
        UUID playbackId = ((OutputBegin) begin.payload()).playbackId();
        device.playbackMark(sessionId, playbackId, PlaybackMark.Status.STARTED);
        device.playbackMark(sessionId, playbackId, PlaybackMark.Status.PROGRESS, new PlaybackMark.PlayedRange(0L, 500L), null);
        device.playbackMark(sessionId, playbackId, PlaybackMark.Status.STOPPED, null,
                PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);

        awaitTrue(() -> sessionEvents(sessionId).stream().anyMatch(event -> event.getEventType().equals("BARGE_IN")),
                "BARGE_IN");
        SessionEvent bargeIn = sessionEvents(sessionId).stream().filter(event -> event.getEventType().equals("BARGE_IN"))
                .findFirst().orElseThrow();
        assertThat(bargeIn.getPayloadJson().replace(" ", "")).contains("\"playbackId\":\"" + playbackId + "\"")
                .contains("\"playedToMs\":500");
        assertThat(logs.events(EventCode.PLAYBACK_MARK_STOPPED)).singleElement().satisfies(event ->
                assertThat(LogCapture.attrs(event)).containsEntry("stopCause", "LOCAL_VOICE_ACTIVITY"));
        assertThat(device.received(WsMessageType.ERROR)).isEmpty();
        assertThat(device.isClosed()).isFalse();
        assertThat(session(sessionId).status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(logs.lines()).noneMatch(line -> line.contains("공룡"));
    }

    // ------------------------------------------------------------------ 보조

    private void start(Map<String, String> extra) {
        Map<String, String> args = new HashMap<>(PRODUCTION_SESSION_TIMES);
        args.putAll(extra);
        env = RealtimeItEnvironment.start(args, null);
        logs = LogCapture.attach();
    }

    private void startWith(Map<String, String> extra, ManualTime time) {
        Map<String, String> args = new HashMap<>(PRODUCTION_SESSION_TIMES);
        args.putAll(extra);
        env = RealtimeItEnvironment.startWith(args, time);
        logs = LogCapture.attach();
        assertThat(env.app.bean(MujoongClock.class)).isSameAs(time.clock);
    }

    private UUID activate(DeviceSimulatorClient device) {
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitStatus(sessionId, SessionStatus.ACTIVE);
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING, WAIT);
        return sessionId;
    }

    private EndOutcome end(UUID sessionId) {
        return env.app.bean(EndRequestHandler.class).end(sessionId, UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, null);
    }

    private kr.mujoong.realtime.session.ActivitySessionRow session(UUID sessionId) {
        return env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow();
    }

    private void awaitStatus(UUID sessionId, SessionStatus status) {
        awaitTrue(() -> env.app.bean(ActivitySessionRepository.class).find(sessionId)
                .map(row -> row.status() == status).orElse(false), "세션 " + status);
    }

    private void awaitLedger(ConnectionState state) {
        awaitTrue(() -> env.ledger().map(row -> row.state() == state).orElse(false), "원장 " + state);
    }

    private DeviceConnection connection(UUID connectionId) {
        return env.app.bean(SocketRegistry.class).byConnectionId(connectionId).orElseThrow();
    }

    private List<DeviceDisconnectRepository.DisconnectEvent> disconnects() {
        return env.app.bean(DeviceDisconnectRepository.class).findByDevice(env.deviceId);
    }

    private List<SessionGapRepository.SessionGapRow> gaps(UUID sessionId) {
        return env.app.bean(SessionGapRepository.class).findBySession(sessionId);
    }

    private List<SessionEvent> sessionEvents(UUID sessionId) {
        return env.app.bean(SessionEventRepository.class).findBySession(sessionId);
    }

    private List<String> transitions() {
        return logs.events(EventCode.DEVICE_CONNECTION).stream().map(LogCapture::attrs)
                .map(attrs -> attrs.get("from") + "->" + attrs.get("to")).toList();
    }

    /** 음성 활동으로 보이는 입력 프레임(평균 절대 진폭이 임계값 위). */
    private static byte[] voice() {
        byte[] pcm = new byte[960];
        for (int i = 0; i < pcm.length; i += 2) {
            pcm[i] = (byte) 0xE8;
            pcm[i + 1] = 0x03;
        }
        return pcm;
    }
}
