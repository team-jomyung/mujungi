package kr.mujoong.core.notification;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 계정 삭제의 Notification 참가자. 구독과 발송 기록을 한 트랜잭션에서 멱등 삭제한다(AC1.7.5). */
@Component
@Order(DeletionParticipant.ORDER_PUSH)
public class PushDeletionParticipant implements DeletionParticipant {
    private final GuardianWriteLock guardians;
    private final PushSubscriptionRepository repository;
    private final DbRetry retry;
    private final TransactionTemplate transactions;

    public PushDeletionParticipant(GuardianWriteLock guardians, PushSubscriptionRepository repository,
            DbRetry retry, PlatformTransactionManager transactionManager) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        return retry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            guardians.statusForUpdate(guardianId);
            int dispatches = repository.deleteDispatches(guardianId);
            int subscriptions = repository.deleteSubscriptions(guardianId);
            return Map.of("core_push_dispatch", dispatches, "core_push_subscription", subscriptions);
        }));
    }
}
