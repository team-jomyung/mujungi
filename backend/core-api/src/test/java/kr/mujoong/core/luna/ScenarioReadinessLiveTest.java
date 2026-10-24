package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.assertThat;
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

/** Explicit live diagnostic using an invented activity and goal, never a child's data. Excluded from normal tests. */
@Tag("luna-live")
class ScenarioReadinessLiveTest {
    private static final JsonCodec JSON = JsonCodec.shared();
    private static final String SITUATION = "무언가를 하다가 어려운 부분이 생긴 상황";
    private static final String TARGET = "가상 상황에서 하던 일을 짧게 설명하고, 막힌 부분을 구체적으로 말해보기. 예: “지금 ○○을 하다가 △△ 부분에서 막혔어.”";

    @Test
    void explainingADifficultyCanProduceAnIndependentlyApprovedPracticeStart() throws Exception {
        assumeTrue(System.getenv(LunaTransport.API_KEY_ENV) != null, "SKIPPED_NO_KEY");
        var clock = new SystemMujoongClock();
        var events = new TestEvents(clock);
        var ordinary = new LunaProperties.PurposeOptions(4000, "medium");
        var properties = new LunaProperties(null, URI.create("https://api.openai.com/v1"), 1_000_000,
                new LunaProperties.Purposes(new LunaProperties.PurposeOptions(4000, "none"),
                        new LunaProperties.PurposeOptions(4000, "low"), ordinary, ordinary, ordinary, ordinary));
        var timing = new TextAiProperties(Duration.ofSeconds(10),
                new TextAiProperties.Retry(1, Duration.ofMillis(250)),
                new TextAiProperties.Result(Duration.ofSeconds(10), 0, 0.2, "v1"));
        var client = new CoreLunaClient(properties, timing, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(properties.baseUrl(), LunaHosts.modeFor(),
                        () -> System.getenv(LunaTransport.API_KEY_ENV), properties.maxResponseBytes()),
                events.logger(), clock, JSON, CoreLunaClient.SYSTEM_SLEEPER);
        var real = new LunaTextAiProvider(client, clock, JSON);
        Path evidence = Path.of("build/scenario-readiness-synthetic.jsonl");
        Files.deleteIfExists(evidence);
        var provider = new ScenarioScriptProvider() {
            private void record(String kind, Object value) {
                try {
                    Files.writeString(evidence, JSON.write(Map.of("kind", kind, "value", value)) + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
            @Override public Generated generate(Call call) {
                var result = real.generate(call); record("synthetic-script", result.script()); return result;
            }
            @Override public Reviewed review(Call call) {
                var result = real.review(call); record("review", result.review()); return result;
            }
        };
        var payload = Map.<String, Object>of("ageYears", 8, "interests", List.of("책", "블록"),
                "goal", Map.of("kind", "CANDIDATE", "skillRef", "NONE", "targetExpression", TARGET,
                        "fourElements", Map.of("situation", SITUATION, "partner", "도움을 줄 수 있는 대화 상대",
                                "intent", "현재 하는 일과 어려운 지점을 알려주기",
                                "expression", "“지금 ○○을 하다가 △△ 부분에서 막혔어.”처럼 하던 일과 막힌 부분을 함께 말하기")),
                "opportunityCondition", SITUATION, "relation", "도움을 줄 수 있는 대화 상대",
                "history", List.of(), "practiceContinuity", List.of(), "baseOnly", true);
        var rules = new ScenarioScriptValidation.Rules("도움을 줄 수 있는 대화 상대", SITUATION, TARGET,
                Set.of(), null, ignored -> false, (a, b) -> false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var generator = new ScenarioScriptGenerator(provider, new ScenarioScriptValidation(new ForbiddenExpressionPolicy()),
                    timing, executor, CoreLunaClient.SYSTEM_SLEEPER::sleep);
            int rounds = Integer.parseInt(System.getenv().getOrDefault("SCENARIO_READINESS_ROUNDS", "1"));
            for (int round = 0; round < rounds; round++) {
                var result = generator.generate(new ScenarioScriptGenerator.Input(payload, Map.of(), rules, false, "{}"),
                        new ScenarioDeadline(clock, clock.now().plusSeconds(30)));
                assertThat(result.details().review().verdict()).isEqualTo("APPROVE");
                assertThat(result.details().scenes()).isNotEmpty();
                System.out.println("scenario-readiness round=" + round + " outcome=APPROVED evidence=REAL_API_SYNTHETIC");
            }
        }
    }
}
