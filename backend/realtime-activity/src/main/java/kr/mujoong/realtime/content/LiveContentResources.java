package kr.mujoong.realtime.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 판정 선택지 판 · 가드레일 정책 판 자원 (U8 functional-spec 8.3 · BR2.1 · NFR2.3 · SD5, contracts/ai-io.md 10.4절).
 *
 * <p>{@value #JUDGMENT_RESOURCE}(J1 ~ J6 질문 · 선택지, 연습이 C안이면 {@value #DIRECT_ASK_JUDGMENT_RESOURCE})와 {@value #GUARDRAIL_RESOURCE}(제브
 * 검사 질문 · 라벨 설명 · 범주 · 모드)를 기동 때 한 번 읽어 검증하고 이후 읽기 전용 값으로 쓴다(핫 리로드 없음, {@code gate.SeedDictionary}와 같은 방식). 하나라도 틀리면
 * {@link LoadException}을 던져 Realtime 기동 점검이 기동을 거부한다. 예외 메시지는 사유 열거값과 판정 지점 · 키 이름뿐이다(문구 원문 없음).
 * J7(연습 대본 사전 점검)은 core 상황 생성 자체 검토(U10) 프롬프트라 이 판에 없다.
 */
public final class LiveContentResources {

    /** 판정 선택지 판(classpath). 연습이 유연 대화 경로일 때 쓴다. */
    public static final String JUDGMENT_RESOURCE = "judgment/option-sets-service-v1.json";
    /**
     * C안 '무중이 직접 묻기'의 판정 선택지 판(classpath). J2 · J3 · J5 질문의 '장면 속 친구' · '코치'를 '무중의 질문' · '무중의 도움'으로 바꾼 판이고
     * 지점 · 코드는 v1과 같다(docs/practice-direct-ask.md 6장). 연습 경로가 C안(운영 기본)이면 앱이 이 판을 쓴다.
     */
    public static final String DIRECT_ASK_JUDGMENT_RESOURCE = "judgment/option-sets-service-v2.json";
    /** C안 판정 선택지 판의 판 번호. 판정 지시문은 이 판과 짝인 {@code prompt-service-v4}를 쓴다. */
    public static final String DIRECT_ASK_JUDGMENT_VERSION = "judgment-service-v2";
    /**
     * 가드레일 정책 판(classpath). 출력 단위별 욕설 검사에는 {@code policy-v3.json}을 읽는다.
     * {@code policy-v1.json} · {@code policy-v2.json}은 이력으로 보관한다.
     */
    public static final String GUARDRAIL_RESOURCE = "guardrail/policy-v3.json";
    /** 제브 선택지 중 '해당 없음' 라벨(정책 범주에 더해 criteria에 정확히 있어야 한다). */
    public static final String GUARDRAIL_PASS_LABEL = "PASS";

    /** 진행 중 판정 지점(J7은 core 몫). */
    public enum JudgmentPoint {
        J1, J2, J3, J4, J5, J6
    }

    /** 제브 검사 모드(C §3.1). */
    public enum GuardrailMode {
        FREE_TALK, PRACTICE_PEER, PRACTICE_COACH
    }

    /** 현재 v3 정책의 제브 범주 수(PROFANITY). */
    public static final int GUARDRAIL_CATEGORY_COUNT = 1;

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** 적재 거부 사유. */
    public enum Problem {
        MISSING,
        PARSE_FAILED,
        BAD_VERSION,
        BAD_JUDGMENT_POINTS,
        BAD_OPTIONS,
        BAD_INSTRUCTIONS,
        BAD_CRITERIA,
        BAD_CATEGORIES,
        BAD_MODES
    }

    /** 적재 거부. 메시지는 사유와 자원 · 지점 · 키 이름뿐이다(문구 · 프롬프트 원문 없음). */
    public static final class LoadException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final Problem problem;

        LoadException(Problem problem, String detail) {
            super("판 리소스 적재 거부: " + problem + (detail == null ? "" : " (" + detail + ")"));
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** 선택지 하나(B §5.2: 코드 · 선택지 · 정의, 판 안에서 순서 고정). */
    public record Option(String code, String label, String definition) {
    }

    /** 판정 지점 하나의 질문 · 선택지. */
    public record PointOptions(JudgmentPoint point, String question, List<Option> options) {

        public PointOptions {
            options = List.copyOf(options);
        }

        /** 선택지 코드(순서 그대로). */
        public List<String> optionCodes() {
            return options.stream().map(Option::code).toList();
        }
    }

    /** 판정 선택지 판. */
    public record JudgmentOptionSet(String version, String source, Map<JudgmentPoint, PointOptions> points) {

        public JudgmentOptionSet {
            points = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(points));
        }

        @Override
        public String toString() {
            return "JudgmentOptionSet[version=" + version + "]";
        }
    }

