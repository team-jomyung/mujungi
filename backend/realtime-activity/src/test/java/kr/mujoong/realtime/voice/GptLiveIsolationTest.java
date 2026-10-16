package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GPT-Live 격리 실호출 규칙 시험 (U11 FR9.1 · BR9.1 · 기능 설계 W7). 기본 test에서 돌고 공급자를 부르지 않는다(R-F1).
 *
 * <p>작업 그래프 자체(check · test → gptLiveTest 의존 없음)는 루트 {@code verifyGptLiveIsolation}이 check마다 확인한다. 이 시험은 그 확인과
 * 빌드 규칙이 제자리에 있는지, 기본 test JVM에 키가 없는지, 키가 없으면 연결 없이 SKIPPED_NO_KEY인지, 합성 입력 · 주소 규칙, 오류 사건 기록
 * (가림), 키트 형식 메시지 모양, 두 열 보고서 형식을 본다.
 */
@Tag("u11")
class GptLiveIsolationTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();

    private static final GptLiveProbe.Opener NEVER_OPEN = (url, key, tap) -> {
        throw new AssertionError("키가 없으면 연결하지 않는다");
    };

    private static final JsonCodec JSON = JsonCodec.shared();

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static List<String> keys(Map<?, ?> map) {
        return map.keySet().stream().map(String::valueOf).toList();
    }

    private static GptLiveProbe probe(Map<String, String> env) {
        return new GptLiveProbe(env::get, REPO_ROOT, Path.of("unused"), NEVER_OPEN);
    }

    // ---- 격리 규칙 ----

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
    void withoutAKeyTheProbeReportsSkippedForBothFormatsAndNeverConnects(@TempDir Path dir) throws IOException {
        Path report = dir.resolve("gpt-live/report.md");
        GptLiveProbe probe = new GptLiveProbe(Map.<String, String>of()::get, REPO_ROOT, report, NEVER_OPEN);

        GptLiveProbe.Result result = probe.run();

        assertThat(result.outcome()).isEqualTo(GptLiveProbe.Outcome.SKIPPED_NO_KEY);
        assertThat(result.kitOutcome()).isEqualTo(GptLiveProbe.Outcome.SKIPPED_NO_KEY);
        assertThat(result.app().rows()).hasSize(GptLiveProbe.ITEMS.size())
                .allSatisfy(row -> assertThat(row.verdict()).isEqualTo(GptLiveProbe.Verdict.SKIPPED_NO_KEY));
        assertThat(GptLiveProbe.anyVerdict(result, GptLiveProbe.Verdict.PASS)).as("건너뜀은 통과가 아니다").isFalse();
        String written = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(written).contains("outcome=SKIPPED_NO_KEY evidence=NONE", "kitFormat=SKIPPED_NO_KEY",
                "SKIPPED_NO_KEY는 통과로 보지 않는다", "| 항목 | 앱 형식 | 키트 형식 |");
    }

    @Test
    void aBlankKeyIsTheSameAsNoKey(@TempDir Path dir) throws IOException {
        GptLiveProbe probe = new GptLiveProbe(Map.of(ProviderClient.API_KEY, "   ")::get, REPO_ROOT,
                dir.resolve("report.md"), NEVER_OPEN);

        assertThat(probe.run().outcome()).isEqualTo(GptLiveProbe.Outcome.SKIPPED_NO_KEY);
    }

    @Test
    void theDemoProviderUrlPassesTheHostRuleWithoutABypass() throws IOException {
        URI url = GptLiveProbe.providerUrl();

        assertThat(ProviderHosts.check(url, "demo")).isEmpty();
        assertThat(ProviderHosts.ALLOWED_HOSTS).contains(url.getHost());
        assertThat(url.getScheme()).isEqualTo("wss");
    }

    // ---- 합성 입력 ----

    @Test
    void theDefaultInputIsTheRepositoryPlaceholderAsPcm16Frames() throws IOException {
        GptLiveProbe.InputAudio input = probe(Map.of()).loadInput();

        long dataBytes = Files.size(REPO_ROOT.resolve(GptLiveProbe.DEFAULT_INPUT)) - 44;
        assertThat(input.sourceLabel()).contains(GptLiveProbe.DEFAULT_INPUT);
        assertThat(input.fromEnv()).isFalse();
        assertThat(input.sampleRate()).isEqualTo(GptLiveProbe.APP_RATE);
        assertThat(input.pcm()).hasSize((int) dataBytes);
        List<byte[]> frames = input.framesAt(GptLiveProbe.APP_RATE, GptLiveProbe.FRAME_BYTES);
        int voiced = (int) Math.ceil(dataBytes / (double) GptLiveProbe.FRAME_BYTES);
        assertThat(frames).hasSize(voiced + GptLiveProbe.TRAILING_SILENCE_FRAMES)
                .allSatisfy(frame -> assertThat(frame).hasSize(GptLiveProbe.FRAME_BYTES));
        assertThat(frames.getLast()).isEqualTo(new byte[GptLiveProbe.FRAME_BYTES]);
    }

    @Test
    void anEnvRawPcmFileUsesTheGivenRateAndTheLastFrameIsZeroPadded(@TempDir Path dir) throws IOException {
        Path pcm = dir.resolve("synthetic.pcm");
        byte[] bytes = new byte[GptLiveProbe.KIT_FRAME_BYTES + 40];
        Arrays.fill(bytes, (byte) 7);
        Files.write(pcm, bytes);

        GptLiveProbe.InputAudio input = probe(Map.of(GptLiveProbe.INPUT_PCM_ENV, pcm.toString(),
                GptLiveProbe.INPUT_RATE_ENV, "16000")).loadInput();

        assertThat(input.sourceLabel()).startsWith("env " + GptLiveProbe.INPUT_PCM_ENV).contains("16000Hz")
                .doesNotContain(dir.toString());
        assertThat(input.fromEnv()).isTrue();
        List<byte[]> kitFrames = input.framesAt(GptLiveProbe.KIT_RATE, GptLiveProbe.KIT_FRAME_BYTES);
        assertThat(kitFrames).hasSize(2 + GptLiveProbe.TRAILING_SILENCE_FRAMES);
        assertThat(kitFrames.get(1)[39]).isEqualTo((byte) 7);
        assertThat(kitFrames.get(1)[40]).isZero();
    }

    @Test
    void unreadableInputFailsBeforeAnyCall(@TempDir Path dir) throws IOException {
        Path odd = dir.resolve("odd.pcm");
        Files.write(odd, new byte[3]);

        assertThatThrownBy(() -> GptLiveProbe.readPcm16Mono(odd, 24_000)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GptLiveProbe.readPcm16Mono(dir.resolve("missing.wav"), 24_000)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GptLiveProbe.rawRate("abc")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GptLiveProbe.rawRate("96000")).isInstanceOf(IOException.class);
        assertThat(GptLiveProbe.rawRate(null)).isEqualTo(GptLiveProbe.APP_RATE);
    }

    @Test
    void resamplingKeepsDurationAndShape() {
        int samples = 2_400;
        ByteBuffer ramp = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            ramp.putShort((short) (i * 10));
        }
        byte[] pcm24 = ramp.array();

        byte[] pcm16 = GptLiveProbe.resample(pcm24, 24_000, 16_000);
        byte[] same = GptLiveProbe.resample(pcm24, 24_000, 24_000);

        assertThat(pcm16).hasSize(samples * 2 * 2 / 3);
        assertThat(same).isEqualTo(pcm24).isNotSameAs(pcm24);
        ByteBuffer out = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN);
        short middle = out.getShort(800 * 2);
        assertThat((int) middle).as("16kHz 800번째 표본 ≈ 24kHz 1200번째(12000)").isBetween(11_900, 12_100);
    }

    // ---- 키트 형식 메시지 ----

    @Test
    void kitFormatSessionStartHasTheKitShapeAndNoChildData() {
        String start = probe(Map.of()).kitSessionStart();
        Map<?, ?> message = JSON.read(start, Map.class);

        assertThat(keys(message)).containsExactly("type", "event_id", "session");
        assertThat(message.get("type")).isEqualTo("session.start");
        Map<?, ?> session = (Map<?, ?>) message.get("session");
        assertThat(keys(session)).containsExactlyInAnyOrder("model", "instructions", "audio", "delegation");
        assertThat(session.get("model")).isEqualTo(ProviderRequestFactory.MODEL);
        assertThat(session.get("instructions")).isEqualTo(GptLiveProbe.KIT_INSTRUCTIONS);
        assertThat(session.get("audio")).isEqualTo(Map.of("format", Map.of("type", "audio/pcm", "rate", 16_000)));
        assertThat(session.get("delegation")).isEqualTo(Map.of("type", "client"));
        assertThat(start).doesNotContain("childContext", "displayName", "interests", "하늘");
    }

    @Test
    void kitFormatAudioAndCloseMessagesUseTheSameTypeNames() {
        GptLiveProbe probe = probe(Map.of());
        byte[] frame = new byte[GptLiveProbe.KIT_FRAME_BYTES];

        Map<?, ?> append = JSON.read(probe.kitAudioAppend(frame), Map.class);
        Map<?, ?> close = JSON.read(probe.kitSessionClose(), Map.class);

        assertThat(keys(append)).containsExactly("type", "audio");
        assertThat(append.get("type")).isEqualTo(ProviderRequestFactory.TYPE_INPUT_AUDIO_APPEND);
        assertThat(Base64.getDecoder().decode((String) append.get("audio"))).hasSize(640);
        assertThat(keys(close)).containsExactly("type", "event_id");
        assertThat(close.get("type")).isEqualTo(ProviderRequestFactory.TYPE_SESSION_CLOSE);
    }

    // ---- 원시 관측 · 오류 사건 ----

    @Test
    void topLevelErrorEventsAreRecordedWithAMaskedMessage() {
        String longTail = "x".repeat(300);
        String message = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"code\":\"unknown_parameter\","
                + "\"param\":\"session.childContext\",\"message\":\"Unknown parameter 'childContext' with value \\\"하늘\\\" key sk-abcdef123456"
                + " see https://platform.example.com/docs 아이가 말한 문장 " + longTail + "\"}}";

        GptLiveProbe.RawFrame frame = GptLiveProbe.parseRaw(JSON, 0, message);

        assertThat(frame.type()).isEqualTo("error");
        GptLiveProbe.ErrorInfo error = frame.error();
        assertThat(error).isNotNull();
        assertThat(error.type()).isEqualTo("invalid_request_error");
        assertThat(error.code()).isEqualTo("unknown_parameter");
        assertThat(error.param()).isEqualTo("session.childContext");
        assertThat(error.message()).startsWith("Unknown parameter")
                .doesNotContain("sk-abcdef123456", "https://", "example.com", "하늘", "아이가 말한", "childContext'", longTail)
                .contains("[키 가림]", "[주소 가림]", "[값 가림]")
                .hasSizeLessThan(GptLiveProbe.ERROR_MESSAGE_MAX + 20);
    }

    @Test
    void sessionErrorEventsKeepTheirCodeAndUnsafeTokensAreHidden() {
        GptLiveProbe.RawFrame app = GptLiveProbe.parseRaw(JSON, 3,
                "{\"type\":\"session.error\",\"code\":\"rate_limited\",\"message\":\"slow down\"}");
        GptLiveProbe.RawFrame odd = GptLiveProbe.parseRaw(JSON, 4,
                "{\"type\":\"error\",\"error\":{\"code\":\"a b|c\",\"param\":\"x y\",\"message\":\"line1\\nline2 | cell\"}}");
        GptLiveProbe.RawFrame broken = GptLiveProbe.parseRaw(JSON, 5, "not json");

        assertThat(app.error()).isEqualTo(new GptLiveProbe.ErrorInfo("session.error", "rate_limited", null, "slow down"));
        assertThat(odd.error().code()).isEqualTo("(형식 밖)");
        assertThat(odd.error().param()).isEqualTo("(형식 밖)");
        assertThat(odd.error().message()).isEqualTo("line1 line2 / cell");
        assertThat(broken.type()).isEqualTo("(해석 불가)");
        assertThat(broken.error()).isNull();
    }

    @Test
    void outputAudioIsCountedInBothAppAndKitShapes() {
        String audio = Base64.getEncoder().encodeToString(new byte[480]);

        GptLiveProbe.RawFrame app = GptLiveProbe.parseRaw(JSON, 0,
                "{\"type\":\"session.output_audio.delta\",\"responseId\":\"r1\",\"audio\":\"" + audio + "\"}");
        GptLiveProbe.RawFrame kitString = GptLiveProbe.parseRaw(JSON, 1,
                "{\"type\":\"session.output_audio.delta\",\"response_id\":\"r2\",\"delta\":\"" + audio + "\"}");
        GptLiveProbe.RawFrame kitObject = GptLiveProbe.parseRaw(JSON, 2,
                "{\"type\":\"session.output_audio.delta\",\"delta\":{\"audio\":\"" + audio + "\"}}");

        assertThat(app.audioBytes()).isEqualTo(480);
        assertThat(app.responseId()).isEqualTo("r1");
        assertThat(kitString.audioBytes()).isEqualTo(480);
        assertThat(kitString.responseId()).isEqualTo("r2");
        assertThat(kitObject.audioBytes()).isEqualTo(480);
        assertThat(kitObject.responseId()).isNull();
    }

    @Test
    void theTapRemembersTheLastEventBeforeAnAbnormalClose() {
        GptLiveProbe.Tap tap = new GptLiveProbe.Tap(JSON);
        tap.record("{\"type\":\"error\",\"error\":{\"code\":\"bad_start\"}}");

        tap.onError(null, new IOException("closed without close frame"));

        assertThat(tap.lastTypeBeforeClose()).isEqualTo("error");
        assertThat(tap.socketError()).isEqualTo("IOException");
        assertThat(tap.closeCode()).isNull();
        assertThat(tap.rawSnapshot()).extracting(GptLiveProbe.RawFrame::type).containsExactly("error", GptLiveProbe.CLOSED_MARK);
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

    // ---- 보고서 ----

    @Test
    void theReportHasAppAndKitColumnsWithTheAppOutcomeOnTop() {
        GptLiveProbe.Row host = new GptLiveProbe.Row("공급자 주소 규칙", "통과", GptLiveProbe.Verdict.PASS);
        List<GptLiveProbe.Row> appRows = GptLiveProbe.ITEMS.stream()
                .map(item -> new GptLiveProbe.Row(item, "앱 관측 | 구분자", GptLiveProbe.Verdict.FAIL)).toList();
        List<GptLiveProbe.Row> kitRows = GptLiveProbe.ITEMS.stream()
                .map(item -> new GptLiveProbe.Row(item, "키트 관측", GptLiveProbe.Verdict.PASS)).toList();
        GptLiveProbe.Result result = new GptLiveProbe.Result(host,
                new GptLiveProbe.Attempt(GptLiveProbe.Mode.APP, GptLiveProbe.Outcome.FAIL, appRows),
                new GptLiveProbe.Attempt(GptLiveProbe.Mode.KIT, GptLiveProbe.Outcome.PASS, kitRows));
        GptLiveProbe.InputAudio input = new GptLiveProbe.InputAudio("저장소 합성음 자리 파일", false, 24_000, new byte[48_000]);

        String report = GptLiveProbe.renderReport(result, input, "2026-10-16T00:00:00Z");

        List<String> lines = report.lines().toList();
        assertThat(lines).contains("outcome=FAIL evidence=REAL_API_SYNTHETIC", "kitFormat=PASS", "| 항목 | 앱 형식 | 키트 형식 |");
        assertThat(lines.indexOf("outcome=FAIL evidence=REAL_API_SYNTHETIC")).isLessThan(lines.indexOf("kitFormat=PASS"));
        List<String> tableRows = lines.stream().filter(line -> line.startsWith("| ") && !line.startsWith("| 항목")).toList();
        assertThat(tableRows).hasSize(1 + GptLiveProbe.ITEMS.size())
                .allSatisfy(line -> assertThat(line.split("\\|", -1)).hasSize(5));
        assertThat(tableRows.get(1)).contains("**FAIL** · 앱 관측 / 구분자", "**PASS** · 키트 관측");
        assertThat(report).contains("1000ms", "앱 형식 24000Hz · 키트 형식 16000Hz");
        assertThat(GptLiveProbe.summary(result)).startsWith("GPT-Live 실호출 앱 형식 FAIL · 키트 형식 PASS");
    }
}
