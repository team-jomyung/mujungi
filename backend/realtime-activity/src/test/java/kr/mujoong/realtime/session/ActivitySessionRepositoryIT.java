package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.session.ActivitySessionRepository.MultiOriginTransition;
import kr.mujoong.realtime.session.ActivitySessionRepository.MultiOriginTransition.Outcome;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Realtime 세션 행 데이터 접근 통합 시험(U1 3.5 Step 18). 실제 init 스크립트 + 두 마이그레이션 위치의 DB에 앱 역할로 붙는다.
 *
 * <p>증명: BR6.6 ④(INSERT 멱등 · 진행 중 충돌은 0행이라 id 재읽기로 202/409를 가림) · BR6.8(전이 승자 하나 · 0행이면 진 것 ·
 * status_version · row_version 증가 · 종료 상태는 되돌아가지 않음 · 출발 상태가 둘 이상인 FAILED 전이는 FOR UPDATE로 읽은 상태로
 * 실패 코드를 정함) · BR6.13(재시작 정리 SERVER_RESTART) · BR6.3(Realtime 2차 방어 조회) · RD-22(잠금은 트랜잭션 안에서만).
 */
@Tag("skeleton")
@Tag("db")
class ActivitySessionRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final ConsentSnapshot CONSENT = new ConsentSnapshot("1.0", true, true, false, false);
    private static final ChildContext CHILD = new ChildContext("하늘", 8, List.of("공룡", "그림"));
    private static final Set<SessionStatus> TIMEOUT_ORIGINS = EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK);

    private static RealtimeDb db;
    private ActivitySessionRepository sessions;

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
        sessions = new ActivitySessionRepository(db.jdbc());
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    // ------------------------------------------------------------------ INSERT (BR6.6 ④)

    @Test
    void insertWritesAPreparingRowWithTheConsentSnapshotAndItsDerivedColumns() {
        NewActivitySession session = newSession(UUID.randomUUID(), UUID.randomUUID());

        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();

        ActivitySessionRow row = sessions.find(session.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(row.statusVersion()).isEqualTo(1);
        assertThat(row.rowVersion()).isZero();
        assertThat(row.guardianId()).isEqualTo(session.guardianId());
        assertThat(row.childId()).isEqualTo(session.childId());
        assertThat(row.deviceId()).isEqualTo(session.deviceId());
        assertThat(row.activityType()).isEqualTo(ActivityType.FREE_TALK);
        assertThat(row.startIdempotencyKey()).isEqualTo(session.startIdempotencyKey());
        assertThat(row.consentSnapshot()).isEqualTo(CONSENT);
        assertThat(row.policyVersion()).isEqualTo("1.0");
        assertThat(row.dataCollectionAllowed()).isTrue();
        assertThat(row.transcriptStorageAllowed()).isTrue();
        assertThat(row.consentVersion()).isNull();
        assertThat(row.childContext()).isEqualTo(CHILD);
        assertThat(row.closingContentVersionId()).isEqualTo(session.closingContentVersionId());
        assertThat(row.introContentVersionId()).isNull();
        assertThat(row.consentCheckedAt()).isEqualTo(T0);
        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.updatedAt()).isEqualTo(T0);
        assertThat(row.endReason()).isNull();
        assertThat(row.failureCode()).isNull();
        assertThat(row.confirmed()).isNull();
        assertThat(row.providerSessionId()).isNull();
        // 문자열 표현에 아이 맥락 · 동의 값이 없다(BR8.7)
        assertThat(row.toString()).doesNotContain("하늘").doesNotContain("공룡");
    }

    @Test
    void consentWithoutVoiceAndTextIsStoredWithFalseDerivedColumns() {
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("2.1", false, true, true, false), new ChildContext("별", null, null), null, null);

        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();

        ActivitySessionRow row = sessions.find(session.id()).orElseThrow();
        assertThat(row.dataCollectionAllowed()).isFalse();
        assertThat(row.transcriptStorageAllowed()).isFalse();
        assertThat(row.policyVersion()).isEqualTo("2.1");
        assertThat(row.childContext()).isEqualTo(new ChildContext("별", null, List.of()));
        assertThat(row.closingContentVersionId()).isNull();
    }

    @Test
    void sameSessionIdIsANoOpThatKeepsTheFirstRow() {
        NewActivitySession first = newSession(UUID.randomUUID(), UUID.randomUUID());
        assertThat(db.inTransaction(() -> sessions.insert(first, T0))).isTrue();

        // 시간 초과 재시도가 같은 id로 늦게 와도(다른 시각) 실패하지 않고 0행이다
        NewActivitySession retry = new NewActivitySession(first.id(), first.guardianId(), first.childId(), first.deviceId(),
                ActivityType.FREE_TALK, first.startIdempotencyKey(), CONSENT, CHILD, null, null);
        assertThat(db.inTransaction(() -> sessions.insert(retry, T0.plusSeconds(3)))).isFalse();

        ActivitySessionRow row = sessions.find(first.id()).orElseThrow();
        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.closingContentVersionId()).isEqualTo(first.closingContentVersionId());
        assertThat(row.statusVersion()).isEqualTo(1);
    }

    @Test
    void anInProgressGuardianOrDeviceBlocksANewSessionWithoutBreakingTheTransaction() {
        UUID guardian = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        NewActivitySession running = newSession(guardian, device);
        assertThat(db.inTransaction(() -> sessions.insert(running, T0))).isTrue();

        NewActivitySession sameGuardian = newSession(guardian, UUID.randomUUID());
        NewActivitySession sameDevice = newSession(UUID.randomUUID(), device);
        // 부분 유일 색인 충돌도 0행이고 트랜잭션은 계속 쓸 수 있다 → id로 다시 읽어 없으면 409 SESSION_IN_PROGRESS
        Optional<ActivitySessionRow> reread = db.inTransaction(() -> {
            assertThat(sessions.insert(sameGuardian, T0)).isFalse();
            assertThat(sessions.insert(sameDevice, T0)).isFalse();
            return sessions.find(sameGuardian.id());
        });
        assertThat(reread).isEmpty();
        assertThat(sessions.find(sameDevice.id())).isEmpty();
        assertThat(sessions.findInProgress(guardian, UUID.randomUUID())).extracting(ActivitySessionRow::id)
                .containsExactly(running.id());
        assertThat(sessions.findInProgress(UUID.randomUUID(), device)).extracting(ActivitySessionRow::id)
                .containsExactly(running.id());

        // 끝난 세션은 막지 않는다
        db.inTransaction(() -> sessions.transitionFromAny(running.id(), TIMEOUT_ORIGINS, SessionStatus.FAILED,
                T0.plusSeconds(20), status -> SessionChange.failed(FailureCode.DEVICE_NO_ACK, T0.plusSeconds(20))));
        assertThat(sessions.findInProgress(guardian, device)).isEmpty();
        assertThat(db.inTransaction(() -> sessions.insert(sameGuardian, T0.plusSeconds(30)))).isTrue();
    }

    // ------------------------------------------------------------------ 전이 (BR6.8)

    @Test
    void concurrentTransitionsFromTheSameStatusHaveExactlyOneWinner() throws Exception {
        NewActivitySession session = insertPreparing();
        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Optional<ActivitySessionRow>>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                SessionStatus target = i % 2 == 0 ? SessionStatus.DEVICE_ACK : SessionStatus.FAILED;
                SessionChange change = target == SessionStatus.FAILED
                        ? SessionChange.failed(FailureCode.DEVICE_NO_ACK, T0.plusSeconds(1))
                        : SessionChange.none();
                results.add(pool.submit(() -> {
                    start.await();
                    return sessions.transition(session.id(), SessionStatus.PREPARING, target, T0.plusSeconds(1), change);
                }));
            }
            start.countDown();
            List<ActivitySessionRow> winners = new ArrayList<>();
            for (Future<Optional<ActivitySessionRow>> result : results) {
                result.get(10, TimeUnit.SECONDS).ifPresent(winners::add);
            }
            assertThat(winners).hasSize(1);
            ActivitySessionRow stored = sessions.find(session.id()).orElseThrow();
            assertThat(stored.status()).isEqualTo(winners.get(0).status());
            assertThat(stored.statusVersion()).isEqualTo(2);
            assertThat(stored.rowVersion()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLostTransitionReturnsNothingAndLeavesTheWinnersRow() {
        NewActivitySession session = insertPreparing();
        assertThat(sessions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK,
                T0.plusSeconds(1), SessionChange.none())).isPresent();

        // 다른 전이가 이긴 뒤의 시도: 0행 → 호출자는 다시 읽고 SessionEvent에 기록만 한다
        assertThat(sessions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.FAILED, T0.plusSeconds(2),
                SessionChange.failed(FailureCode.DEVICE_NO_ACK, T0.plusSeconds(2)))).isEmpty();
        assertThat(sessions.transition(UUID.randomUUID(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK,
                T0.plusSeconds(2), SessionChange.none())).isEmpty();

        ActivitySessionRow stored = sessions.find(session.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SessionStatus.DEVICE_ACK);
        assertThat(stored.statusVersion()).isEqualTo(2);
        assertThat(stored.rowVersion()).isEqualTo(1);
        assertThat(stored.endReason()).isNull();
        assertThat(stored.updatedAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void theU1HappyPathRaisesBothVersionsAndKeepsEveryRecordedTime() {
        NewActivitySession session = insertPreparing();
        Instant ack = T0.plusSeconds(1);
        Instant active = T0.plusSeconds(2);
        Instant closing = T0.plusSeconds(30);
        Instant stopped = T0.plusSeconds(31);
        Instant ended = T0.plusSeconds(33);

        sessions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, ack, SessionChange.none())
                .orElseThrow();
        sessions.transition(session.id(), SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE, active,
                SessionChange.none().withStartedAt(active).withProviderSessionId("provider-session-1")).orElseThrow();
        sessions.transition(session.id(), SessionStatus.ACTIVE, SessionStatus.CLOSING, closing,
                SessionChange.none().withTerminationRequestedAt(closing)).orElseThrow();
        // CLOSING 중 보조 기록: 상태 · status_version은 그대로, row_version만 오른다. started_at은 처음 값을 지킨다
        ActivitySessionRow annotated = sessions.annotate(session.id(), SessionStatus.CLOSING, closing.plusSeconds(1),
                SessionChange.none().withTransportClosedAt(closing.plusSeconds(1)).withStartedAt(closing)).orElseThrow();
        assertThat(annotated.statusVersion()).isEqualTo(4);
        assertThat(annotated.rowVersion()).isEqualTo(4);
        ActivitySessionRow done = sessions.transition(session.id(), SessionStatus.CLOSING, SessionStatus.ENDED, ended,
                SessionChange.ended(EndReason.GUARDIAN_STOP, ended).withOutputStoppedAt(stopped)
                        .withProviderClosedAt(ended)).orElseThrow();

        assertThat(done.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(done.statusVersion()).isEqualTo(5);
        assertThat(done.rowVersion()).isEqualTo(5);
        assertThat(done.confirmed()).isTrue();
        assertThat(done.endReason()).isEqualTo(EndReason.GUARDIAN_STOP);
        assertThat(done.failureCode()).isNull();
        assertThat(done.startedAt()).isEqualTo(active);
        assertThat(done.terminationRequestedAt()).isEqualTo(closing);
        assertThat(done.transportClosedAt()).isEqualTo(closing.plusSeconds(1));
        assertThat(done.outputStoppedAt()).isEqualTo(stopped);
        assertThat(done.providerClosedAt()).isEqualTo(ended);
        assertThat(done.endedAt()).isEqualTo(ended);
        assertThat(done.providerSessionId()).isEqualTo("provider-session-1");
        assertThat(done.updatedAt()).isEqualTo(ended);
        assertThat(done.toString()).doesNotContain("provider-session-1");
    }

    @Test
    void terminalStatusesNeverTransitionAndLateAnnotationsAreDropped() {
        NewActivitySession session = insertPreparing();
        sessions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.FAILED, T0.plusSeconds(1),
                SessionChange.failed(null, T0.plusSeconds(1))).orElseThrow();

        assertThatThrownBy(() -> sessions.transition(session.id(), SessionStatus.FAILED, SessionStatus.ACTIVE,
                T0.plusSeconds(2), SessionChange.none())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessions.transition(session.id(), SessionStatus.ACTIVE, SessionStatus.ACTIVE,
                T0.plusSeconds(2), SessionChange.none())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.REQUESTED,
                T0.plusSeconds(2), SessionChange.none())).isInstanceOf(IllegalArgumentException.class);
        assertThat(sessions.annotate(session.id(), SessionStatus.CLOSING, T0.plusSeconds(3),
                SessionChange.none().withOutputStoppedAt(T0.plusSeconds(3)))).isEmpty();

        // Core 취소 정리로 닫힌 FAILED는 실패 코드가 비어 있다(BR6.7)
        ActivitySessionRow stored = sessions.find(session.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(stored.endReason()).isEqualTo(EndReason.FAILED);
        assertThat(stored.failureCode()).isNull();
        assertThat(stored.outputStoppedAt()).isNull();
        assertThat(stored.statusVersion()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 다중 출발 FAILED (BR6.8 · BR6.12 ②)

    @Test
    void multiOriginFailureReadsTheStatusUnderLockAndPicksTheFailureCode() {
        NewActivitySession preparing = insertPreparing();
        NewActivitySession acked = insertPreparing();
        sessions.transition(acked.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0.plusSeconds(1),
                SessionChange.none()).orElseThrow();
        Instant deadline = T0.plusSeconds(20);

        MultiOriginTransition fromAck = db.inTransaction(() -> sessions.transitionFromAny(acked.id(), TIMEOUT_ORIGINS,
                SessionStatus.FAILED, deadline, status -> SessionChange.failed(failureFor(status), deadline)));
        MultiOriginTransition fromPreparing = db.inTransaction(() -> sessions.transitionFromAny(preparing.id(),
                TIMEOUT_ORIGINS, SessionStatus.FAILED, deadline,
                status -> SessionChange.failed(failureFor(status), deadline)));

        assertThat(fromAck.transitioned()).isTrue();
        assertThat(fromAck.from()).isEqualTo(SessionStatus.DEVICE_ACK);
        assertThat(fromAck.row().failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(fromAck.row().endReason()).isEqualTo(EndReason.FAILED);
        assertThat(fromAck.row().endedAt()).isEqualTo(deadline);
        assertThat(fromAck.row().statusVersion()).isEqualTo(3);
        assertThat(fromPreparing.from()).isEqualTo(SessionStatus.PREPARING);
        assertThat(fromPreparing.row().failureCode()).isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
        assertThat(fromPreparing.row().statusVersion()).isEqualTo(2);
    }

    @Test
    void multiOriginFailureHoldsTheRowLockSoTheCompetingTransitionLosesAfterwards() throws Exception {
        NewActivitySession session = insertPreparing();
        sessions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0.plusSeconds(1),
                SessionChange.none()).orElseThrow();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A: 20초 시한 → FOR UPDATE로 잠근 채 머문다
            Future<MultiOriginTransition> timeout = pool.submit(() -> db.inTransaction(() -> {
                MultiOriginTransition result = sessions.transitionFromAny(session.id(), TIMEOUT_ORIGINS,
                        SessionStatus.FAILED, T0.plusSeconds(20),
                        status -> SessionChange.failed(failureFor(status), T0.plusSeconds(20)));
                locked.countDown();
                await(release);
                return result;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            // B: 같은 순간 도착한 DEVICE_READY → ACTIVE 전이는 잠금을 기다린다
            Future<Optional<ActivitySessionRow>> ready = pool.submit(() -> sessions.transition(session.id(),
                    SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE, T0.plusSeconds(20), SessionChange.none()));
            assertThatThrownBy(() -> ready.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            assertThat(timeout.get(10, TimeUnit.SECONDS).transitioned()).isTrue();
            assertThat(ready.get(10, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        ActivitySessionRow stored = sessions.find(session.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(stored.failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(stored.statusVersion()).isEqualTo(3);
    }

    @Test
    void multiOriginFailureIgnoresStatusesOutsideItsOriginsAndMissingRows() {
        NewActivitySession session = insertPreparing();
        sessions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0.plusSeconds(1),
                SessionChange.none()).orElseThrow();
        sessions.transition(session.id(), SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE, T0.plusSeconds(2),
                SessionChange.none().withStartedAt(T0.plusSeconds(2))).orElseThrow();

        // 20초 시한이 만난 ACTIVE는 이미 충족된 것이라 무시한다
        MultiOriginTransition late = db.inTransaction(() -> sessions.transitionFromAny(session.id(), TIMEOUT_ORIGINS,
                SessionStatus.FAILED, T0.plusSeconds(20), status -> SessionChange.failed(failureFor(status),
                        T0.plusSeconds(20))));
        MultiOriginTransition missing = db.inTransaction(() -> sessions.transitionFromAny(UUID.randomUUID(),
                TIMEOUT_ORIGINS, SessionStatus.FAILED, T0.plusSeconds(20), status -> SessionChange.none()));

        assertThat(late.outcome()).isEqualTo(Outcome.NOT_IN_ORIGINS);
        assertThat(late.transitioned()).isFalse();
        assertThat(late.from()).isNull();
        assertThat(late.row().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(missing.outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(missing.row()).isNull();
        assertThat(sessions.find(session.id()).orElseThrow().statusVersion()).isEqualTo(3);
        assertThatThrownBy(() -> db.inTransaction(() -> sessions.transitionFromAny(session.id(), Set.of(),
                SessionStatus.FAILED, T0, status -> SessionChange.none()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rowLocksAreOnlyTakenInsideABusinessTransaction() {
        NewActivitySession session = insertPreparing();

        assertThatThrownBy(() -> sessions.findForUpdate(session.id()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> sessions.transitionFromAny(session.id(), TIMEOUT_ORIGINS, SessionStatus.FAILED, T0,
                status -> SessionChange.none())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(db.inTransaction(() -> sessions.findForUpdate(session.id()))).isPresent();
        assertThat(sessions.find(session.id()).orElseThrow().status()).isEqualTo(SessionStatus.PREPARING);
    }

    @Test
    void restartClosesEveryInProgressSessionAsServerRestartAndLeavesEndedOnes() {
        NewActivitySession preparing = insertPreparing();
        NewActivitySession active = insertPreparing();
        NewActivitySession ended = insertPreparing();
        sessions.transition(active.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0, SessionChange.none());
        sessions.transition(active.id(), SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE, T0, SessionChange.none());
        sessions.transition(ended.id(), SessionStatus.PREPARING, SessionStatus.FAILED, T0,
                SessionChange.failed(FailureCode.DEVICE_NO_ACK, T0));
        Instant restart = T0.plus(Duration.ofMinutes(5));

        List<ActivitySessionRow> inProgress = sessions.findInProgress();
        assertThat(inProgress).extracting(ActivitySessionRow::id).containsExactlyInAnyOrder(preparing.id(), active.id());
        // S3a: 한 트랜잭션에서 모두 FAILED(SERVER_RESTART)로
        List<MultiOriginTransition> results = db.inTransaction(() -> inProgress.stream()
                .map(row -> sessions.transitionFromAny(row.id(), ActivitySessionRepository.IN_PROGRESS,
                        SessionStatus.FAILED, restart, status -> SessionChange.failed(FailureCode.SERVER_RESTART, restart)))
                .toList());

        assertThat(results).allMatch(MultiOriginTransition::transitioned);
        assertThat(sessions.findInProgress()).isEmpty();
        assertThat(sessions.find(active.id()).orElseThrow().failureCode()).isEqualTo(FailureCode.SERVER_RESTART);
        assertThat(sessions.find(ended.id()).orElseThrow().failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
    }

    // ------------------------------------------------------------------ 도우미

    private NewActivitySession insertPreparing() {
        NewActivitySession session = newSession(UUID.randomUUID(), UUID.randomUUID());
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        return session;
    }

    private static NewActivitySession newSession(UUID guardianId, UUID deviceId) {
        return new NewActivitySession(UUID.randomUUID(), guardianId, UUID.randomUUID(), deviceId, ActivityType.FREE_TALK,
                UUID.randomUUID().toString(), CONSENT, CHILD, UUID.randomUUID(), null);
    }

    /** BR6.12 ②: ACK가 왔으면(DEVICE_ACK) 기기 응답 없음, 아직 PREPARING이면 공급자 쪽 실패로 둔 시험용 규칙. */
    private static FailureCode failureFor(SessionStatus status) {
        return status == SessionStatus.DEVICE_ACK ? FailureCode.DEVICE_NO_ACK : FailureCode.PROVIDER_UNAVAILABLE;
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
