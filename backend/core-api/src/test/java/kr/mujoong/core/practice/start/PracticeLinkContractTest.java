package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.GoalContext;
import kr.mujoong.core.practice.ScenarioDeadline;
import kr.mujoong.core.practice.ScenarioRecordPort.Exposure;
import kr.mujoong.core.practice.ScenarioRepository;
import kr.mujoong.core.practice.ScenarioScriptGenerator;
import kr.mujoong.core.practice.ScenarioScriptValidation;
import kr.mujoong.core.practicegoal.FourElements;
import kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView.Kind;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.ScenarioReview;
import kr.mujoong.core.textai.ScenarioScript;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * core ↔ realtime 교차 계약 시험(core 쪽). core가 실제 생성 · 발급 · 시작 패키지 조립 경로로 낸 {@code practice} 조각이 공유 고정 자료
 * {@code contracts/fixtures/practice-start-package.json}과 트리로 같아야 하고, realtime 조립기 출력 모양인
 * {@code contracts/fixtures/practice-record-v2.json}을 노출 정산이 읽어야 한다. realtime 쪽은 같은 이름의 시험이 같은 자료로 본다.
 * 가짜 공급자 · 합성 문장만 쓴다(실제 AI 아님).
 */
@Tag("practice-legacy")
class PracticeLinkContractTest {

    static final UUID GOAL_ONE = UUID.fromString("00000000-0000-4000-8000-000000000a01");
    static final UUID GOAL_TWO = UUID.fromString("00000000-0000-4000-8000-000000000a02");
    static final UUID SCENARIO_ONE = UUID.fromString("00000000-0000-4000-8000-000000000c01");
    static final UUID SCENARIO_TWO = UUID.fromString("00000000-0000-4000-8000-000000000c02");
    static final String OPPORTUNITY = "친구가 그만하자고 해.";
    static final String STOP = "이제 그만할래.";
    static final String TARGET = "그래, 잠깐 쉬자";
    static final String NARRATION = "무중이랑 블록 놀이를 한다고 생각해 보자. 무중이가 친한 친구야. 둘이서 한참 동안 블록 탑을 쌓았어.";
    static final String OPENER = "휴, 오래 했다.";

    private static final JsonMapper TREE = JsonMapper.builder().build();

    static String fixture(String name) throws IOException {
        return Files.readString(WebFixtures.repoRoot().resolve("contracts/fixtures/" + name), StandardCharsets.UTF_8);
    }

    /** 후보 목표(데모 경로). 상대 문구 '친한 친구'가 BASE relation이 된다. */
    static GoalContext candidate() {
        return new GoalContext(Kind.CANDIDATE, "accept-stop", 1, TARGET,
                new FourElements("블록 놀이를 하다가 친구가 그만하자고 할 때", "친한 친구", "친구의 뜻을 받아들인다", TARGET),
                "NONE", null, null);
    }

    static List<FrozenGoal> goals() {
        GoalContext first = candidate();
        return List.of(
                new FrozenGoal(GOAL_ONE, "그만하자는 말 받아들이기", first.carrierKey(), first.carrierVersion(), first.criteria(),
                        10, 1, 2, false, 1, 2, 1, first),
                new FrozenGoal(GOAL_TWO, "차례 말하기", "TAKE_TURN", 1, "자기 차례를 말로 요청한다", 20, 2, 2, false, 1, 1, 2));
    }

    /** 대본 형식 v6: 무중의 설명(기회 조건 · 기회 문장을 옮기지 않음) → 친구 첫 대사 → 친구 기회 문장. */
    static ScenarioScript.Scene base() {
        return new ScenarioScript.Scene("base-1", "BASE", NARRATION, OPENER, STOP,
                "너랑 친구가 오래 블록 탑을 쌓았어. 친구가 블록을 내려놓았어.", List.of("친구가 한 말을 떠올려 보기"), List.of(TARGET),
                "친한 친구", "교실", "블록 놀이", null, null);
    }

    /** 변형 설명은 무중이 변형을 제안할 때 들리고(서버가 '이어서 해 볼까?'를 붙임) 첫 대사는 비운다. */
    static ScenarioScript.Scene variant() {
        return new ScenarioScript.Scene("variant-1", "VARIANT", "블록 탑이 다 됐네. 이번에는 운동장에서 같은 반 친구랑 공놀이를 해.", "",
                "나 이제 그만할래.", "너랑 같은 반 친구가 운동장에서 오래 공놀이를 했어.", List.of("친구가 한 말을 떠올려 보기"), List.of(TARGET),
                "CLASSMATE", "운동장", "공놀이", null, null);
    }