    /**
     * 가드레일 정책 판. {@code instructions}는 제브 {@code choice} 질문의 한국어 검사 질문이고 {@code criteria}는 '라벨 → 설명'(삽입 순서 유지, 정책 범주 +
     * {@value #GUARDRAIL_PASS_LABEL})이다. {@code categories}는 정책 판의 범주 이름이다(v3: PROFANITY).
     */
    public record GuardrailPolicy(String version, String source, String instructions, Map<String, String> criteria,
            List<String> categories, Set<GuardrailMode> modes) {

        public GuardrailPolicy {
            criteria = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
            categories = List.copyOf(categories);
            modes = Set.copyOf(modes);
        }

        @Override
        public String toString() {
            return "GuardrailPolicy[version=" + version + "]";
        }
    }

    record JudgmentFile(String version, String source, List<RawPoint> points) {
    }

    record RawPoint(String point, String question, List<RawOption> options) {
    }

    record RawOption(String code, String label, String definition) {
    }

    record GuardrailFile(String version, String source, String instructions, Map<String, String> criteria, List<String> categories,
            List<String> modes) {
    }

    private final JudgmentOptionSet judgment;
    private final GuardrailPolicy guardrail;

    private LiveContentResources(JudgmentOptionSet judgment, GuardrailPolicy guardrail) {
        this.judgment = judgment;
        this.guardrail = guardrail;
    }

    /** classpath의 두 판을 읽는다(판정 선택지는 유연 대화 판 {@value #JUDGMENT_RESOURCE}). */
    public static LiveContentResources loadDefault() {
        return parse(resource(JUDGMENT_RESOURCE), resource(GUARDRAIL_RESOURCE));
    }

    /**
     * 연습 경로에 맞는 판정 선택지 판으로 두 판을 읽는다. 앱 전체가 한 판을 쓴다(참여 확인 · 종료 의도 지점 J1 · J6의 질문 · 선택지는 두 판이 같다).
     *
     * @param flexibleDialogue {@code mujoong.practice-flow.flexible-dialogue}. 거짓(운영 기본)이면 C안 판 {@value #DIRECT_ASK_JUDGMENT_RESOURCE}
     */
    public static LiveContentResources loadFor(boolean flexibleDialogue) {
        return flexibleDialogue ? loadDefault()
                : new LiveContentResources(judgment(resource(DIRECT_ASK_JUDGMENT_RESOURCE), DIRECT_ASK_JUDGMENT_RESOURCE),
                        guardrail(resource(GUARDRAIL_RESOURCE)));
    }

    /** 두 판의 JSON 본문을 읽고 검증한다. */
    public static LiveContentResources parse(String judgmentJson, String guardrailJson) {
        return new LiveContentResources(judgment(judgmentJson, JUDGMENT_RESOURCE), guardrail(guardrailJson));
    }

    public JudgmentOptionSet judgment() {
        return judgment;
    }

    public GuardrailPolicy guardrail() {
        return guardrail;
    }

