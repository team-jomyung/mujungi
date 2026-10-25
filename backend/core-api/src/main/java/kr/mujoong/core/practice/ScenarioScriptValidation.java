package kr.mujoong.core.practice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.ScenarioScript.Scene;

/**
 * BR2: 별도 검토의 APPROVE와 독립적인 결정적 검사. 의미 품질을 보증한다고 주장하지 않는다.
 *
 * <p>검사는 두 갈래다(docs/practice-direct-ask.md 4.4 · 계약 ai-io.md 10.17).
 * <ul>
 *   <li><b>장면 판정</b>({@link #inspectDetailed}): 장면 구성과 필수 칸(설명 · 기회 문장 · 다시 설명 · 요점 · 표현 예시 · 관계 · 장소 · 주제 · 보존 ·
 *       돌아보기 질문)을 main과 같은 규칙으로 본다. 걸리면 그 장면이 INVALID_BASE · INVALID_VARIANT가 되어 보완 흐름을 탄다. 들리는 선택 칸은 보지 않는다.
 *   <li><b>칸별 판정</b>({@link #optionalSpokenIssues}): 아이에게 들리는 선택 칸(상황 안내 {@code spokenIntroduction}과 C안 칸 다섯 개
 *       {@code opportunityRelay} · {@code askPrompt} · {@code reaskPrompt} · {@code helpSpeech} · {@code friendReaction})을 칸마다 본다. 생성기는
 *       걸린 칸만 발급 때 빼고({@link #withoutFields}) 장면은 발급한다. realtime이 뺀 칸 자리에 대체 문장을 읽는다. 없는 칸(옛 대본 v1)은 보지 않는다.
 * </ul>
 *
 * <p>C안 칸은 GPT-Live가 글자 그대로 읽고 공백만 뺀 원문 대조로 확인하므로 문장부호는 마침표 · 쉼표 · 물음표만 받는다.
 */
public final class ScenarioScriptValidation {
    public enum Result { VALID, INVALID_BASE, INVALID_VARIANT }
    public enum Reason {
        NONE, EMPTY_SCRIPT, BASE_LAYOUT, SCENE_KEY, FIELD_FORMAT, SPOKEN_FORMAT, REFLECTION_NOT_ALLOWED,
        PRIVATE_OR_FORBIDDEN_CONTENT, TARGET_EXPRESSION_DISCLOSED, PRESSURE_OR_ANSWER,
        GRAPH_BASE_MISMATCH, RELATION_MISMATCH, CONDITION_MISSING, PARTNER_LINE_MISSING,
        ALREADY_SEEN, UNCHANGED_SOURCE, VARIANT_STRUCTURE,
        /** (칸별) C안 칸에 마침표 · 쉼표 · 물음표 밖의 부호 · 기호가 있다. */
        SPOKEN_PUNCTUATION,
        /** (칸별) C안 칸에 역할 말('역할' · '코치' · '무중이가 친구야' 등)이 있다. */
        ROLE_TALK,
        /** (칸별) {@code opportunityRelay}가 상대의 직접 대사({@code opportunitySentence})와 글자가 같다. */
        RELAY_COPIES_PARTNER_LINE,
        /** (칸별) {@code askPrompt}가 '너라면'으로 시작하고 물음표로 끝나는 한 문장이 아니다. */
        ASK_PROMPT_FORM,
        /** (칸별) {@code helpSpeech}가 고정 꼬리 {@value ScenarioScriptValidation#HELP_TAIL}로 끝나지 않거나 꼬리 앞 문장이 없다. */
        HELP_TAIL_MISSING,
        /** (칸별) {@code friendReaction}에 칭찬 · 평가어가 있다. */
        REACTION_PRAISE,
        /** (칸별) {@code friendReaction}이 물음으로 끝난다. */
        REACTION_QUESTION,
        /** (칸별) {@code opportunityRelay}가 상대 말의 낱말을 충분히 살리지 않는다({@link #RELAY_BIGRAM_RATIO_MIN} 미만). */
        RELAY_LOW_FIDELITY,
        /** (칸별) {@code opportunityRelay} · {@code reaskPrompt}가 상대 말에 없는 속뜻 · 의도 · 이유 · 감정 짐작 말투를 쓴다. */
        PARTNER_INTERPRETED,
        /** (칸별) {@code askPrompt}의 '선생님' 호칭이 {@code relation}과 맞지 않는다. */
        ASK_PROMPT_RELATION
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

