package kr.mujoong.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.event.payload.DeviceStatusChanged;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.ActivityCopyRepository.NewActivity;
import kr.mujoong.core.activity.PreparingTimeoutChecker;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.device.DeviceExpiryCleanupJob;
import kr.mujoong.core.health.DbHealthChecker;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.messaging.CoreInboxRepository.NewInboxEvent;
import kr.mujoong.core.messaging.CoreOutboxRepository.OutboxEvent;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.observability.snapshot.MetricSnapshotJob;
import kr.mujoong.core.startup.CoreStartupSequence;
import kr.mujoong.core.startup.SchemaVersionCheck;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.http.ReceiverDouble;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Core 메시징 흐름 (U1 3.5 Step 28 · RD-7 ~ RD-9 · BR5.1 ~ BR5.12 · A1 · A3 · A4 · OD-7 · 편차 (g)(q) · NFR4.5 · NFR4.6). 실제 DB와 수신기
 * 대역(봉투를 contracts/schemas로 검사)으로 발송 · 수신 · 처리 · 재무장 · 시작 복구를 본다. 시험 프로필 대기표는 Outbox 10 · 20 · 40 · 80 ·
 * 160 · 300ms, Inbox 10 · 20 · 40 · 80 · 160ms다.
 */
@Tag("u1")
@Tag("db")
class CoreMessagingFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private static MujoongPostgres postgres;

    private final MutableClock clock = new MutableClock(Instant.parse("2001-05-01T00:00:00Z"));
    private final String token = TestSecrets.internalServiceToken();
    private final JsonCodec json = JsonCodec.shared();
    private DbTestSupport db;
    private BusinessFixtures fixtures;
    private ReceiverDouble receiver;
    private ContractStubServer realtime;

    @BeforeAll
    static void database() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        fixtures = new BusinessFixtures(db);
        receiver = ReceiverDouble.start().requireInternalToken(token);
        realtime = ContractStubServer.start().requireInternalToken(token);
        realtime.route("POST", "/internal/sessions/{sessionId}/end").requestSchema("internal-session-end-request.schema.json")
                .respond(StubResponse.json(202, "{}"));
    }

    @AfterEach
    void tearDown() {
        receiver.close();
        realtime.close();
        db.close();
    }

    // ------------------------------------------------------------------ Outbox 발송(RD-8)

    @Test
    void senderRecordsEachReceiverResponseAsTheTableSays() {
        Family family = fixtures.family(clock.now());
        receiver.respondWith(200, 401, 409, 400, 503);
        CoreBusinessContext.runner(postgres, clock, receiver.baseUrl(), token).run(context -> {
            List<UUID> ids = List.of(write(context, family), write(context, family), write(context, family),
                    write(context, family), write(context, family));
            context.getBean(ReadinessState.class).markReady();
            Instant sentAt = clock.now();

            assertThat(context.getBean(CoreOutboxSender.class).runCycle()).isEqualTo(5);

            CoreOutboxRepository outbox = context.getBean(CoreOutboxRepository.class);
            OutboxEvent published = outbox.findById(ids.get(0)).orElseThrow();
            assertThat(published.status()).isEqualTo("PUBLISHED");
            assertThat(published.payloadJson()).isEqualTo("{}");
            OutboxEvent unauthorized = outbox.findById(ids.get(1)).orElseThrow();
            assertThat(unauthorized.status()).isEqualTo("PENDING");
            assertThat(unauthorized.retryCount()).isEqualTo(1);
            assertThat(unauthorized.availableAt()).isEqualTo(sentAt.plusMillis(10));
            assertThat(unauthorized.lastError()).isEqualTo("HTTP_401");
            assertThat(outbox.findById(ids.get(2)).orElseThrow()).extracting(OutboxEvent::status, OutboxEvent::lastError)
                    .containsExactly("FAILED", "EVENT_CONFLICT");
            assertThat(outbox.findById(ids.get(3)).orElseThrow()).extracting(OutboxEvent::status, OutboxEvent::lastError)
                    .containsExactly("FAILED", "INVALID_ENVELOPE");
            assertThat(outbox.findById(ids.get(4)).orElseThrow()).extracting(OutboxEvent::status, OutboxEvent::retryCount)
                    .containsExactly("PENDING", 1);
            assertThat(receiver.violations()).isEmpty();
        });
    }

    @Test
    void traceIdSurvivesRetriesAndTwentyFirstFailureIsFinal() {
        Family family = fixtures.family(clock.now());
        receiver.respondWith(503, 200);
        CoreBusinessContext.runner(postgres, clock, receiver.baseUrl(), token).run(context -> {
            String traceId = TraceIds.newTraceId();
            MDC.put(TraceIds.MDC_KEY, traceId);
            UUID retried;
            try {
                retried = write(context, family);
            } finally {
                MDC.remove(TraceIds.MDC_KEY);
            }
            context.getBean(ReadinessState.class).markReady();
            CoreOutboxSender sender = context.getBean(CoreOutboxSender.class);
            sender.runCycle();
            clock.advance(Duration.ofMillis(10));
            sender.runCycle();

            List<RecordedRequest> deliveries = receiver.deliveriesOf(retried);
            assertThat(deliveries).hasSize(2);
            assertThat(deliveries).allMatch(r -> traceId.equals(r.header("x-trace-id"))
                    && traceId.equals(r.json().path("traceId").asString()));

            UUID exhausted = write(context, family);
            db.jdbc().update("update core.core_outbox_event set retry_count = 20 where event_id = :id",
                    new MapSqlParameterSource("id", exhausted));
            receiver.respondWith(503);
            sender.runCycle();
            OutboxEvent failed = context.getBean(CoreOutboxRepository.class).findById(exhausted).orElseThrow();
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.retryCount()).isEqualTo(20);
            assertThat(failed.lastError()).isEqualTo("HTTP_503");
        });
    }

    // ------------------------------------------------------------------ Inbox 수신 · 처리(RD-9)

    @Test
    void receiverCommitsBeforeAckAndHandlerRunsOnceForDuplicates() {
        Family family = fixtures.family(clock.now());
        UUID deviceId = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now().minusSeconds(5),
                clock.now());
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            CoreInboxReceiver inbox = context.getBean(CoreInboxReceiver.class);
            String body = envelope(new DeviceStatusChanged(deviceId, ConnectionState.DISCONNECTED, null, 2,
                    clock.now(), clock.now(), family.guardianId()), clock.now());
            UUID eventId = json.decodeEventEnvelope(body).eventId();
            long versionBefore = rowVersion(deviceId);

            assertThat(inbox.receive(body).outcome()).isEqualTo(CoreInboxReceiver.Outcome.ACCEPTED);
            assertThat(db.jdbc().queryForObject("select count(*) from core.core_inbox_event where event_id = :id",
                    new MapSqlParameterSource("id", eventId), Integer.class)).isEqualTo(1);
            db.trackInbox(eventId);
            awaitInboxStatus(eventId, "PROCESSED");
            assertThat(inbox.receive(body).outcome()).isEqualTo(CoreInboxReceiver.Outcome.DUPLICATE);
            assertThat(inbox.receive(body.replace("\"recentDisconnects\":2", "\"recentDisconnects\":3")).outcome())
                    .isEqualTo(CoreInboxReceiver.Outcome.CONFLICT);

            assertThat(rowVersion(deviceId)).isEqualTo(versionBefore + 1);
            Map<String, Object> row = db.jdbc().queryForMap(
                    "select status, guardian_id, last_error, payload_json::text as payload from core.core_inbox_event"
                            + " where event_id = :id", new MapSqlParameterSource("id", eventId));
            assertThat(row).containsEntry("status", "PROCESSED").containsEntry("guardian_id", family.guardianId())
                    .containsEntry("last_error", "EVENT_CONFLICT").containsEntry("payload", "{}");
            assertThat(db.jdbc().queryForObject("select reported_connection_state from core.core_device where id = :id",
                    new MapSqlParameterSource("id", deviceId), String.class)).isEqualTo("DISCONNECTED");
        });
    }

    @Test
    void unknownTypeIsIgnoredAndBrokenEnvelopeIsRejected() {
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            CoreInboxReceiver inbox = context.getBean(CoreInboxReceiver.class);
            UUID eventId = db.trackInbox(UUID.randomUUID());
            String future = """
                    {"eventId":"%s","eventType":"SomethingNew","schemaVersion":1,"occurredAt":"2001-05-01T00:00:00Z",
                     "aggregateType":"Device","aggregateId":"%s","payload":{"secretish":"x"}}
                    """.formatted(eventId, UUID.randomUUID());

            assertThat(inbox.receive(future).outcome()).isEqualTo(CoreInboxReceiver.Outcome.IGNORED);
            assertThat(inbox.receive("{\"eventId\":\"nope\"}").outcome()).isEqualTo(CoreInboxReceiver.Outcome.INVALID);
            assertThat(inbox.receive(future.replace("\"schemaVersion\":1", "\"schemaVersion\":0")).outcome())
                    .isEqualTo(CoreInboxReceiver.Outcome.INVALID);
            Map<String, Object> row = db.jdbc().queryForMap(
                    "select status, payload_json::text as payload from core.core_inbox_event where event_id = :id",
                    new MapSqlParameterSource("id", eventId));
            assertThat(row).containsEntry("status", "IGNORED").containsEntry("payload", "{}");
        });
    }

    @Test
    void failingHandlerBacksOffFiveTimesThenGoesDeadWithOneWarning() {
        Family family = fixtures.family(clock.now());
        UUID deviceId = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        realtime.route("POST", "/internal/sessions/{sessionId}/end").respond(StubResponse.json(503, "{}"));
        try (LogCapture logs = LogCapture.attach(true)) {
            CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
                UUID sessionId = localFailedSession(context, family, deviceId);
                UUID eventId = insertInbox(context, sessionStateChanged(sessionId, family, SessionStatus.PREPARING, 1),
                        clock.now());
                CoreInboxDispatcher dispatcher = context.getBean(CoreInboxDispatcher.class);
                long[] backoff = {10, 20, 40, 80, 160};

                for (int failure = 1; failure <= 5; failure++) {
                    assertThat(dispatcher.process(eventId)).isEqualTo(CoreInboxDispatcher.Outcome.HANDLER_RETRY);
                    assertThat(dispatcher.process(eventId)).isEqualTo(CoreInboxDispatcher.Outcome.SKIPPED);
                    clock.advance(Duration.ofMillis(backoff[failure - 1]));
                }
                assertThat(dispatcher.process(eventId)).isEqualTo(CoreInboxDispatcher.Outcome.DEAD);

                Map<String, Object> row = db.jdbc().queryForMap(
                        "select status, retry_count, last_error from core.core_inbox_event where event_id = :id",
                        new MapSqlParameterSource("id", eventId));
                assertThat(row).containsEntry("status", "DEAD").containsEntry("retry_count", 5)
                        .containsEntry("last_error", "RealtimeCallFailedException");
                assertThat(realtime.requests("POST", "/internal/sessions/{sessionId}/end")).hasSize(6);
                List<Map<String, Object>> dead = logs.events(EventCode.EVENT_DELIVERY).stream()
                        .filter(e -> "DEAD".equals(LogCapture.attrs(e).get("status"))).toList();
                assertThat(dead).hasSize(1);
                assertThat(dead.get(0).get("level")).isEqualTo("WARN");
            });
        }
    }

    @Test
    void sessionEventsApplyByStatusVersionInAnyOrderAndLocalFailedIsRecancelled() {
        Family family = fixtures.family(clock.now());
        UUID deviceId = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            ActivityCopyRepository activities = context.getBean(ActivityCopyRepository.class);
            CoreInboxDispatcher dispatcher = context.getBean(CoreInboxDispatcher.class);
            UUID sessionId = requestedSession(context, family, deviceId);
            Instant startedAt = clock.now().plusSeconds(1);
            Instant endedAt = clock.now().plusSeconds(9);

            UUID ended = insertInbox(context, new SessionStateChanged(sessionId, family.guardianId(), family.childId(),
                    ActivityType.FREE_TALK, SessionStatus.ENDED, 5L, EndReason.GUARDIAN_STOP, null, true, null, endedAt),
                    endedAt);
            UUID active = insertInbox(context, new SessionStateChanged(sessionId, family.guardianId(), family.childId(),
                    ActivityType.FREE_TALK, SessionStatus.ACTIVE, 3L, null, null, false, null, startedAt), startedAt);
            assertThat(dispatcher.process(ended)).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
            assertThat(dispatcher.process(active)).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
            assertThat(dispatcher.process(active)).isEqualTo(CoreInboxDispatcher.Outcome.SKIPPED);

            ActivityCopy copy = activities.findById(sessionId).orElseThrow();
            assertThat(copy.status()).isEqualTo(SessionStatus.ENDED);
            assertThat(copy.statusVersion()).isEqualTo(5);
            assertThat(copy.startedAt()).isEqualTo(startedAt);
            assertThat(copy.endedAt()).isEqualTo(endedAt);
            assertThat(realtime.requests()).isEmpty();

            UUID abandoned = localFailedSession(context, family, deviceId);
            UUID late = insertInbox(context, sessionStateChanged(abandoned, family, SessionStatus.ACTIVE, 4), clock.now());
            assertThat(dispatcher.process(late)).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
            assertThat(activities.findById(abandoned).orElseThrow().status()).isEqualTo(SessionStatus.FAILED);
            RecordedRequest cancel = realtime.requests().get(0);
            assertThat(cancel.path()).isEqualTo("/internal/sessions/" + abandoned + "/end");
            assertThat(cancel.json().path("reason").asString()).isEqualTo("CORE_ABANDONED");
        });
    }

    @Test
    void deviceStatusEventsKeepTheLatestOccurredAtAndFillGuardianFromDeviceRow() {
        Family family = fixtures.family(clock.now());
        UUID deviceId = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token).run(context -> {
            CoreInboxReceiver inbox = context.getBean(CoreInboxReceiver.class);
            String newer = envelope(new DeviceStatusChanged(deviceId, ConnectionState.DISCONNECTED, null, 1,
                    clock.now().plusSeconds(2), null, null), clock.now().plusSeconds(2));
            String older = envelope(new DeviceStatusChanged(deviceId, ConnectionState.CONNECTED, 50, 0,
                    clock.now().plusSeconds(1), null, family.guardianId()), clock.now().plusSeconds(1));
            UUID newerId = db.trackInbox(json.decodeEventEnvelope(newer).eventId());
            UUID olderId = db.trackInbox(json.decodeEventEnvelope(older).eventId());

            inbox.receive(newer);
            awaitInboxStatus(newerId, "PROCESSED");
            inbox.receive(older);
            awaitInboxStatus(olderId, "PROCESSED");

            assertThat(db.jdbc().queryForObject("select reported_connection_state from core.core_device where id = :id",
                    new MapSqlParameterSource("id", deviceId), String.class)).isEqualTo("DISCONNECTED");
            assertThat(db.jdbc().queryForObject("select guardian_id from core.core_inbox_event where event_id = :id",
                    new MapSqlParameterSource("id", newerId), UUID.class)).isEqualTo(family.guardianId());
        });
    }

    // ------------------------------------------------------------------ 시작 복구(RD-12 S3c · S3d · S3f)

    @Test
    void startupRecoveryPullsInboxRearmsControlEventsAndReleasesClaims() {
        Family family = fixtures.family(clock.now());
        UUID deviceId = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        CoreBusinessContext.runner(postgres, clock, receiver.baseUrl(), token).run(context -> {
            UUID failedControl = write(context, family);
            UUID conflicted = write(context, family);
            UUID claimed = write(context, family);
            MapSqlParameterSource ids = new MapSqlParameterSource()
                    .addValue("failed", failedControl).addValue("conflicted", conflicted).addValue("claimed", claimed);
            db.jdbc().update("update core.core_outbox_event set status = 'FAILED', retry_count = 20, last_error = 'HTTP_503'"
                    + " where event_id = :failed", ids);
            db.jdbc().update("update core.core_outbox_event set status = 'FAILED', last_error = 'EVENT_CONFLICT'"
                    + " where event_id = :conflicted", ids);
            MapSqlParameterSource later = new MapSqlParameterSource("claimed", claimed);
            later.addValue("later", java.time.OffsetDateTime.ofInstant(clock.now().plusSeconds(30), java.time.ZoneOffset.UTC));
            db.jdbc().update("update core.core_outbox_event set available_at = :later where event_id = :claimed", later);
            UUID received = insertInbox(context, new DeviceStatusChanged(deviceId, ConnectionState.CONNECTED, null, null,
                    clock.now(), null, family.guardianId()), clock.now());
            MapSqlParameterSource inboxLater = new MapSqlParameterSource("id", received);
            inboxLater.addValue("later", java.time.OffsetDateTime.ofInstant(clock.now().plusSeconds(30), java.time.ZoneOffset.UTC));
            db.jdbc().update("update core.core_inbox_event set next_attempt_at = :later where event_id = :id", inboxLater);
            clock.advance(Duration.ofSeconds(1));

            CoreStartupSequence.Recovery recovery = sequence(context).recover();

            assertThat(recovery.reopenedInboxRows()).isGreaterThanOrEqualTo(1);
            assertThat(recovery.rearmedControlEvents()).isGreaterThanOrEqualTo(1);
            CoreOutboxRepository outbox = context.getBean(CoreOutboxRepository.class);
            assertThat(outbox.findById(failedControl).orElseThrow())
                    .extracting(OutboxEvent::status, OutboxEvent::retryCount, OutboxEvent::availableAt, OutboxEvent::lastError)
                    .containsExactly("PENDING", 0, clock.now(), "HTTP_503");
            assertThat(outbox.findById(conflicted).orElseThrow().status()).isEqualTo("FAILED");
            assertThat(outbox.findById(claimed).orElseThrow().availableAt()).isEqualTo(clock.now());
            assertThat(context.getBean(CoreInboxRepository.class).findById(received).orElseThrow().nextAttemptAt())
                    .isEqualTo(clock.now());
        });
    }

    // ------------------------------------------------------------------ 도움

    private UUID write(ApplicationContext context, Family family) {
        clock.advance(Duration.ofMillis(1));
        TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        UUID id = tx.execute(status -> context.getBean(CoreOutboxWriter.class).write(
                new DeviceReleased(UUID.randomUUID(), family.guardianId(), DeviceReleased.Reason.REREGISTERED),
                family.guardianId(), clock.now()));
        return db.trackOutbox(id);
    }

    private String envelope(EventPayload payload, Instant occurredAt) {
        EventEnvelope envelope = json.envelopeFor(UUID.randomUUID(), payload, occurredAt, TraceIds.newTraceId());
        return json.encodeEventEnvelope(envelope);
    }

    private UUID insertInbox(ApplicationContext context, EventPayload payload, Instant occurredAt) {
        EventEnvelope envelope = json.envelopeFor(UUID.randomUUID(), payload, occurredAt, TraceIds.newTraceId());
        context.getBean(CoreInboxRepository.class).insertReceived(new NewInboxEvent(envelope.eventId(), envelope.eventType(),
                1, occurredAt, envelope.aggregateId(), payload.guardianId(), envelope.payloadJson(),
                json.sha256OfCanonical(envelope.payloadJson()), envelope.traceId(), clock.now()));
        return db.trackInbox(envelope.eventId());
    }

    private SessionStateChanged sessionStateChanged(UUID sessionId, Family family, SessionStatus status, long version) {
        return new SessionStateChanged(sessionId, family.guardianId(), family.childId(), ActivityType.FREE_TALK, status,
                version, null, null, false, null, clock.now());
    }

    private UUID requestedSession(ApplicationContext context, Family family, UUID deviceId) {
        UUID sessionId = UUID.randomUUID();
        context.getBean(ActivityCopyRepository.class).insertRequested(new NewActivity(sessionId, family.guardianId(),
                family.childId(), deviceId, ActivityType.FREE_TALK, UUID.randomUUID().toString(), "hash", clock.now()));
        return sessionId;
    }

    private UUID localFailedSession(ApplicationContext context, Family family, UUID deviceId) {
        UUID sessionId = requestedSession(context, family, deviceId);
        context.getBean(ActivityCopyRepository.class).closeLocalFailed(sessionId, SessionStatus.REQUESTED, 0, 202,
                clock.now());
        return sessionId;
    }

    private long rowVersion(UUID deviceId) {
        Long version = db.jdbc().queryForObject("select row_version from core.core_device where id = :id",
                new MapSqlParameterSource("id", deviceId), Long.class);
        return version == null ? -1 : version;
    }

    private void awaitInboxStatus(UUID eventId, String status) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<String> found = db.jdbc().queryForList("select status from core.core_inbox_event where event_id = :id",
                    new MapSqlParameterSource("id", eventId), String.class);
            if (!found.isEmpty() && status.equals(found.get(0))) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("Inbox 행이 " + WAIT + " 안에 " + status + "가 되지 않았다");
    }

    private static CoreStartupSequence sequence(ApplicationContext context) {
        return new CoreStartupSequence(
                context.getBean(SchemaVersionCheck.class),
                context.getBean(CoreInboxRepository.class),
                context.getBean(CoreOutboxRepository.class),
                context.getBean(ControlEventRearm.class),
                context.getBean(CoreOutboxSender.class),
                context.getBean(CoreInboxRepoller.class),
                context.getBean(CoreInboxDispatcher.class),
                context.getBean(PreparingTimeoutChecker.class),
                context.getBean(DbHealthChecker.class),
                context.getBean(DeviceExpiryCleanupJob.class),
                context.getBean(MessagingCleanupJob.class),
                context.getBean(MetricSnapshotJob.class),
                context.getBean(EventLogger.class),
                context.getBean(ReadinessState.class),
                context.getBean(kr.mujoong.contract.time.MujoongClock.class),
                context.getBean(CoreProperties.class));
    }
}
