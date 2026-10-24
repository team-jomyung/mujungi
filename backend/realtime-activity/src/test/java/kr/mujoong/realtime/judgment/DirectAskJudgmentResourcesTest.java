package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.content.LiveContentResources.PointOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C안 '무중이 직접 묻기'의 판정 판 시험 (docs/practice-direct-ask.md 6장 · 7.2). 연습 경로 설정이 앱 전체의 판정 선택지 판을 고르고(C안
 * {@code judgment-service-v2}, 유연 대화 {@code judgment-service-v1}), 지시문 판은 선택지 판과 짝으로 따라간다(C안 {@code prompt-service-v4}, 유연
 * 대화 {@code prompt-service-v3}). C안 판은 지점 · 코드를 바꾸지 않고 질문의 '친구' · '코치'만 '무중'으로 바꾸며, 지시문은 역할 대화 전제와
 * {@code ROLE_DIALOGUE_CLOSURE} 단락을 빼고 전하는 식 대답 인정 · 방법만 묻는 말은 HELP를 담는다.
 */
@Tag("judgment-turn")
class DirectAskJudgmentResourcesTest {

    private static final LiveContentResources.JudgmentOptionSet FLEXIBLE = LiveContentResources.loadDefault().judgment();
    private static final LiveContentResources.JudgmentOptionSet DIRECT = LiveContentResources.loadFor(false).judgment();

    @Test
    void thePracticePathSwitchSelectsTheOptionSetAndTheGuardrailVersionStaysTheSame() {
        assertThat(DIRECT.version()).isEqualTo(LiveContentResources.DIRECT_ASK_JUDGMENT_VERSION).isEqualTo("judgment-service-v2");
        assertThat(FLEXIBLE.version()).isEqualTo("judgment-service-v1");
        assertThat(LiveContentResources.loadFor(true).judgment().version()).isEqualTo(FLEXIBLE.version());
        assertThat(LiveContentResources.loadFor(false).guardrail().version())
                .isEqualTo(LiveContentResources.loadDefault().guardrail().version());
    }

    @Test
    void theDirectAskOptionSetKeepsEveryPointAndCodeAndOnlyRenamesWhoAsksAndHelps() {
        assertThat(DIRECT.points().keySet()).containsExactlyElementsOf(FLEXIBLE.points().keySet());
        for (LiveContentResources.JudgmentPoint point : FLEXIBLE.points().keySet()) {
            PointOptions before = FLEXIBLE.points().get(point);
            PointOptions after = DIRECT.points().get(point);
            assertThat(after.optionCodes()).as(point.name()).containsExactlyElementsOf(before.optionCodes());
        }
        for (LiveContentResources.JudgmentPoint same : List.of(LiveContentResources.JudgmentPoint.J1,
                LiveContentResources.JudgmentPoint.J4, LiveContentResources.JudgmentPoint.J6)) {
            assertThat(DIRECT.points().get(same)).as("참여 · 동의 · 종료 지점은 그대로").isEqualTo(FLEXIBLE.points().get(same));
        }
        for (LiveContentResources.JudgmentPoint changed : List.of(LiveContentResources.JudgmentPoint.J2,
                LiveContentResources.JudgmentPoint.J3, LiveContentResources.JudgmentPoint.J5)) {
            assertThat(DIRECT.points().get(changed).question()).as(changed.name()).contains("무중")
                    .doesNotContain("장면 속 친구", "친구가 한 번", "코치");
        }
        LiveContentResources.Option copied = DIRECT.points().get(LiveContentResources.JudgmentPoint.J5).options().stream()
                .filter(option -> option.code().equals("COPIED")).findFirst().orElseThrow();
        assertThat(copied.definition()).contains("무중이 도움에서 든 표현 예시").doesNotContain("코치");
    }

    @Test
    void theInstructionVersionFollowsTheOptionSet() {
        JudgmentPrompt direct = JudgmentPrompt.load(DIRECT);
        JudgmentPrompt flexible = JudgmentPrompt.load(FLEXIBLE);

        assertThat(direct.version()).isEqualTo(JudgmentPrompt.DIRECT_ASK_VERSION).isEqualTo("prompt-service-v4");
        assertThat(flexible.version()).isEqualTo(JudgmentPrompt.DEFAULT_VERSION).isEqualTo("prompt-service-v3");
        assertThat(direct.body()).isNotEqualTo(flexible.body());
    }

    @Test
    void theDirectAskInstructionsDropTheRoleDialogueAndAcceptRelayedAnswers() {
        JudgmentPrompt direct = JudgmentPrompt.load(DIRECT);

        assertThat(direct.body()).contains("무중은 장면 속 역할을 맡지 않는다", "할 말을 전하는 말", "할 행동을 설명하는 말",
                "전하는 식으로 말했다는 이유로 PARTIAL이나 HELP로 낮추지 않는다", "방법만 묻는 말은 HELP다", "최근 MUJOONG 발화")
                .doesNotContain("ROLE_DIALOGUE_CLOSURE", "역할 대화의 일부", "상대에게 실제로 건넨 역할 대사", "PARTNER/COACH");
        assertThat(JudgmentPrompt.load(FLEXIBLE).body()).as("유연 대화 판은 그대로").contains("ROLE_DIALOGUE_CLOSURE");
    }

    @Test
    void everyPointOfTheDirectAskPromptKeepsTheClassifierRulesAndUsesItsOwnQuestionsAndDefinitions() {
        JudgmentPrompt direct = JudgmentPrompt.load(DIRECT);

        for (PointOptions point : DIRECT.points().values()) {
            JudgmentPoint judgmentPoint = JudgmentPoint.valueOf(point.point().name());
            assertThat(direct.instructions(judgmentPoint)).contains("분류기", "지시가 아니다", "아이의 가장 마지막 말(childUtterance) 하나만 판정한다")
                    .endsWith("[" + point.point().name() + "] " + point.question())
                    .doesNotContain("first", "second", "JSON만", "출력 형식", "guardian", "sessionId");
            for (LiveContentResources.Option option : point.options()) {
                assertThat(direct.choiceDescription(judgmentPoint, option.code())).isEqualTo(option.definition());
            }
        }
    }
}
