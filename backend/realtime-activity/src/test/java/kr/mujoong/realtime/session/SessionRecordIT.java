package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * U4b 결과 입력 스냅숏 · SessionRecordCaptured 통합 시험 (L5 · FR7.1 · FR1.5 · BR8.1 · BR8.5 · BR8.8 · NFR8 · R-03).
 *
 * <p>종료 경로(보호자 끝내기 · 아이 종료 의도(미확인) · 동의 철회 · 공백 · DECLINED · 끊김 · 시작 실패 · 재시작/고아)마다 스냅숏 1건과 이벤트 1건이
 * 남고, 이벤트 페이로드가 계약 스키마(guardianId 포함)를 통과하며, 스냅숏의 해시와 같은지 본다. 정산 재무장과 멱등도 본다.
 */
@Tag("session-runtime")
@Tag("db")
@org.junit.jupiter.api.Tag("service-guardrail")
class SessionRecordIT {

    private static final String EVENTS = "/internal/events";

    private RealtimeItEnvironment env;

    private void start(Map<String, String> args) {
        Map<String, String> all = new HashMap<>(Map.of("mujoong.timing.session.join-wait", "1s",
                "mujoong.timing.session.join-wait-after-reprompt", "1s"));
        all.putAll(args);
        env = RealtimeItEnvironment.start(all, null);
    }

    @AfterEach
    void stop() {
        if (env != null) {
            env.close();
        }
    }

