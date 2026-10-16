package kr.mujoong.realtime.luna;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Luna 응답 여부 · 지연 확인 도구 — realtime (U11 Step 2 · 3 조사, U9-c와 같은 격리 방식).
 *
 * <p>사람이 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew :realtime-activity:lunaReachTest}로만 부른다. 기본 test · check · CI ·
 * verify.sh에서는 빠진다(build-logic 고정 제외 + 루트 {@code verifyLunaReachIsolation}). 키가 없으면 부르지 않고 {@code SKIPPED_NO_KEY}를
 * 보고하며 이것은 통과가 아니다.
 *
 * <p>U9 {@code lunaLiveTest}는 운영 시한 {@code mujoong.timing.judgment.live-budget}(2초)에 묶여 TIMEOUT이면 Luna가 응답 자체를 하는지 알 수
 * 없다. 이 도구는 운영 부품({@link RealtimeLunaClient} · {@link LunaTransport} · {@link LunaSchemas} · {@link LunaInputSanitizer})을 그대로
 * 쓰고 시도 시한만 넉넉히 준다(env {@value #TIMEOUT_ENV}, 기본 {@value #DEFAULT_TIMEOUT_SECONDS}초). 운영 설정 기본값은 바꾸지 않는다. 주소 ·
 * 출력 한도 · 추론 강도는 {@code application.yml}의 {@code mujoong.luna.*} 값이고 {@link LunaHosts} STRICT 규칙을 통과해야만 부른다(우회 없음).
 *
 * <p>요청은 합성 J6(종료 의도) 판정: 판 리소스 {@value #OPTION_SETS_RESOURCE}의 J6 선택지 순서 그대로, 합성 아이 발화 한 줄(이름 없음), 기준 없음.
 * env {@value #COUNT_ENV}(기본 {@value #DEFAULT_COUNT})번 차례로 보낸다. 보고서에는 회차 · 사유 · 응답 모델 · 스키마 통과 · 1 · 2순위 코드와 확률 ·
 * 지연만 쓴다. 요청 · 응답 원문 · 키 · 주소는 쓰지 않는다(R-F3 · R-F14).
 */
final class LunaReachProbe {

    /** 시도 시한(초) env 이름(비밀 아님). */
    static final String TIMEOUT_ENV = "LUNA_REACH_TIMEOUT_SECONDS";
    /** 호출 횟수 env 이름(비밀 아님). */
    static final String COUNT_ENV = "LUNA_REACH_COUNT";
    /** 모델 이름 env(운영과 같은 이름, 비면 운영 기본 모델). */
    static final String MODEL_ENV = "OPENAI_JUDGE_MODEL";
    static final int DEFAULT_TIMEOUT_SECONDS = 60;
    static final int DEFAULT_COUNT = 5;
    static final int MAX_TIMEOUT_SECONDS = 600;
    static final int MAX_COUNT = 20;
    /** 보고서 경로 시스템 속성(lunaReachTest 작업이 build/luna-reach/report.md로 넣는다). */
    static final String REPORT_PROPERTY = "mujoong.luna.reach.report";
    static final String OPTION_SETS_RESOURCE = "judgment/option-sets-v1.json";
    static final String CONFIG_RESOURCE = "application.yml";
    static final String PROMPT_VERSION = "u11-reach.v1";
    /** 운영 시도 시한(live-budget)과 비교하는 기준. */
    static final Duration LIVE_BUDGET = Duration.ofSeconds(2);
    static final Duration TEN_SECONDS = Duration.ofSeconds(10);
    /** 합성 지시(아이 정보 없음). */
    static final String INSTRUCTIONS = "합성 시험 자료다. 아이 발화가 지금 활동 전체를 끝내고 싶다는 뜻인지 options 중 1 · 2순위와 확률을 고른다. "
            + "장면 속 역할 대사나 특정 주제만 싫다는 말은 종료가 아니다.";
    /** 합성 아이 발화(이름 없음). */
    static final String CHILD_UTTERANCE = "이제 그만할래. 나 그만하고 싶어";

    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
    private static final Pattern OPTION_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** 전체 결과. REACHED는 1회 이상 OK. */
    enum Outcome {
        REACHED,
        NOT_REACHED,
        SKIPPED_NO_KEY
    }

    /** 회차 하나(원문 없음). */
    record Attempt(int index, String reason, String responseModel, String schema, String first, String second, long latencyMillis) {
    }

    /** 요약(지연은 OK 회차만, 비율은 전체 회차 기준). */
    record Summary(int attempts, int ok, Long medianMillis, Long maxMillis, int withinBudget, int withinTenSeconds) {
    }

    record Result(Outcome outcome, int timeoutSeconds, List<Attempt> attempts, Summary summary) {
    }

    /** 운영 호출부 한 번(시험에서 가짜로 바꿔 끼운다). */
    @FunctionalInterface
    interface Caller extends AutoCloseable {

        CompletionStage<LunaResult> call(LunaRequest request);

        @Override
        default void close() {
        }
    }

    /** 설정으로 호출부를 만든다(키 없음 시험은 이것이 불리지 않음을 단언한다). */
    @FunctionalInterface
    interface CallerFactory {

        Caller create(LunaProperties properties, Duration timeout, Function<String, String> env, MujoongClock clock);
    }

    private final Function<String, String> env;
    private final Path report;
    private final CallerFactory factory;
    private final MujoongClock clock;
    private final JsonCodec json = JsonCodec.shared();

    LunaReachProbe(Function<String, String> env, Path report, CallerFactory factory, MujoongClock clock) {
        this.env = env;
        this.report = report;
        this.factory = factory;
        this.clock = clock;
    }

    /** 실제 실행용: 프로세스 env · 보고서 경로 · 운영 부품 조립. */
    static LunaReachProbe live() {
        String configured = System.getProperty(REPORT_PROPERTY);
        Path report = Path.of(configured != null ? configured : "build/luna-reach/report.md");
        return new LunaReachProbe(System::getenv, report, LunaReachProbe::operationalCaller, new SystemMujoongClock());
    }

    Path report() {
        return report;
    }

    /** 운영 {@link RealtimeLunaClient}를 시도 시한만 바꿔 조립한다(주소 규칙은 STRICT, 전송 직전 재확인도 운영 코드가 한다). */
    static Caller operationalCaller(LunaProperties properties, Duration timeout, Function<String, String> env, MujoongClock clock) {
        JsonCodec json = JsonCodec.shared();
        LunaTransport transport = new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(),
                () -> env.apply(LunaTransport.API_KEY_ENV), properties.maxResponseBytes());
        RealtimeLunaClient client = new RealtimeLunaClient(properties, timeout, LunaSchemas.loadDefault(json),
                new LunaInputSanitizer(json), transport, TestEventLoggers.strict(clock, LogCapture.detached(),
                        RealtimeMetrics.inMemory()), clock, json);
        return new Caller() {
            @Override
            public CompletionStage<LunaResult> call(LunaRequest request) {
                return client.call(request);
            }

            @Override
            public void close() {
                transport.close();
            }
        };
    }

    // ---- env ----

    /** 시도 시한(초). 없으면 60, 1 ~ 600 밖이면 거절. */
    static int timeoutSeconds(String value) {
        return boundedInt(value, DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, TIMEOUT_ENV);
    }

    /** 호출 횟수. 없으면 5, 1 ~ 20 밖이면 거절. */
    static int count(String value) {
        return boundedInt(value, DEFAULT_COUNT, MAX_COUNT, COUNT_ENV);
    }

    private static int boundedInt(String value, int fallback, int max, String name) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + "는 정수여야 한다", e);
        }
        if (parsed < 1 || parsed > max) {
            throw new IllegalArgumentException(name + "는 1 ~ " + max + "이다");
        }
        return parsed;
    }

    // ---- 설정 · 요청 ----

    /** application.yml의 mujoong.luna.* 값(모델만 env, 비면 운영 기본 모델). 코드에 주소를 적지 않는다(R-F3). */
    LunaProperties properties() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(CONFIG_RESOURCE, new ClassPathResource(CONFIG_RESOURCE));
        String baseUrl = property(sources, "mujoong.luna.base-url");
        String maxBytes = property(sources, "mujoong.luna.max-response-bytes");
        String tokens = property(sources, "mujoong.luna.purpose.judgment-live.max-output-tokens");
        String effort = property(sources, "mujoong.luna.purpose.judgment-live.reasoning-effort");
        return new LunaProperties(env.apply(MODEL_ENV), URI.create(baseUrl), Integer.parseInt(maxBytes),
                new LunaProperties.Purposes(new LunaProperties.PurposeOptions(Integer.parseInt(tokens), effort)));
    }

    private static String property(List<PropertySource<?>> sources, String key) throws IOException {
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().strip();
            }
        }
        throw new IOException(CONFIG_RESOURCE + "에 " + key + "가 없다");
    }

    /** 판 리소스의 J6 판 번호와 선택지 코드(리소스 순서). */
    record OptionSet(String version, List<String> options) {
    }

    @SuppressWarnings("unchecked")
    OptionSet j6Options() throws IOException {
        ClassPathResource resource = new ClassPathResource(OPTION_SETS_RESOURCE);
        Map<String, Object> document;
        try (var in = resource.getInputStream()) {
            document = json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
        for (Object point : (List<Object>) document.get("points")) {
            Map<String, Object> entry = (Map<String, Object>) point;
            if ("J6".equals(entry.get("point"))) {
                List<String> codes = ((List<Object>) entry.get("options")).stream()
                        .map(option -> String.valueOf(((Map<String, Object>) option).get("code"))).toList();
                return new OptionSet(String.valueOf(document.get("version")), codes);
            }
        }
        throw new IOException(OPTION_SETS_RESOURCE + "에 J6이 없다");
    }

    /** ai-judgment-request 모양의 합성 J6 입력(아이 이름 없음, 기준 null). */
    static Map<String, Object> j6Input(OptionSet set) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("judgmentPoint", "J6");
        input.put("optionSetVersion", set.version());
        input.put("options", set.options());
        input.put("childUtterance", CHILD_UTTERANCE);
        input.put("recentTurns", List.of(Map.of("speaker", "PARTNER", "text", "다음 장면으로 가 볼까?")));
        input.put("criteria", null);
        return input;
    }

    // ---- 실행 ----

    Result run() throws IOException {
        String key = env.apply(LunaTransport.API_KEY_ENV);
        if (key == null || key.isBlank()) {
            Result skipped = new Result(Outcome.SKIPPED_NO_KEY, 0, List.of(), summarize(List.of()));
            writeReport(skipped);
            return skipped;
        }
        int timeout = timeoutSeconds(env.apply(TIMEOUT_ENV));
        int count = count(env.apply(COUNT_ENV));
        LunaProperties properties = properties();
        Optional<LunaHosts.Problem> problem = LunaHosts.check(properties.baseUrl(), LunaHosts.modeFor());
        if (problem.isPresent()) {
            List<Attempt> blocked = List.of(new Attempt(0, "HOST_RULE_" + problem.get(), "-", "-", "-", "-", 0));
            Result result = new Result(Outcome.NOT_REACHED, timeout, blocked, summarize(List.of()));
            writeReport(result);
            return result;
        }
        OptionSet set = j6Options();
        Duration limit = Duration.ofSeconds(timeout);
        List<Attempt> attempts = new ArrayList<>();
        try (Caller caller = factory.create(properties, limit, env, clock)) {
            for (int i = 1; i <= count; i++) {
                attempts.add(once(caller, i, set, limit));
            }
        }
        Summary summary = summarize(attempts);
        Result result = new Result(summary.ok() > 0 ? Outcome.REACHED : Outcome.NOT_REACHED, timeout, attempts, summary);
        writeReport(result);
        return result;
    }

    private Attempt once(Caller caller, int index, OptionSet set, Duration limit) {
        LunaRequest request = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, PROMPT_VERSION, INSTRUCTIONS, j6Input(set), Map.of(),
                clock.now().plus(limit));
        long started = clock.monotonicNanos();
        try {
            // 운영 호출부가 시한에서 끝내므로 바깥 대기는 여유를 더한 안전판이다
            LunaResult result = caller.call(request).toCompletableFuture().get(limit.toMillis() + 10_000, TimeUnit.MILLISECONDS);
            String[] ranks = ranks(result.outputJson(), set.options());
            return new Attempt(index, "OK", safe(result.responseModel()), "통과", ranks[0], ranks[1], result.latency().toMillis());
        } catch (ExecutionException failure) {
            long latency = clock.elapsedSince(started).toMillis();
            String reason = failure.getCause() instanceof LunaException luna ? luna.reason().name()
                    : failure.getCause() == null ? "UNKNOWN" : failure.getCause().getClass().getSimpleName();
            String schema = "SCHEMA_INVALID".equals(reason) ? "불통과" : "-";
            return new Attempt(index, reason, "-", schema, "-", "-", latency);
        } catch (TimeoutException e) {
            return new Attempt(index, "OUTER_WAIT_EXCEEDED", "-", "-", "-", "-", clock.elapsedSince(started).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Luna 응답 확인이 중단됐다", e);
        }
    }

    /** 출력 JSON의 1 · 2순위 코드와 확률(소수 둘째 자리). 선택지 밖 코드는 표시만 하고 원문은 쓰지 않는다. */
    @SuppressWarnings("unchecked")
    String[] ranks(String outputJson, List<String> options) {
        Map<String, Object> output = json.read(outputJson, Map.class);
        return new String[] {rank(output.get("first"), options), rank(output.get("second"), options)};
    }

    private static String rank(Object value, List<String> options) {
        if (!(value instanceof Map<?, ?> ranked)) {
            return "-";
        }
        Object option = ranked.get("option");
        Object probability = ranked.get("probability");
        String code = option instanceof String text && OPTION_CODE.matcher(text).matches() && text.length() <= 40 ? text : "(형식 밖)";
        if (!options.contains(code)) {
            code = code + "(선택지 밖)";
        }
        String p = probability instanceof Number number ? String.format(Locale.ROOT, "%.2f", number.doubleValue()) : "-";
        return code + " " + p;
    }

    static String safe(String token) {
        return token != null && SAFE_TOKEN.matcher(token).matches() ? token : "(형식 밖)";
    }

    /** 요약: 지연 중앙값 · 최대는 OK 회차만, 2초 · 10초 이하 수는 전체 회차 중 OK이면서 그 안에 끝난 회차. */
    static Summary summarize(List<Attempt> attempts) {
        List<Long> ok = attempts.stream().filter(attempt -> "OK".equals(attempt.reason())).map(Attempt::latencyMillis).sorted().toList();
        Long median = null;
        Long max = null;
        if (!ok.isEmpty()) {
            int mid = ok.size() / 2;
            median = ok.size() % 2 == 1 ? ok.get(mid) : (ok.get(mid - 1) + ok.get(mid)) / 2;
            max = ok.getLast();
        }
        int withinBudget = (int) ok.stream().filter(ms -> ms <= LIVE_BUDGET.toMillis()).count();
        int withinTen = (int) ok.stream().filter(ms -> ms <= TEN_SECONDS.toMillis()).count();
        return new Summary(attempts.size(), ok.size(), median, max, withinBudget, withinTen);
    }

    static String percent(int part, int whole) {
        return whole == 0 ? "-" : (part * 100 / whole) + "% (" + part + "/" + whole + ")";
    }

    // ---- 보고서 ----

    private void writeReport(Result result) throws IOException {
        if (report.getParent() != null) {
            Files.createDirectories(report.getParent());
        }
        Files.writeString(report, render(result, clock.now().toString()), StandardCharsets.UTF_8);
    }

    static String render(Result result, String nowUtc) {
        boolean skipped = result.outcome() == Outcome.SKIPPED_NO_KEY;
        StringBuilder out = new StringBuilder();
        out.append("outcome=").append(result.outcome()).append(" evidence=").append(skipped ? "NONE" : "REAL_API_SYNTHETIC").append("\n\n");
        out.append("# Luna 응답 여부 · 지연 확인 보고서 (U11 Step 2 · 3 조사, realtime JUDGMENT_LIVE)\n\n");
        out.append("- 실행 시각(UTC): ").append(nowUtc).append('\n');
        if (skipped) {
            out.append("- 증거 종류: 없음(실호출 안 함, env OPENAI_API_KEY 없음). SKIPPED_NO_KEY는 통과로 보지 않는다.\n");
            return out.toString();
        }
        out.append("- 증거 종류: [실호출-합성] 실제 API · 합성 J6 판정 요청(R-M11). 테스트 대역 · 실제 기기 증거가 아니다.\n");
        out.append("- 시도 시한: ").append(result.timeoutSeconds()).append("초(env ").append(TIMEOUT_ENV)
                .append(", 운영 live-budget ").append(LIVE_BUDGET.toSeconds()).append("초와 다름 — 운영 설정은 바꾸지 않았다)\n");
        out.append("- REACHED는 1회 이상 OK다. 요청 · 응답 원문 · 키 · 주소는 담지 않는다(R-F3 · R-F14).\n\n");
        out.append("| 회차 | 결과 사유 | 응답 모델 | 스키마 통과 | 1순위 | 2순위 | 지연 ms |\n|---|---|---|---|---|---|---|\n");
        for (Attempt attempt : result.attempts()) {
            out.append("| ").append(attempt.index()).append(" | ").append(attempt.reason()).append(" | ").append(attempt.responseModel())
                    .append(" | ").append(attempt.schema()).append(" | ").append(attempt.first()).append(" | ").append(attempt.second())
                    .append(" | ").append(attempt.latencyMillis()).append(" |\n");
        }
        Summary summary = result.summary();
        out.append("\n## 요약\n\n");
        out.append("- 성공(OK): ").append(summary.ok()).append('/').append(summary.attempts()).append('\n');
        out.append("- 지연(OK 회차): 중앙값 ").append(summary.medianMillis() == null ? "-" : summary.medianMillis() + "ms")
                .append(" · 최대 ").append(summary.maxMillis() == null ? "-" : summary.maxMillis() + "ms").append('\n');
        out.append("- 2초 이하(live-budget 안, 전체 회차 기준): ").append(percent(summary.withinBudget(), summary.attempts())).append('\n');
        out.append("- 10초 이하(전체 회차 기준): ").append(percent(summary.withinTenSeconds(), summary.attempts())).append('\n');
        return out.toString();
    }

    static String oneLine(Result result) {
        Summary summary = result.summary();
        return "Luna 응답 확인 " + result.outcome() + " — OK " + summary.ok() + "/" + summary.attempts() + " · 중앙값 "
                + (summary.medianMillis() == null ? "-" : summary.medianMillis() + "ms");
    }
}
