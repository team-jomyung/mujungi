package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import kr.mujoong.contract.status.ActivityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class InstructionTemplatesServiceGuardrailTest {
    @Test void firstResponseUsesConfirmedParticipationWithoutMandatoryQuestion() {
        assertThat(InstructionTemplates.ResponseStart.FIRST.content(ActivityType.FREE_TALK))
                .contains("안내 전달과 참여 수락을 확인", "반복하지 않는다", "먼저 따르고")
                .doesNotContain("관심사나 일상 질문 하나로", InstructionTemplates.INTERJECTION_LEAD);
    }

    @Test void continueDoesNotForceBackchannelOrAnotherQuestion() {
        assertThat(InstructionTemplates.ResponseStart.CONTINUE.content(ActivityType.FREE_TALK))
                .contains("새 질문을 자동으로 붙이지 않는다").doesNotContain(InstructionTemplates.INTERJECTION_LEAD);
    }

    @Test void restVariantsBothWaitWithoutPressureToResume() {
        for (var action : java.util.List.of(InstructionTemplates.NextAction.REST_ACK_ONLY,
                InstructionTemplates.NextAction.REST_ACK_AND_RETURN)) {
            assertThat(action.content(ActivityType.FREE_TALK)).contains("권유 없이", "새로 말할 때까지 기다린다")
                    .doesNotContain("이어 서", "이어가자", InstructionTemplates.INTERJECTION_LEAD);
        }
    }

    @Test void holdDoesNotInventUnderstandingFailure() {
        assertThat(InstructionTemplates.NextAction.HOLD_REASK.content(ActivityType.FREE_TALK))
                .contains("이해 실패를 꾸미거나 새 질문을 강제하지 말고").doesNotContain("잘 못 알아들었다고");
    }

    @Test void everyCorrectionRetainsCategoryAndClosesScopeWithinBudget() {
        for (var correction : DialogInstructions.Correction.values()) {
            String body = InstructionTemplates.correctionContent(correction, ActivityType.FREE_TALK);
            assertThat(body).contains("취소된 질문을 잇지 않고").endsWith(InstructionTemplates.SCOPE_CLOSE)
                    .hasSizeLessThan(500).doesNotContain(InstructionTemplates.INTERJECTION_LEAD);
        }
        assertThat(InstructionTemplates.correctionContent(DialogInstructions.Correction.DIAGNOSIS, ActivityType.FREE_TALK))
                .contains("진단하거나 이름 붙이지 않는다");
    }

    @Test void retryDoesNotReopenCancelledQuestionOrHelpBudget() {
        assertThat(InstructionTemplates.Retry.RETRY_FREE_TURN.content(ActivityType.FREE_TALK))
                .contains("이어서 말하지 않는다", "도움 요청을 꾸미거나").endsWith(InstructionTemplates.SCOPE_CLOSE);
    }

    @Test void practiceTemplatesArePreservedAndScopeCloseIsIdempotent() {
        for (var start : InstructionTemplates.ResponseStart.values()) {
            assertThat(start.content(ActivityType.PRACTICE)).isEqualTo(start.content());
            assertThat(InstructionTemplates.scoped(start.content(ActivityType.FREE_TALK)))
                    .isEqualTo(start.content(ActivityType.FREE_TALK));
        }
        for (var action : InstructionTemplates.NextAction.values()) {
            assertThat(action.content(ActivityType.PRACTICE)).isEqualTo(action.content());
        }
    }
}