    /** 칸별로 판정하는 들리는 선택 칸의 계약 이름. 진단 코드 {@code ScenarioField_<칸>_<사유>}의 칸 자리에도 쓴다. */
    public static final String SPOKEN_INTRODUCTION = "spokenIntroduction";
    public static final String OPPORTUNITY_RELAY = "opportunityRelay";
    public static final String ASK_PROMPT = "askPrompt";
    public static final String REASK_PROMPT = "reaskPrompt";
    public static final String HELP_SPEECH = "helpSpeech";
    public static final String FRIEND_REACTION = "friendReaction";
    /** 칸별 판정 칸(계약 순서). */
    public static final List<String> OPTIONAL_SPOKEN_FIELDS =
            List.of(SPOKEN_INTRODUCTION, OPPORTUNITY_RELAY, ASK_PROMPT, REASK_PROMPT, HELP_SPEECH, FRIEND_REACTION);
    /** 칸별 판정이 낼 수 있는 사유(진단 코드 {@code ScenarioField_<칸>_<사유>}의 사유 자리). */
    public static final Set<Reason> FIELD_REASONS = Collections.unmodifiableSet(java.util.EnumSet.of(Reason.SPOKEN_FORMAT,
            Reason.SPOKEN_PUNCTUATION, Reason.ROLE_TALK, Reason.PRIVATE_OR_FORBIDDEN_CONTENT, Reason.TARGET_EXPRESSION_DISCLOSED,
            Reason.PRESSURE_OR_ANSWER, Reason.RELAY_COPIES_PARTNER_LINE, Reason.RELAY_LOW_FIDELITY, Reason.PARTNER_INTERPRETED,
            Reason.ASK_PROMPT_FORM, Reason.ASK_PROMPT_RELATION, Reason.HELP_TAIL_MISSING, Reason.REACTION_PRAISE,
            Reason.REACTION_QUESTION));

    /** {@code helpSpeech}의 고정 꼬리(공백은 대조하지 않는다, 원문 대조와 같은 규칙). */
    public static final String HELP_TAIL = "네 말로 해 볼래?";
    /** {@code askPrompt}의 고정 머리. */
    public static final String ASK_LEAD = "너라면";
    /** 선생님 장면의 호칭({@code askPrompt} 관계 검사). */
    public static final String TEACHER_TITLE = "선생님";
    /**
     * 전하기 충실도 문턱. 공백 · 문장부호를 뺀 상대 말({@code opportunitySentence})의 서로 다른 두 글자 조각(바이그램) 가운데 전하기에도 있는 조각의 비율이
     * 이 값보다 작으면 전하기를 뺀다. 상대 말 쪽을 분모로 해서 앞에 장면 맥락 문장이 붙어도 비율이 떨어지지 않는다.
     */
    public static final double RELAY_BIGRAM_RATIO_MIN = 0.5;
    /**
     * 상대의 속뜻 · 의도 · 이유 · 감정을 짐작하는 말투 하나. {@code pattern}은 무중이 전하는 말에서, {@code ownWord}는 상대 말에서 공백을 뺀 글로 찾는다.
     * 상대 말에 같은 낱말(줄기)이 있으면 상대가 직접 한 말을 전한 것이라 걸지 않는다(상대가 "어려울 것 같아"라고 했으면 "어려울 것 같대"는 통과).
     */
    record Interpretive(String mark, Pattern pattern, Pattern ownWord) {
        static Interpretive of(String mark, String pattern, String ownWord) {
            return new Interpretive(mark, Pattern.compile(pattern), Pattern.compile(ownWord));
        }
    }
    /**
     * 짐작 말투 목록(설계 13장 · 계약 10.17). '[하-햏]'은 '하'의 줄어든 꼴(한 · 할 · 해 · 했 …)까지 받는다('궁금해한대' · '싶어 한대'). 이 밖에 짐작 꼴
     * '-나 봐' · '-ㄴ가 봐'('모르나 봐' · '그런가 봐')는 {@link #conjectures}가 따로 찾는다.
     */
    static final List<Interpretive> INTERPRETIVE = List.of(
            Interpretive.of("것같", "(?:것|거)같(?!이)", "(?:것|거)같(?!이)"),
            Interpretive.of("것처럼", "(?:것|거)처럼", "(?:것|거)처럼"),
            Interpretive.of("고싶은것", "고싶은(?:것|거)", "싶"),
            Interpretive.of("고싶어서", "고싶어서", "싶"),
            Interpretive.of("싶어하", "싶어[하-햏]", "싶"),
            Interpretive.of("려는", "려는", "려(?:는|고|면|다|던)"),
            Interpretive.of("려고", "려고", "려(?:는|고|면|다|던)"),
            Interpretive.of("궁금해서", "궁금해서", "궁금"),
            Interpretive.of("궁금해하", "궁금해[하-햏]", "궁금"),
            Interpretive.of("싫어서", "싫어서", "싫"),
            Interpretive.of("싫어하", "싫어[하-햏]", "싫"),
            Interpretive.of("사실은", "사실은", "사실"),
            Interpretive.of("속마음", "속마음", "마음"),
            Interpretive.of("기분이", "기분이", "기분"),
            Interpretive.of("속상해서", "속상해서", "속상"),
            Interpretive.of("서운해서", "서운해서", "서운"),
            Interpretive.of("몰라서", "몰라서", "몰라|모르"),
            Interpretive.of("까봐", "까봐", "까봐"));

