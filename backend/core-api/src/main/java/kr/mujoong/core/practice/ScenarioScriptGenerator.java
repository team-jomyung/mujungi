package kr.mujoong.core.practice;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kr.mujoong.core.textai.GeneratedScenario;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import kr.mujoong.core.textai.TextAiProperties;

/** 생성·별도 검토·서버 검사. 내용 슬롯1·기술 슬롯1은 모든 호출이 공유한다. */
public final class ScenarioScriptGenerator {
    @FunctionalInterface public interface Sleeper{void sleep(Duration duration)throws InterruptedException;}
    public record Input(Map<String,Object> payload,Map<String,String> aliases,
            ScenarioScriptValidation.Rules rules,boolean graphContextUsed,String goalContextJson){
        public Input{payload=Map.copyOf(payload);aliases=Map.copyOf(aliases);}
        @Override public String toString(){return "ScenarioGenerationInput[redacted]";}
    }
    /** reference는 이 장면 입력에 넣은 참고 씨앗이다(없으면 null). 장면을 저장한 뒤에만 참고 시점을 남기려고 함께 돌려준다. */
    public record Result(GeneratedScenario legacy,ScenarioRepository.Details details,
            kr.mujoong.core.knowledge.ChildKnowledgeService.ReferenceSeed reference){
        public Result(GeneratedScenario legacy,ScenarioRepository.Details details){this(legacy,details,null);}
        Result withReference(kr.mujoong.core.knowledge.ChildKnowledgeService.ReferenceSeed seed){return new Result(legacy,details,seed);}
    }
    private final ScenarioScriptProvider provider;
    private final ScenarioScriptValidation validation;
    private final TextAiProperties timing;
    private final ExecutorService executor;
    private final Sleeper sleeper;
    private java.util.function.Consumer<String> diagnostics = ignored -> {};
    public ScenarioScriptGenerator diagnostics(java.util.function.Consumer<String> observer) {
        this.diagnostics=java.util.Objects.requireNonNull(observer);return this;
    }
    private void diagnose(String fixedCode) {
        // Observability must never alter generation or its fail-closed checks.
        try { diagnostics.accept(fixedCode); } catch(RuntimeException ignored) { }
    }
    public ScenarioScriptGenerator(ScenarioScriptProvider provider,ScenarioScriptValidation validation,
            TextAiProperties timing,ExecutorService executor,Sleeper sleeper){
        this.provider=provider;this.validation=validation;this.timing=timing;this.executor=executor;this.sleeper=sleeper;
    }
    private static final class Budget{int calls;boolean technical;}
    /** 변형 라운드 전에 통째로 독립 승인 · 서버 검사를 통과한 대본. 변형 라운드가 실패하면 고치지 않고 이것을 그대로 발급한다(JIRA-283). */
    private record Approved(ScenarioScript script,String model,String reviewModel,Input input){}
    public static final class ContentExhausted extends kr.mujoong.core.common.error.CoreApiException {
        private static final long serialVersionUID=1L;
        boolean retryableDuplicate;
        ContentExhausted(){super(kr.mujoong.contract.error.ErrorCode.SCENARIO_GEN_FAILED);}
        public boolean permanent(){return !retryableDuplicate;}
    }
    public Result generate(Input input,ScenarioDeadline deadline){
        var budget=new Budget();ScenarioScript.Scene preserved=null;String model=null;
        Input active=input;List<String> correctionReasons=List.of();ScenarioScript scriptToRepair=null;Approved approvedBeforeVariant=null;
        for(int correction=0;correction<=2;correction++){
            if(correction==2 && (correctionReasons.isEmpty() || !correctionReasons.stream().allMatch(
                    reason->java.util.Set.of("INCOMPLETE_SCENE","ANSWER_INCLUDED","EARLY_CUE","GOAL_MISMATCH").contains(reason))))break;
            final Input current=active;
            try {
                Map<String,Object> payload=new LinkedHashMap<>(current.payload());
                if(correction>0){payload.put("correction",true);payload.put("correctionReasons",correctionReasons);
                    if(scriptToRepair!=null)payload.put("scriptToRepair",scriptToRepair);}
                if(preserved!=null)payload.put("baseToPreserve",preserved);
                var generationStartedWith=deadline.remaining();
                var generated=invoke(()->provider.generate(call(payload,current,deadline)),budget,deadline);
                var generationCost=generationStartedWith.minus(deadline.remaining());
                if(generated==null||generated.script()==null||!metadata(generated.model()))throw content();
                model=generated.model();var script=includeBasePartnerLine(generated.script());
                var withCondition=includeOpportunityCondition(script,current.rules().opportunity());
                if(withCondition!=script){diagnose("ScenarioConditionAppended");script=withCondition;}
                var cleaned=validation.withoutInvalidSceneStart(script,current.rules());
                if(cleaned!=script){diagnose("ScenarioSceneStartDropped");script=cleaned;}
                scriptToRepair=script;
                Input reviewedInput=current;
                boolean planMismatch=false;
                if(current.graphContextUsed()&&current.rules().variants() instanceof ScenarioGraphContext.Session graph&&!script.scenes().isEmpty()){
                    var plan=graph.plan(script.scenes().getFirst());
                    if(plan.isPresent()){
                        var actual=withPlan(current,plan.get());
                        planMismatch=current.payload().containsKey("variantPlan")&&!current.payload().get("variantPlan").equals(actual.payload().get("variantPlan"));
                        if(!planMismatch)reviewedInput=actual;
                    }
                }
                var reviewStartedWith=deadline.remaining();
                var reviewed=review(script,reviewedInput,budget,deadline);
                var reviewCost=reviewStartedWith.minus(deadline.remaining());
                var inspection=validation.inspectDetailed(script,current.rules());
                var result=inspection.result();
                if(result!=ScenarioScriptValidation.Result.VALID)diagnose("ScenarioRule_"+inspection.reason().name());
                if(current.graphContextUsed()&&script.scenes().size()>1&&!current.payload().containsKey("variantPlan")&&result!=ScenarioScriptValidation.Result.INVALID_BASE)
                    result=ScenarioScriptValidation.Result.INVALID_VARIANT;
                if(planMismatch&&script.scenes().size()>1&&result!=ScenarioScriptValidation.Result.INVALID_BASE)
                    result=ScenarioScriptValidation.Result.INVALID_VARIANT;
                if(current.graphContextUsed()&&current.rules().variants() instanceof ScenarioGraphContext.Session graph&&graph.fallbackRequired()){
                    diagnose(graph.planMissingOnly()?"ScenarioGraphFallback_PLAN_EMPTY":"ScenarioGraphFallback_READ_FAILED");
                    // 대체로 다시 만들면 아래 거절 진단까지 가지 않으므로 첫 라운드 검토 결과를 여기서 남긴다
                    if(!reviewed.review().approved()){
                        diagnose("ScenarioReview_"+reviewed.review().verdict());
                        reviewed.review().reasons().forEach(reason->diagnose("ScenarioReview_"+reason));
                    }
                    // 그래프는 정상이고 변형 계획만 없을 때, 첫 라운드 대본이 통째로 승인 · 서버 검사를 통과했으면 다시 만들지 않고 그대로 발급한다(JIRA-283).
                    // 다시 만들면 호출 2번(약 12초)이 더 들어 30초 마감을 넘기기 쉽다. 그래프 읽기 실패면 지금처럼 그래프 없이 새로 만든다
                    if(correction==0&&graph.planMissingOnly()&&result==ScenarioScriptValidation.Result.VALID&&reviewed.review().approved()){
                        diagnose("ScenarioGraphFallbackApprovedIssued");
                        return approved(script,model,reviewed.model(),current,budget,deadline);
                    }
                    if(correction==1)throw ScenarioDeadline.failed();
                    active=withoutGraph(current);preserved=null;continue;
                }
                if(correction==0&&reviewedInput.payload().containsKey("variantPlan")&&result!=ScenarioScriptValidation.Result.INVALID_BASE){
                    // 다음 호출은 BASE와 VARIANT를 함께 생성하고 다시 검토해야 한다.
                    // 실제 첫 호출 비용으로 남은 예산을 비교하고, 부족하면 검증 완료 BASE만 발급한다.
                    // 거절/불확실/서버 검사 실패인 BASE는 이 경로로 발급하지 않는다.
                    var estimatedVariantCost=generationCost.multipliedBy(2).plus(reviewCost);
                    if(result==ScenarioScriptValidation.Result.VALID && reviewed.review().approved()){
                        if(deadline.remaining().compareTo(estimatedVariantCost)<=0)
                            return approved(script,model,reviewed.model(),current,budget,deadline);
                        approvedBeforeVariant=new Approved(script,model,reviewed.model(),current);
                    }
                    active=reviewedInput;
                    preserved=reviewed.review().approved()?script.scenes().getFirst():null;
                    continue;
                }
                if(preserved!=null&&(script.scenes().isEmpty()||!preserved.equals(script.scenes().getFirst())))result=ScenarioScriptValidation.Result.INVALID_BASE;
                if(result==ScenarioScriptValidation.Result.VALID&&reviewed.review().approved())
                    return approved(script,model,reviewed.model(),current,budget,deadline);
                correctionReasons=new java.util.ArrayList<>(reviewed.review().reasons());
                if(result!=ScenarioScriptValidation.Result.VALID)diagnose("ScenarioValidation_"+result.name());
                if(!reviewed.review().approved()){
                    diagnose("ScenarioReview_"+reviewed.review().verdict());
                    reviewed.review().reasons().forEach(reason->diagnose("ScenarioReview_"+reason));
                }
                if(approvedBeforeVariant!=null)return issueApprovedBeforeVariant(approvedBeforeVariant,budget,deadline);
                if(result!=ScenarioScriptValidation.Result.VALID)correctionReasons.add(result.name());
                if(!script.scenes().isEmpty()&&script.scenes().getFirst().sceneDescription()!=null){
                    var base=script.scenes().getFirst();
                    String description=ScenarioContentPolicy.normalize(base.sceneDescription());
                    if(base.opportunitySentence()!=null&&!description.contains(ScenarioContentPolicy.normalize(base.opportunitySentence())))
                        correctionReasons.add("OPPORTUNITY_NOT_COPIED");
                    if(!description.contains(ScenarioContentPolicy.normalize(current.rules().opportunity())))
                        correctionReasons.add("CONDITION_NOT_COPIED");
                    if(!java.util.Objects.equals(base.relation(),current.rules().relation()))correctionReasons.add("RELATION_MISMATCH");
                }
                boolean variantOnly=result==ScenarioScriptValidation.Result.INVALID_VARIANT
                        ||(!reviewed.review().reasons().isEmpty()&&reviewed.review().reasons().stream().allMatch(ScenarioScriptGenerator::variantReason));
                preserved=variantOnly&&result!=ScenarioScriptValidation.Result.INVALID_BASE&&!script.scenes().isEmpty()
                        ?script.scenes().getFirst():null;
            }catch(ScenarioScriptProvider.Failure failure){
                diagnose("ScenarioProvider_"+failure.kind().name());
                if(approvedBeforeVariant!=null)return issueApprovedBeforeVariant(approvedBeforeVariant,budget,deadline);
                if(failure.kind()!=ScenarioScriptProvider.Failure.Kind.CONTENT)throw ScenarioDeadline.failed();
                correctionReasons=List.of("INVALID_OUTPUT_FORMAT");
            }catch(kr.mujoong.core.common.error.CoreApiException exhausted){
                // 변형 라운드의 마감 · 호출 수 소진 · 그래프 대체 실패도 승인된 대본이 있으면 그것을 발급한다(마감이 지났으면 발급도 실패한다)
                if(approvedBeforeVariant==null)throw exhausted;
                return issueApprovedBeforeVariant(approvedBeforeVariant,budget,deadline);
            }
        }
        if(preserved!=null&&model!=null){
            try {
                var reduced=new ScenarioScript(List.of(preserved));var review=review(reduced,active,budget,deadline);
                if(review.review().approved()&&validation.inspect(reduced,active.rules())==ScenarioScriptValidation.Result.VALID)
                    return approved(reduced,model,review.model(),active,budget,deadline);
            }catch(ScenarioScriptProvider.Failure failure){
                if(failure.kind()!=ScenarioScriptProvider.Failure.Kind.CONTENT)throw ScenarioDeadline.failed();
            }
        }
        throw new ContentExhausted();
    }
    private Result issueApprovedBeforeVariant(Approved approved,Budget budget,ScenarioDeadline deadline){
        diagnose("ScenarioVariantFailedApprovedIssued");
        return approved(approved.script(),approved.model(),approved.reviewModel(),approved.input(),budget,deadline);
    }
    private Input withoutGraph(Input input){
        // 그래프 없이 만들 때는 참고 씨앗도 빼서 지금까지의 그래프 없는 장면과 같게 한다(유일한 불변 문장으로 오해되지 않게).
        var payload=new LinkedHashMap<>(input.payload());payload.remove("structureContext");payload.remove("variantPlan");payload.remove("referenceContext");payload.put("baseOnly",true);
        var r=input.rules();
        return new Input(payload,input.aliases(),new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),r.originalNames(),r.sourceText(),r.seen(),(a,b)->false),false,input.goalContextJson());
    }
    /** Internal description only. The repaired script must still receive independent approval and every server check. */
    static ScenarioScript includeBasePartnerLine(ScenarioScript script){
        if(script.scenes().isEmpty())return script;
        var base=script.scenes().getFirst();
        if(!"BASE".equals(base.sceneKind()) || base.sceneDescription()==null || base.sceneDescription().isBlank()
                || base.opportunitySentence()==null || base.opportunitySentence().isBlank()
                || ScenarioContentPolicy.normalize(base.sceneDescription()).contains(ScenarioContentPolicy.normalize(base.opportunitySentence())))return script;
        // These are already generated scene facts, never an inferred child answer or new event.
        String description=base.sceneDescription()+" 상대가 “"+base.opportunitySentence()+"”라고 말한다.";
        if(description.length()>8000)return script;
        var scenes=new java.util.ArrayList<>(script.scenes());scenes.set(0,base.withDescription(description));
        return new ScenarioScript(scenes);
    }
    /**
     * 기본 장면 설명에 기회 조건 원문이 없으면 끝에 덧붙인다(내부 설명만, 아이에게 읽지 않는다). 모델은 조건을 뜻은 같게 표현만 바꿔 쓰는 일이 잦아
     * 글자 그대로 비교하는 서버 검사(CONDITION_MISSING)에 걸려 고쳐 생성하느라 호출 2번을 더 썼다(2026-10-27 실키, JIRA-283). 덧붙인 대본도
     * 독립 검토와 모든 서버 검사를 그대로 거친다.
     */
    static ScenarioScript includeOpportunityCondition(ScenarioScript script,String condition){
        if(script.scenes().isEmpty()||condition==null||ScenarioContentPolicy.normalize(condition).isBlank())return script;
        var base=script.scenes().getFirst();
        if(!"BASE".equals(base.sceneKind())||base.sceneDescription()==null||base.sceneDescription().isBlank()
                ||ScenarioContentPolicy.normalize(base.sceneDescription()).contains(ScenarioContentPolicy.normalize(condition)))return script;
        String sentence=condition.strip();
        if(!sentence.matches(".*[.!?。]$"))sentence+=".";
        String description=base.sceneDescription().strip()+" "+sentence;
        if(description.length()>8000)return script;
        var scenes=new java.util.ArrayList<>(script.scenes());scenes.set(0,base.withDescription(description));
        return new ScenarioScript(scenes);
    }
    private Input withPlan(Input input,kr.mujoong.core.knowledge.VariantPlanner.Plan plan){
        var projected=Map.<String,Object>of("grade",plan.grade(),"changedField",plan.changedField(),"context",plan.context(),
                "signature",plan.signature(),"invariantSentence",plan.invariantSentence(),"preservationRule",plan.preservationRule(),
                "misreadCost",plan.misreadCost(),"recoverable",plan.recoverable());
        var clean=new kr.mujoong.core.luna.LunaInputSanitizer(kr.mujoong.contract.json.JsonCodec.shared()).sanitize(
                Map.of("variantPlan",projected),input.aliases(),kr.mujoong.core.luna.LunaPurpose.SCENARIO_GENERATION).payload();
        var payload=new LinkedHashMap<>(input.payload());payload.remove("baseOnly");payload.put("variantPlan",clean.get("variantPlan"));
        return new Input(payload,input.aliases(),input.rules(),input.graphContextUsed(),input.goalContextJson());
    }
    private ScenarioScriptProvider.Call call(Map<String,Object> payload,Input input,ScenarioDeadline deadline){
        return new ScenarioScriptProvider.Call(payload,input.aliases(),deadline.callDeadline(timing.requestTimeout()));
    }
    private ScenarioScriptProvider.Reviewed review(ScenarioScript script,Input input,Budget budget,ScenarioDeadline deadline){
        var payload=new LinkedHashMap<>(input.payload());payload.put("script",script);
        var reviewed=invoke(()->provider.review(call(payload,input,deadline)),budget,deadline);
        if(reviewed==null||reviewed.review()==null||!metadata(reviewed.model()))throw content();
        return reviewed;
    }
    private <T>T invoke(Supplier<T> operation,Budget budget,ScenarioDeadline deadline){
        for(;;){
            deadline.check();if(budget.calls>=6)throw ScenarioDeadline.failed();budget.calls++;
            try{return timedCall(operation,deadline);}
            catch(ScenarioScriptProvider.Failure failure){
                if(failure.kind()!=ScenarioScriptProvider.Failure.Kind.TRANSIENT||budget.technical||timing.retry().maxRetries()<1)throw failure;
                budget.technical=true;Duration wait=failure.retryAfter().filter(d->d.compareTo(timing.retry().backoff())>0).orElse(timing.retry().backoff());
                var left=deadline.remaining();if(left.compareTo(wait)<=0||left.minus(wait).compareTo(timing.retry().backoff())<0)throw ScenarioDeadline.failed();
                try{sleeper.sleep(wait);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw ScenarioDeadline.failed();}
            }
        }
    }
    private <T>T timedCall(Supplier<T> operation,ScenarioDeadline deadline){
        try{return deadline.call(executor,timing.requestTimeout(),operation::get);}
        catch(kr.mujoong.core.common.error.CoreApiException failure){
            deadline.check();
            throw new ScenarioScriptProvider.Failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT,Optional.empty());
        }
    }
    private Result approved(ScenarioScript script,String model,String reviewModel,Input input,Budget budget,ScenarioDeadline deadline){
        deadline.check();var base=script.scenes().getFirst();
        var legacy=new GeneratedScenario(base.sceneDescription(),base.opportunitySentence(),input.rules().relation(),input.rules().opportunity(),model,"scenario-generation.v4");
        var scenes=script.scenes().stream().map(s->s.withPartnerRole(ScenarioContentPolicy.PARTNER_ROLE)).toList();
        String version=input.graphContextUsed()&&input.rules().variants() instanceof ScenarioGraphContext.Session graph?graph.graphVersion():null;
        return new Result(legacy,new ScenarioRepository.Details("scenario-script.v1",scenes,new Scenario.Review("APPROVE",reviewModel,"scenario-review.v3"),new Scenario.Generation(model,"scenario-generation.v4",input.graphContextUsed(),budget.calls,version),input.goalContextJson()));
    }
    private static boolean variantReason(String code){return java.util.Set.of("INVARIANT_MISMATCH","SKILL_MISMATCH","TRANSFER_MISMATCH","UNCHANGED_VARIANT").contains(code);}
    private static boolean metadata(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");}
    private static ScenarioScriptProvider.Failure content(){return new ScenarioScriptProvider.Failure(ScenarioScriptProvider.Failure.Kind.CONTENT,Optional.empty());}
}
