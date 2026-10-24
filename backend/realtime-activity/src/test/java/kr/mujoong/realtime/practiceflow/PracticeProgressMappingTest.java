package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScriptWithVariant;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 진행 단계 표시 매핑 시험 (U12 BR8.1, 기능 설계 4절): 새 단계가 어떤 길로 가도 보호자 화면이 아는 6값(SCENARIO_INTRO · FIRST_RESPONSE ·
 * HELP_OFFER · CUE · AFTER_CUE_RESPONSE · NEW_SCENARIO_RESPONSE)으로만 접혀 나가고, 접기는 표시에만 있어 기록 값은 새 단계를 사실대로 둔다.
 * 운영 기본 경로(C안 '무중이 직접 묻기')의 친구 반응 · 고정 마무리 문장 단계도 같은 6값으로 접힌다. 가짜 출력 · 가짜 판정 위의 합성 대화이다
 * (테스트 대역 증거).
 */
@Tag("practice-flow")
class PracticeProgressMappingTest {

    private static final Set<String> SIX = Set.of("SCENARIO_INTRO", "FIRST_RESPONSE", "HELP_OFFER", "CUE", "AFTER_CUE_RESPONSE",
            "NEW_SCENARIO_RESPONSE");
    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID GOAL_TWO = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID SCENARIO_TWO = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    private static String twoGoalPackage() {
        return "{\"goals\":[{\"goalSelectionId\":\"" + GOAL + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"judgmentCriteria\":\"원하는 것을 말로 요청한다\",\"firstScenario\":" + directAskScriptWithVariant(SCENARIO) + "},"
                + "{\"goalSelectionId\":\"" + GOAL_TWO + "\",\"order\":2,\"displayName\":\"거절하기\","
                + "\"judgmentCriteria\":\"싫다는 뜻을 말로 전한다\"}]}";
    }

    @Test
    void everyStageOnAVariantAndNextGoalPathFoldsIntoTheSixValuesAndNeverLeaksTheNewStageNames() throws Exception {
        Harness h = new Harness(twoGoalPackage(), FIXED_SPEECH);
        when(h.http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any()))
                .thenReturn(new InternalHttpClient.Response(200, directAskScript(SCENARIO_TWO)));
        Set<String> seen = new LinkedHashSet<>();

        h.activate();
        assertThat(h.ext.progress().stage()).as("목표 안내(GOAL_BRIEF)").isEqualTo("SCENARIO_INTRO");
        seen.add(h.ext.progress().stage());
        h.played();
        seen.add(h.ext.progress().stage());
        h.played();
        assertThat(h.ext.progress().stage()).as("친구 말 전하기 + 묻기").isEqualTo("FIRST_RESPONSE");
        h.played();
        h.child("손 괜찮아?");
        h.result("J2", "FULL");
        assertThat(h.ext.progress().stage()).as("목표 달성 후 친구 반응(FRIEND_REACTION)").isEqualTo("AFTER_CUE_RESPONSE");
        seen.add(h.ext.progress().stage());
        h.played();
        assertThat(h.ext.progress().stage()).as("친구 반응 후 변형 제안(VARIANT_OFFER)").isEqualTo("AFTER_CUE_RESPONSE");
        h.played();
        h.child("응");
        h.result("J4", "ACCEPT");
        assertThat(h.ext.progress().stage()).as("변형 장면의 모든 단계").isEqualTo("NEW_SCENARIO_RESPONSE");
        seen.add(h.ext.progress().stage());
        h.played();
        seen.add(h.ext.progress().stage());
        h.played();
        seen.add(h.ext.progress().stage());
        h.child("졸리구나, 내일 또 하자");
        h.result("J2", "FULL");
        seen.add(h.ext.progress().stage());
        h.played(); // 변형 장면의 친구 반응 → 목표 회고
        assertThat(h.ext.progress().stage()).as("변형 장면 뒤 목표 회고도 변형 표시").isEqualTo("NEW_SCENARIO_RESPONSE");

        h.played(); // 목표 회고 → 다음 목표 대기
        assertThat(h.ext.progress().stage()).as("다음 목표를 기다리는 NEXT_GOAL").isEqualTo("SCENARIO_INTRO");
        assertThat(h.ext.progress().goalIndex()).isEqualTo(1);
        seen.add(h.ext.progress().stage());
        h.deliverPosted();
        assertThat(h.ext.progress().goalIndex()).isEqualTo(2);
        assertThat(h.ext.progress().stage()).as("다음 목표 안내는 변형 표시를 이어받지 않는다").isEqualTo("SCENARIO_INTRO");
        h.played();
        h.played();
        h.played();
        h.child("싫어");
        h.result("J2", "PARTIAL");
        assertThat(h.ext.progress().stage()).as("무중의 되묻기(REASK)").isEqualTo("FIRST_RESPONSE");
        seen.add(h.ext.progress().stage());
        h.played();
        h.child("싫어");
        h.result("J3", "STILL_SHORT");
        assertThat(h.ext.progress().stage()).isEqualTo("HELP_OFFER");
        seen.add(h.ext.progress().stage());
        h.played();
        h.child("응");
        h.result("J4", "ACCEPT");
        assertThat(h.ext.progress().stage()).as("역할 전환 없이 무중의 도움(FOCUS)").isEqualTo("CUE");
        seen.add(h.ext.progress().stage());
        h.played();
        h.child("음");
        h.result("J5", "PARTIAL");
        assertThat(h.ext.progress().stage()).as("고정 마무리 문장(SCENE_CLOSE)").isEqualTo("AFTER_CUE_RESPONSE");
        seen.add(h.ext.progress().stage());

        assertThat(SIX).containsAll(seen);
        assertThat(seen).as("여섯 값이 모두 한 번 이상 나왔다").containsExactlyInAnyOrderElementsOf(SIX);
        assertThat(h.recorder.list()).as("접기는 표시에만 있다: 기록은 변형 장면을 사실대로 둔다")
                .extracting(PracticeFlowTypes.SceneRunRecord::sceneKind)
                .contains(PracticeFlowTypes.SceneKind.BASE, PracticeFlowTypes.SceneKind.VARIANT);
    }

    @Test
    void theClosingStageKeepsTheLastValueAndTheIntroFailureStillShowsAKnownValue() {
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO)), FIXED_SPEECH);

        assertThat(h.ext.progress()).as("시작 전에는 진행 값이 없다").isNull();
        h.toIntroduction();
        h.end(Playback.FAILED);

        assertThat(h.ext.progress().stage()).isIn(SIX);
    }
}
