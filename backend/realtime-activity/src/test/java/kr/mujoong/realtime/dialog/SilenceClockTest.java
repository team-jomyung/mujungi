package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.realtime.dialog.SilenceClock.Decision;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 공백 시계 시험 (U4b L4 · FR4.1 · BR5.1 ~ BR5.4 · U4b-J10 · U4b-J11 · NFR2).
 *
 * <p>3:59 · 4:01 · 5:59 · 6:01 경계, 멈춤(재생 · 출력 대기 · 일시정지 · 확인 질문을 하나의 멈춤 신호로 받는다) 동안 세지 않음, 리셋 규칙을 본다.
 */
@Tag("session-runtime")
class SilenceClockTest {

    private static final Instant T0 = Instant.parse("2026-10-13T09:00:00Z");
    private static final Duration WARNING = Duration.ofMinutes(4);
    private static final Duration END = Duration.ofMinutes(6);

    private final SilenceClock clock = new SilenceClock(WARNING, END);

    private static Instant at(int minutes, int seconds) {
        return T0.plusSeconds(minutes * 60L + seconds);
    }

    @Test
    void theWarningComesBetween3m59sAnd4m01sAndOnlyOnce() {
        clock.reset(T0);

        assertThat(clock.tick(at(3, 59))).isEqualTo(Decision.NONE);
        assertThat(clock.warning(at(3, 59))).isNull();
        assertThat(clock.tick(at(4, 1))).isEqualTo(Decision.WARN);
        assertThat(clock.tick(at(4, 2))).as("경고는 한 번").isEqualTo(Decision.NONE);
        SilenceClock.Warning warning = clock.warning(at(4, 1));
        assertThat(warning.silentSince()).isEqualTo(T0);
        assertThat(warning.endsAt()).isEqualTo(T0.plus(END));
    }

    @Test
    void theEndComesBetween5m59sAnd6m01s() {
        clock.reset(T0);
        clock.tick(at(4, 0));

        assertThat(clock.tick(at(5, 59))).isEqualTo(Decision.NONE);
        assertThat(clock.tick(at(6, 1))).isEqualTo(Decision.END);
    }

    @Test
    void pausedTimeIsNotCountedAndResumingContinuesFromWhatWasLeft() {
        clock.reset(T0);
        clock.setPaused(true, at(1, 0));
        assertThat(clock.nextDeadline(at(2, 0))).as("멈춘 동안 다음 판정 시각이 없다").isNull();
        clock.setPaused(false, at(3, 0));

        assertThat(clock.accumulated(at(3, 0))).isEqualTo(Duration.ofMinutes(1));
        assertThat(clock.tick(at(5, 59))).isEqualTo(Decision.NONE);
        assertThat(clock.tick(at(6, 1))).isEqualTo(Decision.WARN);
        assertThat(clock.nextDeadline(at(6, 1))).isEqualTo(at(8, 0));
    }

    @Test
    void aPauseNeverResetsButAResetClearsTheWarning() {
        clock.reset(T0);
        clock.tick(at(4, 0));
        clock.setPaused(true, at(4, 30));
        clock.setPaused(false, at(4, 40));
        assertThat(clock.isWarned()).as("안내 재생(멈춤)은 경고를 끄지 않는다").isTrue();

        clock.reset(at(5, 0));

        assertThat(clock.isWarned()).isFalse();
        assertThat(clock.accumulated(at(5, 0))).isZero();
        assertThat(clock.tick(at(8, 59))).isEqualTo(Decision.NONE);
    }

    @Test
    void aResetWhilePausedStartsCountingOnlyWhenThePauseEnds() {
        clock.setPaused(true, T0);
        clock.reset(T0);

        assertThat(clock.accumulated(at(10, 0))).isZero();
        clock.setPaused(false, at(10, 0));
        assertThat(clock.accumulated(at(11, 0))).isEqualTo(Duration.ofMinutes(1));
        assertThat(clock.isPaused()).isFalse();
    }

    @Test
    void beforeTheFirstResetNothingIsCountedOrDecided() {
        clock.setPaused(true, T0);
        clock.setPaused(false, at(1, 0));

        assertThat(clock.tick(at(30, 0))).isEqualTo(Decision.NONE);
        assertThat(clock.accumulated(at(30, 0))).isZero();
        assertThat(clock.nextDeadline(at(30, 0))).isNull();
    }

    @Test
    void theNextDeadlineIsTheWarningThenTheEnd() {
        clock.reset(T0);
        assertThat(clock.nextDeadline(T0)).isEqualTo(at(4, 0));
        clock.tick(at(4, 0));
        assertThat(clock.nextDeadline(at(4, 0))).isEqualTo(at(6, 0));
    }

    @Test
    void theEndMustBeLongerThanTheWarning() {
        assertThatThrownBy(() -> new SilenceClock(END, WARNING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SilenceClock(Duration.ZERO, END)).isInstanceOf(IllegalArgumentException.class);
    }
}
