package kr.mujoong.realtime.practiceflow;

import java.util.Map;
import java.util.UUID;

/** 대본 · 시작 패키지 JSON 시험 자료(가짜 값, 한국어 문장은 시험용이다). */
final class PracticeScriptFixtures {

    static final String BASE_SCENE = "{\"sceneKey\":\"base-1\",\"sceneKind\":\"BASE\",\"sceneDescription\":\"친구가 상자 두 개를 들고 있어.\","
            + "\"opportunitySentence\":\"친구가 말했어. 그거 줘.\",\"reexplanation\":\"친구는 빨간 상자와 파란 상자를 들고 있어.\","
            + "\"partnerRole\":\"같은 반 친구\",\"focusPoints\":[\"어느 것인지 말하기\"],"
            + "\"expressionExamples\":[\"파란 상자를 줘\"],\"relation\":\"친구\",\"place\":\"교실\",\"topic\":\"물건 부탁\"}";

    static final String VARIANT_SCENE = "{\"sceneKey\":\"variant-1\",\"sceneKind\":\"VARIANT\",\"sceneDescription\":\"동생이 책을 들고 있어.\","
            + "\"opportunitySentence\":\"동생이 말했어. 그거 줘.\",\"reexplanation\":\"동생은 두 권을 들고 있어.\","
            + "\"partnerRole\":\"동생\"}";

    // ---- C안 대본 판 v2(docs/practice-direct-ask.md 2장 예의 공룡 그림 장면) ----

    static final String V2_INTRO = "친구랑 공룡 그림을 같이 그리는 이야기야. 너는 친구한테 같이 그리자고 했어.";
    static final String V2_RELAY = "그런데 친구가 손이 좀 아파서 그림을 오래 못 그릴 것 같대.";
    static final String V2_ASK = "너라면 친구한테 뭐라고 말할래?";
    static final String V2_REASK = "친구 손이 아프다고 했지? 그 말을 듣고 친구한테 어떤 말을 해 주면 좋을까?";
    static final String V2_REEXPLAIN = "친구가 손이 아파서 그림을 오래 그리기 힘들대.";
    static final String V2_HELP = "친구가 힘들 때는 친구 마음을 먼저 물어보고 다른 방법을 말해 주면 돼. "
            + "예를 들면 손 괜찮아, 그럼 다음에 같이 그리자처럼 말할 수 있어. 네 말로 해 볼래?";
    static final String V2_REACTION = "그렇게 말해 주면 친구도 마음이 편해서 고마워할 거야.";
    /** 친구 말 전하기 + 묻기 한 슬롯. */
    static final String V2_OPPORTUNITY = V2_RELAY + " " + V2_ASK;
    /** 상대의 직접 대사(들려주지 않는 칸). */
    static final String V2_DIRECT_LINE = "손이 좀 아파서 오래 못 그릴 것 같아.";

    static final String V2_VARIANT_INTRO = "동생이랑 블록 탑을 쌓는 이야기야.";
    static final String V2_VARIANT_RELAY = "그런데 동생이 졸려서 그만하고 싶대.";
    static final String V2_VARIANT_ASK = "너라면 동생한테 뭐라고 말할래?";
    static final String V2_VARIANT_REACTION = "그렇게 말해 주면 동생도 마음이 놓일 거야.";

    static final String V2_BASE_SCENE = "{\"sceneKey\":\"base-1\",\"sceneKind\":\"BASE\","
            + "\"sceneDescription\":\"친구와 공룡 그림을 같이 그린다. 친구가 “" + V2_DIRECT_LINE + "”라고 말한다.\","
            + "\"spokenIntroduction\":\"" + V2_INTRO + "\",\"opportunityRelay\":\"" + V2_RELAY + "\",\"askPrompt\":\"" + V2_ASK + "\","
            + "\"reaskPrompt\":\"" + V2_REASK + "\",\"helpSpeech\":\"" + V2_HELP + "\",\"friendReaction\":\"" + V2_REACTION + "\","
            + "\"opportunitySentence\":\"" + V2_DIRECT_LINE + "\",\"reexplanation\":\"" + V2_REEXPLAIN + "\","
            + "\"partnerRole\":\"같은 반 친구\",\"focusPoints\":[\"친구가 힘들 때는 친구 마음을 먼저 물어보고 다른 방법을 말해 주면 돼\"],"
            + "\"expressionExamples\":[\"손 괜찮아? 그럼 다음에 같이 그리자\"],\"relation\":\"친구\",\"place\":\"교실\",\"topic\":\"그림 그리기\"}";

