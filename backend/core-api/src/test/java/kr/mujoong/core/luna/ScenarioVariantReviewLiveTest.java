package kr.mujoong.core.luna;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.core.knowledge.KnowledgeSeed;
import kr.mujoong.core.knowledge.SeedArchiveLoader;
import kr.mujoong.core.knowledge.VariantPlanner;
import kr.mujoong.core.luna.adapter.LunaTextAiProvider;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import kr.mujoong.core.textai.TextAiProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 그래프 맥락 연습 상황에서 첫 BASE 대본을 두 가지 검토 입력으로 비교하는 실호출 진단이다. 지어낸 목표와 기본 씨앗 카탈로그만 쓰고 아이 자료는 쓰지 않는다.
 * A는 운영 생성기가 변형 계획을 붙인 입력(variantPlan 있음, baseOnly 없음), B는 BASE만 요청한 입력(baseOnly=true, variantPlan 없음)이다.
 * 기본 test 작업에서는 빠지고 lunaLiveTest로만 돈다.
 */
@Tag("luna-live")
class ScenarioVariantReviewLiveTest {
    private static final JsonCodec JSON = JsonCodec.shared();

    @Test
    void baseOnlyScriptIsReviewedWithAndWithoutTheVariantPlan() throws Exception {
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
        var provider = new LunaTextAiProvider(client, clock, JSON);
        List<KnowledgeSeed> seeds;
        try (var input = new ClassPathResource("knowledge/social-comm-poc.zip").getInputStream()) {
            seeds = new SeedArchiveLoader().load(input, "poc-v1").seeds();
        }
        int count = Integer.parseInt(System.getenv().getOrDefault("VARIANT_REVIEW_SEEDS", "2"));
        int rounds = Integer.parseInt(System.getenv().getOrDefault("VARIANT_REVIEW_ROUNDS", "3"));
        Path evidence = Path.of("build/scenario-variant-review-synthetic.jsonl");
        Files.deleteIfExists(evidence);
        var planner = new VariantPlanner();
        for (var seed : seeds.stream().filter(KnowledgeSeed::enabled).limit(count).toList()) {
            for (int round = 0; round < rounds; round++) {
                Map<String, Object> base = basePayload(seed);
                ScenarioScriptProvider.Generated generated;
                try {
                    generated = provider.generate(new ScenarioScriptProvider.Call(base, Map.of(), clock.now().plusSeconds(30)));
                } catch (RuntimeException failed) {
                    record(evidence, seed, round, "generation", Map.of("failed", failed.getClass().getSimpleName()));
                    continue;
                }
                var scene = generated.script().scenes().getFirst();
                record(evidence, seed, round, "script", generated.script());
                if (scene.preservation() == null) {
                    record(evidence, seed, round, "plan", Map.of("missing", "preservation"));
                    continue;
                }
                var context = new VariantPlanner.Context(scene.topic(), scene.place(), scene.preservation().participants(),
                        scene.relation(), scene.preservation().situation());
                var plan = planner.plan(seed, context, List.of());
                if (plan.isEmpty()) {
                    record(evidence, seed, round, "plan", Map.of("missing", "plan", "context", context));
                    continue;
                }
                var withPlan = new LinkedHashMap<>(base);
                withPlan.remove("baseOnly");
                withPlan.put("variantPlan", projected(plan.get()));
                withPlan.put("script", generated.script());
                var baseOnly = new LinkedHashMap<>(base);
                baseOnly.put("script", generated.script());
                review(provider, clock, evidence, seed, round, "A_withVariantPlan", withPlan);
                review(provider, clock, evidence, seed, round, "B_baseOnly", baseOnly);
            }
        }
    }

    private static void review(LunaTextAiProvider provider, SystemMujoongClock clock, Path evidence, KnowledgeSeed seed, int round,
            String mode, Map<String, Object> payload) {
        try {
            var reviewed = provider.review(new ScenarioScriptProvider.Call(payload, Map.of(), clock.now().plusSeconds(30)));
            record(evidence, seed, round, mode, reviewed.review());
        } catch (RuntimeException failed) {
            record(evidence, seed, round, mode, Map.of("failed", failed.getClass().getSimpleName()));
        }
    }

    private static Map<String, Object> basePayload(KnowledgeSeed seed) {
        String situation = "같이 활동하다가 " + seed.label() + "이 필요한 상황";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ageYears", 8);
        payload.put("goal", Map.of("kind", "CANDIDATE", "skillRef", seed.seedId(),
                "targetExpression", "가상 상황에서 " + seed.label() + "을 자기 말로 해 보기",
                "fourElements", Map.of("situation", situation, "partner", "같이 활동하는 상대",
                        "intent", seed.label(), "expression", "자기 말로 " + seed.label())));
        payload.put("practiceContinuity", List.of());
        payload.put("opportunityCondition", situation);
        payload.put("relation", seed.relationship());
        payload.put("history", List.of());
        payload.put("structureContext", Map.of("skillRef", seed.seedId(), "signature", seed.signature(),
                "invariantSentence", seed.invariantSentence(), "preservationRule", seed.preservationRule(),
                "relationship", seed.relationship(), "situation", seed.situation()));
        payload.put("baseOnly", true);
        return payload;
    }

    /** 운영 생성기의 withPlan과 같은 모양. */
    private static Map<String, Object> projected(VariantPlanner.Plan plan) {
        return Map.of("grade", plan.grade(), "changedField", plan.changedField(), "context", plan.context(),
                "signature", plan.signature(), "invariantSentence", plan.invariantSentence(),
                "preservationRule", plan.preservationRule(), "misreadCost", plan.misreadCost(), "recoverable", plan.recoverable());
    }

    private static void record(Path evidence, KnowledgeSeed seed, int round, String kind, Object value) {
        try {
            Files.writeString(evidence, JSON.write(Map.of("seed", seed.seedId(), "round", round, "kind", kind, "value", value)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
