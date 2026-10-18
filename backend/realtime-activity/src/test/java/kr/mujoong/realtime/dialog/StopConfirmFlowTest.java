package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.realtime.dialog.StopConfirmFlow.Decision;
import kr.mujoong.realtime.dialog.StopConfirmFlow.Phase;
import kr.mujoong.realtime.dialog.StopIntentJudge.Verdict;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 종료 확인 질문 흐름 시험 (U4b L4 · FR3.1 · BR4.2 ~ BR4.4 · U4b-J6 · FD-Q3).
 *
 * <p>네 경로(STOP → 곧바로 종료, CONFIRM → 확정, CONFIRM → 무응답, CONFIRM → 다른 말)와 19 · 21초 경계, 일시정지 동안 창 멈춤을 본다.
 */
@Tag("session-runtime")
class StopConfirmFlowTest {

    private static final Instant T0 = Instant.parse("2026-10-13T09:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(20);

    private final StopConfirmFlow flow = new StopConfirmFlow(WAIT);

    @Test
    void aClearStopClosesAtOnceWithoutAQuestion() {
        assertThat(flow.onJudgment(Verdict.STOP)).isEqualTo(Decision.CLOSE);
        assertThat(flow.isActive()).isFalse();
        assertThat(flow.onJudgment(Verdict.NONE)).isEqualTo(Decision.NONE);
    }

    @Test
    void anUnclearIntentAsksThenAStopOrAJoinWordConfirmsTheEnd() {
        assertThat(flow.onJudgment(Verdict.CONFIRM)).isEqualTo(Decision.ASK);
        assertThat(flow.phase()).isEqualTo(Phase.ASKING);
        flow.promptPlayed(T0);
        assertThat(flow.onReply("응", false, T0.plusSeconds(19))).as("참여 분류 ACCEPT").isEqualTo(Decision.CLOSE);
        assertThat(flow.isActive()).isFalse();

        flow.onJudgment(Verdict.CONFIRM);
        flow.promptPlayed(T0);
        assertThat(flow.onReply("이제 끝", true, T0.plusSeconds(3))).as("STOP 판정").isEqualTo(Decision.CLOSE);
    }

    @Test
    void anyOtherReplyResumesTheConversation() {
        flow.onJudgment(Verdict.CONFIRM);
        flow.promptPlayed(T0);

        assertThat(flow.onReply("공룡 얘기 더 해 줘", false, T0.plusSeconds(5))).isEqualTo(Decision.RESUME);
        assertThat(flow.phase()).isEqualTo(Phase.IDLE);
    }

    @Test
    void noReplyUntil21SecondsResumesAndNineteenStillWaits() {
        flow.onJudgment(Verdict.CONFIRM);
        flow.promptPlayed(T0);

        assertThat(flow.tick(T0.plusSeconds(19))).isEqualTo(Decision.NONE);
        assertThat(flow.windowEnd()).isEqualTo(T0.plus(WAIT));
        assertThat(flow.tick(T0.plusSeconds(21))).isEqualTo(Decision.RESUME);
        assertThat(flow.windowEnd()).isNull();
    }

    @Test
    void aReplyAfterTheWindowEdgeIsTheExpiryFirst() {
        flow.onJudgment(Verdict.CONFIRM);
        flow.promptPlayed(T0);

        assertThat(flow.onReply("응", false, T0.plus(WAIT))).as("창 끝 시각의 대답은 만료").isEqualTo(Decision.RESUME);
    }

    @Test
    void aReplyWhileTheQuestionIsPlayingIsTheFirstReply() {
        flow.onJudgment(Verdict.CONFIRM);

        assertThat(flow.tick(T0.plusSeconds(60))).as("창이 열리기 전에는 만료가 없다").isEqualTo(Decision.NONE);
        assertThat(flow.onReply("그만할래", true, T0)).isEqualTo(Decision.CLOSE);
    }

    @Test
    void aPauseShiftsTheWindowAndCancelForgetsTheFlow() {
        flow.onJudgment(Verdict.CONFIRM);
        flow.promptPlayed(T0);
        flow.shift(Duration.ofSeconds(10));
        assertThat(flow.tick(T0.plusSeconds(21))).isEqualTo(Decision.NONE);
        assertThat(flow.tick(T0.plusSeconds(30))).isEqualTo(Decision.RESUME);

        flow.onJudgment(Verdict.CONFIRM);
        flow.cancel();
        assertThat(flow.isActive()).isFalse();
        assertThat(flow.onReply("응", false, T0)).isEqualTo(Decision.NONE);
    }

    @Test
    void judgmentsWhileAskingAreIgnoredAndTheWindowMustBePositive() {
        flow.onJudgment(Verdict.CONFIRM);
        assertThat(flow.onJudgment(Verdict.STOP)).isEqualTo(Decision.NONE);
        assertThatThrownBy(() -> new StopConfirmFlow(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
