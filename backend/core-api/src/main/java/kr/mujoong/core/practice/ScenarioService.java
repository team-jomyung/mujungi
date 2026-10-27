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
import kr.mujoong.core.usage.AiUsageLink;
import kr.mujoong.core.usage.AiUsageScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** C10, AC4.2.1/9 and AC6.5.5/8. Generation never performs a Realtime state transition. */
@Service
public class ScenarioService {
    /** 공개 오류 코드는 유지하고 준비 소유자에게 재요청 가능 여부만 전달한다. */
    public static final class GenerationFailure extends CoreApiException {
        private static final long serialVersionUID=1L;
        private final boolean permanent;
        GenerationFailure(boolean permanent){super(ErrorCode.SCENARIO_GEN_FAILED);this.permanent=permanent;}
        public boolean permanent(){return permanent;}
    }
    private final ScenarioRepository repository;
    private final PracticeSessionContextPort sessions;
    private final ScenarioRecordPort records;
    private final PracticeAccountPort accounts;
    private final PragmaticsKnowledge knowledge;
    private final TextAiGateway ai;
    private final MujoongClock clock;
    private final TransactionTemplate tx;
    private final ScenarioLimits limits;
    private StructuredScenarioFlow structured;
    private kr.mujoong.core.observability.EventLogger diagnostics;
    @org.springframework.beans.factory.annotation.Autowired
    void connectDiagnostics(kr.mujoong.core.observability.EventLogger events){this.diagnostics=events;}
    private void diagnose(String fixedCode){
        if(diagnostics!=null)diagnostics.appError(kr.mujoong.contract.log.EventLog.Component.ACTIVITY_COORDINATOR,fixedCode);
    }
    private kr.mujoong.core.consent.ConsentPolicyService consent;
    @org.springframework.beans.factory.annotation.Autowired
    void connectConsent(kr.mujoong.core.consent.ConsentPolicyService consent){this.consent=consent;}
    @org.springframework.beans.factory.annotation.Autowired
    void connectStructured(kr.mujoong.core.textai.TextAiProvider provider,ScenarioPrivacyContextPort privacy,
            kr.mujoong.core.luna.LunaInputSanitizer sanitizer,kr.mujoong.core.knowledge.ChildKnowledgeService graph,
            kr.mujoong.core.textai.ForbiddenExpressionPolicy forbidden,kr.mujoong.core.textai.TextAiProperties timing,
            @org.springframework.beans.factory.annotation.Qualifier("textAiExecutor") java.util.concurrent.ExecutorService executor,
            kr.mujoong.core.config.CoreProperties properties,JsonCodec json){
        if(provider instanceof kr.mujoong.core.textai.ScenarioScriptProvider scripts){
            structured=new StructuredScenarioFlow(new ScenarioScriptGenerator(scripts,new ScenarioScriptValidation(forbidden),timing,executor,
                    kr.mujoong.core.luna.CoreLunaClient.SYSTEM_SLEEPER::sleep).diagnostics(this::diagnose),privacy,sanitizer,
                    new ScenarioGraphContext(graph,executor,clock,properties.timing().knowledge().graphBudget()),repository,executor,
                    properties.timing().practice().scenarioHistoryTimeout(),json);
        }
    }
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
    /** 세션이 고정한 정의 버전을 고정한다. 쓸 수 없는 버전(없음 · 초안 · 미완성 · 고정 실패)은 계약 안의 400이다(U5a 리뷰 2). */
    private kr.mujoong.core.knowledge.BehaviorDefinition pin(GoalSnapshot goal) {
        try {
            return knowledge.pinVersion(goal.behaviorKey(),goal.behaviorVersion());
        } catch (IllegalArgumentException unusable) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
    }
    private void authorize(SessionContext context) {
        var account=accounts.requireActive(context.guardianId(),true);
        if (!account.childId().equals(context.childId())) throw CoreApiException.of(ErrorCode.NOT_FOUND);
    }

    public Scenario generate(Request request) {
        return generate(request, () -> context(request));
    }

    /** 시작 준비 소유자가 선점과 동의를 다시 확인하는 내부 경로. C10의 ACTIVE 검사는 바꾸지 않는다. */
    public Scenario generatePrepared(Request request, java.util.function.Supplier<SessionContext> preparation) {
        if (!"ORIGINAL".equals(request.kind()) || request.previousScenarioId()!=null)
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        return preparation instanceof PreparedContext bounded?generate(request,preparation,bounded.leaseUntil()):generate(request, preparation);
    }
    public record PreparedContext(Instant leaseUntil,java.util.function.Supplier<SessionContext> source) implements java.util.function.Supplier<SessionContext>{
        @Override public SessionContext get(){return source.get();}
    }