    private static final Pattern PRESSURE = Pattern.compile("(?s).*(?:정답은|따라 말해|빨리 말해|꼭 말해야|틀리면).*");
    private static final Pattern CONTACT =
            Pattern.compile("(?s).*(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}).*");
    /** 상황 안내의 문어체 · 조건 꼬리(main 규칙 그대로). */
    private static final Pattern INTRODUCTION_TAIL = Pattern.compile("(?s).*(?:하고 있다|라고 말한다|다를 때)[.!?。\\s]*$");
    /** 공백을 뺀 글에서 찾는다. 무중이 다른 사람 역할을 맡는다는 말(설계 4.3 '역할 말 금지'). */
    private static final Pattern ROLE_TALK = Pattern.compile("역할|코치|무중이?(?:가|는)?"
            + "(?:친구|상대방?|선생님|엄마|아빠|형|오빠|누나|언니|동생|짝꿍)"
            + "(?:야|이야|예요|이에요|이다|라고|처럼|인척|할게|해볼게|맡을게|가될게|이될게)");
    /** 공백을 뺀 글에서 찾는다. 친구 반응에 쓰지 않는 칭찬 · 평가어(설계 4.3). */
    private static final Pattern PRAISE =
            Pattern.compile("잘했|잘한|잘하네|잘말했|멋지|멋져|멋진|멋있|정답|훌륭|최고|대단|똑똑|완벽|칭찬|맞았|맞혔");
    /** 짐작 꼴 '-나 봐' · '-ㄴ가 봐'의 뒷부분(공백 하나를 허용한다). 어간은 바로 앞 글자다({@link #conjectures}). */
    private static final Pattern CONJECTURE = Pattern.compile("(나|가) ?(?:봐|보)");
    /** '-나다' 동사에 '-아 보다'가 붙은 꼴('만나 봐' · '일어나 봐' 등)의 끝 두 글자. 짐작 '-나 봐'로 보지 않는다. */
    private static final Set<String> NADA_VERB_ENDINGS =
            Set.of("만나", "떠나", "지나", "끝나", "어나", "아나", "타나", "라나", "러나", "켜나", "겨나", "져나", "쳐나");
    private static final String NA_BWA_MARK = "나봐";
    private static final String NGA_BWA_MARK = "ㄴ가봐";
    /** 한글 음절의 받침 번호(유니코드 한글 음절 배열): ㄴ. */
    private static final int JONG_NIEUN = 4;

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

    /** 장면 판정의 칸 검사(main 규칙 그대로). 들리는 선택 칸은 {@link #optionalSpokenIssues}가 칸마다 본다. */
    private Reason unsafeReason(Scene s, Rules rules) {
        if (!text(s.sceneDescription(), 8000) || !text(s.opportunitySentence(), 2000) || !text(s.reexplanation(), 2000)
                || !text(s.relation(), 200) || !text(s.place(), 200) || !text(s.topic(), 200)
                || !list(s.focusPoints()) || !list(s.expressionExamples())) {
            return Reason.FIELD_FORMAT;
        }
        var spoken = new ArrayList<>(List.of(s.sceneDescription(), s.opportunitySentence(), s.reexplanation()));
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
        if (all.stream().anyMatch(v -> privateOrForbidden(v, rules))) return Reason.PRIVATE_OR_FORBIDDEN_CONTENT;
        if (spoken.stream().anyMatch(v -> discloses(v, rules.targetExpression()))) return Reason.TARGET_EXPRESSION_DISCLOSED;
        return spoken.stream().anyMatch(v -> PRESSURE.matcher(v).matches()) ? Reason.PRESSURE_OR_ANSWER : Reason.NONE;
    }

