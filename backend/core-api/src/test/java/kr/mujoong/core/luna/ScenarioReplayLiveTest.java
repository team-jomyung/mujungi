package kr.mujoong.core.luna;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.core.luna.adapter.LunaTextAiProvider;
import kr.mujoong.core.practice.ScenarioDeadline;
import kr.mujoong.core.practice.ScenarioScriptGenerator;
import kr.mujoong.core.practice.ScenarioScriptValidation;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import kr.mujoong.core.textai.TextAiProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 로컬 원문 진단: 운영 연습 시작과 같은 생성 입력(SCENARIO_REPLAY_INPUT JSON 파일)을 운영 생성기로 실호출해, 모든 초안 · 검토 결과를 로컬 파일
 * (SCENARIO_REPLAY_OUT, 기본 build/scenario-replay.jsonl)에만 남긴다. 입력 파일은 사람이 로컬 DB에서 뽑아 저장소 밖에 두며 이 시험은 기본
 * test 작업에서 빠지고 lunaLiveTest로만 돈다. 그래프 변형 계획은 재현하지 않는다(BASE 라운드 · 그래프 없는 수정만).
 */
@Tag("luna-live")
class ScenarioReplayLiveTest {
    private static final JsonCodec JSON = JsonCodec.shared();

    @Test
    @SuppressWarnings("unchecked")
    void replaysAPracticeStartInputAndRecordsEveryDraftAndReview() throws Exception {
        assumeTrue(System.getenv(LunaTransport.API_KEY_ENV) != null, "SKIPPED_NO_KEY");
        String inputPath = System.getenv("SCENARIO_REPLAY_INPUT");
        assumeTrue(inputPath != null, "SKIPPED_NO_INPUT");
        Map<String, Object> input = JSON.read(Files.readString(Path.of(inputPath)), Map.class);
        Map<String, Object> payload = (Map<String, Object>) input.get("payload");
        String relation = (String) payload.get("relation");
        String opportunity = (String) payload.get("opportunityCondition");
        String target = (String) input.get("targetExpression");
        var clock = new SystemMujoongClock();
        var events = new TestEvents(clock);
        var ordinary = new LunaProperties.PurposeOptions(4000, "medium");
        var properties = new LunaProperties(null, URI.create("https://api.openai.com/v1"), 1_000_000,
                new LunaProperties.Purposes(new LunaProperties.PurposeOptions(4000, "none"),
                        new LunaProperties.PurposeOptions(4000, "low"), ordinary, ordinary, ordinary, ordinary));
        // 운영 설정과 같게 공통 10초 · 장면 15초(FT-1030-n)
        var timing = new TextAiProperties(Duration.ofSeconds(10), new TextAiProperties.Retry(1, Duration.ofMillis(250)),
                new TextAiProperties.Result(Duration.ofSeconds(10), 0, 0.2, "v1"), new TextAiProperties.Scenario(Duration.ofSeconds(15)));
        var client = new CoreLunaClient(properties, timing, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(),
                        () -> System.getenv(LunaTransport.API_KEY_ENV), properties.maxResponseBytes()),
                events.logger(), clock, JSON, CoreLunaClient.SYSTEM_SLEEPER);
        var real = new LunaTextAiProvider(client, clock, JSON);
        Path out = Path.of(System.getenv().getOrDefault("SCENARIO_REPLAY_OUT", "build/scenario-replay.jsonl"));
        Files.deleteIfExists(out);
        var provider = new ScenarioScriptProvider() {
            @Override public Generated generate(Call call) {
                record(out, "generationInput", Map.of("keys", call.input().keySet(),
                        "correctionReasons", call.input().getOrDefault("correctionReasons", List.of()),
                        "correctionFindings", call.input().getOrDefault("correctionFindings", List.of())));
                var result = real.generate(call); record(out, "draft", result.script()); return result;
            }
            @Override public Reviewed review(Call call) {
                var result = real.review(call); record(out, "review", result.review()); return result;
            }
        };
        var rules = new ScenarioScriptValidation.Rules(relation, opportunity, target, Set.of(), null, ignored -> false, (a, b) -> false);
        int rounds = Integer.parseInt(System.getenv().getOrDefault("SCENARIO_REPLAY_ROUNDS", "3"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var generator = new ScenarioScriptGenerator(provider, new ScenarioScriptValidation(new ForbiddenExpressionPolicy()),
                    timing, executor, CoreLunaClient.SYSTEM_SLEEPER::sleep);
            generator.diagnostics(code -> record(out, "diagnostic", code));
            for (int round = 0; round < rounds; round++) {
                record(out, "attempt", round);
                try {
                    var result = generator.generate(new ScenarioScriptGenerator.Input(payload, Map.of(), rules, false, "{}"),
                            new ScenarioDeadline(clock, clock.now().plusSeconds(60)));
                    record(out, "outcome", Map.of("result", "APPROVED", "calls", result.details().generation().calls()));
                } catch (RuntimeException failure) {
                    record(out, "outcome", Map.of("result", "FAILED", "kind", failure.getClass().getSimpleName()));
                }
            }
        }
    }

    private static void record(Path out, String kind, Object value) {
        try {
            Files.writeString(out, JSON.write(Map.of("kind", kind, "value", value)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
