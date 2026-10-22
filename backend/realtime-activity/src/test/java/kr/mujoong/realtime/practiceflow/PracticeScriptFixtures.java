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
     * BASE 장면 하나짜리 대본. 설명 · 기회 문장 · 역할 이름을 바꿔 옛 core 규칙(설명이 기회 문장을 포함)이나 옛 지시문 값을 흉내 낸다.
     */
    static String sceneScript(UUID scenarioId, String sceneDescription, String opportunitySentence, String partnerRole) {
        return openerScript(scenarioId, sceneDescription, null, opportunitySentence, partnerRole);
    }

    /** BASE 장면 하나짜리 대본에 친구 첫 대사(partnerOpener, null이면 칸 없음)를 싣는다. */
    static String openerScript(UUID scenarioId, String sceneDescription, String partnerOpener, String opportunitySentence,
            String partnerRole) {
        return JsonCodec.shared().write(Map.of("scenarioId", scenarioId.toString(), "kind", "ORIGINAL",
                "scriptVersion", "scenario-script.v1",
                "scenes", List.of(scene("base-1", "BASE", sceneDescription, partnerOpener, opportunitySentence, partnerRole)),
                "validation", Map.of("goalIncluded", true, "triggerIncluded", true, "forbiddenFound", false)));
    }

    /** 판 v6 대본의 BASE 설명(무중이 역할 밖에서 하는 상황 사실). */
    static final String V6_BASE_DESCRIPTION = "무중이랑 상자 정리를 한다고 생각해 보자. 무중이가 같은 반 친구야. 네가 친구한테 파란 상자를 달라고 했어.";
    /** 판 v6 대본의 VARIANT 설명(무중의 장면 전환 말, 서버가 '이어서 해 볼까?'를 붙인다). */
    static final String V6_VARIANT_DESCRIPTION = "상자 정리 다 했네. 이번엔 간식 시간이야. 네가 친구한테 사과 주스를 달라고 했어.";
    static final String V6_BASE_OPENER = "알았어, 잠깐만!";
    static final String V6_BASE_OPPORTUNITY = "자, 여기 빨간 상자!";
    static final String V6_VARIANT_OPPORTUNITY = "자, 포도 주스 여기!";

    /**
     * 판 v6 모양의 두 장면 대본: BASE는 친구 첫 대사가 있고(core 필수), VARIANT 설명은 무중의 장면 전환 말이다. {@code variantOpener}가 null이면
     * VARIANT 첫 대사는 빈 문자열(생성 출력 규칙)이다.
     */
    static String v6Script(UUID scenarioId, String variantOpener) {
        Map<String, Object> base = scene("base-1", "BASE", V6_BASE_DESCRIPTION, V6_BASE_OPENER, V6_BASE_OPPORTUNITY, "같은 반 친구");
        base.put("focusPoints", List.of("달라고 한 것과 받은 것이 같은지 보기"));
        base.put("expressionExamples", List.of("아니, 나 파란 상자 달라고 했어."));
        Map<String, Object> variant = scene("variant-1", "VARIANT", V6_VARIANT_DESCRIPTION, variantOpener == null ? "" : variantOpener,
                V6_VARIANT_OPPORTUNITY, "같은 반 친구");
        variant.put("focusPoints", List.of("달라고 한 것과 받은 것이 같은지 보기"));
        variant.put("expressionExamples", List.of("아니, 나 사과 주스 달라고 했어."));
        return JsonCodec.shared().write(Map.of("scenarioId", scenarioId.toString(), "kind", "ORIGINAL",
                "scriptVersion", "scenario-script.v1", "scenes", List.of(base, variant),
                "validation", Map.of("goalIncluded", true, "triggerIncluded", true, "forbiddenFound", false)));
    }

    /** 장면 JSON 한 건(빈 값이 아닌 선택 칸만 싣는다). 코치 자료는 상자 고르기 장면 것이다. */
    static Map<String, Object> scene(String key, String kind, String sceneDescription, String partnerOpener,
            String opportunitySentence, String partnerRole) {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("sceneKey", key);
        scene.put("sceneKind", kind);
        scene.put("sceneDescription", sceneDescription);
        if (partnerOpener != null) {
            scene.put("partnerOpener", partnerOpener);
        }
        scene.put("opportunitySentence", opportunitySentence);
        scene.put("reexplanation", "친구는 빨간 상자와 파란 상자를 들고 있어.");
        scene.put("partnerRole", partnerRole);
        return scene;
    }

    /** 목표 하나 짜리 시작 패키지 practice 조각(첫 목표에 firstScenario). */
    static String packageJson(UUID goal, String firstScenarioJson) {
        return "{\"goals\":[{\"goalSelectionId\":\"" + goal + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"behaviorKey\":\"REQUEST\",\"behaviorVersion\":1,\"judgmentCriteria\":\"원하는 것을 말로 요청한다\""
                + (firstScenarioJson == null ? "" : ",\"firstScenario\":" + firstScenarioJson) + "}]}";
    }
}
