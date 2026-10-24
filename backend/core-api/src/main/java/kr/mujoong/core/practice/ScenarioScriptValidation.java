package kr.mujoong.core.practice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.ScenarioScript.Scene;

/**
 * BR2: 별도 검토의 APPROVE와 독립적인 결정적 검사. 의미 품질을 보증한다고 주장하지 않는다.
 *
 * <p>연습 대화 C안(docs/practice-direct-ask.md 4.3)의 들리는 새 칸 다섯 개({@code opportunityRelay} · {@code askPrompt} ·
 * {@code reaskPrompt} · {@code helpSpeech} · {@code friendReaction})는 있을 때만 검사한다(옛 대본 v1에는 없다). 이 칸은 GPT-Live가 글자 그대로
 * 읽고 공백만 뺀 원문 대조로 확인하므로 문장부호는 마침표 · 쉼표 · 물음표만 받는다. 기존 칸의 규칙은 바꾸지 않았다.
 */
public final class ScenarioScriptValidation {
    public enum Result { VALID, INVALID_BASE, INVALID_VARIANT }
    public enum Reason {
        NONE, EMPTY_SCRIPT, BASE_LAYOUT, SCENE_KEY, FIELD_FORMAT, SPOKEN_FORMAT, REFLECTION_NOT_ALLOWED,
        PRIVATE_OR_FORBIDDEN_CONTENT, TARGET_EXPRESSION_DISCLOSED, PRESSURE_OR_ANSWER,
        GRAPH_BASE_MISMATCH, RELATION_MISMATCH, CONDITION_MISSING, PARTNER_LINE_MISSING,
        ALREADY_SEEN, UNCHANGED_SOURCE, VARIANT_STRUCTURE,
        /** C안 들리는 칸에 마침표 · 쉼표 · 물음표 밖의 부호 · 기호가 있다. */
        SPOKEN_PUNCTUATION,
        /** C안 들리는 칸에 역할 말('역할' · '코치' · '무중이가 친구야' 등)이 있다. */
        ROLE_TALK,
        /** {@code opportunityRelay}가 상대의 직접 대사({@code opportunitySentence})와 글자가 같다. */
        RELAY_COPIES_PARTNER_LINE,
        /** {@code askPrompt}가 '너라면'으로 시작하고 물음표로 끝나는 한 문장이 아니다. */
        ASK_PROMPT_FORM,
        /** {@code helpSpeech}가 고정 꼬리 {@value ScenarioScriptValidation#HELP_TAIL}로 끝나지 않거나 꼬리 앞 문장이 없다. */
        HELP_TAIL_MISSING,
        /** {@code friendReaction}에 칭찬 · 평가어가 있다. */
        REACTION_PRAISE,
        /** {@code friendReaction}이 물음으로 끝난다. */
        REACTION_QUESTION
    }
    public record Inspection(Result result,Reason reason) {}
    @FunctionalInterface public interface VariantCheck {
        boolean valid(Scene base,Scene variant);
        default boolean validBase(Scene base){return true;}
    }
    public record Rules(String relation,String opportunity,String targetExpression,Set<String> originalNames,
            String sourceText,Predicate<String> seen,VariantCheck variants,boolean reflectionAllowed) {
        public Rules(String relation,String opportunity,String targetExpression,Set<String> originalNames,
                String sourceText,Predicate<String> seen,VariantCheck variants) {
            this(relation,opportunity,targetExpression,originalNames,sourceText,seen,variants,false);
        }
        public Rules {originalNames=Set.copyOf(originalNames);}
        @Override public String toString(){return "ScenarioRules[redacted]";}
    }

    /** {@code helpSpeech}의 고정 꼬리(공백은 대조하지 않는다, 원문 대조와 같은 규칙). */
    public static final String HELP_TAIL = "네 말로 해 볼래?";
    /** {@code askPrompt}의 고정 머리. */
    public static final String ASK_LEAD = "너라면";

