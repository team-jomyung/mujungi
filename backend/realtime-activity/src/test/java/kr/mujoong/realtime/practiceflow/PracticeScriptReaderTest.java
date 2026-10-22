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

    @Test
    void thePartnerOpenerIsReadWhenPresentAndAnOldScriptWithoutItOrABlankOneMeansNoOpener() {
        PracticeScript.Scene withOpener = PracticeScriptReader.read(PracticeScriptFixtures.openerScript(SCENARIO,
                "무중이랑 상자 정리를 한다고 생각해 보자.", "잠깐만, 찾아볼게.", "자, 여기 빨간 상자!", "같은 반 친구")).baseScene();
        PracticeScript.Scene old = PracticeScriptReader.read(minimalScript(SCENARIO)).baseScene();
        PracticeScript.Scene blank = PracticeScriptReader.read(PracticeScriptFixtures.openerScript(SCENARIO,
                "상자를 정리하고 있어.", "   ", "자, 여기!", "친구")).baseScene();

        assertThat(withOpener.partnerOpener()).isEqualTo("잠깐만, 찾아볼게.");
        assertThat(withOpener.hasPartnerOpener()).isTrue();
        assertThat(old.partnerOpener()).as("partnerOpener가 없는 옛 대본은 거절하지 않고 빈 값").isEmpty();
        assertThat(old.hasPartnerOpener()).isFalse();
        assertThat(blank.hasPartnerOpener()).as("공백뿐인 첫 대사는 없는 것으로 본다").isFalse();
        assertThat(withOpener.toString()).doesNotContain("찾아볼게");
    }

    /** 생성 판 v7의 코치 전용 빈칸 예문은 선택 칸이다: 있으면 그대로 읽고, 없는 옛 대본 · 공백 항목은 빈 값으로 둔다(거절하지 않는다). */
    @Test
    void expressionFramesAreReadWhenPresentAndAnOldScriptWithoutThemHasNone() {
        PracticeScript v7 = PracticeScriptReader.read(PracticeScriptFixtures.v7Script(SCENARIO));
        PracticeScript.Scene old = PracticeScriptReader.read(PracticeScriptFixtures.v6Script(SCENARIO, null)).baseScene();
        String blankJson = PracticeScriptFixtures.v7Script(SCENARIO).replace(PracticeScriptFixtures.V7_FRAME, "  ");

        assertThat(v7.baseScene().expressionFrames()).containsExactly(PracticeScriptFixtures.V7_FRAME);
        assertThat(v7.baseScene().firstFrame()).isEqualTo(PracticeScriptFixtures.V7_FRAME);
        assertThat(v7.variantScene().orElseThrow().firstFrame()).isEqualTo(PracticeScriptFixtures.V7_FRAME);
        assertThat(old.expressionFrames()).as("빈칸 예문이 없는 옛 대본은 빈 값").isEmpty();
        assertThat(old.firstFrame()).isEmpty();
        assertThat(PracticeScriptReader.read(blankJson).baseScene().firstFrame()).as("공백 항목은 없는 것으로 본다").isEmpty();
        assertThat(v7.baseScene().toString()).doesNotContain("뭐뭐");
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
