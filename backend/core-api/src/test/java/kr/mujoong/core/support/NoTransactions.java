package kr.mujoong.core.support;

import java.util.Random;
import kr.mujoong.core.common.db.DbRetry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 단위 시험용 트랜잭션 대역(시험 소스 전용). 실제 DB 없이 {@code TransactionTemplate}을 돌리고 마지막 트랜잭션이 되돌림 표시였는지 기록한다.
 */
public final class NoTransactions implements PlatformTransactionManager {

    private int commits;
    private int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
        if (status.isRollbackOnly()) {
            rollbacks++;
        } else {
            commits++;
        }
    }

    @Override
    public void rollback(TransactionStatus status) {
        rollbacks++;
    }

    public int commits() {
        return commits;
    }

    public int rollbacks() {
        return rollbacks;
    }

    /** 재시도 없는 DbRetry(대기하지 않음). */
    public static DbRetry noRetry() {
        return new DbRetry(0, new Random(0), duration -> {
        });
    }
}
