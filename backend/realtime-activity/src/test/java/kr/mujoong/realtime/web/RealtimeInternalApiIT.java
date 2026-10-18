package kr.mujoong.realtime.web;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ActivitySessionRow;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.session.SessionChange;
import kr.mujoong.realtime.session.TombstoneMap;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.InternalApi.Reply;
import kr.mujoong.realtime.testing.InternalApi.Token;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Realtime 내부 API 시험 (U1 3.5 Step 35 · 계약 5절 C8 · 6절 C11 · J54 응답표 · J111 · 편차 (f)(n)(v)(w) · BR6.6 · BR6.7 · BR6.9 ·
 * BR6.15 · BR3.9 · BR5.4 · SD-8 · OD-7 · OD-8).
 *
 * <p>실제 서버(무작위 포트) · 실제 DB · Core 계약 대역 · 가짜 공급자 · 기기 시뮬레이터로 HTTP를 보내고 응답을 {@code contracts/schemas}로
 * 검사한다. 이 묶음은 시한이 시험 중에 발동하지 않게 기기 응답 · 종료 확인 · 끊김 시한을 운영 값으로 두고, DB 점검 주기를 길게 둬 준비 상태를
 * 시험이 직접 바꾼다(Retry-After = 점검 주기 초, J124).
 */
@Tag("u1")
@Tag("db")
class RealtimeInternalApiIT {

    private static final Map<String, String> ARGS = Map.of(
            "mujoong.timing.session.device-ack-timeout", "20s",
            "mujoong.timing.session.closing-confirm", "30s",
            "mujoong.timing.device.disconnect", "40s",
            "mujoong.health.db-check-interval", "1h",
            "mujoong.session.tombstone-ttl", "10m");
    private static final String SESSIONS = "/internal/sessions";

    private RealtimeItEnvironment env;
    private InternalApi api;
    private LogCapture logs;

    @BeforeEach
    void start() {
        env = RealtimeItEnvironment.start(ARGS, null);
        api = env.api();
        logs = LogCapture.attach();
    }

    @AfterEach
    void stop() {
        try {
            env.close();
        } finally {
            logs.close();
        }
    }

    @Test
    void startAccepts202PreparingAndTheSameIdAnswersItsCurrentStateWhileAnotherIdIsInProgress() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();

        Reply first = startSession(sessionId);
        assertThat(first.status()).isEqualTo(202);
        assertThat(first.json().get("status").asString()).isEqualTo("PREPARING");
        assertThat(InternalApi.schemaErrors("internal-session-start-response.schema.json", first.body())).isEmpty();
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(command.opId(), sessionId);
        awaitStatus(sessionId, SessionStatus.DEVICE_ACK);

