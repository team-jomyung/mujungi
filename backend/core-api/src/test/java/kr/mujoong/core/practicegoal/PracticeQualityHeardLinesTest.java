package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.knowledge.KnowledgeSeed;
import kr.mujoong.core.knowledge.SeedArchiveLoader;
import kr.mujoong.core.luna.LunaPurpose;
import kr.mujoong.core.practice.Scenario;
import kr.mujoong.core.practice.ScenarioScriptGenerator.Note;
import kr.mujoong.core.textai.ScenarioScript;
import org.junit.jupiter.api.Test;

/**
 * 실호출 품질 하니스의 '아이가 듣는 순서' 보기와 참고 점검({@link PracticeQualityLiveTest.HeardLines}) · VARIANT 진단
 * ({@link PracticeQualityLiveTest.VariantDiagnosis}) 시험. 하니스는 키가 있어야 돌므로 이 부분만 합성 대본 · 합성 호출 기록으로 기본 시험에서 확인한다
 * (실제 AI 없음, 테스트 대역 증거).
 */
class PracticeQualityHeardLinesTest {

    private static final String CONDITION = "친구가 부탁한 것과 다른 것을 줬어.";

    @Test
    void theHeardOrderIsMujoongThenTheFriendAndTheChecksFlagACopiedConditionAHeardTargetAndPraise() {
        var base = new ScenarioScript.Scene("base-1", "BASE", "무중이랑 공룡 그림을 그린다고 생각해 보자. " + CONDITION, "알았어, 잠깐만!", "자, 여기 초록색!",
                "너는 파란색을 달라고 했어.", List.of("달라고 한 것과 받은 것 보기"), List.of("아니, 나 파란색 달라고 했어."), "FRIEND_CLOSE", "교실", "그림",
                null, "같은 모둠 친구");
        var variant = new ScenarioScript.Scene("variant-1", "VARIANT", "그림 다 그렸네. 잘했어! 이번엔 쉬는 시간이야.", "", "아니, 나 딸기 우유 달라고 했어? 자, 초코 우유 여기!",
                "쉬는 시간이야.", List.of(), List.of("아니, 나 딸기 우유 달라고 했어."), "FRIEND_CLOSE", "교실", "간식", null, "같은 모둠 친구");
        Scenario scenario = new Scenario(UUID.randomUUID(), "ORIGINAL", "같은 모둠 친구", CONDITION, "글", "지문", "자, 여기 초록색!",
                new Scenario.Validation(true, true, false), "scenario-script.v1", List.of(base, variant), null, null);

        List<Map<String, Object>> heard = PracticeQualityLiveTest.HeardLines.of(scenario, "아니, 나 파란색 달라고 했어");

        assertThat(heard).hasSize(2);
        assertThat(heard.get(0).get("heardOrder")).isEqualTo(List.of("무중: 무중이랑 공룡 그림을 그린다고 생각해 보자. " + CONDITION,
                "친구: 알았어, 잠깐만!", "친구: 자, 여기 초록색!"));
        assertThat(heard.get(0).get("checks")).isEqualTo(Map.of("targetHeard", false, "opportunityConditionCopied", true, "praiseWords", List.of(),
                "partnerOpenerPresent", true, "descriptionMentionsOpportunityWords", List.of(), "expressionFrames", Map.of("present", false)));
        assertThat(heard.get(1).get("heardOrder")).as("변형은 무중의 제안 뒤 친구 기회 문장(빈 첫 대사는 없다)")
                .isEqualTo(List.of("무중: 그림 다 그렸네. 잘했어! 이번엔 쉬는 시간이야. 이어서 해 볼까?", "친구: 아니, 나 딸기 우유 달라고 했어? 자, 초코 우유 여기!"));
        assertThat(heard.get(1).get("checks")).isEqualTo(Map.of("targetHeard", true, "opportunityConditionCopied", false, "praiseWords",
                List.of("잘했"), "descriptionMentionsOpportunityWords", List.of(), "expressionFrames", Map.of("present", false),
                "variantOpportunity", Map.of("sameAsBase", false, "sharedKeyWords", List.of())));

        Map<String, Object> totals = PracticeQualityLiveTest.HeardLines.totals(List.of(Map.of("heard", heard)));
        assertThat(totals).containsEntry("scenes", 2).containsEntry("targetHeard", 1).containsEntry("opportunityConditionCopied", 1)
                .containsEntry("praiseWords", 1).containsEntry("baseWithoutPartnerOpener", 0)
                .containsEntry("descriptionMentionsOpportunityWords", 0).containsEntry("scenesWithoutExpressionFrames", 2)
                .containsEntry("expressionFramesNotOneToOneOrWithoutBlank", 0).containsEntry("variantOpportunitySameAsBase", 0)
                .containsEntry("variantOpportunitySharesKeyWords", 0);
        assertThat(PracticeQualityLiveTest.HeardLines.of(null, "x")).isEmpty();
    }

