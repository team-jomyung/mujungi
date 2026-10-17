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
@Tag("u13")
class JevLiveTaskIsolationTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();
    private static final String FAKE_KEY = "fake-jev-live-key-not-real";
    private static final String FAKE_URL = "https://jev-live.invalid/base";
    private static final String FAKE_MODEL = "jev-live-model-1";
    private static final JevLiveProbe.ClientFactory NEVER = (properties, key, budget, clock) -> {
        throw new AssertionError("키가 없으면 어댑터를 만들지 않는다");
    };

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    /** 표본마다 정해 둔 판정을 돌려주는 가짜 어댑터. 받은 요청의 후보 문장을 모아 보고서와 대조한다. */
    private static final class FakeClient implements GuardrailClient {

        final List<String> candidates = new ArrayList<>();
        final List<String> keysSeen = new ArrayList<>();
        private final Supplier<String> key;
        private final boolean failSecond;

        FakeClient(Supplier<String> key, boolean failSecond) {
            this.key = key;
            this.failSecond = failSecond;
        }

        @Override
        public GuardrailMode mode() {
            return GuardrailMode.REAL;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public java.util.concurrent.CompletionStage<GuardrailVerdict> check(GuardrailRequest request, java.time.Instant deadline) {
            candidates.add(request.candidate());
            keysSeen.add(key.get());
            GuardrailVerdict verdict;
            if (failSecond && candidates.size() == 2) {
                verdict = GuardrailVerdict.failed(GuardrailVerdict.Failure.TIMEOUT, request.policyVersion(), 800);
            } else if (candidates.size() == 1) {
                verdict = GuardrailVerdict.allow("OK", request.policyVersion(), 210, List.of(), List.of());
            } else {
                verdict = GuardrailVerdict.block("DIAGNOSIS_CLAIM", request.policyVersion(), 340, List.of("DIAGNOSIS"), List.of());
            }
            return java.util.concurrent.CompletableFuture.completedFuture(verdict);
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
        GuardrailClient client = JevLiveProbe.operationalClient(new GuardrailProperties("REAL", FAKE_URL, FAKE_MODEL), () -> FAKE_KEY,
                budget, new MutableClock());
        assertThat(client).isInstanceOf(JevGuardrailClient.class);
        assertThat(client.mode()).isEqualTo(GuardrailMode.REAL);
        assertThat(client.available()).as("STRICT 규칙은 허용 호스트 밖 주소를 받지 않는다").isFalse();
        assertThat(JevLiveProbe.SAMPLES).hasSizeGreaterThanOrEqualTo(3).anyMatch(s -> s.expect() == JevLiveProbe.Expect.ALLOW)
                .anyMatch(s -> s.expect() == JevLiveProbe.Expect.NOT_ALLOW);
    }

    // ---- 실행 · 보고서 ----

    @Test
    void aRunWithAFakeClientSendsEverySampleOnceAndTheReportHasNoCandidateTextKeyOrAddress(@TempDir Path dir) throws IOException {
        List<FakeClient> clients = new ArrayList<>();
        JevLiveProbe.ClientFactory factory = (properties, key, budget, clock) -> {
            assertThat(properties.baseUrl()).isEqualTo(FAKE_URL);
            assertThat(properties.model()).isEqualTo(FAKE_MODEL);
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
        assertThat(clients.getFirst().keysSeen).as("키는 호출 때마다 env에서 읽는다").containsOnly(FAKE_KEY);
        String written = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(written).startsWith("outcome=REACHED evidence=REAL_API_SYNTHETIC")
                .contains("| 표본 | 기대 | 판정 | 실패 종류 | 걸린 분류 | 사유 코드 | 지연 ms |", "| S1-benign-offer | ALLOW | ALLOW | - | - | OK | 210 |",
                        "| S2-diagnosis | NOT_ALLOW | BLOCK | - | DIAGNOSIS | DIAGNOSIS_CLAIM | 340 |", "판정 받음: 5/5", "model=" + FAKE_MODEL)
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
                .contains("| S2-diagnosis | NOT_ALLOW | FAILED | TIMEOUT | - | TIMEOUT | 800 |", "판정 받음: 4/5");
        assertThat(JevLiveProbe.oneLine(result)).isEqualTo("jevLive outcome=NOT_REACHED reached=4/5");
    }
}
