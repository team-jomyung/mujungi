package kr.mujoong.core.practice;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** Deletes only U5a-owned personal rows; shared definitions and tips remain. U2 registers this participant. */
@Component
public class PracticeDeletionParticipant implements DeletionParticipant {
    private final PracticeGoalRepository repository;
    private final PracticeAccountPort accounts;
    private final DbRetry retry;
    private final TransactionTemplate tx;
    public PracticeDeletionParticipant(PracticeGoalRepository repository,PracticeAccountPort accounts,
            DbRetry retry,PlatformTransactionManager transactions) {
        this.repository=repository;this.accounts=accounts;this.retry=retry;this.tx=new TransactionTemplate(transactions);
    }
    @Override public Map<String,Integer> deleteByGuardian(UUID guardianId,UUID deletionRequestId) {
        Objects.requireNonNull(guardianId,"guardianId");Objects.requireNonNull(deletionRequestId,"deletionRequestId");
        return retry.execute(DbRetry.Idempotency.IDEMPOTENT,()->tx.execute(status->{
            accounts.lockForDeletion(guardianId);
            return repository.deleteByGuardian(guardianId,accounts.childrenForDeletion(guardianId));
        }));
    }
}