    /**
     * 실측 F2 · F5(docs/practice-dialogue-standard.md 12.2.1)의 모양을 참고 점검이 잡는다: 설명이 단서(초록색)를 미리 말함(s6), 꾸밈말 '잠깐'만 비운
     * 빈칸 예문(s10), 소재(크레파스)가 그대로인 변형(s10 · x4), BASE와 같은 기회 문장.
     */
    @Test
    void theV7ChecksFlagACueNamedInTheDescriptionATrivialFrameAndAVariantThatKeepsTheMaterial() {
        var base = new ScenarioScript.Scene("base-1", "BASE", "무중이랑 그림을 그린다고 생각해 보자. 너는 파란색을 달라고 했는데 무중이가 초록색 크레파스를 건넸어.",
                "나는 하늘을 칠하고 있어.", "아, 네가 말한 건 초록색인 줄 알았어.", "너는 파란색을 달라고 했어.", List.of("빌려 달라고 부탁하기"),
                List.of("형, 다 쓰고 나서 잠깐 빌려줄 수 있어?"), List.of("형, 다 쓰고 나서 뭐뭐 빌려줄 수 있어?"), "CLUB_SENIOR", "교실", "그림", null,
                "동아리 선배");
        var sameMaterial = new ScenarioScript.Scene("variant-1", "VARIANT", "이번엔 다른 그림이야.", "", "다 쓰면 크레파스 돌려줘.", "다른 그림이야.",
                List.of(), List.of("나도 크레파스 써도 돼?"), List.of("나도 뭐뭐 써도 돼?"), "CLUB_SENIOR", "교실", "그림", null, "동아리 선배");
        var withMaterial = new ScenarioScript.Scene(base.sceneKey(), base.sceneKind(), base.sceneDescription(), base.partnerOpener(),
                "자, 크레파스 여기 있어.", base.reexplanation(), base.focusPoints(), base.expressionExamples(), base.expressionFrames(), base.relation(),
                base.place(), base.topic(), null, base.partnerRole());

        var heard = PracticeQualityLiveTest.HeardLines.of(scenario(base, sameMaterial), "다 쓰면 빌려줘");
        Map<String, Object> baseChecks = checks(heard.get(0));
        assertThat(baseChecks.get("descriptionMentionsOpportunityWords")).as("설명이 기회 문장의 단서 낱말을 미리 말함").isEqualTo(List.of("초록색"));
        assertThat(baseChecks.get("expressionFrames")).isEqualTo(Map.of("present", true, "oneToOne", true, "allBlanked", true,
                "blankedWords", List.of(List.of("잠깐"))));
        assertThat(checks(heard.get(1)).get("expressionFrames")).isEqualTo(Map.of("present", true, "oneToOne", true, "allBlanked", true,
                "blankedWords", List.of(List.of("크레파스"))));

        var kept = PracticeQualityLiveTest.HeardLines.of(scenario(withMaterial, sameMaterial), "다 쓰면 빌려줘");
        assertThat(checks(kept.get(1)).get("variantOpportunity")).as("소재가 그대로인 변형")
                .isEqualTo(Map.of("sameAsBase", false, "sharedKeyWords", List.of("크레파스")));
        var copied = new ScenarioScript.Scene(sameMaterial.sceneKey(), "VARIANT", sameMaterial.sceneDescription(), "", base.opportunitySentence(),
                sameMaterial.reexplanation(), List.of(), List.of("나도 크레파스 써도 돼?"), List.of("나도 크레파스 써도 돼?"), sameMaterial.relation(),
                sameMaterial.place(), sameMaterial.topic(), null, sameMaterial.partnerRole());
        var same = PracticeQualityLiveTest.HeardLines.of(scenario(base, copied), "다 쓰면 빌려줘");
        assertThat(checks(same.get(1)).get("variantOpportunity")).isEqualTo(Map.of("sameAsBase", true, "sharedKeyWords",
                List.of("말한", "초록색", "알았어")));
        assertThat(checks(same.get(1)).get("expressionFrames")).as("'뭐뭐'가 없는 빈칸 예문")
                .isEqualTo(Map.of("present", true, "oneToOne", true, "allBlanked", false, "blankedWords", List.of("UNALIGNED")));
        Map<String, Object> totals = PracticeQualityLiveTest.HeardLines.totals(List.of(Map.of("heard", heard), Map.of("heard", kept),
                Map.of("heard", same)));
        assertThat(totals).containsEntry("descriptionMentionsOpportunityWords", 3).containsEntry("scenesWithoutExpressionFrames", 0)
                .containsEntry("variantOpportunitySameAsBase", 1).containsEntry("variantOpportunitySharesKeyWords", 2)
                .containsEntry("expressionFramesNotOneToOneOrWithoutBlank", 1);
    }

