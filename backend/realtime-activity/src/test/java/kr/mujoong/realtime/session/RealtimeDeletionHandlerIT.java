package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.event.payload.DeletionRequested;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.messaging.InboxEventRow;
import kr.mujoong.realtime.messaging.RealtimeInboxReceiver;
import kr.mujoong.realtime.messaging.RealtimeInboxRepository;
import kr.mujoong.realtime.recording.FileAudioRecorder;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Realtime 계정 삭제 처리기 통합 시험 (U4b L6 · FR8.1 · BR9.1 ~ BR9.5 · NFR9 · R-12).
 *
 * <p>실제 DB · 원음 디렉터리 · 기기 시뮬레이터 · 가짜 공급자로 DeletionRequested를 Inbox에 넣어, 진행 세션이 있을 때와 없을 때 그 보호자의 Realtime 행 ·
 * 원음 파일이 0건이 되고, DeletionCompleted{counts}가 한 번 나가며, 재시도 · 파일 삭제 실패 · 늦은 사건이 결과를 바꾸지 않는지 본다.
 */
@Tag("u4b")
@Tag("db")
class RealtimeDeletionHandlerIT {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final List<String> SESSION_TABLES = List.of("realtime_result_input_snapshot",
            "realtime_session_event", "realtime_session_stop_expression", "realtime_transcript",
            "realtime_audio_recording", "realtime_session_gap");

    @TempDir
    Path recordings;

    private RealtimeItEnvironment env;

    @BeforeEach
    void start() {
        // 파일 삭제 실패 뒤 재시도를 볼 수 있게 Inbox 재시도 간격만 늘린다(5회 안에 끝난다)
        env = RealtimeItEnvironment.start(Map.of("mujoong.recording.directory", recordings.toString(),
                "mujoong.timing.session.join-wait", "1s", "mujoong.timing.session.join-wait-after-reprompt", "1s",
                "mujoong.inbox.retry-backoff", "700ms,700ms,700ms,700ms,700ms"), null);
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void withoutALiveSessionEverythingOfTheGuardianIsGoneAndCountsArePublishedOnce() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device, true);
        device.sendAudio(new byte[960]);
        env.provider.emitTurn("item-1", "공룡이 좋아", "나도 공룡 좋아해", List.of(new byte[4800]));
        device.await(WsMessageType.OUTPUT_END, WAIT);
        endConfirmed(device, sessionId);
        awaitTrue(() -> count("realtime_result_input_snapshot", sessionId) == 1, "스냅숏");
        awaitTrue(() -> filesUnder(sessionId) > 0, "원음 파일");

        UUID requestId = deletionRequested();

