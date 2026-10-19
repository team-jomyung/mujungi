package kr.mujoong.realtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.event.payload.DeviceStatusChanged;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.realtime.messaging.InboxEventRow.Status;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Realtime Outbox/Inbox 데이터 접근 통합 시험(U1 3.5 Step 18).
 *
 * <p>증명: BR5.1(업무와 같은 트랜잭션에서만 기록, 되돌리면 이벤트도 없음) · RD-8 · BR5.2(선점은 created_at, event_id 순 N건 · SKIP LOCKED로
 * 두 폴러가 같은 행을 집지 않음 · 재시도 대기 행이 뒤 행을 막지 않음 · 선점 시간 동안 안 보임) · BR5.3(PUBLISHED · 재시도 · FAILED) ·
 * BR5.11(전달 끝 · 7일 지난 실패 행 payload 비움) · S3f · A1 재무장(제어 이벤트만, 제외 조건) · BR5.5 · BR5.6(새 행 RECEIVED는
 * next_attempt_at = received_at, 중복은 0행, 충돌 기록) · BR5.7(선점 경주 승자 하나) · BR5.8(재호출 · DEAD retry_count 유지) ·
 * BR5.9(IGNORED) · BR5.12(시작 때 당김) · A3(trace_id 저장) · A4(guardian_id 채움) · BR5.13(last_error 형식).
 */
