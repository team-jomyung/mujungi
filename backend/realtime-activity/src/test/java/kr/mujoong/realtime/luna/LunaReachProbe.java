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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Luna 응답 여부 · 지연 · 사용량 확인 도구 — realtime (U11 Step 2 · 3 조사, U9-c와 같은 격리 방식).
 *
 * <p>사람이 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew :realtime-activity:lunaReachTest}로만 부른다. 기본 test · check · CI ·
 * verify.sh에서는 빠진다(build-logic 고정 제외 + 루트 {@code verifyLunaReachIsolation}). 키가 없으면 부르지 않고 {@code SKIPPED_NO_KEY}를
 * 보고하며 이것은 통과가 아니다.
 *
 * <p>U9 {@code lunaLiveTest}는 운영 시한 {@code mujoong.timing.judgment.live-budget}(4초)에 묶여 TIMEOUT이면 Luna가 응답 자체를 하는지 알 수
 * 없다. 이 도구는 시도 시한만 넉넉히 준다(env {@value #TIMEOUT_ENV}, 기본 {@value #DEFAULT_TIMEOUT_SECONDS}초). 운영 설정은 바꾸지 않는다.
 *
 * <p>공급자 사용량(usage 토큰)을 남기려고 {@link RealtimeLunaClient#call}을 거치지 않고, 그 안의 운영 부품을 같은 순서로 직접 부른다: 입력 정리
 * {@link LunaInputSanitizer} → 요청 스키마 {@link LunaSchemas#isValidRequest} → 보내기 전 거절 {@link LunaTransport#rejectBeforeSend} → 본문
 * 조립 {@link RealtimeLunaClient.ResponsesRequest} → 전송 {@link LunaTransport#send} → 응답 해석 {@link RealtimeLunaClient.ResponsesReply}.
 * 운영 클라이언트는 응답의 {@code usage}를 버리고 돌려주지 않기 때문이다(운영 코드는 바꾸지 않음). 사유 분류는 운영
 * {@code RealtimeLunaClient.classify}와 같은 순서이고, 둘이 같은 사유를 내는지는 루프백 대역 대조 시험이 지킨다.
 *
 * <p>주소 · 출력 한도 · 추론 강도는 {@code application.yml}의 {@code mujoong.luna.*} 값이고 {@link LunaHosts} STRICT 규칙을 통과해야만 부른다
 * (전송 직전 재확인도 운영 전송부가 한다). 요청은 합성 J6(종료 의도) 판정이다: 판 리소스 {@value #OPTION_SETS_RESOURCE}의 J6 선택지 순서,
 * 합성 아이 발화 한 줄(이름 없음), 기준 없음. env {@value #COUNT_ENV}(기본 {@value #DEFAULT_COUNT})번 차례로 보낸다. 보고서에는 회차 · 사유 ·
 * 응답 모델 · 스키마 통과 · 1 · 2순위 코드와 확률 · 지연 · 사용량 토큰만 쓴다. 요청 · 응답 원문 · 키 · 주소 · 금액은 쓰지 않는다(R-F3 · R-F14).
 */
final class LunaReachProbe {

    /** 시도 시한(초) env 이름(비밀 아님). */
    static final String TIMEOUT_ENV = "LUNA_REACH_TIMEOUT_SECONDS";
    /** 호출 횟수 env 이름(비밀 아님). */
    static final String COUNT_ENV = "LUNA_REACH_COUNT";
    /** 모델 이름 env(운영과 같은 이름, 비면 운영 기본 모델). */
    static final String MODEL_ENV = "OPENAI_JUDGE_MODEL";
    static final int DEFAULT_TIMEOUT_SECONDS = 60;
    /** 사용량 한도가 있어 기본은 두 번이다(사람이 늘릴 수 있다). */
    static final int DEFAULT_COUNT = 2;
    static final int MAX_TIMEOUT_SECONDS = 600;
    static final int MAX_COUNT = 20;
    /** 보고서 경로 시스템 속성(lunaReachTest 작업이 build/luna-reach/report.md로 넣는다). */
    static final String REPORT_PROPERTY = "mujoong.luna.reach.report";
    static final String OPTION_SETS_RESOURCE = "judgment/option-sets-v1.json";
    static final String CONFIG_RESOURCE = "application.yml";
    static final String PROMPT_VERSION = "u11-reach.v1";
    /** 운영 시도 시한(live-budget)과 비교하는 기준. */
    static final Duration LIVE_BUDGET = Duration.ofSeconds(4);
    static final Duration TEN_SECONDS = Duration.ofSeconds(10);
    /** 운영 전송부 시한 뒤 바깥 대기 여유. */
    static final Duration OUTER_MARGIN = Duration.ofSeconds(10);
    /** 보고서 머리 문장(금액을 계산하지 않는다). */
    static final String PRICE_NOTE = "단가는 OpenAI 결제 화면에서 확인한다(이 보고서는 사용량만 남긴다).";
    /** 사용량을 받지 못한 회차 표시(과금 여부를 단정하지 않는다). */
    static final String USAGE_UNKNOWN = "모름";
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

    /** 호출 한 번의 결과. {@code usage}가 null이면 사용량을 받지 못했다(모름). */
    record CallResult(String reason, String responseModel, String outputJson, long latencyMillis, Map<String, Long> usage) {
    }

    /** 회차 하나(원문 없음). {@code usage}가 null이면 모름. */
    record Attempt(int index, String reason, String responseModel, String schema, String first, String second, long latencyMillis,
            Map<String, Long> usage) {

        Attempt(int index, String reason, String responseModel, String schema, String first, String second, long latencyMillis) {
            this(index, reason, responseModel, schema, first, second, latencyMillis, null);
        }
    }

    /** 요약(지연은 OK 회차만, 비율은 전체 회차 기준, 사용량은 받은 회차 합계와 모름 회차 수). */
    record Summary(int attempts, int ok, Long medianMillis, Long maxMillis, int withinBudget, int withinTenSeconds,
            Map<String, Long> usageTotals, int usageUnknown) {
    }

    record Result(Outcome outcome, int timeoutSeconds, List<Attempt> attempts, Summary summary) {
    }

    /** 공급자 호출 한 번(시험에서 가짜로 바꿔 끼운다). */
    @FunctionalInterface
    interface Caller extends AutoCloseable {

        CallResult call(LunaRequest request, Duration limit);

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

    /** 운영 전송부(STRICT 주소 규칙, env 키)로 호출부를 조립한다. */
    static Caller operationalCaller(LunaProperties properties, Duration timeout, Function<String, String> env, MujoongClock clock) {
        LunaTransport transport = new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(),
                () -> env.apply(LunaTransport.API_KEY_ENV), properties.maxResponseBytes());
        return new OperationalCaller(properties, transport, clock);
    }

    /** {@link RealtimeLunaClient}의 운영 부품을 같은 순서로 부르고 응답의 usage도 읽는다. */
    static final class OperationalCaller implements Caller {

        private final LunaProperties properties;
        private final LunaTransport transport;
        private final MujoongClock clock;
        private final JsonCodec json = JsonCodec.shared();
        private final LunaSchemas schemas = LunaSchemas.loadDefault(json);
        private final LunaInputSanitizer sanitizer = new LunaInputSanitizer(json);

        OperationalCaller(LunaProperties properties, LunaTransport transport, MujoongClock clock) {
            this.properties = properties;
            this.transport = transport;
            this.clock = clock;
        }

        @Override
        public CallResult call(LunaRequest request, Duration limit) {
            SanitizedInput input;
            try {
                input = sanitizer.sanitize(request.input(), request.aliases());
            } catch (LunaException rejected) {
                return notSent(rejected.reason());
            }
            String inputJson = json.write(input.payload());
            if (!schemas.isValidRequest(request.purpose(), inputJson)) {
                return notSent(LunaFailureReason.FORBIDDEN_INPUT);
            }
            Optional<LunaFailureReason> rejected = transport.rejectBeforeSend(limit);
            if (rejected.isPresent()) {
                return notSent(rejected.get());
            }
            String body = json.write(new RealtimeLunaClient.ResponsesRequest(properties.model(), request.instructions(), inputJson,
                    schemas.pair(request.purpose()), request.purpose(), properties.options(request.purpose())).body());
            long started = clock.monotonicNanos();
            LunaTransport.RawResponse response;
            try {
                response = transport.send(body, limit).get(limit.plus(OUTER_MARGIN).toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException failure) {
                return new CallResult(LunaTransport.translate(failure).reason().name(), null, null,
                        clock.elapsedSince(started).toMillis(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Luna 응답 확인이 중단됐다", e);
            }
            return classify(schemas, json, response, request.purpose(), clock.elapsedSince(started).toMillis());
        }

        private static CallResult notSent(LunaFailureReason reason) {
            return new CallResult(reason.name(), null, null, 0, null);
        }

        @Override
        public void close() {
            transport.close();
        }
    }

    /**
     * 응답 분류 — 운영 {@code RealtimeLunaClient.classify}와 같은 순서(BR4.4 → BR4.1 → BR4.3 → BR4.2)다. 사용량은 본문의 {@code usage}
     * 숫자 필드이고, 본문이 JSON이 아니거나 {@code usage}가 없으면 모름(null)이다.
     */
    static CallResult classify(LunaSchemas schemas, JsonCodec json, LunaTransport.RawResponse response, LunaPurpose purpose,
            long latencyMillis) {
        Map<String, Long> usage = usageOf(json, response.body());
        int status = response.status();
        LunaFailureReason failure = null;
        String model = null;
        String output = null;
        if (status == 401 || status == 403) {
            failure = LunaFailureReason.AUTH_FAILED;
        } else if (status == 429) {
            failure = LunaFailureReason.RATE_LIMITED;
        } else if (status >= 500) {
            failure = LunaFailureReason.SERVER_ERROR;
        } else if (status < 200 || status >= 300) {
            failure = LunaFailureReason.REQUEST_REJECTED;
        } else {
            RealtimeLunaClient.ResponsesReply reply = RealtimeLunaClient.ResponsesReply.parse(json, response.body());
            if (reply == null) {
                failure = LunaFailureReason.SERVER_ERROR;
            } else {
                model = reply.model();
                if ("incomplete".equals(reply.status())) {
                    failure = LunaFailureReason.LENGTH_EXCEEDED;
                } else if (reply.refused()) {
                    failure = LunaFailureReason.REFUSED;
                } else if (!"completed".equals(reply.status())) {
                    failure = LunaFailureReason.SERVER_ERROR;
                } else if (reply.outputText().isBlank()) {
                    failure = LunaFailureReason.EMPTY_OUTPUT;
                } else if (reply.model() == null || reply.model().isBlank()) {
                    failure = LunaFailureReason.MODEL_MISSING;
                } else if (!schemas.isValidOutput(purpose, reply.outputText())) {
                    failure = LunaFailureReason.SCHEMA_INVALID;
                } else {
                    output = reply.outputText();
                }
            }
        }
        return new CallResult(failure == null ? "OK" : failure.name(), model, output, latencyMillis, usage);
    }

    /** 본문 {@code usage}의 숫자 필드(한 단계 안의 세부 항목은 {@code 바깥.안} 이름). 없으면 null(모름). */
    @SuppressWarnings("unchecked")
    static Map<String, Long> usageOf(JsonCodec json, String body) {
        Map<String, Object> root;
        try {
            root = json.read(body, Map.class);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null || !(root.get("usage") instanceof Map<?, ?> usage)) {
            return null;
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : usage.entrySet()) {
            String name = safe(String.valueOf(entry.getKey()));
            if (entry.getValue() instanceof Number number) {
                numbers.put(name, number.longValue());
            } else if (entry.getValue() instanceof Map<?, ?> details) {
                for (Map.Entry<?, ?> detail : details.entrySet()) {
                    if (detail.getValue() instanceof Number number) {
                        numbers.put(name + "." + safe(String.valueOf(detail.getKey())), number.longValue());
                    }
                }
            }
        }
        return numbers;
    }

    // ---- env ----

    /** 시도 시한(초). 없으면 60, 1 ~ 600 밖이면 거절. */
    static int timeoutSeconds(String value) {
        return boundedInt(value, DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, TIMEOUT_ENV);
    }

    /** 호출 횟수. 없으면 2, 1 ~ 20 밖이면 거절. */
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
                new LunaProperties.Purposes(new LunaProperties.PurposeOptions(Integer.parseInt(tokens), effort)),
                false, null);
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
                LunaRequest request = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, PROMPT_VERSION, INSTRUCTIONS, j6Input(set), Map.of(),
                        clock.now().plus(limit));
                attempts.add(attempt(i, caller.call(request, limit), set.options()));
            }
        }
        Summary summary = summarize(attempts);
        Result result = new Result(summary.ok() > 0 ? Outcome.REACHED : Outcome.NOT_REACHED, timeout, attempts, summary);
        writeReport(result);
        return result;
    }

    /** 호출 결과 → 보고서 회차(원문 없음). */
    Attempt attempt(int index, CallResult call, List<String> options) {
        String model = call.responseModel() == null ? "-" : safe(call.responseModel());
        if ("OK".equals(call.reason())) {
            String[] ranks = ranks(call.outputJson(), options);
            return new Attempt(index, "OK", model, "통과", ranks[0], ranks[1], call.latencyMillis(), call.usage());
        }
        String schema = "SCHEMA_INVALID".equals(call.reason()) ? "불통과" : "-";
        return new Attempt(index, safe(call.reason()), model, schema, "-", "-", call.latencyMillis(), call.usage());
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

    /** 요약: 지연 중앙값 · 최대는 OK 회차만, 4초 · 10초 이하 수는 OK이면서 그 안에 끝난 회차, 사용량은 받은 회차 합계 · 모름 회차 수. */
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
        Map<String, Long> totals = new LinkedHashMap<>();
        int unknown = 0;
        for (Attempt attempt : attempts) {
            if (attempt.usage() == null) {
                unknown++;
            } else {
                attempt.usage().forEach((name, value) -> totals.merge(name, value, Long::sum));
            }
        }
        return new Summary(attempts.size(), ok.size(), median, max, withinBudget, withinTen, totals, unknown);
    }

    static String percent(int part, int whole) {
        return whole == 0 ? "-" : (part * 100 / whole) + "% (" + part + "/" + whole + ")";
    }

    static String usageText(Map<String, Long> usage) {
        if (usage == null) {
            return USAGE_UNKNOWN;
        }
        if (usage.isEmpty()) {
            return "숫자 필드 없음";
        }
        List<String> parts = new ArrayList<>();
        usage.forEach((name, value) -> parts.add(name + "=" + value));
        return String.join(" ", parts);
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
        out.append("# Luna 응답 여부 · 지연 · 사용량 확인 보고서 (U11 Step 2 · 3 조사, realtime JUDGMENT_LIVE)\n\n");
        out.append("- ").append(PRICE_NOTE).append('\n');
        out.append("- 실행 시각(UTC): ").append(nowUtc).append('\n');
        if (skipped) {
            out.append("- 증거 종류: 없음(실호출 안 함, env OPENAI_API_KEY 없음). SKIPPED_NO_KEY는 통과로 보지 않는다.\n");
            return out.toString();
        }
        out.append("- 증거 종류: [실호출-합성] 실제 API · 합성 J6 판정 요청(R-M11). 테스트 대역 · 실제 기기 증거가 아니다.\n");
        out.append("- 시도 시한: ").append(result.timeoutSeconds()).append("초(env ").append(TIMEOUT_ENV)
                .append(", 운영 live-budget ").append(LIVE_BUDGET.toSeconds()).append("초와 다름 — 운영 설정은 바꾸지 않았다)\n");
        out.append("- REACHED는 1회 이상 OK다. 사용량을 받지 못한 회차는 '").append(USAGE_UNKNOWN)
                .append("'이고 과금 여부를 단정하지 않는다. 요청 · 응답 원문 · 키 · 주소는 담지 않는다(R-F3 · R-F14).\n\n");
        out.append("| 회차 | 결과 사유 | 응답 모델 | 스키마 통과 | 1순위 | 2순위 | 지연 ms | 사용량(토큰) |\n|---|---|---|---|---|---|---|---|\n");
        for (Attempt attempt : result.attempts()) {
            out.append("| ").append(attempt.index()).append(" | ").append(attempt.reason()).append(" | ").append(attempt.responseModel())
                    .append(" | ").append(attempt.schema()).append(" | ").append(attempt.first()).append(" | ").append(attempt.second())
                    .append(" | ").append(attempt.latencyMillis()).append(" | ").append(usageText(attempt.usage())).append(" |\n");
        }
        Summary summary = result.summary();
        out.append("\n## 요약\n\n");
        out.append("- 성공(OK): ").append(summary.ok()).append('/').append(summary.attempts()).append('\n');
        out.append("- 지연(OK 회차): 중앙값 ").append(summary.medianMillis() == null ? "-" : summary.medianMillis() + "ms")
                .append(" · 최대 ").append(summary.maxMillis() == null ? "-" : summary.maxMillis() + "ms").append('\n');
        out.append("- 4초 이하(live-budget 안, 전체 회차 기준): ").append(percent(summary.withinBudget(), summary.attempts())).append('\n');
        out.append("- 10초 이하(전체 회차 기준): ").append(percent(summary.withinTenSeconds(), summary.attempts())).append('\n');
        out.append("- 사용량 합계(받은 회차): ").append(summary.usageTotals().isEmpty() ? "-" : usageText(summary.usageTotals()))
                .append(" · ").append(USAGE_UNKNOWN).append(' ').append(summary.usageUnknown()).append("회\n");
        return out.toString();
    }

    static String oneLine(Result result) {
        Summary summary = result.summary();
        return "Luna 응답 확인 " + result.outcome() + " — OK " + summary.ok() + "/" + summary.attempts() + " · 중앙값 "
                + (summary.medianMillis() == null ? "-" : summary.medianMillis() + "ms") + " · 사용량 "
                + (summary.usageTotals().isEmpty() ? "-" : usageText(summary.usageTotals()));
    }
}