    private static String resource(String name) {
        ClassLoader loader = Objects.requireNonNullElse(Thread.currentThread().getContextClassLoader(),
                LiveContentResources.class.getClassLoader());
        try (InputStream in = loader.getResourceAsStream(name)) {
            if (in == null) {
                throw new LoadException(Problem.MISSING, name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LoadException(Problem.MISSING, name);
        }
    }

    /** @param name 거부 사유에 남길 자원 이름 */
    private static JudgmentOptionSet judgment(String json, String name) {
        JudgmentFile file;
        try {
            file = JsonCodec.shared().read(Objects.requireNonNull(json, "judgmentJson"), JudgmentFile.class);
        } catch (RuntimeException e) {
            throw new LoadException(Problem.PARSE_FAILED, name);
        }
        if (file == null || blank(file.version()) || blank(file.source())) {
            throw new LoadException(Problem.BAD_VERSION, name);
        }
        if (file.points() == null) {
            throw new LoadException(Problem.BAD_JUDGMENT_POINTS, name);
        }
        Map<JudgmentPoint, PointOptions> points = new LinkedHashMap<>();
        for (RawPoint raw : file.points()) {
            JudgmentPoint point = raw == null ? null : point(raw.point());
            if (point == null || points.containsKey(point) || blank(raw.question())) {
                throw new LoadException(Problem.BAD_JUDGMENT_POINTS, raw == null ? null : safeName(raw.point()));
            }
            points.put(point, new PointOptions(point, raw.question(), options(point, raw.options())));
        }
        if (!points.keySet().equals(EnumSet.allOf(JudgmentPoint.class))) {
            throw new LoadException(Problem.BAD_JUDGMENT_POINTS, "J1 ~ J6 각각 하나");
        }
        return new JudgmentOptionSet(file.version(), file.source(), points);
    }

    private static List<Option> options(JudgmentPoint point, List<RawOption> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new LoadException(Problem.BAD_OPTIONS, point.name());
        }
        List<Option> options = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (RawOption option : raw) {
            if (option == null || option.code() == null || !CODE.matcher(option.code()).matches() || !codes.add(option.code())
                    || blank(option.label()) || blank(option.definition())) {
                throw new LoadException(Problem.BAD_OPTIONS, point.name());
            }
            options.add(new Option(option.code(), option.label(), option.definition()));
        }
        return options;
    }

    private static GuardrailPolicy guardrail(String json) {
        GuardrailFile file;
        try {
            file = JsonCodec.shared().read(Objects.requireNonNull(json, "guardrailJson"), GuardrailFile.class);
        } catch (RuntimeException e) {
            throw new LoadException(Problem.PARSE_FAILED, GUARDRAIL_RESOURCE);
        }
        if (file == null || blank(file.version()) || blank(file.source())) {
            throw new LoadException(Problem.BAD_VERSION, GUARDRAIL_RESOURCE);
        }
        if (blank(file.instructions())) {
            throw new LoadException(Problem.BAD_INSTRUCTIONS, GUARDRAIL_RESOURCE);
        }
        int categoryCount = "jev-guardrail-v2".equals(file.version()) ? 7 : GUARDRAIL_CATEGORY_COUNT;
        List<String> categories = file.categories();
        if (categories == null || categories.size() != categoryCount || new HashSet<>(categories).size() != categories.size()
                || categories.stream().anyMatch(category -> category == null || !CODE.matcher(category).matches())) {
            throw new LoadException(Problem.BAD_CATEGORIES, "categories " + categoryCount + "개 · 중복 없음");
        }
        // criteria 키는 정책 판의 범주 + PASS이고 설명은 모두 비어 있지 않다.
        Map<String, String> criteria = file.criteria();
        Set<String> expectedLabels = new HashSet<>(categories);
        expectedLabels.add(GUARDRAIL_PASS_LABEL);
        if (criteria == null || !criteria.keySet().equals(expectedLabels) || criteria.values().stream().anyMatch(LiveContentResources::blank)) {
            throw new LoadException(Problem.BAD_CRITERIA, "criteria = categories + " + GUARDRAIL_PASS_LABEL + ", 설명 비어 있지 않음");
        }
        List<String> modes = file.modes();
        Set<GuardrailMode> parsed = EnumSet.noneOf(GuardrailMode.class);
        if (modes != null) {
            for (String mode : modes) {
                GuardrailMode value = mode(mode);
                if (value == null || !parsed.add(value)) {
                    throw new LoadException(Problem.BAD_MODES, "modes");
                }
            }
        }
        if (modes == null || !parsed.equals(EnumSet.allOf(GuardrailMode.class))) {
            throw new LoadException(Problem.BAD_MODES, "FREE_TALK · PRACTICE_PEER · PRACTICE_COACH 정확히 셋");
        }
        return new GuardrailPolicy(file.version(), file.source(), file.instructions(), criteria, categories, parsed);
    }

    private static JudgmentPoint point(String name) {
        for (JudgmentPoint point : JudgmentPoint.values()) {
            if (point.name().equals(name)) {
                return point;
            }
        }
        return null;
    }

    private static GuardrailMode mode(String name) {
        for (GuardrailMode mode : GuardrailMode.values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return null;
    }

    /** 예외 메시지에 넣어도 되는 지점 이름(코드 형식일 때만). */
    private static String safeName(String name) {
        return name != null && CODE.matcher(name).matches() && name.length() <= 8 ? name : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
