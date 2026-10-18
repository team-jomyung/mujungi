package kr.mujoong.core.notification;

import static kr.mujoong.core.common.db.DbTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.support.NoTransactions;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("web-push")
@Tag("db")
class PushEventRepositoryIT {
    private final JsonCodec json = JsonCodec.shared();
    private final List<UUID> owners = new ArrayList<>();
    private DbTestSupport db;
    private MutableClock clock;
    private GuardianWriteLock guardians;
    private PushEventRepository events;
    private PushSubscriptionRepository subscriptions;
    private PushSubscriptionService service;
    private PushDeletionParticipant deletion;

    @BeforeEach
    void setup() {
        db = DbTestSupport.coreApp();
        clock = new MutableClock(DbTestSupport.EPOCH);
        guardians = new GuardianWriteLock(db.jdbc());
        events = new PushEventRepository(db.jdbc(), json);
        subscriptions = new PushSubscriptionRepository(db.jdbc());
        var tx = new DataSourceTransactionManager(db.dataSource());
        service = new PushSubscriptionService(guardians, subscriptions, clock, NoTransactions.noRetry(), tx);
        deletion = new PushDeletionParticipant(guardians, subscriptions, NoTransactions.noRetry(), tx);
    }

    @AfterEach
    void cleanup() {
        if (db != null) {
            try {
                for (UUID owner : owners) deletion.deleteByGuardian(owner, UUID.randomUUID());
            } finally {
                db.close();
            }
        }
    }

    @Test
    void enqueueFreezesOnlyTheOwnersActiveRecipientsAndDoesNotCopyTheirSecrets() {
        UUID owner = owner();
        UUID other = owner();
        var active = PushSubscriptionFixtures.request();
        var revoked = PushSubscriptionFixtures.request();
        service.put(owner, active);
        service.put(owner, revoked);
        service.revoke(owner, revoked.endpoint());
        service.put(other, PushSubscriptionFixtures.request());
        UUID activeId = subscriptions.findActiveByGuardian(owner).getFirst().id();
        var copy = copy(owner);
        String key = copy.sessionId() + ":ACTIVITY_COMPLETED";
        assertThat(enqueue(copy, PushEventType.ACTIVITY_COMPLETED, key)).isEqualTo(1);
        String snapshot = progress(key);
        assertThat(json.read(snapshot, Map.class)).containsOnlyKeys(activeId.toString());
        assertThat(snapshot).contains("PENDING").doesNotContain(active.endpoint(), active.keys().p256dh(), active.keys().auth());
        service.put(owner, PushSubscriptionFixtures.request());
        assertThat(enqueue(copy, PushEventType.ACTIVITY_COMPLETED, key)).isZero();
        assertThat(progress(key)).isEqualTo(snapshot);
        assertThat(row(key)).containsEntry("status", "REQUESTED").containsEntry("attempt_count", 0)
                .containsEntry("activity_type", "FREE_TALK");
    }

    @Test
    void anEventWithoutRecipientsStaysSkippedEvenIfADeviceSubscribesLater() {
        UUID owner = owner();
        var copy = copy(owner);
        String key = copy.sessionId() + ":ACTIVITY_COMPLETED";
        assertThat(enqueue(copy, PushEventType.ACTIVITY_COMPLETED, key)).isEqualTo(1);
        service.put(owner, PushSubscriptionFixtures.request());
        assertThat(enqueue(copy, PushEventType.ACTIVITY_COMPLETED, key)).isZero();
        assertThat(row(key)).containsEntry("status", "SKIPPED");
        assertThat(json.read(progress(key), Map.class)).isEmpty();
    }

    @Test
    void concurrentDeliveryOfTheSameLogicalEventCreatesExactlyOneRequest() {
        UUID owner = owner();
        service.put(owner, PushSubscriptionFixtures.request());
        var copy = copy(owner);
        String key = copy.sessionId() + ":ACTIVITY_COMPLETED";
        var start = new CountDownLatch(1);
        var one = db.start(() -> after(start, copy, key));
        var two = db.start(() -> after(start, copy, key));
        start.countDown();
        assertThat(List.of(await(one), await(two))).containsExactlyInAnyOrder(0, 1);
        assertThat(count(owner)).isEqualTo(1);
    }

