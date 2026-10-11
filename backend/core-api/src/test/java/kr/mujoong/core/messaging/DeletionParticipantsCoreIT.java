package kr.mujoong.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.event.payload.DeviceStatusChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.NewActivity;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.device.DeviceDeletionParticipant;
import kr.mujoong.core.messaging.CoreInboxRepository.NewInboxEvent;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Core 계정 삭제 참가자 둘 (U1 3.5 Step 28 · BR2.10 · BR5.14 · J58 · A1 · A4 · SD-21 · RD-11). 두 번 불러도 같은 결과(멱등), FK 순서(활동 행이
 * 남으면 Device 참가자는 실패하고 아무것도 바꾸지 않음), PENDING · RECEIVED 유지, 다른 보호자 행 보존, SessionRecordCaptured 원문 정리,
 * FAILED 제어 이벤트는 지우지 않고 재무장, guardian_id NULL 0건. 3.6 수정 분량: 지울 것이 없는 보호자는 두 참가자 모두 0건이고
 * DeviceReleased를 쓰지 않으며, 보호자가 아직 없는 등록 대기(UNREGISTERED) 코드 행은 건드리지 않는다(7일 정리 작업 몫).
 */
@Tag("u1")
@Tag("db")
class DeletionParticipantsCoreIT {

    private static MujoongPostgres postgres;

    private final MutableClock clock = new MutableClock(Instant.parse("2001-06-01T00:00:00Z"));
    private final JsonCodec json = JsonCodec.shared();
    private DbTestSupport db;
    private BusinessFixtures fixtures;

