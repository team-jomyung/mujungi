package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.core.luna.adapter.LunaTextAiProvider;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.GeneratedScenario;
import kr.mujoong.core.textai.ScenarioPrompt;
import kr.mujoong.core.textai.TextAiProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Luna 실호출 시험 — core (U9 FR12.1 · BR9.2 · NFR5.2 · 편차 U9-c). 기본 {@code test} · {@code check} · CI · verify.sh에서는 돌지 않고,
 * 사람이 이 PC env에 {@code OPENAI_API_KEY}를 넣고 {@code ./gradlew lunaLiveTest}로만 돈다(BR9.3).
 *
 * <ul>
 *   <li>키가 없으면 보고서에 {@code SKIPPED_NO_KEY}를 쓰고 건너뛴다(통과로 보고하지 않음).
 *   <li>키가 있으면 앱 프로필 없이 호출부를 직접 만들고 STRICT 주소 규칙(https + api.openai.com)을 쓴다. 목적마다 합성 자료 1건으로 실제
 *       Responses API를 불러, 요청용 스키마 사본이 거절되지 않는지(REQUEST_REJECTED 아님) · {@code temperature} 없이 받아들여지는지 ·
 *       스키마 검증 · 응답 모델 · 지연을 확인한다.
 *   <li>보고서({@code build/luna-live/report.txt})에는 목적 · 결과 사유 · 응답 모델 · 지연만 쓰고 원문은 쓰지 않는다(R-M11 · R-F14).
 *       증거 종류는 '실제 API · 합성 자료'다.
 * </ul>
 *
 * <p>시도 시한은 운영 설정 값(application.yml {@code mujoong.text-ai.*})과 같은 값을 쓴다.
 */
@Tag("luna-live")
class CoreLunaLiveTest {

    private static final JsonCodec JSON = JsonCodec.shared();

    private static Path report() {
        String configured = System.getProperty("mujoong.luna.live.report");
        return Path.of(configured != null ? configured : "build/luna-live/report.txt");
    }

    private static void write(String line) throws IOException {
        Path report = report();
        Files.createDirectories(report.getParent());
        Files.writeString(report, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Test
    void everyCorePurposeAnswersARealSyntheticCall() throws IOException {
        String key = System.getenv(LunaTransport.API_KEY_ENV);
        if (key == null || key.isBlank()) {
            write("app=core outcome=SKIPPED_NO_KEY evidence=NONE");
        }
        assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY: env OPENAI_API_KEY 없음(실호출 안 함)");

        MujoongClock clock = new SystemMujoongClock();
        TestEvents events = new TestEvents(clock);
        String modelEnv = System.getenv("OPENAI_ANALYSIS_MODEL");
        LunaProperties.PurposeOptions options = new LunaProperties.PurposeOptions(4000, "medium");
        LunaProperties properties = new LunaProperties(modelEnv, URI.create("https://api.openai.com/v1"), 1_000_000,
                new LunaProperties.Purposes(options, options, options, options, options, options));
        TextAiProperties timing = new TextAiProperties(Duration.ofSeconds(3), new TextAiProperties.Retry(1, Duration.ofMillis(250)),
                new TextAiProperties.Result(Duration.ofSeconds(10), 0, 0.2, "v1"));
        LunaHosts.Mode mode = LunaHosts.modeFor();
        CoreLunaClient client = new CoreLunaClient(properties, timing, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(properties.baseUrl(), mode, () -> System.getenv(LunaTransport.API_KEY_ENV),
                        properties.maxResponseBytes()), events.logger(), clock, JSON, CoreLunaClient.SYSTEM_SLEEPER);
        List<String> problems = new ArrayList<>();

        for (LunaPurpose purpose : List.of(LunaPurpose.SCENARIO_REVIEW, LunaPurpose.SEED_GENERATION, LunaPurpose.GOAL_CANDIDATES,
                LunaPurpose.REJUDGMENT, LunaPurpose.SCENARIO_GENERATION)) {
            LunaRequest request = LunaRequest.of(purpose, "u9-live.v1",
                    "합성 시험 자료다. 입력 데이터를 참고해 지정한 JSON 스키마에 맞는 JSON 객체 하나만 출력한다.",
                    syntheticInput(), Map.of(), clock.now().plusSeconds(30));
            problems.addAll(record(purpose.name(), "CONTRACT", () -> client.call(request)));
        }
        LunaRequest resultText = LunaRequest.legacy(LunaPurpose.RESULT_TEXT, "v1",
                "합성 시험 자료다. {\"sentences\":[{\"text\":\"...\",\"citations\":[]}]} 모양의 JSON 객체 하나만 출력한다.",
                Map.of("task", "sentences", "turns", List.of(Map.of("utteranceSeq", 1, "speaker", "CHILD", "text", "이거 같이 하자"))),
                Map.of(), clock.now().plusSeconds(30));
        problems.addAll(record("RESULT_TEXT", "LEGACY_JSON_OBJECT", () -> client.call(resultText)));
        long started = clock.monotonicNanos();
        String scenarioLine;
        try {
            GeneratedScenario scenario = new LunaTextAiProvider(client, clock, JSON).generateScenario(new ScenarioPrompt(
                    "부탁하기", "필요한 것과 이유를 함께 말한다.", "친구가 블록을 들고 있다.", "블록 놀이", "PEER", 8, List.of(), null),
                    Duration.ofSeconds(3));
            scenarioLine = "outcome=OK model=" + scenario.modelVersion();
        } catch (RuntimeException e) {
            scenarioLine = "outcome=" + e.getClass().getSimpleName() + ":" + e.getMessage().replaceAll("[^A-Za-z_: ]", "");
            problems.add("SCENARIO_GENERATION(adapter) " + scenarioLine);
        }
        write("app=core purpose=SCENARIO_GENERATION mode=TEXT_AI_ADAPTER " + scenarioLine + " latencyMs="
                + clock.elapsedSince(started).toMillis() + " evidence=REAL_API_SYNTHETIC");

        assertThat(problems).as("실호출 결과(보고서 " + report() + ")").isEmpty();
    }

    private static List<String> record(String purpose, String mode, java.util.function.Supplier<LunaResult> call)
            throws IOException {
        try {
            LunaResult result = call.get();
            write("app=core purpose=" + purpose + " mode=" + mode + " outcome=OK model=" + result.responseModel() + " latencyMs="
                    + result.latency().toMillis() + " attempts=" + result.attemptsUsed() + " evidence=REAL_API_SYNTHETIC");
            return List.of();
        } catch (LunaException failure) {
            write("app=core purpose=" + purpose + " mode=" + mode + " outcome=" + failure.reason() + " attempts="
                    + failure.attemptsUsed() + " evidence=REAL_API_SYNTHETIC");
            return List.of(purpose + " " + failure.reason());
        }
    }

    /** 합성 입력(실제 아동 데이터 아님, R-F15). */
    private static Map<String, Object> syntheticInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("goalName", "부탁하기");
        input.put("behaviorDefinition", "필요한 것과 이유를 함께 말한다.");
        input.put("situation", "블록 놀이");
        input.put("utterances", List.of(Map.of("utteranceSeq", 1, "speaker", "CHILD", "text", "그 블록 줄래? 탑 쌓고 싶어")));
        return input;
    }
}