    @Test
    void dispatchInsertRollsBackWithItsCallingTransaction() {
        UUID owner = owner();
        var copy = copy(owner);
        assertThatThrownBy(() -> db.inTx(tx -> {
            guardians.statusForShare(owner);
            events.enqueue(copy, PushEventType.ACTIVITY_COMPLETED, copy.sessionId() + ":ACTIVITY_COMPLETED", clock.now());
            throw new IllegalStateException("사본 처리 실패를 재현한다");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(owner)).isZero();
    }

    @Test
    void endingASessionSkipsOnlyItsPendingTechnicalMessagesAndReleasesClaims() {
        UUID owner = owner();
        service.put(owner, PushSubscriptionFixtures.request());
        var copy = copy(owner);
        var otherSession = copy(owner);
        String voice = copy.sessionId() + ":VOICE_PROBLEM:DEVICE:one";
        String stale = copy.sessionId() + ":STALE";
        String completed = copy.sessionId() + ":ACTIVITY_COMPLETED";
        String others = otherSession.sessionId() + ":STALE";
        enqueue(copy, PushEventType.VOICE_PROBLEM, voice);
        enqueue(copy, PushEventType.DEVICE_STATUS_UNKNOWN, stale);
        enqueue(copy, PushEventType.ACTIVITY_COMPLETED, completed);
        enqueue(otherSession, PushEventType.DEVICE_STATUS_UNKNOWN, others);
        db.jdbc().update("update core.core_push_dispatch set claim_id=:claim,lease_until=:now where event_key=:key",
                ClockParams.at(clock.now().plusSeconds(30)).addValue("claim", UUID.randomUUID()).addValue("key", voice));
        assertThat(events.skipTechnical(copy.sessionId())).isEqualTo(2);
        assertThat(events.skipTechnical(copy.sessionId())).isZero();
        assertThat(row(voice)).containsEntry("status", "SKIPPED").containsEntry("last_error_code", "SESSION_ENDED")
                .containsEntry("claim_id", null).containsEntry("lease_until", null);
        assertThat(row(stale)).containsEntry("status", "SKIPPED");
        assertThat(row(completed)).containsEntry("status", "REQUESTED");
        assertThat(row(others)).containsEntry("status", "REQUESTED");
    }

    @Test
    void currentPauseKeepsItsOwnPendingMessageAndRecoverySkipsAllPendingVoiceMessages() {
        UUID owner = owner();
        service.put(owner, PushSubscriptionFixtures.request());
        var copy = copy(owner);
        String old = copy.sessionId() + ":VOICE_PROBLEM:DEVICE:old";
        String current = copy.sessionId() + ":VOICE_PROBLEM:PROVIDER:current";
        String sent = copy.sessionId() + ":VOICE_PROBLEM:DEVICE:sent";
        String stale = copy.sessionId() + ":STALE";
        enqueue(copy, PushEventType.VOICE_PROBLEM, old);
        enqueue(copy, PushEventType.VOICE_PROBLEM, current);
        enqueue(copy, PushEventType.VOICE_PROBLEM, sent);
        enqueue(copy, PushEventType.DEVICE_STATUS_UNKNOWN, stale);
        db.jdbc().update("update core.core_push_dispatch set status='SENT',sent_at=:now where event_key=:key",
                ClockParams.at(clock.now()).addValue("key", sent));
        assertThat(events.skipOtherVoice(copy.sessionId(), current)).isEqualTo(1);
        assertThat(row(old)).containsEntry("status", "SKIPPED").containsEntry("last_error_code", "PAUSE_CHANGED");
        assertThat(row(current)).containsEntry("status", "REQUESTED");
        assertThat(events.skipOtherVoice(copy.sessionId(), null)).isEqualTo(1);
        assertThat(row(current)).containsEntry("status", "SKIPPED");
        assertThat(row(sent)).containsEntry("status", "SENT");
        assertThat(row(stale)).containsEntry("status", "REQUESTED");
    }

    private int after(CountDownLatch start, ActivityCopy copy, String key) throws InterruptedException {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return enqueue(copy, PushEventType.ACTIVITY_COMPLETED, key);
    }

    private int enqueue(ActivityCopy copy, PushEventType type, String key) {
        return db.inTx(tx -> {
            guardians.statusForShare(copy.guardianId());
            return events.enqueue(copy, type, key, clock.now());
        });
    }

    private UUID owner() {
        UUID owner = db.insertGuardian("ACTIVE");
        owners.add(owner);
        return owner;
    }

    private ActivityCopy copy(UUID owner) {
        return new ActivityCopy(UUID.randomUUID(), owner, UUID.randomUUID(), UUID.randomUUID(), ActivityType.FREE_TALK,
                SessionStatus.ACTIVE, 3, null, null, false, false, null, "key", "hash",
                clock.now(), clock.now(), null, clock.now(), clock.now(), clock.now());
    }

    private Map<String, Object> row(String key) {
        return db.jdbc().queryForMap("select * from core.core_push_dispatch where event_key=:key", Map.of("key", key));
    }

    private String progress(String key) {
        return db.jdbc().queryForObject("select recipient_progress::text from core.core_push_dispatch where event_key=:key",
                Map.of("key", key), String.class);
    }

    private int count(UUID owner) {
        return db.jdbc().queryForObject("select count(*) from core.core_push_dispatch where guardian_id=:id",
                Map.of("id", owner), Integer.class);
    }
}