    private static final Pattern PRESSURE = Pattern.compile("(?s).*(?:정답은|따라 말해|빨리 말해|꼭 말해야|틀리면).*");
    private static final Pattern CONTACT =
            Pattern.compile("(?s).*(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}).*");
    /** 공백을 뺀 글에서 찾는다. 무중이 다른 사람 역할을 맡는다는 말(설계 4.3 '역할 말 금지'). */
    private static final Pattern ROLE_TALK = Pattern.compile("역할|코치|무중이?(?:가|는)?"
            + "(?:친구|상대방?|선생님|엄마|아빠|형|오빠|누나|언니|동생|짝꿍)"
            + "(?:야|이야|예요|이에요|이다|라고|처럼|인척|할게|해볼게|맡을게|가될게|이될게)");
    /** 공백을 뺀 글에서 찾는다. 친구 반응에 쓰지 않는 칭찬 · 평가어(설계 4.3). */
    private static final Pattern PRAISE =
            Pattern.compile("잘했|잘한|잘하네|잘말했|멋지|멋져|멋진|멋있|정답|훌륭|최고|대단|똑똑|완벽|칭찬|맞았|맞혔");

    /** C안 들리는 칸 하나와 그 칸의 상한(길이는 계약 스키마 maxLength, 문장 수는 설계 4.3). */
    private record Spoken(String value, int maxLength, int maxSentences) {}

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

    private Reason unsafeReason(Scene s, Rules rules) {
        if (!text(s.sceneDescription(), 8000) || !text(s.opportunitySentence(), 2000) || !text(s.reexplanation(), 2000)
                || !text(s.relation(), 200) || !text(s.place(), 200) || !text(s.topic(), 200)
                || !list(s.focusPoints()) || !list(s.expressionExamples())) {
            return Reason.FIELD_FORMAT;
        }
        var spoken = new ArrayList<>(List.of(s.sceneDescription(), s.opportunitySentence(), s.reexplanation()));
        if (s.spokenIntroduction() != null) {
            if (!text(s.spokenIntroduction(), 500) || controlled(s.spokenIntroduction())
                    || s.spokenIntroduction().matches("(?s).*(?:하고 있다|라고 말한다|다를 때)[.!?。\\s]*$")) {
                return Reason.SPOKEN_FORMAT;
            }
            spoken.add(s.spokenIntroduction());
        }
        var all = new ArrayList<>(spoken);
        all.addAll(s.focusPoints());
        all.addAll(s.expressionExamples());
        if (s.reflectionPrompt() != null) {
            if (!rules.reflectionAllowed() || !"BASE".equals(s.sceneKind())) return Reason.REFLECTION_NOT_ALLOWED;
            if (!text(s.reflectionPrompt(), 300) || controlled(s.reflectionPrompt())) return Reason.SPOKEN_FORMAT;
            spoken.add(s.reflectionPrompt());
            all.add(s.reflectionPrompt());
        }
        all.addAll(List.of(s.relation(), s.place(), s.topic()));
        if (s.preservation() != null) {
            var p = s.preservation();
            if (!text(p.skillRef(), 200) || !text(p.invariantSentence(), 2000) || !text(p.preservationRule(), 2000)
                    || !text(p.transferDistance(), 200) || !text(p.changedElement(), 200) || !text(p.participants(), 200)
                    || !text(p.situation(), 2000)) {
                return Reason.FIELD_FORMAT;
            }
            all.addAll(List.of(p.skillRef(), p.invariantSentence(), p.preservationRule(), p.transferDistance(),
                    p.changedElement(), p.participants(), p.situation()));
        }
        // C안 들리는 칸: 모양 검사 뒤 내용 검사(금지 · 개인정보 · 목표 표현 · 압박)에 더한다
        var directAsk = directAskReason(s);
        if (directAsk != Reason.NONE) return directAsk;
        var disclosure = new ArrayList<>(spoken);
        var pressure = new ArrayList<>(spoken);
        for (String value : nonNull(s.opportunityRelay(), s.askPrompt(), s.reaskPrompt())) {
            disclosure.add(value);
            pressure.add(value);
            all.add(value);
        }
        // 도움 말은 표현 예시를 담는 칸이라 목표 표현 누설은 보지 않고 압박만 본다(설계 4.3). 친구 반응은 목표를 이룬 뒤에 들린다
        for (String value : nonNull(s.helpSpeech(), s.friendReaction())) {
            pressure.add(value);
            all.add(value);
        }
        if (all.stream().anyMatch(v -> !forbidden.isAllowed(v) || rules.originalNames().stream().anyMatch(v::contains)
                || CONTACT.matcher(v).matches())) {
            return Reason.PRIVATE_OR_FORBIDDEN_CONTENT;
        }
        String target = rules.targetExpression();
        if (target != null && !ScenarioContentPolicy.normalize(target).isBlank()
                && disclosure.stream().anyMatch(v -> contains(v, target))) {
            return Reason.TARGET_EXPRESSION_DISCLOSED;
        }
        return pressure.stream().anyMatch(v -> PRESSURE.matcher(v).matches()) ? Reason.PRESSURE_OR_ANSWER : Reason.NONE;
    }

