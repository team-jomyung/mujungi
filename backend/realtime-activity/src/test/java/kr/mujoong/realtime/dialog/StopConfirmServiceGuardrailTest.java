package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class StopConfirmServiceGuardrailTest {
    private final Instant now = Instant.parse("2026-10-21T00:00:00Z");
    private final StopConfirmFlow flow = new StopConfirmFlow(Duration.ofSeconds(20), true);
    private void ask() { flow.onJudgment(StopIntentJudge.Verdict.CONFIRM); }
    @Test void anEarlyYesCannotConfirmAnUnheardQuestion() {
        ask();
        assertThat(flow.onReply("응", false, now)).isEqualTo(StopConfirmFlow.Decision.NONE);
        assertThat(flow.phase()).isEqualTo(StopConfirmFlow.Phase.ASKING);
    }
    @Test void aClearStopDoesNotWaitForTheQuestion() {
        ask();
        assertThat(flow.onReply("그만할래", true, now)).isEqualTo(StopConfirmFlow.Decision.CLOSE);
    }
    @Test void yesAfterActualCompletionConfirms() {
        ask(); flow.promptPlayed(now);
        assertThat(flow.onReply("응", false, now.plusSeconds(1))).isEqualTo(StopConfirmFlow.Decision.CLOSE);
    }
    @Test void noAfterActualCompletionResumes() {
        ask(); flow.promptPlayed(now);
        assertThat(flow.onReply("싫어", false, now.plusSeconds(1))).isEqualTo(StopConfirmFlow.Decision.RESUME);
    }
    @Test void anUnclearReplyKeepsTheSameWindow() {
        ask(); flow.promptPlayed(now);
        assertThat(flow.onReply("잘 모르겠어", false, now.plusSeconds(1))).isEqualTo(StopConfirmFlow.Decision.NONE);
        assertThat(flow.windowEnd()).isEqualTo(now.plusSeconds(20));
    }
    @Test void silenceExpiresWithoutConfirmingStop() {
        ask(); flow.promptPlayed(now);
        assertThat(flow.tick(now.plusSeconds(20))).isEqualTo(StopConfirmFlow.Decision.RESUME);
    }
    @Test void aYesAtTheDeadlineIsTooLate() {
        ask(); flow.promptPlayed(now);
        assertThat(flow.onReply("응", false, now.plusSeconds(20))).isEqualTo(StopConfirmFlow.Decision.RESUME);
    }
    @Test void failureCancellationCannotBeReopenedByAnOldCompletion() {
        ask(); flow.cancel(); flow.promptPlayed(now);
        assertThat(flow.isActive()).isFalse();
        assertThat(flow.onReply("응", false, now)).isEqualTo(StopConfirmFlow.Decision.NONE);
    }
}
