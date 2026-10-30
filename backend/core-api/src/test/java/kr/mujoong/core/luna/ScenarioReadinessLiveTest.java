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
        probe("difficulty", SITUATION, "도움을 줄 수 있는 대화 상대", "현재 하는 일과 어려운 지점을 알려주기",
                "“지금 ○○을 하다가 △△ 부분에서 막혔어.”처럼 하던 일과 막힌 부분을 함께 말하기", TARGET);
    }

    @Test
    void anAmbiguousPartnerStateCanProduceAnIndependentlyApprovedPracticeStart() throws Exception {
        probe("ambiguous-state", "상대가 잠시 말이 없거나 응답 상태가 불분명한 상황", "응답을 기다리는 대화 상대",
                "상대가 생각 중인지 응답을 마쳤는지 확인하기", "“지금 생각 중이야, 아니면 답변을 마친 거야?”처럼 상태를 묻기",
                "상대의 말이나 응답 상태가 불분명한 가상 상황에서, 현재 상태를 확인하는 질문을 해보기. 예: “지금 생각 중이야, 아니면 답변을 마친 거야?”");
    }

    @Test
    void askingToRepeatAMissedLineCanProduceAnIndependentlyApprovedPracticeStart() throws Exception {
        probe("ask-repeat", "대화 중 상대의 말을 듣지 못했을 때", "방금 말한 상대", "놓친 내용을 다시 들을 수 있도록 요청하기",
                "“방금 한 말을 못 들었어. 다시 말해 줄래?”",
                "상대의 말을 듣지 못했을 때, 어떤 말을 놓쳤는지 알려 주고 다시 말해 달라고 한다. 예: “방금 한 말을 못 들었어. 다시 말해 줄래?”");
    }

    private Path evidence;

    private void record(String kind, Object value) {
        try {
            Files.writeString(evidence, JSON.write(Map.of("kind", kind, "value", value)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private void probe(String name, String situation, String partner, String intent, String expression, String target) throws Exception {
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
        Path evidence = Path.of("build/scenario-readiness-" + name + "-synthetic.jsonl");
        Files.deleteIfExists(evidence);
        this.evidence = evidence;
        var provider = new ScenarioScriptProvider() {
            @Override public Generated generate(Call call) {
                var result = real.generate(call); record("synthetic-script", result.script()); return result;
            }
            @Override public Reviewed review(Call call) {
                record("reviewed-synthetic-script", call.input().get("script"));
                var result = real.review(call); record("review", result.review()); return result;
            }
        };
        // 운영처럼 관심사는 보내지 않는다(FT-1028-b)
        var payload = Map.<String, Object>of("ageYears", 8,
                "goal", Map.of("kind", "CANDIDATE", "skillRef", "NONE", "targetExpression", target,
                        "fourElements", Map.of("situation", situation, "partner", partner,
                                "intent", intent, "expression", expression)),
                "opportunityCondition", situation, "relation", partner,
                "history", List.of(), "practiceContinuity", List.of(), "baseOnly", true);
        var rules = new ScenarioScriptValidation.Rules(partner, situation, target,
                Set.of(), null, ignored -> false, (a, b) -> false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var generator = new ScenarioScriptGenerator(provider, new ScenarioScriptValidation(new ForbiddenExpressionPolicy()),
                    timing, executor, CoreLunaClient.SYSTEM_SLEEPER::sleep);
            int rounds = Integer.parseInt(System.getenv().getOrDefault("SCENARIO_READINESS_ROUNDS", "1"));
            // 비교 측정용(SCENARIO_READINESS_CONTINUE=true): 한 회차가 실패해도 결과를 남기고 다음 회차로 간다. 기본은 첫 실패에서 멈춘다
            // 운영 상황 준비 예산과 같게 둔다(FT-1030-a 60초). 다른 값으로 비교할 때만 env로 바꾼다
            var deadline = Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("SCENARIO_READINESS_DEADLINE_SECONDS", "60")));
            boolean keepGoing = Boolean.parseBoolean(System.getenv().getOrDefault("SCENARIO_READINESS_CONTINUE", "false"));
            for (int round = 0; round < rounds; round++) {
                record("round", Map.of("round", round));
                try {
                    var result = generator.generate(new ScenarioScriptGenerator.Input(payload, Map.of(), rules, false, "{}"),
                            new ScenarioDeadline(clock, clock.now().plus(deadline)));
                    assertThat(result.details().review().verdict()).isEqualTo("APPROVE");
                    assertThat(result.details().scenes()).isNotEmpty();
                    record("outcome", Map.of("round", round, "result", "APPROVED", "calls", result.details().generation().calls()));
                } catch (RuntimeException failure) {
                    record("outcome", Map.of("round", round, "result", "FAILED"));
                    if (!keepGoing) throw failure;
                }
            }
        }
    }
}