    /** C안 들리는 칸의 모양(설계 4.3). 없는 칸은 보지 않는다. */
    private static Reason directAskReason(Scene s) {
        List<Spoken> fields = new ArrayList<>();
        if (s.opportunityRelay() != null) fields.add(new Spoken(s.opportunityRelay(), 200, 2));
        if (s.askPrompt() != null) fields.add(new Spoken(s.askPrompt(), 100, 1));
        if (s.reaskPrompt() != null) fields.add(new Spoken(s.reaskPrompt(), 200, 2));
        if (s.helpSpeech() != null) fields.add(new Spoken(s.helpSpeech(), 300, 3));
        if (s.friendReaction() != null) fields.add(new Spoken(s.friendReaction(), 200, 2));
        for (Spoken field : fields) {
            String value = field.value();
            if (!text(value, field.maxLength()) || controlled(value)) return Reason.SPOKEN_FORMAT;
            if (!plainPunctuation(value)) return Reason.SPOKEN_PUNCTUATION;
            if (sentences(value) > field.maxSentences()) return Reason.SPOKEN_FORMAT;
            if (ROLE_TALK.matcher(compact(value)).find()) return Reason.ROLE_TALK;
        }
        if (s.opportunityRelay() != null && ScenarioContentPolicy.normalize(s.opportunityRelay())
                .equals(ScenarioContentPolicy.normalize(s.opportunitySentence()))) {
            return Reason.RELAY_COPIES_PARTNER_LINE;
        }
        if (s.askPrompt() != null) {
            String ask = s.askPrompt().strip();
            if (!ask.startsWith(ASK_LEAD) || !ask.endsWith("?")) return Reason.ASK_PROMPT_FORM;
        }
        if (s.helpSpeech() != null && !endsWithHelpTail(s.helpSpeech())) return Reason.HELP_TAIL_MISSING;
        if (s.friendReaction() != null) {
            if (PRAISE.matcher(compact(s.friendReaction())).find()) return Reason.REACTION_PRAISE;
            if (s.friendReaction().strip().endsWith("?")) return Reason.REACTION_QUESTION;
        }
        return Reason.NONE;
    }

    /** 꼬리가 마지막 문장이고 그 앞에 문장(요점 · 예시)이 있다. 공백은 대조하지 않는다. */
    private static boolean endsWithHelpTail(String help) {
        String body = compact(help);
        String tail = compact(HELP_TAIL);
        if (!body.endsWith(tail)) return false;
        String head = body.substring(0, body.length() - tail.length());
        return !head.isEmpty() && (head.endsWith(".") || head.endsWith("?"));
    }

    /** 글자 · 숫자 · 공백(U+0020)과 마침표 · 쉼표 · 물음표만 있다. */
    private static boolean plainPunctuation(String value) {
        return value.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == ' ' || c == '.' || c == ',' || c == '?');
    }

    /** 마침표 · 물음표로 나눈 비지 않은 조각 수. */
    private static int sentences(String value) {
        int count = 0;
        for (String part : value.split("[.?]")) {
            if (!part.isBlank()) count++;
        }
        return count;
    }

    private static String compact(String value) {
        return value.replaceAll("\\s", "");
    }

    private static List<String> nonNull(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (value != null) present.add(value);
        }
        return present;
    }

    private static boolean controlled(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
    private static boolean contains(String value,String part){return part!=null&&!ScenarioContentPolicy.normalize(part).isBlank()&&ScenarioContentPolicy.normalize(value).contains(ScenarioContentPolicy.normalize(part));}
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max;}
    private static boolean list(List<String> values){return values!=null&&values.size()<=3&&values.stream().allMatch(v->text(v,500));}
}
