package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.luna.LunaResponsesStub.Reply;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Luna 응답 여부 확인 도구 규칙 시험 (U11 Step 2 · 3 조사). 기본 test에서 돌고 공급자를 부르지 않는다(R-F1). 호출부는 가짜만 쓴다.
 *
 * <p>작업 그래프 자체(check · test → lunaReachTest 의존 없음)는 루트 {@code verifyLunaReachIsolation}이 check마다 확인한다. 이 시험은 빌드
 * 규칙 · 키 제거 · 키 없음 SKIPPED · env 파싱 · 요청 모양(스키마 통과 · 아이 이름 없음) · 요약 계산 · 보고서 형식을 본다.
 */
@Tag("judgment-turn")
class LunaReachIsolationTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();
    private static final JsonCodec JSON = JsonCodec.shared();
    private static final LunaReachProbe.CallerFactory NEVER = (properties, timeout, env, clock) -> {
        throw new AssertionError("키가 없으면 호출부를 만들지 않는다");
    };

    /** 시험용 고정 시계(호출부 가짜가 지연을 정한다). */
    private static final class FixedClock implements MujoongClock {

        @Override
        public Instant now() {
            return Instant.parse("2026-10-16T00:00:00Z");
        }

        @Override
        public long monotonicNanos() {
            return 0;
        }
    }

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static LunaReachProbe.Attempt ok(int index, long millis) {
        return new LunaReachProbe.Attempt(index, "OK", "gpt-6-luna", "통과", "END_CLEAR 0.81", "REST 0.12", millis);
    }

    // ---- 격리 규칙 ----

    @Test
    void buildRulesKeepLunaReachOutOfDefaultTestAndCheck() throws IOException {
        String conventions = read("backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts");
        assertThat(conventions).contains("val lunaReachTag = \"luna-reach\"", "excludeTags(lunaReachTag)",
                "environment.remove(\"OPENAI_API_KEY\")");

        String realtime = read("backend/realtime-activity/build.gradle.kts");
        assertThat(realtime).contains("tasks.register<Test>(\"lunaReachTest\")", "setIncludeTags(setOf(lunaReachTag))",
                "luna-reach/report.md");
        assertThat(realtime).doesNotContain("dependsOn(\"lunaReachTest\")", "dependsOn(tasks.named(\"lunaReachTest\"))");

        String root = read("backend/build.gradle.kts");
        assertThat(root).contains("tasks.register(\"verifyLunaReachIsolation\")", "dependsOn(verifyLunaReachIsolation)",
                "it.name == \"lunaReachTest\"", "lunaReachTag !in testOptions.excludeTags",
                "reachOptions.includeTags != setOf(lunaReachTag)");
    }

    @Test
    void defaultTestJvmDoesNotSeeTheLunaKey() {
        assumeTrue("true".equals(System.getProperty("mujoong.test.liveIsolated")), "Gradle 기본 test 작업 밖에서 돈다");
        assertThat(System.getenv(LunaTransport.API_KEY_ENV)).as("기본 test JVM에는 OPENAI_API_KEY가 없다").isNull();
    }

    @Test
    void withoutAKeyTheProbeReportsSkippedAndNeverBuildsACaller(@TempDir Path dir) throws IOException {
        Path report = dir.resolve("luna-reach/report.md");
        LunaReachProbe probe = new LunaReachProbe(Map.<String, String>of()::get, report, NEVER, new FixedClock());

        LunaReachProbe.Result result = probe.run();

        assertThat(result.outcome()).isEqualTo(LunaReachProbe.Outcome.SKIPPED_NO_KEY);
        assertThat(result.attempts()).isEmpty();
        assertThat(Files.readString(report, StandardCharsets.UTF_8).lines().findFirst())
                .hasValue("outcome=SKIPPED_NO_KEY evidence=NONE");
        assertThat(new LunaReachProbe(Map.of(LunaTransport.API_KEY_ENV, " ")::get, dir.resolve("b.md"), NEVER, new FixedClock())
                .run().outcome()).isEqualTo(LunaReachProbe.Outcome.SKIPPED_NO_KEY);
    }

    // ---- env ----

    @Test
    void timeoutAndCountEnvAreParsedWithDefaultsAndBounds() {
        assertThat(LunaReachProbe.timeoutSeconds(null)).isEqualTo(60);
        assertThat(LunaReachProbe.timeoutSeconds(" 90 ")).isEqualTo(90);
        assertThat(LunaReachProbe.count("")).as("사용량 한도 때문에 기본 2회").isEqualTo(2);
        assertThat(LunaReachProbe.count("3")).isEqualTo(3);
        assertThatThrownBy(() -> LunaReachProbe.timeoutSeconds("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LunaReachProbe.timeoutSeconds("601")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LunaReachProbe.timeoutSeconds("1m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LunaReachProbe.count("21")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 설정 · 요청 ----

    @Test
    void operationalConfigIsReadFromApplicationYmlAndPassesTheStrictHostRule() throws IOException {
        LunaReachProbe probe = new LunaReachProbe(Map.<String, String>of()::get, Path.of("unused"), NEVER, new FixedClock());

        LunaProperties properties = probe.properties();

        assertThat(LunaHosts.check(properties.baseUrl(), LunaHosts.modeFor())).isEmpty();
        assertThat(properties.model()).isEqualTo(LunaProperties.DEFAULT_MODEL);
        assertThat(properties.options(LunaPurpose.JUDGMENT_LIVE).reasoningEffort()).isNotBlank();
    }

    @Test
    void theSyntheticJ6RequestUsesTheResourceOptionsAndPassesTheRequestSchema() throws IOException {
        LunaReachProbe probe = new LunaReachProbe(Map.<String, String>of()::get, Path.of("unused"), NEVER, new FixedClock());

        LunaReachProbe.OptionSet set = probe.j6Options();
        Map<String, Object> input = LunaReachProbe.j6Input(set);

        assertThat(set.version()).isEqualTo("judgment-v1");
        assertThat(set.options()).containsExactly("END_CLEAR", "UNCLEAR", "NOT_END", "REST");
        assertThat(input.get("judgmentPoint")).isEqualTo("J6");
        assertThat(input.get("criteria")).isNull();
        assertThat(LunaSchemas.loadDefault(JSON).isValidRequest(LunaPurpose.JUDGMENT_LIVE, JSON.write(input))).isTrue();
        assertThat(JSON.write(input)).doesNotContain("displayName", "childName", "하늘");
    }

    @Test
    void aRunWithAFakeCallerUsesTheGivenTimeoutAndReportsEachAttempt(@TempDir Path dir) throws IOException {
        AtomicReference<Duration> timeoutSeen = new AtomicReference<>();
        List<LunaRequest> requests = new ArrayList<>();
        String output = "{\"first\":{\"option\":\"END_CLEAR\",\"probability\":0.8123},\"second\":{\"option\":\"WAT\",\"probability\":0.1}}";
        Map<String, Long> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 300L);
        usage.put("output_tokens", 40L);
        LunaReachProbe.CallerFactory fake = (properties, timeout, env, clock) -> {
            timeoutSeen.set(timeout);
            return (request, limit) -> {
                requests.add(request);
                return requests.size() == 2
                        ? new LunaReachProbe.CallResult("TIMEOUT", null, null, 30_000, null)
                        : new LunaReachProbe.CallResult("OK", "gpt-6-luna-x", output, 1500L * requests.size(), usage);
            };
        };
        Map<String, String> env = Map.of(LunaTransport.API_KEY_ENV, "fake-not-a-key", LunaReachProbe.TIMEOUT_ENV, "30",
                LunaReachProbe.COUNT_ENV, "3");
        Path report = dir.resolve("report.md");

        LunaReachProbe.Result result = new LunaReachProbe(env::get, report, fake, new FixedClock()).run();

        assertThat(timeoutSeen.get()).isEqualTo(Duration.ofSeconds(30));
        assertThat(requests).hasSize(3).allSatisfy(request -> assertThat(request.deadline())
                .isEqualTo(Instant.parse("2026-10-16T00:00:30Z")));
        assertThat(result.outcome()).isEqualTo(LunaReachProbe.Outcome.REACHED);
        assertThat(result.attempts()).extracting(LunaReachProbe.Attempt::reason).containsExactly("OK", "TIMEOUT", "OK");
        assertThat(result.attempts().getFirst().first()).isEqualTo("END_CLEAR 0.81");
        assertThat(result.attempts().getFirst().second()).isEqualTo("WAT(선택지 밖) 0.10");
        assertThat(result.summary().usageTotals()).containsEntry("input_tokens", 600L).containsEntry("output_tokens", 80L);
        assertThat(result.summary().usageUnknown()).isEqualTo(1);
        String written = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(written).startsWith("outcome=REACHED evidence=REAL_API_SYNTHETIC")
                .contains("| 회차 | 결과 사유 | 응답 모델 | 스키마 통과 | 1순위 | 2순위 | 지연 ms | 사용량(토큰) |",
                        "| 2 | TIMEOUT | - | - | - | - | 30000 | 모름 |", "input_tokens=300 output_tokens=40 |",
                        "사용량 합계(받은 회차): input_tokens=600 output_tokens=80 · 모름 1회", LunaReachProbe.PRICE_NOTE)
                .doesNotContain("fake-not-a-key", LunaReachProbe.CHILD_UTTERANCE, "\"probability\"", "https://", "$", "USD");
    }

    @Test
    void usageIsReadFromNumericFieldsAndIsUnknownWithoutABody() {
        String body = "{\"status\":\"completed\",\"usage\":{\"input_tokens\":312,\"input_tokens_details\":{\"cached_tokens\":0},"
                + "\"output_tokens\":58,\"output_tokens_details\":{\"reasoning_tokens\":40},\"total_tokens\":370,\"note\":\"x\"}}";

        Map<String, Long> usage = LunaReachProbe.usageOf(JSON, body);

        assertThat(usage).containsExactly(Map.entry("input_tokens", 312L), Map.entry("input_tokens_details.cached_tokens", 0L),
                Map.entry("output_tokens", 58L), Map.entry("output_tokens_details.reasoning_tokens", 40L),
                Map.entry("total_tokens", 370L));
        assertThat(LunaReachProbe.usageOf(JSON, "{\"error\":{}}")).isNull();
        assertThat(LunaReachProbe.usageOf(JSON, "<html>")).isNull();
        assertThat(LunaReachProbe.usageText(null)).isEqualTo("모름");
    }

    @Test
    void theProbeClassifiesEveryReplyLikeTheOperationalClientAndOnlyOncePerCall() throws Exception {
        LunaReachProbe.OptionSet set = new LunaReachProbe(Map.<String, String>of()::get, Path.of("unused"), NEVER, new FixedClock())
                .j6Options();
        String valid = "{\"first\":{\"option\":\"END_CLEAR\",\"probability\":0.7},\"second\":{\"option\":\"REST\",\"probability\":0.2}}";
        List<Reply> replies = List.of(Reply.ok("gpt-6-luna", valid), Reply.status(500), Reply.rateLimited(1), Reply.status(401),
                Reply.status(400), Reply.incomplete("gpt-6-luna"), Reply.emptyOutput("gpt-6-luna"), Reply.refusal("gpt-6-luna"),
                Reply.noModel(valid), Reply.ok("gpt-6-luna", "{\"first\":{\"option\":\"END_CLEAR\",\"probability\":2}}"),
                Reply.json(200, "<html>"), Reply.json(200, "{\"status\":\"cancelled\",\"model\":\"m\"}"),
                Reply.redirect("https://x.invalid/"));
        MutableClock clock = new MutableClock();
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            RealtimeLunaClient operational = RealtimeLunaTestFixtures.client(stub.baseUrl(), Duration.ofSeconds(5),
                    TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory()), clock);
            LunaReachProbe.OperationalCaller probe = new LunaReachProbe.OperationalCaller(
                    RealtimeLunaTestFixtures.properties(stub.baseUrl()),
                    RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST), clock);
            for (Reply reply : replies) {
                LunaRequest request = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, LunaReachProbe.PROMPT_VERSION,
                        LunaReachProbe.INSTRUCTIONS, LunaReachProbe.j6Input(set), Map.of(), clock.now().plusSeconds(5));
                stub.respond(reply);
                String expected;
                try {
                    operational.call(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
                    expected = "OK";
                } catch (ExecutionException failure) {
                    expected = ((LunaException) failure.getCause()).reason().name();
                }
                int before = stub.requestCount();
                stub.respond(reply);

                LunaReachProbe.CallResult actual = probe.call(request, Duration.ofSeconds(5));

                assertThat(actual.reason()).as("대본 %s", reply.status()).isEqualTo(expected);
                assertThat(stub.requestCount() - before).as("호출 1회").isEqualTo(1);
            }
            assertThat(stub.requests().getLast().body()).isEqualTo(stub.requests().get(stub.requestCount() - 2).body());
            probe.close();
        }
    }

    // ---- 요약 · 보고서 ----

    @Test
    void theSummaryUsesOkLatenciesAndWholeRunRatios() {
        List<LunaReachProbe.Attempt> attempts = List.of(ok(1, 1800), ok(2, 2500), ok(3, 12_000), ok(4, 900),
                new LunaReachProbe.Attempt(5, "AUTH_FAILED", "-", "-", "-", "-", 300));

        LunaReachProbe.Summary summary = LunaReachProbe.summarize(attempts);

        assertThat(summary.attempts()).isEqualTo(5);
        assertThat(summary.ok()).isEqualTo(4);
        assertThat(summary.medianMillis()).isEqualTo((1800 + 2500) / 2);
        assertThat(summary.maxMillis()).isEqualTo(12_000);
        assertThat(summary.withinBudget()).isEqualTo(3);
        assertThat(summary.withinTenSeconds()).isEqualTo(3);
        assertThat(LunaReachProbe.percent(2, 5)).isEqualTo("40% (2/5)");
        assertThat(LunaReachProbe.summarize(List.of()).medianMillis()).isNull();
        assertThat(LunaReachProbe.summarize(List.of(ok(1, 700), ok(2, 300), ok(3, 500))).medianMillis()).isEqualTo(500);
    }

    @Test
    void theReportHasTheOutcomeFirstThenTheTableAndSummary() {
        List<LunaReachProbe.Attempt> attempts = List.of(new LunaReachProbe.Attempt(1, "TIMEOUT", "-", "-", "-", "-", 60_000),
                new LunaReachProbe.Attempt(2, "SCHEMA_INVALID", "-", "불통과", "-", "-", 4_000));
        LunaReachProbe.Result result = new LunaReachProbe.Result(LunaReachProbe.Outcome.NOT_REACHED, 60, attempts,
                LunaReachProbe.summarize(attempts));

        String report = LunaReachProbe.render(result, "2026-10-16T00:00:00Z");

        List<String> lines = report.lines().toList();
        assertThat(lines.getFirst()).isEqualTo("outcome=NOT_REACHED evidence=REAL_API_SYNTHETIC");
        assertThat(lines.stream().filter(line -> line.startsWith("| ") && !line.startsWith("| 회차")).toList()).hasSize(2)
                .allSatisfy(line -> assertThat(line.split("\\|", -1)).as("8칸(사용량 포함)").hasSize(10));
        assertThat(report).contains("| 2 | SCHEMA_INVALID | - | 불통과 |", "| 60000 | 모름 |", "성공(OK): 0/2", "중앙값 - · 최대 -",
                "사용량 합계(받은 회차): - · 모름 2회", LunaReachProbe.PRICE_NOTE,
                "4초 이하(live-budget 안, 전체 회차 기준): 0% (0/2)", "시도 시한: 60초");
    }
}
