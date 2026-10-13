package kr.mujoong.core.practice;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/**
 * U5a가 가진 개인 행만 지운다(공용 행동 정의 · 지원 문구는 남긴다). 아이 참가자(300)보다 먼저 돌아야 아이 행 삭제가 외래 키에 막히지 않는다
 * ({@link DeletionParticipant#ORDER_PRACTICE}, U5a 리뷰 B).
 */
@Component
@Order(DeletionParticipant.ORDER_PRACTICE)
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
