package kr.mujoong.core.practice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.ScenarioScript.Scene;

/**
 * BR2: 별도 검토의 APPROVE와 독립적인 결정적 검사. 의미 품질을 보증한다고 주장하지 않는다.
 *
 * <p>대본 형식 v6(docs/practice-dialogue-standard.md): 아이에게는 무중의 장면 설명 → 친구 첫 대사 → 친구 기회 문장 순서로 들린다. 그래서 설명이
 * {@code opportunityCondition} · 기회 문장을 글자 그대로 담으라는 요구는 두지 않는다(기회 조건이 장면에서 실제로 생기는지는 별도 검토가 의미로
 * 본다). 대신 BASE의 친구 첫 대사가 비어 있지 않고, 도움 동의 없이 들리는 필드(설명 · 첫 대사 · 기회 문장 · 다시 설명)가 길이 안이며 목표 표현을
 * 글자 그대로 담지 않는지를 본다. VARIANT의 첫 대사는 비울 수 있다(무중의 변형 제안 뒤 친구가 기회 문장으로 바로 시작한다).
 *
 * <p>대본 형식 v7(같은 문서 14절): VARIANT 기회 문장이 BASE 기회 문장과 같으면 변형을 뺀다(소재를 바꾼 새 상황이 아니다). 빈칸 예문
 * {@code expressionFrames}가 있으면 표현 예시와 1:1이고, 예문마다 '뭐뭐'가 있고 예시와 같지 않으며, 예시와 맞춰 볼 수 있으면 비운 자리가
 * 꾸밈말 하나뿐이 아니어야 한다. 맞춰 볼 수 없는 예문의 뜻은 검토가 본다.
 */
public final class ScenarioScriptValidation {
    public enum Result { VALID, INVALID_BASE, INVALID_VARIANT }

    @FunctionalInterface
    public interface VariantCheck {
        boolean valid(Scene base, Scene variant);

        default boolean validBase(Scene base) {
            return true;
        }
    }

    public record Rules(String relation, String opportunity, String targetExpression, Set<String> originalNames,
            String sourceText, Predicate<String> seen, VariantCheck variants) {
        public Rules {
            originalNames = Set.copyOf(originalNames);
        }

        @Override
        public String toString() {
            return "ScenarioRules[redacted]";
        }
    }

    /** 친구 첫 대사의 길이 상한(스키마 {@code partnerOpener.maxLength}와 같다). */
    static final int PARTNER_OPENER_MAX = 300;
    /** 빈칸 예문에서 아이가 채울 자리. */
    static final String BLANK = "뭐뭐";
    /**
     * 비우면 안 되는 꾸밈말 · 지시어 · 대명사 · 감탄사(정규화한 모양). 이것만 비운 빈칸 예문은 사실상 정답 문장이다(실측 s10 «…나서 뭐뭐 빌려줄 수 있어?»는
     * '잠깐'만 비웠다).
     */
    static final Set<String> TRIVIAL_BLANKS = Set.of("잠깐", "잠시", "좀", "조금", "그냥", "같이", "함께", "혹시", "지금", "먼저", "이제", "다시",
            "또", "꼭", "정말", "진짜", "너무", "빨리", "천천히", "많이", "한번", "한번만", "제발", "이거", "그거", "저거", "이것", "그것", "저것",
            "여기", "거기", "저기", "나", "너", "나도", "너도", "내가", "네가", "우리", "응", "아니", "어", "아", "음", "그래", "네", "야");
    /** '뭐뭐'(모음으로 끝남) 뒤 조사와 예시의 받침 뒤 조사 짝. 빈칸 뒤 말을 예시와 맞춰 볼 때 쓴다. */
    private static final Map<String, String> PARTICLE_AFTER_CONSONANT = Map.of("를", "을", "가", "이", "는", "은", "와", "과", "로", "으로",
            "랑", "이랑", "라고", "이라고", "야", "이야", "라서", "이라서");

    private final ForbiddenExpressionPolicy forbidden;

