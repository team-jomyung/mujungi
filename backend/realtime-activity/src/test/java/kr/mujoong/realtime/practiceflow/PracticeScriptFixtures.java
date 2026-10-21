package kr.mujoong.realtime.practiceflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;

/** 대본 · 시작 패키지 JSON 시험 자료(가짜 값, 한국어 문장은 시험용이다). */
final class PracticeScriptFixtures {

    static final String BASE_SCENE = "{\"sceneKey\":\"base-1\",\"sceneKind\":\"BASE\",\"sceneDescription\":\"친구가 상자 두 개를 들고 있어.\","
            + "\"opportunitySentence\":\"친구가 말했어. 그거 줘.\",\"reexplanation\":\"친구는 빨간 상자와 파란 상자를 들고 있어.\","
            + "\"partnerRole\":\"같은 반 친구\",\"focusPoints\":[\"어느 것인지 말하기\"],"
            + "\"expressionExamples\":[\"파란 상자를 줘\"],\"relation\":\"친구\",\"place\":\"교실\",\"topic\":\"물건 부탁\"}";

    static final String VARIANT_SCENE = "{\"sceneKey\":\"variant-1\",\"sceneKind\":\"VARIANT\",\"sceneDescription\":\"동생이 책을 들고 있어.\","
            + "\"opportunitySentence\":\"동생이 말했어. 그거 줘.\",\"reexplanation\":\"동생은 두 권을 들고 있어.\","
            + "\"partnerRole\":\"동생\"}";

    private PracticeScriptFixtures() {
    }

    /** 필수만 담은 대본(선택 필드 · 변형 없음). */
    static String minimalScript(UUID scenarioId) {
        return "{\"scenarioId\":\"" + scenarioId + "\",\"kind\":\"ORIGINAL\",\"scriptVersion\":\"scenario-script.v1\","
                + "\"scenes\":[" + BASE_SCENE.replace(",\"focusPoints\":[\"어느 것인지 말하기\"],\"expressionExamples\":[\"파란 상자를 줘\"],"
                + "\"relation\":\"친구\",\"place\":\"교실\",\"topic\":\"물건 부탁\"", "") + "],"
                + "\"validation\":{\"goalIncluded\":true,\"triggerIncluded\":true,\"forbiddenFound\":false}}";
    }

    /** 선택 필드 · 변형 · 검토 · 생성 메타까지 담은 대본. */
    static String fullScript(UUID scenarioId) {
        return "{\"scenarioId\":\"" + scenarioId + "\",\"kind\":\"ORIGINAL\",\"scriptVersion\":\"scenario-script.v1\","
                + "\"relationType\":\"친구\",\"scenes\":[" + BASE_SCENE + "," + VARIANT_SCENE + "],"
                + "\"selfReview\":{\"verdict\":\"APPROVE\",\"model\":\"fake-model\",\"promptVersion\":\"scenario-review.v1\"},"
                + "\"generation\":{\"model\":\"fake-model\",\"promptVersion\":\"scenario-generation.v2\",\"graphContextUsed\":true,"
                + "\"calls\":3,\"kgVersion\":\"kg-1\"},"
                + "\"validation\":{\"goalIncluded\":true,\"triggerIncluded\":true,\"forbiddenFound\":false}}";
    }

    /**
     * BASE 장면 하나짜리 대본. 설명 · 기회 문장 · 역할 이름을 바꿔 core 규칙(설명이 기회 문장을 포함)이나 옛 지시문 값을 흉내 낸다.
     */
    static String sceneScript(UUID scenarioId, String sceneDescription, String opportunitySentence, String partnerRole) {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("sceneKey", "base-1");
        scene.put("sceneKind", "BASE");
        scene.put("sceneDescription", sceneDescription);
        scene.put("opportunitySentence", opportunitySentence);
        scene.put("reexplanation", "친구는 빨간 상자와 파란 상자를 들고 있어.");
        scene.put("partnerRole", partnerRole);
        return JsonCodec.shared().write(Map.of("scenarioId", scenarioId.toString(), "kind", "ORIGINAL",
                "scriptVersion", "scenario-script.v1", "scenes", List.of(scene),
                "validation", Map.of("goalIncluded", true, "triggerIncluded", true, "forbiddenFound", false)));
    }

    /** 목표 하나 짜리 시작 패키지 practice 조각(첫 목표에 firstScenario). */
    static String packageJson(UUID goal, String firstScenarioJson) {
        return "{\"goals\":[{\"goalSelectionId\":\"" + goal + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"behaviorKey\":\"REQUEST\",\"behaviorVersion\":1,\"judgmentCriteria\":\"원하는 것을 말로 요청한다\""
                + (firstScenarioJson == null ? "" : ",\"firstScenario\":" + firstScenarioJson) + "}]}";
    }
}