    @BeforeAll
    static void database() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        fixtures = new BusinessFixtures(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void deviceParticipantReleasesThenDeletesAllRowsAndIsIdempotent() {
        Family leaving = fixtures.family(clock.now());
        Family staying = fixtures.family(clock.now());
        UUID active = fixtures.registeredDevice(leaving.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        UUID oldDevice = releasedDevice(leaving.guardianId());
        UUID otherDevice = fixtures.registeredDevice(staying.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        context().run(context -> {
            DeviceDeletionParticipant participant = context.getBean(DeviceDeletionParticipant.class);
            UUID requestId = UUID.randomUUID();

            assertThat(participant.deleteByGuardian(leaving.guardianId(), requestId)).containsEntry("device", 2);
            assertThat(participant.deleteByGuardian(leaving.guardianId(), requestId)).containsEntry("device", 0);

            assertThat(count("select count(*) from core.core_device where id in (:a, :b)",
                    new MapSqlParameterSource("a", active).addValue("b", oldDevice))).isZero();
            assertThat(count("select count(*) from core.core_device where id = :id", new MapSqlParameterSource("id",
                    otherDevice))).isEqualTo(1);
            List<Map<String, Object>> released = db.jdbc().queryForList("""
                    select event_type, status, guardian_id, aggregate_id, payload_json::text as payload
                      from core.core_outbox_event where guardian_id = :g
                    """, new MapSqlParameterSource("g", leaving.guardianId()));
            assertThat(released).hasSize(1);
            assertThat(released.get(0)).containsEntry("event_type", "DeviceReleased").containsEntry("status", "PENDING")
                    .containsEntry("aggregate_id", active);
            assertThat((String) released.get(0).get("payload")).contains("ACCOUNT_DELETED");
        });
    }

    @Test
    void remainingActivityRowsMakeDeviceParticipantFailWithoutPartialChanges() {
        Family family = fixtures.family(clock.now());
        UUID device = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        context().run(context -> {
            context.getBean(ActivityCopyRepository.class).insertRequested(new NewActivity(UUID.randomUUID(),
                    family.guardianId(), family.childId(), device, ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                    "hash", clock.now()));
            DeviceDeletionParticipant participant = context.getBean(DeviceDeletionParticipant.class);

            assertThatThrownBy(() -> participant.deleteByGuardian(family.guardianId(), UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(db.jdbc().queryForObject("select registration_status from core.core_device where id = :id",
                    new MapSqlParameterSource("id", device), String.class)).isEqualTo("REGISTERED");
            assertThat(count("select count(*) from core.core_outbox_event where guardian_id = :g",
                    new MapSqlParameterSource("g", family.guardianId()))).isZero();

            db.jdbc().update("delete from core.core_activity where guardian_id = :g",
                    new MapSqlParameterSource("g", family.guardianId()));
            assertThat(participant.deleteByGuardian(family.guardianId(), UUID.randomUUID())).containsEntry("device", 1);
        });
    }

    @Test
    void messagingParticipantDeletesFinishedRowsKeepsPendingAndRearmsFailedControlEvents() {
        Family leaving = fixtures.family(clock.now());
        Family staying = fixtures.family(clock.now());
        UUID device = fixtures.registeredDevice(leaving.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        context().run(context -> {
            UUID published = outbox(context, leaving, "PUBLISHED", null);
            UUID failedControl = outbox(context, leaving, "FAILED", "HTTP_503");
            UUID failedClosed = outbox(context, leaving, "FAILED", "ACCOUNT_DELETED");
            UUID pending = outbox(context, leaving, "PENDING", null);
            UUID othersPublished = outbox(context, staying, "PUBLISHED", null);
            UUID processed = inbox(context, deviceStatus(device, leaving), "PROCESSED");
            UUID dead = inbox(context, deviceStatus(device, leaving), "DEAD");
            UUID received = inbox(context, deviceStatus(device, leaving), "RECEIVED");
            UUID transcript = transcriptInbox(context, leaving);
            UUID othersProcessed = inbox(context, deviceStatus(device, staying), "PROCESSED");
            CoreMessagingDeletionParticipant participant = context.getBean(CoreMessagingDeletionParticipant.class);
            UUID requestId = UUID.randomUUID();
            clock.advance(Duration.ofSeconds(1));

            Map<String, Integer> first = participant.deleteByGuardian(leaving.guardianId(), requestId);
            Map<String, Integer> second = participant.deleteByGuardian(leaving.guardianId(), requestId);

            assertThat(first).containsEntry(CoreMessagingDeletionParticipant.OUTBOX, 2)
                    .containsEntry(CoreMessagingDeletionParticipant.INBOX, 3);
            assertThat(second).containsEntry(CoreMessagingDeletionParticipant.OUTBOX, 0)
                    .containsEntry(CoreMessagingDeletionParticipant.INBOX, 0);
            assertThat(outboxStatus(published)).isNull();
            assertThat(outboxStatus(failedClosed)).isNull();
            assertThat(outboxStatus(failedControl)).isEqualTo("PENDING");
            assertThat(outboxStatus(pending)).isEqualTo("PENDING");
            assertThat(outboxStatus(othersPublished)).isEqualTo("PUBLISHED");
            assertThat(inboxStatus(processed)).isNull();
            assertThat(inboxStatus(dead)).isNull();
            assertThat(inboxStatus(transcript)).isNull();
            assertThat(inboxStatus(received)).isEqualTo("RECEIVED");
            assertThat(inboxStatus(othersProcessed)).isEqualTo("PROCESSED");
            assertThat(count("select count(*) from core.core_outbox_event where event_id = :id and retry_count = 0"
                    + " and last_error = 'HTTP_503'", new MapSqlParameterSource("id", failedControl))).isEqualTo(1);
        });
    }

    @Test
    void everyMessagingRowKeepsItsGuardianIdForA4() {
        Family family = fixtures.family(clock.now());
        UUID device = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        context().run(context -> {
            UUID out = outbox(context, family, "PENDING", null);
            UUID oldStyle = inboxWithoutGuardianInPayload(context, device);

            assertThat(count("select count(*) from core.core_outbox_event where event_id = :id and guardian_id is null",
                    new MapSqlParameterSource("id", out))).isZero();
            assertThat(db.jdbc().queryForObject("select guardian_id from core.core_inbox_event where event_id = :id",
                    new MapSqlParameterSource("id", oldStyle), UUID.class)).isEqualTo(family.guardianId());
        });
    }

    @Test
    void aGuardianWithNothingToDeleteGetsZeroCountsNoReleaseAndPendingCodesStay() {
        Family family = fixtures.family(clock.now());
        UUID pendingCode = unregisteredDevice();
        context().run(context -> {
            UUID requestId = UUID.randomUUID();

            assertThat(context.getBean(DeviceDeletionParticipant.class).deleteByGuardian(family.guardianId(), requestId))
                    .containsOnly(Map.entry(DeviceDeletionParticipant.DEVICE, 0));
            assertThat(context.getBean(CoreMessagingDeletionParticipant.class).deleteByGuardian(family.guardianId(), requestId))
                    .containsEntry(CoreMessagingDeletionParticipant.OUTBOX, 0)
                    .containsEntry(CoreMessagingDeletionParticipant.INBOX, 0);

            assertThat(count("select count(*) from core.core_outbox_event where guardian_id = :g",
                    new MapSqlParameterSource("g", family.guardianId()))).as("기기가 없으면 DeviceReleased도 없다").isZero();
            assertThat(db.jdbc().queryForObject("select registration_status from core.core_device where id = :id",
                    new MapSqlParameterSource("id", pendingCode), String.class))
                    .as("보호자 없는 등록 대기 행은 삭제 대상이 아니다").isEqualTo("UNREGISTERED");
        });
    }

    // ------------------------------------------------------------------ 도움

    /** 보호자가 아직 없는 등록 대기 코드 행(기기 웹이 받은 코드). */
    private UUID unregisteredDevice() {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = kr.mujoong.core.common.db.ClockParams.at(clock.now());
        kr.mujoong.core.common.db.ClockParams.uuid(params, "id", id);
        kr.mujoong.core.common.db.ClockParams.instant(params, "expiresAt", clock.now().plus(Duration.ofHours(24)));
        kr.mujoong.core.common.db.ClockParams.text(params, "code", DbTestSupport.randomCode());
        kr.mujoong.core.common.db.ClockParams.text(params, "hash", DbTestSupport.randomHash());
        db.jdbc().update("""
                insert into core.core_device (id, registration_code, code_issued_at, code_expires_at, bootstrap_secret_hash,
                    registration_status, created_at, updated_at)
                values (:id, :code, :now, :expiresAt, :hash, 'UNREGISTERED', :now, :now)
                """, params);
        return db.trackDevice(id);
    }

    private org.springframework.boot.test.context.runner.ApplicationContextRunner context() {
        return CoreBusinessContext.runner(postgres, clock, java.net.URI.create("http://127.0.0.1:9"),
                TestSecrets.internalServiceToken());
    }

    private UUID releasedDevice(UUID guardianId) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = kr.mujoong.core.common.db.ClockParams.at(clock.now());
        kr.mujoong.core.common.db.ClockParams.uuid(params, "id", id);
        kr.mujoong.core.common.db.ClockParams.uuid(params, "guardianId", guardianId);
        kr.mujoong.core.common.db.ClockParams.text(params, "code", DbTestSupport.randomCode());
        kr.mujoong.core.common.db.ClockParams.text(params, "hash", DbTestSupport.randomHash());
        db.jdbc().update("""
                insert into core.core_device (id, guardian_id, registration_code, code_issued_at, code_expires_at,
                    bootstrap_secret_hash, registration_status, registered_at, released_at, release_reason, created_at, updated_at)
                values (:id, :guardianId, :code, :now, :now, :hash, 'RELEASED', :now, :now, 'REREGISTERED', :now, :now)
                """, params);
        return db.trackDevice(id);
    }

    private UUID outbox(ApplicationContext context, Family family, String status, String lastError) {
        clock.advance(Duration.ofMillis(1));
        TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        UUID id = tx.execute(s -> context.getBean(CoreOutboxWriter.class).write(
                new DeviceReleased(UUID.randomUUID(), family.guardianId(), DeviceReleased.Reason.REREGISTERED),
                family.guardianId(), clock.now()));
        CoreOutboxRepository outbox = context.getBean(CoreOutboxRepository.class);
        switch (status) {
            case "PUBLISHED" -> outbox.markPublished(id, clock.now());
            case "FAILED" -> outbox.markFailed(id, lastError);
            default -> {
                // PENDING 그대로
            }
        }
        return db.trackOutbox(id);
    }

    private DeviceStatusChanged deviceStatus(UUID device, Family family) {
        return new DeviceStatusChanged(device, ConnectionState.CONNECTED, null, null, clock.now(), null, family.guardianId());
    }

    private UUID inbox(ApplicationContext context, EventPayload payload, String status) {
        EventEnvelope envelope = json.envelopeFor(UUID.randomUUID(), payload, clock.now(), TraceIds.newTraceId());
        context.getBean(CoreInboxRepository.class).insertReceived(new NewInboxEvent(envelope.eventId(), envelope.eventType(),
                1, clock.now(), envelope.aggregateId(), payload.guardianId(), envelope.payloadJson(),
                json.sha256OfCanonical(envelope.payloadJson()), envelope.traceId(), clock.now()));
        CoreInboxRepository inbox = context.getBean(CoreInboxRepository.class);
        switch (status) {
            case "PROCESSED" -> inbox.markProcessed(envelope.eventId(), clock.now());
            case "DEAD" -> inbox.markDead(envelope.eventId(), "IllegalStateException");
            default -> {
                // RECEIVED 그대로
            }
        }
        return db.trackInbox(envelope.eventId());
    }

    private UUID transcriptInbox(ApplicationContext context, Family family) {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"guardianId\":\"" + family.guardianId() + "\",\"sessionId\":\"" + UUID.randomUUID()
                + "\",\"transcript\":\"아이 말 원문 표식\"}";
        context.getBean(CoreInboxRepository.class).insertReceived(new NewInboxEvent(eventId, "SessionRecordCaptured", 1,
                clock.now(), UUID.randomUUID(), family.guardianId(), payload, json.sha256OfCanonical(payload), null,
                clock.now()));
        return db.trackInbox(eventId);
    }

    private UUID inboxWithoutGuardianInPayload(ApplicationContext context, UUID device) {
        String body = json.encodeEventEnvelope(json.envelopeFor(UUID.randomUUID(),
                new DeviceStatusChanged(device, ConnectionState.OFFLINE, null, null, clock.now(), null, null), clock.now(),
                null));
        UUID eventId = json.decodeEventEnvelope(body).eventId();
        db.trackInbox(eventId);
        assertThat(context.getBean(CoreInboxReceiver.class).receive(body).outcome())
                .isEqualTo(CoreInboxReceiver.Outcome.ACCEPTED);
        return eventId;
    }

    private String outboxStatus(UUID eventId) {
        List<String> rows = db.jdbc().queryForList("select status from core.core_outbox_event where event_id = :id",
                new MapSqlParameterSource("id", eventId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String inboxStatus(UUID eventId) {
        List<String> rows = db.jdbc().queryForList("select status from core.core_inbox_event where event_id = :id",
                new MapSqlParameterSource("id", eventId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int count(String sql, MapSqlParameterSource params) {
        Integer value = db.jdbc().queryForObject(sql, params, Integer.class);
        return value == null ? -1 : value;
    }
}