        // BR6.6 ①: 같은 sessionId는 지금 상태로 202, ③: 다른 sessionId는 2차 방어 409
        assertThat(startSession(sessionId).json().get("status").asString()).isEqualTo("DEVICE_ACK");
        Reply other = startSession(UUID.randomUUID());
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.code()).isEqualTo("SESSION_IN_PROGRESS");
        assertThat(InternalApi.schemaErrors("error-envelope.schema.json", other.body())).isEmpty();
        // 패키지가 세션 행에 고정됐다(BR6.5): 아이 맥락 세 필드 · 동의 스냅샷
        ActivitySessionRow row = sessions().find(sessionId).orElseThrow();
        assertThat(row.childContext()).isEqualTo(new ChildContext("하늘", 8, List.of("공룡", "그림")));
        assertThat(row.consentSnapshot().policyVersion()).isEqualTo("1.0");
    }

    @Test
    void startWithoutALiveDeviceIs423AndTheErrorCarriesTheSameTraceIdAsTheHeader() {
        String traceId = "0123456789abcdef0123456789abcdef";
        Reply reply = api.send("POST", SESSIONS, body(UUID.randomUUID()), Token.VALID, traceId);

        assertThat(reply.status()).isEqualTo(423);
        assertThat(reply.code()).isEqualTo("DEVICE_NOT_CONNECTED");
        assertThat(reply.header("X-Trace-Id")).isEqualTo(traceId);
        assertThat(reply.json().get("traceId").asString()).isEqualTo(traceId);
        // 형식이 틀린 traceId는 이어 쓰지 않고 새로 만든다(OD-7)
        String forgedId = "EVIL-trace\" injected=1";
        Reply forged = api.send("POST", SESSIONS, body(UUID.randomUUID()), Token.VALID, forgedId);
        assertThat(forged.header("X-Trace-Id")).matches("[0-9a-f]{32}").isNotEqualTo(forgedId);
        assertThat(forged.json().get("traceId").asString()).isEqualTo(forged.header("X-Trace-Id"));
    }

    @Test
    void guardianStopFollowsTheJ54TableForEveryState() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        startSession(sessionId);
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);

        // PREPARING · DEVICE_ACK: 409 SESSION_NOT_ACTIVE
        assertNotActive(end(sessionId, "GUARDIAN_STOP"));
        device.ack(command.opId(), sessionId);
        awaitStatus(sessionId, SessionStatus.DEVICE_ACK);
        assertNotActive(end(sessionId, "GUARDIAN_STOP"));
        // ACTIVE: 202 {status: CLOSING}, CLOSING: 202 {status: CLOSING}(재처리 없음)
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitStatus(sessionId, SessionStatus.ACTIVE);
        Reply accepted = end(sessionId, "GUARDIAN_STOP");
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.json().get("status").asString()).isEqualTo("CLOSING");
        assertThat(InternalApi.schemaErrors("internal-session-end-response.schema.json", accepted.body())).isEmpty();
        Reply again = end(sessionId, "GUARDIAN_STOP");
        assertThat(again.status()).isEqualTo(202);
        assertThat(again.json().get("status").asString()).isEqualTo("CLOSING");
        assertThat(device.awaitCount(WsMessageType.STOP_OUTPUT, 1, WAIT)).hasSize(1);
        // 마무리 순서를 끝내 ENDED → 종료 상태는 409
        WsEnvelope closing = device.await(WsMessageType.PLAY_APPROVED_CONTENT, WAIT);
        device.playbackMark(sessionId, ((PlayApprovedContent) closing.payload()).playbackId(),
                PlaybackMark.Status.COMPLETED);
        device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        device.voiceSessionClosed(sessionId);
        awaitStatus(sessionId, SessionStatus.ENDED);
        assertNotActive(end(sessionId, "GUARDIAN_STOP"));
        assertThat(device.received(WsMessageType.STOP_OUTPUT)).hasSize(1);

        // 모르는 sessionId: 409, 묘비 없음(편차 (w) · J111)
        UUID unknown = UUID.randomUUID();
        assertNotActive(end(unknown, "GUARDIAN_STOP"));
        assertThat(env.app.bean(TombstoneMap.class).contains(unknown)).isFalse();
    }

    @Test
    void coreAbandonedClosesNonTerminalSessionsAndLeavesATombstoneForUnknownIds() {
        // (가) 모르는 id: 10분 묘비 + 202 {} → 같은 id의 늦은 시작은 409 SESSION_NOT_ACTIVE((v))
        UUID cancelled = UUID.randomUUID();
        Reply tombstone = end(cancelled, "CORE_ABANDONED");
        assertThat(tombstone.status()).isEqualTo(202);
        assertThat(tombstone.json().isEmpty()).isTrue();
        assertThat(InternalApi.schemaErrors("internal-session-end-response.schema.json", tombstone.body())).isEmpty();
        Reply late = startSession(cancelled);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("SESSION_NOT_ACTIVE");
        assertThat(sessions().find(cancelled)).isEmpty();

        // (나) PREPARING(실행기 있음) → FAILED(실패 코드 없음) + 202 {}
        DeviceSimulatorClient device = env.connectDevice();
        UUID preparing = UUID.randomUUID();
        startSession(preparing);
        device.await(WsMessageType.START_SESSION, WAIT);
        assertThat(end(preparing, "CORE_ABANDONED").status()).isEqualTo(202);
        awaitStatus(preparing, SessionStatus.FAILED);
        ActivitySessionRow failed = sessions().find(preparing).orElseThrow();
        assertThat(failed.failureCode()).isNull();
        // (라) 이미 종료: 202 {} 무동작
        Reply terminal = end(preparing, "CORE_ABANDONED");
        assertThat(terminal.status()).isEqualTo(202);
        assertThat(sessions().find(preparing).orElseThrow().statusVersion()).isEqualTo(failed.statusVersion());

        // 실행기 없는 ACTIVE 행: FAILED로 닫는다(BR6.7 (나))
        UUID orphanActive = dbOnlySession(SessionStatus.ACTIVE);
        assertThat(end(orphanActive, "CORE_ABANDONED").status()).isEqualTo(202);
        assertThat(sessions().find(orphanActive).orElseThrow().status()).isEqualTo(SessionStatus.FAILED);
        // (다) CLOSING(실행기 없는 행): 그대로 두고 202 {}
        // U4b(FR11.2): 고아 점검이 closing-confirm 뒤 이 행을 먼저 닫지 않게 단언 바로 직전에 만든다(순서만 바꿈, 단언은 그대로)
        UUID closing = dbOnlySession(SessionStatus.CLOSING);
        assertThat(end(closing, "CORE_ABANDONED").status()).isEqualTo(202);
        assertThat(sessions().find(closing).orElseThrow().status()).isEqualTo(SessionStatus.CLOSING);
        // 실행기 없는 CLOSING 행의 GUARDIAN_STOP은 202 {status: CLOSING}
        Reply closingStop = end(closing, "GUARDIAN_STOP");
        assertThat(closingStop.status()).isEqualTo(202);
        assertThat(closingStop.json().get("status").asString()).isEqualTo("CLOSING");
    }

    @Test
    void endRejectsUnknownReasonsAndMalformedRequestsWith400() {
        UUID sessionId = dbOnlySession(SessionStatus.ACTIVE);

        // U4b(FR1.4): CONSENT_WITHDRAWN은 응답표에 들어왔다(EndRequestU4bTest · SessionLifecycleU4bIT). 계약 열거의 알 수 없는 값으로 거절을 본다
        assertValidationFailed(end(sessionId, "UNRECOGNIZED"));
        assertValidationFailed(end(sessionId, "SOMETHING_ELSE"));
        assertValidationFailed(api.post(SESSIONS + "/" + sessionId + "/end", "{\"reason\":\"GUARDIAN_STOP\"}"));
        assertValidationFailed(api.post(SESSIONS + "/" + sessionId + "/end", "not json"));
        assertValidationFailed(api.post(SESSIONS + "/not-a-uuid/end",
                InternalApi.endBody(UUID.randomUUID(), "GUARDIAN_STOP")));
        // 거절된 요청은 세션을 바꾸지 않는다
        assertThat(sessions().find(sessionId).orElseThrow().status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void invalidStartPackagesAre400AndCreateNothing() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        String valid = body(sessionId);

        assertValidationFailed(startRaw(valid.replace("\"age\":8", "\"age\":13")));
        // U12(BR1.1): 연습(PRACTICE)은 이제 받는다(202는 PracticeStartPathIT). 알 수 없는 활동 종류와 계약 모양이 아닌 practice 조각은 그대로 400이다
        assertValidationFailed(startRaw(valid.replace("\"FREE_TALK\"", "\"WEBINAR\"")));
        assertValidationFailed(startRaw(valid.replace("\"FREE_TALK\"", "\"PRACTICE\"").replace("\"practice\":null",
                "\"practice\":{\"goals\":\"not-a-list\"}")));
        assertValidationFailed(startRaw(valid.replace("/media/approved/closing-v1.wav", "https://evil.invalid/x.wav")));
        assertValidationFailed(startRaw(valid.replace("\"WAITING_FILLER\":", "\"OTHER_CONTENT\":")));
        assertValidationFailed(startRaw(valid.replace("[{\"text\":\"무중아 그만할래\",\"isDefault\":true}]", "[]")));
        assertValidationFailed(startRaw(valid.replace("\"sessionId\":\"" + sessionId + "\",", "")));
        assertValidationFailed(startRaw("{\"sessionId\":"));

        assertThat(sessions().find(sessionId)).isEmpty();
        assertThat(device.received(WsMessageType.START_SESSION)).isEmpty();
        // 모르는 필드는 무시한다(계약 10절)
        Reply tolerant = startRaw(valid.replace("\"activityType\"", "\"futureField\":{\"x\":1},\"activityType\""));
        assertThat(tolerant.status()).isEqualTo(202);
    }

    @Test
    void viewReturnsTheSummaryVisibleItemsAndNextSeqOr404() {
        Reply missing = api.get(SESSIONS + "/" + UUID.randomUUID() + "/view?sinceSeq=0");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("NOT_FOUND");

        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        startSession(sessionId);
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);
        Reply preparing = api.get(SESSIONS + "/" + sessionId + "/view");
        assertThat(preparing.status()).isEqualTo(200);
        assertThat(InternalApi.schemaErrors("internal-session-view.schema.json", preparing.body())).isEmpty();
        JsonNode summary = preparing.json();
        assertThat(summary.get("status").asString()).isEqualTo("PREPARING");
        assertThat(summary.get("statusVersion").asInt()).isEqualTo(1);
        assertThat(summary.get("confirmed").asBoolean()).isFalse();
        assertThat(summary.get("items").isEmpty()).isTrue();
        assertThat(summary.get("nextSeq").asInt()).isZero();
        // 보호자 LiveView의 observedAt · stale은 Core가 채운다
        assertThat(summary.has("observedAt")).isFalse();
        assertThat(summary.has("stale")).isFalse();

        device.ack(command.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitStatus(sessionId, SessionStatus.ACTIVE);
        env.provider.emitTurn("item-1", "공룡이 좋아", "나도 공룡 좋아해", List.of(new byte[4800]));
        awaitTrue(() -> transcripts().findAll(sessionId).size() == 2
                && transcripts().findAll(sessionId).stream().allMatch(row -> row.isFinal()), "전사 두 행 FINAL");

        Reply turn = api.get(SESSIONS + "/" + sessionId + "/view?sinceSeq=0");
        assertThat(InternalApi.schemaErrors("internal-session-view.schema.json", turn.body())).isEmpty();
        JsonNode items = turn.json().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("speaker").asString()).isEqualTo("CHILD");
        assertThat(items.get(0).get("text").asString()).isEqualTo("공룡이 좋아");
        assertThat(items.get(1).get("speaker").asString()).isEqualTo("AI");
        assertThat(items.get(1).get("final").asBoolean()).isTrue();
        assertThat(turn.json().get("nextSeq").asInt()).isEqualTo(2);
        assertThat(turn.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(turn.json().get("startedAt").isNull()).isFalse();
        // 처음 실린 확정 행마다 SESSION_ITEMS_SERVED 한 줄(OD-8)
        awaitTrue(() -> logs.events(EventCode.SESSION_ITEMS_SERVED).size() == 2, "SESSION_ITEMS_SERVED 2줄");
        assertThat(LogCapture.attrs(logs.events(EventCode.SESSION_ITEMS_SERVED).get(0)))
                .containsKeys("tViewItemReady", "tViewItemFirstServed");
        api.get(SESSIONS + "/" + sessionId + "/view?sinceSeq=0");
        assertThat(logs.events(EventCode.SESSION_ITEMS_SERVED)).hasSize(2);

        // 확정 전 아이 조각(seq 3)은 실리되 nextSeq는 그 앞에 머문다(J23)
        upsertPartialChild(sessionId, 3, "그리고");
        Reply partial = api.get(SESSIONS + "/" + sessionId + "/view?sinceSeq=2");
        assertThat(partial.json().get("items")).hasSize(1);
        assertThat(partial.json().get("items").get(0).get("final").asBoolean()).isFalse();
        assertThat(partial.json().get("nextSeq").asInt()).isEqualTo(2);

        assertValidationFailed(api.get(SESSIONS + "/" + sessionId + "/view?sinceSeq=-1"));
        assertValidationFailed(api.get(SESSIONS + "/" + sessionId + "/view?sinceSeq=abc"));
    }

    @Test
    void missingWrongOrDuplicatedTokensAre401WithASecurityEventAndNoEffect() {
        UUID sessionId = UUID.randomUUID();
        for (Token mode : List.of(Token.MISSING, Token.WRONG, Token.DUPLICATED)) {
            Reply reply = api.post(SESSIONS, body(sessionId), mode);
            assertThat(reply.status()).isEqualTo(401);
            assertThat(reply.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(InternalApi.schemaErrors("error-envelope.schema.json", reply.body())).isEmpty();
        }
        assertThat(api.post("/internal/events", "{}", Token.MISSING).status()).isEqualTo(401);
        assertThat(api.send("GET", SESSIONS + "/" + sessionId + "/view", null, Token.WRONG, null).status())
                .isEqualTo(401);

        assertThat(sessions().find(sessionId)).isEmpty();
        Map<String, Object> event = logs.events(EventCode.SECURITY_INTERNAL_TOKEN_FAILED).getFirst();
        assertThat(LogCapture.attrs(event)).containsEntry("direction", "CORE_TO_REALTIME").containsEntry("statusCode", 401);
        assertThat(logs.lines()).noneMatch(line -> line.contains(env.internalToken));
    }

    @Test
    void bodiesOverTwoMegabytesAre400WithTheRouteCode() {
        String huge = "{\"pad\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}";

        Reply start = api.post(SESSIONS, huge);
        assertThat(start.status()).isEqualTo(400);
        assertThat(start.code()).isEqualTo("VALIDATION_FAILED");
        Reply event = api.post("/internal/events", huge);
        assertThat(event.status()).isEqualTo(400);
        assertThat(event.code()).isEqualTo("INVALID_ENVELOPE");
        // 입력값을 응답에 되비추지 않는다(SD-11)
        assertThat(start.body()).doesNotContain("aaaa");
    }

    @Test
    void notReadyAndDrainingAnswer503WithRetryAfterBeforeAuthentication() {
        ReadinessState readiness = env.app.bean(ReadinessState.class);
        for (int i = 0; i < 3; i++) {
            readiness.recordDbCheck(false);
        }
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.NOT_READY);

        Reply notReady = api.post(SESSIONS, body(UUID.randomUUID()), Token.MISSING);
        assertThat(notReady.status()).isEqualTo(503);
        assertThat(notReady.code()).isEqualTo("SERVICE_NOT_READY");
        assertThat(notReady.header("Retry-After")).isEqualTo("3600");
        assertThat(InternalApi.schemaErrors("error-envelope.schema.json", notReady.body())).isEmpty();
        assertThat(api.post("/internal/events", eventBody(UUID.randomUUID())).status()).isEqualTo(503);

        readiness.recordDbCheck(true);
        assertThat(api.post(SESSIONS, body(UUID.randomUUID()), Token.MISSING).status()).isEqualTo(401);

        readiness.markDraining();
        Reply draining = api.get(SESSIONS + "/" + UUID.randomUUID() + "/view");
        assertThat(draining.status()).isEqualTo(503);
        assertThat(draining.header("Retry-After")).isEqualTo("3600");
    }

    @Test
    void theEventsEndpointCommitsOnceAndReportsConflictsAndBadEnvelopes() {
        UUID eventId = UUID.randomUUID();
        String event = eventBody(eventId);

        Reply first = api.post("/internal/events", event);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).isEqualTo("{}");
        assertThat(api.post("/internal/events", event).status()).isEqualTo(200);
        Reply conflict = api.post("/internal/events", event.replace("REREGISTERED", "ACCOUNT_DELETED"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("EVENT_CONFLICT");
        Reply invalid = api.post("/internal/events", "{\"eventId\":\"" + UUID.randomUUID() + "\"}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("INVALID_ENVELOPE");

        assertThat(env.db.single("select count(*) from realtime.realtime_inbox_event where event_id = cast(:id as uuid)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", eventId.toString()),
                Long.class)).isEqualTo(1L);
    }

    @Test
    void pathsOutsideTheInternalChainAre404Envelopes() {
        Reply health = api.send("GET", "/api/health", null, Token.MISSING, null);
        assertThat(health.status()).isEqualTo(404);
        assertThat(health.code()).isEqualTo("NOT_FOUND");
        Reply unknown = api.get("/internal/nothing-here");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("NOT_FOUND");
        Reply wrongMethod = api.send("DELETE", SESSIONS, null, Token.VALID, null);
        assertThat(wrongMethod.status()).isEqualTo(404);
        assertThat(InternalApi.schemaErrors("error-envelope.schema.json", wrongMethod.body())).isEmpty();
    }

    // ------------------------------------------------------------------ 보조

    private Reply startSession(UUID sessionId) {
        return api.post(SESSIONS, body(sessionId));
    }

    private Reply startRaw(String json) {
        return api.post(SESSIONS, json);
    }

    private String body(UUID sessionId) {
        return InternalApi.startBody(sessionId, env.guardianId, env.childId, env.deviceId);
    }

    private Reply end(UUID sessionId, String reason) {
        return api.post(SESSIONS + "/" + sessionId + "/end", InternalApi.endBody(UUID.randomUUID(), reason));
    }

    private static void assertNotActive(Reply reply) {
        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("SESSION_NOT_ACTIVE");
    }

    private static void assertValidationFailed(Reply reply) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(InternalApi.schemaErrors("error-envelope.schema.json", reply.body())).isEmpty();
    }

    private ActivitySessionRepository sessions() {
        return env.app.bean(ActivitySessionRepository.class);
    }

    private TranscriptRepository transcripts() {
        return env.app.bean(TranscriptRepository.class);
    }

    private void awaitStatus(UUID sessionId, SessionStatus status) {
        awaitTrue(() -> sessions().find(sessionId).map(row -> row.status() == status).orElse(false),
                "세션 " + status);
    }

    /** 실행기 없이 DB에만 있는 세션(다른 보호자 · 기기, 재시작 뒤 남은 행과 같은 모양). */
    private UUID dbOnlySession(SessionStatus target) {
        UUID id = UUID.randomUUID();
        Instant now = env.app.bean(MujoongClock.class).now();
        transactions().executeWithoutResult(status -> {
            sessions().insert(new NewActivitySession(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                    new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("바다", 9, List.of()), null,
                    null), now);
            SessionStatus from = SessionStatus.PREPARING;
            for (SessionStatus next : List.of(SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE, SessionStatus.CLOSING)) {
                if (from == target) {
                    break;
                }
                sessions().transition(id, from, next, now, SessionChange.none());
                from = next;
            }
        });
        assertThat(sessions().find(id).orElseThrow().status()).isEqualTo(target);
        return id;
    }

    private void upsertPartialChild(UUID sessionId, int seq, String text) {
        Instant now = env.app.bean(MujoongClock.class).now();
        transactions().executeWithoutResult(status -> transcripts().upsertAll(
                List.of(TranscriptEntry.partial(sessionId, seq, TranscriptEntry.Speaker.CHILD, text, now)), now));
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(env.app.bean(PlatformTransactionManager.class));
    }

    /** 처리기가 있는 DeviceReleased 봉투(기기 연결이 없어 무해하다). */
    private String eventBody(UUID eventId) {
        return """
                {"eventId":"%s","eventType":"DeviceReleased","schemaVersion":1,"occurredAt":"2026-10-15T09:01:00Z",
                 "aggregateType":"Device","aggregateId":"%s","traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
                 "payload":{"deviceId":"%s","reason":"REREGISTERED","guardianId":"%s"}}
                """.formatted(eventId, env.deviceId, env.deviceId, env.guardianId);
    }
}
