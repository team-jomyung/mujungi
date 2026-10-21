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
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
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
                "environment.remove(\"OPENAI_API_KEY\")");

        String realtime = read("backend/realtime-activity/build.gradle.kts");
        assertThat(realtime).contains("tasks.register<Test>(\"gptLiveTest\")", "setIncludeTags(setOf(gptLiveTag))",
                "isEnabled = false", "setFinalizedBy(emptyList<Any>())");
        assertThat(realtime).as("check · build가 gptLiveTest에 의존하지 않는다")
                .doesNotContain("dependsOn(\"gptLiveTest\")", "dependsOn(tasks.named(\"gptLiveTest\"))", "finalizedBy(\"gptLiveTest\")");

        String root = read("backend/build.gradle.kts");
        assertThat(root).contains("tasks.register(\"verifyGptLiveIsolation\")", "dependsOn(verifyGptLiveIsolation)",
                "it.name == \"gptLiveTest\"", "gptLiveTag !in testOptions.excludeTags",
                "test.environment.containsKey(\"OPENAI_API_KEY\")", "liveOptions.includeTags != setOf(gptLiveTag)");
    }

    @Test
    void defaultTestJvmDoesNotSeeTheProviderKey() {
        assumeTrue("true".equals(System.getProperty("mujoong.test.liveIsolated")), "Gradle 기본 test 작업 밖에서 돈다");
        assertThat(System.getenv(ProviderClient.API_KEY)).as("기본 test JVM에는 OPENAI_API_KEY가 없다").isNull();
    }

    @Test
    void withoutAKeyTheProbeReportsSkippedForEveryItemAndNeverConnects(@TempDir Path dir) throws IOException {
        Path report = dir.resolve("gpt-live/report.md");
        GptLiveProbe probe = new GptLiveProbe(Map.<String, String>of()::get, REPO_ROOT, report, NEVER_OPEN);

        GptLiveProbe.Result result = probe.run();

        assertThat(result.outcome()).isEqualTo(GptLiveProbe.Outcome.SKIPPED_NO_KEY);
        assertThat(result.rows()).hasSize(GptLiveProbe.ITEMS.size())
                .allSatisfy(row -> assertThat(row.verdict()).isEqualTo(GptLiveProbe.Verdict.SKIPPED_NO_KEY));
        assertThat(GptLiveProbe.anyVerdict(result, GptLiveProbe.Verdict.PASS)).as("건너뜀은 통과가 아니다").isFalse();
        String written = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(written).contains("outcome=SKIPPED_NO_KEY evidence=NONE", "SKIPPED_NO_KEY는 통과로 보지 않는다", "| 항목 | 판정 · 관측 |");
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
        byte[] bytes = new byte[640 + 40];
        Arrays.fill(bytes, (byte) 7);
        Files.write(pcm, bytes);

        GptLiveProbe.InputAudio input = probe(Map.of(GptLiveProbe.INPUT_PCM_ENV, pcm.toString(),
                GptLiveProbe.INPUT_RATE_ENV, "16000")).loadInput();

        assertThat(input.sourceLabel()).startsWith("env " + GptLiveProbe.INPUT_PCM_ENV).contains("16000Hz")
                .doesNotContain(dir.toString());
        assertThat(input.fromEnv()).isTrue();
        List<byte[]> frames16k = input.framesAt(16_000, 640);
        assertThat(frames16k).hasSize(2 + GptLiveProbe.TRAILING_SILENCE_FRAMES);
        assertThat(frames16k.get(1)[39]).isEqualTo((byte) 7);
        assertThat(frames16k.get(1)[40]).isZero();
        assertThat(input.framesAt(16_000, 640, 3)).as("무음 프레임 수를 지정할 수 있다").hasSize(5);
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

    // ---- 운영 형식 요청 · 환경 옵션 ----

    @Test
    void theSessionStartUsesTheProductionFactoryShapeAndTheRateOption() {
        ProviderRequestFactory factory = new ProviderRequestFactory();
        kr.mujoong.realtime.session.ChildContext context = new kr.mujoong.realtime.session.ChildContext("하늘", 8, List.of("블록"));

        Map<?, ?> start24 = JSON.read(JSON.write(factory.sessionStart(context)), Map.class);
        Map<?, ?> start16 = JSON.read(JSON.write(factory.sessionStart(context, 16_000)), Map.class);

        assertThat(keys(start24)).containsExactly("type", "session");
        Map<?, ?> session = (Map<?, ?>) start24.get("session");
        assertThat(keys(session)).containsExactlyInAnyOrder("model", "instructions", "audio");
        assertThat(session.get("audio")).isEqualTo(Map.of("format", Map.of("type", "audio/pcm", "rate", 24_000)));
        assertThat(((Map<?, ?>) start16.get("session")).get("audio"))
                .isEqualTo(Map.of("format", Map.of("type", "audio/pcm", "rate", 16_000)));
    }

    @Test
    void theRateQuietPlainTurnsAndOneShotEnvsAreValidatedBeforeAnyCall() throws IOException {
        assertThat(GptLiveProbe.sessionRate(null)).isEqualTo(24_000);
        assertThat(GptLiveProbe.sessionRate(" 16000 ")).isEqualTo(16_000);
        assertThatThrownBy(() -> GptLiveProbe.sessionRate("abc")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GptLiveProbe.sessionRate("96000")).isInstanceOf(IOException.class);
        assertThat(GptLiveProbe.quietSeconds(null)).isNull();
        assertThat(GptLiveProbe.quietSeconds(" 6 ")).isEqualTo(java.time.Duration.ofSeconds(6));
        assertThatThrownBy(() -> GptLiveProbe.quietSeconds("1")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GptLiveProbe.quietSeconds("x")).isInstanceOf(IOException.class);
        assertThat(GptLiveProbe.plainTurns(null)).isEqualTo(2);
        assertThat(GptLiveProbe.plainTurns("3")).isEqualTo(3);
        assertThatThrownBy(() -> GptLiveProbe.plainTurns("0")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GptLiveProbe.plainTurns("4")).isInstanceOf(IOException.class);
        assertThat(GptLiveProbe.oneShot(null)).isTrue();
        assertThat(GptLiveProbe.oneShot("1")).isTrue();
        assertThat(GptLiveProbe.oneShot("0")).isFalse();
        assertThat(GptLiveProbe.oneShot("Off")).isFalse();
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
    void typeNamesInQuotesStayVisibleWhileKeysAndKoreanAreMasked() {
        String masked = GptLiveProbe.maskMessage("Invalid value: 'session.instructions.correct'. Supported values are: "
                + "'session.start', \"session.input_audio.append\". key sk-abcdef123456 value '아이 말' 'Value1' https://x.example.com");

        assertThat(masked).contains("'session.instructions.correct'", "'session.start'", "\"session.input_audio.append\"",
                "[키 가림]", "[주소 가림]", "[값 가림]")
                .doesNotContain("sk-abcdef123456", "아이 말", "Value1", "example.com", "\u0001");
    }

    @Test
    void supportedTypeListsComeFromTheFullMessageEvenWhenItIsCutOff() {
        String message = "Invalid value: 'session.instructions.correct'. Supported values are: 'session.start', "
                + "'session.input_audio.append', 'session.close', 'session.start', 'session.output_audio.fixtu";

        GptLiveProbe.TypeLists lists = GptLiveProbe.typeLists(message);
        GptLiveProbe.RawFrame frame = GptLiveProbe.parseRaw(JSON, 0, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"code\":\"invalid_value\",\"param\":\"type\",\"message\":\"" + "y".repeat(300) + " " + message + "\"}}");

        assertThat(lists.rejected()).containsExactly("session.instructions.correct");
        assertThat(lists.supported()).containsExactly("session.close", "session.input_audio.append",
                "session.output_audio.fixtu(잘림)", "session.start");
        assertThat(frame.error().supportedTypes()).as("200자 자르기 전 원문에서 뽑는다").contains("session.close", "session.start");
        assertThat(GptLiveProbe.describeTypeLists(List.of(frame.error(), frame.error())))
                .startsWith("지원 4개 [session.close, session.input_audio.append, session.output_audio.fixtu(잘림), session.start]")
                .contains("거절된 값 [session.instructions.correct]");
        assertThat(GptLiveProbe.typeLists("no list here").supported()).isEmpty();
        assertThat(GptLiveProbe.describeTypeLists(List.of())).startsWith("없음");
    }

    @Test
    void eventShapesKeepKeysAndValueTypesOnlyOncePerType() {
        String audio = Base64.getEncoder().encodeToString(new byte[480]);
        List<GptLiveProbe.RawFrame> raw = List.of(
                GptLiveProbe.parseRaw(JSON, 0, "{\"type\":\"session.output_audio.delta\",\"response_id\":\"resp_1\",\"delta\":\""
                        + audio + "\"}"),
                GptLiveProbe.parseRaw(JSON, 1, "{\"type\":\"session.output_audio.delta\",\"response_id\":\"resp_1\",\"extra\":1}"),
                GptLiveProbe.parseRaw(JSON, 2, "{\"type\":\"session.output_transcript.delta\",\"delta\":{\"text\":\"안녕 하늘아\","
                        + "\"start_ms\":120,\"final\":false},\"items\":[{\"id\":\"a\"}]}"),
                new GptLiveProbe.RawFrame(3, GptLiveProbe.CLOSED_MARK, null, List.of()));

        String shapes = GptLiveProbe.describeShapes(raw);

        assertThat(shapes).isEqualTo("session.output_audio.delta {type:string, response_id:string, delta:string(base64)} ; "
                + "session.output_transcript.delta {type:string, delta:{text:string, start_ms:number, final:boolean}, "
                + "items:array<object>}");
        assertThat(shapes).doesNotContain("resp_1", "안녕", audio, "120");
        assertThat(GptLiveProbe.shapeOf(null, 0)).isEqualTo("null");
    }

    @Test
    void usageEventsKeepNumericFieldsAndTheLastValueWins() {
        List<GptLiveProbe.RawFrame> raw = List.of(
                GptLiveProbe.parseRaw(JSON, 0, "{\"type\":\"session.started\",\"session\":{\"id\":\"s\"}}"),
                GptLiveProbe.parseRaw(JSON, 1, "{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":1.5,\"note\":\"x\"}}"),
                GptLiveProbe.parseRaw(JSON, 2, "{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":12.25,"
                        + "\"audio\":{\"input_seconds\":6,\"output_seconds\":6.25}}}"),
                GptLiveProbe.parseRaw(JSON, 3, "{\"type\":\"session.closed\",\"reason\":\"client\",\"usage\":{\"seconds\":12.5}}"));

        String usage = GptLiveProbe.describeUsage(raw, 13_000L);

        assertThat(usage).isEqualTo("session.usage.updated 2건 · 마지막 seconds=12.250 audio.input_seconds=6 audio.output_seconds=6.250"
                + " · session.closed 사용량 seconds=12.500 · 세션 길이(started ~ 닫힘) 13000ms");
        assertThat(GptLiveProbe.usageNumbers(Map.of("type", "session.usage.updated", "seconds", 3))).isEqualTo("seconds=3");
        assertThat(GptLiveProbe.describeUsage(List.of(), null))
                .isEqualTo("session.usage.updated 0건 · 마지막 없음 · session.closed 사용량 없음 · 세션 길이(started ~ 닫힘) 모름");
    }

    @Test
    void outputAudioIsCountedFromTheObservedDeltaShapeAndOlderShapesToo() {
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
    void boundaryCandidatesListOnlyUnknownResponseEndLikeTypesWithoutValues() {
        List<GptLiveProbe.RawFrame> raw = List.of(
                new GptLiveProbe.RawFrame(0, "session.started", null, List.of("session", "type")),
                new GptLiveProbe.RawFrame(1, "session.output_audio.delta", null, List.of("delta", "type")),
                new GptLiveProbe.RawFrame(2, "session.response.completed", "ignored-value", List.of("response", "type")),
                new GptLiveProbe.RawFrame(3, "session.response.completed", null, List.of("response", "type")),
                new GptLiveProbe.RawFrame(4, "session.later.unknown", null, List.of("type")));

        String found = GptLiveProbe.boundaryCandidates(raw);

        assertThat(found).isEqualTo("session.response.completed 2건(키 [response, type])");
        assertThat(found).doesNotContain("ignored-value", "session.later.unknown");
        assertThat(GptLiveProbe.boundaryCandidates(raw.subList(0, 2))).isEqualTo("없음");
    }

    @Test
    void theReportHasOneColumnWithTheOutcomeOnTopAndNeverCarriesTranscriptsAudioOrKeys() {
        GptLiveProbe.Row host = new GptLiveProbe.Row("공급자 주소 규칙", "통과", GptLiveProbe.Verdict.PASS);
        List<GptLiveProbe.Row> rows = GptLiveProbe.ITEMS.stream()
                .map(item -> new GptLiveProbe.Row(item, "관측 | 구분자", GptLiveProbe.Verdict.INFO)).toList();
        GptLiveProbe.Result result = new GptLiveProbe.Result(GptLiveProbe.Outcome.FAIL, host, rows, 24_000);
        GptLiveProbe.InputAudio input = new GptLiveProbe.InputAudio("저장소 합성음 자리 파일", false, 24_000, new byte[48_000]);

        String report = GptLiveProbe.renderReport(result, input, "2026-10-17T00:00:00Z");

        List<String> lines = report.lines().toList();
        assertThat(lines).contains("outcome=FAIL evidence=REAL_API_SYNTHETIC", "rate=24000 phases=P1,P2,P3,P4,P6", "| 항목 | 판정 · 관측 |",
                "- " + GptLiveProbe.PRICE_NOTE);
        List<String> tableRows = lines.stream().filter(line -> line.startsWith("| ") && !line.startsWith("| 항목")).toList();
        assertThat(tableRows).hasSize(1 + GptLiveProbe.ITEMS.size())
                .allSatisfy(line -> assertThat(line.split("\\|", -1)).hasSize(4));
        assertThat(tableRows.get(1)).contains("**INFO** · 관측 / 구분자");
        assertThat(report).contains("1000ms", "표식 낱말", "response.create는 보내지 않는다").doesNotContain("USD", "$", "키트");
        assertThat(GptLiveProbe.summary(result)).startsWith("GPT-Live 실호출 FAIL — ①=INFO");
        assertThat(GptLiveProbe.ITEMS).hasSize(19).startsWith("① 입력 표본율 수락(세션 시작 · 음성 송신 · 출력 수신)");
    }

    // ---- 추가 전용 실험(지시 추가 원시 메시지 · 표식 낱말) ----

    @Test
    void theAppendMessageIsRawJsonWithAnExplicitNullDelegationIdAndNeverAResponseCreate() {
        GptLiveProbe probe = probe(Map.of());

        String message = probe.appendMessage(GptLiveProbe.EVENT_ID_P2, GptLiveProbe.INSTRUCTION_P2);

        Map<?, ?> parsed = JSON.read(message, Map.class);
        assertThat(message).contains("\"delegation_id\":null");
        assertThat(keys(parsed)).containsExactlyInAnyOrder("type", "event_id", "delegation_id", "content");
        assertThat(parsed.get("type")).isEqualTo("session.instructions.append");
        assertThat(parsed.get("event_id")).isEqualTo("gl-p2-append");
        assertThat(parsed.get("delegation_id")).isNull();
        assertThat(parsed.get("content")).isEqualTo(GptLiveProbe.INSTRUCTION_P2);
        assertThat(message).doesNotContain("response.create");
    }

    @Test
    void theProbesRawAppendIsTheSameWireShapeTheProductionFactoryBuilds() {
        GptLiveProbe probe = probe(Map.of());
        ProviderRequestFactory factory = new ProviderRequestFactory();

        String raw = probe.appendMessage("gl-x", GptLiveProbe.INSTRUCTION_P2);
        String production = JSON.write(factory.instructionsAppend("gl-x", GptLiveProbe.INSTRUCTION_P2));

        assertThat(JSON.read(raw, Map.class)).isEqualTo(JSON.read(production, Map.class));
        assertThat(production).contains("\"delegation_id\":null");
    }

    @Test
    void theFixedInstructionsAskForOneMarkerEachAndStayShort() {
        assertThat(GptLiveProbe.INSTRUCTION_P2).contains(GptLiveProbe.MARKER_P2).doesNotContain(GptLiveProbe.MARKER_P3);
        assertThat(GptLiveProbe.INSTRUCTION_P3).contains(GptLiveProbe.MARKER_P3).doesNotContain(GptLiveProbe.MARKER_P2);
        assertThat(GptLiveProbe.MARKER_P2).isNotEqualTo(GptLiveProbe.MARKER_P3);
        assertThat(GptLiveProbe.INSTRUCTION_P2.length()).as("500 토큰 안(글자 수로 넉넉히)").isLessThan(300);
        assertThat(GptLiveProbe.INSTRUCTION_P3.length()).isLessThan(300);
    }

    @Test
    void theTapFindsAMarkerInMemoryAndReportsOnlyBooleansCountsAndTimes() {
        GptLiveProbe.Tap tap = new GptLiveProbe.Tap(JSON);
        tap.record("{\"type\":\"session.output_transcript.delta\",\"delta\":\"안녕 하\",\"start_ms\":0,\"end_ms\":100,\"event_id\":\"a\"}");
        long mark = System.nanoTime();
        tap.record("{\"type\":\"session.output_transcript.delta\",\"delta\":\"세요 나\",\"start_ms\":100,\"end_ms\":200,\"event_id\":\"b\"}");
        tap.record("{\"type\":\"session.output_transcript.delta\",\"delta\":{\"text\":\" 비야\"},\"event_id\":\"c\"}");
        tap.record("{\"type\":\"session.input_transcript.delta\",\"delta\":\"나비\",\"event_id\":\"d\"}");
        long end = System.nanoTime() + 1;

        GptLiveProbe.MarkerHit hit = tap.findMarker("나비", 0L, end);
        GptLiveProbe.MarkerHit none = tap.findMarker("두루미", 0L, end);
        GptLiveProbe.MarkerHit later = tap.findMarker("나비", mark + 5_000_000_000L, mark + 6_000_000_000L);

        assertThat(hit.found()).isTrue();
        assertThat(hit.charsBefore()).as("공백을 뺀 '안녕하세요' 5자 뒤").isEqualTo(5);
        assertThat(hit.windowChars()).isEqualTo(8);
        assertThat(hit.markerStartMs()).as("표식 첫 글자가 든 조각의 start_ms").isEqualTo(100L);
        assertThat(hit.foundAtNanos()).as("표식이 완성된 조각(세 번째)의 도착 시각").isGreaterThanOrEqualTo(mark);
        assertThat(none.found()).isFalse();
        assertThat(none.windowChars()).isEqualTo(8);
        assertThat(later.found()).isFalse();
        assertThat(tap.rawSnapshot()).as("원시 관측에는 전사 글이 없다").allSatisfy(frame -> assertThat(frame.shape()).doesNotContain("안녕"));
    }

    @Test
    void anAppendedEventKeepsItsClientEventIdAndTheUnknownTypeListSkipsIt() {
        GptLiveProbe.RawFrame ack = GptLiveProbe.parseRaw(JSON, 0,
                "{\"type\":\"session.instructions.appended\",\"event_id\":\"srv-1\",\"client_event_id\":\"gl-p2-append\","
                        + "\"instructions\":\"" + "ab ".repeat(13) + "a" + "\"}").at(10L);
        GptLiveProbe.RawFrame boundary = GptLiveProbe.parseRaw(JSON, 1, "{\"type\":\"session.response.finished\"}").at(20L);

        assertThat(ack.clientEventId()).isEqualTo("gl-p2-append");
        assertThat(ack.instructionsLength()).isEqualTo(40);
        assertThat(ack.shape()).isEqualTo("{type:string, event_id:string, client_event_id:string, instructions:string}");
        assertThat(GptLiveProbe.unknownTypesIn(List.of(ack, boundary), 0L, 100L)).isEqualTo("{session.response.finished=1}");
        assertThat(GptLiveProbe.unknownTypesIn(List.of(ack, boundary), 0L, 15L)).isEqualTo("없음");
    }

    @Test
    void theTapKeepsARunningAudioClockAndEventTimes() {
        String audio = Base64.getEncoder().encodeToString(new byte[960]);
        GptLiveProbe.Tap tap = new GptLiveProbe.Tap(JSON);
        for (int i = 0; i < 3; i++) {
            tap.record("{\"type\":\"session.output_audio.delta\",\"delta\":\"" + audio + "\"}");
        }
        tap.record("{\"type\":\"session.output_transcript.delta\",\"delta\":\"가\",\"start_ms\":10,\"end_ms\":90,\"event_id\":\"a\"}");
        tap.record("{\"type\":\"session.instructions.appended\",\"client_event_id\":\"x\",\"start_ms\":60,\"end_ms\":80,\"event_id\":\"b\"}");

        List<GptLiveProbe.RawFrame> raw = tap.rawSnapshot();

        assertThat(raw).extracting(GptLiveProbe.RawFrame::clockMs).containsExactly(20L, 40L, 60L, 60L, 60L);
        assertThat(raw.get(2).clockStartMs()).isEqualTo(40L);
        assertThat(raw.get(3).startMs()).isEqualTo(10L);
        assertThat(raw.get(3).endMs()).isEqualTo(90L);
        assertThat(raw.get(4).startMs()).isEqualTo(60L);
        assertThat(raw.get(0).startMs()).isEqualTo(-1L);
        assertThat(tap.pieces()).singleElement().satisfies(piece -> {
            assertThat(piece.clockMs()).isEqualTo(60L);
            assertThat(piece.endMs()).isEqualTo(90L);
        });
        assertThat(GptLiveProbe.chunksIn(raw, 0L, Long.MAX_VALUE)).extracting(GptLiveProbe.Chunk::clockStartMs)
                .containsExactly(0L, 20L, 40L);
    }

    @Test
    void transcriptPiecesAreAttributedByStartMsToTheWindowWhoseAudioClockRangeHoldsThem() {
        GptLiveProbe.Win p3 = new GptLiveProbe.Win("P3", 10L, 20L, 1_000L, 1_400L);
        GptLiveProbe.Win p4 = new GptLiveProbe.Win("P4#1", 21L, 40L, 1_400L, 1_800L);
        GptLiveProbe.Win silent = new GptLiveProbe.Win("P6a", 41L, 50L, -1L, -1L);
        List<GptLiveProbe.TextAt> pieces = List.of(
                new GptLiveProbe.TextAt(30L, "두루미 안녕", 1_000L, 1_100L, 1_400L),
                new GptLiveProbe.TextAt(31L, "평범", 1_450L, 1_500L, 1_450L),
                new GptLiveProbe.TextAt(32L, "시계 밖", 9_000L, 9_100L, 1_450L),
                new GptLiveProbe.TextAt(33L, "시각 없음", -1L, -1L, 1_450L));

        java.util.Map<String, List<GptLiveProbe.TextAt>> map = GptLiveProbe.byStart(pieces, List.of(p3, p4, silent));

        assertThat(map.get("P3")).extracting(GptLiveProbe.TextAt::atNanos).as("도착이 P4 창이어도 start_ms가 P3 구간").containsExactly(30L);
        assertThat(map.get("P4#1")).extracting(GptLiveProbe.TextAt::atNanos).containsExactly(31L);
        assertThat(map.get("P6a")).isEmpty();
        assertThat(map.get("?")).extracting(GptLiveProbe.TextAt::atNanos).containsExactly(32L, 33L);
        assertThat(GptLiveProbe.findMarker(map.get("P3"), "두루미", piece -> true).charsBefore()).isZero();
    }
}
