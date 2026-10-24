package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.luna.LunaInputSanitizer;
import kr.mujoong.realtime.luna.LunaProperties;
import kr.mujoong.realtime.luna.LunaSchemas;
import kr.mujoong.realtime.luna.LunaTransport;
import kr.mujoong.realtime.luna.RealtimeLunaClient;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 판정 추론 강도 비교 실측 도구(4회차 R5, docs/practice-dialogue-standard.md 16 · 17절). 같은 판정 입력을 운영 판정 부품
 * ({@link LunaJudgeClient} → {@link RealtimeLunaClient} → {@link LunaTransport}, 운영 판정 지시문 · 선택지 판 · 확신 문턱)으로
 * {@code reasoning.effort}만 바꿔 보내 지연과 1순위를 잰다. 시도 시한은 넉넉히 두고(운영 live-budget보다 길게) 운영이라면 시한 안에 들었을지를
 * 따로 센다.
 *
 * <p>사람이 env {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew :realtime-activity:lunaReachTest --tests "*JudgmentEffortBenchTest*"}로만
 * 돈다(기본 test는 {@code luna-reach}를 고정 제외하고 키를 지운다). 키가 없으면 건너뜀으로 끝난다(통과 아님). 결과는 [실호출-합성] 증거다
 * (R-M11). 보고서 · JSON에는 사례 번호 · 선택지 코드 · 지연만 쓰고 발화 원문 · 키 · 주소는 쓰지 않는다(R-F14).
 *
 * <p>사례: 기준 연습 대화의 아이 대사 5개(시험 자원, 기대 판정 포함)에 env {@value #CASES_ENV}의 JSON 배열
 * ({@code [{id, point, text, recent:[…], criteria, expected}]}, 실음성 실측 대화록에서 만든 합성 발화)을 더한다. 운영처럼 최근 대화는 그 앞
 * 아이 전사 최대 3개, 기준은 J2 · J3 · J5에만 싣는다.
 */
@Tag("luna-reach")
class JudgmentEffortBenchTest {

    static final String OUT_ENV = "MUJOONG_JUDGE_BENCH_OUT";
    static final String EFFORTS_ENV = "MUJOONG_JUDGE_BENCH_EFFORTS";
    static final String ROUNDS_ENV = "MUJOONG_JUDGE_BENCH_ROUNDS";
    static final String CASES_ENV = "MUJOONG_JUDGE_BENCH_CASES";
    static final String MAX_CALLS_ENV = "MUJOONG_JUDGE_BENCH_MAX_CALLS";
    static final String LIMIT_ENV = "MUJOONG_JUDGE_BENCH_LIMIT_SECONDS";
    static final String MODEL_ENV = "OPENAI_JUDGE_MODEL";
    private static final String CONFIG = "application.yml";
    private static final String STANDARD = "practiceflow/standard-practice-dialogue.json";
    private static final String CHILD_NAME = "하늘";
    private static final int CORE_FUNCTION_MAX = 500;

    private final JsonCodec json = JsonCodec.shared();
    private final MujoongClock clock = new SystemMujoongClock();

    /** 사례 하나(원문은 보고서에 쓰지 않는다). */
    record BenchCase(String id, JudgmentPoint point, String text, List<String> recent, String criteria, String expected) {
    }

    /** 한 번의 호출 결과. */
    record Attempt(String effort, String caseId, int round, String kind, String first, Double firstProbability, String second,
            String abstainReason, long latencyMs, String model, boolean withinBudget, String error) {
    }

    @Test
    void judgmentLatencyAndAgreementByReasoningEffort() throws Exception {
        String key = System.getenv(LunaTransport.API_KEY_ENV);
        Path out = Path.of(env(OUT_ENV, "build/judge-bench"));
        Files.createDirectories(out);
        if (key == null || key.isBlank()) {
            Files.writeString(out.resolve("report.md"), "outcome=SKIPPED_NO_KEY evidence=NONE\n", StandardCharsets.UTF_8);
        }
        assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY: env OPENAI_API_KEY 없음(실호출 안 함, 통과 아님)");

        List<PropertySource<?>> config = new YamlPropertySourceLoader().load(CONFIG, new ClassPathResource(CONFIG));
        URI baseUrl = URI.create(property(config, "mujoong.luna.base-url"));
        int maxBytes = Integer.parseInt(property(config, "mujoong.luna.max-response-bytes"));
        int tokens = Integer.parseInt(property(config, "mujoong.luna.purpose.judgment-live.max-output-tokens"));
        String operationalEffort = property(config, "mujoong.luna.purpose.judgment-live.reasoning-effort");
        Duration liveBudget = Duration.parse("PT" + property(config, "mujoong.timing.judgment.live-budget").toUpperCase(Locale.ROOT));
        double minConfidence = Double.parseDouble(property(config, "mujoong.judgment.min-confidence"));
        double minMargin = Double.parseDouble(property(config, "mujoong.judgment.min-margin"));

        List<String> efforts = List.of(env(EFFORTS_ENV, "medium,low,minimal").split(","))
                .stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
        int rounds = bounded(env(ROUNDS_ENV, "3"), 1, 10, ROUNDS_ENV);
        int maxCalls = bounded(env(MAX_CALLS_ENV, "200"), 1, 1000, MAX_CALLS_ENV);
        Duration limit = Duration.ofSeconds(bounded(env(LIMIT_ENV, "20"), 1, 120, LIMIT_ENV));

        LiveContentResources.JudgmentOptionSet optionSet = LiveContentResources.loadDefault().judgment();
        JudgmentPrompt prompt = JudgmentPrompt.load(optionSet);
        JudgeOutcomeValidator validator = new JudgeOutcomeValidator(minConfidence, minMargin, json);
        List<BenchCase> cases = new ArrayList<>(standardCases());
        cases.addAll(extraCases());

        Map<String, LunaJudgeClient> clients = new LinkedHashMap<>();
        for (String effort : efforts) {
            LunaProperties properties = new LunaProperties(System.getenv(MODEL_ENV), baseUrl, maxBytes,
                    new LunaProperties.Purposes(new LunaProperties.PurposeOptions(tokens, effort)), true);
            RealtimeLunaClient client = new RealtimeLunaClient(properties, limit, LunaSchemas.loadDefault(json),
                    new LunaInputSanitizer(json), new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(),
                            () -> System.getenv(LunaTransport.API_KEY_ENV), properties.maxResponseBytes()),
                    TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory()), clock, json);
            clients.put(effort, new LunaJudgeClient(client::call, prompt, validator, clock));
        }

        List<Attempt> attempts = new ArrayList<>();
        int calls = 0;
        boolean capped = false;
        for (int round = 1; round <= rounds && !capped; round++) {
            for (int c = 0; c < cases.size() && !capped; c++) {
                BenchCase item = cases.get(c);
                // 같은 사례 안에서 강도 순서를 돌려 시간대 차이가 한 강도에 몰리지 않게 한다
                for (int e = 0; e < efforts.size(); e++) {
                    if (calls >= maxCalls) {
                        capped = true;
                        break;
                    }
                    String effort = efforts.get((e + round + c) % efforts.size());
                    calls++;
                    attempts.add(call(clients.get(effort), effort, item, round, optionSet, liveBudget, limit));
                }
            }
        }

        // 요약이 실패해도 호출 결과는 남게 원자료를 먼저 쓴다
        Files.writeString(out.resolve("judge-bench-attempts.json"), json.write(attempts.stream().map(this::attemptMap).toList()),
                StandardCharsets.UTF_8);
        Map<String, Object> summary = summarize(efforts, cases, attempts, liveBudget);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("evidence", "REAL_API_SYNTHETIC");
        document.put("operationalEffort", operationalEffort);
        document.put("liveBudgetMs", liveBudget.toMillis());
        document.put("attemptLimitMs", limit.toMillis());
        document.put("promptVersion", prompt.version());
        document.put("optionSetVersion", optionSet.version());
        document.put("efforts", efforts);
        document.put("rounds", rounds);
        document.put("calls", calls);
        document.put("cappedByMaxCalls", capped);
        document.put("cases", cases.stream().map(item -> ordered("id", item.id(), "point", item.point().name(), "expected",
                item.expected(), "recentTurns", item.recent().size(), "criteria", item.criteria() != null)).toList());
        document.put("summary", summary);
        document.put("attempts", attempts.stream().map(this::attemptMap).toList());
        Files.writeString(out.resolve("judge-bench.json"), json.write(document), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("report.md"), report(document, efforts, cases, attempts, liveBudget), StandardCharsets.UTF_8);
        System.out.println("judge-bench calls=" + calls + " out=" + out.toAbsolutePath());

        assertThat(attempts).as("응답이 하나라도 와야 한다").anyMatch(attempt -> attempt.error() == null);
    }

    private Attempt call(LunaJudgeClient client, String effort, BenchCase item, int round,
            LiveContentResources.JudgmentOptionSet optionSet, Duration liveBudget, Duration limit) {
        LiveContentResources.PointOptions options = optionSet.points().get(LiveContentResources.JudgmentPoint.valueOf(item.point().name()));
        List<JudgeRequest.RecentTurn> recent = item.recent().stream()
                .map(text -> new JudgeRequest.RecentTurn(JudgeRequest.Speaker.CHILD, text)).toList();
        JudgeRequest.Criteria criteria = item.criteria() == null ? null
                : new JudgeRequest.Criteria(item.criteria().length() <= CORE_FUNCTION_MAX ? item.criteria()
                        : item.criteria().substring(0, CORE_FUNCTION_MAX), item.criteria());
        JudgeRequest request = new JudgeRequest(item.point(), optionSet.version(), options.optionCodes(), item.text(), recent, criteria,
                Map.of(CHILD_NAME, "아이"), false, clock.now().plus(limit));
        try {
            JudgeOutcome outcome = client.judge(request, UUID.randomUUID()).toCompletableFuture()
                    .get(limit.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            long latency = outcome.latency() == null ? -1 : outcome.latency().toMillis();
            boolean within = latency >= 0 && latency <= liveBudget.toMillis();
            return new Attempt(effort, item.id(), round, outcome.kind().name(), outcome.firstOption(), outcome.firstProbability(),
                    outcome.secondOption(), outcome.abstainReason() == null ? null : outcome.abstainReason().name(), latency,
                    safe(outcome.model()), within, null);
        } catch (ExecutionException | TimeoutException failure) {
            return new Attempt(effort, item.id(), round, null, null, null, null, null, -1, null, false,
                    failure.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Attempt(effort, item.id(), round, null, null, null, null, null, -1, null, false, "INTERRUPTED");
        }
    }

    // ---- 사례 ----

    @SuppressWarnings("unchecked")
    private List<BenchCase> standardCases() throws IOException {
        Map<String, Object> document;
        try (InputStream in = new ClassPathResource(STANDARD).getInputStream()) {
            document = json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
        Map<String, Object> practice = (Map<String, Object>) document.get("practice");
        String criteria = String.valueOf(((Map<String, Object>) ((List<Object>) practice.get("goals")).get(0)).get("judgmentCriteria"));
        List<BenchCase> cases = new ArrayList<>();
        List<String> said = new ArrayList<>();
        int n = 0;
        for (Object raw : (List<Object>) document.get("childTurns")) {
            Map<String, Object> turn = (Map<String, Object>) raw;
            JudgmentPoint point = JudgmentPoint.valueOf(String.valueOf(turn.get("point")));
            String text = String.valueOf(turn.get("text"));
            boolean withCriteria = point == JudgmentPoint.J2 || point == JudgmentPoint.J3 || point == JudgmentPoint.J5;
            cases.add(new BenchCase("std-" + (++n) + "-" + point.name(), point, text, lastThree(said), withCriteria ? criteria : null,
                    String.valueOf(turn.get("option"))));
            said.add(text);
        }
        return cases;
    }

    @SuppressWarnings("unchecked")
    private List<BenchCase> extraCases() throws IOException {
        String file = System.getenv(CASES_ENV);
        if (file == null || file.isBlank()) {
            return List.of();
        }
        List<Object> raw = json.read(Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8), List.class);
        List<BenchCase> cases = new ArrayList<>();
        for (Object entry : raw) {
            Map<String, Object> item = (Map<String, Object>) entry;
            List<String> recent = item.get("recent") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
            Object expected = item.get("expected");
            Object criteria = item.get("criteria");
            cases.add(new BenchCase(String.valueOf(item.get("id")), JudgmentPoint.valueOf(String.valueOf(item.get("point"))),
                    String.valueOf(item.get("text")), lastThree(recent), criteria == null ? null : String.valueOf(criteria),
                    expected == null ? null : String.valueOf(expected)));
        }
        return cases;
    }

    private static List<String> lastThree(List<String> said) {
        int from = Math.max(0, said.size() - JudgeRequest.MAX_RECENT_TURNS);
        return List.copyOf(said.subList(from, said.size()));
    }

    // ---- 요약 ----

    private Map<String, Object> summarize(List<String> efforts, List<BenchCase> cases, List<Attempt> attempts, Duration liveBudget) {
        Map<String, String> majority = majorityFirst(efforts.isEmpty() ? null : efforts.get(0), attempts);
        Map<String, Object> byEffort = new LinkedHashMap<>();
        for (String effort : efforts) {
            List<Attempt> mine = attempts.stream().filter(attempt -> attempt.effort().equals(effort)).toList();
            List<Long> latencies = mine.stream().filter(attempt -> attempt.latencyMs() >= 0).map(Attempt::latencyMs).sorted().toList();
            long confirmed = mine.stream().filter(attempt -> "CONFIRMED".equals(attempt.kind())).count();
            long confirmedInBudget = mine.stream().filter(attempt -> "CONFIRMED".equals(attempt.kind()) && attempt.withinBudget()).count();
            long withExpected = 0;
            long firstMatchesExpected = 0;
            long effectiveMatchesExpected = 0;
            for (Attempt attempt : mine) {
                String expected = expectedOf(cases, attempt.caseId());
                if (expected == null) {
                    continue;
                }
                withExpected++;
                if (expected.equals(attempt.first())) {
                    firstMatchesExpected++;
                    if ("CONFIRMED".equals(attempt.kind()) && attempt.withinBudget()) {
                        effectiveMatchesExpected++;
                    }
                }
            }
            long withMajority = 0;
            long matchesMajority = 0;
            for (Attempt attempt : mine) {
                String reference = majority.get(attempt.caseId());
                if (reference != null && attempt.first() != null) {
                    withMajority++;
                    if (reference.equals(attempt.first())) {
                        matchesMajority++;
                    }
                }
            }
            Map<String, Long> abstains = new TreeMap<>();
            Map<String, Long> errors = new TreeMap<>();
            for (Attempt attempt : mine) {
                if (attempt.abstainReason() != null) {
                    abstains.merge(attempt.abstainReason(), 1L, Long::sum);
                }
                if (attempt.error() != null) {
                    errors.merge(attempt.error(), 1L, Long::sum);
                }
            }
            byEffort.put(effort, ordered("calls", mine.size(), "confirmed", confirmed, "confirmedWithinLiveBudget", confirmedInBudget,
                    "latencyP50Ms", percentile(latencies, 50), "latencyP90Ms", percentile(latencies, 90), "latencyMaxMs",
                    latencies.isEmpty() ? -1 : latencies.get(latencies.size() - 1), "withinLiveBudget",
                    mine.stream().filter(Attempt::withinBudget).count(), "expectedCompared", withExpected, "firstMatchesExpected",
                    firstMatchesExpected, "confirmedInBudgetMatchesExpected", effectiveMatchesExpected, "majorityCompared", withMajority,
                    "firstMatchesFirstEffortMajority", matchesMajority, "abstainReasons", abstains, "errors", errors));
        }
        return ordered("liveBudgetMs", liveBudget.toMillis(), "referenceEffortForMajority", efforts.isEmpty() ? null : efforts.get(0),
                "byEffort", byEffort);
    }

    /** 기준 강도(첫 번째)에서 사례마다 가장 많이 나온 1순위(같으면 사전 순 앞). */
    private static Map<String, String> majorityFirst(String effort, List<Attempt> attempts) {
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        for (Attempt attempt : attempts) {
            if (attempt.effort().equals(effort) && attempt.first() != null) {
                counts.computeIfAbsent(attempt.caseId(), ignored -> new TreeMap<>()).merge(attempt.first(), 1L, Long::sum);
            }
        }
        Map<String, String> majority = new LinkedHashMap<>();
        counts.forEach((caseId, byOption) -> majority.put(caseId, byOption.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue)).map(Map.Entry::getKey).orElse(null)));
        return majority;
    }

    private static String expectedOf(List<BenchCase> cases, String caseId) {
        // 기대가 없는 사례(null)도 있으므로 사례를 먼저 찾고 기대 값을 꺼낸다
        return cases.stream().filter(item -> item.id().equals(caseId)).findFirst().map(BenchCase::expected).orElse(null);
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    // ---- 보고서 ----

    @SuppressWarnings("unchecked")
    private String report(Map<String, Object> document, List<String> efforts, List<BenchCase> cases, List<Attempt> attempts,
            Duration liveBudget) {
        StringBuilder md = new StringBuilder();
        md.append("# 판정 추론 강도 비교 (실제 Luna · 합성 발화)\n\n");
        md.append("- 증거 종류: [실호출-합성] 실제 API · 합성 발화(R-M11). 실제 기기 · 실제 아이 결과가 아니다.\n");
        md.append("- 운영 부품 그대로(판정 지시문 ").append(document.get("promptVersion")).append(" · 선택지 판 ")
                .append(document.get("optionSetVersion")).append(" · 확신 문턱). 운영 강도: ").append(document.get("operationalEffort"))
                .append(", 운영 live-budget ").append(liveBudget.toMillis()).append("ms, 이 도구의 시도 시한 ").append(document.get("attemptLimitMs"))
                .append("ms(운영이라면 시한 안이었는지를 따로 센다).\n");
        md.append("- 강도 ").append(efforts).append(" · 사례 ").append(cases.size()).append("개 × ").append(document.get("rounds"))
                .append("회 · 호출 ").append(document.get("calls")).append("회").append(Boolean.TRUE.equals(document.get("cappedByMaxCalls"))
                        ? "(호출 상한으로 멈춤)" : "").append(". 보고서에는 발화 원문을 쓰지 않는다(R-F14).\n\n");
        md.append("| 강도 | 호출 | 확정 | 시한 안 확정 | 지연 중앙 · 90% · 최대(ms) | 기대와 1순위 같음 | 시한 안 확정 + 기대 같음 | 첫 강도 다수와 같음 | 보류 사유 | 오류 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, Object> byEffort = (Map<String, Object>) ((Map<String, Object>) document.get("summary")).get("byEffort");
        for (String effort : efforts) {
            Map<String, Object> row = (Map<String, Object>) byEffort.get(effort);
            md.append("| ").append(effort).append(" | ").append(row.get("calls")).append(" | ").append(row.get("confirmed")).append(" | ")
                    .append(row.get("confirmedWithinLiveBudget")).append(" | ").append(row.get("latencyP50Ms")).append(" · ")
                    .append(row.get("latencyP90Ms")).append(" · ").append(row.get("latencyMaxMs")).append(" | ")
                    .append(row.get("firstMatchesExpected")).append(" / ").append(row.get("expectedCompared")).append(" | ")
                    .append(row.get("confirmedInBudgetMatchesExpected")).append(" / ").append(row.get("expectedCompared")).append(" | ")
                    .append(row.get("firstMatchesFirstEffortMajority")).append(" / ").append(row.get("majorityCompared")).append(" | ")
                    .append(row.get("abstainReasons")).append(" | ").append(row.get("errors")).append(" |\n");
        }
        md.append("\n## 사례별 1순위(회차 순, 괄호는 지연 초 · 시한 넘으면 *)\n\n| 사례 | 지점 | 기대 |");
        for (String effort : efforts) {
            md.append(' ').append(effort).append(" |");
        }
        md.append("\n|---|---|---|");
        efforts.forEach(effort -> md.append("---|"));
        md.append('\n');
        for (BenchCase item : cases) {
            md.append("| ").append(item.id()).append(" | ").append(item.point().name()).append(" | ")
                    .append(item.expected() == null ? "-" : item.expected()).append(" |");
            for (String effort : efforts) {
                StringBuilder cell = new StringBuilder();
                for (Attempt attempt : attempts) {
                    if (attempt.caseId().equals(item.id()) && attempt.effort().equals(effort)) {
                        if (!cell.isEmpty()) {
                            cell.append(", ");
                        }
                        String code = attempt.error() != null ? "ERR"
                                : attempt.first() != null ? attempt.first() : "보류:" + attempt.abstainReason();
                        if (attempt.first() != null && !"CONFIRMED".equals(attempt.kind())) {
                            code = code + "(보류)";
                        }
                        cell.append(code);
                        if (attempt.latencyMs() >= 0) {
                            cell.append(String.format(Locale.ROOT, "(%.1f%s)", attempt.latencyMs() / 1000.0,
                                    attempt.withinBudget() ? "" : "*"));
                        }
                    }
                }
                md.append(' ').append(cell).append(" |");
            }
            md.append('\n');
        }
        return md.toString();
    }

    private Map<String, Object> attemptMap(Attempt attempt) {
        return ordered("effort", attempt.effort(), "case", attempt.caseId(), "round", attempt.round(), "kind", attempt.kind(), "first",
                attempt.first(), "firstProbability", attempt.firstProbability(), "second", attempt.second(), "abstainReason",
                attempt.abstainReason(), "latencyMs", attempt.latencyMs(), "model", attempt.model(), "withinLiveBudget",
                attempt.withinBudget(), "error", attempt.error());
    }

    // ---- 도우미 ----

    private static String property(List<PropertySource<?>> sources, String key) throws IOException {
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().strip();
            }
        }
        throw new IOException(CONFIG + "에 " + key + "가 없다");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int bounded(String value, int min, int max, String name) {
        int parsed = Integer.parseInt(value.strip());
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(name + "는 " + min + " ~ " + max + "이다");
        }
        return parsed;
    }

    private static String safe(String model) {
        return model != null && model.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$") ? model : null;
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
