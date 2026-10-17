package kr.mujoong.core.practice.start;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.PracticeStart;
import kr.mujoong.core.activity.StartActivityService;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.knowledge.PragmaticsKnowledge;
import kr.mujoong.core.practice.PracticeAccountPort;
import kr.mujoong.core.practice.PracticeGoalRepository;
import kr.mujoong.core.practice.PracticeSessionContextPort;
import kr.mujoong.core.practice.Scenario;
import kr.mujoong.core.practice.ScenarioConfiguration.ScenarioLimits;
import kr.mujoong.core.practice.ScenarioRepository;
import kr.mujoong.core.practice.ScenarioService;
import kr.mujoong.core.practice.start.PracticePreparationRepository.Preparation;
import kr.mujoong.core.practice.start.PreparedPracticePackageMapper.PreparedSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PracticeStartService implements PracticeStart {
    private final PracticeAccountPort accounts;
    private final ConsentPolicyService consent;
    private final PracticeGoalRepository goals;
    private final PragmaticsKnowledge knowledge;
    private final PracticePreparationRepository preparations;
    private final ScenarioRepository scenarios;
    private final ScenarioService generation;
    private final StartActivityService activities;
    private final TransactionTemplate tx;
    private final MujoongClock clock;
    private final ScenarioLimits limits;
    private final PreparedPracticePackageMapper mapper=new PreparedPracticePackageMapper();
    private kr.mujoong.core.practicegoal.SavedGoalReader savedGoals;
    private kr.mujoong.core.practice.ScenarioPrivacyContextPort privacy;
    private kr.mujoong.core.luna.LunaInputSanitizer sanitizer;
    @org.springframework.beans.factory.annotation.Autowired
    void connectSavedGoals(kr.mujoong.core.practicegoal.SavedGoalReader savedGoals,
            kr.mujoong.core.practice.ScenarioPrivacyContextPort privacy,kr.mujoong.core.luna.LunaInputSanitizer sanitizer){
        this.savedGoals=savedGoals;this.privacy=privacy;this.sanitizer=sanitizer;
    }

    public PracticeStartService(PracticeAccountPort accounts, ConsentPolicyService consent, PracticeGoalRepository goals,
            PragmaticsKnowledge knowledge, PracticePreparationRepository preparations, ScenarioRepository scenarios,
            ScenarioService generation, StartActivityService activities, TransactionTemplate tx, MujoongClock clock,
            ScenarioLimits limits) {
        this.accounts=accounts;this.consent=consent;this.goals=goals;this.knowledge=knowledge;this.preparations=preparations;
        this.scenarios=scenarios;this.generation=generation;this.activities=activities;this.tx=tx;this.clock=clock;this.limits=limits;
    }
    public record Started(UUID sessionId, kr.mujoong.contract.status.SessionStatus status, int goalCount) {}
    public Started start(UUID guardian, PracticeStartRequest request) {
        Objects.requireNonNull(request);
        accounts.requireGuardian(guardian);
        var replay=activities.replayPractice(guardian,request.idempotencyKey(),request.requestHash());
        if(replay.isPresent()) return new Started(replay.get().sessionId(),replay.get().status(),request.goalSelectionIds().size());
        PreparedSnapshot ready;
        try { ready=prepare(guardian,request); }
        catch(CoreApiException concurrent) {
            if(concurrent.code()==ErrorCode.SESSION_IN_PROGRESS || concurrent.code()==ErrorCode.SERVICE_NOT_READY) {
                var winner=activities.replayPractice(guardian,request.idempotencyKey(),request.requestHash());
                if(winner.isPresent()) return new Started(winner.get().sessionId(),winner.get().status(),request.goalSelectionIds().size());
            }
            throw concurrent;
        }
        var started=activities.startPractice(guardian,request.idempotencyKey(),ready.sessionId(),request.requestHash(),mapper.c8(ready));
        return new Started(started.sessionId(),started.status(),ready.goals().size());
    }
    @Override public PracticePackage buildPackage(UUID guardian, List<UUID> selections) {
        return mapper.map(prepare(guardian,new PracticeStartRequest(UUID.randomUUID(),selections)));
    }
    public PreparedSnapshot prepare(UUID guardian, PracticeStartRequest request) {
        accounts.requireGuardian(guardian);
        var existing=preparations.find(guardian,request.idempotencyKey());
        if(existing.isPresent()) {
            checkHash(existing.get(),request);
            if("FAILED".equals(existing.get().status())) throw CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);
        }
        activities.checkPracticeReadiness(guardian);
        Preparation claim=Objects.requireNonNull(tx.execute(status->{
            var account=accounts.requireActive(guardian,true);
            if(!consent.check(guardian).ok()) throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
            var old=preparations.find(guardian,request.idempotencyKey());
            if(old.isPresent()) {
                var value=old.get();checkHash(value,request);
                if(!value.childId().equals(account.childId())) throw CoreApiException.of(ErrorCode.NOT_FOUND);
                if("READY".equals(value.status())) return value;
                if("FAILED".equals(value.status())) throw CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);
                if(value.leaseUntil().isAfter(clock.now())) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
                return preparations.reclaim(value,clock.now(),clock.now().plus(limits.requestTimeout()));
            }
            List<WeightedGoalOrder.Selection> selected=request.goalSelectionIds().stream().map(id->{
                if(savedGoals!=null)return selection(guardian,account.childId(),id);
                var selectedGoal=goals.find(guardian,id).filter(g->g.active()&&g.childId().equals(account.childId()))
                        .orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
                kr.mujoong.core.knowledge.BehaviorDefinition definition;
                try { definition=knowledge.pinVersion(selectedGoal.behaviorKey(),selectedGoal.behaviorVersion()); }
                catch(IllegalArgumentException invalid) { throw CoreApiException.of(ErrorCode.VALIDATION_FAILED); }
                if(!"ACTIVE".equals(definition.status())) throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
                return new WeightedGoalOrder.Selection(guardian,account.childId(),id,selectedGoal.displayName(),
                        selectedGoal.behaviorKey(),selectedGoal.behaviorVersion(),definition.judgmentCriteria(),
                        Math.toIntExact(selectedGoal.sortOrder()),selectedGoal.isFavorite());
            }).toList();
            var frozen=new WeightedGoalOrder().freeze(guardian,account.childId(),selected,RandomGenerator.getDefault());
            return preparations.insert(guardian,account.childId(),request,frozen,clock.now(),clock.now().plus(limits.requestTimeout()));
        }));
        if("READY".equals(claim.status())) return snapshot(claim);
        boolean failed=false;
        try {
            var first=claim.goals().getFirst();
            Scenario scene=generation.generatePrepared(new ScenarioService.Request(claim.sessionId(),first.goalSelectionId(),"ORIGINAL",null),
                    new ScenarioService.PreparedContext(claim.leaseUntil(),()->preparationContext(claim)));
            return Objects.requireNonNull(tx.execute(status->{
                accounts.requireActive(guardian,true);
                preparationContext(claim);
                if(preparations.ready(claim,scene.scenarioId(),clock.now())!=1) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
                return new PreparedSnapshot(claim.sessionId(),claim.key(),claim.goals(),scene);
            }));
        } catch(CoreApiException failure) {
            failed=failure.code()==ErrorCode.SCENARIO_GEN_FAILED&&
                    (!(failure instanceof ScenarioService.GenerationFailure disposition)||disposition.permanent());throw failure;
        } finally {
            boolean finalFailed=failed;
            tx.executeWithoutResult(status->{accounts.lockForDeletion(guardian);preparations.release(claim,finalFailed,clock.now());});
        }
    }
    private void checkHash(Preparation p, PracticeStartRequest request) {
        if(!p.requestHash().equals(request.requestHash())) throw CoreApiException.of(ErrorCode.IDEMPOTENCY_CONFLICT);
    }
    private PreparedSnapshot snapshot(Preparation p) {
        return new PreparedSnapshot(p.sessionId(),p.key(),p.goals(),scenarios.require(p.firstScenarioId()).scenario());
    }
    private PracticeSessionContextPort.SessionContext preparationContext(Preparation expected) {
        var account=accounts.requireActive(expected.guardianId(),false);
        if(!consent.check(expected.guardianId()).ok()) throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
        var current=preparations.findSession(expected.sessionId()).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
        if(!current.claimId().equals(expected.claimId()) || !"PREPARING".equals(current.status())
                || !current.leaseUntil().isAfter(clock.now())) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
        if(!current.childId().equals(account.childId())) throw CoreApiException.of(ErrorCode.NOT_FOUND);
        return context(current,false);
    }
    public static PracticeSessionContextPort.SessionContext context(Preparation p, boolean active) {
        return new PracticeSessionContextPort.SessionContext(p.sessionId(),p.guardianId(),p.childId(),active,
                p.goals().stream().map(g->new PracticeSessionContextPort.GoalSnapshot(g.goalSelectionId(),g.behaviorKey(),g.behaviorVersion(),
                        g.goalContext().candidate()?g.goalContext().fourElements().partner():"PEER",g.goalContext())).toList());
    }
    private WeightedGoalOrder.Selection selection(UUID guardian,UUID child,UUID id){
        var view=savedGoals.find(child,id).filter(v->v.childId().equals(child)&&v.selectionId().equals(id)).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
        var metadata=goals.preparationMetadata(guardian,child,id).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
        var original=kr.mujoong.core.practice.GoalContext.from(view);
        var aliases=privacy.require(guardian,child).aliases();
        var clean=sanitizer.sanitize(java.util.Map.of("name",metadata.displayName()),aliases,kr.mujoong.core.luna.LunaPurpose.SCENARIO_GENERATION).payload();
        var context=original.mapContent(text->(String)sanitizer.sanitize(java.util.Map.of("text",text),aliases,kr.mujoong.core.luna.LunaPurpose.SCENARIO_GENERATION).payload().get("text"));
        String criteria;
        if(context.candidate())criteria=context.criteria();
        else {
            kr.mujoong.core.knowledge.BehaviorDefinition definition;
            try{definition=knowledge.pinVersion(context.legacyBehaviorKey(),context.legacyBehaviorVersion());}
            catch(IllegalArgumentException invalid){throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);}
            if(!"ACTIVE".equals(definition.status()))throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
            criteria=definition.judgmentCriteria();
        }
        return new WeightedGoalOrder.Selection(guardian,child,id,(String)clean.get("name"),context.carrierKey(),context.carrierVersion(),criteria,Math.toIntExact(metadata.sortOrder()),metadata.favorite(),context);
    }
}
