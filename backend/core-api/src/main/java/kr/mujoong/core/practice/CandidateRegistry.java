package kr.mujoong.core.practice;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.knowledge.PragmaticsKnowledge;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
/** Legacy C12 entry point; PracticeGoal owns registration and selection storage. */
@Service
public class CandidateRegistry extends kr.mujoong.core.practicegoal.CandidateRegistry {
    public CandidateRegistry(PracticeAccountPort accounts,CandidateEvidencePort evidence,PracticeGoalRepository repository,
            PragmaticsKnowledge knowledge,ForbiddenExpressionPolicy expressions,MujoongClock clock,
            DbRetry retry,PlatformTransactionManager transactions){
        super(accounts,evidence,repository,knowledge,expressions,clock,retry,transactions);
    }
}
