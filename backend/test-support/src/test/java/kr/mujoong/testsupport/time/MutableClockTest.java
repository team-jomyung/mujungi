package kr.mujoong.testsupport.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 가변 Clock 자기 시험(RD-2 · RD-23 · J107). */
@Tag("skeleton")
class MutableClockTest {

    @Test
    void startsAtFixedInstantWithZeroMonotonicReading() {
        MutableClock clock = new MutableClock();

        assertThat(clock.now()).isEqualTo(MutableClock.DEFAULT_START);
        assertThat(clock.monotonicNanos()).isZero();
    }

    @Test
    void advanceMovesWallClockAndMonotonicReadingByTheSameAmount() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-15T09:00:00Z"));

        clock.advance(Duration.ofSeconds(40));
        clock.advanceMillis(250);

        assertThat(clock.now()).isEqualTo(Instant.parse("2026-10-15T09:00:40.250Z"));
        assertThat(clock.monotonicNanos()).isEqualTo(Duration.ofMillis(40_250).toNanos());
        assertThat(clock.elapsedSince(0L)).isEqualTo(Duration.ofMillis(40_250));
    }

    @Test
    void advanceByZeroChangesNothing() {
        MutableClock clock = new MutableClock();

        clock.advance(Duration.ZERO);

        assertThat(clock.now()).isEqualTo(MutableClock.DEFAULT_START);
        assertThat(clock.monotonicNanos()).isZero();
    }

    @Test
    void negativeAdvanceIsRejectedAndLeavesClockUnchanged() {
        MutableClock clock = new MutableClock();
        clock.advance(Duration.ofSeconds(5));

        assertThatThrownBy(() -> clock.advance(Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.now()).isEqualTo(MutableClock.DEFAULT_START.plusSeconds(5));
        assertThat(clock.monotonicNanos()).isEqualTo(Duration.ofSeconds(5).toNanos());
    }

    @Test
    void setInstantJumpsWallClockOnlyLikeHostClockJump() {
        MutableClock clock = new MutableClock();
        clock.advance(Duration.ofSeconds(3));
        long monotonicBefore = clock.monotonicNanos();

        clock.setInstant(MutableClock.DEFAULT_START.minus(Duration.ofHours(1)));

        assertThat(clock.now()).isEqualTo(MutableClock.DEFAULT_START.minus(Duration.ofHours(1)));
        assertThat(clock.monotonicNanos()).isEqualTo(monotonicBefore);
        clock.advance(Duration.ofSeconds(1));
        assertThat(clock.monotonicNanos()).isEqualTo(monotonicBefore + Duration.ofSeconds(1).toNanos());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> new MutableClock(null)).isInstanceOf(NullPointerException.class);
        MutableClock clock = new MutableClock();
        assertThatThrownBy(() -> clock.advance(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> clock.setInstant(null)).isInstanceOf(NullPointerException.class);
    }
}
