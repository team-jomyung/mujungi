package kr.mujoong.core.practice;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.ScenarioScript.Scene;

/** BR2: 별도 검토의 APPROVE와 독립적인 결정적 검사. 의미 품질을 보증한다고 주장하지 않는다. */
public final class ScenarioScriptValidation {
    public enum Result { VALID, INVALID_BASE, INVALID_VARIANT }
    @FunctionalInterface public interface VariantCheck {boolean valid(Scene base,Scene variant);}
    public record Rules(String relation,String opportunity,String targetExpression,Set<String> originalNames,
            String sourceText,Predicate<String> seen,VariantCheck variants) {
        public Rules {originalNames=Set.copyOf(originalNames);}
        @Override public String toString(){return "ScenarioRules[redacted]";}
    }
    private final ForbiddenExpressionPolicy forbidden;
    public ScenarioScriptValidation(ForbiddenExpressionPolicy forbidden){this.forbidden=forbidden;}
    public Result inspect(ScenarioScript script,Rules rules){
        if(script==null||script.scenes().isEmpty())return Result.INVALID_BASE;
        var scenes=script.scenes();var base=scenes.getFirst();
        if(!"BASE".equals(base.sceneKind())||scenes.stream().filter(s->"BASE".equals(s.sceneKind())).count()!=1)
            return Result.INVALID_BASE;
        var keys=new HashSet<String>();
        if(scenes.stream().anyMatch(s->s.sceneKey()==null||!s.sceneKey().matches("[A-Za-z0-9_-]{1,40}")||!keys.add(s.sceneKey())))return Result.INVALID_BASE;
        if(!safe(base,rules)||!rules.relation().equals(base.relation())
                ||!contains(base.sceneDescription(),rules.opportunity())
                ||!contains(base.sceneDescription(),base.opportunitySentence())
                ||rules.seen().test(ScenarioContentPolicy.fingerprint(base.sceneDescription()))
                ||(rules.sourceText()!=null&&ScenarioContentPolicy.normalize(base.sceneDescription()).equals(ScenarioContentPolicy.normalize(rules.sourceText()))))return Result.INVALID_BASE;
        for(var scene:scenes.subList(1,scenes.size()))
            if(!"VARIANT".equals(scene.sceneKind())||!safe(scene,rules)||!rules.variants().valid(base,scene))return Result.INVALID_VARIANT;
        return Result.VALID;
    }
    private boolean safe(Scene s,Rules rules){
        if(!text(s.sceneDescription(),8000)||!text(s.opportunitySentence(),2000)||!text(s.reexplanation(),2000)
                ||!text(s.relation(),200)||!text(s.place(),200)||!text(s.topic(),200)
                ||!list(s.focusPoints())||!list(s.expressionExamples()))return false;
        var spoken=List.of(s.sceneDescription(),s.opportunitySentence(),s.reexplanation());
        var all=new java.util.ArrayList<>(spoken);all.addAll(s.focusPoints());all.addAll(s.expressionExamples());
        all.addAll(List.of(s.relation(),s.place(),s.topic()));
        if(s.preservation()!=null) {
            var p=s.preservation();
            if(!text(p.skillRef(),200)||!text(p.invariantSentence(),2000)||!text(p.preservationRule(),2000)
                    ||!text(p.transferDistance(),200)||!text(p.changedElement(),200)||!text(p.participants(),200)||!text(p.situation(),2000))return false;
            all.addAll(List.of(p.skillRef(),p.invariantSentence(),p.preservationRule(),p.transferDistance(),p.changedElement(),p.participants(),p.situation()));
        }
        if(all.stream().anyMatch(v->!forbidden.isAllowed(v)||rules.originalNames().stream().anyMatch(v::contains)
                ||v.matches("(?s).*(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}).*")))return false;
        String target=rules.targetExpression();
        if(target!=null&&!ScenarioContentPolicy.normalize(target).isBlank()
                &&spoken.stream().anyMatch(v->contains(v,target)))return false;
        return spoken.stream().noneMatch(v->v.matches("(?s).*(?:정답은|따라 말해|빨리 말해|꼭 말해야|틀리면).*"));
    }
    private static boolean contains(String value,String part){return part!=null&&!ScenarioContentPolicy.normalize(part).isBlank()&&ScenarioContentPolicy.normalize(value).contains(ScenarioContentPolicy.normalize(part));}
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max;}
    private static boolean list(List<String> values){return values!=null&&values.size()<=3&&values.stream().allMatch(v->text(v,500));}
}
