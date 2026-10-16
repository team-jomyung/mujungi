package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GPT-Live 격리 실호출 규칙 시험 (U11 FR9.1 · BR9.1 · 기능 설계 W7). 기본 test에서 돌고 공급자를 부르지 않는다(R-F1).
 *
 * <p>작업 그래프 자체(check · test → gptLiveTest 의존 없음)는 루트 {@code verifyGptLiveIsolation}이 check마다 확인한다. 이 시험은 그 확인과
 * 빌드 규칙이 제자리에 있는지, 기본 test JVM에 키가 없는지, 키가 없으면 연결 없이 SKIPPED_NO_KEY인지, 합성 입력과 주소 규칙을 본다.
 */
@Tag("u11")
class GptLiveIsolationTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();

    private static final GptLiveProbe.Opener NEVER_OPEN = (url, key, tap) -> {
        throw new AssertionError("키가 없으면 연결하지 않는다");
    };

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    @Test
    void buildRulesKeepGptLiveOutOfDefaultTestAndCheck() throws IOException {
        String conventions = read("backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts");
        assertThat(conventions).contains("val gptLiveTag = \"gpt-live\"", "excludeTags(gptLiveTag)",
                "environment.remove(\"PROVIDER_API_KEY\")");

        String realtime = read("backend/realtime-activity/build.gradle.kts");
        assertThat(realtime).contains("tasks.register<Test>(\"gptLiveTest\")", "setIncludeTags(setOf(gptLiveTag))",
                "isEnabled = false", "setFinalizedBy(emptyList<Any>())");
        assertThat(realtime).as("check · build가 gptLiveTest에 의존하지 않는다")
                .doesNotContain("dependsOn(\"gptLiveTest\")", "dependsOn(tasks.named(\"gptLiveTest\"))", "finalizedBy(\"gptLiveTest\")");

        String root = read("backend/build.gradle.kts");
        assertThat(root).contains("tasks.register(\"verifyGptLiveIsolation\")", "dependsOn(verifyGptLiveIsolation)",
                "it.name == \"gptLiveTest\"", "gptLiveTag !in testOptions.excludeTags",
                "test.environment.containsKey(\"PROVIDER_API_KEY\")", "liveOptions.includeTags != setOf(gptLiveTag)");
    }

    @Test
    void defaultTestJvmDoesNotSeeTheProviderKey() {
        assumeTrue("true".equals(System.getProperty("mujoong.test.liveIsolated")), "Gradle 기본 test 작업 밖에서 돈다");
        assertThat(System.getenv(ProviderClient.API_KEY)).as("기본 test JVM에는 PROVIDER_API_KEY가 없다").isNull();
    }

    @Test
    void withoutAKeyTheProbeReportsSkippedAndNeverConnects(@TempDir Path dir) throws IOException {
        Path report = dir.resolve("gpt-live/report.md");
        GptLiveProbe probe = new GptLiveProbe(Map.<String, String>of()::get, REPO_ROOT, report, NEVER_OPEN);

        GptLiveProbe.Result result = probe.run();

        assertThat(result.outcome()).isEqualTo(GptLiveProbe.Outcome.SKIPPED_NO_KEY);
        assertThat(result.rows()).hasSize(5).allSatisfy(row -> assertThat(row.verdict()).isEqualTo(GptLiveProbe.Verdict.SKIPPED_NO_KEY));
        assertThat(GptLiveProbe.anyVerdict(result, GptLiveProbe.Verdict.PASS)).as("건너뜀은 통과가 아니다").isFalse();
        String written = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(written).contains("outcome=SKIPPED_NO_KEY evidence=NONE", "SKIPPED_NO_KEY는 통과로 보지 않는다",
                "| 항목 | 관측 | 통과 여부 |");
    }

    @Test
    void aBlankKeyIsTheSameAsNoKey(@TempDir Path dir) throws IOException {
        GptLiveProbe probe = new GptLiveProbe(Map.of(ProviderClient.API_KEY, "   ")::get, REPO_ROOT,
                dir.resolve("report.md"), NEVER_OPEN);

        assertThat(probe.run().outcome()).isEqualTo(GptLiveProbe.Outcome.SKIPPED_NO_KEY);
    }

    @Test
    void theDefaultInputIsTheRepositoryPlaceholderAsPcm16Frames() throws IOException {
        GptLiveProbe probe = new GptLiveProbe(Map.<String, String>of()::get, REPO_ROOT, Path.of("unused"), NEVER_OPEN);

        GptLiveProbe.InputAudio input = probe.loadInput();

        long dataBytes = Files.size(REPO_ROOT.resolve(GptLiveProbe.DEFAULT_INPUT)) - 44;
        assertThat(input.sourceLabel()).contains(GptLiveProbe.DEFAULT_INPUT);
        assertThat(input.pcmBytes()).isEqualTo(dataBytes);
        int voiced = (int) Math.ceil(dataBytes / (double) GptLiveProbe.FRAME_BYTES);
        assertThat(input.frames()).hasSize(voiced + GptLiveProbe.TRAILING_SILENCE_FRAMES)
                .allSatisfy(frame -> assertThat(frame).hasSize(GptLiveProbe.FRAME_BYTES));
        assertThat(input.frames().getLast()).isEqualTo(new byte[GptLiveProbe.FRAME_BYTES]);
    }

    @Test
    void anEnvRawPcmFileIsUsedAndTheLastFrameIsZeroPadded(@TempDir Path dir) throws IOException {
        Path pcm = dir.resolve("synthetic.pcm");
        byte[] bytes = new byte[GptLiveProbe.FRAME_BYTES + 40];
        Arrays.fill(bytes, (byte) 7);
        Files.write(pcm, bytes);
        GptLiveProbe probe = new GptLiveProbe(Map.of(GptLiveProbe.INPUT_PCM_ENV, pcm.toString())::get, REPO_ROOT,
                Path.of("unused"), NEVER_OPEN);

        GptLiveProbe.InputAudio input = probe.loadInput();

        assertThat(input.sourceLabel()).startsWith("env " + GptLiveProbe.INPUT_PCM_ENV).doesNotContain(dir.toString());
        assertThat(input.frames()).hasSize(2 + GptLiveProbe.TRAILING_SILENCE_FRAMES);
        byte[] second = input.frames().get(1);
        assertThat(second[39]).isEqualTo((byte) 7);
        assertThat(second[40]).isZero();
    }

    @Test
    void unreadableInputFailsBeforeAnyCall(@TempDir Path dir) throws IOException {
        Path odd = dir.resolve("odd.pcm");
        Files.write(odd, new byte[3]);

        assertThatThrownBy(() -> GptLiveProbe.readPcm16Mono24k(odd)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GptLiveProbe.readPcm16Mono24k(dir.resolve("missing.wav"))).isInstanceOf(IOException.class);
    }

    @Test
    void theDemoProviderUrlPassesTheHostRuleWithoutABypass() throws IOException {
        URI url = GptLiveProbe.providerUrl();

        assertThat(ProviderHosts.check(url, "demo")).isEmpty();
        assertThat(ProviderHosts.ALLOWED_HOSTS).contains(url.getHost());
        assertThat(url.getScheme()).isEqualTo("wss");
    }

    @Test
    void responseCreatedCandidatesStopAtTheFirstNewOutputDelta() {
        List<GptLiveProbe.RawFrame> raw = List.of(
                new GptLiveProbe.RawFrame(0, "session.output.done", "old", List.of("responseId", "type")),
                new GptLiveProbe.RawFrame(1, "session.response.created", "r2", List.of("responseId", "type")),
                new GptLiveProbe.RawFrame(2, "session.output_audio.delta", "r2", List.of("audio", "responseId", "type")),
                new GptLiveProbe.RawFrame(3, "session.later.unknown", null, List.of("type")));

        String found = GptLiveProbe.responseCreatedCandidates(raw, 1, Set.of("r2"));
        String empty = GptLiveProbe.responseCreatedCandidates(raw, 2, Set.of("r2"));

        assertThat(found).contains("session.response.created", "새 응답과 같음").doesNotContain("session.later.unknown");
        assertThat(empty).startsWith("없음");
    }
}