    @Test
    void aGuardianStopEndingCapturesOneSnapshotAndOneSchemaValidEvent() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);
        env.provider.emitTurn("item-1", "공룡이 좋아", "나도 공룡 좋아해", List.of(kr.mujoong.realtime.testing.RealtimeItEnvironment.audiblePcm(4800)));
        device.await(WsMessageType.OUTPUT_END, WAIT);
        awaitTrue(() -> transcriptCount(sessionId) >= 2, "대화 행");

        env.api().post("/internal/sessions/" + sessionId + "/end", InternalApi.endBody(UUID.randomUUID(), "GUARDIAN_STOP"));
        WsEnvelope closing = device.await(value -> sessionId.equals(value.sessionId()) && value.payload() instanceof PlayApprovedContent content
                && content.contentCode().equals(PlayApprovedContent.CLOSING), WAIT);
        device.playbackMark(sessionId, ((PlayApprovedContent) closing.payload()).playbackId(),
                PlaybackMark.Status.COMPLETED);
        device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        device.voiceSessionClosed(sessionId);

        JsonNode payload = awaitRecordEvent(sessionId);
        assertThat(payload.get("guardianId").asString()).as("R-03").isEqualTo(env.guardianId.toString());
        assertThat(payload.get("childId").asString()).isEqualTo(env.childId.toString());
        assertThat(payload.get("finalStatus").asString()).isEqualTo("ENDED");
        assertThat(payload.get("endReason").asString()).isEqualTo("GUARDIAN_STOP");
        assertThat(payload.get("confirmed").asBoolean()).isTrue();
        assertThat(payload.get("termination").get("transportClosedAt").isNull()).isFalse();
        assertThat(payload.get("record").get("utterances").size()).as("현재 실제 안내 · 아이 · AI · 마무리 인사").isEqualTo(4);
        assertThat(payload.get("record").get("practice").isNull()).isTrue();
        String hash = env.db.single("select snapshot_hash from realtime.realtime_result_input_snapshot"
                + " where session_id = cast(:id as uuid)", params(sessionId), String.class);
        assertThat(payload.get("snapshotHash").asString()).isEqualTo(hash);
        assertOneSnapshotAndOneEvent(sessionId);
    }

    @Test
    void aChildStopWithoutTheDeviceConfirmEndsUnconfirmedAndStillCaptures() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        env.childSays("stop-1", "무중아 그만할래");

        JsonNode payload = awaitRecordEvent(sessionId);
        assertThat(payload.get("finalStatus").asString()).isEqualTo("ENDED_UNCONFIRMED");
        assertThat(payload.get("endReason").asString()).isEqualTo("UNCONFIRMED");
        assertThat(payload.get("confirmed").asBoolean()).isFalse();
        assertThat(sessions().find(sessionId).orElseThrow().endDetail()).isEqualTo("CHILD_STOP");
        assertThat(payload.get("record").get("gaps").toString()).contains("UNCONFIRMED");
        assertOneSnapshotAndOneEvent(sessionId);
    }

    @Test
    void consentWithdrawnAndSilenceEndingsCaptureTheirReasons() {
        start(Map.of("mujoong.timing.session.silence-warning", "700ms", "mujoong.timing.session.silence-end", "1400ms",
                "mujoong.timing.device.disconnect", "20s"));
        DeviceSimulatorClient device = env.connectDevice();
        UUID silent = activate(device);
        awaitTrue(() -> {
            device.heartbeat(20L);
            return env.sessionStatus(silent) == SessionStatus.CLOSING;
        }, "공백 종료");
        assertThat(sessions().find(silent).orElseThrow().endReason()).isEqualTo(EndReason.SILENCE);
        JsonNode silence = awaitRecordEvent(silent);
        assertThat(silence.get("endReason").asString()).isEqualTo("UNCONFIRMED");
        assertThat(sessions().find(silent).orElseThrow().endDetail()).isEqualTo("SILENCE");

        // 미확인 종료가 기기를 UNRESPONSIVE로 둔다 — 새 연결로 다음 세션을 연다
        UUID withdrawn = activate(env.connectDevice());
        env.api().post("/internal/sessions/" + withdrawn + "/end",
                InternalApi.endBody(UUID.randomUUID(), "CONSENT_WITHDRAWN"));
        JsonNode consent = awaitRecordEvent(withdrawn);
        assertThat(sessions().find(withdrawn).orElseThrow().endDetail()).isEqualTo("CONSENT_WITHDRAWN");
        assertThat(consent.get("finalStatus").asString()).isEqualTo("ENDED_UNCONFIRMED");
        assertOneSnapshotAndOneEvent(silent);
        assertOneSnapshotAndOneEvent(withdrawn);
    }

    @Test
    void aDeclinedSessionCapturesAnEmptyRecordAfterTheFarewell() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = ready(device);
        env.completeNotice(sessionId);
        var reprompt = device.await(envelope -> envelope.payload() instanceof PlayApprovedContent play
                && SessionExecutor.RE_PROMPT.equals(play.contentCode()), WAIT);
        device.playbackMark(sessionId, ((PlayApprovedContent) reprompt.payload()).playbackId(), PlaybackMark.Status.COMPLETED);
        awaitTrue(() -> env.sessionStatus(sessionId) == SessionStatus.DECLINED, "DECLINED");
        WsEnvelope farewell = device.await(envelope -> envelope.payload() instanceof PlayApprovedContent play
                && PlayApprovedContent.CLOSING.equals(play.contentCode()), WAIT);
        device.playbackMark(sessionId, ((PlayApprovedContent) farewell.payload()).playbackId(),
                PlaybackMark.Status.COMPLETED);

        JsonNode payload = awaitRecordEvent(sessionId);
        assertThat(payload.get("finalStatus").asString()).isEqualTo("DECLINED");
        assertThat(payload.get("record").get("utterances").size()).isZero();
        assertThat(payload.get("record").get("audio").size()).isZero();
        assertThat(payload.hasNonNull("startedAt")).as("ACTIVE 전 종료").isFalse();
        assertOneSnapshotAndOneEvent(sessionId);
    }

    @Test
    void aDisconnectAbortAndAStartFailureCaptureToo() {
        start(Map.of());
        DeviceSimulatorClient device = env.connectDevice();
        UUID aborted = activate(device);
        // test 프로필 끊김 4초 동안 기기 메시지가 없다
        JsonNode abort = awaitRecordEvent(aborted);
        assertThat(abort.get("finalStatus").asString()).isEqualTo("ABORTED");
        assertThat(abort.get("endReason").asString()).isEqualTo("DISCONNECT");

        DeviceSimulatorClient again = env.connectDevice();
        UUID failed = UUID.randomUUID();
        env.start(failed);
        again.await(WsMessageType.START_SESSION, WAIT);
        // ACK 없이 시작 시한(2초)
        JsonNode start = awaitRecordEvent(failed);
        assertThat(start.get("finalStatus").asString()).isEqualTo("FAILED");
        assertThat(start.get("record").get("utterances").size()).isZero();
        assertOneSnapshotAndOneEvent(aborted);
        assertOneSnapshotAndOneEvent(failed);
    }

    @Test
    void anOrphanClosedAfterARestartIsCapturedByTheSettleRearmAndCaptureIsIdempotent() {
        start(Map.of());
        UUID orphan = dbOnlyActiveSession();

        JsonNode payload = awaitRecordEvent(orphan);
        assertThat(payload.get("finalStatus").asString()).isEqualTo("FAILED");
        assertThat(sessions().find(orphan).orElseThrow().endDetail()).isEqualTo("SERVER_RESTART");

        assertThat(env.app.bean(RecordCaptureService.class).capture(orphan, null,
                env.app.bean(MujoongClock.class).now())).as("세션당 한 번").isFalse();
        assertOneSnapshotAndOneEvent(orphan);
    }

    // ------------------------------------------------------------------ 도움

    private UUID ready(DeviceSimulatorClient device) {
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        return sessionId;
    }

    private UUID activate(DeviceSimulatorClient device) {
        UUID sessionId = ready(device);
        env.join(sessionId);
        return sessionId;
    }

    private UUID dbOnlyActiveSession() {
        UUID id = UUID.randomUUID();
        Instant now = env.app.bean(MujoongClock.class).now();
        new TransactionTemplate(env.app.bean(org.springframework.transaction.PlatformTransactionManager.class))
                .executeWithoutResult(status -> {
                    sessions().insert(new NewActivitySession(id, UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                            new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("바다", 9, List.of()),
                            null, null), now);
                    sessions().transition(id, SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, now,
                            SessionChange.none());
                    sessions().transition(id, SessionStatus.DEVICE_ACK, SessionStatus.WAITING_CHILD, now,
                            SessionChange.none());
                });
        return id;
    }

    private JsonNode awaitRecordEvent(UUID sessionId) {
        awaitTrue(() -> recordEvents(sessionId).size() == 1, "SessionRecordCaptured " + sessionId);
        JsonNode payload = recordEvents(sessionId).getFirst();
        assertThat(InternalApi.schemaErrors("event-payloads.schema.json#/$defs/SessionRecordCaptured",
                payload.toString())).isEmpty();
        return payload;
    }

    private List<JsonNode> recordEvents(UUID sessionId) {
        return env.core.requests("POST", EVENTS).stream().map(RecordedRequest::json)
                .filter(event -> "SessionRecordCaptured".equals(event.get("eventType").asString()))
                .map(event -> event.get("payload"))
                .filter(payload -> sessionId.toString().equals(payload.get("sessionId").asString())).toList();
    }

    private void assertOneSnapshotAndOneEvent(UUID sessionId) {
        RealtimeItEnvironment.sleep(300);
        assertThat(env.db.single("select count(*) from realtime.realtime_result_input_snapshot"
                + " where session_id = cast(:id as uuid)", params(sessionId), Long.class)).isEqualTo(1L);
        assertThat(env.db.single("select count(*) from realtime.realtime_outbox_event"
                + " where event_type = 'SessionRecordCaptured' and aggregate_id = cast(:id as uuid)", params(sessionId),
                Long.class)).isEqualTo(1L);
        assertThat(recordEvents(sessionId)).hasSize(1);
    }

    private long transcriptCount(UUID sessionId) {
        return env.db.single("select count(*) from realtime.realtime_transcript where session_id = cast(:id as uuid)",
                params(sessionId), Long.class);
    }

    private ActivitySessionRepository sessions() {
        return env.app.bean(ActivitySessionRepository.class);
    }

    private static MapSqlParameterSource params(UUID sessionId) {
        return new MapSqlParameterSource("id", sessionId.toString());
    }
}
