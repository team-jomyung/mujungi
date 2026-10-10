package kr.mujoong.core.messaging;

import static kr.mujoong.core.common.db.DbTestSupport.EPOCH;
import static kr.mujoong.core.common.db.DbTestSupport.await;
import static kr.mujoong.core.common.db.DbTestSupport.randomHash;
import static kr.mujoong.core.common.db.DbTestSupport.randomTraceId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import kr.mujoong.core.messaging.CoreInboxRepository.NewInboxEvent;
import kr.mujoong.core.messaging.CoreOutboxRepository.NewOutboxEvent;
import kr.mujoong.core.messaging.CoreOutboxRepository.OutboxEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Core Outbox · Inbox 저장소 통합 시험(U1 3.5 Step 17).
 *
 * <p>증명: BR5.1(업무 쓰기와 같은 트랜잭션 · 트랜잭션 밖 기록 거부), BR5.2 · RD-8(두 폴러가 한 행을 한 번만 선점 · created_at, event_id 순
 * · 재시도 대기 행이 뒤 행을 막지 않음 · batch 2 · 선점 만료), BR5.3(결과별 상태 · PENDING에서만), BR5.5 · BR5.6(같은 event_id 중복 삽입 ·
 * 충돌 기록 · RECEIVED는 next_attempt_at = received_at), BR5.7(선점 경주 승자 하나), BR5.8(재호출 · DEAD · 상한), BR5.9(IGNORED),
 * BR5.11(payload {} · purged_at · 7일 정리), BR5.12 · S3c · S3f(당김 · 선점 해제), BR5.14 · A1 · SD-21(삭제 참가자 · FAILED 제어 이벤트
 * 재무장), A3(trace_id 저장), A4(Outbox guardian_id 필수 · NULL 0).
 */
@Tag("u1")
@Tag("db")
class CoreMessagingRepositoryIT {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration RETENTION = Duration.ofDays(7);
    /** PD-8 시험 값(batch-size 2). */
    private static final int BATCH = 2;

    private DbTestSupport db;
    private CoreOutboxRepository outbox;
    private CoreInboxRepository inbox;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        outbox = new CoreOutboxRepository(db.jdbc());
        inbox = new CoreInboxRepository(db.jdbc());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    // ------------------------------------------------------------------ Outbox 기록 (BR5.1 · A3 · A4)

