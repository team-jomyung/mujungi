package kr.mujoong.core.practice;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.knowledge.PragmaticsKnowledge;
import kr.mujoong.core.practice.PracticeSessionContextPort.GoalSnapshot;
import kr.mujoong.core.practice.PracticeSessionContextPort.SessionContext;
import kr.mujoong.core.practice.ScenarioConfiguration.ScenarioLimits;
import kr.mujoong.core.textai.ScenarioPrompt;
import kr.mujoong.core.textai.TextAiException;
import kr.mujoong.core.textai.TextAiGateway;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** C10, AC4.2.1/9 and AC6.5.5/8. Generation never performs a Realtime state transition. */
@Service
public class ScenarioService {
    private final ScenarioRepository repository;
    private final PracticeSessionContextPort sessions;
    private final ScenarioRecordPort records;
    private final PracticeAccountPort accounts;
    private final PragmaticsKnowledge knowledge;
    private final TextAiGateway ai;
    private final MujoongClock clock;
    private final TransactionTemplate tx;
    private final ScenarioLimits limits;
    public ScenarioService(ScenarioRepository repository, PracticeSessionContextPort sessions, ScenarioRecordPort records,
            PracticeAccountPort accounts, PragmaticsKnowledge knowledge, TextAiGateway ai, MujoongClock clock,
            TransactionTemplate tx, ScenarioLimits limits) {
        this.repository=repository; this.sessions=sessions; this.records=records; this.accounts=accounts;
        this.knowledge=knowledge; this.ai=ai; this.clock=clock; this.tx=tx; this.limits=limits;
    }
    public record Request(UUID sessionId, UUID goalSelectionId, String kind, UUID previousScenarioId) {
        public Request {
            if (sessionId==null || goalSelectionId==null || !("ORIGINAL".equals(kind) || "NEW_SCENARIO".equals(kind))
                    || ("ORIGINAL".equals(kind) && previousScenarioId!=null)
                    || ("NEW_SCENARIO".equals(kind) && previousScenarioId==null)) {
                throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
        String key() {
            return JsonCodec.shared().sha256OfCanonical(JsonCodec.shared().write(List.of(sessionId,goalSelectionId,kind,
                    previousScenarioId == null ? "" : previousScenarioId.toString())));
        }
    }
    private SessionContext context(Request request) {
        SessionContext result=sessions.require(request.sessionId());
        if (result==null || !request.sessionId().equals(result.sessionId())) throw CoreApiException.of(ErrorCode.NOT_FOUND);
        if (!result.active()) throw CoreApiException.of(ErrorCode.SESSION_NOT_ACTIVE);
        goal(result,request);
        return result;
    }
    private GoalSnapshot goal(SessionContext context, Request request) {
        return context.goals().stream().filter(g -> request.goalSelectionId().equals(g.goalSelectionId())).findFirst()
                .orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
    }
    private void authorize(SessionContext context) {
        var account=accounts.requireActive(context.guardianId(),true);
        if (!account.childId().equals(context.childId())) throw CoreApiException.of(ErrorCode.NOT_FOUND);
    }

    public Scenario generate(Request request) {
        Instant deadline=clock.now().plus(limits.requestTimeout());
        SessionContext context=context(request);
        GoalSnapshot goal=goal(context,request);
        ScenarioRepository.Stored previous=request.previousScenarioId()==null ? null : repository.require(request.previousScenarioId());
        if (previous!=null && (!previous.sessionId().equals(context.sessionId()) || !previous.childId().equals(context.childId())
                || !previous.guardianId().equals(context.guardianId()) || !previous.goalSelectionId().equals(goal.goalSelectionId())
                || !previous.behaviorKey().equals(goal.behaviorKey()) || previous.behaviorVersion()!=goal.behaviorVersion()
                || !previous.scenario().kind().equals("ORIGINAL"))) throw CoreApiException.of(ErrorCode.NOT_FOUND);
        String relation=previous==null ? goal.relationType() : previous.scenario().relationType();
        if (relation==null || relation.isBlank()) throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        record Prepared(kr.mujoong.core.knowledge.BehaviorDefinition definition, ScenarioRepository.Claim claim) {}
        var prepared=tx.execute(status -> {
            authorize(context);
            var pinned=knowledge.pinVersion(goal.behaviorKey(),goal.behaviorVersion());
            return new Prepared(pinned,repository.claim(request.key(),context,request,clock.now(),deadline));
        });
        Objects.requireNonNull(prepared);
        var claim=prepared.claim();
        var definition=prepared.definition();
        String opportunity=previous==null ? definition.opportunityCondition() : previous.scenario().opportunityCondition();
        if (claim.replay()!=null) return claim.replay();
        boolean validationFailed=false;
        try {
            var account=accounts.requireActive(context.guardianId(),false);
            if (account.ageYears()==null) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
            ScenarioPrompt prompt=new ScenarioPrompt(definition.displayName(),definition.behaviorDefinitionText(),opportunity,
                    definition.scenarioCondition(),relation,account.ageYears(),account.interests(),
                    previous==null ? null : previous.scenario().scenarioText());
            for (int attempt=0; attempt<2; attempt++) {
                if (!clock.now().isBefore(deadline)) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
                try {
                    var generated=ai.generateScenario(prompt,deadline);
                    if (!ScenarioContentPolicy.valid(generated,relation,opportunity)) continue;
                    if (previous!=null && ScenarioContentPolicy.normalize(previous.scenario().scenarioText())
                            .equals(ScenarioContentPolicy.normalize(generated.scenarioText()))) continue;
                    if (repository.seen(context.childId(),ScenarioContentPolicy.fingerprint(generated.scenarioText(),generated.triggerScript()))) continue;
                    SessionContext latest=context(request);
                    if (!latest.guardianId().equals(context.guardianId()) || !latest.childId().equals(context.childId())
                            || !goal(latest,request).equals(goal)) throw CoreApiException.of(ErrorCode.NOT_FOUND);
                    return tx.execute(status -> { authorize(latest);
                        return repository.save(request.key(),claim.claimId(),latest,goal,request,generated,clock.now()); });
                } catch (DuplicateKeyException duplicate) {
                    // Another request won the child/content uniqueness race. Regenerate once.
                } catch (TextAiException failure) {
                    if (failure.reason()==TextAiException.Reason.UNAVAILABLE || failure.reason()==TextAiException.Reason.TIMEOUT)
                        throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
                }
            }
            validationFailed=true;
            throw CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);
        } finally {
            boolean failed=validationFailed;
            tx.executeWithoutResult(status -> repository.release(request.key(),claim.claimId(),failed,clock.now()));
        }
    }

    /** Called by a future U4 ActivityRecorded adapter. Only the authoritative recorded exposures are applied. */
    public int reconcileExposures(UUID sessionId) {
        var record=records.require(sessionId);
        if (record==null || !sessionId.equals(record.sessionId())) throw CoreApiException.of(ErrorCode.NOT_FOUND);
        return Objects.requireNonNull(tx.execute(status -> {
            var account=accounts.requireActive(record.guardianId(),true);
            if (!account.childId().equals(record.childId())) throw CoreApiException.of(ErrorCode.NOT_FOUND);
            int count=0;
            for (var exposure: record.exposures()) {
                var stored=repository.require(exposure.scenarioId());
                if (!stored.sessionId().equals(sessionId) || !stored.guardianId().equals(record.guardianId())
                        || !stored.childId().equals(record.childId()) || exposure.exposedAt()==null
                        || exposure.exposedAt().isAfter(clock.now())) throw CoreApiException.of(ErrorCode.NOT_FOUND);
                count+=repository.expose(exposure.scenarioId(),sessionId,exposure.exposedAt());
            }
            return count;
        }));
    }
}
