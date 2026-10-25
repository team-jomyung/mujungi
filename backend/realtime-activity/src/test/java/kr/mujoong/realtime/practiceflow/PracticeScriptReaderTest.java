package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.BASE_SCENE;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.fullScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeScriptException.Reason;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * U10 대본 읽기 · 시작 패키지 practice 조각 해석 시험 (U12 FR1.3 · BR1.3 · BR1.4). 필수 6필드, 선택 필드 누락의 빈 값 정규화, scriptVersion 없음(새
 * 흐름 아님), 거절 사유가 문장을 담지 않는지, 목표 순서 · 첫 대본 거절 처리를 본다. 서버 · DB 없음(테스트 대역 증거).
 */
@Tag("practice-flow")
class PracticeScriptReaderTest {

    private static final UUID SCENARIO = UUID.randomUUID();

    @Test
    void aFullScriptKeepsEverySceneFieldAndTheOptionalMetadata() {
        PracticeScript script = PracticeScriptReader.read(fullScript(SCENARIO));

        assertThat(script.scenarioId()).isEqualTo(SCENARIO);
        assertThat(script.scriptVersion()).isEqualTo("scenario-script.v1");
        assertThat(script.scenes()).hasSize(2);
        PracticeScript.Scene base = script.baseScene();
        assertThat(base.sceneKey()).isEqualTo("base-1");
        assertThat(base.sceneKind()).isEqualTo(SceneKind.BASE);
        assertThat(base.sceneDescription()).isEqualTo("친구가 상자 두 개를 들고 있어.");
        assertThat(base.opportunitySentence()).isEqualTo("친구가 말했어. 그거 줘.");
        assertThat(base.reexplanation()).isEqualTo("친구는 빨간 상자와 파란 상자를 들고 있어.");
        assertThat(base.partnerRole()).isEqualTo("같은 반 친구");
        assertThat(base.focusPoints()).containsExactly("어느 것인지 말하기");
        assertThat(base.expressionExamples()).containsExactly("파란 상자를 줘");
        assertThat(base.relation()).isEqualTo("친구");
        assertThat(script.variantScene()).get().extracting(PracticeScript.Scene::sceneKey).isEqualTo("variant-1");
        assertThat(script.selfReview()).isEqualTo(new PracticeScript.SelfReview("APPROVE", "fake-model", "scenario-review.v1"));
        assertThat(script.generation()).isEqualTo(new PracticeScript.Generation("fake-model", "scenario-generation.v2", true, 3, "kg-1"));
    }

