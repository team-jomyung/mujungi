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
 * 종료 흐름 시험 (U4b L4 · FR3.1 · U4b-J6 · FD-Q3).
 *
 * <p>2026-10-22 결정으로 확인 질문(ASK)으로 들어가는 입구가 없어졌다: 판정 STOP이면 곧바로 종료, 그 밖은 아무것도 하지 않는다. 확인 중 단계가
 * 열리지 않으므로 대답 · 시간 경과 · 재생 끝 · 일시정지가 흐름을 바꾸지 않는 것을 본다(서비스 가드레일 흐름도 같다).
 */
@Tag("session-runtime")
@org.junit.jupiter.api.Tag("service-guardrail")
class StopConfirmFlowTest {

    private static final Instant T0 = Instant.parse("2026-10-13T09:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(20);

    private final StopConfirmFlow flow = new StopConfirmFlow(WAIT);

    @Test
    void aClearStopClosesAtOnceWithoutAQuestion() {
        assertThat(flow.onJudgment(Verdict.STOP)).isEqualTo(Decision.CLOSE);
        assertThat(flow.isActive()).isFalse();
        assertThat(flow.phase()).isEqualTo(Phase.IDLE);
    }

    @Test
    void anythingButAStopNeverAsksAQuestion() {
        assertThat(flow.onJudgment(Verdict.NONE)).isEqualTo(Decision.NONE);
        assertThat(flow.isActive()).isFalse();
        assertThat(flow.phase()).isEqualTo(Phase.IDLE);
        assertThat(flow.windowEnd()).isNull();
    }

    @Test
    void withoutAnOpenQuestionRepliesTicksAndPromptEndsChangeNothing() {
        flow.onJudgment(Verdict.NONE);

        flow.promptPlayed(T0);
        assertThat(flow.phase()).as("질문을 재생하지 않았으면 재생 끝이 창을 열지 않는다").isEqualTo(Phase.IDLE);
        assertThat(flow.windowEnd()).isNull();
        assertThat(flow.onReply("응", false, T0.plusSeconds(1))).isEqualTo(Decision.NONE);
        assertThat(flow.onReply("그만할래", true, T0.plusSeconds(2))).as("종료는 onJudgment 경로가 맡는다")
                .isEqualTo(Decision.NONE);
        assertThat(flow.tick(T0.plusSeconds(60))).isEqualTo(Decision.NONE);
    }

    @Test
    void aPauseShiftAndCancelAreHarmlessWhenIdle() {
        flow.shift(Duration.ofSeconds(10));
        flow.cancel();

        assertThat(flow.isActive()).isFalse();
        assertThat(flow.windowEnd()).isNull();
        assertThat(flow.onJudgment(Verdict.STOP)).as("취소 뒤에도 종료는 곧바로다").isEqualTo(Decision.CLOSE);
    }

    @Test
    void theServiceGuardrailFlowBehavesTheSame() {
        StopConfirmFlow guarded = new StopConfirmFlow(WAIT, true);

        assertThat(guarded.onJudgment(Verdict.STOP)).isEqualTo(Decision.CLOSE);
        assertThat(guarded.onJudgment(Verdict.NONE)).isEqualTo(Decision.NONE);
        guarded.promptPlayed(T0);
        assertThat(guarded.onReply("응", false, T0)).isEqualTo(Decision.NONE);
        assertThat(guarded.onReply("그만할래", true, T0)).isEqualTo(Decision.NONE);
        assertThat(guarded.isActive()).isFalse();
    }

    @Test
    void theWindowMustBePositiveAndAVerdictIsRequired() {
        assertThatThrownBy(() -> new StopConfirmFlow(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StopConfirmFlow(Duration.ofSeconds(-1), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flow.onJudgment(null)).isInstanceOf(NullPointerException.class);
    }
}
