package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Slot;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.Fact;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * core ↔ realtime 교차 계약 시험(realtime 쪽). core 출력 그대로인 공유 고정 자료 {@code contracts/fixtures/practice-start-package.json}으로
 * 새 연습 흐름이 한 장면을 진행하고, 기록 조립기 출력이 {@code contracts/fixtures/practice-record-v2.json}(core 노출 정산이 읽는 모양)과
 * 트리로 같아야 한다. core 쪽은 같은 이름의 시험이 같은 자료로 본다. 테스트 대역 · 합성 대화 증거다(실제 AI · 기기 아님).
 */
@Tag("practice-flow")
class PracticeLinkContractTest {

    private static final JsonMapper TREE = JsonMapper.builder().build();
    private static final UUID GOAL_ONE = UUID.fromString("00000000-0000-4000-8000-000000000a01");
    private static final UUID GOAL_TWO = UUID.fromString("00000000-0000-4000-8000-000000000a02");
    private static final UUID SCENARIO_ONE = UUID.fromString("00000000-0000-4000-8000-000000000c01");
    private static final UUID SCENARIO_TWO = UUID.fromString("00000000-0000-4000-8000-000000000c02");

    static String fixture(String name) throws IOException {
        String root = System.getProperty("mujoong.repo.root");
        Path base = root == null || root.isBlank() ? Path.of("..", "..") : Path.of(root);
        return Files.readString(base.resolve("contracts/fixtures/" + name), StandardCharsets.UTF_8);
    }

    @Test
    void theCoreStartPackageIsReadAsTheNewFlowWithShortPartnerNames() throws IOException {
        PracticeStartPackage pack = PracticeStartPackage.parse(fixture("practice-start-package.json"));

        assertThat(pack.usesNewFlow()).isTrue();
        assertThat(pack.goals()).extracting(GoalSpec::goalSelectionId).containsExactly(GOAL_ONE, GOAL_TWO);
        assertThat(pack.firstScenario().scenarioId()).isEqualTo(SCENARIO_ONE);
        assertThat(pack.firstScenario().scenes()).extracting(Scene::sceneKind).containsExactly(SceneKind.BASE, SceneKind.VARIANT);
        assertThat(pack.firstScenario().baseScene().partnerRole()).isEqualTo("친한 친구");
        assertThat(pack.firstScenario().variantScene().orElseThrow().partnerRole()).isEqualTo("같은 반 친구");
        Scene base = pack.firstScenario().baseScene();
        assertThat(SceneIntro.carriesOpportunity(base.sceneDescription(), base.opportunitySentence()))
                .as("고정 자료의 BASE 설명은 기회 문장으로 끝난다(재생 생략 경로를 지난다)").isTrue();
    }

    @Test
    void theCoreStartPackageRunsOneSceneWithTheOpportunitySentenceHeardOnceAndTheLabelInThePeerInstruction()
            throws IOException {
        String json = fixture("practice-start-package.json");
        Scene base = PracticeStartPackage.parse(json).firstScenario().baseScene();
        Harness h = new Harness(json, Map.of());

        h.activate();
        assertThat(h.out.lastSlot().generated()).isFalse();
        assertThat(h.out.lastSlot().text()).as("도입 = 장면 설명 원문").isEqualTo(base.sceneDescription());
        assertThat(h.out.lastSlot().speech().partnerRole()).isEqualTo("친한 친구");
        int slots = h.out.slots.size();
        h.played();
        assertThat(h.out.slots).as("기회 문장을 다시 재생하지 않는다").hasSize(slots);
        assertThat(h.out.timers).as("입력 대기").containsKey("no-response");

        h.child("그래 쉬자");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        String sentence = SceneIntro.normalize(base.opportunitySentence());
        assertThat(h.out.slots.stream().filter(slot -> !slot.generated())
                .filter(slot -> SceneIntro.normalize(slot.text()).contains(sentence)).count())
                .as("입력 대기까지 기회 문장 재생 1회").isEqualTo(1);

        h.result("J2", "PARTIAL");
        Slot reask = h.out.lastSlot();
        assertThat(reask.generated()).isTrue();
        assertThat(reask.role()).isEqualTo("PEER");
        assertThat(reask.text()).contains("너는 아이의 친한 친구 역할이다", "아이 대신 대답하지 않는다", "정답이나 모범 문장을 먼저 말하지 말고");

        // 진행이 남긴 장면 행이 core 정산이 읽는 칸(scenarioRef · sceneKind · goalRef · startedAt)을 고정 자료 2의 첫 장면과 같게 싣는다
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.list()).hasSize(1);
        assertThat(row.scenarioRef()).as("고정 자료 1 firstScenario.scenarioId").isEqualTo(SCENARIO_ONE);
        assertThat(row.goalRef()).isEqualTo(GOAL_ONE);
        assertThat(row.sceneKind()).isEqualTo(SceneKind.BASE);
        JsonNode recorded = TREE.readTree(JsonCodec.shared().write(h.recorder.practiceRecord())).path("scenes").get(0);
        JsonNode shared = TREE.readTree(fixture("practice-record-v2.json")).path("scenes").get(0);
        for (String field : List.of("scenarioRef", "sceneKind", "goalRef")) {
            assertThat(recorded.path(field)).as(field).isEqualTo(shared.path(field));
        }
        assertThat(recorded.path("startedAt").asString()).as("정산이 읽는 시작 시각").isNotBlank();
    }

    @Test
    void theAssembledRecordIsTreeEqualToTheSharedRecordFixture() throws IOException {
        UUID session = UUID.randomUUID();
        UUID guardian = UUID.randomUUID();
        Instant first = Instant.parse("2026-10-20T01:00:05Z");
        Instant second = Instant.parse("2026-10-20T01:02:10Z");
        Instant third = Instant.parse("2026-10-20T01:05:00Z");
        List<SceneRunRecord> runs = List.of(
                new SceneRunRecord(UUID.fromString("00000000-0000-4000-8000-000000000b01"), session, guardian, GOAL_ONE, 0,
                        SCENARIO_ONE, SceneKind.BASE, first, second, LiveTiming.AFTER_REASK, null, 0, 0,
                        List.of(new Fact(FactKind.REASK_RESOLVED, first.plusSeconds(30)))),
                new SceneRunRecord(UUID.fromString("00000000-0000-4000-8000-000000000b02"), session, guardian, GOAL_ONE, 0,
                        SCENARIO_ONE, SceneKind.VARIANT, second, third, LiveTiming.IMMEDIATE, null, 0, 0,
                        List.of(new Fact(FactKind.VARIANT_PLAYED, second))),
                new SceneRunRecord(UUID.fromString("00000000-0000-4000-8000-000000000b03"), session, guardian, GOAL_TWO, 1,
                        SCENARIO_TWO, SceneKind.BASE, third, null, null, VariantSkipReason.NO_VARIANT, 0, 0, List.of()));

        JsonNode actual = TREE.readTree(JsonCodec.shared().write(PracticeFlowRecordAssembler.assemble(runs)));

        assertThat(actual).isEqualTo(TREE.readTree(fixture("practice-record-v2.json")));
        assertThat(PracticeFlowRecordAssembler.FLOW_VERSION).isEqualTo(actual.path("flowVersion").asString());
    }
}
