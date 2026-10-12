package kr.mujoong.core.practice;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.knowledge.BehaviorDefinition;
import kr.mujoong.core.knowledge.PragmaticsKnowledge;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** C12 port for U6. Stores reviewed candidates; does not implement or call the U6 pipeline. */
@Service
public class CandidateRegistry {
    private final PracticeAccountPort accounts;
    private final CandidateEvidencePort evidence;
    private final PracticeGoalRepository repository;
    private final PragmaticsKnowledge knowledge;
    private final ForbiddenExpressionPolicy expressions;
    private final MujoongClock clock;
    private final DbRetry retry;
    private final TransactionTemplate tx;
    public CandidateRegistry(PracticeAccountPort accounts,CandidateEvidencePort evidence,PracticeGoalRepository repository,
            PragmaticsKnowledge knowledge,ForbiddenExpressionPolicy expressions,MujoongClock clock,
            DbRetry retry,PlatformTransactionManager transactions) {
        this.accounts=accounts;this.evidence=evidence;this.repository=repository;this.knowledge=knowledge;
        this.expressions=expressions;this.clock=clock;this.retry=retry;this.tx=new TransactionTemplate(transactions);
    }
    public List<UUID> register(UUID sessionId,UUID childId,List<Candidate> candidates) {
        if(sessionId==null || childId==null || candidates==null || candidates.size()>100) invalid();
        return retry.execute(DbRetry.Idempotency.IDEMPOTENT,()->tx.execute(status->{
            PracticeAccountPort.AccountContext owner=accounts.requireChild(childId,true);
            if(!owner.childId().equals(childId))throw CoreApiException.of(ErrorCode.NOT_FOUND);
            List<UUID> ids=new ArrayList<>();
            for(Candidate candidate:candidates) {
                validate(candidate);
                BehaviorDefinition definition;
                try { definition=knowledge.pinVersion(candidate.behaviorKey(),candidate.behaviorVersion());
                    if(!"ACTIVE".equals(definition.status()))invalid(); }
                catch(IllegalArgumentException error){throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);}
                List<CandidateEvidencePort.Evidence> refs=candidate.evidence().stream()
                    .map(e->new CandidateEvidencePort.Evidence(sessionId,e.utteranceSeq(),e.kind())).toList();
                if(!evidence.verifies(childId,sessionId,refs))invalid();
                ids.add(repository.register(childId,sessionId,candidate,definition.displayName(),clock.now()));
            }
            return List.copyOf(ids);
        }));
    }
    public List<CandidateView> viewForSession(UUID sessionId,UUID guardianId) {
        if(sessionId==null)invalid();
        var account=accounts.requireActive(guardianId,false);
        return repository.candidates(account.childId(),sessionId);
    }
    private void validate(Candidate candidate) {
        if(candidate==null || candidate.behaviorKey()==null || candidate.behaviorKey().isBlank()
                || candidate.behaviorVersion()<1 || candidate.reasonText()==null || candidate.reasonText().isBlank()
                || candidate.reasonText().length()>2000 || !expressions.isAllowed(candidate.reasonText())
                || candidate.evidence()==null || candidate.evidence().isEmpty() || candidate.evidence().size()>100)invalid();
        for(Evidence ref:candidate.evidence()) {
            if(ref==null || ref.utteranceSeq()<1 || ref.kind()==null || !Set.of("GUARDIAN_INPUT","CHILD_RECALL").contains(ref.kind()))invalid();
        }
    }
    private static void invalid(){throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);}
    public record Candidate(String behaviorKey,int behaviorVersion,String reasonText,List<Evidence> evidence) {
        @Override public String toString(){return "Candidate[redacted]";}
    }
    public record Evidence(long utteranceSeq,String kind) {}
    public record CandidateView(UUID recommendationId,String displayName,String reasonText,boolean selected) {
        @Override public String toString(){return "CandidateView[redacted]";}
    }
}
