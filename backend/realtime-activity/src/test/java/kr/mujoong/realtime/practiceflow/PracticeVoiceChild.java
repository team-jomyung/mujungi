package kr.mujoong.realtime.practiceflow;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.practiceflow.FlowState.Phase;
import kr.mujoong.realtime.practiceflow.FlowState.Sub;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;

/**
 * 실음성 연습 하니스의 합성 아이(하늘이, 8살, 실제 아동 아님). 행동 계획 7가지(C안 5회차), 계획에 맞는 아이 말, 차례마다의 결정, 아이 말을 만드는
 * Luna(Responses API) · 소리로 바꾸는 TTS 보낼 곳을 둔다.
 *
 * <ul>
 *   <li>아이 말은 목표마다 Luna가 한 번에 만든다: 장면 안에서 아이가 들을 승인 문장(대본 칸)과 장면 설명 · 계획별 칸 뜻을 받아 칸마다 짧은 말을
 *       쓴다(합성). 실패하면 대본의 표현 예시 · 고정 문장으로 대신하고 그 사실을 적는다.
 *   <li>참여 · 도움 수락 · 변형 수락 / 거절 · 여는 말 대답(유연 대화) · 그만하기는 고정 문장이다.
 *   <li>대화가 시작되기 전에 계획이 쓸 말을 모두 TTS로 미리 만든다(4회차 H4). 아이가 말할 때 TTS를 기다리지 않는다.
 * </ul>
 */
final class PracticeVoiceChild {

    /** 아이 행동 계획(C안 5회차). */
    enum Plan {
        DIRECT("바로 직접 말하기", "첫 질문에 장면 속 상대에게 바로 하는 말로 답한다"),
        RELAY("전하는 식", "첫 질문에 '친구한테 ~라고 말할래'처럼 무중에게 전하는 식으로 답한다(변형 장면도 같다)"),
        PARTIAL("일부만 → 되묻기 → 전달", "첫 질문에 핵심이 빠진 짧은 말을 하고, 되물으면 제대로 말한다"),
        HELP("모름 · 도움 요청 → 도움 → 자기 말", "첫 질문에 모르겠다며 도움을 청하고, 도움을 받으면 자기 말로 말한다"),
        CONFUSED("상황 모름 → 다시 설명 → 답", "첫 질문에 무슨 말인지 모르겠다고 하고, 다시 설명을 들으면 답한다"),
        SILENT("무응답 → 다시 묻기 → 답", "첫 질문에 한 번 말하지 않고 무응답 시계를 기다리다가, 다시 물으면 답한다"),
        STOP("그만하기", "첫 질문에 '" + "무중아 그만할래" + "'라고 말한다");

        final String label;
        final String description;

        Plan(String label, String description) {
            this.label = label;
            this.description = description;
        }

        /** 변형 장면 제안을 받아들이는 계획(직접 · 전하는 식만, 나머지는 거절해 대화를 짧게 둔다). */
        boolean acceptsVariant() {
            return this == DIRECT || this == RELAY;
        }

        static List<Plan> parse(String value) {
            List<Plan> plans = new ArrayList<>();
            if (value != null) {
                for (String part : value.split(",")) {
                    String name = part.strip().toUpperCase(Locale.ROOT);
                    for (Plan plan : values()) {
                        if (plan.name().equals(name) && !plans.contains(plan)) {
                            plans.add(plan);
                        }
                    }
                }
            }
            return plans.isEmpty() ? List.of(values()) : List.copyOf(plans);
        }
    }

    /** 장면마다 Luna가 만드는 아이 말 칸. */
    enum Line {
        DIRECT("direct", "장면 속 상대에게 바로 하는 말(목표 행동을 해낸 말). 대본의 표현 예시가 있으면 뜻은 같게, 말은 조금 바꾼다"),
        RELAY("relay", "상대에게 할 말을 무중이에게 전하는 식으로 하는 말(예: 친구한테 ~라고 말할래, ~라고 할 거야)"),
        PARTIAL("partial", "하고 싶은 말의 일부만 짧게 하는 말. 목표 행동의 핵심이 빠져 있어야 한다"),
        HELP_REQUEST("helpRequest", "뭐라고 해야 할지 몰라 무중이에게 도움을 청하는 말"),
        CONFUSED("confused", "무슨 상황인지 잘 모르겠다고 하는 말"),
        OWN_WORDS("ownWords", "무중이의 도움 말을 들은 뒤 상대에게 자기 말로 다시 하는 말(도움 말의 예시와 뜻은 같고 말은 다르게)");

