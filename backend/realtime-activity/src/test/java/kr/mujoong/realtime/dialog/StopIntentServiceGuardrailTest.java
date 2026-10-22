package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class StopIntentServiceGuardrailTest {
    private final StopIntentJudge judge = new StopIntentJudge(java.util.List.of("무중아 그만할래"));
    @Test void aRegisteredStopIsClear() {
        assertThat(judge.judge("무중아 그만할래").verdict()).isEqualTo(StopIntentJudge.Verdict.STOP);
    }
    @Test void anUnregisteredClearStopIsNotRestrictedToTheSnapshot() {
        assertThat(judge.judge("오늘은 여기까지 할래").verdict()).isEqualTo(StopIntentJudge.Verdict.STOP);
    }
    @Test void reportedSpeechDoesNotBecomeTheCurrentStop() {
        assertThat(judge.judge("엄마가 그만하라고 했어").verdict()).isEqualTo(StopIntentJudge.Verdict.NONE);
        assertThat(judge.requestsImmediateMute("엄마가 조용히 하라고 했어")).isFalse();
    }
    @Test void aNegatedStopDoesNotClose() {
        assertThat(judge.judge("그만하고 싶지 않아").verdict()).isEqualTo(StopIntentJudge.Verdict.NONE);
    }
    @Test void topicRefusalIsNotAWholeConversationStop() {
        assertThat(judge.refusesTopic("공룡 얘기 그만할래")).isTrue();
        assertThat(judge.judge("공룡 얘기 그만할래").verdict()).isEqualTo(StopIntentJudge.Verdict.NONE);
    }
    @Test void restAndThinkingDoNotRequireAStopQuestion() {
        assertThat(judge.requestsRest("잠깐 쉴게")).isTrue();
        assertThat(judge.requestsRest("생각할게")).isTrue();
    }
    @Test void quotedOrNegatedRestDoesNotPauseTheConversation() {
        assertThat(judge.requestsRest("쉬고 싶지 않아")).isFalse();
        assertThat(judge.requestsRest("친구가 생각할게라고 했어")).isFalse();
    }
    @Test void anImmediateMuteIsAnExplicitOutputRequest() {
        assertThat(judge.requestsImmediateMute("지금 말하지 마")).isTrue();
        assertThat(judge.requestsImmediateMute("소리 내지 마")).isTrue();
    }
}
