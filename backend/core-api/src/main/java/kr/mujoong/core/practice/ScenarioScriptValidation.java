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
    public enum Reason {
        NONE, EMPTY_SCRIPT, BASE_LAYOUT, SCENE_KEY, FIELD_FORMAT, SPOKEN_FORMAT,
        PRIVATE_OR_FORBIDDEN_CONTENT, TARGET_EXPRESSION_DISCLOSED, PRESSURE_OR_ANSWER,
        GRAPH_BASE_MISMATCH, RELATION_MISMATCH, CONDITION_MISSING, PARTNER_LINE_MISSING,
        ALREADY_SEEN, UNCHANGED_SOURCE, VARIANT_STRUCTURE
    }
    public record Inspection(Result result,Reason reason) {}
    @FunctionalInterface public interface VariantCheck {
        boolean valid(Scene base,Scene variant);
        default boolean validBase(Scene base){return true;}
    }
    public record Rules(String relation,String opportunity,String targetExpression,Set<String> originalNames,
            String sourceText,Predicate<String> seen,VariantCheck variants) {
        public Rules {originalNames=Set.copyOf(originalNames);}
        @Override public String toString(){return "ScenarioRules[redacted]";}
    }
    private final ForbiddenExpressionPolicy forbidden;
    public ScenarioScriptValidation(ForbiddenExpressionPolicy forbidden){this.forbidden=forbidden;}
    public Result inspect(ScenarioScript script,Rules rules){
        return inspectDetailed(script,rules).result();
    }
    public Inspection inspectDetailed(ScenarioScript script,Rules rules){
        if(script==null||script.scenes().isEmpty())return invalid(Reason.EMPTY_SCRIPT);
        var scenes=script.scenes();var base=scenes.getFirst();
        if(!"BASE".equals(base.sceneKind())||scenes.stream().filter(s->"BASE".equals(s.sceneKind())).count()!=1)
            return invalid(Reason.BASE_LAYOUT);
        var keys=new HashSet<String>();
        if(scenes.stream().anyMatch(s->s.sceneKey()==null||!s.sceneKey().matches("[A-Za-z0-9_-]{1,40}")||!keys.add(s.sceneKey())))return invalid(Reason.SCENE_KEY);
        var unsafe=unsafeReason(base,rules);if(unsafe!=Reason.NONE)return invalid(unsafe);
        if(!rules.variants().validBase(base))return invalid(Reason.GRAPH_BASE_MISMATCH);
        if(!rules.relation().equals(base.relation()))return invalid(Reason.RELATION_MISMATCH);
        if(!contains(base.sceneDescription(),rules.opportunity()))return invalid(Reason.CONDITION_MISSING);
        if(!contains(base.sceneDescription(),base.opportunitySentence()))return invalid(Reason.PARTNER_LINE_MISSING);
        if(rules.seen().test(ScenarioContentPolicy.fingerprint(base.sceneDescription())))return invalid(Reason.ALREADY_SEEN);
        if(rules.sourceText()!=null&&ScenarioContentPolicy.normalize(base.sceneDescription()).equals(ScenarioContentPolicy.normalize(rules.sourceText())))return invalid(Reason.UNCHANGED_SOURCE);
        for(var scene:scenes.subList(1,scenes.size())){
            if(!"VARIANT".equals(scene.sceneKind()))return new Inspection(Result.INVALID_VARIANT,Reason.VARIANT_STRUCTURE);
            unsafe=unsafeReason(scene,rules);if(unsafe!=Reason.NONE)return new Inspection(Result.INVALID_VARIANT,unsafe);
            if(!rules.variants().valid(base,scene))return new Inspection(Result.INVALID_VARIANT,Reason.VARIANT_STRUCTURE);
        }
        return new Inspection(Result.VALID,Reason.NONE);
    }
    private static Inspection invalid(Reason reason){return new Inspection(Result.INVALID_BASE,reason);}
    /**
     * FT-1023-g: 선택 필드인 여는 말이 서버 검사(형식 · 금지 · 정답 · 압박)를 못 맞추면 대본 전체를 버리지 않고 그 필드만 뺀다(빼면 지금 흐름 그대로다).
     * 내용을 더하지 않고 빼기만 하므로 뺀 대본도 독립 검토와 나머지 서버 검사를 그대로 받는다. 바뀐 것이 없으면 같은 객체를 돌려준다.
     */
    public ScenarioScript withoutInvalidSceneStart(ScenarioScript script,Rules rules){
        if(script==null)return null;
        boolean changed=false;var scenes=new java.util.ArrayList<Scene>(script.scenes().size());
        for(var s:script.scenes()){
            if(s==null||s.openingLine()==null||openingAllowed(s,rules)){scenes.add(s);continue;}
            changed=true;
            scenes.add(new Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),s.focusPoints(),
                    s.expressionExamples(),s.relation(),s.place(),s.topic(),s.preservation(),s.partnerRole(),s.spokenIntroduction(),null));
        }
        return changed?new ScenarioScript(scenes):script;
    }
    private boolean openingAllowed(Scene s,Rules rules){
        String v=s.openingLine();
        return spokenSafe(v,200,rules);
    }
    /** 들려줄 선택 필드 하나의 형식 · 금지 · 이름 · 연락처 · 정답 · 압박 검사(unsafeReason과 같은 기준). */
    private boolean spokenSafe(String v,int max,Rules rules){
        if(!text(v,max)||v.chars().anyMatch(Character::isISOControl))return false;
        if(!forbidden.isAllowed(v)||rules.originalNames().stream().anyMatch(v::contains)
                ||v.matches("(?s).*(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}).*"))return false;
        String target=rules.targetExpression();
        if(target!=null&&!ScenarioContentPolicy.normalize(target).isBlank()&&contains(v,target))return false;
        return !v.matches("(?s).*(?:정답은|따라 말해|빨리 말해|꼭 말해야|틀리면).*");
    }
    private Reason unsafeReason(Scene s,Rules rules){
        if(!text(s.sceneDescription(),8000)||!text(s.opportunitySentence(),2000)||!text(s.reexplanation(),2000)
                ||!text(s.relation(),200)||!text(s.place(),200)||!text(s.topic(),200)
                ||!list(s.focusPoints())||!list(s.expressionExamples()))return Reason.FIELD_FORMAT;
        var spoken=new java.util.ArrayList<>(List.of(s.sceneDescription(),s.opportunitySentence(),s.reexplanation()));
        if(s.spokenIntroduction()!=null) {
            if(!text(s.spokenIntroduction(),500)||s.spokenIntroduction().chars().anyMatch(Character::isISOControl)
                    ||s.spokenIntroduction().matches("(?s).*(?:하고 있다|라고 말한다|다를 때)[.!?。\\s]*$"))return Reason.SPOKEN_FORMAT;
            spoken.add(s.spokenIntroduction());
        }
        var all=new java.util.ArrayList<>(spoken);all.addAll(s.focusPoints());all.addAll(s.expressionExamples());
        if(s.openingLine()!=null) {
            // 상대가 먼저 거는 첫 말. scenario-generation.v3부터 기회 문장과 같아도 된다(첫 말이 목표 기회를 바로 열 수 있다)
            if(!text(s.openingLine(),200)||s.openingLine().chars().anyMatch(Character::isISOControl))return Reason.SPOKEN_FORMAT;
            spoken.add(s.openingLine());all.add(s.openingLine());
        }
        all.addAll(List.of(s.relation(),s.place(),s.topic()));
        if(s.preservation()!=null) {
            var p=s.preservation();
            if(!text(p.skillRef(),200)||!text(p.invariantSentence(),2000)||!text(p.preservationRule(),2000)
                ||!text(p.transferDistance(),200)||!text(p.changedElement(),200)||!text(p.participants(),200)||!text(p.situation(),2000))return Reason.FIELD_FORMAT;
            all.addAll(List.of(p.skillRef(),p.invariantSentence(),p.preservationRule(),p.transferDistance(),p.changedElement(),p.participants(),p.situation()));
        }
        if(all.stream().anyMatch(v->!forbidden.isAllowed(v)||rules.originalNames().stream().anyMatch(v::contains)
                ||v.matches("(?s).*(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}).*")))return Reason.PRIVATE_OR_FORBIDDEN_CONTENT;
        String target=rules.targetExpression();
        if(target!=null&&!ScenarioContentPolicy.normalize(target).isBlank()
                &&spoken.stream().anyMatch(v->contains(v,target)))return Reason.TARGET_EXPRESSION_DISCLOSED;
        return spoken.stream().anyMatch(v->v.matches("(?s).*(?:정답은|따라 말해|빨리 말해|꼭 말해야|틀리면).*"))?Reason.PRESSURE_OR_ANSWER:Reason.NONE;
    }
    private static boolean contains(String value,String part){return part!=null&&!ScenarioContentPolicy.normalize(part).isBlank()&&ScenarioContentPolicy.normalize(value).contains(ScenarioContentPolicy.normalize(part));}
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max;}
    private static boolean list(List<String> values){return values!=null&&values.size()<=3&&values.stream().allMatch(v->text(v,500));}
}
