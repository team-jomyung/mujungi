package kr.mujoong.core.notification;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** C6 구독 등록/해제. 보호자 잠금으로 계정 삭제 접수와 순서를 정한다(AC1.7.5 · U7-J3/4). */
@Service
public class PushSubscriptionService {
    private final GuardianWriteLock guardians;
    private final PushSubscriptionRepository repository;
    private final MujoongClock clock;
    private final DbRetry retry;
    private final TransactionTemplate transactions;

    public PushSubscriptionService(GuardianWriteLock guardians, PushSubscriptionRepository repository,
            MujoongClock clock, DbRetry retry, PlatformTransactionManager transactionManager) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public void put(UUID guardianId, PushSubscriptionRequest request) {
        requireGuardian(guardianId);
        PushSubscriptionValidator.requireValid(request);
        retry.run(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.executeWithoutResult(status -> {
            guardians.lockActive(guardianId);
            if (repository.upsert(guardianId, request, clock.now()) == 0) {
                throw CoreApiException.of(ErrorCode.PUSH_SUBSCRIPTION_CONFLICT);
            }
        }));
    }

    public void revoke(UUID guardianId, String endpoint) {
        requireGuardian(guardianId);
        PushSubscriptionValidator.requireEndpoint(endpoint);
        retry.run(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.executeWithoutResult(status -> {
            guardians.lockActive(guardianId);
            repository.revoke(guardianId, endpoint, clock.now());
        }));
    }

    private static void requireGuardian(UUID guardianId) {
        if (guardianId == null) {
            throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        }
    }
}