    @Test
    void missingOptionalFieldsAreNormalizedToEmptyValuesAndNotRejected() {
        PracticeScript script = PracticeScriptReader.read(minimalScript(SCENARIO));

        assertThat(script.selfReview()).isSameAs(PracticeScript.SelfReview.EMPTY);
        assertThat(script.generation()).isSameAs(PracticeScript.Generation.EMPTY);
        assertThat(script.variantScene()).as("변형이 없으면 빈 값(변형은 제안하지 않는다)").isEmpty();
        PracticeScript.Scene base = script.baseScene();
        assertThat(base.focusPoints()).isEmpty();
        assertThat(base.expressionExamples()).isEmpty();
        assertThat(base.relation()).isEmpty();
        assertThat(base.place()).isEmpty();
        assertThat(base.topic()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sceneKey", "sceneKind", "sceneDescription", "opportunitySentence", "reexplanation", "partnerRole"})
    void eachOfTheSixRequiredSceneFieldsIsRequired(String field) {
        String nulled = minimalScript(SCENARIO).replaceAll("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":null");

        assertReason(nulled, Reason.MISSING_SCENE_FIELD);
        assertReason(minimalScript(SCENARIO).replaceAll("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":\"  \""),
                Reason.MISSING_SCENE_FIELD);
    }

    @Test
    void aMissingScriptVersionMeansNotTheNewFlowAndOtherVersionsAreUnsupported() {
        String none = minimalScript(SCENARIO).replace("\"scriptVersion\":\"scenario-script.v1\",", "");

        assertReason(none, Reason.NO_SCRIPT_VERSION);
        assertReason(minimalScript(SCENARIO).replace("scenario-script.v1", "scenario-script.v9"), Reason.UNSUPPORTED_VERSION);
    }

    @Test
    void aDirectAskVersionTwoScriptKeepsTheFiveSpokenFieldsAndTheV1ScriptHasNone() {
        PracticeScript script = PracticeScriptReader.read(PracticeScriptFixtures.directAskScriptWithVariant(SCENARIO));

        assertThat(script.scriptVersion()).isEqualTo(PracticeScript.SCRIPT_VERSION_V2).isEqualTo("scenario-script.v2");
        PracticeScript.Scene base = script.baseScene();
        assertThat(base.spokenIntroduction()).isEqualTo(PracticeScriptFixtures.V2_INTRO);
        assertThat(base.opportunityRelay()).isEqualTo(PracticeScriptFixtures.V2_RELAY);
        assertThat(base.askPrompt()).isEqualTo(PracticeScriptFixtures.V2_ASK);
        assertThat(base.reaskPrompt()).isEqualTo(PracticeScriptFixtures.V2_REASK);
        assertThat(base.helpSpeech()).isEqualTo(PracticeScriptFixtures.V2_HELP);
        assertThat(base.friendReaction()).isEqualTo(PracticeScriptFixtures.V2_REACTION);
        assertThat(base.opportunitySentence()).as("상대의 직접 대사는 들려주지 않는 칸으로 그대로 읽는다")
                .isEqualTo(PracticeScriptFixtures.V2_DIRECT_LINE);
        assertThat(script.variantScene()).get().extracting(PracticeScript.Scene::askPrompt)
                .isEqualTo(PracticeScriptFixtures.V2_VARIANT_ASK);
        assertThat(script.generation().promptVersion()).isEqualTo("scenario-generation.v3");
        assertThat(script.selfReview().promptVersion()).as("C안 대본도 main 검토 판이다").isEqualTo("scenario-review.v1");

        PracticeScript.Scene old = PracticeScriptReader.read(fullScript(SCENARIO)).baseScene();
        assertThat(java.util.Arrays.asList(old.opportunityRelay(), old.askPrompt(), old.reaskPrompt(), old.helpSpeech(),
                old.friendReaction())).as("옛 대본 v1에는 새 칸이 없다(대체 문장을 쓴다)").containsOnlyNulls();
        assertThat(PracticeScript.readable("scenario-script.v1")).isTrue();
        assertThat(PracticeScript.readable("scenario-script.v2")).isTrue();
        assertThat(PracticeScript.readable("scenario-script.v3")).isFalse();
        assertThat(PracticeScript.readable(null)).isFalse();
    }

    @Test
    void aV2ScriptWhoseCoreRemovedSomeFieldsIsReadWithOnlyThoseFieldsMissing() {
        String script = PracticeScriptFixtures.directAskScriptWithVariant(SCENARIO)
                .replaceFirst("\"opportunityRelay\":\"[^\"]*\",", "")
                .replaceFirst("\"spokenIntroduction\":\"[^\"]*\",", "");

        PracticeScript read = PracticeScriptReader.read(script);

        PracticeScript.Scene base = read.baseScene();
        assertThat(base.opportunityRelay()).as("core가 뺀 칸").isNull();
        assertThat(base.spokenIntroduction()).isNull();
        assertThat(base.askPrompt()).isEqualTo(PracticeScriptFixtures.V2_ASK);
        assertThat(base.reaskPrompt()).isEqualTo(PracticeScriptFixtures.V2_REASK);
        assertThat(base.helpSpeech()).isEqualTo(PracticeScriptFixtures.V2_HELP);
        assertThat(base.friendReaction()).isEqualTo(PracticeScriptFixtures.V2_REACTION);
        assertThat(read.variantScene()).get().as("앞 장면에서만 지웠다(변형 장면 칸은 그대로)")
                .extracting(PracticeScript.Scene::opportunityRelay).isEqualTo(PracticeScriptFixtures.V2_VARIANT_RELAY);
        DirectAskLines lines = new DirectAskLines(PracticeScriptFixtures.FIXED_SPEECH);
        assertThat(lines.relay(base)).as("뺀 칸만 대체 문장").isEqualTo("친구가 이렇게 말했어. " + PracticeScriptFixtures.V2_DIRECT_LINE);
        assertThat(lines.introduction(base)).isEqualTo(DirectAskLines.INTRODUCTION_FALLBACK);
        assertThat(lines.ask(base)).isEqualTo(PracticeScriptFixtures.V2_ASK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opportunityRelay", "askPrompt", "reaskPrompt", "helpSpeech", "friendReaction", "spokenIntroduction"})
    void aSpokenFieldThatIsBlankTooLongOrHasAControlCharacterRejectsTheScript(String field) {
        String script = PracticeScriptFixtures.directAskScript(SCENARIO);
        String value = "\"" + field + "\":\"[^\"]*\"";

        assertReason(script.replaceFirst(value, "\"" + field + "\":\"  \""), Reason.VALIDATION_FAILED);
        assertReason(script.replaceFirst(value, "\"" + field + "\":\"" + "가".repeat(501) + "\""), Reason.VALIDATION_FAILED);
        assertReason(script.replaceFirst(value, "\"" + field + "\":\"줄\\\\u0007바꿈\""), Reason.VALIDATION_FAILED);
        assertThat(PracticeScriptReader.read(script.replaceFirst(value + ",", "")).baseScene())
                .as("칸이 없는 것은 거절이 아니라 대체 문장 대상이다").isNotNull();
    }

    @Test
    void theSpokenFieldLimitsAreTheContractMaxLengths() {
        String script = PracticeScriptFixtures.directAskScript(SCENARIO);
        assertThat(PracticeScriptReader.read(script.replaceFirst("\"askPrompt\":\"[^\"]*\"",
                "\"askPrompt\":\"" + "가".repeat(99) + "?\"")).baseScene().askPrompt()).hasSize(100);
        assertReason(script.replaceFirst("\"askPrompt\":\"[^\"]*\"", "\"askPrompt\":\"" + "가".repeat(100) + "?\""),
                Reason.VALIDATION_FAILED);
        assertThat(PracticeScriptReader.read(script.replaceFirst("\"helpSpeech\":\"[^\"]*\"",
                "\"helpSpeech\":\"" + "가".repeat(289) + " 네 말로 해 볼래?\"")).baseScene().helpSpeech()).hasSize(300);
        assertReason(script.replaceFirst("\"helpSpeech\":\"[^\"]*\"", "\"helpSpeech\":\"" + "가".repeat(290) + " 네 말로 해 볼래?\""),
                Reason.VALIDATION_FAILED);
        for (String field : new String[] {"opportunityRelay", "reaskPrompt", "friendReaction"}) {
            assertReason(script.replaceFirst("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":\"" + "가".repeat(201) + "\""),
                    Reason.VALIDATION_FAILED);
        }
    }

    @Test
    void scenarioIdScenesKindAndValidationAreChecked() {
        assertReason(minimalScript(SCENARIO).replace(SCENARIO.toString(), "not-a-uuid"), Reason.BAD_SCENARIO_ID);
        assertReason(minimalScript(SCENARIO).replace("\"scenarioId\":\"" + SCENARIO + "\",", ""), Reason.BAD_SCENARIO_ID);
        assertReason(minimalScript(SCENARIO).replaceAll("\"scenes\":\\[.*\\],\"validation\"", "\"scenes\":[],\"validation\""),
                Reason.NO_SCENES);
        assertReason(minimalScript(SCENARIO).replace("\"sceneKind\":\"BASE\"", "\"sceneKind\":\"SIDE\""), Reason.BAD_SCENE_KIND);
        assertReason(minimalScript(SCENARIO).replace("\"sceneKind\":\"BASE\"", "\"sceneKind\":\"VARIANT\""), Reason.NO_BASE_SCENE);
        assertReason(minimalScript(SCENARIO).replace("\"forbiddenFound\":false", "\"forbiddenFound\":true"), Reason.VALIDATION_FAILED);
        assertReason(minimalScript(SCENARIO).replace("\"goalIncluded\":true", "\"goalIncluded\":false"), Reason.VALIDATION_FAILED);
        assertReason(minimalScript(SCENARIO).replaceAll(",\"validation\":\\{[^}]*\\}", ""), Reason.VALIDATION_FAILED);
    }

    @Test
    void unparsableInputAndAWrongKindAreRejected() {
        assertReason("not json", Reason.UNPARSABLE);
        assertReason("", Reason.UNPARSABLE);
        assertReason("{\"scenes\":\"text\"}", Reason.UNPARSABLE);
        assertThat(PracticeScriptReader.read(minimalScript(SCENARIO), "ORIGINAL").scenarioId()).isEqualTo(SCENARIO);
        assertThatThrownBy(() -> PracticeScriptReader.read(minimalScript(SCENARIO), "NEW_SCENARIO"))
                .isInstanceOfSatisfying(PracticeScriptException.class, e -> assertThat(e.reason()).isEqualTo(Reason.VALIDATION_FAILED));
    }

    @Test
    void rejectionMessagesCarryOnlyTheReasonCodeNeverTheSentences() {
        String broken = minimalScript(SCENARIO).replace("\"sceneKind\":\"BASE\"", "\"sceneKind\":\"SIDE\"");

        assertThatThrownBy(() -> PracticeScriptReader.read(broken)).hasMessageContaining("BAD_SCENE_KIND")
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("친구가").doesNotContain("상자").doesNotContain("그거 줘");
                    assertThat(String.valueOf(e.getCause())).doesNotContain("친구가").doesNotContain("상자");
                });
        assertThat(PracticeScriptReader.read(minimalScript(SCENARIO)).toString()).doesNotContain("친구가").doesNotContain("상자");
        assertThat(PracticeScriptReader.read(minimalScript(SCENARIO)).baseScene().toString()).doesNotContain("친구가");
    }

    @Test
    void theStartPackageReadsGoalsInOrderAndTheFirstScenarioOfTheFirstGoal() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String json = "{\"goals\":[{\"goalSelectionId\":\"" + second + "\",\"order\":2,\"displayName\":\"사과하기\","
                + "\"judgmentCriteria\":\"미안하다고 말한다\",\"firstScenario\":null},"
                + "{\"goalSelectionId\":\"" + first + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"judgmentCriteria\":\"원하는 것을 말로 요청한다\",\"firstScenario\":" + minimalScript(SCENARIO) + "}]}";

        PracticeStartPackage pack = PracticeStartPackage.parse(json);

        assertThat(pack.goals()).extracting(GoalSpec::goalSelectionId).containsExactly(first, second);
        assertThat(pack.goals()).extracting(GoalSpec::goalIndex).containsExactly(0, 1);
        assertThat(pack.goals()).extracting(GoalSpec::goalName).containsExactly("부탁하기", "사과하기");
        assertThat(pack.firstGoal().judgmentCriteria()).isEqualTo("원하는 것을 말로 요청한다");
        assertThat(pack.usesNewFlow()).isTrue();
        assertThat(pack.scriptVersion()).isEqualTo("scenario-script.v1");
        assertThat(pack.firstScenario().scenarioId()).isEqualTo(SCENARIO);
        assertThat(pack.firstScenarioRejection()).isNull();
    }

    @Test
    void aFirstScenarioWithoutScriptVersionIsTheLegacyBranchAndANullOneIsNeither() {
        UUID goal = UUID.randomUUID();
        String legacy = packageJson(goal, "{\"scenarioId\":\"" + SCENARIO + "\",\"kind\":\"ORIGINAL\",\"scenarioText\":\"옛 상황\"}");

        PracticeStartPackage pack = PracticeStartPackage.parse(legacy);

        assertThat(pack.usesNewFlow()).isFalse();
        assertThat(pack.scriptVersion()).isNull();
        assertThat(pack.firstScenario()).isNull();
        assertThat(pack.firstScenarioRejection()).as("거절이 아니라 새 흐름이 아닌 것이다").isNull();
        assertThat(PracticeStartPackage.parse(packageJson(goal, null)).usesNewFlow()).isFalse();
    }

    @Test
    void aNewFlowFirstScenarioThatBreaksTheContractIsRejectedButThePackageStillReads() {
        UUID goal = UUID.randomUUID();
        String broken = minimalScript(SCENARIO).replace("\"forbiddenFound\":false", "\"forbiddenFound\":true");

        PracticeStartPackage pack = PracticeStartPackage.parse(packageJson(goal, broken));

        assertThat(pack.usesNewFlow()).isTrue();
        assertThat(pack.firstScenario()).isNull();
        assertThat(pack.firstScenarioRejection()).isEqualTo(Reason.VALIDATION_FAILED);
        assertThat(pack.goals()).hasSize(1);
    }

    @Test
    void aPackageWithoutGoalsOrWithAnIncompleteGoalIsBadAndTheStringsHideTheCriteria() {
        UUID goal = UUID.randomUUID();
        assertPackageBad(null);
        assertPackageBad("not json");
        assertPackageBad("{}");
        assertPackageBad("{\"goals\":[]}");
        assertPackageBad("{\"goals\":[null]}");
        assertPackageBad(packageJson(goal, null).replace(goal.toString(), "x"));
        assertPackageBad(packageJson(goal, null).replace("\"displayName\":\"부탁하기\",", ""));
        assertPackageBad(packageJson(goal, null).replace("\"judgmentCriteria\":\"원하는 것을 말로 요청한다\"", "\"judgmentCriteria\":\" \""));

        PracticeStartPackage pack = PracticeStartPackage.parse(packageJson(goal, BASE_SCENE.isEmpty() ? null : minimalScript(SCENARIO)));
        assertThat(pack.toString()).doesNotContain("원하는 것을").doesNotContain("부탁하기");
        assertThat(pack.firstGoal().toString()).doesNotContain("원하는 것을").doesNotContain("부탁하기");
    }

    private static void assertReason(String json, Reason reason) {
        assertThatThrownBy(() -> PracticeScriptReader.read(json)).isInstanceOfSatisfying(PracticeScriptException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static void assertPackageBad(String json) {
        assertThatThrownBy(() -> PracticeStartPackage.parse(json)).isInstanceOfSatisfying(PracticeScriptException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.BAD_PACKAGE));
    }
}