    /**
     * 가짜 공급자 대본(BASE + VARIANT, partnerRole 없음, 검토 APPROVE) → 생성기 → 발급 대본({@link ScenarioRepository#issued}) → 준비 원본
     * → C8 {@code practice} 조각.
     */
    static Map<String, Object> issuedPackage() {
        ScenarioScriptProvider provider = mock(ScenarioScriptProvider.class);
        when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base(), variant())),
                "fake-model"));
        when(provider.review(any())).thenReturn(new ScenarioScriptProvider.Reviewed(new ScenarioReview("APPROVE", List.of()),
                "fake-review"));
        MutableClock clock = new MutableClock();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var generator = new ScenarioScriptGenerator(provider, new ScenarioScriptValidation(new ForbiddenExpressionPolicy()),
                    new TextAiProperties(Duration.ofSeconds(3), new TextAiProperties.Retry(1, Duration.ofMillis(250))), executor,
                    clock::advance);
            var rules = new ScenarioScriptValidation.Rules("친한 친구", OPPORTUNITY, TARGET, Set.of("합성아이"), null, seen -> false,
                    (a, b) -> true);
            var input = new ScenarioScriptGenerator.Input(Map.of("age", 8, "history", List.of()), Map.of("합성아이", "아이"), rules,
                    false, "{}");
            var result = generator.generate(input, new ScenarioDeadline(clock, clock.now().plusSeconds(15)));
            var scenario = ScenarioRepository.issued(SCENARIO_ONE, "ORIGINAL", result.legacy(), result.details());
            var prepared = new PreparedPracticePackageMapper.PreparedSnapshot(UUID.randomUUID(), UUID.randomUUID(), goals(), scenario);
            return new PreparedPracticePackageMapper().c8(prepared);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void theIssuedStartPackageIsTreeEqualToTheSharedFixture() throws IOException {
        JsonNode actual = TREE.readTree(JsonCodec.shared().write(issuedPackage()));
        JsonNode expected = TREE.readTree(fixture("practice-start-package.json"));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void theIssuedV6ScenesCarryTheFriendOpenerAndANarrationThatDoesNotCopyTheOpportunityCondition() {
        JsonNode first = TREE.readTree(JsonCodec.shared().write(issuedPackage())).path("goals").get(0).path("firstScenario");
        JsonNode scenes = first.path("scenes");

        assertThat(scenes.get(0).path("partnerOpener").asString()).isEqualTo(OPENER);
        assertThat(scenes.get(1).path("partnerOpener").asString()).as("변형은 첫 대사 없이 기회 문장으로 시작").isEmpty();
        assertThat(scenes.get(0).path("sceneDescription").asString()).doesNotContain(OPPORTUNITY).doesNotContain(STOP);
        assertThat(first.path("generation").path("promptVersion").asString()).isEqualTo("scenario-generation.v6");
        assertThat(first.path("selfReview").path("promptVersion").asString()).isEqualTo("scenario-review.v4");
        assertThat(first.path("validation").path("goalIncluded").asBoolean()).as("검증 표시는 서버 검사 · 별도 검토 통과의 뜻 그대로").isTrue();
    }

    @Test
    void everyIssuedScenePartnerRoleIsAShortNameNotAnInstruction() {
        JsonNode scenes = TREE.readTree(JsonCodec.shared().write(issuedPackage())).path("goals").get(0).path("firstScenario")
                .path("scenes");

        assertThat(scenes).hasSize(2);
        assertThat(scenes.get(0).path("partnerRole").asString()).isEqualTo("친한 친구");
        assertThat(scenes.get(1).path("partnerRole").asString()).isEqualTo("같은 반 친구");
        for (JsonNode scene : scenes) {
            String role = scene.path("partnerRole").asString();
            assertThat(role.codePointCount(0, role.length())).isLessThanOrEqualTo(20);
            assertThat(role).doesNotContain("역할을 맡아", "평가하지 마", ".");
        }
    }

    @Test
    void theRealtimeRecordFixtureIsReadForExposureReconciliation() throws IOException {
        assertThat(RecordedScenarioExposures.parse(fixture("practice-record-v2.json"), JsonCodec.shared())).containsExactly(
                new Exposure(SCENARIO_ONE, Instant.parse("2026-10-20T01:00:05Z")),
                new Exposure(SCENARIO_TWO, Instant.parse("2026-10-20T01:05:00Z")));
    }
}