        final String field;
        final String meaning;

        Line(String field, String meaning) {
            this.field = field;
            this.meaning = meaning;
        }
    }

    // ---- 고정 문장(아이 쪽) ----
    static final String JOIN = "응, 할래.";
    static final String HELP_ACCEPT = "응, 도와줘.";
    static final String VARIANT_ACCEPT = "응, 해 볼래.";
    static final String VARIANT_DECLINE = "아니, 안 할래.";
    /** 유연 대화의 여는 말(상대가 먼저 거는 말, 판정 없음)에 하는 짧은 대답(FT-1023-g). C안은 여는 말을 읽지 않는다. */
    static final String OPENING_REPLY = "응, 좋아.";
    static final String STOP_LINE = "무중아 그만할래";
    static final String STOP_CONFIRM_YES = "응, 그만할래.";
    static final String KEEP_GOING = "아니, 더 할래.";
    static final String FALLBACK_HELP_REQUEST = "몰라. 뭐라고 해야 돼?";
    static final String FALLBACK_CONFUSED = "무슨 말이야? 잘 모르겠어.";
    static final String FALLBACK_DIRECT = "알겠어. 그럼 같이 하자.";

    /** 합성 아동 프로필(실제 아동 아님). 판정 입력에서는 실행부가 별칭으로 바꾼다. */
    static final String CHILD_NAME = "하늘";
    static final int CHILD_AGE = 8;
    static final List<String> CHILD_INTERESTS = List.of("공룡", "그림");
    /** 세션 종료 표현(시작 패키지 기본값과 같다). */
    static final List<String> STOP_EXPRESSIONS = List.of(STOP_LINE);
    /** 한 대화에서 아이가 말하는 차례 상한(그 뒤는 하니스가 끝내기를 요청한다). */
    static final int MAX_CHILD_TURNS = 16;

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final int MAX_LINE_CHARS = 80;
    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    private PracticeVoiceChild() {
    }

    // ------------------------------------------------------------------ 아이 말 묶음

    /** 장면 하나의 아이 말(칸 → 글, 칸 → 출처 LUNA · FALLBACK). */
    record SceneLines(Map<Line, String> lines, Map<Line, String> sources) {