    @Test
    void outboxRowsCommitOrRollBackTogetherWithTheBusinessWrite() {
        UUID rolledBackEvent = UUID.randomUUID();
        UUID[] rolledBackGuardian = new UUID[1];
        assertThatThrownBy(() -> db.inTx(status -> {
            rolledBackGuardian[0] = db.insertGuardian("ACTIVE");
            outbox.insert(event(rolledBackEvent, EventType.DEVICE_RELEASED, rolledBackGuardian[0]), EPOCH);
            throw new IllegalStateException("업무 쓰기 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(outbox.findById(rolledBackEvent)).as("업무가 되돌려지면 이벤트도 없다").isEmpty();
        assertThat(db.jdbc().queryForObject("select count(*) from core.core_guardian where id = :id",
                ClockParams.uuid(new MapSqlParameterSource(), "id",
                        rolledBackGuardian[0]), Long.class)).isZero();

        UUID committedEvent = db.trackOutbox(UUID.randomUUID());
        String traceId = randomTraceId();
        Instant now = EPOCH.plusSeconds(7);
        UUID guardianId = db.inTx(status -> {
            UUID guardian = db.insertGuardian("ACTIVE");
            outbox.insert(new NewOutboxEvent(committedEvent, EventType.DEVICE_RELEASED.wire(), 1, now.minusMillis(3),
                    "Device", UUID.randomUUID(), guardian, "{\"reason\":\"REREGISTERED\"}", randomHash(), traceId), now);
            return guardian;
        });
        OutboxEvent stored = outbox.findById(committedEvent).orElseThrow();
        assertThat(stored.status()).isEqualTo("PENDING");
        assertThat(stored.retryCount()).isZero();
        assertThat(stored.availableAt()).isEqualTo(now);
        assertThat(stored.createdAt()).isEqualTo(now);
        assertThat(stored.occurredAt()).isEqualTo(now.minusMillis(3));
        assertThat(stored.guardianId()).as("A4").isEqualTo(guardianId);
        assertThat(stored.traceId()).as("A3").isEqualTo(traceId);
        assertThat(stored.payloadJson()).contains("REREGISTERED");
        assertThat(stored.toString()).doesNotContain("REREGISTERED");

        // 트랜잭션 밖 기록은 거부한다(전파 MANDATORY와 같은 뜻)
        UUID outside = UUID.randomUUID();
        assertThatThrownBy(() -> outbox.insert(event(outside, EventType.DEVICE_RELEASED, guardianId), now))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outbox.findById(outside)).isEmpty();
    }

    // ------------------------------------------------------------------ Outbox 선점 · 결과 (BR5.2 · BR5.3 · RD-8)

    @Test
    void twoPollersNeverClaimTheSameRowBecauseLockedRowsAreSkipped() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        List<UUID> ids = db.inTx(status -> {
            List<UUID> created = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                UUID id = db.trackOutbox(UUID.randomUUID());
                outbox.insert(event(id, EventType.DEVICE_RELEASED, guardianId), EPOCH.plusSeconds(i));
                created.add(id);
            }
            return created;
        });
        Instant now = EPOCH.plusSeconds(10);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();

        Future<List<OutboxEvent>> first = db.startTx(firstPid, status -> {
            List<OutboxEvent> claimed = outbox.claimDue(now, LEASE, BATCH);
            firstClaimed.countDown();
            awaitLatch(release);
            return claimed;
        });
        awaitLatch(firstClaimed);
        // 앞 폴러가 행 잠금을 쥔 채 커밋 전이다. 뒤 폴러는 기다리지 않고 잠긴 행을 건너뛴다(SKIP LOCKED)
        Future<List<OutboxEvent>> second = db.start(() -> outbox.claimDue(now, LEASE, BATCH));
        List<OutboxEvent> secondClaimed = await(second);
        release.countDown();
        List<OutboxEvent> firstClaimedRows = await(first);

        assertThat(firstClaimedRows).extracting(OutboxEvent::eventId).containsExactly(ids.get(0), ids.get(1));
        assertThat(secondClaimed).extracting(OutboxEvent::eventId).containsExactly(ids.get(2), ids.get(3));
        assertThat(firstClaimedRows).allSatisfy(row -> assertThat(row.availableAt()).isEqualTo(now.plus(LEASE)));
        // 선점 기간 동안은 아무도 다시 집지 않고, 선점이 끝나면 다시 대상이 된다(프로세스가 죽은 경우)
        assertThat(outbox.claimDue(now.plus(LEASE).minusMillis(1), LEASE, 10)).isEmpty();
        assertThat(outbox.claimDue(now.plus(LEASE), LEASE, 10)).extracting(OutboxEvent::eventId)
                .containsExactlyElementsOf(ids);
    }

    @Test
    void claimOrderIsCreatedAtThenEventIdAndRetryWaitingRowsDoNotBlockLaterRows() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID waiting = UUID.randomUUID();
            outbox.insert(event(waiting, EventType.DEVICE_RELEASED, guardianId), EPOCH);
            outbox.recordRetry(waiting, 0, EPOCH.plusSeconds(100), "HTTP_503");
            List<UUID> sameInstant = List.of(UUID.randomUUID(), UUID.randomUUID()).stream()
                    .sorted(Comparator.comparing(UUID::toString)).toList();
            // 같은 created_at이면 event_id(uuid 바이트 순서)로 정한다. 넣는 순서를 뒤집어도 결과 순서는 같다
            outbox.insert(event(sameInstant.get(1), EventType.DEVICE_RELEASED, guardianId), EPOCH.plusSeconds(1));
            outbox.insert(event(sameInstant.get(0), EventType.DEVICE_RELEASED, guardianId), EPOCH.plusSeconds(1));
            UUID later = UUID.randomUUID();
            outbox.insert(event(later, EventType.DELETION_REQUESTED, guardianId), EPOCH.plusSeconds(2));
            Instant now = EPOCH.plusSeconds(5);

            List<OutboxEvent> claimed = outbox.claimDue(now, LEASE, BATCH);
            assertThat(claimed).extracting(OutboxEvent::eventId).containsExactlyElementsOf(sameInstant);
            assertThat(outbox.claimDue(now, LEASE, BATCH)).extracting(OutboxEvent::eventId).containsExactly(later);
            assertThat(outbox.claimDue(now, LEASE, BATCH)).isEmpty();
            assertThat(outbox.findById(waiting).orElseThrow().availableAt()).as("재시도 대기 행은 그대로")
                    .isEqualTo(EPOCH.plusSeconds(100));
            return null;
        });
    }

    @Test
    void deliveryOutcomesChangeOnlyPendingRows() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID published = UUID.randomUUID();
            UUID retried = UUID.randomUUID();
            outbox.insert(event(published, EventType.DEVICE_RELEASED, guardianId), EPOCH);
            outbox.insert(event(retried, EventType.DEVICE_RELEASED, guardianId), EPOCH);
            Instant now = EPOCH.plusSeconds(1);

            // 200 → PUBLISHED, payload 비움(BR5.11)
            assertThat(outbox.markPublished(published, now)).isEqualTo(1);
            OutboxEvent sent = outbox.findById(published).orElseThrow();
            assertThat(sent.status()).isEqualTo("PUBLISHED");
            assertThat(sent.publishedAt()).isEqualTo(now);
            assertThat(sent.purgedAt()).isEqualTo(now);
            assertThat(sent.payloadJson().replace(" ", "")).isEqualTo("{}");
            assertThat(sent.payloadHash()).as("해시는 남는다").isNotBlank();
            assertThat(outbox.markPublished(published, now)).isZero();
            assertThat(outbox.markFailed(published, "EVENT_CONFLICT")).isZero();

            // 그 밖의 실패 → retry_count + 1, 다음 시각. 선점 뒤 바뀐 행은 0행
            assertThat(outbox.recordRetry(retried, 0, now.plusSeconds(1), "HTTP_503")).isEqualTo(1);
            assertThat(outbox.recordRetry(retried, 0, now.plusSeconds(2), "HTTP_503")).isZero();
            OutboxEvent waiting = outbox.findById(retried).orElseThrow();
            assertThat(waiting.retryCount()).isEqualTo(1);
            assertThat(waiting.availableAt()).isEqualTo(now.plusSeconds(1));
            assertThat(waiting.lastError()).isEqualTo("HTTP_503");
            // 409 · 400 → 즉시 FAILED, payload는 7일 뒤 정리까지 둔다
            assertThat(outbox.markFailed(retried, "EVENT_CONFLICT")).isEqualTo(1);
            OutboxEvent failed = outbox.findById(retried).orElseThrow();
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.purgedAt()).isNull();
            assertThat(outbox.recordRetry(retried, 1, now.plusSeconds(3), "HTTP_503")).isZero();
            return null;
        });
    }

    @Test
    void retryCountCannotPassTwentyAndTheTwentyFirstFailureBecomesFailed() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID id = UUID.randomUUID();
            outbox.insert(event(id, EventType.DEVICE_RELEASED, guardianId), EPOCH);
            for (int count = 0; count < 20; count++) {
                assertThat(outbox.recordRetry(id, count, EPOCH.plusSeconds(count + 1L), "TIMEOUT")).isEqualTo(1);
            }
            assertThat(outbox.findById(id).orElseThrow().retryCount()).isEqualTo(20);
            // 20회 뒤 또 실패하면 sender가 FAILED로 둔다. retry_count는 20으로 남는다(BR5.3)
            assertThat(outbox.markFailed(id, "TIMEOUT")).isEqualTo(1);
            assertThat(outbox.findById(id).orElseThrow().retryCount()).isEqualTo(20);
            return null;
        });
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID id = UUID.randomUUID();
            outbox.insert(event(id, EventType.DEVICE_RELEASED, guardianId), EPOCH);
            db.jdbc().update("update core.core_outbox_event set retry_count = 20 where event_id = :id",
                    ClockParams.uuid(new MapSqlParameterSource(), "id", id));
            assertThatThrownBy(() -> outbox.recordRetry(id, 20, EPOCH.plusSeconds(30), "TIMEOUT"))
                    .as("V1 검사: retry_count ≤ 20").isInstanceOf(DataIntegrityViolationException.class);
            return null;
        });
    }

    @Test
    void releasingClaimsMakesRowsDueAgainWithoutTouchingRetryCounts() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID waiting = UUID.randomUUID();
            outbox.insert(event(first, EventType.DEVICE_RELEASED, guardianId), EPOCH);
            outbox.insert(event(second, EventType.DEVICE_RELEASED, guardianId), EPOCH.plusMillis(1));
            outbox.insert(event(waiting, EventType.DEVICE_RELEASED, guardianId), EPOCH.plusMillis(2));
            outbox.recordRetry(waiting, 0, EPOCH.plusSeconds(60), "HTTP_502");
            Instant now = EPOCH.plusSeconds(1);
            assertThat(outbox.claimDue(now, LEASE, 10)).hasSize(2);

            // 정지 요청: 아직 보내지 않은 선점만 되돌린다(RD-8 6)
            assertThat(outbox.releaseClaims(List.of(first), now)).isEqualTo(1);
            assertThat(outbox.releaseClaims(List.of(), now)).isZero();
            assertThat(outbox.findById(first).orElseThrow().availableAt()).isEqualTo(now);
            assertThat(outbox.findById(second).orElseThrow().availableAt()).isEqualTo(now.plus(LEASE));

            // S3f: 크래시 뒤 남은 선점 · 재시도 대기를 모두 걷는다(retry_count 유지)
            assertThat(outbox.releaseAllClaims(now)).isGreaterThanOrEqualTo(2);
            assertThat(outbox.findById(second).orElseThrow().availableAt()).isEqualTo(now);
            OutboxEvent pulled = outbox.findById(waiting).orElseThrow();
            assertThat(pulled.availableAt()).isEqualTo(now);
            assertThat(pulled.retryCount()).isEqualTo(1);
            return null;
        });
    }

    // ------------------------------------------------------------------ A1 재무장 · BR5.14 삭제 참가자 · BR5.11 정리

    @Test
    void rearmRevivesOnlyRecoverableFailedControlEvents() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            // 7일 정리로 payload가 비워진 행(EPOCH 생성)과 아직 비워지지 않은 행(1초 뒤 생성)
            UUID purged = failedOutbox(guardianId, EventType.DEVICE_RELEASED, "TIMEOUT", EPOCH);
            UUID recoverable = failedOutbox(guardianId, EventType.DEVICE_RELEASED, "HTTP_503", EPOCH.plusSeconds(1));
            UUID conflict = failedOutbox(guardianId, EventType.DEVICE_RELEASED, "EVENT_CONFLICT", EPOCH.plusSeconds(1));
            assertThat(outbox.purgeFailedOlderThan(EPOCH.plus(RETENTION), RETENTION, 100)).isEqualTo(1);
            UUID otherType = failedOutbox(guardianId, EventType.DELETION_REQUESTED, "TIMEOUT", EPOCH.plus(RETENTION));
            Set<String> excluded = Set.of("EVENT_CONFLICT", "INVALID_ENVELOPE", "ACCOUNT_DELETED");
            Instant start = EPOCH.plus(RETENTION).plusSeconds(1);

            assertThat(outbox.rearmFailedControlEventsForGuardian(guardianId, Set.of(EventType.DEVICE_RELEASED), excluded, start))
                    .isEqualTo(1);
            OutboxEvent revived = outbox.findById(recoverable).orElseThrow();
            assertThat(revived.status()).isEqualTo("PENDING");
            assertThat(revived.retryCount()).isZero();
            assertThat(revived.availableAt()).isEqualTo(start);
            assertThat(revived.lastError()).as("last_error는 둔다").isEqualTo("HTTP_503");
            assertThat(outbox.findById(conflict).orElseThrow().status()).isEqualTo("FAILED");
            assertThat(outbox.findById(purged).orElseThrow().status()).as("payload를 비운 행은 다시 보낼 수 없다")
                    .isEqualTo("FAILED");
            assertThat(outbox.findById(otherType).orElseThrow().status()).isEqualTo("FAILED");

            // 시작 복구(S3d)는 보호자 구분 없이 같은 조건을 쓴다
            assertThat(outbox.rearmFailedControlEvents(Set.of(EventType.DELETION_REQUESTED), excluded, start))
                    .isGreaterThanOrEqualTo(1);
            assertThat(outbox.findById(otherType).orElseThrow().status()).isEqualTo("PENDING");
            assertThat(outbox.rearmFailedControlEvents(Set.of(), excluded, start)).isZero();
            assertThatThrownBy(() -> outbox.rearmFailedControlEvents(Set.of(EventType.SESSION_STATE_CHANGED), excluded, start))
                    .isInstanceOf(IllegalArgumentException.class);
            return null;
        });
    }

    @Test
    void deletionParticipantRemovesOnlyDeliveredRowsOfTheGuardianAndIsIdempotent() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID otherGuardian = db.insertGuardian("ACTIVE");
            UUID published = pendingOutbox(guardianId, EventType.DEVICE_RELEASED, EPOCH);
            outbox.markPublished(published, EPOCH);
            UUID conflict = failedOutbox(guardianId, EventType.DEVICE_RELEASED, "EVENT_CONFLICT", EPOCH);
            UUID recoverable = failedOutbox(guardianId, EventType.DEVICE_RELEASED, "HTTP_503", EPOCH);
            UUID pending = pendingOutbox(guardianId, EventType.DEVICE_RELEASED, EPOCH);
            UUID othersPublished = pendingOutbox(otherGuardian, EventType.DEVICE_RELEASED, EPOCH);
            outbox.markPublished(othersPublished, EPOCH);
            Set<String> excluded = Set.of("EVENT_CONFLICT", "INVALID_ENVELOPE", "ACCOUNT_DELETED");

            // FAILED 제어 이벤트는 지우지 않고 되살린다(A1 · SD-21), 그다음 전달 끝 행만 지운다(BR5.14 (a)(b))
            assertThat(outbox.rearmFailedControlEventsForGuardian(guardianId, Set.of(EventType.DEVICE_RELEASED), excluded,
                    EPOCH.plusSeconds(1))).isEqualTo(1);
            assertThat(outbox.deleteDeliveredByGuardian(guardianId)).isEqualTo(2);
            assertThat(outbox.deleteDeliveredByGuardian(guardianId)).isZero();
            assertThat(outbox.findById(published)).isEmpty();
            assertThat(outbox.findById(conflict)).isEmpty();
            assertThat(outbox.findById(recoverable).orElseThrow().status()).isEqualTo("PENDING");
            assertThat(outbox.findById(pending)).isPresent();
            assertThat(outbox.findById(othersPublished)).isPresent();

            UUID processed = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            inbox.markProcessed(processed, EPOCH);
            UUID dead = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            inbox.markDead(dead, "IllegalStateException");
            UUID received = receivedInbox(guardianId, EventType.DEVICE_STATUS_CHANGED, EPOCH);
            UUID transcriptReceived = receivedInbox(guardianId, EventType.SESSION_RECORD_CAPTURED, EPOCH);
            UUID ignored = UUID.randomUUID();
            inbox.insertIgnored(inboxEvent(ignored, "FutureEvent", guardianId, EPOCH));
            UUID othersProcessed = receivedInbox(otherGuardian, EventType.SESSION_STATE_CHANGED, EPOCH);
            inbox.markProcessed(othersProcessed, EPOCH);

            // (c) 대화 원문이 든 이벤트는 상태와 무관하게 비우고 RECEIVED면 IGNORED로 닫는다
            assertThat(inbox.closeTranscriptEventsForGuardian(guardianId, EventType.SESSION_RECORD_CAPTURED,
                    EPOCH.plusSeconds(2))).isEqualTo(1);
            InboxEvent closed = inbox.findById(transcriptReceived).orElseThrow();
            assertThat(closed.status()).isEqualTo("IGNORED");
            assertThat(closed.payloadJson()).doesNotContain("transcript");
            assertThat(closed.purgedAt()).isEqualTo(EPOCH.plusSeconds(2));
            assertThat(inbox.closeTranscriptEventsForGuardian(guardianId, EventType.SESSION_RECORD_CAPTURED,
                    EPOCH.plusSeconds(3))).as("멱등").isZero();

            assertThat(inbox.deleteFinishedByGuardian(guardianId)).isEqualTo(4);
            assertThat(inbox.deleteFinishedByGuardian(guardianId)).isZero();
            assertThat(inbox.findById(received)).as("RECEIVED는 남긴다").isPresent();
            assertThat(inbox.findById(processed)).isEmpty();
            assertThat(inbox.findById(dead)).isEmpty();
            assertThat(inbox.findById(ignored)).isEmpty();
            assertThat(inbox.findById(othersProcessed)).isPresent();
            assertThatThrownBy(() -> inbox.closeTranscriptEventsForGuardian(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH))
                    .isInstanceOf(IllegalArgumentException.class);
            return null;
        });
    }

    @Test
    void retentionCleanupEmptiesOldFailedOutboxAndDeadInboxPayloadsOnly() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID failed = failedOutbox(guardianId, EventType.DEVICE_RELEASED, "TIMEOUT", EPOCH);
            UUID youngerFailed = failedOutbox(guardianId, EventType.DEVICE_RELEASED, "TIMEOUT", EPOCH.plusSeconds(10));
            UUID dead = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            inbox.markDead(dead, "IllegalStateException");
            Instant boundary = EPOCH.plus(RETENTION);

            assertThat(outbox.purgeFailedOlderThan(boundary.minusMillis(1), RETENTION, 100)).isZero();
            assertThat(outbox.purgeFailedOlderThan(boundary, RETENTION, 100)).isEqualTo(1);
            OutboxEvent purged = outbox.findById(failed).orElseThrow();
            assertThat(purged.payloadJson().replace(" ", "")).isEqualTo("{}");
            assertThat(purged.purgedAt()).isEqualTo(boundary);
            assertThat(outbox.findById(youngerFailed).orElseThrow().purgedAt()).isNull();
            assertThat(outbox.purgeFailedOlderThan(boundary, RETENTION, 100)).isZero();

            assertThat(inbox.purgeDeadOlderThan(boundary.minusMillis(1), RETENTION, 100)).isZero();
            assertThat(inbox.purgeDeadOlderThan(boundary, RETENTION, 100)).isEqualTo(1);
            assertThat(inbox.findById(dead).orElseThrow().payloadJson().replace(" ", "")).isEqualTo("{}");
            return null;
        });
    }

    // ------------------------------------------------------------------ Inbox (BR5.5 ~ BR5.9 · BR5.12)

    @Test
    void inboxInsertIsIdempotentByEventIdAndAConflictKeepsTheFirstRow() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID eventId = UUID.randomUUID();
            String traceId = randomTraceId();
            NewInboxEvent first = new NewInboxEvent(eventId, EventType.DEVICE_STATUS_CHANGED.wire(), 1, EPOCH,
                    UUID.randomUUID(), guardianId, "{\"state\":\"CONNECTED\"}", randomHash(), traceId, EPOCH.plusSeconds(1));
            assertThat(inbox.insertReceived(first)).isTrue();
            InboxEvent stored = inbox.findById(eventId).orElseThrow();
            assertThat(stored.status()).isEqualTo("RECEIVED");
            assertThat(stored.sourceService()).isEqualTo(CoreInboxRepository.SOURCE_REALTIME);
            assertThat(stored.nextAttemptAt()).as("방금 받은 행도 폴러에 잡힌다(BR5.6)").isEqualTo(stored.receivedAt());
            assertThat(stored.traceId()).isEqualTo(traceId);
            assertThat(stored.guardianId()).isEqualTo(guardianId);

            NewInboxEvent different = new NewInboxEvent(eventId, EventType.DEVICE_STATUS_CHANGED.wire(), 1, EPOCH,
                    first.aggregateId(), guardianId, "{\"state\":\"OFFLINE\"}", randomHash(), null, EPOCH.plusSeconds(2));
            assertThat(inbox.insertReceived(different)).as("같은 event_id는 한 번만").isFalse();
            assertThat(inbox.insertIgnored(different)).isFalse();
            assertThat(inbox.findById(eventId).orElseThrow().payloadHash()).isEqualTo(first.payloadHash());
            assertThat(inbox.recordConflict(eventId, "EVENT_CONFLICT")).isEqualTo(1);
            InboxEvent conflicted = inbox.findById(eventId).orElseThrow();
            assertThat(conflicted.lastError()).isEqualTo("EVENT_CONFLICT");
            assertThat(conflicted.status()).isEqualTo("RECEIVED");
            assertThat(conflicted.payloadJson()).contains("CONNECTED");

            // 처리기가 없는 종류 · 상위 schemaVersion은 처음부터 IGNORED, payload 비움(BR5.9 · BR5.11)
            UUID unknown = UUID.randomUUID();
            assertThat(inbox.insertIgnored(new NewInboxEvent(unknown, "FutureEvent", 2, EPOCH, UUID.randomUUID(), guardianId,
                    "{\"x\":1}", randomHash(), null, EPOCH.plusSeconds(3)))).isTrue();
            InboxEvent ignored = inbox.findById(unknown).orElseThrow();
            assertThat(ignored.status()).isEqualTo("IGNORED");
            assertThat(ignored.payloadJson().replace(" ", "")).isEqualTo("{}");
            assertThat(ignored.purgedAt()).isEqualTo(EPOCH.plusSeconds(3));
            assertThat(ignored.nextAttemptAt()).isNull();
            assertThat(ignored.schemaVersion()).isEqualTo(2);
            assertThat(ignored.toString()).doesNotContain("\"x\"");
            return null;
        });
    }

    @Test
    void racingClaimsOnOneInboxRowLetExactlyOneCallerInvokeTheHandler() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        UUID eventId = db.trackInbox(UUID.randomUUID());
        inbox.insertReceived(inboxEvent(eventId, EventType.SESSION_STATE_CHANGED.wire(), guardianId, EPOCH));
        Instant now = EPOCH.plusSeconds(1);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();

        // 응답 뒤 제출된 호출과 재호출 폴러가 같은 행을 두고 겨룬다(BR5.7)
        Future<Integer> first = db.startTx(firstPid, status -> {
            int rows = inbox.claim(eventId, now, LEASE);
            claimed.countDown();
            awaitLatch(release);
            return rows;
        });
        awaitLatch(claimed);
        Future<Integer> second = db.startTx(secondPid, status -> inbox.claim(eventId, now, LEASE));
        db.awaitLockWait(secondPid);
        release.countDown();

        assertThat(await(first)).isEqualTo(1);
        assertThat(await(second)).isZero();
        assertThat(inbox.claim(eventId, now.plus(LEASE).minusMillis(1), LEASE)).as("선점 동안").isZero();
        assertThat(inbox.claim(eventId, now.plus(LEASE), LEASE)).as("처리기가 선점 안에 못 끝내면 다시 선점").isEqualTo(1);
    }

    @Test
    void repollCandidatesAreDueReceivedRowsOrderedByNextAttemptThenReceivedAt() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID late = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH.plusSeconds(3));
            UUID earlyFirst = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH.plusSeconds(1));
            UUID earlySecond = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH.plusSeconds(2));
            // 두 행의 다음 시도를 같은 시각으로 맞춘다: 같으면 received_at 순
            inbox.recordHandlerFailure(earlySecond, 0, EPOCH.plusSeconds(1), "IllegalStateException");
            UUID future = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH.plusSeconds(100));
            UUID done = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            inbox.markProcessed(done, EPOCH);
            Instant now = EPOCH.plusSeconds(3);

            assertThat(inbox.findDue(now, BATCH)).extracting(InboxEvent::eventId).containsExactly(earlyFirst, earlySecond);
            assertThat(inbox.findDue(now, 10)).extracting(InboxEvent::eventId).containsExactly(earlyFirst, earlySecond, late);
            assertThat(inbox.findDue(now, 10)).extracting(InboxEvent::eventId).doesNotContain(future, done);
            return null;
        });
    }

    @Test
    void handlerOutcomesRetryWithBackoffThenProcessOrDie() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID processed = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            assertThat(inbox.markProcessed(processed, EPOCH.plusSeconds(1))).isEqualTo(1);
            InboxEvent done = inbox.findById(processed).orElseThrow();
            assertThat(done.status()).isEqualTo("PROCESSED");
            assertThat(done.processedAt()).isEqualTo(EPOCH.plusSeconds(1));
            assertThat(done.payloadJson().replace(" ", "")).isEqualTo("{}");
            assertThat(done.purgedAt()).isEqualTo(EPOCH.plusSeconds(1));
            assertThat(inbox.markProcessed(processed, EPOCH.plusSeconds(2))).isZero();
            assertThat(inbox.claim(processed, EPOCH.plusSeconds(2), LEASE)).isZero();

            UUID failing = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            long[] backoffSeconds = {1, 2, 4, 8, 16};
            Instant at = EPOCH;
            for (int count = 0; count < 5; count++) {
                at = at.plusSeconds(backoffSeconds[count]);
                assertThat(inbox.recordHandlerFailure(failing, count, at, "IllegalStateException")).isEqualTo(1);
            }
            assertThat(inbox.recordHandlerFailure(failing, 2, at, "IllegalStateException")).as("낡은 횟수").isZero();
            InboxEvent retried = inbox.findById(failing).orElseThrow();
            assertThat(retried.retryCount()).isEqualTo(5);
            assertThat(retried.nextAttemptAt()).isEqualTo(EPOCH.plusSeconds(31));
            // 6번째 실패는 DEAD, retry_count 5 유지(BR5.8)
            assertThat(inbox.markDead(failing, "IllegalStateException")).isEqualTo(1);
            InboxEvent dead = inbox.findById(failing).orElseThrow();
            assertThat(dead.status()).isEqualTo("DEAD");
            assertThat(dead.retryCount()).isEqualTo(5);
            assertThat(inbox.markDead(failing, "IllegalStateException")).isZero();
            return null;
        });
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID id = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            for (int count = 0; count < 5; count++) {
                inbox.recordHandlerFailure(id, count, EPOCH.plusSeconds(count + 1L), "IllegalStateException");
            }
            assertThatThrownBy(() -> inbox.recordHandlerFailure(id, 5, EPOCH.plusSeconds(60), "IllegalStateException"))
                    .as("V1 검사: retry_count ≤ 5").isInstanceOf(DataIntegrityViolationException.class);
            return null;
        });
    }

    @Test
    void startupPullsEveryReceivedRowForwardKeepingRetryCounts() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID claimed = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            inbox.claim(claimed, EPOCH, LEASE);
            UUID retrying = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            inbox.recordHandlerFailure(retrying, 0, EPOCH.plusSeconds(16), "IllegalStateException");
            UUID processed = receivedInbox(guardianId, EventType.SESSION_STATE_CHANGED, EPOCH);
            inbox.markProcessed(processed, EPOCH);
            Instant start = EPOCH.plusSeconds(2);

            assertThat(inbox.pullForwardReceived(start)).isGreaterThanOrEqualTo(2);
            assertThat(inbox.findById(claimed).orElseThrow().nextAttemptAt()).isEqualTo(start);
            InboxEvent pulled = inbox.findById(retrying).orElseThrow();
            assertThat(pulled.nextAttemptAt()).isEqualTo(start);
            assertThat(pulled.retryCount()).isEqualTo(1);
            assertThat(inbox.findById(processed).orElseThrow().nextAttemptAt()).isNotEqualTo(start);
            assertThat(inbox.claim(claimed, start, LEASE)).isEqualTo(1);
            return null;
        });
    }

    // ------------------------------------------------------------------ 값 형식

    @Test
    void valueTypesRequireGuardianAndValidTraceIdsAndHidePayloads() {
        assertThatThrownBy(() -> new NewOutboxEvent(UUID.randomUUID(), "DeviceReleased", 1, EPOCH, "Device",
                UUID.randomUUID(), null, "{}", randomHash(), null)).as("A4").isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewOutboxEvent(UUID.randomUUID(), "DeviceReleased", 1, EPOCH, "Device",
                UUID.randomUUID(), UUID.randomUUID(), "{}", randomHash(), "NOT-A-TRACE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewOutboxEvent(UUID.randomUUID(), "DeviceReleased", 0, EPOCH, "Device",
                UUID.randomUUID(), UUID.randomUUID(), "{}", randomHash(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewInboxEvent(UUID.randomUUID(), "DeviceStatusChanged", 1, EPOCH, UUID.randomUUID(),
                null, "{}", randomHash(), "ABC", EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewInboxEvent(UUID.randomUUID(), " ", 1, EPOCH, UUID.randomUUID(), null, "{}",
                randomHash(), null, EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewInboxEvent(UUID.randomUUID(), "DeviceStatusChanged", 0, EPOCH, UUID.randomUUID(),
                null, "{}", randomHash(), null, EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.claimDue(EPOCH, LEASE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.claimDue(EPOCH, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.findDue(EPOCH, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.claim(UUID.randomUUID(), EPOCH, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.markFailed(UUID.randomUUID(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.recordRetry(UUID.randomUUID(), -1, EPOCH, "X"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.recordHandlerFailure(UUID.randomUUID(), -1, EPOCH, "X"))
                .isInstanceOf(IllegalArgumentException.class);

        NewOutboxEvent secret = new NewOutboxEvent(UUID.randomUUID(), "DeviceReleased", 1, EPOCH, "Device", UUID.randomUUID(),
                UUID.randomUUID(), "{\"transcript\":\"비밀 대화\"}", randomHash(), null);
        assertThat(secret.toString()).doesNotContain("비밀 대화");
        NewInboxEvent received = inboxEvent(UUID.randomUUID(), "SessionRecordCaptured", UUID.randomUUID(), EPOCH);
        assertThat(received.toString()).doesNotContain("transcript");
    }

    // ------------------------------------------------------------------ 도우미

    private static NewOutboxEvent event(UUID eventId, EventType type, UUID guardianId) {
        return new NewOutboxEvent(eventId, type.wire(), 1, EPOCH, type.aggregateType().wire(), UUID.randomUUID(), guardianId,
                "{\"deviceId\":\"" + UUID.randomUUID() + "\"}", randomHash(), randomTraceId());
    }

    private UUID pendingOutbox(UUID guardianId, EventType type, Instant createdAt) {
        UUID id = UUID.randomUUID();
        outbox.insert(event(id, type, guardianId), createdAt);
        return id;
    }

    private UUID failedOutbox(UUID guardianId, EventType type, String lastError, Instant createdAt) {
        UUID id = pendingOutbox(guardianId, type, createdAt);
        assertThat(outbox.markFailed(id, lastError)).isEqualTo(1);
        return id;
    }

    private static NewInboxEvent inboxEvent(UUID eventId, String type, UUID guardianId, Instant receivedAt) {
        return new NewInboxEvent(eventId, type, 1, receivedAt.minusMillis(5), UUID.randomUUID(), guardianId,
                "{\"transcript\":\"아이 말 원문\"}", randomHash(), randomTraceId(), receivedAt);
    }

    private UUID receivedInbox(UUID guardianId, EventType type, Instant receivedAt) {
        UUID id = UUID.randomUUID();
        assertThat(inbox.insertReceived(inboxEvent(id, type.wire(), guardianId, receivedAt))).isTrue();
        return id;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("15초 안에 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리는 중 인터럽트", e);
        }
    }
}
