package kr.mujoong.testsupport.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.time.TimerDriver.TimerHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 수동 타이머 구동기 자기 시험(RD-2 · RD-23 · NFR6.2 · J107). NFR6.2 경계 시험이 모두 이 구동기에 기댄다. */
@Tag("u1")
class ManualTimerDriverTest {

    private MutableClock clock;
    private ManualTimerDriver driver;
    private List<String> fired;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        driver = new ManualTimerDriver(clock);
        fired = new ArrayList<>();
    }

    @Test
    void runsDueTimersInDeadlineOrderWithClockAtEachDeadline() {
        List<Instant> seenAt = new ArrayList<>();
        driver.schedule(Duration.ofSeconds(30), () -> record("30s", seenAt));
        driver.schedule(Duration.ofSeconds(10), () -> record("10s", seenAt));
        driver.schedule(Duration.ofSeconds(20), () -> record("20s", seenAt));

        int executed = driver.advance(Duration.ofSeconds(25));

        assertThat(executed).isEqualTo(2);
        assertThat(fired).containsExactly("10s", "20s");
        assertThat(seenAt).containsExactly(
                MutableClock.DEFAULT_START.plusSeconds(10), MutableClock.DEFAULT_START.plusSeconds(20));
        assertThat(clock.now()).isEqualTo(MutableClock.DEFAULT_START.plusSeconds(25));
        assertThat(driver.pendingCount()).isEqualTo(1);
        assertThat(driver.nextDeadline()).contains(MutableClock.DEFAULT_START.plusSeconds(30));
    }

    @Test
    void twoTimersWithTheSameDeadlineRunInRegistrationOrder() {
        driver.schedule(Duration.ofSeconds(5), () -> fired.add("first"));
        driver.scheduleAt(MutableClock.DEFAULT_START.plusSeconds(5), () -> fired.add("second"));

        driver.advance(Duration.ofSeconds(5));

        assertThat(fired).containsExactly("first", "second");
    }

    @Test
    void timerJustBeforeDeadlineDoesNotFireAndFiresExactlyAtDeadline() {
        driver.schedule(Duration.ofMillis(40_000), () -> fired.add("40s"));

        assertThat(driver.advance(Duration.ofMillis(39_999))).isZero();
        assertThat(fired).isEmpty();
        assertThat(driver.advance(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(fired).containsExactly("40s");
    }

    @Test
    void cancelledTimerNeverRunsAndSecondCancelReturnsFalse() {
        TimerHandle handle = driver.schedule(Duration.ofSeconds(1), () -> fired.add("cancelled"));
        driver.schedule(Duration.ofSeconds(2), () -> fired.add("kept"));

        assertThat(handle.cancel()).isTrue();
        assertThat(handle.isCancelled()).isTrue();
        assertThat(handle.cancel()).isFalse();
        driver.advance(Duration.ofSeconds(3));

        assertThat(fired).containsExactly("kept");
        assertThat(driver.pendingCount()).isZero();
    }

    @Test
    void cancelAfterFiringReturnsFalse() {
        TimerHandle handle = driver.schedule(Duration.ofSeconds(1), () -> fired.add("ran"));
        driver.advance(Duration.ofSeconds(1));

        assertThat(handle.cancel()).isFalse();
        assertThat(handle.isCancelled()).isFalse();
    }

    @Test
    void monotonicReadingIncreasesByExactlyTheAdvancedAmount() {
        long start = clock.monotonicNanos();
        driver.schedule(Duration.ofMillis(300), () -> fired.add("mid"));

        driver.advance(Duration.ofMillis(800));

        assertThat(clock.monotonicNanos() - start).isEqualTo(Duration.ofMillis(800).toNanos());
        assertThat(clock.elapsedSince(start)).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    void pastDeadlineRunsImmediatelyWithoutMovingClock() {
        driver.scheduleAt(MutableClock.DEFAULT_START.minusSeconds(5), () -> fired.add("past"));
        driver.schedule(Duration.ofSeconds(-1), () -> fired.add("negative"));
        driver.schedule(Duration.ZERO, () -> fired.add("zero"));

        int executed = driver.runDue();

        assertThat(executed).isEqualTo(3);
        assertThat(fired).containsExactly("past", "negative", "zero");
        assertThat(clock.now()).isEqualTo(MutableClock.DEFAULT_START);
        assertThat(clock.monotonicNanos()).isZero();
    }

    @Test
    void timerScheduledByATaskWithinTheWindowRunsInTheSameAdvance() {
        driver.schedule(Duration.ofSeconds(1), () -> {
            fired.add("outer");
            driver.schedule(Duration.ofSeconds(2), () -> fired.add("inner"));
        });

        driver.advance(Duration.ofSeconds(3));

        assertThat(fired).containsExactly("outer", "inner");
        assertThat(clock.now()).isEqualTo(MutableClock.DEFAULT_START.plusSeconds(3));
    }

    @Test
    void advanceToNextJumpsToEarliestDeadlineOnly() {
        driver.schedule(Duration.ofSeconds(7), () -> fired.add("7s"));
        driver.schedule(Duration.ofSeconds(9), () -> fired.add("9s"));

        assertThat(driver.advanceToNext()).isEqualTo(1);
        assertThat(fired).containsExactly("7s");
        assertThat(clock.now()).isEqualTo(MutableClock.DEFAULT_START.plusSeconds(7));
        driver.advanceToNext();
        assertThat(driver.advanceToNext()).isZero();
        assertThat(fired).containsExactly("7s", "9s");
    }

    @Test
    void negativeAdvanceAndRegistrationAfterCloseAreRejected() {
        driver.schedule(Duration.ofSeconds(1), () -> fired.add("dropped"));

        assertThatThrownBy(() -> driver.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        driver.close();
        driver.close();

        assertThatThrownBy(() -> driver.schedule(Duration.ofSeconds(1), () -> fired.add("late")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(driver.advance(Duration.ofSeconds(5))).isZero();
        assertThat(fired).isEmpty();
    }

    private void record(String name, List<Instant> seenAt) {
        fired.add(name);
        seenAt.add(clock.now());
    }
}
