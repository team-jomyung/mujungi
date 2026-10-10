package kr.mujoong.core.activity;

import static kr.mujoong.core.common.db.DbTestSupport.EPOCH;
import static kr.mujoong.core.common.db.DbTestSupport.await;
import static kr.mujoong.core.common.db.DbTestSupport.randomCode;
import static kr.mujoong.core.common.db.DbTestSupport.randomHash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.ActivityCopyRepository.NewActivity;
import kr.mujoong.core.activity.ActivityCopyRepository.StateChange;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.device.DeviceJdbcRepository.PendingDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link ActivityCopyRepository} 통합 시험(U1 3.5 Step 17).
 *
 * <p>증명: BR6.2 · AC3.1.8(같은 키 동시 삽입 → 승자 재읽기), BR6.3(보호자당 진행 중 하나 · 유일 위반이 트랜잭션을 깨지 않음),
 * BR5.10(statusVersion으로 최신만 · 역순 도착 · local_failed 제외), BR6.14(started_at만 갱신 · ended_at · observed_at),
 * BR6.6 · BR6.7 · BR6.12 ①(로컬 FAILED는 REQUESTED · 버전 0일 때만), BR6.1 ②(대조로 닫을 때 버전 유지), BR6.12 ① 후보 조회 경계,
 * BR8.5(남의 세션은 없는 것과 같다).
 */
@Tag("u1")
@Tag("db")
class ActivityCopyRepositoryIT {

    private DbTestSupport db;
    private ActivityCopyRepository activities;
    private DeviceJdbcRepository devices;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        activities = new ActivityCopyRepository(db.jdbc());
        devices = new DeviceJdbcRepository(db.jdbc());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void insertedRequestedCopyIsReadableByKeyOwnerAndInProgress() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity request = request(home, "key-" + UUID.randomUUID(), EPOCH);
            assertThat(activities.insertRequested(request)).isTrue();

            ActivityCopy copy = activities.findByIdempotencyKey(home.guardianId(), request.idempotencyKey()).orElseThrow();
            assertThat(copy.sessionId()).isEqualTo(request.sessionId());
            assertThat(copy.status()).isEqualTo(SessionStatus.REQUESTED);
            assertThat(copy.statusVersion()).isZero();
            assertThat(copy.activityType()).isEqualTo(ActivityType.FREE_TALK);
            assertThat(copy.localFailed()).isFalse();
            assertThat(copy.startResultCode()).isNull();
            assertThat(copy.requestHash()).isEqualTo(request.requestHash());
            assertThat(copy.requestedAt()).isEqualTo(EPOCH);
            assertThat(copy.startedAt()).isNull();
            assertThat(copy.observedAt()).isNull();
            assertThat(copy.isInProgress()).isTrue();
            assertThat(copy.toString()).doesNotContain(request.idempotencyKey());