    private Scenario generate(Request request, java.util.function.Supplier<SessionContext> source) {
        return generate(request,source,clock.now().plus(limits.requestTimeout()));
    }
    private Scenario generate(Request request, java.util.function.Supplier<SessionContext> source,Instant deadline) {
        Instant started=clock.now();
        var budget=new ScenarioDeadline(clock,deadline);
        SessionContext context=source.get();
        // 장면 생성 · 검수 사용량을 이 세션(연습 준비 ID 또는 진행 중 활동)에 붙인다(JIRA-277)
        return AiUsageScope.call(new AiUsageLink(context.guardianId(),context.sessionId()),
                ()->generate(request,source,deadline,started,budget,context));
    }
    private Scenario generate(Request request, java.util.function.Supplier<SessionContext> source,Instant deadline,
            Instant started,ScenarioDeadline budget,SessionContext context) {
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
            if(structured!=null&&!consent.check(context.guardianId()).ok())throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
            return new Prepared(structured!=null&&goal.goalContext().candidate()?null:pin(goal),repository.claim(request.key(),context,request,clock.now(),deadline));
        });
        Objects.requireNonNull(prepared);
        var claim=prepared.claim();
        var definition=prepared.definition();
        String opportunity=previous==null ? (definition==null?goal.goalContext().fourElements().situation():definition.opportunityCondition()) : previous.scenario().opportunityCondition();
        if (claim.replay()!=null) return claim.replay();
        // 요청 키를 FAILED로 굳히는 것은 두 번 모두 내용 검증에 실패했을 때뿐이다. 다른 요청과의 경합 · 이미 낸 상황과 같은 내용은
        // 다시 만들면 될 수 있으므로 키를 풀어 같은 키로 다시 요청할 수 있게 한다(U5a 리뷰 6)
        boolean validationFailed=false;
        boolean retryableMiss=false;
        try {
            var account=accounts.requireActive(context.guardianId(),false);
            if (account.ageYears()==null) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
            if(structured!=null){
                ScenarioScriptGenerator.Result generated;
                try{generated=structured.generate(context,goal,definition,previous,account,started,budget);}
                catch(ScenarioScriptGenerator.ContentExhausted failure){validationFailed=!failure.retryableDuplicate;throw failure;}
                budget.check();
                try{return tx.execute(status->{
                    repository.beginDeadline(budget);
                    authorize(context);
                    repository.limitSql(budget);
                    SessionContext latest=source.get();
                    if(!latest.guardianId().equals(context.guardianId())||!latest.childId().equals(context.childId())||!goal(latest,request).equals(goal))
                        throw CoreApiException.of(ErrorCode.NOT_FOUND);
                    repository.limitSql(budget);
                    if(!consent.check(context.guardianId()).ok())throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
                    budget.check();return repository.save(request.key(),claim.claimId(),latest,goal,request,generated.legacy(),clock.now(),generated.details(),budget);
                });}
                catch(org.springframework.dao.DataAccessException database){throw ScenarioDeadline.failed();}
            }
            ScenarioPrompt prompt=new ScenarioPrompt(definition.displayName(),definition.behaviorDefinitionText(),opportunity,
                    definition.scenarioCondition(),relation,account.ageYears(),account.interests(),
                    previous==null ? null : previous.scenario().scenarioText());
            for (int attempt=0; attempt<2; attempt++) {
                if (!clock.now().isBefore(deadline)) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
                try {
                    var generated=ai.generateScenario(prompt,deadline);
                    if (!ScenarioContentPolicy.valid(generated,relation,opportunity)) continue;
                    if (previous!=null && ScenarioContentPolicy.normalize(previous.scenario().scenarioText())
                            .equals(ScenarioContentPolicy.normalize(generated.scenarioText()))) { retryableMiss=true; continue; }
                    if (repository.seen(context.childId(),ScenarioContentPolicy.fingerprint(generated.scenarioText()))) { retryableMiss=true; continue; }
                    SessionContext latest=source.get();
                    if (!latest.guardianId().equals(context.guardianId()) || !latest.childId().equals(context.childId())
                            || !goal(latest,request).equals(goal)) throw CoreApiException.of(ErrorCode.NOT_FOUND);
                    return tx.execute(status -> { authorize(latest);
                        return repository.save(request.key(),claim.claimId(),latest,goal,request,generated,clock.now()); });
                } catch (DuplicateKeyException duplicate) {
                    // 같은 아이의 같은 내용을 다른 요청이 먼저 저장했다(경합). 한 번 더 만든다
                    retryableMiss=true;
                } catch (TextAiException failure) {
                    if (failure.reason()==TextAiException.Reason.UNAVAILABLE || failure.reason()==TextAiException.Reason.TIMEOUT)
                        throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
                }
            }
            validationFailed=!retryableMiss;
            throw CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);
        } catch(CoreApiException failure) {
            if(structured!=null&&failure.code()==ErrorCode.SCENARIO_GEN_FAILED)throw new GenerationFailure(validationFailed);
            throw failure;
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
