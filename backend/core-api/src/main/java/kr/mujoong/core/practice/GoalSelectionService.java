package kr.mujoong.core.practice;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.knowledge.BehaviorDefinition;
import kr.mujoong.core.knowledge.PragmaticsKnowledge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** C4 management, serialized per account so add/favorite/delete/restore and deletion cannot interleave writes. */
@Service
public class GoalSelectionService {
    private final PracticeAccountPort accounts;
    private final PracticeGoalRepository goals;
    private final PragmaticsKnowledge knowledge;
    private final GoalMetadataPort metadata;
    private final MujoongClock clock;
    private final Duration restoreWindow;
    private final DbRetry retry;
    private final TransactionTemplate tx;
    public GoalSelectionService(PracticeAccountPort accounts,PracticeGoalRepository goals,PragmaticsKnowledge knowledge,
            GoalMetadataPort metadata,MujoongClock clock,
            @Value("${mujoong.timing.goal.restore-window}") Duration restoreWindow,
            DbRetry retry,PlatformTransactionManager transactions) {
        if(restoreWindow.isNegative() || restoreWindow.isZero()) throw new IllegalArgumentException("goal.restore-window");
        this.accounts=accounts;this.goals=goals;this.knowledge=knowledge;this.metadata=metadata;this.clock=clock;
        this.restoreWindow=restoreWindow;this.retry=retry;this.tx=new TransactionTemplate(transactions);
    }
    public List<View> list(UUID guardianId) {
        accounts.requireActive(guardianId,false);
        return goals.list(guardianId).stream().map(this::view).toList();
    }
    public View add(UUID guardianId,UUID recommendationId) {
        required(recommendationId);
        return write(guardianId,account -> {
            PracticeGoalRepository.Recommendation candidate=goals.recommendation(account.childId(),recommendationId)
                    .filter(r->"ACTIVE".equals(r.status())).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
            BehaviorDefinition definition=active(candidate.behaviorKey(),candidate.behaviorVersion());
            GoalSelection existing=goals.byRecommendation(guardianId,recommendationId).orElse(null);
            UUID id;
            if(existing!=null) {
                if(existing.active()) throw CoreApiException.of(ErrorCode.ALREADY_SELECTED);
                goals.reactivate(existing.id()); id=existing.id();
            } else id=goals.insertSelection(guardianId,account.childId(),candidate,definition.displayName(),clock.now());
            return view(owned(guardianId,id));
        });
    }
    public View favorite(UUID guardianId,UUID id,Boolean favorite) {
        required(id);if(favorite==null)throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        return write(guardianId,account -> {
            GoalSelection current=owned(guardianId,id);
            if(!current.active())throw CoreApiException.of(ErrorCode.NOT_FOUND);
            goals.favorite(id,favorite);return view(owned(guardianId,id));
        });
    }
    public Instant delete(UUID guardianId,UUID id) {
        required(id);
        return write(guardianId,account -> {
            GoalSelection current=owned(guardianId,id);
            if(!current.active())return current.restoreUntil();
            Instant now=clock.now(),until=now.plus(restoreWindow);
            goals.softDelete(id,now,until);return until;
        });
    }
    public View restore(UUID guardianId,UUID id) {
        required(id);
        return write(guardianId,account -> {
            GoalSelection current=owned(guardianId,id);
            if(current.active())return view(current);
            if(!clock.now().isBefore(current.restoreUntil()))throw CoreApiException.of(ErrorCode.RESTORE_EXPIRED);
            goals.recommendation(account.childId(),current.recommendationId()).filter(r->"ACTIVE".equals(r.status()))
                    .orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
            active(current.behaviorKey(),current.behaviorVersion());
            goals.reactivate(id);return view(owned(guardianId,id));
        });
    }
    private BehaviorDefinition active(String key,int version) {
        try{BehaviorDefinition definition=knowledge.pinVersion(key,version);
            if(!"ACTIVE".equals(definition.status()))throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
            return definition;}
        catch(IllegalArgumentException error){throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);}
    }
    private GoalSelection owned(UUID guardianId,UUID id) {
        return goals.find(guardianId,id).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
    }
    private <T>T write(UUID guardianId,Function<PracticeAccountPort.AccountContext,T> body) {
        return retry.execute(DbRetry.Idempotency.IDEMPOTENT,()->tx.execute(status->body.apply(accounts.requireActive(guardianId,true))));
    }
    private View view(GoalSelection selection) {
        GoalMetadataPort.Metadata data=metadata.lookup(selection.childId(),selection.id(),selection.sourceSessionId());
        return new View(selection.id(),selection.displayName(),selection.isFavorite(),selection.sortOrder(),selection.selectedAt(),
                data.sourceActivityType(),data.sourceActivityAt(),data.practiceCount());
    }
    private static void required(UUID id){if(id==null)throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);}
    public record View(UUID goalSelectionId,String displayName,boolean isFavorite,long sortOrder,Instant selectedAt,
            String sourceActivityType,Instant sourceActivityAt,Integer practiceCount) {}
}