    public ScenarioScriptValidation(ForbiddenExpressionPolicy forbidden) {
        this.forbidden = forbidden;
    }

    public Result inspect(ScenarioScript script, Rules rules) {
        if (script == null || script.scenes().isEmpty()) {
            return Result.INVALID_BASE;
        }
        var scenes = script.scenes();
        var base = scenes.getFirst();
        if (!"BASE".equals(base.sceneKind()) || scenes.stream().filter(s -> "BASE".equals(s.sceneKind())).count() != 1) {
            return Result.INVALID_BASE;
        }
        var keys = new HashSet<String>();
        if (scenes.stream().anyMatch(s -> s.sceneKey() == null || !s.sceneKey().matches("[A-Za-z0-9_-]{1,40}") || !keys.add(s.sceneKey()))) {
            return Result.INVALID_BASE;
        }
        if (!text(base.partnerOpener(), PARTNER_OPENER_MAX) || !safe(base, rules) || !rules.variants().validBase(base)
                || !rules.relation().equals(base.relation())
                || rules.seen().test(ScenarioContentPolicy.fingerprint(base.sceneDescription()))
                || (rules.sourceText() != null
                        && ScenarioContentPolicy.normalize(base.sceneDescription()).equals(ScenarioContentPolicy.normalize(rules.sourceText())))) {
            return Result.INVALID_BASE;
        }
        for (var scene : scenes.subList(1, scenes.size())) {
            if (!"VARIANT".equals(scene.sceneKind()) || !safe(scene, rules) || sameText(scene.opportunitySentence(), base.opportunitySentence())
                    || !rules.variants().valid(base, scene)) {
                return Result.INVALID_VARIANT;
            }
        }
        return Result.VALID;
    }

    private boolean safe(Scene s, Rules rules) {
        if (!text(s.sceneDescription(), 8000) || !text(s.opportunitySentence(), 2000) || !text(s.reexplanation(), 2000)
                || !optionalText(s.partnerOpener(), PARTNER_OPENER_MAX)
                || !text(s.relation(), 200) || !text(s.place(), 200) || !text(s.topic(), 200)
                || !list(s.focusPoints()) || !list(s.expressionExamples()) || !frames(s.expressionExamples(), s.expressionFrames())) {
            return false;
        }
        var spoken = new ArrayList<>(List.of(s.sceneDescription(), s.opportunitySentence(), s.reexplanation()));
        if (s.partnerOpener() != null && !s.partnerOpener().isBlank()) {
            spoken.add(s.partnerOpener());
        }
        var all = new ArrayList<>(spoken);
        all.addAll(s.focusPoints());
        all.addAll(s.expressionExamples());
        if (s.expressionFrames() != null) {
            all.addAll(s.expressionFrames());
        }
        all.addAll(List.of(s.relation(), s.place(), s.topic()));
        if (s.preservation() != null) {
            var p = s.preservation();
            if (!text(p.skillRef(), 200) || !text(p.invariantSentence(), 2000) || !text(p.preservationRule(), 2000)
                    || !text(p.transferDistance(), 200) || !text(p.changedElement(), 200) || !text(p.participants(), 200)
                    || !text(p.situation(), 2000) || !text(p.relationship(), 200)) {
                return false;
            }
            all.addAll(List.of(p.skillRef(), p.invariantSentence(), p.preservationRule(), p.transferDistance(), p.changedElement(),
                    p.participants(), p.situation(), p.relationship()));
        }
        if (all.stream().anyMatch(v -> !forbidden.isAllowed(v) || rules.originalNames().stream().anyMatch(v::contains)
                || v.matches("(?s).*(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}).*"))) {
            return false;
        }
        String target = rules.targetExpression();
        if (target != null && !ScenarioContentPolicy.normalize(target).isBlank() && spoken.stream().anyMatch(v -> contains(v, target))) {
            return false;
        }
        return spoken.stream().noneMatch(v -> v.matches("(?s).*(?:정답은|따라 말해|빨리 말해|꼭 말해야|틀리면).*"));
    }