    /**
     * 실측 s8 · s1 모양(F1): 진단은 하니스 추정이 아니라 생성기가 낸 사유 코드로 정하고 뜻을 싣는다. 지난 실측의 s8 진단('서버 검사가 변형을 뺌')은 실제
     * 원인과 달랐다.
     */
    @Test
    void theVariantDiagnosisCarriesTheGeneratorPathAndDecidesFromTheGeneratorNotes() throws Exception {
        KnowledgeSeed seed;
        try (var source = getClass().getResourceAsStream("/knowledge/social-comm-poc.zip")) {
            seed = new SeedArchiveLoader().load(source, "poc-v1").seeds().getFirst();
        }
        List<Map<String, Object>> calls = calls(seed);
        Scenario baseOnly = scenario(seed, false, "BASE_ONLY_EARLIER_REVIEW");

        Map<String, Object> s1 = PracticeQualityLiveTest.VariantDiagnosis.of(baseOnly, seed.seedId(), seed, calls,
                List.of(Note.BASE_APPROVED_ALONE, Note.BASE_REPLACED, Note.VARIANT_REJECTED, Note.REVIEW_REUSED, Note.BASE_ONLY_EARLIER_REVIEW));
        assertThat(s1).containsEntry("issued", false).containsEntry("diagnosis", "VARIANT_REJECTED_BY_REVIEW").containsEntry("source", "GENERATOR_NOTES")
                .containsEntry("basePreservation", "BASE_ONLY_EARLIER_REVIEW");
        assertThat(s1.get("generatorPath")).isEqualTo(List.of("BASE 단독 검토 승인", "BASE 바꿔 끼움(다음 생성이 고친 보존 BASE를 검토 전에 되돌림)",
                "검토가 변형 사유로만 거절", "단독 검토 승인을 다시 씀(검토 호출 없음)", "BASE만 발급(추가 검토 없음)"));

        Map<String, Object> dropped = PracticeQualityLiveTest.VariantDiagnosis.of(baseOnly, seed.seedId(), seed, calls,
                List.of(Note.BASE_APPROVED_ALONE, Note.VARIANT_INVALID, Note.REVIEW_REUSED, Note.BASE_ONLY_EARLIER_REVIEW));
        assertThat(dropped).containsEntry("diagnosis", "VARIANT_DROPPED_BY_SERVER_CHECK").containsEntry("source", "GENERATOR_NOTES");

        Map<String, Object> unexplained = PracticeQualityLiveTest.VariantDiagnosis.of(baseOnly, seed.seedId(), seed, calls, List.of());
        assertThat(unexplained).as("사유 코드가 없으면 추정하지 않는다").containsEntry("diagnosis", "UNEXPLAINED").containsEntry("source", "NONE");

        Map<String, Object> s8 = PracticeQualityLiveTest.VariantDiagnosis.of(scenario(seed, true, "BASE_REPLACED"), seed.seedId(), seed, calls,
                List.of(Note.BASE_APPROVED_ALONE, Note.BASE_REPLACED));
        assertThat(s8).containsEntry("issued", true).containsEntry("diagnosis", "VARIANT_ISSUED").containsEntry("basePreservation", "BASE_REPLACED");
        for (Note note : Note.values()) {
            assertThat(PracticeQualityLiveTest.VariantDiagnosis.label(note)).as(note.name()).isNotBlank();
        }
    }

