package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.realtime.dialog.JoinTracker.Action;
import kr.mujoong.realtime.dialog.JoinTracker.Phase;
import kr.mujoong.realtime.dialog.JoinTracker.Reply;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 참여 판단 넓힘 시험 (L3 · FR2.2 · BR2.2 · BR2.4 · U4b-J4 · U4b-J5 · R-11).
 *
 * <p>되살린 JoinTracker에 더한 것: 두 창 시간(처음 · 안내 뒤), 창 끝 조회, 기술 일시정지 동안 창 멈춤(시작 시각 밀기), 안내 재생 중 대답의 즉시
 * 처리. 가변 시각만 넣어 결정을 본다.
 */
@Tag("u4b")
class JoinTrackerU4bTest {

    private static final Instant T0 = Instant.parse("2026-10-13T09:00:00Z");
    private static final Duration FIRST = Duration.ofSeconds(20);
    private static final Duration AFTER = Duration.ofSeconds(30);

    @Test
    void theFirstWindowUsesJoinWaitAndLaterWindowsUseTheAfterRepromptWait() {
        JoinTracker tracker = new JoinTracker(FIRST, AFTER);
        tracker.promptPlayed(T0);
        assertThat(tracker.windowEnd()).isEqualTo(T0.plus(FIRST));

        assertThat(tracker.tick(T0.plus(FIRST))).isEqualTo(Action.REPROMPT);
        Instant second = T0.plus(FIRST).plusSeconds(3);
        tracker.promptPlayed(second);

        assertThat(tracker.windowEnd()).isEqualTo(second.plus(AFTER));
        assertThat(tracker.tick(second.plusSeconds(29))).isEqualTo(Action.NONE);
        assertThat(tracker.tick(second.plusSeconds(30))).isEqualTo(Action.DECLINE);
    }

    @Test
    void the19And21SecondBoundariesHoldOnEachWindow() {
        JoinTracker early = new JoinTracker(FIRST, FIRST);
        early.promptPlayed(T0);
        assertThat(early.reply(Reply.ACCEPT, T0.plusSeconds(19))).isEqualTo(Action.START);
        assertThat(early.phase()).isEqualTo(Phase.JOINED);

        JoinTracker late = new JoinTracker(FIRST, FIRST);
        late.promptPlayed(T0);
        assertThat(late.reply(Reply.ACCEPT, T0.plusSeconds(21))).as("창 끝 뒤의 대답은 만료가 먼저다")
                .isEqualTo(Action.REPROMPT);
        assertThat(late.prompts()).isEqualTo(2);
    }

    @Test
    void aPauseShiftsTheOpenWindowSoItResumesWithTheTimeThatWasLeft() {
        JoinTracker tracker = new JoinTracker(FIRST, AFTER);
        tracker.promptPlayed(T0);

        tracker.shift(Duration.ofSeconds(15));

        assertThat(tracker.windowEnd()).isEqualTo(T0.plus(FIRST).plusSeconds(15));
        assertThat(tracker.tick(T0.plusSeconds(21))).as("멈춘 15초는 세지 않는다").isEqualTo(Action.NONE);
        assertThat(tracker.tick(T0.plusSeconds(35))).isEqualTo(Action.REPROMPT);
    }

    @Test
    void shiftingWithoutAnOpenWindowOrByNothingChangesNothing() {
        JoinTracker tracker = new JoinTracker(FIRST, AFTER);
        tracker.shift(Duration.ofSeconds(5));
        assertThat(tracker.windowEnd()).as("안내 재생 중(창 없음)").isNull();

        tracker.promptPlayed(T0);
        tracker.shift(Duration.ZERO);
        tracker.shift(Duration.ofSeconds(-3));
        assertThat(tracker.windowEnd()).isEqualTo(T0.plus(FIRST));
    }

    @Test
    void aReplyWhileThePromptIsStillPlayingIsHandledAtOnce() {
        JoinTracker tracker = new JoinTracker(FIRST, AFTER);

        assertThat(tracker.phase()).isEqualTo(Phase.PROMPTING);
        assertThat(tracker.reply(Reply.REFUSE, T0)).isEqualTo(Action.OFFER_ALTERNATIVE);
        assertThat(tracker.reply(Reply.ACCEPT, T0.plusSeconds(1))).as("대안 재생 중의 참여도 곧바로 시작")
                .isEqualTo(Action.START);
    }

    @Test
    void bothWindowsMustBePositive() {
        assertThatThrownBy(() -> new JoinTracker(Duration.ZERO, AFTER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JoinTracker(FIRST, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JoinTracker(null, AFTER)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theWindowEndIsGoneOnceTheTrackerFinished() {
        JoinTracker tracker = new JoinTracker(FIRST, AFTER);
        tracker.promptPlayed(T0);
        tracker.reply(Reply.ACCEPT, T0.plusSeconds(2));

        assertThat(tracker.windowEnd()).isNull();
        assertThat(tracker.reply(Reply.REFUSE, T0.plusSeconds(3))).isEqualTo(Action.NONE);
        assertThat(tracker.tick(T0.plusSeconds(60))).isEqualTo(Action.NONE);
    }
}
