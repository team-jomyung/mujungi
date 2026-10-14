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
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.support.NoTransactions;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("u7")
@Tag("db")
class PushSubscriptionIT {
    private DbTestSupport db;
    private MutableClock clock;
    private GuardianWriteLock guardians;
    private PushSubscriptionRepository repository;
    private PushSubscriptionService service;
    private PushDeletionParticipant deletion;
    private DataSourceTransactionManager transactions;
    private final List<UUID> owners = new ArrayList<>();

    @BeforeEach
    void setup() {
        db = DbTestSupport.coreApp();
        clock = new MutableClock(DbTestSupport.EPOCH);
        guardians = new GuardianWriteLock(db.jdbc());
        repository = new PushSubscriptionRepository(db.jdbc());
        transactions = new DataSourceTransactionManager(db.dataSource());
        service = new PushSubscriptionService(guardians, repository, clock, NoTransactions.noRetry(), transactions);
        deletion = new PushDeletionParticipant(guardians, repository, NoTransactions.noRetry(), transactions);
    }

    @AfterEach
    void cleanup() {
        if (db != null) {
            try {
                for (UUID owner : owners) {
                    deletion.deleteByGuardian(owner, UUID.randomUUID());
                }
            } finally {
                db.close();
            }
        }
    }

    @Test
    void sameEndpointUpdatesKeysAndReactivatesTheSameOwnedRow() {
        UUID owner = owner();
        var first = PushSubscriptionFixtures.request();
        service.put(owner, first);
        var stored = repository.findActiveByGuardian(owner).getFirst();
        service.put(owner, first);
        assertThat(repository.findActiveByGuardian(owner)).hasSize(1);
        service.revoke(owner, first.endpoint());
        clock.advanceMillis(1000);
        var changed = PushSubscriptionFixtures.request(first.endpoint());
        service.put(owner, changed);
        var reactivated = repository.findActiveByGuardian(owner).getFirst();
        assertThat(reactivated.id()).isEqualTo(stored.id());
        assertThat(reactivated.createdAt()).isEqualTo(stored.createdAt());
        assertThat(reactivated.p256dh()).isEqualTo(changed.keys().p256dh());
        assertThat(reactivated.auth()).isEqualTo(changed.keys().auth());
        assertThat(reactivated.revokedAt()).isNull();
    }

    @Test
    void revokeOnlyTargetsTheCurrentOwnedEndpointAndIsIdempotent() {
        UUID owner = owner();
        UUID other = owner();
        var current = PushSubscriptionFixtures.request();
        var secondDevice = PushSubscriptionFixtures.request();
        var others = PushSubscriptionFixtures.request();
        service.put(owner, current);
        service.put(owner, secondDevice);
        service.put(other, others);
        service.revoke(owner, current.endpoint());
        var revoked = db.jdbc().queryForObject("select revoked_at from core.core_push_subscription where endpoint=:endpoint",
                Map.of("endpoint", current.endpoint()), java.time.OffsetDateTime.class);
        clock.advanceMillis(1000);
        service.revoke(owner, current.endpoint());
        service.revoke(owner, others.endpoint());
        service.revoke(owner, "https://push.example.test/missing");
        assertThat(repository.findActiveByGuardian(owner)).extracting(PushSubscription::endpoint)
                .containsExactly(secondDevice.endpoint());
        assertThat(repository.findActiveByGuardian(other)).hasSize(1);
        assertThat(db.jdbc().queryForObject("select revoked_at from core.core_push_subscription where endpoint=:endpoint",
                Map.of("endpoint", current.endpoint()), java.time.OffsetDateTime.class)).isEqualTo(revoked);
    }

    @Test
    void anotherGuardianCannotTakeOverEvenARevokedEndpoint() {
        UUID owner = owner();
        UUID other = owner();
        var request = PushSubscriptionFixtures.request();
        service.put(owner, request);
        error(() -> service.put(other, request), ErrorCode.PUSH_SUBSCRIPTION_CONFLICT);
        service.revoke(owner, request.endpoint());
        error(() -> service.put(other, request), ErrorCode.PUSH_SUBSCRIPTION_CONFLICT);
        assertThat(repository.findActiveByGuardian(other)).isEmpty();
    }

    @Test
    void concurrentRegistrationForDifferentGuardiansHasOneOwnerAndOneConflict() {
        UUID first = owner();
        UUID second = owner();
        var request = PushSubscriptionFixtures.request();
        var start = new CountDownLatch(1);
        var one = db.start(() -> registerAfter(start, first, request));
        var two = db.start(() -> registerAfter(start, second, request));
        start.countDown();
        assertThat(List.of(await(one), await(two))).containsExactlyInAnyOrder("SAVED", "PUSH_SUBSCRIPTION_CONFLICT");
        assertThat(repository.findActiveByGuardian(first).size() + repository.findActiveByGuardian(second).size()).isEqualTo(1);
    }