            assertThat(activities.findInProgressByGuardian(home.guardianId())).map(ActivityCopy::sessionId)
                    .contains(request.sessionId());
            assertThat(activities.findOwned(request.sessionId(), home.guardianId())).isPresent();
            assertThat(activities.findOwned(request.sessionId(), UUID.randomUUID())).as("남의 세션은 없는 것과 같다").isEmpty();
            assertThat(activities.findById(request.sessionId())).isPresent();
            assertThat(activities.findById(UUID.randomUUID())).isEmpty();
            return null;
        });
    }

    @Test
    void concurrentInsertsWithTheSameKeyLeaveOneRowAndTheLoserRereadsTheWinner() {
        Household home = committedHousehold();
        String key = "key-" + UUID.randomUUID();
        NewActivity winner = request(home, key, EPOCH);
        NewActivity loser = request(home, key, EPOCH.plusMillis(5));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger winnerPid = new AtomicInteger();
        AtomicInteger loserPid = new AtomicInteger();

        Future<Boolean> first = db.startTx(winnerPid, status -> {
            boolean ok = activities.insertRequested(winner);
            inserted.countDown();
            awaitLatch(release);
            return ok;
        });
        awaitLatch(inserted);
        Future<Optional<ActivityCopy>> second = db.startTx(loserPid, status -> {
            assertThat(activities.insertRequested(loser)).as("같은 키는 새 세션을 만들지 않는다").isFalse();
            return activities.findByIdempotencyKey(home.guardianId(), key);
        });
        db.awaitLockWait(loserPid);
        release.countDown();

        assertThat(await(first)).isTrue();
        assertThat(await(second)).map(ActivityCopy::sessionId).contains(winner.sessionId());
        assertThat(activities.findById(loser.sessionId())).isEmpty();
    }

    @Test
    void aSecondKeyWhileOneSessionIsInProgressIsRejectedWithoutBreakingTheTransaction() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity first = request(home, "first-" + UUID.randomUUID(), EPOCH);
            NewActivity second = request(home, "second-" + UUID.randomUUID(), EPOCH.plusSeconds(1));
            assertThat(activities.insertRequested(first)).isTrue();

            // 진행 중 하나의 부분 유일 위반: 같은 키 행이 없으니 409 SESSION_IN_PROGRESS로 번역한다(BR6.3)
            assertThat(activities.insertRequested(second)).isFalse();
            assertThat(activities.findByIdempotencyKey(home.guardianId(), second.idempotencyKey())).isEmpty();

            // 트랜잭션은 깨지지 않았다. 첫 세션이 끝나면 새 키로 시작할 수 있다
            assertThat(activities.applyStateChange(change(first.sessionId(), SessionStatus.ENDED, 6, EPOCH.plusSeconds(60)),
                    EPOCH.plusSeconds(61))).isEqualTo(1);
            assertThat(activities.findInProgressByGuardian(home.guardianId())).isEmpty();
            assertThat(activities.insertRequested(second)).isTrue();
            return null;
        });
    }

    @Test
    void stateChangesApplyOnlyNewerVersionsAndAnyArrivalOrderEndsTheSame() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity inOrder = request(home, "a-" + UUID.randomUUID(), EPOCH);
            activities.insertRequested(inOrder);
            Instant activeAt = EPOCH.plusSeconds(3);
            Instant endedAt = EPOCH.plusSeconds(50);

            assertThat(activities.applyStateChange(change(inOrder.sessionId(), SessionStatus.ACTIVE, 3, activeAt),
                    activeAt.plusMillis(10))).isEqualTo(1);
            assertThat(activities.applyStateChange(change(inOrder.sessionId(), SessionStatus.PREPARING, 1, EPOCH.plusSeconds(1)),
                    activeAt.plusMillis(20))).as("낮은 버전").isZero();
            assertThat(activities.applyStateChange(change(inOrder.sessionId(), SessionStatus.ACTIVE, 3, activeAt),
                    activeAt.plusMillis(30))).as("같은 버전").isZero();
            StateChange ended = new StateChange(inOrder.sessionId(), SessionStatus.ENDED, 5, EndReason.GUARDIAN_STOP, null,
                    true, endedAt);
            assertThat(activities.applyStateChange(ended, endedAt.plusMillis(5))).isEqualTo(1);
            assertThat(activities.applyStateChange(change(inOrder.sessionId(), SessionStatus.CLOSING, 4, endedAt.minusSeconds(2)),
                    endedAt.plusMillis(9))).isZero();

            ActivityCopy copy = activities.findById(inOrder.sessionId()).orElseThrow();
            assertThat(copy.status()).isEqualTo(SessionStatus.ENDED);
            assertThat(copy.statusVersion()).isEqualTo(5);
            assertThat(copy.endReason()).isEqualTo("GUARDIAN_STOP");
            assertThat(copy.failureCode()).isNull();
            assertThat(copy.confirmed()).isTrue();
            assertThat(copy.startedAt()).isEqualTo(activeAt);
            assertThat(copy.endedAt()).isEqualTo(endedAt);
            assertThat(copy.observedAt()).as("사본을 마지막으로 갱신한 처리 시각").isEqualTo(endedAt.plusMillis(5));
            assertThat(copy.isInProgress()).isFalse();
            return null;
        });
    }

    @Test
    void eventsArrivingInReverseOrderLeaveTheSameFinalCopy() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity reversed = request(home, "r-" + UUID.randomUUID(), EPOCH);
            activities.insertRequested(reversed);
            StateChange failed = new StateChange(reversed.sessionId(), SessionStatus.FAILED, 4, EndReason.FAILED,
                    FailureCode.PROVIDER_UNAVAILABLE, false, EPOCH.plusSeconds(20));

            assertThat(activities.applyStateChange(failed, EPOCH.plusSeconds(21))).isEqualTo(1);
            for (int version = 3; version >= 1; version--) {
                SessionStatus earlier = version == 3 ? SessionStatus.DEVICE_ACK : SessionStatus.PREPARING;
                assertThat(activities.applyStateChange(change(reversed.sessionId(), earlier, version, EPOCH.plusSeconds(version)),
                        EPOCH.plusSeconds(22))).isZero();
            }
            ActivityCopy copy = activities.findById(reversed.sessionId()).orElseThrow();
            assertThat(copy.status()).isEqualTo(SessionStatus.FAILED);
            assertThat(copy.statusVersion()).isEqualTo(4);
            assertThat(copy.failureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(copy.endedAt()).isEqualTo(EPOCH.plusSeconds(20));
            assertThat(copy.localFailed()).isFalse();
            return null;
        });
    }

    @Test
    void aStaleActiveEventFillsOnlyAnEmptyStartedAt() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity request = request(home, "s-" + UUID.randomUUID(), EPOCH);
            activities.insertRequested(request);
            // ACTIVE 이벤트를 건너뛰고 CLOSING이 먼저 반영됐다
            activities.applyStateChange(change(request.sessionId(), SessionStatus.CLOSING, 4, EPOCH.plusSeconds(30)),
                    EPOCH.plusSeconds(31));
            Instant activeAt = EPOCH.plusSeconds(5);

            assertThat(activities.applyStateChange(change(request.sessionId(), SessionStatus.ACTIVE, 3, activeAt),
                    EPOCH.plusSeconds(32))).isZero();
            assertThat(activities.fillStartedAtIfMissing(request.sessionId(), activeAt, EPOCH.plusSeconds(32))).isEqualTo(1);
            assertThat(activities.fillStartedAtIfMissing(request.sessionId(), EPOCH.plusSeconds(6), EPOCH.plusSeconds(33)))
                    .isZero();

            ActivityCopy copy = activities.findById(request.sessionId()).orElseThrow();
            assertThat(copy.startedAt()).isEqualTo(activeAt);
            assertThat(copy.status()).as("status · status_version은 건드리지 않는다").isEqualTo(SessionStatus.CLOSING);
            assertThat(copy.statusVersion()).isEqualTo(4);
            assertThat(copy.observedAt()).isEqualTo(EPOCH.plusSeconds(31));
            return null;
        });
    }

    @Test
    void localFailedIsWrittenOnlyFromRequestedVersionZeroAndThenIgnoresEvents() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity abandoned = request(home, "f-" + UUID.randomUUID(), EPOCH);
            activities.insertRequested(abandoned);
            Instant now = EPOCH.plusSeconds(4);

            assertThat(activities.closeLocalFailed(abandoned.sessionId(), SessionStatus.REQUESTED, 0, 503, now)).isEqualTo(1);
            ActivityCopy copy = activities.findById(abandoned.sessionId()).orElseThrow();
            assertThat(copy.status()).isEqualTo(SessionStatus.FAILED);
            assertThat(copy.endReason()).isEqualTo("FAILED");
            assertThat(copy.failureCode()).isNull();
            assertThat(copy.localFailed()).isTrue();
            assertThat(copy.startResultCode()).isEqualTo(503);
            assertThat(copy.statusVersion()).isZero();
            assertThat(copy.endedAt()).isEqualTo(now);

            // 로컬 FAILED 사본은 늦은 Realtime 이벤트로 바뀌지 않는다(BR5.10 · BR6.14). 처리기는 0행 뒤 다시 읽어 재취소를 정한다
            assertThat(activities.applyStateChange(change(abandoned.sessionId(), SessionStatus.PREPARING, 1, now), now))
                    .isZero();
            assertThat(activities.fillStartedAtIfMissing(abandoned.sessionId(), now, now)).isZero();
            assertThat(activities.closeLocalFailed(abandoned.sessionId(), SessionStatus.REQUESTED, 0, 202, now)).isZero();
            assertThat(activities.findById(abandoned.sessionId()).orElseThrow().startResultCode()).isEqualTo(503);
            return null;
        });
    }

    @Test
    void localFailedLosesWhenARealtimeEventAlreadyAdvancedTheCopy() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity started = request(home, "p-" + UUID.randomUUID(), EPOCH);
            activities.insertRequested(started);
            activities.applyStateChange(change(started.sessionId(), SessionStatus.PREPARING, 1, EPOCH.plusSeconds(1)),
                    EPOCH.plusSeconds(1));

            // BR6.12 ① · BR6.7: 0행이면 Realtime 이벤트가 앞선 것이므로 로컬 FAILED로 만들지 않는다
            assertThat(activities.closeLocalFailed(started.sessionId(), SessionStatus.REQUESTED, 0, 202, EPOCH.plusSeconds(10)))
                    .isZero();
            assertThat(activities.findById(started.sessionId()).orElseThrow().status()).isEqualTo(SessionStatus.PREPARING);
            return null;
        });
    }

    @Test
    void reconcileClosesAStaleNonRequestedCopyKeepingItsVersion() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity stale = request(home, "c-" + UUID.randomUUID(), EPOCH);
            activities.insertRequested(stale);
            activities.applyStateChange(change(stale.sessionId(), SessionStatus.ACTIVE, 3, EPOCH.plusSeconds(3)),
                    EPOCH.plusSeconds(3));
            Instant now = EPOCH.plus(Duration.ofHours(2));

            assertThat(activities.closeLocalFailed(stale.sessionId(), SessionStatus.ACTIVE, 2, 202, now))
                    .as("대조한 버전과 다르면 0행").isZero();
            assertThat(activities.closeLocalFailed(stale.sessionId(), SessionStatus.ACTIVE, 3, 202, now)).isEqualTo(1);
            ActivityCopy closed = activities.findById(stale.sessionId()).orElseThrow();
            assertThat(closed.status()).isEqualTo(SessionStatus.FAILED);
            assertThat(closed.statusVersion()).as("BR6.1 ② 대조로 닫은 행은 버전을 줄이지 않는다").isEqualTo(3);
            assertThat(closed.startResultCode()).isEqualTo(202);
            assertThat(closed.startedAt()).isEqualTo(EPOCH.plusSeconds(3));

            // 계정 잠김이 풀려 새 키로 시작할 수 있다
            assertThat(activities.insertRequested(request(home, "n-" + UUID.randomUUID(), now))).isTrue();
            return null;
        });
    }

    @Test
    void preparingTimeoutCandidatesAreRequestedVersionZeroRowsUpToTheCutoff() {
        db.inRolledBackTx(status -> {
            NewActivity oldest = request(household(), "o-" + UUID.randomUUID(), EPOCH);
            NewActivity newer = request(household(), "w-" + UUID.randomUUID(), EPOCH.plusSeconds(5));
            NewActivity advanced = request(household(), "v-" + UUID.randomUUID(), EPOCH);
            NewActivity alreadyFailed = request(household(), "x-" + UUID.randomUUID(), EPOCH);
            activities.insertRequested(oldest);
            activities.insertRequested(newer);
            activities.insertRequested(advanced);
            activities.insertRequested(alreadyFailed);
            activities.applyStateChange(change(advanced.sessionId(), SessionStatus.PREPARING, 1, EPOCH), EPOCH);
            activities.closeLocalFailed(alreadyFailed.sessionId(), SessionStatus.REQUESTED, 0, 503, EPOCH);

            assertThat(activities.findRequestedDueBefore(EPOCH.minusMillis(1), 10)).isEmpty();
            assertThat(activities.findRequestedDueBefore(EPOCH, 10)).extracting(ActivityCopy::sessionId)
                    .containsExactly(oldest.sessionId());
            assertThat(activities.findRequestedDueBefore(EPOCH.plusSeconds(5), 10)).extracting(ActivityCopy::sessionId)
                    .containsExactly(oldest.sessionId(), newer.sessionId());
            assertThat(activities.findRequestedDueBefore(EPOCH.plusSeconds(5), 1)).extracting(ActivityCopy::sessionId)
                    .containsExactly(oldest.sessionId());
            return null;
        });
    }

    @Test
    void valueTypesAndArgumentsAreValidated() {
        Household home = new Household(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> request(home, "k".repeat(101), EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewActivity(UUID.randomUUID(), home.guardianId(), home.childId(), home.deviceId(),
                ActivityType.UNRECOGNIZED, "k", randomHash(), EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> change(UUID.randomUUID(), SessionStatus.REQUESTED, 1, EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> change(UUID.randomUUID(), SessionStatus.ACTIVE, 0, EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> change(UUID.randomUUID(), SessionStatus.UNRECOGNIZED, 2, EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> activities.closeLocalFailed(UUID.randomUUID(), SessionStatus.ENDED, 0, 503, EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> activities.closeLocalFailed(UUID.randomUUID(), SessionStatus.REQUESTED, 0, 200, EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> activities.findRequestedDueBefore(EPOCH, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> activities.findByIdempotencyKey(home.guardianId(), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownEndReasonOrFailureCodeIsStoredAsEmpty() {
        db.inRolledBackTx(status -> {
            Household home = household();
            NewActivity request = request(home, "u-" + UUID.randomUUID(), EPOCH);
            activities.insertRequested(request);
            StateChange unknown = new StateChange(request.sessionId(), SessionStatus.ABORTED, 7, EndReason.UNRECOGNIZED,
                    FailureCode.UNRECOGNIZED, false, EPOCH.plusSeconds(9));
            assertThat(activities.applyStateChange(unknown, EPOCH.plusSeconds(9))).isEqualTo(1);
            ActivityCopy copy = activities.findById(request.sessionId()).orElseThrow();
            assertThat(copy.endReason()).isNull();
            assertThat(copy.failureCode()).isNull();
            assertThat(copy.status()).isEqualTo(SessionStatus.ABORTED);
            return null;
        });
    }

    // ------------------------------------------------------------------ 도우미

    private record Household(UUID guardianId, UUID childId, UUID deviceId) {
    }

    /** 보호자 · 아이 · 등록 기기(호출자 트랜잭션 안에서 만든다). */
    private Household household() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        UUID childId = db.insertChild(guardianId);
        PendingDevice device = new PendingDevice(UUID.randomUUID(), randomCode(), randomHash(), EPOCH,
                EPOCH.plus(Duration.ofHours(24)));
        devices.insertPending(device);
        devices.register(device.id(), guardianId, EPOCH);
        db.trackDevice(device.id());
        return new Household(guardianId, childId, device.id());
    }

    /** 커밋한 household(동시성 시험용, 정리 대상). */
    private Household committedHousehold() {
        return db.inTx(status -> household());
    }

    private static NewActivity request(Household home, String key, Instant requestedAt) {
        return new NewActivity(UUID.randomUUID(), home.guardianId(), home.childId(), home.deviceId(), ActivityType.FREE_TALK,
                key, randomHash(), requestedAt);
    }

    private static StateChange change(UUID sessionId, SessionStatus status, long version, Instant at) {
        EndReason reason = status.isTerminal() ? EndReason.GUARDIAN_STOP : null;
        return new StateChange(sessionId, status, version, reason, null, false, at);
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
