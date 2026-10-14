package kr.mujoong.core.notification.transport;

import static kr.mujoong.core.common.db.DbTestSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.StaleEvaluator;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.notification.PushEventRepository;
import kr.mujoong.core.notification.PushEventType;
import kr.mujoong.core.notification.PushSubscription;
import kr.mujoong.core.notification.PushSubscriptionFixtures;
import kr.mujoong.core.notification.PushSubscriptionRepository;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.testsupport.time.MutableClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Tag("u7")
@Tag("db")
class PushDispatchIT {
    private static final Duration LEASE = Duration.ofSeconds(6);
    private static final List<Duration> BACKOFF =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));
    private DbTestSupport db;
    private MutableClock clock;
    private GuardianWriteLock guardians;
    private PushSubscriptionRepository subscriptions;
    private ActivityCopyRepository activities;
    private PushEventRepository events;
    private PushDispatchRepository dispatches;
    private UUID owner, session, device;

    @BeforeEach
    void setup() {
        db = DbTestSupport.coreApp();
        clock = new MutableClock(EPOCH);
        guardians = new GuardianWriteLock(db.jdbc());
        subscriptions = new PushSubscriptionRepository(db.jdbc());
        activities = new ActivityCopyRepository(db.jdbc());
        events = new PushEventRepository(db.jdbc(), JsonCodec.shared());
        owner = db.insertGuardian("ACTIVE");
        UUID child = db.insertChild(owner);
        var devices = new DeviceJdbcRepository(db.jdbc());
        device = db.trackDevice(UUID.randomUUID());
        db.inTx(
                tx -> {
                    devices.insertPending(
                            new DeviceJdbcRepository.PendingDevice(
                                    device,
                                    randomCode(),
                                    randomHash(),
                                    clock.now(),
                                    clock.now().plus(Duration.ofDays(1))));
                    devices.register(device, owner, clock.now());
                    return null;
                });
        session = UUID.randomUUID();
        activities.insertRequested(
                new ActivityCopyRepository.NewActivity(
                        session,
                        owner,
                        child,
                        device,
                        ActivityType.FREE_TALK,
                        UUID.randomUUID().toString(),
                        randomHash(),
                        clock.now()));
        db.jdbc()
                .update(
                        "update core.core_activity set"
                                + " status='ACTIVE',status_version=1,started_at=:now where"
                                + " session_id=:id",
                        ClockParams.at(clock.now()).addValue("id", session));
        dispatches = repository();
    }

    @AfterEach
    void cleanup() {
        if (db != null) {
            try {
                if (owner != null) {
                    subscriptions.deleteDispatches(owner);
                    subscriptions.deleteSubscriptions(owner);
                }
            } finally {
                db.close();
            }
        }
    }

    @Test
    void partialSuccessIsCheckpointedAndRestartRetriesOnlyTheFailedPhone() {
        var one = subscribe();
        var two = subscribe();
        enqueue(PushEventType.VOICE_PROBLEM);
        List<String> sent = new ArrayList<>();
        PushDeliverySender first =
                sender(
                        (target, payload, topic) -> {
                            sent.add(target.endpoint());
                            return target.endpoint().equals(one.endpoint())
                                    ? accepted()
                                    : DeliveryOutcome.failed("PUSH_DELIVERY_FAILED");
                        });
        worker(first).runOnce();
        assertThat(progress(one.id()).status()).isEqualTo(RecipientProgress.Status.SENT);
        assertThat(progress(two.id()).status()).isEqualTo(RecipientProgress.Status.PENDING);
        assertThat(status()).isEqualTo("REQUESTED");
        assertThat(dispatches.claimNext(clock.now(), LEASE)).isEmpty();
        dispatches = repository();
        clock.advanceMillis(BACKOFF.getFirst().toMillis());
        worker(
                        sender(
                                (target, payload, topic) -> {
                                    sent.add(target.endpoint());
                                    return accepted();
                                }))
                .runOnce();
        assertThat(sent).containsExactlyInAnyOrder(one.endpoint(), two.endpoint(), two.endpoint());
        assertThat(status()).isEqualTo("SENT");
        assertThat(subscriptions.findActive(owner, one.id()).orElseThrow().lastUsedAt())
                .isEqualTo(EPOCH);
        assertThat(progress(one.id()).attempt()).isEqualTo(1);
        assertThat(progress(two.id()).attempt()).isEqualTo(2);
    }

    @Test
    void exhaustedRetryScheduleIsDurableAndCountsExactlyFourAttempts() {
        var sub = subscribe();
        enqueue(PushEventType.VOICE_PROBLEM);
        AtomicInteger calls = new AtomicInteger();
        var sender =
                sender(
                        (target, payload, topic) -> {
                            calls.incrementAndGet();
                            return DeliveryOutcome.failed("PUSH_DELIVERY_FAILED");
                        });
        for (int i = 0; i < 4; i++) {
            assertThat(worker(sender).runOnce()).isEqualTo(1);
            if (i < 3) clock.advanceMillis(BACKOFF.get(i).toMillis());
        }
        assertThat(calls).hasValue(4);
        assertThat(status()).isEqualTo("FAILED");
        assertThat(progress(sub.id()).attempt()).isEqualTo(4);
        assertThat(worker(sender).runOnce()).isZero();
    }

    @Test
    void expiredSubscriptionIsDeletedAndRevokedSubscriptionIsNeverSent() {
        var expired = subscribe();
        var revoked = subscribe();
        enqueue(PushEventType.VOICE_PROBLEM);
        subscriptions.revoke(owner, revoked.endpoint(), clock.now());
        AtomicInteger calls = new AtomicInteger();
        worker(
                        sender(
                                (target, payload, topic) -> {
                                    calls.incrementAndGet();
                                    return new DeliveryOutcome(
                                            DeliveryOutcome.Status.EXPIRED,
                                            "PUSH_SUBSCRIPTION_EXPIRED");
                                }))
                .runOnce();
        assertThat(calls).hasValue(1);
        assertThat(status()).isEqualTo("SKIPPED");
        assertThat(subscriptions.findActive(owner, expired.id())).isEmpty();
        assertThat(progress(revoked.id()).status()).isEqualTo(RecipientProgress.Status.SKIPPED);
    }

    @Test
    void newClaimFencesExpiredWorkerAndSurvivesAnOldFinish() {
        var sub = subscribe();
        enqueue(PushEventType.VOICE_PROBLEM);
        var old = dispatches.claimNext(clock.now(), LEASE).orElseThrow();
        assertThat(dispatches.claimNext(clock.now(), LEASE)).isEmpty();
        clock.advanceMillis(LEASE.toMillis());
        var current = dispatches.claimNext(clock.now(), LEASE).orElseThrow();
        assertThat(current.claimId()).isNotEqualTo(old.claimId());
        AtomicInteger calls = new AtomicInteger();
        var sender =
                sender(
                        (t, p, k) -> {
                            calls.incrementAndGet();
                            return accepted();
                        });
        dispatches.processRecipient(old, sub.id(), sender, 4, LEASE);
        dispatches.finish(old, clock.now(), BACKOFF);
        assertThat(calls).hasValue(0);
        assertThat(status()).isEqualTo("REQUESTED");
        dispatches.processRecipient(current, sub.id(), sender, 4, LEASE);
        dispatches.finish(current, clock.now(), BACKOFF);
        assertThat(calls).hasValue(1);
        assertThat(status()).isEqualTo("SENT");
    }

    @Test
    void endAfterClaimSuppressesTechnicalSend() {
        subscribe();
        enqueue(PushEventType.VOICE_PROBLEM);
        var claimed = dispatches.claimNext(clock.now(), LEASE).orElseThrow();
        endSession();
        for (String id : claimed.recipients().keySet())
            dispatches.processRecipient(claimed, UUID.fromString(id), neverSend(), 4, LEASE);
        dispatches.finish(claimed, clock.now(), BACKOFF);
        assertThat(status()).isEqualTo("SKIPPED");
    }

    @Test
    void deletionAfterClaimSuppressesAnySend() {
        subscribe();
        enqueue(PushEventType.VOICE_PROBLEM);
        var claimed = dispatches.claimNext(clock.now(), LEASE).orElseThrow();
        db.jdbc()
                .update(
                        "update core.core_guardian set status='DELETION_REQUESTED' where id=:id",
                        Map.of("id", owner));
        for (String id : claimed.recipients().keySet())
            dispatches.processRecipient(claimed, UUID.fromString(id), neverSend(), 4, LEASE);
        dispatches.finish(claimed, clock.now(), BACKOFF);
        assertThat(status()).isEqualTo("SKIPPED");
    }

    @Test
    void restoredDeviceStatusSuppressesQueuedStaleNotification() {
        subscribe();
        enqueue(PushEventType.DEVICE_STATUS_UNKNOWN);
        db.jdbc()
                .update(
                        "update core.core_device set reported_at=:now where id=:id",
                        ClockParams.at(clock.now()).addValue("id", device));
        worker(neverSend()).runOnce();
        assertThat(status()).isEqualTo("SKIPPED");
    }

    @Test
    void currentConfirmedCompletionAndDisconnectRemainDeliverable() {
        subscribe();
        endSession();
        enqueue(PushEventType.ACTIVITY_COMPLETED);
        worker(sender((t, p, k) -> accepted())).runOnce();
        assertThat(status()).isEqualTo("SENT");
        subscriptions.deleteDispatches(owner);
        db.jdbc()
                .update(
                        "update core.core_activity set status='ABORTED',end_reason='DISCONNECT'"
                                + " where session_id=:id",
                        Map.of("id", session));
        enqueue(PushEventType.ENDED_BY_CONNECTION);
        worker(sender((t, p, k) -> accepted())).runOnce();
        assertThat(status()).isEqualTo("SENT");
    }

    @Test
    void concurrentEndWaitsUntilOneBoundedSendAndCheckpointComplete() {
        var sub = subscribe();
        enqueue(PushEventType.VOICE_PROBLEM);
        var claimed = dispatches.claimNext(clock.now(), LEASE).orElseThrow();
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        var send =
                db.start(
                        () -> {
                            dispatches.processRecipient(
                                    claimed,
                                    sub.id(),
                                    sender(
                                            (t, p, k) -> {
                                                sending.countDown();
                                                awaitLatch(release);
                                                return accepted();
                                            }),
                                    4,
                                    LEASE);
                            return true;
                        });
        awaitLatch(sending);
        AtomicInteger endingPid = new AtomicInteger();
        var ending =
                db.startTx(
                        endingPid,
                        tx -> {
                            endSession();
                            return true;
                        });
        try {
            db.awaitLockWait(endingPid);
        } finally {
            release.countDown();
        }
        assertThat(await(send)).isTrue();
        assertThat(await(ending)).isTrue();
        dispatches.finish(claimed, clock.now(), BACKOFF);
        assertThat(status()).isEqualTo("SENT");
    }

    @Test
    void newerEndedSnapshotWithUnknownReasonSkipsQueuedCompletion() {
        subscribe();
        endSession();
        enqueue(PushEventType.ACTIVITY_COMPLETED);
        db.jdbc()
                .update(
                        "update core.core_activity set"
                            + " end_reason=null,status_version=status_version+1 where"
                            + " session_id=:id",
                        Map.of("id", session));
        assertThat(worker(neverSend()).runOnce()).isEqualTo(1);
        assertThat(status()).isEqualTo("SKIPPED");
    }

    private PushDispatchRepository repository() {
        return new PushDispatchRepository(
                db.jdbc(),
                new DataSourceTransactionManager(db.dataSource()),
                guardians,
                subscriptions,
                new StaleEvaluator(
                        Duration.ofSeconds(30), mock(CoreMetrics.class), mock(EventLogger.class)),
                clock,
                JsonCodec.shared());
    }

    private PushDispatchWorker worker(PushDeliverySender sender) {
        return new PushDispatchWorker(dispatches, sender, clock, () -> true, BACKOFF, LEASE);
    }

    private PushSubscription subscribe() {
        var request = PushSubscriptionFixtures.request();
        subscriptions.upsert(owner, request, clock.now());
        return subscriptions.findActiveByGuardian(owner).stream()
                .filter(s -> s.endpoint().equals(request.endpoint()))
                .findFirst()
                .orElseThrow();
    }

    private void enqueue(PushEventType type) {
        events.enqueue(
                activities.findById(session).orElseThrow(),
                type,
                session + ":" + type + ":" + UUID.randomUUID(),
                clock.now());
    }

    private String status() {
        return db.jdbc()
                .queryForObject(
                        "select status from core.core_push_dispatch where guardian_id=:id",
                        Map.of("id", owner),
                        String.class);
    }

    private RecipientProgress progress(UUID subscription) {
        String text =
                db.jdbc()
                        .queryForObject(
                                "select recipient_progress::text from core.core_push_dispatch where"
                                        + " guardian_id=:id",
                                Map.of("id", owner),
                                String.class);
        return PushDeliveryLedger.read(JsonCodec.shared(), text).get(subscription.toString());
    }

    private void endSession() {
        db.jdbc()
                .update(
                        "update core.core_activity set"
                            + " status='ENDED',confirmed=true,end_reason='GUARDIAN_STOP',ended_at=:now"
                            + " where session_id=:id",
                        ClockParams.at(clock.now()).addValue("id", session));
    }

    private static DeliveryOutcome accepted() {
        return new DeliveryOutcome(DeliveryOutcome.Status.ACCEPTED, null);
    }

    private static PushDeliverySender neverSend() {
        return sender(
                (t, p, k) -> {
                    throw new AssertionError("Unexpected push");
                });
    }

    @FunctionalInterface
    private interface Send {
        DeliveryOutcome send(PushTarget target, PushPayload payload, String topic);
    }

    private static PushDeliverySender sender(Send send) {
        return new PushDeliverySender() {
            public boolean enabled() {
                return true;
            }

            public DeliveryOutcome send(PushTarget target, PushPayload payload, String topic) {
                return send.send(target, payload, topic);
            }
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted test");
        }
    }
}