    private static Scenario scenario(ScenarioScript.Scene... scenes) {
        return new Scenario(UUID.randomUUID(), "ORIGINAL", "동아리 선배", CONDITION, "글", "지문", scenes[0].opportunitySentence(),
                new Scenario.Validation(true, true, false), "scenario-script.v1", List.of(scenes), null, null);
    }

    private static Scenario scenario(KnowledgeSeed seed, boolean withVariant, String basePreservation) {
        var base = new ScenarioScript.Scene("base-1", "BASE", "놀이 장면이야.", "준비됐어.", "자, 여기!", "놀이 장면이야.", List.of(), List.of(), "FRIEND_CLOSE",
                "교실", "놀이", null, "친구");
        var variant = new ScenarioScript.Scene("variant-1", "VARIANT", "이번엔 그림이야.", "", "이것 봐!", "그림이야.", List.of(), List.of(), "FRIEND_CLOSE",
                "교실", "그림", null, "친구");
        return new Scenario(UUID.randomUUID(), "ORIGINAL", "친구", CONDITION, "글", "지문", "자, 여기!", new Scenario.Validation(true, true, false),
                "scenario-script.v1", withVariant ? List.of(base, variant) : List.of(base), null,
                new Scenario.Generation("fake", "scenario-generation.v7", true, 4, seed.seedId(), basePreservation));
    }

    /** 그래프 맥락 · 보존 값 · 어휘 · 계획 요청 · VARIANT 출력이 모두 있는 합성 호출 기록(앞 단계 진단을 모두 지난다). */
    private static List<Map<String, Object>> calls(KnowledgeSeed seed) {
        Map<String, Object> preservation = new LinkedHashMap<>();
        preservation.put("skillRef", seed.seedId());
        preservation.put("invariantSentence", seed.invariantSentence());
        preservation.put("preservationRule", seed.preservationRule());
        preservation.put("participants", "아이와 친구");
        preservation.put("relationship", "FRIEND_CLOSE");
        preservation.put("situation", "FRIEND_PRIVATE");
        Map<String, Object> baseScene = Map.of("sceneKind", "BASE", "topic", "놀이", "place", "교실", "preservation", preservation);
        Map<String, Object> first = call(LunaPurpose.SCENARIO_GENERATION, Map.of("structureContext", Map.of()), Map.of("scenes", List.of(baseScene)));
        Map<String, Object> second = call(LunaPurpose.SCENARIO_GENERATION, Map.of("structureContext", Map.of(), "variantPlan", Map.of()),
                Map.of("scenes", List.of(baseScene, Map.of("sceneKind", "VARIANT"))));
        Map<String, Object> review = call(LunaPurpose.SCENARIO_REVIEW, Map.of("script", Map.of("scenes", List.of(baseScene, Map.of("sceneKind", "VARIANT")))),
                Map.of("verdict", "REJECT", "reasons", List.of("UNCHANGED_VARIANT")));
        return List.of(first, second, review);
    }

    private static Map<String, Object> call(LunaPurpose purpose, Map<String, Object> input, Map<String, Object> output) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("purpose", purpose.name());
        call.put("outcome", "OK");
        call.put("input", input);
        call.put("output", output);
        return call;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checks(Map<String, Object> scene) {
        return (Map<String, Object>) scene.get("checks");
    }
}