    // ---- 칸별 판정: 들리는 선택 칸 ----

    /**
     * 들리는 선택 칸을 칸마다 판정해 걸린 칸만 칸 이름 → 사유로 돌려준다(계약 순서, 걸린 칸이 없으면 빈 지도). 값이 없는 칸은 보지 않는다. 사유는 칸마다 처음
     * 걸린 하나이고 순서는 모양(빈 값 · 길이 · 제어 문자 · 문장부호 · 문장 수 · 역할 말) → 내용(금지 표현 · 원래 이름 · 연락처 → 목표 표현 누설 → 압박) →
     * 칸의 뜻(상대 대사 복사 · 해석 말투 · 충실도 · 묻기 모양 · 관계 · 도움 꼬리 · 칭찬 · 물음 끝)이다. 목표 표현 누설은 도움 · 상대 반응 칸에서 보지 않는다
     * (도움은 표현 예시를 담고, 상대 반응은 목표를 이룬 뒤에 들린다). 장면 판정과 따로 돌고 장면 결과를 바꾸지 않는다.
     */
    public Map<String, Reason> optionalSpokenIssues(Scene scene, Rules rules) {
        Map<String, Reason> issues = new LinkedHashMap<>();
        note(issues, SPOKEN_INTRODUCTION, introductionReason(scene.spokenIntroduction(), rules));
        note(issues, OPPORTUNITY_RELAY, relayReason(scene, rules));
        note(issues, ASK_PROMPT, askReason(scene, rules));
        note(issues, REASK_PROMPT, reaskReason(scene, rules));
        note(issues, HELP_SPEECH, helpReason(scene.helpSpeech(), rules));
        note(issues, FRIEND_REACTION, reactionReason(scene.friendReaction(), rules));
        return Collections.unmodifiableMap(issues);
    }

    /** 이름({@link #optionalSpokenIssues}의 키)이 가리키는 칸을 뺀(null) 장면 사본. 다른 칸은 그대로다. */
    public static Scene withoutFields(Scene scene, Set<String> fields) {
        if (fields.isEmpty()) return scene;
        return scene.withSpokenIntroduction(keep(scene.spokenIntroduction(), SPOKEN_INTRODUCTION, fields))
                .withDirectAsk(keep(scene.opportunityRelay(), OPPORTUNITY_RELAY, fields), keep(scene.askPrompt(), ASK_PROMPT, fields),
                        keep(scene.reaskPrompt(), REASK_PROMPT, fields), keep(scene.helpSpeech(), HELP_SPEECH, fields),
                        keep(scene.friendReaction(), FRIEND_REACTION, fields));
    }

    /** 상황 안내: 모양은 main 규칙 그대로(빈 값 · 500자 · 제어 문자 · 문어체 · 조건 꼬리), 내용은 금지 · 누설 · 압박. */
    private Reason introductionReason(String value, Rules rules) {
        if (value == null) return Reason.NONE;
        if (!text(value, 500) || controlled(value) || INTRODUCTION_TAIL.matcher(value).matches()) return Reason.SPOKEN_FORMAT;
        return content(value, rules, true);
    }

    /** 전하기: 모양 · 내용 뒤 상대 대사 복사 → 해석 말투 → 충실도. */
    private Reason relayReason(Scene s, Rules rules) {
        String value = s.opportunityRelay();
        if (value == null) return Reason.NONE;
        Reason reason = shapeThenContent(value, 200, 2, rules, true);
        if (reason != Reason.NONE) return reason;
        String partner = s.opportunitySentence() == null ? "" : s.opportunitySentence();
        if (ScenarioContentPolicy.normalize(value).equals(ScenarioContentPolicy.normalize(partner))) return Reason.RELAY_COPIES_PARTNER_LINE;
        if (interprets(partner, value)) return Reason.PARTNER_INTERPRETED;
        return relayFidelity(partner, value) < RELAY_BIGRAM_RATIO_MIN ? Reason.RELAY_LOW_FIDELITY : Reason.NONE;
    }