        JsonNode completed = awaitDeletionCompleted(requestId);
        assertThat(completed.get("guardianId").asString()).isEqualTo(env.guardianId.toString());
        JsonNode counts = completed.get("counts");
        assertThat(counts.get("sessions").asInt()).isEqualTo(1);
        assertThat(counts.get("transcripts").asInt()).isPositive();
        assertThat(counts.get("audioFiles").asInt()).isEqualTo(2);
        assertThat(counts.get("practiceRecords").asInt()).isZero();
        assertGuardianDataGone(sessionId);
        // BR9.5: 발행된 DeletionCompleted 행도 메시징 참가자가 한 번 더 불려 지운다
        awaitTrue(() -> guardianOutboxRows() == 0, "발행 뒤 메시징 정리");
    }

    @Test
    void aLiveSessionIsStoppedWithoutTransitionOrSnapshotThenErased() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device, false);
        int statusVersion = env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow().statusVersion();

        UUID requestId = deletionRequested();

        WsEnvelope close = device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        assertThat(((CloseTransport) close.payload()).reason()).isEqualTo(CloseTransport.Reason.CONSENT_WITHDRAWN);
        awaitDeletionCompleted(requestId);
        assertGuardianDataGone(sessionId);
        assertThat(recordEvents()).as("강제 정지는 스냅숏 · 이벤트를 만들지 않는다").isEmpty();
        assertThat(statusVersion).isEqualTo(4);
        awaitTrue(() -> env.app.bean(SessionRegistry.class).executor(sessionId).isEmpty(), "실행기 정리");

        // 늦은 사건(기기 표식 · HEARTBEAT · 상태)은 지운 기록을 되살리지 않는다(공급자 연결은 강제 정지가 닫았다)
        assertThat(env.provider.openConnections()).isZero();
        device.playbackMark(sessionId, UUID.randomUUID(), PlaybackMark.Status.COMPLETED);
        device.deviceState();
        device.heartbeat(20L);
        RealtimeItEnvironment.sleep(300);
        assertThat(count("realtime_transcript", sessionId)).isZero();
        assertThat(count("realtime_session_event", sessionId)).isZero();
    }

    @Test
    void aRetriedRequestIsIdempotentAndWritesTheCompletionOnlyOnce() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device, false);
        endConfirmed(device, sessionId);
        awaitTrue(() -> count("realtime_result_input_snapshot", sessionId) == 1, "스냅숏");
        UUID requestId = UUID.randomUUID();
        Instant now = Instant.now();
        String payload = JSON.write(new DeletionRequested(requestId, env.guardianId, env.childId, now));
        InboxEventRow row = new InboxEventRow(UUID.randomUUID(), "CORE", "DeletionRequested", 1, now, requestId,
                env.guardianId, payload, "0".repeat(64), InboxEventRow.Status.RECEIVED, 0, now, now, null, null, null,
                null);
        DeletionRequestedHandler handler = env.app.bean(DeletionRequestedHandler.class);

        // 처리기 재호출(재시도)을 한 트랜잭션에서 두 번 — 두 번째는 0건이고 완료 이벤트를 다시 쓰지 않는다(BR9.4)
        Long written = new TransactionTemplate(env.app.bean(PlatformTransactionManager.class)).execute(status -> {
            handler.handle(row, now);
            handler.handle(row, now);
            // 같은 트랜잭션의 앱 연결로 센다(커밋 전 행)
            return env.app.bean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)
                    .queryForObject("select count(*) from realtime.realtime_outbox_event"
                            + " where event_type = 'DeletionCompleted' and aggregate_id = cast(:id as uuid)",
                            new MapSqlParameterSource("id", requestId.toString()), Long.class);
        });

        assertThat(written).isEqualTo(1L);
        awaitTrue(() -> deletionCompletedEvents(requestId).size() == 1, "DeletionCompleted 한 번");
        RealtimeItEnvironment.sleep(300);
        assertThat(deletionCompletedEvents(requestId)).hasSize(1);
        assertThat(deletionCompletedEvents(requestId).getFirst().get("counts").get("sessions").asInt()).isEqualTo(1);
        assertGuardianDataGone(sessionId);
    }

    @Test
    void aFileThatCannotBeDeletedFailsTheAttemptAndTheRetryFinishesWithTheFullCount() throws IOException {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device, true);
        device.sendAudio(new byte[960]);
        env.provider.emitTurn("item-1", "공룡이 좋아", "나도 공룡 좋아해", List.of(new byte[4800]));
        device.await(WsMessageType.OUTPUT_END, WAIT);
        endConfirmed(device, sessionId);
        awaitTrue(() -> filesUnder(sessionId) == 2, "원음 파일 둘");
        awaitTrue(() -> count("realtime_result_input_snapshot", sessionId) == 1, "스냅숏");
        // 아이 원음 파일 자리를 비지 않은 디렉터리로 바꿔 지우지 못하게 한다
        Path child = env.app.bean(FileAudioRecorder.class).resolve(sessionId + "/child.pcm");
        Files.deleteIfExists(child);
        Files.createDirectories(child);
        Path blocker = Files.writeString(child.resolve("blocker"), "x");

        UUID requestId = deletionRequested();
        awaitTrue(() -> env.app.bean(RealtimeInboxRepository.class).find(lastEventId)
                .map(row -> row.retryCount() > 0).orElse(false), "재시도 예약");
        assertThat(deletionCompletedEvents(requestId)).isEmpty();
        assertThat(count("realtime_activity_session", sessionId)).as("실패한 시도는 되돌린다").isEqualTo(1);

        Files.delete(blocker);
        JsonNode completed = awaitDeletionCompleted(requestId);

        assertThat(completed.get("counts").get("audioFiles").asInt()).as("앞 시도에서 지운 파일도 센다(R-12)").isEqualTo(2);
        assertGuardianDataGone(sessionId);
    }

    // ------------------------------------------------------------------ 도움

    private UUID lastEventId;

    private UUID deletionRequested() {
        UUID requestId = UUID.randomUUID();
        lastEventId = UUID.randomUUID();
        String body = JSON.encodeEventEnvelope(JSON.envelopeFor(lastEventId,
                new DeletionRequested(requestId, env.guardianId, env.childId, Instant.now()), Instant.now(),
                "0123456789abcdef0123456789abcdef"));
        RealtimeInboxReceiver.Result result = env.app.bean(RealtimeInboxReceiver.class).receive(body);
        assertThat(result.outcome()).isEqualTo(RealtimeInboxReceiver.Outcome.ACCEPTED);
        return requestId;
    }

    private UUID activate(DeviceSimulatorClient device, boolean rawAudio) {
        UUID sessionId = UUID.randomUUID();
        StartSessionHandler.StartCommand base = env.startCommand(sessionId, new ChildContext("하늘", 8, List.of("공룡")));
        StartSessionHandler.StartCommand command = new StartSessionHandler.StartCommand(sessionId, base.idempotencyKey(),
                base.guardianId(), base.childId(), base.deviceId(), base.activityType(),
                new ConsentSnapshot("1.0", true, true, false, rawAudio), base.childContext(), base.stopExpressions(),
                new LinkedHashMap<>(base.approvedContent()));
        env.app.bean(StartSessionHandler.class).start(command, "0123456789abcdef0123456789abcdef");
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        return sessionId;
    }

    private void endConfirmed(DeviceSimulatorClient device, UUID sessionId) {
        env.api().post("/internal/sessions/" + sessionId + "/end", InternalApi.endBody(UUID.randomUUID(), "GUARDIAN_STOP"));
        WsEnvelope closing = device.await(envelope -> envelope.payload() instanceof PlayApprovedContent play
                && PlayApprovedContent.CLOSING.equals(play.contentCode()), WAIT);
        device.playbackMark(sessionId, ((PlayApprovedContent) closing.payload()).playbackId(),
                PlaybackMark.Status.COMPLETED);
        device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        device.voiceSessionClosed(sessionId);
        awaitTrue(() -> env.sessionStatus(sessionId) == SessionStatus.ENDED, "ENDED");
    }

    private JsonNode awaitDeletionCompleted(UUID requestId) {
        awaitTrue(() -> deletionCompletedEvents(requestId).size() == 1, "DeletionCompleted");
        JsonNode payload = deletionCompletedEvents(requestId).getFirst();
        assertThat(InternalApi.schemaErrors("event-payloads.schema.json#/$defs/DeletionCompleted", payload.toString()))
                .isEmpty();
        // PROCESSED가 된 Inbox 행은 발행 뒤 메시징 참가자가 지운다(BR5.14) — 처리됐거나 이미 지워졌으면 된다
        awaitTrue(() -> env.app.bean(RealtimeInboxRepository.class).find(lastEventId)
                .map(row -> row.status() == InboxEventRow.Status.PROCESSED).orElse(true), "PROCESSED");
        return payload;
    }

    private List<JsonNode> deletionCompletedEvents(UUID requestId) {
        return env.core.requests("POST", "/internal/events").stream().map(RecordedRequest::json)
                .filter(event -> "DeletionCompleted".equals(event.get("eventType").asString()))
                .map(event -> event.get("payload"))
                .filter(payload -> requestId.toString().equals(payload.get("deletionRequestId").asString())).toList();
    }

    private List<JsonNode> recordEvents() {
        return env.core.requests("POST", "/internal/events").stream().map(RecordedRequest::json)
                .filter(event -> "SessionRecordCaptured".equals(event.get("eventType").asString())).toList();
    }

    private void assertGuardianDataGone(UUID sessionId) {
        for (String table : SESSION_TABLES) {
            assertThat(count(table, sessionId)).as(table).isZero();
        }
        assertThat(count("realtime_activity_session", sessionId)).isZero();
        assertThat(env.db.single("select count(*) from realtime.realtime_activity_session where guardian_id = cast(:g as uuid)",
                new MapSqlParameterSource("g", env.guardianId.toString()), Long.class)).isZero();
        assertThat(filesUnder(sessionId)).as("원음 파일 0건(NFR9)").isZero();
    }

    private long count(String table, UUID sessionId) {
        String column = table.equals("realtime_activity_session") ? "id" : "session_id";
        return env.db.single("select count(*) from realtime." + table + " where " + column + " = cast(:id as uuid)",
                new MapSqlParameterSource("id", sessionId.toString()), Long.class);
    }

    private long guardianOutboxRows() {
        return env.db.single("select count(*) from realtime.realtime_outbox_event where guardian_id = cast(:g as uuid)"
                + " and status <> 'PENDING'", new MapSqlParameterSource("g", env.guardianId.toString()), Long.class);
    }

    private long filesUnder(UUID sessionId) {
        Path dir = recordings.resolve(sessionId.toString());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