    /** 빈칸 예문 칸(생성 판 v7부터). 없으면(옛 모양) 보지 않는다. 있으면 표현 예시와 1:1이고 예문마다 {@link #frameValid}다. */
    private static boolean frames(List<String> examples, List<String> frames) {
        if (frames == null) {
            return true;
        }
        if (frames.size() != examples.size() || !list(frames)) {
            return false;
        }
        for (int i = 0; i < frames.size(); i++) {
            if (!frameValid(examples.get(i), frames.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 빈칸 예문 하나: '뭐뭐'가 있고, 예시와 같지 않고, 빈칸 밖의 말이 남는다(빈칸만 있는 예문은 틀이 아니다). 예시와 맞춰 볼 수 있으면(빈칸 밖의 말이
     * 예시에 순서대로 있다) 비운 자리 가운데 하나는 꾸밈말 · 지시어가 아닌 말이어야 한다. 맞춰 볼 수 없으면 결정적으로 판단하지 않는다(검토가 본다).
     */
    static boolean frameValid(String example, String frame) {
        String f = ScenarioContentPolicy.normalize(frame);
        String e = ScenarioContentPolicy.normalize(example);
        if (!f.contains(BLANK) || f.equals(e) || f.replace(BLANK, "").isEmpty()) {
            return false;
        }
        Optional<List<String>> removed = removedSpans(e, f);
        return removed.isEmpty() || removed.get().stream().anyMatch(span -> !span.isEmpty() && !TRIVIAL_BLANKS.contains(span));
    }

    /** 정규화한 예시에서 빈칸 예문의 '뭐뭐' 자리마다 빠진 말. 빈칸 밖의 말이 예시에 순서대로 없으면 빈 값이다. */
    static Optional<List<String>> removedSpans(String example, String frame) {
        String[] segments = frame.split(BLANK, -1);
        if (!example.startsWith(segments[0])) {
            return Optional.empty();
        }
        int position = segments[0].length();
        List<String> spans = new ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            boolean last = i == segments.length - 1;
            int[] match = find(example, position, segments[i], last);
            if (match == null) {
                return Optional.empty();
            }
            spans.add(example.substring(position, match[0]));
            position = match[0] + match[1];
        }
        return position == example.length() ? Optional.of(spans) : Optional.empty();
    }

    /** {@code from} 뒤에서 조각을 찾는다(빈칸 바로 뒤 조사는 받침 뒤 모양도 받는다). 끝 조각은 예시 끝에 붙어야 한다. 찾으면 {시작, 길이}. */
    private static int[] find(String example, int from, String segment, boolean last) {
        List<String> forms = new ArrayList<>(List.of(segment));
        PARTICLE_AFTER_CONSONANT.forEach((vowel, consonant) -> {
            if (segment.startsWith(vowel)) {
                forms.add(consonant + segment.substring(vowel.length()));
            }
        });
        for (String form : forms) {
            int at = last ? (example.endsWith(form) ? example.length() - form.length() : -1) : example.indexOf(form, from);
            if (at >= from) {
                return new int[] {at, form.length()};
            }
        }
        return null;
    }

    private static boolean sameText(String a, String b) {
        return a != null && b != null && ScenarioContentPolicy.normalize(a).equals(ScenarioContentPolicy.normalize(b));
    }

    private static boolean contains(String value, String part) {
        return part != null && !ScenarioContentPolicy.normalize(part).isBlank()
                && ScenarioContentPolicy.normalize(value).contains(ScenarioContentPolicy.normalize(part));
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    /** 비었거나(없음) 상한 안의 문장. VARIANT의 친구 첫 대사처럼 비울 수 있는 칸이다. */
    private static boolean optionalText(String value, int max) {
        return value == null || value.length() <= max;
    }

    private static boolean list(List<String> values) {
        return values != null && values.size() <= 3 && values.stream().allMatch(v -> text(v, 500));
    }
}