    static final String V2_VARIANT_SCENE = "{\"sceneKey\":\"variant-1\",\"sceneKind\":\"VARIANT\","
            + "\"sceneDescription\":\"동생과 블록 탑을 쌓는다. 동생이 “졸려서 그만하고 싶어.”라고 말한다.\","
            + "\"spokenIntroduction\":\"" + V2_VARIANT_INTRO + "\",\"opportunityRelay\":\"" + V2_VARIANT_RELAY + "\","
            + "\"askPrompt\":\"" + V2_VARIANT_ASK + "\",\"reaskPrompt\":\"동생이 졸리다고 했지? 동생한테 어떤 말을 해 주면 좋을까?\","
            + "\"helpSpeech\":\"동생이 힘들 때는 마음을 먼저 물어봐 주면 돼. 예를 들면 졸리구나, 그럼 내일 또 하자처럼 말할 수 있어. 네 말로 해 볼래?\","
            + "\"friendReaction\":\"" + V2_VARIANT_REACTION + "\",\"opportunitySentence\":\"졸려서 그만하고 싶어.\","
            + "\"reexplanation\":\"동생이 졸려서 블록 놀이를 그만하고 싶대.\",\"partnerRole\":\"동생\"}";

    /** C안 고정 문장(시드 006 · 004, 설계 5장 표 그대로). 시험이 시작 패키지 approvedSpeech로 넘긴다. */
    static final Map<String, String> FIXED_SPEECH = Map.of(
            "HELP_OFFER", "무중이가 조금 도와줄까?",
            "VARIANT_OFFER", "비슷한 이야기로 한 번 더 해 볼까?",
            "RETRY_PROMPT", "네 말로 한 번 말해 볼래?",
            "INPUT_CONFIRM", "잘 못 들었어. 한 번 더 말해 줄래?",
            "SCENE_CLOSE", "알겠어. 이 이야기는 여기까지 할게.",
            "WAIT_LEAD", "천천히 생각해도 돼.",
            "ASK_DEFAULT", "너라면 친구한테 뭐라고 말할래?");

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

    /** C안 대본 판 v2: 원 장면만(변형 없음). */
    static String directAskScript(UUID scenarioId) {
        return v2Script(scenarioId, V2_BASE_SCENE);
    }

    /** C안 대본 판 v2: 원 장면 + 변형 장면. */
    static String directAskScriptWithVariant(UUID scenarioId) {
        return v2Script(scenarioId, V2_BASE_SCENE + "," + V2_VARIANT_SCENE);
    }

    private static String v2Script(UUID scenarioId, String scenes) {
        return "{\"scenarioId\":\"" + scenarioId + "\",\"kind\":\"ORIGINAL\",\"scriptVersion\":\"scenario-script.v2\","
                + "\"relationType\":\"친구\",\"scenes\":[" + scenes + "],"
                + "\"selfReview\":{\"verdict\":\"APPROVE\",\"model\":\"fake-model\",\"promptVersion\":\"scenario-review.v2\"},"
                + "\"generation\":{\"model\":\"fake-model\",\"promptVersion\":\"scenario-generation.v3\",\"graphContextUsed\":true,"
                + "\"calls\":3,\"kgVersion\":\"kg-1\"},"
                + "\"validation\":{\"goalIncluded\":true,\"triggerIncluded\":true,\"forbiddenFound\":false}}";
    }

    /** 목표 하나 짜리 시작 패키지 practice 조각(첫 목표에 firstScenario). */
    static String packageJson(UUID goal, String firstScenarioJson) {
        return "{\"goals\":[{\"goalSelectionId\":\"" + goal + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"behaviorKey\":\"REQUEST\",\"behaviorVersion\":1,\"judgmentCriteria\":\"원하는 것을 말로 요청한다\""
                + (firstScenarioJson == null ? "" : ",\"firstScenario\":" + firstScenarioJson) + "}]}";
    }
}