    @Test
    void missingOrDeletingGuardianCannotWriteAndInvalidInputCannotCreateRows() {
        UUID owner = owner();
        var request = PushSubscriptionFixtures.request();
        error(() -> service.put(owner, null), ErrorCode.VALIDATION_FAILED);
        error(() -> service.put(UUID.randomUUID(), request), ErrorCode.UNAUTHENTICATED);
        error(() -> service.put(null, request), ErrorCode.UNAUTHENTICATED);
        service.put(owner, request);
        db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id", Map.of("id", owner));
        error(() -> service.put(owner, request), ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        error(() -> service.revoke(owner, request.endpoint()), ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        assertThat(repository.findActiveByGuardian(owner)).hasSize(1);
    }

    @Test
    void senderLookupsAndUpdatesRemainGuardianScopedAndRevocationAware() {
        UUID owner = owner();
        UUID other = owner();
        var request = PushSubscriptionFixtures.request();
        service.put(owner, request);
        var stored = repository.findActiveByGuardian(owner).getFirst();
        assertThat(repository.findActive(other, stored.id())).isEmpty();
        assertThat(repository.markUsed(other, stored.id(), clock.now())).isZero();
        assertThat(repository.expire(other, stored.id())).isZero();
        assertThat(repository.markUsed(owner, stored.id(), clock.now())).isEqualTo(1);
        assertThat(repository.findActive(owner, stored.id()).orElseThrow().lastUsedAt()).isEqualTo(clock.now());
        service.revoke(owner, request.endpoint());
        assertThat(repository.findActive(owner, stored.id())).isEmpty();
        assertThat(repository.markUsed(owner, stored.id(), clock.now())).isZero();
        assertThat(repository.expire(owner, stored.id())).isEqualTo(1);
        assertThat(repository.expire(owner, stored.id())).isZero();
    }

    @Test
    void deletionRunsBeforeActivityIsAtomicIdempotentAndPreservesOtherGuardians() {
        UUID owner = owner();
        UUID other = owner();
        service.put(owner, PushSubscriptionFixtures.request());
        service.put(other, PushSubscriptionFixtures.request());
        insertDispatch(owner);
        insertDispatch(other);
        var failing = new PushSubscriptionRepository(db.jdbc()) {
            @Override public int deleteSubscriptions(UUID guardianId) {
                throw new IllegalStateException("발송 기록 삭제 뒤 시험 실패");
            }
        };
        var failingDeletion = new PushDeletionParticipant(guardians, failing, NoTransactions.noRetry(), transactions);
        assertThatThrownBy(() -> failingDeletion.deleteByGuardian(owner, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(dispatchCount(owner)).isEqualTo(1);
        assertThat(repository.findActiveByGuardian(owner)).hasSize(1);
        assertThat(PushDeletionParticipant.class.getAnnotation(Order.class).value()).isEqualTo(60);
        assertThat(DeletionParticipant.ORDER_PUSH).isLessThan(DeletionParticipant.ORDER_ACTIVITY_RECORD);
        assertThat(deletion.deleteByGuardian(owner, UUID.randomUUID()))
                .containsEntry("core_push_dispatch", 1).containsEntry("core_push_subscription", 1);
        assertThat(deletion.deleteByGuardian(owner, UUID.randomUUID()).values()).allMatch(count -> count == 0);
        assertThat(dispatchCount(other)).isEqualTo(1);
        assertThat(repository.findActiveByGuardian(other)).hasSize(1);
    }

    private UUID owner() {
        UUID owner = db.insertGuardian("ACTIVE");
        owners.add(owner);
        return owner;
    }

    private String registerAfter(CountDownLatch start, UUID owner, PushSubscriptionRequest request) throws InterruptedException {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            service.put(owner, request);
            return "SAVED";
        } catch (CoreApiException e) {
            return e.code().name();
        }
    }

    private void insertDispatch(UUID owner) {
        var params = ClockParams.at(clock.now()).addValue("id", UUID.randomUUID()).addValue("owner", owner)
                .addValue("session", UUID.randomUUID()).addValue("eventKey", UUID.randomUUID().toString());
        db.jdbc().update("""
                insert into core.core_push_dispatch
                (id,guardian_id,session_id,event_type,event_key,status,attempt_count,requested_at,
                 activity_type,next_attempt_at,recipient_progress)
                values(:id,:owner,:session,'ACTIVITY_COMPLETED',:eventKey,'REQUESTED',0,:now,
                       'FREE_TALK',:now,'{}'::jsonb)
                """, params);
    }

    private int dispatchCount(UUID owner) {
        return db.jdbc().queryForObject("select count(*) from core.core_push_dispatch where guardian_id=:id",
                Map.of("id", owner), Integer.class);
    }

    private static void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CoreApiException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
