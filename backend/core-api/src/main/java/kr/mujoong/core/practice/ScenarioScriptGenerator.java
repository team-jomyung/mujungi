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
import kr.mujoong.core.textai.ScenarioReview;
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
        Input active=input;List<String> correctionReasons=List.of();List<ScenarioReview.Finding> correctionFindings=List.of();ScenarioScript scriptToRepair=null;Approved approvedBeforeVariant=null;
        Duration lastRoundCost=null,lastReviewCost=null;
        for(int correction=0;correction<=2;correction++){
            if(correction==2 && (correctionReasons.isEmpty() || !correctionReasons.stream().allMatch(
                    reason->REPAIRABLE.contains(reason))))break;
            // 직전 라운드(생성 + 검토)만큼 시간이 남지 않으면 검토까지 끝낼 수 없다. 마감 직전 생성 호출을 쓰고
            // 검토가 시간 초과로 끝나던 것을 막는다(2026-10-28 실키, FT-1028-c)
            if(lastRoundCost!=null&&deadline.remaining().compareTo(lastRoundCost)<=0){
                diagnose("ScenarioBudgetInsufficient");
                if(approvedBeforeVariant!=null)return issueApprovedBeforeVariant(approvedBeforeVariant,budget,deadline);
                break;
            }
            final Input current=active;
            try {
                Map<String,Object> payload=new LinkedHashMap<>(current.payload());
                if(correction>0){payload.put("correction",true);payload.put("correctionReasons",correctionReasons);
                    // 검토가 지목한 점검 항목 · 필드(닫힌 값)도 넘긴다. 사유만 받으면 어느 필드를 고칠지 몰라 지목된 필드를 그대로 둔
                    // 대본을 다시 냈다(2026-10-30 합성 실호출, FT-1030-f)
                    if(!correctionFindings.isEmpty())payload.put("correctionFindings",findingsInput(correctionFindings));
                    if(scriptToRepair!=null)payload.put("scriptToRepair",scriptToRepair);}
                if(preserved!=null)payload.put("baseToPreserve",preserved);
                var generationStartedWith=deadline.remaining();
                var generated=invoke(()->provider.generate(call(payload,current,deadline)),budget,deadline);
                var generationCost=generationStartedWith.minus(deadline.remaining());
                if(generated==null||generated.script()==null||!metadata(generated.model()))throw content();
                model=generated.model();var script=includeBasePartnerLine(generated.script());
                // 한국어 장면에 일본어 · 한자 문장이 섞여 나왔다(2026-10-30 합성 실호출). 검토 승인과 관계없이 고쳐 쓰게 한다(FT-1030-j)
                boolean foreign=foreignScript(script);
                if(foreign)diagnose("ScenarioForeignScriptText");
                var withCondition=includeOpportunityCondition(script,current.rules().opportunity());
                if(withCondition!=script){diagnose("ScenarioConditionAppended");script=withCondition;}
                var cleaned=validation.withoutInvalidSceneStart(script,current.rules());
                if(cleaned!=script){diagnose("ScenarioSceneStartDropped");script=cleaned;}
                if(preserved!=null){
                    // 변형 라운드의 BASE는 이미 승인된 것이다. 모델이 글자 하나라도 다르게 옮기면 서버 검사(INVALID_BASE)에 걸려 변형이 매번 실패했다
                    // (2026-10-30 실키 4번 모두). 모델에게서는 VARIANT만 받고 BASE는 승인본으로 고정한다(FT-1030-o)
                    var pinned=pinBase(script,preserved);
                    if(pinned!=script){diagnose("ScenarioVariantBasePinned");script=pinned;}
                }
                scriptToRepair=script;
                Input reviewedInput=current;
                boolean planMismatch=false;
                if(current.graphContextUsed()&&current.rules().variants() instanceof ScenarioGraphContext.Session graph&&!script.scenes().isEmpty()){
                    var plan=graph.plan(script.scenes().getFirst());
                    if(plan.isPresent()){
                        var actual=withPlan(current,plan.get());
                        diagnose("ScenarioVariantPlan_"+plan.get().grade()+"_"+plan.get().changedField());
                        planMismatch=current.payload().containsKey("variantPlan")&&!current.payload().get("variantPlan").equals(actual.payload().get("variantPlan"));
                        if(!planMismatch)reviewedInput=actual;
                    }
                }
                var reviewStartedWith=deadline.remaining();
                var reviewed=review(script,reviewedInput,budget,deadline);
                boolean approvedScript=reviewed.review().approved()&&!foreign;
                var reviewCost=reviewStartedWith.minus(deadline.remaining());
                lastReviewCost=reviewCost;lastRoundCost=generationCost.plus(reviewCost);
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
                    if(!reviewed.review().approved())diagnoseReview(reviewed.review());
                    // 그래프는 정상이고 변형 계획만 없을 때, 첫 라운드 대본이 통째로 승인 · 서버 검사를 통과했으면 다시 만들지 않고 그대로 발급한다(JIRA-283).
                    // 다시 만들면 호출 2번(약 12초)이 더 들어 30초 마감을 넘기기 쉽다. 그래프 읽기 실패면 지금처럼 그래프 없이 새로 만든다
                    if(correction==0&&graph.planMissingOnly()&&result==ScenarioScriptValidation.Result.VALID&&approvedScript){
                        diagnose("ScenarioGraphFallbackApprovedIssued");
                        return approved(script,model,reviewed.model(),current,budget,deadline);
                    }
                    if(correction==1)throw ScenarioDeadline.failed();
                    // 다음 생성은 이 대본을 고쳐 만드므로 거절 이유를 함께 넘긴다. 빠지면 이유 없이 같은 대본을 다시 고쳐
                    // 같은 사유로 또 거절됐다(2026-10-28 실키, FT-1028-c). 변형 판정은 BASE만 만드는 다음 생성과 무관해 넘기지 않는다
                    correctionReasons=new java.util.ArrayList<>(reviewed.review().reasons());
                    correctionFindings=reviewed.review().findings();
                    if(result==ScenarioScriptValidation.Result.INVALID_BASE)correctionReasons.add(result.name());
                    if(foreign)correctionReasons.add(FOREIGN_SCRIPT_REASON);
                    active=withoutGraph(current);preserved=null;continue;
                }
                if(correction==0&&reviewedInput.payload().containsKey("variantPlan")&&result!=ScenarioScriptValidation.Result.INVALID_BASE){
                    // 다음 호출은 BASE와 VARIANT를 함께 생성하고 다시 검토해야 한다.
                    // 실제 첫 호출 비용으로 남은 예산을 비교하고, 부족하면 검증 완료 BASE만 발급한다.
                    // 거절/불확실/서버 검사 실패인 BASE는 이 경로로 발급하지 않는다.
                    var estimatedVariantCost=generationCost.multipliedBy(2).plus(reviewCost);
                    // 변형 라운드로 넘어가기 전 첫 BASE의 판정을 남긴다. 빠져 있어 변형 경로 실패의 원인을 기록으로 가릴 수 없었다(2026-10-30 실키)
                    // 변형 장면만 서버 검사에 걸린 경우(INVALID_VARIANT)는 BASE 자체가 승인된 것이라 지금처럼 BASE를 지키고 변형 라운드로 간다
                    boolean baseApproved=approvedScript&&(result==ScenarioScriptValidation.Result.VALID||result==ScenarioScriptValidation.Result.INVALID_VARIANT);
                    diagnose("ScenarioVariantBase_"+(baseApproved?"APPROVED":"NOT_APPROVED"));
                    if(result!=ScenarioScriptValidation.Result.VALID)diagnose("ScenarioValidation_"+result.name());
                    if(!reviewed.review().approved())diagnoseReview(reviewed.review());
                    if(!baseApproved){
                        // 거절된 BASE는 변형 라운드로 보내지 않는다. 사유 없이 BASE와 VARIANT를 함께 고쳐 만들게 하면 호출이 무거워 시간 초과가
                        // 잦고 고쳐지지도 않았다(2026-10-30 실키 3회 연속 실패). 변형 계획이 없던 때처럼 사유 · 지적 필드를 넘겨 그래프 없이
                        // BASE만 다시 만든다. 구조(보존) 사유는 그래프 없는 장면과 무관해 빼고 넘긴다(FT-1030-i)
                        correctionReasons=new java.util.ArrayList<>(reviewed.review().reasons().stream().filter(r->!variantReason(r)).toList());
                        correctionFindings=reviewed.review().findings().stream().filter(f->!variantReason(f.reason())).toList();
                        if(result!=ScenarioScriptValidation.Result.VALID)correctionReasons.add(result.name());
                        if(foreign)correctionReasons.add(FOREIGN_SCRIPT_REASON);
                        active=withoutGraph(current);preserved=null;
                        continue;
                    }
                    if(result==ScenarioScriptValidation.Result.VALID){
                        if(deadline.remaining().compareTo(estimatedVariantCost)<=0)
                            return approved(script,model,reviewed.model(),current,budget,deadline);
                        approvedBeforeVariant=new Approved(script,model,reviewed.model(),current);
                    }
                    active=reviewedInput;
                    preserved=script.scenes().getFirst();
                    continue;
                }
                if(preserved!=null&&(script.scenes().isEmpty()||!preserved.equals(script.scenes().getFirst())))result=ScenarioScriptValidation.Result.INVALID_BASE;
                if(result==ScenarioScriptValidation.Result.VALID&&approvedScript)
                    return approved(script,model,reviewed.model(),current,budget,deadline);
                correctionReasons=new java.util.ArrayList<>(reviewed.review().reasons());
                correctionFindings=reviewed.review().findings();
                if(foreign)correctionReasons.add(FOREIGN_SCRIPT_REASON);
                if(result!=ScenarioScriptValidation.Result.VALID)diagnose("ScenarioValidation_"+result.name());
                if(!reviewed.review().approved())diagnoseReview(reviewed.review());
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
        if(preserved!=null&&model!=null&&(lastReviewCost==null||deadline.remaining().compareTo(lastReviewCost)>0)){
            try {
                var reduced=new ScenarioScript(List.of(preserved));var review=review(reduced,active,budget,deadline);
                var reducedResult=validation.inspect(reduced,active.rules());
                if(review.review().approved()&&reducedResult==ScenarioScriptValidation.Result.VALID)
                    return approved(reduced,model,review.model(),active,budget,deadline);
                // 변형을 빼고 BASE만 다시 검토한 결과도 남긴다(대본 원문 없음)
                diagnose("ScenarioReducedReview_"+(review.review().approved()?"APPROVED":"NOT_APPROVED"));
                if(reducedResult!=ScenarioScriptValidation.Result.VALID)diagnose("ScenarioValidation_"+reducedResult.name());
                if(!review.review().approved())diagnoseReview(review.review());
            }catch(ScenarioScriptProvider.Failure failure){
                if(failure.kind()!=ScenarioScriptProvider.Failure.Kind.CONTENT)throw ScenarioDeadline.failed();
            }
        }
        throw new ContentExhausted();
    }
    /** 거절 판정 · 사유와, 사유마다 걸린 점검 항목 · 필드를 고정 코드로만 남긴다(대본 원문 없음, R-F14). */
    private void diagnoseReview(kr.mujoong.core.textai.ScenarioReview review){
        diagnose("ScenarioReview_"+review.verdict());
        review.reasons().forEach(reason->diagnose("ScenarioReview_"+reason));
        review.findings().forEach(f->diagnose("ScenarioReviewFinding_"+f.reason()+"_"+f.check()+"_"+f.field()));
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
        return new ScenarioScriptProvider.Call(payload,input.aliases(),deadline.callDeadline(timing.scenarioRequestTimeout()));
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
        try{return deadline.call(executor,timing.scenarioRequestTimeout(),operation::get);}
        catch(kr.mujoong.core.common.error.CoreApiException failure){
            deadline.check();
            throw new ScenarioScriptProvider.Failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT,Optional.empty());
        }
    }
    /** 첫 장면을 승인된 BASE로 바꾼다. 모델이 BASE를 빼고 VARIANT만 냈으면 앞에 넣는다. 이미 같으면 그대로 둔다. */
    static ScenarioScript pinBase(ScenarioScript script,ScenarioScript.Scene base){
        if(script.scenes().isEmpty())return script;
        var scenes=new java.util.ArrayList<>(script.scenes());
        if("BASE".equals(scenes.getFirst().sceneKind())){
            if(base.equals(scenes.getFirst()))return script;
            scenes.set(0,base);
        }else scenes.add(0,base);
        return new ScenarioScript(scenes);
    }
    /** 검토 지적을 생성 입력 모양(사유 · 점검 항목 · 필드, 모두 닫힌 값)으로 바꾼다. 대본 원문 · 자유 설명은 담지 않는다. */
    private static List<Map<String,String>> findingsInput(List<ScenarioReview.Finding> findings){
        return findings.stream().map(f->Map.of("reason",f.reason(),"check",f.check(),"field",f.field())).toList();
    }
    private Result approved(ScenarioScript script,String model,String reviewModel,Input input,Budget budget,ScenarioDeadline deadline){
        deadline.check();var base=script.scenes().getFirst();
        var legacy=new GeneratedScenario(base.sceneDescription(),base.opportunitySentence(),input.rules().relation(),input.rules().opportunity(),model,"scenario-generation.v7");
        var scenes=script.scenes().stream().map(s->s.withPartnerRole(ScenarioContentPolicy.PARTNER_ROLE)).toList();
        String version=input.graphContextUsed()&&input.rules().variants() instanceof ScenarioGraphContext.Session graph?graph.graphVersion():null;
        return new Result(legacy,new ScenarioRepository.Details("scenario-script.v1",scenes,new Scenario.Review("APPROVE",reviewModel,"scenario-review.v6"),new Scenario.Generation(model,"scenario-generation.v7",input.graphContextUsed(),budget.calls,version),input.goalContextJson()));
    }
    /**
     * 마지막 수정 라운드를 쓸 수 있는 장면 보완 사유. 외국 문자는 생성이 다시 써서 고칠 수 있다(FT-1030-j). 반복은 장소 · 활동 · 핵심 사건을 새로
     * 골라 고칠 수 있다(FT-1030-l: 목표가 특정 물건에 묶이면 최근 노출과 겹치기 쉬워 두 라운드로는 고쳐지지 않았다).
     */
    private static final java.util.Set<String> REPAIRABLE=java.util.Set.of("INCOMPLETE_SCENE","ANSWER_INCLUDED","EARLY_CUE","GOAL_MISMATCH","FOREIGN_SCRIPT","REPETITION");
    static final String FOREIGN_SCRIPT_REASON="FOREIGN_SCRIPT";
    private static final java.util.regex.Pattern FOREIGN_SCRIPT=java.util.regex.Pattern.compile("[\\u3040-\\u30ff\\u4e00-\\u9fff]");
    /** 아이가 듣거나 장면을 이루는 문장에 가나 · 한자가 들어 있는가(대본 원문은 남기지 않는다). */
    static boolean foreignScript(ScenarioScript script){
        return script.scenes().stream().flatMap(s->java.util.stream.Stream.of(s.sceneDescription(),s.spokenIntroduction(),s.openingLine(),
                s.opportunitySentence(),s.reexplanation())).anyMatch(text->text!=null&&FOREIGN_SCRIPT.matcher(text).find());
    }
    private static boolean variantReason(String code){return java.util.Set.of("INVARIANT_MISMATCH","SKILL_MISMATCH","TRANSFER_MISMATCH","UNCHANGED_VARIANT").contains(code);}
    private static boolean metadata(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");}
    private static ScenarioScriptProvider.Failure content(){return new ScenarioScriptProvider.Failure(ScenarioScriptProvider.Failure.Kind.CONTENT,Optional.empty());}
}