    /** 묻기: 모양 · 내용 뒤 '너라면 …?' 한 문장 → 관계(선생님 장면이면 '선생님'이 있어야 하고, 아니면 없어야 한다). */
    private Reason askReason(Scene s, Rules rules) {
        String value = s.askPrompt();
        if (value == null) return Reason.NONE;
        Reason reason = shapeThenContent(value, 100, 1, rules, true);
        if (reason != Reason.NONE) return reason;
        String ask = value.strip();
        if (!ask.startsWith(ASK_LEAD) || !ask.endsWith("?")) return Reason.ASK_PROMPT_FORM;
        return teacherRelation(s.relation()) == compact(ask).contains(TEACHER_TITLE) ? Reason.NONE : Reason.ASK_PROMPT_RELATION;
    }

    /** 되묻기: 모양 · 내용 뒤 해석 말투(충실도는 보지 않는다). */
    private Reason reaskReason(Scene s, Rules rules) {
        String value = s.reaskPrompt();
        if (value == null) return Reason.NONE;
        Reason reason = shapeThenContent(value, 200, 2, rules, true);
        if (reason != Reason.NONE) return reason;
        return interprets(s.opportunitySentence(), value) ? Reason.PARTNER_INTERPRETED : Reason.NONE;
    }

    /** 도움: 모양 · 내용(누설 제외) 뒤 고정 꼬리. */
    private Reason helpReason(String value, Rules rules) {
        if (value == null) return Reason.NONE;
        Reason reason = shapeThenContent(value, 300, 3, rules, false);
        if (reason != Reason.NONE) return reason;
        return endsWithHelpTail(value) ? Reason.NONE : Reason.HELP_TAIL_MISSING;
    }

    /** 상대 반응: 모양 · 내용(누설 제외) 뒤 칭찬 · 물음 끝. */
    private Reason reactionReason(String value, Rules rules) {
        if (value == null) return Reason.NONE;
        Reason reason = shapeThenContent(value, 200, 2, rules, false);
        if (reason != Reason.NONE) return reason;
        if (PRAISE.matcher(compact(value)).find()) return Reason.REACTION_PRAISE;
        return value.strip().endsWith("?") ? Reason.REACTION_QUESTION : Reason.NONE;
    }

    /** C안 칸의 모양(길이 상한은 계약 스키마 maxLength, 문장 수는 설계 4.3) 뒤 내용. */
    private Reason shapeThenContent(String value, int maxLength, int maxSentences, Rules rules, boolean disclosure) {
        if (!text(value, maxLength) || controlled(value)) return Reason.SPOKEN_FORMAT;
        if (!plainPunctuation(value)) return Reason.SPOKEN_PUNCTUATION;
        if (sentences(value) > maxSentences) return Reason.SPOKEN_FORMAT;
        if (ROLE_TALK.matcher(compact(value)).find()) return Reason.ROLE_TALK;
        return content(value, rules, disclosure);
    }

    /** 들리는 칸 하나의 내용: 금지 표현 · 원래 이름 · 연락처 → 목표 표현 누설(disclosure일 때) → 압박 · 답 강요. */
    private Reason content(String value, Rules rules, boolean disclosure) {
        if (privateOrForbidden(value, rules)) return Reason.PRIVATE_OR_FORBIDDEN_CONTENT;
        if (disclosure && discloses(value, rules.targetExpression())) return Reason.TARGET_EXPRESSION_DISCLOSED;
        return PRESSURE.matcher(value).matches() ? Reason.PRESSURE_OR_ANSWER : Reason.NONE;
    }

    private boolean privateOrForbidden(String value, Rules rules) {
        return !forbidden.isAllowed(value) || rules.originalNames().stream().anyMatch(value::contains) || CONTACT.matcher(value).matches();
    }

    private static boolean discloses(String value, String target) {
        return target != null && !ScenarioContentPolicy.normalize(target).isBlank() && contains(value, target);
    }

    /**
     * 전하기 충실도: 공백 · 문장부호를 뺀 상대 말의 서로 다른 두 글자 조각 가운데 공백 · 문장부호를 뺀 전하기에도 있는 조각의 비율(0 ~ 1). 조각이 2개 미만인
     * 아주 짧은 말은 상대 말이 전하기에 통째로 들어 있으면 1, 아니면 0이다.
     */
    static double relayFidelity(String partnerLine, String relay) {
        String partner = ScenarioContentPolicy.normalize(partnerLine == null ? "" : partnerLine);
        String spoken = ScenarioContentPolicy.normalize(relay == null ? "" : relay);
        Set<String> pieces = bigrams(partner);
        if (pieces.size() < 2) return spoken.contains(partner) ? 1.0 : 0.0;
        long kept = pieces.stream().filter(spoken::contains).count();
        return (double) kept / pieces.size();
    }

