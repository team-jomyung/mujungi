package kr.mujoong.realtime.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 제브 실호출 작업 분리 시험 (U13 Step 15b · R-F1 · R-F3). 기본 test에서 돌고 제브를 부르지 않는다(키는 가짜 문자열, 호출부는 가짜).
 *
 * <p>작업 그래프 자체(check · test → jevLiveTest 의존 없음)는 루트 {@code verifyJevLiveIsolation}이 check마다 확인한다. 이 시험은 그 빌드 규칙이
 * 파일에 못 박혀 있는지, 기본 test JVM에 제브 키가 없는지, 키가 없으면 어댑터를 만들지 않는지, 보고서에 후보 문장 원문 · 키 · 주소가 없는지를 본다.
 * 합격은 테스트 대역 증거다(R-M11).
 */
@Tag("jev-guardrail")
class JevLiveTaskIsolationTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();
    private static final String FAKE_KEY = "fake-jev-live-key-not-real";
    private static final String FAKE_URL = "https://jev-live.invalid/base";
    private static final String FAKE_MODEL = "jev-live-model-1";
    private static final int SAMPLES = JevLiveProbe.SAMPLES.size();
    private static final JevLiveProbe.ClientFactory NEVER = (properties, key, budget, clock) -> {
        throw new AssertionError("키가 없으면 어댑터를 만들지 않는다");
    };

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    /** 표본마다 정해 둔 판정을 돌려주는 가짜 어댑터. 받은 요청의 후보 문장을 모아 보고서와 대조한다. */
    private static final class FakeClient implements JevLiveProbe.Inspector {

        final List<String> candidates = new ArrayList<>();
        final List<GuardrailRequest> requests = new ArrayList<>();
        final List<String> keysSeen = new ArrayList<>();
        final List<GuardrailProperties> propertiesSeen = new ArrayList<>();
        private final Supplier<String> key;
        private final boolean failSecond;

        FakeClient(Supplier<String> key, boolean failSecond) {
            this.key = key;
            this.failSecond = failSecond;
        }

        @Override
        public java.util.concurrent.CompletionStage<JevGuardrailClient.Inspection> inspect(GuardrailRequest request,
                java.time.Instant deadline) {
            candidates.add(request.candidate());
            requests.add(request);
            keysSeen.add(key.get());
            JevGuardrailClient.Inspection inspection;
            if (failSecond && candidates.size() == 2) {
                inspection = new JevGuardrailClient.Inspection(
                        GuardrailVerdict.failed(GuardrailVerdict.Failure.TIMEOUT, request.policyVersion(), 800), null, null, null);
            } else if (candidates.size() == 1) {
                inspection = new JevGuardrailClient.Inspection(
                        GuardrailVerdict.allow("PASS", request.policyVersion(), 210, List.of(), List.of()), "PASS", 0.8123, FAKE_MODEL);
            } else {
                inspection = new JevGuardrailClient.Inspection(
                        GuardrailVerdict.block("DIAGNOSIS", request.policyVersion(), 340, List.of("DIAGNOSIS"), List.of()),
                        "DIAGNOSIS", 0.0456, FAKE_MODEL);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(inspection);
        }
    }

    // ---- 격리 규칙 ----

    @Test
    void buildRulesKeepJevLiveOutOfDefaultTestAndCheck() throws IOException {
        String conventions = read("backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts");
        assertThat(conventions).contains("val jevLiveTag = \"jev-live\"", "excludeTags(jevLiveTag)", "environment.remove(\"JEV_API_KEY\")");

        String realtime = read("backend/realtime-activity/build.gradle.kts");
        assertThat(realtime).contains("tasks.register<Test>(\"jevLiveTest\")", "setIncludeTags(setOf(jevLiveTag))", "jev-live/report.txt");
        assertThat(realtime).doesNotContain("dependsOn(\"jevLiveTest\")", "dependsOn(tasks.named(\"jevLiveTest\"))");

        String root = read("backend/build.gradle.kts");
        assertThat(root).contains("tasks.register(\"verifyJevLiveIsolation\")", "dependsOn(verifyJevLiveIsolation)", "it.name == \"jevLiveTest\"",
                "jevLiveTag !in testOptions.excludeTags", "liveOptions.includeTags != setOf(jevLiveTag)");
    }

    @Test
    void defaultTestJvmDoesNotSeeTheJevKey() {
        assumeTrue("true".equals(System.getProperty("mujoong.test.liveIsolated")), "Gradle 기본 test 작업 밖에서 돈다");
        assertThat(System.getenv(GuardrailStartupRules.API_KEY_ENV)).as("기본 test JVM에는 JEV_API_KEY가 없다").isNull();
    }

    // ---- 키 · 설정이 없으면 부르지 않는다 ----

    @Test
    void withoutAKeyTheProbeReportsSkippedAndNeverBuildsAClient(@TempDir Path dir) throws IOException {
        Path report = dir.resolve("jev-live/report.txt");

        JevLiveProbe.Result result = new JevLiveProbe(Map.<String, String>of()::get, report, NEVER, new MutableClock()).run();

        assertThat(result.outcome()).isEqualTo(JevLiveProbe.Outcome.SKIPPED_NO_KEY);
        assertThat(Files.readString(report, StandardCharsets.UTF_8)).startsWith("outcome=SKIPPED_NO_KEY evidence=NONE");
        assertThat(new JevLiveProbe(Map.of(GuardrailStartupRules.API_KEY_ENV, "  ")::get, dir.resolve("b.txt"), NEVER, new MutableClock())
                .run().outcome()).isEqualTo(JevLiveProbe.Outcome.SKIPPED_NO_KEY);
    }

    @Test
    void withAKeyButNoAddressOrModelTheProbeSkipsWithoutCalling(@TempDir Path dir) throws IOException {
        Map<String, String> noUrl = Map.of(GuardrailStartupRules.API_KEY_ENV, FAKE_KEY, JevLiveProbe.MODEL_ENV, FAKE_MODEL);
        Map<String, String> noModel = Map.of(GuardrailStartupRules.API_KEY_ENV, FAKE_KEY, JevLiveProbe.BASE_URL_ENV, FAKE_URL);

        assertThat(new JevLiveProbe(noUrl::get, dir.resolve("a.txt"), NEVER, new MutableClock()).run().outcome())
                .isEqualTo(JevLiveProbe.Outcome.SKIPPED_NO_CONFIG);
        assertThat(new JevLiveProbe(noModel::get, dir.resolve("b.txt"), NEVER, new MutableClock()).run().outcome())
                .isEqualTo(JevLiveProbe.Outcome.SKIPPED_NO_CONFIG);
        assertThat(Files.readString(dir.resolve("a.txt"), StandardCharsets.UTF_8)).contains("outcome=SKIPPED_NO_CONFIG evidence=NONE");
    }

    // ---- 설정 · 구성 ----

    @Test
    void theOperationalBudgetIsReadFromApplicationYmlAndTheOperationalClientUsesTheStrictHostRule() throws IOException {
        Duration budget = JevLiveProbe.operationalBudget();

        assertThat(budget).isPositive();
        GuardrailClient client = JevLiveProbe.operationalClient(new GuardrailProperties("REAL", FAKE_URL, FAKE_MODEL, 0.5), () -> FAKE_KEY,
                budget, new MutableClock());
        assertThat(client).isInstanceOf(JevGuardrailClient.class);
        assertThat(client.mode()).isEqualTo(GuardrailMode.REAL);
        assertThat(client.available()).as("STRICT 규칙은 허용 호스트 밖 주소를 받지 않는다").isFalse();
        assertThat(JevLiveProbe.SAMPLES).hasSizeGreaterThanOrEqualTo(3).anyMatch(s -> s.expect() == JevLiveProbe.Expect.ALLOW)
                .anyMatch(s -> s.expect() == JevLiveProbe.Expect.NOT_ALLOW);
    }

    @Test
    void theSamplesCoverEveryGuardrailModeAndEachPracticeModeHasAllowBlockAndBorderlineCases() {
        assertThat(JevLiveProbe.SAMPLES).extracting(JevLiveProbe.Sample::mode).containsAll(List.of(GuardrailRequest.Mode.values()));
        assertThat(JevLiveProbe.SAMPLES).extracting(JevLiveProbe.Sample::id).doesNotHaveDuplicates();
        for (GuardrailRequest.Mode mode : List.of(GuardrailRequest.Mode.PRACTICE_PEER, GuardrailRequest.Mode.PRACTICE_COACH)) {
            List<JevLiveProbe.Sample> practice = JevLiveProbe.SAMPLES.stream().filter(s -> s.mode() == mode).toList();
            assertThat(practice).as(mode + " 표본").hasSizeGreaterThanOrEqualTo(4)
                    .anyMatch(s -> s.expect() == JevLiveProbe.Expect.ALLOW)
                    .anyMatch(s -> s.expect() == JevLiveProbe.Expect.NOT_ALLOW)
                    .anyMatch(s -> s.expect() == JevLiveProbe.Expect.BORDERLINE);
            assertThat(practice).as(mode + " 맥락은 연습 대화(아이 말 뒤 친구 되물음)").allSatisfy(s -> assertThat(s.context())
                    .extracting(GuardrailRequest.ContextTurn::speaker).contains(GuardrailRequest.Speaker.CHILD, GuardrailRequest.Speaker.PARTNER)
                    .hasSizeLessThanOrEqualTo(GuardrailRequest.CONTEXT_MAX_TURNS));
        }
        // 기존 자유대화 표본 다섯은 그대로 둔다(10/18 실호출과 비교할 수 있게)
        assertThat(JevLiveProbe.SAMPLES.stream().filter(s -> s.id().startsWith("S")).map(JevLiveProbe.Sample::mode))
                .hasSize(5).containsOnly(GuardrailRequest.Mode.FREE_TALK);
    }

    @Test
    void theOperationalThresholdIsTheTemporaryDefaultFromApplicationYmlAndAnEnvOverrideMustStayInRange() throws IOException {
        assertThat(JevLiveProbe.operationalThreshold(Map.<String, String>of()::get)).as("application.yml 기본값(임시 시험값)").isEqualTo(0.5);
        assertThat(JevLiveProbe.operationalThreshold(Map.of(JevLiveProbe.THRESHOLD_ENV, " 0.75 ")::get)).isEqualTo(0.75);
        assertThat(JevLiveProbe.operationalThreshold(Map.of(JevLiveProbe.THRESHOLD_ENV, "  ")::get)).as("빈 값은 기본값").isEqualTo(0.5);
        for (String bad : List.of("0", "1.5", "abc", "-1")) {
            Map<String, String> env = Map.of(JevLiveProbe.THRESHOLD_ENV, bad);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> JevLiveProbe.operationalThreshold(env::get))
                    .isInstanceOf(IOException.class).hasMessageNotContaining("(" + bad + ")");
        }
    }

    // ---- 실행 · 보고서 ----

    @Test
    void aRunWithAFakeClientSendsEverySampleOnceAndTheReportHasNoCandidateTextKeyOrAddress(@TempDir Path dir) throws IOException {
        List<FakeClient> clients = new ArrayList<>();
        JevLiveProbe.ClientFactory factory = (properties, key, budget, clock) -> {
            assertThat(properties.baseUrl()).isEqualTo(FAKE_URL);
            assertThat(properties.model()).isEqualTo(FAKE_MODEL);
            assertThat(properties.passThreshold()).as("문턱은 application.yml의 기본값(임시 시험값)").isEqualTo(0.5);
            FakeClient client = new FakeClient(key, false);
            clients.add(client);
            return client;
        };
        Map<String, String> env = Map.of(GuardrailStartupRules.API_KEY_ENV, FAKE_KEY, JevLiveProbe.BASE_URL_ENV, FAKE_URL,
                JevLiveProbe.MODEL_ENV, FAKE_MODEL);
        Path report = dir.resolve("report.txt");

        JevLiveProbe.Result result = new JevLiveProbe(env::get, report, factory, new MutableClock()).run();

        assertThat(result.outcome()).isEqualTo(JevLiveProbe.Outcome.REACHED);
        assertThat(clients).hasSize(1);
        assertThat(clients.getFirst().candidates).hasSameSizeAs(JevLiveProbe.SAMPLES).doesNotHaveDuplicates();
        assertThat(clients.getFirst().requests).extracting(GuardrailRequest::mode)
                .as("표본마다 운영과 같은 검사 모드로 보낸다").isEqualTo(JevLiveProbe.SAMPLES.stream().map(JevLiveProbe.Sample::mode).toList());
        assertThat(clients.getFirst().requests).extracting(GuardrailRequest::recentContext)
                .isEqualTo(JevLiveProbe.SAMPLES.stream().map(JevLiveProbe.Sample::context).toList());
        assertThat(clients.getFirst().keysSeen).as("키는 호출 때마다 env에서 읽는다").containsOnly(FAKE_KEY);
        String written = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(written).startsWith("outcome=REACHED evidence=REAL_API_SYNTHETIC")
                .contains("통과 문턱=0.5000 (임시 시험값, AI 성능 시험으로 확정",
                        "| 표본 | 모드 | 기대 | 판정 | 실패 종류 | 고른 라벨 | PASS 확률 | 사유 코드 | 지연 ms |",
                        "| S1-benign-offer | FREE_TALK | ALLOW | ALLOW | - | PASS | 0.8123 | PASS | 210 |",
                        "| S2-diagnosis | FREE_TALK | NOT_ALLOW | BLOCK | - | DIAGNOSIS | 0.0456 | DIAGNOSIS | 340 |",
                        "| P1-peer-reask | PRACTICE_PEER | ALLOW | BLOCK | - | DIAGNOSIS | 0.0456 | DIAGNOSIS | 340 |",
                        "판정 받음: " + SAMPLES + "/" + SAMPLES, "model=" + FAKE_MODEL, "응답 model=" + FAKE_MODEL,
                        "| 모드 | 기대 | 표본 | 최솟값 | 최댓값 |", "| 문턱 | 모드 | 허용 기대인데 막힘 | 차단 기대인데 통과 |")
                .doesNotContain(FAKE_KEY, FAKE_URL, "jev-live.invalid");
        for (String candidate : clients.getFirst().candidates) {
            assertThat(written).as("후보 문장 원문은 보고서에 없다(R-F14)").doesNotContain(candidate);
        }
    }

    @Test
    void aFailedSampleMakesTheRunNotReachedAndIsCountedByKind(@TempDir Path dir) throws IOException {
        Map<String, String> env = Map.of(GuardrailStartupRules.API_KEY_ENV, FAKE_KEY, JevLiveProbe.BASE_URL_ENV, FAKE_URL,
                JevLiveProbe.MODEL_ENV, FAKE_MODEL);
        MujoongClock clock = new MutableClock();
        Path report = dir.resolve("report.txt");

        JevLiveProbe.Result result = new JevLiveProbe(env::get, report, (p, key, b, c) -> new FakeClient(key, true), clock).run();

        assertThat(result.outcome()).isEqualTo(JevLiveProbe.Outcome.NOT_REACHED);
        String written = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(written).startsWith("outcome=NOT_REACHED evidence=REAL_API_SYNTHETIC")
                .contains("| S2-diagnosis | FREE_TALK | NOT_ALLOW | FAILED | TIMEOUT | - | - | TIMEOUT | 800 |",
                        "판정 받음: " + (SAMPLES - 1) + "/" + SAMPLES);
        assertThat(JevLiveProbe.oneLine(result)).isEqualTo("jevLive outcome=NOT_REACHED reached=" + (SAMPLES - 1) + "/" + SAMPLES);
    }

    /** 표본 기대별로 PASS 확률을 정해 둔 가짜 어댑터(늘 PASS가 1순위). 문턱별 다시 계산을 확인한다. */
    private static JevLiveProbe.Inspector passingWith(Map<JevLiveProbe.Expect, Double> byExpect) {
        Map<String, JevLiveProbe.Expect> expects = new java.util.HashMap<>();
        JevLiveProbe.SAMPLES.forEach(sample -> expects.put(sample.candidate(), sample.expect()));
        return (request, deadline) -> {
            double pass = byExpect.get(expects.get(request.candidate()));
            GuardrailVerdict verdict = pass >= 0.5 ? GuardrailVerdict.allow("PASS", request.policyVersion(), 100, List.of(), List.of())
                    : GuardrailVerdict.review(JevGuardrailClient.REASON_BELOW_THRESHOLD, request.policyVersion(), 100, List.of(), List.of());
            return java.util.concurrent.CompletableFuture.completedFuture(new JevGuardrailClient.Inspection(verdict, "PASS", pass, FAKE_MODEL));
        };
    }

    private static long count(JevLiveProbe.Expect expect, GuardrailRequest.Mode mode) {
        return JevLiveProbe.SAMPLES.stream().filter(s -> s.expect() == expect && s.mode() == mode).count();
    }

    @Test
    void theReportRecomputesMisjudgementsAtEachCandidateThresholdFromTheSameAnswers(@TempDir Path dir) throws IOException {
        Map<String, String> env = Map.of(GuardrailStartupRules.API_KEY_ENV, FAKE_KEY, JevLiveProbe.BASE_URL_ENV, FAKE_URL,
                JevLiveProbe.MODEL_ENV, FAKE_MODEL);
        Map<JevLiveProbe.Expect, Double> probabilities = Map.of(JevLiveProbe.Expect.ALLOW, 0.55, JevLiveProbe.Expect.NOT_ALLOW, 0.45,
                JevLiveProbe.Expect.BORDERLINE, 0.5);
        Path report = dir.resolve("report.txt");

        JevLiveProbe.Result result = new JevLiveProbe(env::get, report, (p, key, b, c) -> passingWith(probabilities), new MutableClock()).run();

        assertThat(result.outcome()).isEqualTo(JevLiveProbe.Outcome.REACHED);
        String written = Files.readString(report, StandardCharsets.UTF_8);
        GuardrailRequest.Mode peer = GuardrailRequest.Mode.PRACTICE_PEER;
        GuardrailRequest.Mode coach = GuardrailRequest.Mode.PRACTICE_COACH;
        // 0.4: 차단 기대(0.45)가 통과한다. 0.5: 둘 다 맞다. 0.6: 허용 기대(0.55)가 막힌다. 경계 사례(0.5)는 세지 않는다
        assertThat(written).contains(
                "| 0.4000 | PRACTICE_PEER | 0 | " + count(JevLiveProbe.Expect.NOT_ALLOW, peer) + " |",
                "| 0.5000 | PRACTICE_PEER | 0 | 0 |",
                "| 0.6000 | PRACTICE_PEER | " + count(JevLiveProbe.Expect.ALLOW, peer) + " | 0 |",
                "| 0.4000 | PRACTICE_COACH | 0 | " + count(JevLiveProbe.Expect.NOT_ALLOW, coach) + " |",
                "| 0.6000 | PRACTICE_COACH | " + count(JevLiveProbe.Expect.ALLOW, coach) + " | 0 |",
                "| PRACTICE_PEER | BORDERLINE | " + count(JevLiveProbe.Expect.BORDERLINE, peer) + "/" + count(JevLiveProbe.Expect.BORDERLINE, peer)
                        + " | 0.5000 | 0.5000 |",
                "| PRACTICE_COACH | ALLOW | " + count(JevLiveProbe.Expect.ALLOW, coach) + "/" + count(JevLiveProbe.Expect.ALLOW, coach)
                        + " | 0.5500 | 0.5500 |");
        long decided = JevLiveProbe.SAMPLES.stream().filter(s -> s.expect() != JevLiveProbe.Expect.BORDERLINE).count();
        assertThat(written).contains("기대와 같음(관측, 합격 조건 아님): " + decided + "/" + decided + " (경계 사례 제외)");
        for (JevLiveProbe.Sample sample : JevLiveProbe.SAMPLES) {
            assertThat(written).as("후보 문장 · 맥락 원문은 보고서에 없다(R-F14)").doesNotContain(sample.candidate());
            sample.context().forEach(turn -> assertThat(written).doesNotContain(turn.text()));
        }
    }
}
