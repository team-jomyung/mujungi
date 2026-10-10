package kr.mujoong.contract.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 운영 Clock(RD-2 · R1 ~ R4): UTC 시각과 단조 읽기. */
@Tag("u1")
class SystemMujoongClockTest {

    private final SystemMujoongClock clock = new SystemMujoongClock();

    @Test
    void nowIsUtcAndCloseToTheSystemInstant() {
        Instant before = Instant.now();
        Instant now = clock.now();
        Instant after = Instant.now();

        assertThat(now).isBetween(before, after);
        assertThat(now.atOffset(ZoneOffset.UTC).getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(now.toString()).endsWith("Z");
    }

    @Test
    void monotonicReadingNeverDecreases() {
        long previous = clock.monotonicNanos();
        for (int i = 0; i < 10_000; i++) {
            long current = clock.monotonicNanos();
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }

    @Test
    void elapsedSinceIsNonNegativeEvenForAFutureStart() {
        long start = clock.monotonicNanos();

        assertThat(clock.elapsedSince(start)).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(clock.elapsedSince(start + Duration.ofHours(1).toNanos())).isEqualTo(Duration.ZERO);
    }
}