    private static Set<String> bigrams(String text) {
        int[] points = text.codePoints().toArray();
        Set<String> pieces = new LinkedHashSet<>();
        for (int i = 0; i + 1 < points.length; i++) {
            pieces.add(new String(points, i, 2));
        }
        return pieces;
    }

    /**
     * 무중이 전하는 말(전하기 · 되묻기)에 상대가 하지 않은 짐작 말투가 있는가. 목록 말투는 상대 말에 같은 낱말(줄기)이 있으면, 짐작 꼴('-나 봐' · '-ㄴ가 봐')은
     * 상대 말에도 짐작 꼴이 있으면 걸지 않는다.
     */
    static boolean interprets(String partnerLine, String spoken) {
        String partner = partnerLine == null ? "" : partnerLine;
        String own = compact(partner);
        Set<String> partnerConjectures = new LinkedHashSet<>();
        conjectures(partner, partnerConjectures);
        for (String mark : interpretiveMarks(spoken)) {
            var listed = INTERPRETIVE.stream().filter(item -> item.mark().equals(mark)).findFirst();
            boolean said = listed.isPresent() ? listed.get().ownWord().matcher(own).find() : !partnerConjectures.isEmpty();
            if (!said) return true;
        }
        return false;
    }

    /** 글에 든 짐작 말투의 표지(목록 말투 이름 · 짐작 꼴 '나봐' · 'ㄴ가봐'). 상대 말과의 대조는 {@link #interprets}가 한다. */
    static Set<String> interpretiveMarks(String text) {
        Set<String> marks = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return marks;
        String compact = compact(text);
        for (Interpretive item : INTERPRETIVE) {
            if (item.pattern().matcher(compact).find()) marks.add(item.mark());
        }
        conjectures(text, marks);
        return marks;
    }

    /**
     * 짐작 꼴 '-나 봐'('했나 봐' · '좋아하나 봐' · '모르나 봐')와 '-ㄴ가 봐'('아픈가 봐' · '그런가 봐')를 찾는다. 어간 글자에 띄어 쓰지 않고 붙은 것만 보고
     * ('나 봐'의 '나'가 '나(자신)'인 말, '한번 가 봐'는 뺀다), '만나 봐' · '일어나 봐'처럼 '-나다' 동사에 '-아 보다'가 붙은 꼴도 뺀다.
     */
    private static void conjectures(String text, Set<String> marks) {
        String spaced = text.replaceAll("\\s+", " ");
        Matcher matcher = CONJECTURE.matcher(spaced);
        while (matcher.find()) {
            int at = matcher.start();
            if (at == 0) continue;
            char stem = spaced.charAt(at - 1);
            if (!hangulSyllable(stem)) continue;
            if ("나".equals(matcher.group(1))) {
                if (!NADA_VERB_ENDINGS.contains(stem + "나")) marks.add(NA_BWA_MARK);
            } else if (jongseong(stem) == JONG_NIEUN) {
                marks.add(NGA_BWA_MARK);
            }
        }
    }

    /** 선생님 장면인가: 관계 코드 {@code TEACHER}이거나 관계 이름에 '선생님' · '교사'가 들어 있다(예: '담임 선생님'). */
    static boolean teacherRelation(String relation) {
        if (relation == null) return false;
        String plain = compact(relation);
        return "TEACHER".equals(plain) || plain.contains(TEACHER_TITLE) || plain.contains("교사");
    }

    private static boolean hangulSyllable(char c) {
        return c >= '가' && c <= '힣';
    }

    private static int jongseong(char syllable) {
        return (syllable - '가') % 28;
    }

    private static String keep(String value, String field, Set<String> dropped) {
        return dropped.contains(field) ? null : value;
    }

    private static void note(Map<String, Reason> issues, String field, Reason reason) {
        if (reason != Reason.NONE) issues.put(field, reason);
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

    private static boolean controlled(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
    private static boolean contains(String value,String part){return part!=null&&!ScenarioContentPolicy.normalize(part).isBlank()&&ScenarioContentPolicy.normalize(value).contains(ScenarioContentPolicy.normalize(part));}
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max;}
    private static boolean list(List<String> values){return values!=null&&values.size()<=3&&values.stream().allMatch(v->text(v,500));}
}