        String get(Line line) {
            return lines.get(line);
        }

        Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>();
            for (Line line : Line.values()) {
                report.put(line.field, PracticeVoiceReport.ordered("text", lines.get(line), "source", sources.get(line)));
            }
            return report;
        }
    }

    /** 목표 하나의 아이 말(원 장면 · 변형 장면)과 만든 호출의 사실. */
    record GoalLines(SceneLines base, SceneLines variant, String source, Map<String, Object> call) {

        SceneLines scene(SceneKind kind) {
            return kind == SceneKind.VARIANT && variant != null ? variant : base;
        }

        Map<String, Object> report() {
            return PracticeVoiceReport.ordered("source", source, "call", call, "base", base.report(), "variant",
                    variant == null ? null : variant.report());
        }
    }

    /** 아이 차례 하나의 결정. */
    record Act(String kind, String key, String text, String rule) {

        static final String SPEAK = "SPEAK";
        static final String SILENT = "SILENT";
        static final String NONE = "NONE";

        static Act speak(String key, String text, String rule) {
            return new Act(SPEAK, key, text, rule);
        }

        static Act silent(String rule) {
            return new Act(SILENT, null, null, rule);
        }

        static Act none(String rule) {
            return new Act(NONE, null, null, rule);
        }
    }

    /**
     * 차례 결정에 쓰는 상황.
     *
     * @param lastLine 바로 앞에 아이가 들은 무중 말의 출처(대본 칸 · 고정 code · 승인 음성)
     * @param answersInPhase 이 장면 · 이 단계에서 아이가 이미 말한 횟수
     * @param silentUsed 이 장면에서 무응답 계획의 침묵을 이미 썼는가
     * @param lastAnswer 아이가 바로 앞에 한 말(되물을 때 다시 말한다)
     */
    record Cue(Phase phase, Sub sub, SceneKind sceneKind, String lastLine, int answersInPhase, boolean silentUsed, String lastAnswer) {
    }

    /** 흐름이 아이 말을 기다릴 때 계획대로 할 일을 고른다. */
    static Act decide(Plan plan, Cue cue, GoalLines lines) {
        SceneLines scene = lines.scene(cue.sceneKind());
        boolean variant = cue.sceneKind() == SceneKind.VARIANT;
        if (cue.phase() == Phase.OPPORTUNITY && cue.sub() == Sub.OPENING) {
            return Act.speak("OPENING_REPLY", OPENING_REPLY, "여는 말(유연 대화) → 고정 짧은 대답");
        }
        if (cue.lastLine() != null && cue.lastLine().endsWith(DirectAskLines.INPUT_CONFIRM) && cue.lastAnswer() != null
                && cue.phase().awaitsInput()) {
            return Act.speak("REPEAT", cue.lastAnswer(), "확인 질문(INPUT_CONFIRM) → 앞의 말을 다시");
        }
        switch (cue.phase()) {
            case OPPORTUNITY -> {
                if (cue.answersInPhase() > 0) {
                    return plan == Plan.RELAY && variant
                            ? Act.speak("RELAY", scene.get(Line.RELAY), "변형 장면 다시 묻기 → 전하는 식")
                            : Act.speak("DIRECT", scene.get(Line.DIRECT), "다시 묻기 · 다시 설명 뒤 → 직접 말");
                }
                return switch (plan) {
                    case DIRECT -> Act.speak("DIRECT", scene.get(Line.DIRECT), "직접 말하기 계획 → 직접 말");
                    case RELAY -> Act.speak("RELAY", scene.get(Line.RELAY), "전하는 식 계획 → 전하는 말");
                    case PARTIAL -> variant ? Act.speak("DIRECT", scene.get(Line.DIRECT), "변형 장면 → 직접 말")
                            : Act.speak("PARTIAL", scene.get(Line.PARTIAL), "일부만 계획 → 일부만");
                    case HELP -> variant ? Act.speak("DIRECT", scene.get(Line.DIRECT), "변형 장면 → 직접 말")
                            : Act.speak("HELP_REQUEST", scene.get(Line.HELP_REQUEST), "도움 요청 계획 → 모름 · 도움 요청");
                    case CONFUSED -> variant ? Act.speak("DIRECT", scene.get(Line.DIRECT), "변형 장면 → 직접 말")
                            : Act.speak("CONFUSED", scene.get(Line.CONFUSED), "상황 모름 계획 → 무슨 말인지 모름");
                    case SILENT -> !cue.silentUsed() && !variant ? Act.silent("무응답 계획 → 한 번 말하지 않고 무응답 시계를 기다림")
                            : Act.speak("DIRECT", scene.get(Line.DIRECT), "다시 물은 뒤 → 직접 말");
                    case STOP -> Act.speak("STOP", STOP_LINE, "그만하기 계획 → 종료 표현");
                };
            }
            case REASK -> {
                return Act.speak("DIRECT", scene.get(Line.DIRECT), "되묻기 → 직접 말");
            }
            case HELP_OFFER -> {
                return Act.speak("HELP_ACCEPT", HELP_ACCEPT, "도움 제안 → 고정 수락");
            }
            case RETRY -> {
                return Act.speak("OWN_WORDS", scene.get(Line.OWN_WORDS), "도움 뒤 다시 해 보기 → 자기 말");
            }
            case VARIANT_OFFER -> {
                return plan.acceptsVariant() ? Act.speak("VARIANT_ACCEPT", VARIANT_ACCEPT, "변형 제안 → 고정 수락")
                        : Act.speak("VARIANT_DECLINE", VARIANT_DECLINE, "변형 제안 → 고정 거절");
            }
            default -> {
                return Act.none("아이 말을 기다리는 단계가 아님(" + cue.phase() + ")");
            }
        }
    }

    /** 대화 전에 미리 합성할 말(계획이 쓸 수 있는 말 전부, 앞 대화와 같은 글은 다시 만들지 않는다). */
    static List<String> linesToPrepare(Plan plan, GoalLines lines, boolean hasVariant) {
        Set<String> texts = new LinkedHashSet<>();
        texts.add(JOIN);
        SceneLines base = lines.base();
        switch (plan) {
            case DIRECT -> texts.add(base.get(Line.DIRECT));
            case RELAY -> {
                texts.add(base.get(Line.RELAY));
                texts.add(base.get(Line.DIRECT));
            }
            case PARTIAL -> {
                texts.add(base.get(Line.PARTIAL));
                texts.add(base.get(Line.DIRECT));
            }
            case HELP -> {
                texts.add(base.get(Line.HELP_REQUEST));
                texts.add(HELP_ACCEPT);
                texts.add(base.get(Line.OWN_WORDS));
            }
            case CONFUSED -> {
                texts.add(base.get(Line.CONFUSED));
                texts.add(base.get(Line.DIRECT));
            }
            case SILENT -> texts.add(base.get(Line.DIRECT));
            case STOP -> texts.add(STOP_LINE);
        }
        if (plan != Plan.STOP) {
            // 판정이 계획과 다르게 갈라져도 쓰는 말(되묻기 뒤 직접 말 · 도움 수락 · 자기 말 · 여는 말 대답 · 변형 답)
            texts.add(base.get(Line.DIRECT));
            texts.add(HELP_ACCEPT);
            texts.add(base.get(Line.OWN_WORDS));
            texts.add(OPENING_REPLY);
            texts.add(plan.acceptsVariant() ? VARIANT_ACCEPT : VARIANT_DECLINE);
            if (hasVariant && plan.acceptsVariant() && lines.variant() != null) {
                texts.add(lines.variant().get(plan == Plan.RELAY ? Line.RELAY : Line.DIRECT));
                texts.add(lines.variant().get(Line.DIRECT));
            }
        }
        texts.removeIf(text -> text == null || text.isBlank());
        return List.copyOf(texts);
    }

    // ------------------------------------------------------------------ Luna로 아이 말 만들기

    /** 아이 말을 쓰게 하는 지시(합성 인물, 원문은 하니스 출력에만 남는다). */
    static final String CHILD_INSTRUCTIONS = String.join("\n",
            "너는 말하기 연습 앱을 시험하려고 만든 합성 아이 '하늘이'(8살, 공룡과 그림을 좋아함)의 대사를 쓴다. 실제 아이가 아니다.",
            "무중이(AI 친구)가 아이에게 장면을 들려주고 묻는다. 아래 장면 자료를 보고 칸마다 하늘이가 소리 내어 할 말만 쓴다.",
            "규칙:",
            "- 8살 아이의 짧은 반말 입말로 쓴다. 한 칸은 한두 문장이고 40자 안쪽이다.",
            "- 따옴표, 이름표, 괄호, 설명, 이모지를 쓰지 않는다. 문장부호는 마침표, 쉼표, 물음표만 쓴다.",
            "- '그만', '끝내' 같은 말과 '무중아 그만할래'는 어느 칸에도 쓰지 않는다.",
            "- 무중이가 들려준 문장을 그대로 따라 말하지 않는다. 장면에 맞게 아이 자기 말로 한다.",
            "칸 뜻:",
            lineMeanings());

    private static String lineMeanings() {
        List<String> lines = new ArrayList<>();
        for (Line line : Line.values()) {
            lines.add("- " + line.field + ": " + line.meaning);
        }
        return String.join("\n", lines);
    }

    /** Responses API 구조화 출력 스키마(장면 칸 6개, 변형 장면이 있으면 그 칸도). */
    static Map<String, Object> schema(boolean variant) {
        Map<String, Object> lineProperties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Line line : Line.values()) {
            lineProperties.put(line.field, Map.of("type", "string"));
            required.add(line.field);
        }
        Map<String, Object> scene = PracticeVoiceReport.ordered("type", "object", "additionalProperties", false, "required", required,
                "properties", lineProperties);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("base", scene);
        if (variant) {
            properties.put("variant", scene);
        }
        return PracticeVoiceReport.ordered("type", "object", "additionalProperties", false, "required",
                variant ? List.of("base", "variant") : List.of("base"), "properties", properties);
    }

    /** 아이 말을 만들 때 넘기는 장면 자료(목표 · 장면 설명 · 아이가 들을 승인 문장). */
    static String input(String goalName, String criteria, PracticeScript script, Map<String, String> approvedSpeech) {
        DirectAskLines lines = new DirectAskLines(approvedSpeech);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goal", PracticeVoiceReport.ordered("displayName", goalName, "judgmentCriteria", criteria));
        data.put("base", sceneInput(lines, script.baseScene()));
        script.variantScene().ifPresent(scene -> data.put("variant", sceneInput(lines, scene)));
        return JSON.write(data);
    }

    private static Map<String, Object> sceneInput(DirectAskLines lines, Scene scene) {
        Map<String, Object> heard = new LinkedHashMap<>();
        heard.put("introduction", lines.introduction(scene));
        heard.put("relayAndAsk", lines.opportunity(scene));
        heard.put("reask", lines.reask(scene));
        heard.put("reexplanationAndAsk", lines.reexplanation(scene));
        heard.put("help", lines.help(scene).text());
        heard.put("friendReaction", lines.friendReaction(scene));
        return PracticeVoiceReport.ordered("partnerRole", scene.partnerRole(), "sceneDescription", scene.sceneDescription(),
                "expressionExamples", scene.expressionExamples(), "heardFromMujoong", heard);
    }

    /** Luna 응답(JSON 글)을 칸별 말로 바꾼다. 빠지거나 규칙에 어긋난 칸은 대체 문장이다. */
    @SuppressWarnings("unchecked")
    static GoalLines parse(String text, PracticeScript script, Map<String, Object> call) {
        Map<String, Object> root;
        try {
            root = JSON.read(text, Map.class);
        } catch (RuntimeException notJson) {
            root = null;
        }
        if (root == null) {
            return fallback(script, "LUNA_OUTPUT_NOT_JSON", call);
        }
        Map<String, Object> base = root.get("base") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Map<String, Object> variant = root.get("variant") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        SceneLines baseLines = scene(base, script.baseScene());
        SceneLines variantLines = script.variantScene().map(scene -> scene(variant, scene)).orElse(null);
        boolean anyFallback = baseLines.sources().containsValue("FALLBACK")
                || (variantLines != null && variantLines.sources().containsValue("FALLBACK"));
        return new GoalLines(baseLines, variantLines, anyFallback ? "LUNA_WITH_FALLBACK" : "LUNA", call);
    }

    private static SceneLines scene(Map<String, Object> generated, Scene scene) {
        Map<Line, String> fallback = fallbackLines(scene);
        Map<Line, String> lines = new EnumMap<>(Line.class);
        Map<Line, String> sources = new EnumMap<>(Line.class);
        for (Line line : Line.values()) {
            String cleaned = generated.get(line.field) instanceof String raw ? clean(raw) : null;
            if (cleaned == null) {
                lines.put(line, fallback.get(line));
                sources.put(line, "FALLBACK");
            } else {
                lines.put(line, cleaned);
                sources.put(line, "LUNA");
            }
        }
        return new SceneLines(Map.copyOf(lines), Map.copyOf(sources));
    }

    /** Luna를 부르지 못했거나 응답을 못 쓸 때의 아이 말(대본의 표현 예시 · 고정 문장). */
    static GoalLines fallback(PracticeScript script, String reason, Map<String, Object> call) {
        Map<String, Object> facts = new LinkedHashMap<>(call == null ? Map.of() : call);
        facts.put("fallbackReason", reason);
        SceneLines base = fallbackScene(script.baseScene());
        SceneLines variant = script.variantScene().map(PracticeVoiceChild::fallbackScene).orElse(null);
        return new GoalLines(base, variant, "FALLBACK", facts);
    }

    private static SceneLines fallbackScene(Scene scene) {
        Map<Line, String> lines = fallbackLines(scene);
        Map<Line, String> sources = new EnumMap<>(Line.class);
        for (Line line : Line.values()) {
            sources.put(line, "FALLBACK");
        }
        return new SceneLines(Map.copyOf(lines), Map.copyOf(sources));
    }

    private static Map<Line, String> fallbackLines(Scene scene) {
        String direct = scene.expressionExamples().stream().map(DirectAskLines::plain).map(PracticeVoiceChild::clean)
                .filter(text -> text != null && !text.isBlank()).findFirst().orElse(FALLBACK_DIRECT);
        String partner = scene.relation() != null && !scene.relation().isBlank() ? scene.relation().strip() : "친구";
        String body = direct.replaceAll("[.?!,\\s]+$", "");
        Map<Line, String> lines = new EnumMap<>(Line.class);
        lines.put(Line.DIRECT, direct);
        lines.put(Line.RELAY, partner + "한테 " + body + "라고 말할래.");
        int cut = body.indexOf(' ');
        lines.put(Line.PARTIAL, "음, " + (cut > 0 ? body.substring(0, cut) : body) + ".");
        lines.put(Line.HELP_REQUEST, FALLBACK_HELP_REQUEST);
        lines.put(Line.CONFUSED, FALLBACK_CONFUSED);
        lines.put(Line.OWN_WORDS, direct);
        return lines;
    }

    /** 아이 한 줄 정리: 따옴표 · 괄호 · 이름표를 덜고 한 줄로, 상한 안으로. 종료 표현이 들었거나 비면 null(대체 문장을 쓴다). */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[\"'“”‘’()\\[\\]{}「」『』<>]", "")
                .replaceAll("^\\s*(하늘이|하늘|아이)\\s*[:：]\\s*", "").replaceAll("\\s+", " ").strip();
        if (text.isEmpty() || text.replace(" ", "").contains(STOP_LINE.replace(" ", ""))) {
            return null;
        }
        if (text.codePointCount(0, text.length()) > MAX_LINE_CHARS) {
            int end = text.offsetByCodePoints(0, MAX_LINE_CHARS);
            int sentence = Math.max(text.lastIndexOf('.', end), Math.max(text.lastIndexOf('?', end), text.lastIndexOf('!', end)));
            text = (sentence > 0 ? text.substring(0, sentence + 1) : text.substring(0, end)).strip();
        }
        return text.isEmpty() ? null : text;
    }

    // ------------------------------------------------------------------ 보낼 곳(실호출 · 점검이 다르다)

    /** 아이 말을 쓰는 텍스트 모델 입구. */
    interface LineWriter extends AutoCloseable {

        /** 구조화 출력 한 번(스키마가 null이면 JSON 객체 출력). */
        Reply write(String instructions, String input, Map<String, Object> schema);

        String model();

        String modelSource();

        @Override
        default void close() {
        }
    }

    /** 텍스트 모델 응답 하나(글은 하니스 출력에만, 오류는 짧은 코드만). */
    record Reply(String outcome, String model, String text, long latencyMs, Map<String, Long> usage, String errorCode) {

        boolean ok() {
            return "OK".equals(outcome);
        }
    }

    /** TTS 입구. */
    interface Speech extends AutoCloseable {

        Synth synthesize(String text);

        String model();

        String voice();

        boolean sendsInstructions();

        @Override
        default void close() {
        }
    }

    /** TTS 결과 하나(pcm = 24kHz 16비트 모노 LE). */
    record Synth(String outcome, byte[] pcm, long latencyMs) {

        boolean ok() {
            return "OK".equals(outcome);
        }
    }

    /** TTS 목소리 지시(지시를 받는 모델에만 보낸다, 합성 인물 하늘이). */
    static final String TTS_INSTRUCTIONS = "8살 한국 아이가 말하듯이 밝고 또박또박, 조금 천천히 말한다. 글을 읽는 말투가 아니다.";

    /** 목소리 지시를 받는 TTS 모델인가(tts-1 계열은 받지 않는다). */
    static boolean sendsInstructions(String model) {
        return model != null && !model.toLowerCase(Locale.ROOT).startsWith("tts-");
    }

    /**
     * OpenAI TTS({@code POST {base}/audio/speech}, {@code response_format: pcm} = 24kHz 16비트 모노). 주소는 Luna와 같은 규칙(실호출 STRICT)으로
     * 확인하고, 키는 보낼 때마다 읽고 남기지 않는다. 기본 프록시 선택기를 따른다(GPT-Live 연결과 같다).
     */
    static final class OpenAiSpeech implements Speech {

        private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
        private final URI uri;
        private final Supplier<String> key;
        private final String model;
        private final String voice;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient http;

        OpenAiSpeech(URI baseUrl, LunaHosts.Mode mode, Supplier<String> key, String model, String voice) {
            this.uri = join(baseUrl, "audio/speech");
            Optional<LunaHosts.Problem> problem = LunaHosts.check(uri, mode);
            if (problem.isPresent()) {
                throw new IllegalStateException("TTS 주소가 주소 규칙을 통과하지 못했다: " + problem.get());
            }
            if (!MODEL_NAME.matcher(model).matches() || !MODEL_NAME.matcher(voice).matches()) {
                throw new IllegalStateException("TTS 모델 · 목소리 이름 형식이 올바르지 않다");
            }
            this.key = key;
            this.model = model;
            this.voice = voice;
            this.http = client(executor, CONNECT_TIMEOUT);
        }

        @Override
        public Synth synthesize(String text) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("voice", voice);
            body.put("input", text);
            body.put("response_format", "pcm");
            if (PracticeVoiceChild.sendsInstructions(model)) {
                body.put("instructions", TTS_INSTRUCTIONS);
            }
            String apiKey = key.get();
            if (apiKey == null || apiKey.isBlank()) {
                return new Synth("NO_KEY", null, 0);
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.write(body), StandardCharsets.UTF_8)).build();
            long started = System.nanoTime();
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                long latency = (System.nanoTime() - started) / 1_000_000L;
                int status = response.statusCode();
                if (status == 200) {
                    byte[] pcm = response.body();
                    if (pcm.length < 2) {
                        return new Synth("EMPTY_AUDIO", null, latency);
                    }
                    return new Synth("OK", pcm.length % 2 == 0 ? pcm : java.util.Arrays.copyOf(pcm, pcm.length - 1), latency);
                }
                return new Synth(statusOutcome(status), null, latency);
            } catch (IOException failure) {
                return new Synth("TRANSPORT_ERROR", null, (System.nanoTime() - started) / 1_000_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Synth("INTERRUPTED", null, (System.nanoTime() - started) / 1_000_000L);
            }
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public String voice() {
            return voice;
        }

        @Override
        public boolean sendsInstructions() {
            return PracticeVoiceChild.sendsInstructions(model);
        }

        @Override
        public void close() {
            http.close();
            executor.shutdownNow();
        }
    }

    /**
     * 아이 말을 쓰는 Luna(Responses API {@code POST {base}/responses}, 구조화 출력). 운영 Luna 호출부(LunaTransport)는 Decisions API만 보내므로
     * 하니스가 따로 보낸다. 주소 규칙은 Luna와 같고(실호출 STRICT), 키는 보낼 때마다 읽는다. 저장하지 않게 {@code store: false}로 보낸다.
     */
    static final class ResponsesWriter implements LineWriter {

        private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
        private static final int MAX_OUTPUT_TOKENS = 4000;
        private final URI uri;
        private final Supplier<String> key;
        private final String model;
        private final String modelSource;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient http;

        ResponsesWriter(URI baseUrl, LunaHosts.Mode mode, Supplier<String> key, String model, String modelSource) {
            this.uri = join(baseUrl, "responses");
            Optional<LunaHosts.Problem> problem = LunaHosts.check(uri, mode);
            if (problem.isPresent()) {
                throw new IllegalStateException("아이 말 모델 주소가 주소 규칙을 통과하지 못했다: " + problem.get());
            }
            if (!MODEL_NAME.matcher(model).matches()) {
                throw new IllegalStateException("아이 말 모델 이름 형식이 올바르지 않다");
            }
            this.key = key;
            this.model = model;
            this.modelSource = modelSource;
            this.http = client(executor, CONNECT_TIMEOUT);
        }

        @Override
        public Reply write(String instructions, String input, Map<String, Object> schema) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("instructions", instructions);
            body.put("input", List.of(PracticeVoiceReport.ordered("role", "user", "content", input)));
            body.put("max_output_tokens", MAX_OUTPUT_TOKENS);
            body.put("store", false);
            body.put("text", Map.of("format", schema == null ? Map.of("type", "json_object")
                    : PracticeVoiceReport.ordered("type", "json_schema", "name", "child_lines", "strict", true, "schema", schema)));
            String apiKey = key.get();
            if (apiKey == null || apiKey.isBlank()) {
                return new Reply("NO_KEY", null, null, 0, null, null);
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.write(body), StandardCharsets.UTF_8)).build();
            long started = System.nanoTime();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return classify(response.statusCode(), response.body(), (System.nanoTime() - started) / 1_000_000L);
            } catch (IOException failure) {
                return new Reply("TRANSPORT_ERROR", null, null, (System.nanoTime() - started) / 1_000_000L, null, null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Reply("INTERRUPTED", null, null, (System.nanoTime() - started) / 1_000_000L, null, null);
            }
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public String modelSource() {
            return modelSource;
        }

        @Override
        public void close() {
            http.close();
            executor.shutdownNow();
        }
    }

    /** Responses API 응답 분류(본문 글 · 거절 · 길이 초과 · 상태 코드). 오류 문장은 남기지 않고 짧은 코드만 남긴다. */
    @SuppressWarnings("unchecked")
    static Reply classify(int status, String body, long latency) {
        Map<String, Object> root;
        try {
            root = JSON.read(body, Map.class);
        } catch (RuntimeException notJson) {
            root = null;
        }
        Map<String, Long> usage = usageOf(root);
        if (status < 200 || status >= 300) {
            return new Reply(statusOutcome(status), null, null, latency, usage, errorCode(root));
        }
        if (root == null) {
            return new Reply("SERVER_ERROR", null, null, latency, usage, null);
        }
        StringBuilder text = new StringBuilder();
        boolean refused = false;
        if (root.get("output") instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> message) || !"message".equals(message.get("type"))
                        || !(message.get("content") instanceof List<?> contents)) {
                    continue;
                }
                for (Object content : contents) {
                    if (content instanceof Map<?, ?> part) {
                        if ("refusal".equals(part.get("type"))) {
                            refused = true;
                        } else if ("output_text".equals(part.get("type")) && part.get("text") instanceof String piece) {
                            text.append(piece);
                        }
                    }
                }
            }
        }
        String model = root.get("model") instanceof String m && SAFE_TOKEN.matcher(m).matches() ? m : null;
        Object replyStatus = root.get("status");
        if ("incomplete".equals(replyStatus)) {
            return new Reply("LENGTH_EXCEEDED", model, null, latency, usage, null);
        }
        if (refused) {
            return new Reply("REFUSED", model, null, latency, usage, null);
        }
        if (!"completed".equals(replyStatus)) {
            return new Reply("SERVER_ERROR", model, null, latency, usage, null);
        }
        if (text.toString().isBlank()) {
            return new Reply("EMPTY_OUTPUT", model, null, latency, usage, null);
        }
        return new Reply("OK", model, text.toString().strip(), latency, usage, null);
    }

    static String statusOutcome(int status) {
        return status == 401 || status == 403 ? "AUTH_FAILED" : status == 429 ? "RATE_LIMITED"
                : status >= 500 ? "SERVER_ERROR" : "REQUEST_REJECTED_" + status;
    }

    /** 오류 본문의 {@code error.code} · {@code error.type}(짧은 토큰만, 메시지 문장은 남기지 않는다). */
    private static String errorCode(Map<String, Object> root) {
        if (root == null || !(root.get("error") instanceof Map<?, ?> error)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String name : List.of("code", "type")) {
            if (error.get(name) instanceof String token && SAFE_TOKEN.matcher(token).matches()) {
                parts.add(name + "=" + token);
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** 응답 본문 {@code usage}의 숫자 필드(한 단계 안 세부 항목은 {@code 바깥.안}). 없으면 null. */
    static Map<String, Long> usageOf(Map<String, Object> root) {
        if (root == null || !(root.get("usage") instanceof Map<?, ?> usage)) {
            return null;
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : usage.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!SAFE_TOKEN.matcher(name).matches()) {
                continue;
            }
            if (entry.getValue() instanceof Number number) {
                numbers.put(name, number.longValue());
            } else if (entry.getValue() instanceof Map<?, ?> details) {
                for (Map.Entry<?, ?> detail : details.entrySet()) {
                    if (detail.getValue() instanceof Number number && SAFE_TOKEN.matcher(String.valueOf(detail.getKey())).matches()) {
                        numbers.put(name + "." + detail.getKey(), number.longValue());
                    }
                }
            }
        }
        return numbers;
    }

    private static URI join(URI baseUrl, String path) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/" + path);
    }

    private static HttpClient client(ExecutorService executor, Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().executor(executor).connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder.build();
    }
}
