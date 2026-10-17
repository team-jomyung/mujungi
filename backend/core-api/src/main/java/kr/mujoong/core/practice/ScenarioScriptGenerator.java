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
    public record Result(GeneratedScenario legacy,ScenarioRepository.Details details){}
    private final ScenarioScriptProvider provider;
    private final ScenarioScriptValidation validation;
    private final TextAiProperties timing;
    private final ExecutorService executor;
    private final Sleeper sleeper;
    public ScenarioScriptGenerator(ScenarioScriptProvider provider,ScenarioScriptValidation validation,
            TextAiProperties timing,ExecutorService executor,Sleeper sleeper){
        this.provider=provider;this.validation=validation;this.timing=timing;this.executor=executor;this.sleeper=sleeper;
    }
    private static final class Budget{int calls;boolean technical;}
    public static final class ContentExhausted extends kr.mujoong.core.common.error.CoreApiException {
        private static final long serialVersionUID=1L;
        boolean retryableDuplicate;
        ContentExhausted(){super(kr.mujoong.contract.error.ErrorCode.SCENARIO_GEN_FAILED);}
        public boolean permanent(){return !retryableDuplicate;}
    }
    public Result generate(Input input,ScenarioDeadline deadline){
        var budget=new Budget();ScenarioScript.Scene preserved=null;String model=null;
        Input active=input;
        for(int correction=0;correction<=1;correction++){
            final Input current=active;
            try {
                Map<String,Object> payload=new LinkedHashMap<>(current.payload());
                if(correction>0)payload.put("correction",true);
                if(preserved!=null)payload.put("baseToPreserve",preserved);
                var generated=invoke(()->provider.generate(call(payload,current,deadline)),budget,deadline);
                if(generated==null||generated.script()==null||!metadata(generated.model()))throw content();
                model=generated.model();var script=generated.script();
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
                var reviewed=review(script,reviewedInput,budget,deadline);
                var result=validation.inspect(script,current.rules());
                if(current.graphContextUsed()&&script.scenes().size()>1&&!current.payload().containsKey("variantPlan")&&result!=ScenarioScriptValidation.Result.INVALID_BASE)
                    result=ScenarioScriptValidation.Result.INVALID_VARIANT;
                if(planMismatch&&script.scenes().size()>1&&result!=ScenarioScriptValidation.Result.INVALID_BASE)
                    result=ScenarioScriptValidation.Result.INVALID_VARIANT;
                if(current.graphContextUsed()&&current.rules().variants() instanceof ScenarioGraphContext.Session graph&&graph.fallbackRequired()){
                    if(correction==1)throw ScenarioDeadline.failed();
                    active=withoutGraph(current);preserved=null;continue;
                }
                if(correction==0&&reviewedInput.payload().containsKey("variantPlan")&&result!=ScenarioScriptValidation.Result.INVALID_BASE){
                    active=reviewedInput;
                    preserved=reviewed.review().approved()?script.scenes().getFirst():null;
                    continue;
                }
                if(preserved!=null&&(script.scenes().isEmpty()||!preserved.equals(script.scenes().getFirst())))result=ScenarioScriptValidation.Result.INVALID_BASE;
                if(result==ScenarioScriptValidation.Result.VALID&&reviewed.review().approved())
                    return approved(script,model,reviewed.model(),current,budget,deadline);
                boolean variantOnly=result==ScenarioScriptValidation.Result.INVALID_VARIANT
                        ||(!reviewed.review().reasons().isEmpty()&&reviewed.review().reasons().stream().allMatch(ScenarioScriptGenerator::variantReason));
                preserved=variantOnly&&result!=ScenarioScriptValidation.Result.INVALID_BASE&&!script.scenes().isEmpty()
                        ?script.scenes().getFirst():null;
            }catch(ScenarioScriptProvider.Failure failure){
                if(failure.kind()!=ScenarioScriptProvider.Failure.Kind.CONTENT)throw ScenarioDeadline.failed();
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
    private Input withoutGraph(Input input){
        var payload=new LinkedHashMap<>(input.payload());payload.remove("structureContext");payload.remove("variantPlan");payload.put("baseOnly",true);
        var r=input.rules();
        return new Input(payload,input.aliases(),new ScenarioScriptValidation.Rules(r.relation(),r.opportunity(),r.targetExpression(),r.originalNames(),r.sourceText(),r.seen(),(a,b)->false),false,input.goalContextJson());
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
        var legacy=new GeneratedScenario(base.sceneDescription(),base.opportunitySentence(),input.rules().relation(),input.rules().opportunity(),model,"scenario-generation.v2");
        var scenes=script.scenes().stream().map(s->s.withPartnerRole(ScenarioContentPolicy.PARTNER_ROLE)).toList();
        String version=input.graphContextUsed()&&input.rules().variants() instanceof ScenarioGraphContext.Session graph?graph.graphVersion():null;
        return new Result(legacy,new ScenarioRepository.Details("scenario-script.v1",scenes,new Scenario.Review("APPROVE",reviewModel,"scenario-review.v1"),new Scenario.Generation(model,"scenario-generation.v2",input.graphContextUsed(),budget.calls,version),input.goalContextJson()));
    }
    private static boolean variantReason(String code){return java.util.Set.of("INVARIANT_MISMATCH","SKILL_MISMATCH","TRANSFER_MISMATCH","UNCHANGED_VARIANT").contains(code);}
    private static boolean metadata(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");}
    private static ScenarioScriptProvider.Failure content(){return new ScenarioScriptProvider.Failure(ScenarioScriptProvider.Failure.Kind.CONTENT,Optional.empty());}
}