@Tag("skeleton")
@Tag("db")
class RealtimeMessagingRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final JsonCodec JSON = JsonCodec.shared();

    private static RealtimeDb db;
    private RealtimeOutboxRepository outbox;
    private RealtimeInboxRepository inbox;
    private UUID guardian;

    @BeforeAll
    static void openDatabase() {
        db = RealtimeDb.open();
    }

    @AfterAll
    static void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        db.wipe();
        outbox = new RealtimeOutboxRepository(db.jdbc());
        inbox = new RealtimeInboxRepository(db.jdbc());
        guardian = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    // ================================================================== Outbox

    @Test
    void outboxRowsAreWrittenOnlyInsideTheBusinessTransactionAndVanishWithItsRollback() {
        EventEnvelope committed = deviceStatus(T0, TraceIds.newTraceId());
        EventEnvelope rolledBack = deviceStatus(T0, null);

        assertThatThrownBy(() -> outbox.insert(NewOutboxEvent.of(committed, guardian), T0))
                .isInstanceOf(IllegalTransactionStateException.class);
        db.rolledBack(() -> outbox.insert(NewOutboxEvent.of(rolledBack, guardian), T0));
        db.inTransaction(() -> outbox.insert(NewOutboxEvent.of(committed, guardian), T0));

        assertThat(outbox.find(rolledBack.eventId())).isEmpty();
        OutboxEventRow row = outbox.find(committed.eventId()).orElseThrow();
        assertThat(row.status()).isEqualTo(OutboxEventRow.Status.PENDING);
        assertThat(row.retryCount()).isZero();
        assertThat(row.availableAt()).isEqualTo(T0);
        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.eventType()).isEqualTo("DeviceStatusChanged");
        assertThat(row.aggregateType()).isEqualTo("Device");
        assertThat(row.aggregateId()).isEqualTo(committed.aggregateId());
        assertThat(row.schemaVersion()).isEqualTo(1);
        assertThat(row.occurredAt()).isEqualTo(T0);
        assertThat(row.guardianId()).isEqualTo(guardian);
        assertThat(row.traceId()).isEqualTo(committed.traceId());
        assertThat(row.payloadHash()).isEqualTo(JSON.sha256OfCanonical(committed.payloadJson()));
        assertThat(row.publishedAt()).isNull();
        assertThat(row.purgedAt()).isNull();
        // 행의 열에서 다시 만든 봉투는 보낸 봉투와 같은 내용이다(RD-8)
        EventEnvelope rebuilt = row.toEnvelope();
        assertThat(JSON.canonicalize(rebuilt.payloadJson())).isEqualTo(committed.payloadJson());
        assertThat(rebuilt.traceId()).isEqualTo(committed.traceId());
        assertThat(row.toString()).doesNotContain(committed.payloadJson());
    }

    @Test
    void outboxEventsNeedAGuardianAndMustTravelFromRealtimeToCore() {
        EventEnvelope status = deviceStatus(T0, null);

        assertThatThrownBy(() -> NewOutboxEvent.of(status, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NewOutboxEvent.of(deviceReleased(), guardian))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewOutboxEvent(status, guardian, "not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NewOutboxEvent.of(status, guardian).toString()).doesNotContain(status.payloadJson());
    }

    @Test
    void claimTakesDueRowsInCreationOrderWithoutBeingBlockedByAWaitingRetry() {
        EventEnvelope waiting = insertOutbox(deviceStatus(T0, null), T0.minusSeconds(10));
        outbox.claimDue(T0.minusSeconds(10), 1, LEASE);
        assertThat(outbox.scheduleRetry(waiting.eventId(), 0, T0.plusSeconds(60), LastError.http(503))).isTrue();
        EventEnvelope first = insertOutbox(deviceStatus(T0, null), T0);
        EventEnvelope second = insertOutbox(sessionState(), T0.plusMillis(1));
        EventEnvelope third = insertOutbox(deviceStatus(T0, null), T0.plusMillis(2));
        Instant now = T0.plusSeconds(1);

        List<OutboxEventRow> batch = outbox.claimDue(now, 2, LEASE);

        // 재시도 대기 행은 더 먼저 만들어졌어도 뒤 행을 막지 않는다
        assertThat(batch).extracting(OutboxEventRow::eventId).containsExactly(first.eventId(), second.eventId());
        assertThat(batch).extracting(OutboxEventRow::availableAt).containsOnly(now.plus(LEASE));
        // 선점된 행은 선점 시간 동안 다른 폴러에게 보이지 않는다
        assertThat(outbox.claimDue(now, 2, LEASE)).extracting(OutboxEventRow::eventId).containsExactly(third.eventId());
        assertThat(outbox.claimDue(now, 2, LEASE)).isEmpty();
        // 선점이 끝나면 다시 대상이다(프로세스가 선점 뒤 죽은 경우)
        assertThat(outbox.claimDue(now.plus(LEASE), 5, LEASE)).extracting(OutboxEventRow::eventId)
                .containsExactly(first.eventId(), second.eventId(), third.eventId());
        assertThat(outbox.claimDue(T0.plusSeconds(60), 5, LEASE)).extracting(OutboxEventRow::eventId)
                .containsExactly(waiting.eventId());
        assertThatThrownBy(() -> outbox.claimDue(now, 0, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.claimDue(now, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rowsWithTheSameCreationTimeAreClaimedInEventIdOrder() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(insertOutbox(deviceStatus(T0, null), T0).eventId());
        }
        List<UUID> databaseOrder = db.jdbc().queryForList(
                "select event_id from realtime.realtime_outbox_event order by created_at, event_id",
                new MapSqlParameterSource(), UUID.class);

        assertThat(outbox.claimDue(T0, 4, LEASE)).extracting(OutboxEventRow::eventId)
                .containsExactlyElementsOf(databaseOrder)
                .containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void twoPollersNeverClaimTheSameRow() throws Exception {
        for (int i = 0; i < 4; i++) {
            insertOutbox(deviceStatus(T0, null), T0.plusMillis(i));
        }
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 재시작이 겹친 순간: 첫 폴러의 선점 트랜잭션이 아직 커밋 전이다
            Future<List<OutboxEventRow>> first = pool.submit(() -> db.inTransaction(() -> {
                List<OutboxEventRow> rows = outbox.claimDue(T0.plusSeconds(1), 2, LEASE);
                firstClaimed.countDown();
                await(release);
                return rows;
            }));
            assertThat(firstClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            // 둘째 폴러는 잠긴 행을 기다리지 않고 건너뛴다(SKIP LOCKED)
            List<OutboxEventRow> second = pool.submit(() -> outbox.claimDue(T0.plusSeconds(1), 2, LEASE))
                    .get(5, TimeUnit.SECONDS);
            release.countDown();
            List<OutboxEventRow> firstRows = first.get(10, TimeUnit.SECONDS);

            Set<UUID> union = new HashSet<>();
            firstRows.forEach(row -> union.add(row.eventId()));
            second.forEach(row -> assertThat(union.add(row.eventId())).as("두 폴러가 같은 행을 선점함").isTrue());
            assertThat(firstRows).hasSize(2);
            assertThat(second).hasSize(2);
            assertThat(union).hasSize(4);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(outbox.claimDue(T0.plusSeconds(1), 10, LEASE)).isEmpty();
    }

    @Test
    void aPublishedRowEmptiesItsPayloadAndCannotBeChangedAgain() {
        EventEnvelope event = insertOutbox(sessionState(), T0);
        Instant sentAt = T0.plusSeconds(1);

        assertThat(outbox.markPublished(event.eventId(), sentAt)).isTrue();

        OutboxEventRow row = outbox.find(event.eventId()).orElseThrow();
        assertThat(row.status()).isEqualTo(OutboxEventRow.Status.PUBLISHED);
        assertThat(row.payloadJson()).isEqualTo("{}");
        assertThat(row.purgedAt()).isEqualTo(sentAt);
        assertThat(row.publishedAt()).isEqualTo(sentAt);
        assertThat(row.payloadHash()).isEqualTo(JSON.sha256OfCanonical(event.payloadJson()));
        assertThat(outbox.markPublished(event.eventId(), sentAt)).isFalse();
        assertThat(outbox.markFailed(event.eventId(), LastError.EVENT_CONFLICT)).isFalse();
        assertThat(outbox.scheduleRetry(event.eventId(), 0, sentAt, LastError.http(500))).isFalse();
        assertThatThrownBy(row::toEnvelope).isInstanceOf(IllegalStateException.class);
        assertThat(outbox.markPublished(UUID.randomUUID(), sentAt)).isFalse();
    }

    @Test
    void eachFailureIsCountedOnceAndAFailedRowKeepsItsCount() {
        EventEnvelope event = insertOutbox(deviceStatus(T0, null), T0);

        assertThat(outbox.scheduleRetry(event.eventId(), 0, T0.plusSeconds(1), LastError.http(503))).isTrue();
        // 같은 실패를 두 번 세지 않는다(선점 때 읽은 retry_count가 낡음)
        assertThat(outbox.scheduleRetry(event.eventId(), 0, T0.plusSeconds(2), LastError.http(503))).isFalse();
        assertThat(outbox.scheduleRetry(event.eventId(), 1, T0.plusSeconds(3),
                LastError.exceptionKind(new java.net.http.HttpTimeoutException("x")))).isTrue();
        OutboxEventRow retried = outbox.find(event.eventId()).orElseThrow();
        assertThat(retried.retryCount()).isEqualTo(2);
        assertThat(retried.availableAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(retried.lastError()).isEqualTo("java.net.http.HttpTimeoutException");

        assertThat(outbox.markFailed(event.eventId(), LastError.EVENT_CONFLICT)).isTrue();
        OutboxEventRow failed = outbox.find(event.eventId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(OutboxEventRow.Status.FAILED);
        assertThat(failed.retryCount()).isEqualTo(2);
        assertThat(failed.lastError()).isEqualTo(LastError.EVENT_CONFLICT);
        // last_error에는 코드 · 예외 종류만(BR5.13)
        assertThatThrownBy(() -> outbox.markFailed(event.eventId(), "payload {\"text\":\"안녕\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LastError.http(42)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LastError.exceptionKind(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupAndShutdownReleaseClaimsWithoutTouchingRetryCounts() {
        EventEnvelope a = insertOutbox(deviceStatus(T0, null), T0);
        EventEnvelope b = insertOutbox(deviceStatus(T0, null), T0.plusMillis(1));
        EventEnvelope c = insertOutbox(deviceStatus(T0, null), T0.plusMillis(2));
        Instant claimedAt = T0.plusMillis(2);
        assertThat(outbox.claimDue(claimedAt, 3, LEASE)).hasSize(3);
        outbox.scheduleRetry(c.eventId(), 0, T0.plusSeconds(10), LastError.http(502));
        Instant stopAt = T0.plusSeconds(2);

        // 정상 정지: 아직 보내지 않은 선점 행만 되돌린다(RD-8 6)
        assertThat(outbox.releaseClaims(List.of(a.eventId()), stopAt)).isEqualTo(1);
        assertThat(outbox.releaseClaims(List.of(), stopAt)).isZero();
        assertThat(outbox.find(a.eventId()).orElseThrow().availableAt()).isEqualTo(stopAt);
        assertThat(outbox.find(b.eventId()).orElseThrow().availableAt()).isEqualTo(claimedAt.plus(LEASE));

        // S3f: 크래시 뒤 미래 available_at인 PENDING 전부를 당긴다
        Instant restart = T0.plusSeconds(3);
        assertThat(outbox.releaseClaims(restart)).isEqualTo(2);
        assertThat(outbox.claimDue(restart, 10, LEASE)).extracting(OutboxEventRow::eventId)
                .containsExactly(a.eventId(), b.eventId(), c.eventId());
        assertThat(outbox.find(c.eventId()).orElseThrow().retryCount()).isEqualTo(1);
    }

    @Test
    void rearmingRevivesOnlyFailedControlEventsThatCanStillBeSent() {
        EventEnvelope rearmable = insertOutbox(deletionCompleted(), T0);
        EventEnvelope conflict = insertOutbox(deletionCompleted(), T0);
        EventEnvelope accountDeleted = insertOutbox(deletionCompleted(), T0);
        EventEnvelope purged = insertOutbox(deletionCompleted(), T0.minus(Duration.ofDays(8)));
        EventEnvelope ordinary = insertOutbox(deviceStatus(T0, null), T0);
        outbox.scheduleRetry(rearmable.eventId(), 0, T0, LastError.http(503));
        outbox.markFailed(rearmable.eventId(), LastError.http(503));
        outbox.markFailed(conflict.eventId(), LastError.EVENT_CONFLICT);
        outbox.markFailed(accountDeleted.eventId(), LastError.ACCOUNT_DELETED);
        outbox.markFailed(purged.eventId(), LastError.http(500));
        outbox.markFailed(ordinary.eventId(), LastError.http(500));
        assertThat(outbox.purgeFailedBefore(T0.minus(Duration.ofDays(7)), T0, 10)).isEqualTo(1);
        Instant startup = T0.plusSeconds(600);

        assertThat(outbox.rearmControlEvents(startup)).isEqualTo(1);

        OutboxEventRow revived = outbox.find(rearmable.eventId()).orElseThrow();
        assertThat(revived.status()).isEqualTo(OutboxEventRow.Status.PENDING);
        assertThat(revived.retryCount()).isZero();
        assertThat(revived.availableAt()).isEqualTo(startup);
        assertThat(revived.lastError()).isEqualTo(LastError.http(503));
        for (EventEnvelope untouched : List.of(conflict, accountDeleted, purged, ordinary)) {
            assertThat(outbox.find(untouched.eventId()).orElseThrow().status()).isEqualTo(OutboxEventRow.Status.FAILED);
        }
        assertThat(RealtimeOutboxRepository.REARM_EVENT_TYPES).containsExactly("DeletionCompleted", "SessionRecordCaptured");
        // 다시 불러도 결과가 같다(멱등)
        assertThat(outbox.rearmControlEvents(startup)).isZero();
    }

    @Test
    void failedPayloadsAreEmptiedAfterTheRetentionInBatches() {
        EventEnvelope old1 = insertOutbox(deviceStatus(T0, null), T0.minus(Duration.ofDays(9)));
        EventEnvelope old2 = insertOutbox(deviceStatus(T0, null), T0.minus(Duration.ofDays(8)));
        EventEnvelope recent = insertOutbox(deviceStatus(T0, null), T0.minus(Duration.ofDays(6)));
        EventEnvelope pending = insertOutbox(deviceStatus(T0, null), T0.minus(Duration.ofDays(9)));
        List.of(old1, old2, recent).forEach(e -> outbox.markFailed(e.eventId(), LastError.http(500)));
        Instant cutoff = T0.minus(Duration.ofDays(7));

        assertThat(outbox.purgeFailedBefore(cutoff, T0, 1)).isEqualTo(1);
        assertThat(outbox.find(old1.eventId()).orElseThrow().purgedAt()).isEqualTo(T0);
        assertThat(outbox.purgeFailedBefore(cutoff, T0, 10)).isEqualTo(1);
        assertThat(outbox.purgeFailedBefore(cutoff, T0, 10)).isZero();

        assertThat(outbox.find(old2.eventId()).orElseThrow().payloadJson()).isEqualTo("{}");
        assertThat(outbox.find(recent.eventId()).orElseThrow().purgedAt()).isNull();
        assertThat(outbox.find(pending.eventId()).orElseThrow().purgedAt()).isNull();
        assertThatThrownBy(() -> outbox.purgeFailedBefore(cutoff, T0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ================================================================== Inbox

    @Test
    void aNewEventIsReceivedWithItsNextAttemptAtTheReceiptTime() {
        EventEnvelope released = deviceReleasedWithTrace();

        assertThat(inbox.insertIfAbsent(NewInboxEvent.received(released, guardian, T0))).isTrue();

        InboxEventRow row = inbox.find(released.eventId()).orElseThrow();
        assertThat(row.status()).isEqualTo(Status.RECEIVED);
        assertThat(row.sourceService()).isEqualTo(RealtimeInboxRepository.SOURCE_CORE);
        assertThat(row.nextAttemptAt()).isEqualTo(T0);
        assertThat(row.receivedAt()).isEqualTo(T0);
        assertThat(row.retryCount()).isZero();
        assertThat(row.guardianId()).isEqualTo(guardian);
        assertThat(row.traceId()).isEqualTo(released.traceId());
        assertThat(row.eventType()).isEqualTo("DeviceReleased");
        assertThat(row.aggregateId()).isEqualTo(released.aggregateId());
        assertThat(row.occurredAt()).isEqualTo(T0);
        assertThat(row.schemaVersion()).isEqualTo(1);
        assertThat(JSON.canonicalize(row.payloadJson())).isEqualTo(released.payloadJson());
        assertThat(row.payloadHash()).isEqualTo(JSON.sha256OfCanonical(released.payloadJson()));
        assertThat(row.toString()).doesNotContain("REREGISTERED");
    }

    @Test
    void aRepeatedEventIdIsNotInsertedAndAConflictIsOnlyNotedOnTheExistingRow() {
        EventEnvelope released = deviceReleasedWithTrace();
        inbox.insertIfAbsent(NewInboxEvent.received(released, guardian, T0));
        EventEnvelope changed = new EventEnvelope(released.eventId(), released.eventType(), 1, T0, "Device",
                released.aggregateId(), null, "{\"deviceId\":\"" + released.aggregateId() + "\",\"reason\":\"ACCOUNT_DELETED\"}");

        // 같은 내용 재전송: 0행, 해시가 같아 호출자는 200
        assertThat(inbox.insertIfAbsent(NewInboxEvent.received(released, guardian, T0.plusSeconds(1)))).isFalse();
        // 다른 내용: 0행, 해시가 달라 호출자는 409 + 기존 행에 EVENT_CONFLICT만
        NewInboxEvent conflicting = NewInboxEvent.received(changed, guardian, T0.plusSeconds(2));
        assertThat(inbox.insertIfAbsent(conflicting)).isFalse();
        InboxEventRow existing = inbox.find(released.eventId()).orElseThrow();
        assertThat(existing.payloadHash()).isNotEqualTo(conflicting.payloadHash());
        assertThat(inbox.recordConflict(released.eventId())).isTrue();

        InboxEventRow row = inbox.find(released.eventId()).orElseThrow();
        assertThat(row.lastError()).isEqualTo(LastError.EVENT_CONFLICT);
        assertThat(row.status()).isEqualTo(Status.RECEIVED);
        assertThat(row.receivedAt()).isEqualTo(T0);
        assertThat(row.payloadHash()).isEqualTo(existing.payloadHash());
        assertThat(inbox.recordConflict(UUID.randomUUID())).isFalse();
    }

    @Test
    void anEventWithoutAHandlerIsIgnoredFromTheStartWithItsPayloadEmptied() {
        EventEnvelope unknown = new EventEnvelope(UUID.randomUUID(), "SomethingNew", 1, T0, "Device", UUID.randomUUID(),
                null, "{\"field\":\"value\"}");
        NewInboxEvent event = NewInboxEvent.ignored(unknown, null, T0);

        assertThat(inbox.insertIfAbsent(event)).isTrue();

        InboxEventRow row = inbox.find(unknown.eventId()).orElseThrow();
        assertThat(row.status()).isEqualTo(Status.IGNORED);
        assertThat(row.payloadJson()).isEqualTo("{}");
        assertThat(row.purgedAt()).isEqualTo(T0);
        assertThat(row.nextAttemptAt()).isNull();
        assertThat(row.guardianId()).isNull();
        // 해시는 원래 payload 기준이라 같은 eventId 재전송의 중복 · 충돌 판정에 쓰인다
        assertThat(row.payloadHash()).isEqualTo(JSON.sha256OfCanonical(unknown.payloadJson()));
        assertThat(inbox.claim(unknown.eventId(), T0.plusSeconds(1), LEASE)).isFalse();
        assertThat(inbox.findDue(T0.plusSeconds(1), 10)).isEmpty();
        assertThat(event.toString()).doesNotContain("value");
    }

    @Test
    void onlyOneOfManyRacingClaimsWinsUntilTheLeaseRunsOut() throws Exception {
        EventEnvelope released = deviceReleasedWithTrace();
        inbox.insertIfAbsent(NewInboxEvent.received(released, guardian, T0));
        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return inbox.claim(released.eventId(), T0.plusSeconds(1), LEASE);
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> claim : claims) {
                winners += claim.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(inbox.find(released.eventId()).orElseThrow().nextAttemptAt()).isEqualTo(T0.plusSeconds(1).plus(LEASE));
        // 처리기가 선점 안에 끝나지 못하면 같은 행을 다시 선점할 수 있다(처리기는 멱등)
        assertThat(inbox.claim(released.eventId(), T0.plusSeconds(1).plus(LEASE).minusMillis(1), LEASE)).isFalse();
        assertThat(inbox.claim(released.eventId(), T0.plusSeconds(1).plus(LEASE), LEASE)).isTrue();
        assertThatThrownBy(() -> inbox.claim(released.eventId(), T0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRepollerSeesDueRowsInAttemptThenReceiptOrderUpToItsLimit() {
        EventEnvelope a = insertInbox(T0.plusSeconds(2));
        EventEnvelope b = insertInbox(T0);
        EventEnvelope c = insertInbox(T0.plusSeconds(1));
        EventEnvelope claimed = insertInbox(T0.minusSeconds(1));
        inbox.claim(claimed.eventId(), T0.plusSeconds(3), LEASE);

        assertThat(inbox.findDue(T0.plusSeconds(3), 2)).extracting(InboxEventRow::eventId)
                .containsExactly(b.eventId(), c.eventId());
        assertThat(inbox.findDue(T0.plusSeconds(3), 10)).extracting(InboxEventRow::eventId)
                .containsExactly(b.eventId(), c.eventId(), a.eventId());
        assertThat(inbox.findDue(T0.minusSeconds(5), 10)).isEmpty();
        assertThatThrownBy(() -> inbox.findDue(T0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlerOutcomesProcessRetryAndFinallyDeadWithTheCountKept() {
        EventEnvelope processed = insertInbox(T0);
        EventEnvelope failing = insertInbox(T0);

        assertThat(inbox.markProcessed(processed.eventId(), T0.plusSeconds(1))).isTrue();
        InboxEventRow done = inbox.find(processed.eventId()).orElseThrow();
        assertThat(done.status()).isEqualTo(Status.PROCESSED);
        assertThat(done.processedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(done.payloadJson()).isEqualTo("{}");
        assertThat(done.purgedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(inbox.markProcessed(processed.eventId(), T0.plusSeconds(2))).isFalse();

        // 1 · 2 · 4 · 8 · 16초 다섯 번 재호출, 여섯 번째 실패는 DEAD(retry_count 5 유지)
        long[] backoffSeconds = {1, 2, 4, 8, 16};
        Instant at = T0;
        for (int retry = 0; retry < backoffSeconds.length; retry++) {
            at = at.plusSeconds(backoffSeconds[retry]);
            assertThat(inbox.scheduleRetry(failing.eventId(), retry, at, "java.lang.IllegalStateException")).isTrue();
        }
        assertThat(inbox.scheduleRetry(failing.eventId(), 0, at, "java.lang.IllegalStateException")).isFalse();
        assertThat(inbox.find(failing.eventId()).orElseThrow().nextAttemptAt()).isEqualTo(at);
        assertThat(inbox.markDead(failing.eventId(), "java.lang.IllegalStateException")).isTrue();

        InboxEventRow dead = inbox.find(failing.eventId()).orElseThrow();
        assertThat(dead.status()).isEqualTo(Status.DEAD);
        assertThat(dead.retryCount()).isEqualTo(5);
        assertThat(dead.lastError()).isEqualTo("java.lang.IllegalStateException");
        assertThat(inbox.markDead(failing.eventId(), "x")).isFalse();
        assertThat(inbox.markProcessed(failing.eventId(), T0)).isFalse();
        assertThatThrownBy(() -> inbox.markDead(failing.eventId(), "토큰 포함 문장")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupPullsEveryReceivedRowForwardAndDeadPayloadsAreEmptiedAfterRetention() {
        EventEnvelope claimed = insertInbox(T0);
        EventEnvelope waiting = insertInbox(T0);
        EventEnvelope dead = insertInbox(T0.minus(Duration.ofDays(8)));
        inbox.claim(claimed.eventId(), T0, LEASE);
        inbox.scheduleRetry(waiting.eventId(), 0, T0.plusSeconds(16), LastError.exceptionKind(new IllegalStateException()));
        inbox.markDead(dead.eventId(), LastError.exceptionKind(new IllegalStateException()));
        Instant startup = T0.plusSeconds(5);

        // BR5.12: HTTP 수신 전에 RECEIVED 전부를 now로(retry_count 유지)
        assertThat(inbox.pullForwardReceived(startup)).isEqualTo(2);
        assertThat(inbox.findDue(startup, 10)).extracting(InboxEventRow::eventId)
                .containsExactlyInAnyOrder(claimed.eventId(), waiting.eventId());
        assertThat(inbox.find(waiting.eventId()).orElseThrow().retryCount()).isEqualTo(1);

        assertThat(inbox.purgeDeadBefore(T0.minus(Duration.ofDays(7)), T0, 10)).isEqualTo(1);
        assertThat(inbox.purgeDeadBefore(T0.minus(Duration.ofDays(7)), T0, 10)).isZero();
        assertThat(inbox.find(dead.eventId()).orElseThrow().payloadJson()).isEqualTo("{}");
        assertThat(inbox.find(claimed.eventId()).orElseThrow().purgedAt()).isNull();
        assertThatThrownBy(() -> inbox.purgeDeadBefore(T0, T0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 도우미

    private EventEnvelope insertOutbox(EventEnvelope envelope, Instant now) {
        db.inTransaction(() -> outbox.insert(NewOutboxEvent.of(envelope, guardian), now));
        return envelope;
    }

    private EventEnvelope insertInbox(Instant receivedAt) {
        EventEnvelope envelope = deviceReleasedWithTrace();
        assertThat(inbox.insertIfAbsent(NewInboxEvent.received(envelope, guardian, receivedAt))).isTrue();
        return envelope;
    }

    private EventEnvelope deviceStatus(Instant since, String traceId) {
        return envelope(new DeviceStatusChanged(UUID.randomUUID(), ConnectionState.CONNECTED, 40, 0, since, since,
                guardian), traceId);
    }

    private EventEnvelope sessionState() {
        return envelope(new SessionStateChanged(UUID.randomUUID(), guardian, UUID.randomUUID(), ActivityType.FREE_TALK,
                SessionStatus.PREPARING, 1L, null, null, null, null, T0), null);
    }

    private EventEnvelope deletionCompleted() {
        return envelope(new DeletionCompleted(UUID.randomUUID(), guardian, T0, null), null);
    }

    private EventEnvelope deviceReleased() {
        return envelope(new DeviceReleased(UUID.randomUUID(), guardian, DeviceReleased.Reason.REREGISTERED), null);
    }

    private EventEnvelope deviceReleasedWithTrace() {
        return envelope(new DeviceReleased(UUID.randomUUID(), guardian, DeviceReleased.Reason.REREGISTERED),
                TraceIds.newTraceId());
    }

    private static EventEnvelope envelope(EventPayload payload, String traceId) {
        return JSON.envelopeFor(UUID.randomUUID(), payload, T0, traceId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시험 래치 대기 시간 초과");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
