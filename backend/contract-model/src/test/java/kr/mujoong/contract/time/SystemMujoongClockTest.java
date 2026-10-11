package kr.mujoong.contract.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 운영 Clock(RD-2 · R1 ~ R4): UTC 시각과 단조 읽기.
 *
 * <p>3.6 수정(BT-TC-10)에서 더한 셋: 실제 대기를 상한 있는 래치 대기로 재고(J138 — 운영 구동기 시험만 실제 시간을 쓴다), 운영 Clock이
 * 물려받는 경과 규칙(인터페이스 기본 메서드)의 경계 — 0 아래 자르기의 한 틱 양쪽과 단조 값 넘침 — 를 값을 고정한 읽기로 본다(3.6 수정 J317).
 */
@Tag("u1")
class SystemMujoongClockTest {

    /** 실제 대기 길이(수십 ms, J138). */
    private static final Duration REAL_WAIT = Duration.ofMillis(30);
    /** 부하가 큰 CI에서도 넘지 않을 상한(시험이 멈추지 않게). */
    private static final Duration UPPER_BOUND = Duration.ofSeconds(10);

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

    @Test
    void elapsedSinceMeasuresABoundedRealWaitAndWallTimeDoesNotGoBack() throws InterruptedException {
        CountDownLatch never = new CountDownLatch(1);
        Instant wallBefore = clock.now();
        long start = clock.monotonicNanos();

        assertThat(never.await(REAL_WAIT.toMillis(), TimeUnit.MILLISECONDS)).as("아무도 풀지 않는 래치").isFalse();

        Duration elapsed = clock.elapsedSince(start);
        assertThat(elapsed).isGreaterThanOrEqualTo(REAL_WAIT).isLessThan(UPPER_BOUND);
        assertThat(clock.now()).isAfterOrEqualTo(wallBefore);
    }

    @Test
    void theInheritedElapsedRuleClampsOnlyStartsAfterTheReading() throws NoSuchMethodException {
        // 운영 Clock은 경과 규칙을 고쳐 쓰지 않고 인터페이스 기본 메서드를 물려받는다 — 아래 고정 읽기 시험이 운영 규칙과 같다
        assertThatThrownBy(() -> SystemMujoongClock.class.getDeclaredMethod("elapsedSince", long.class))
                .isInstanceOf(NoSuchMethodException.class);
        assertThat(MujoongClock.class.getMethod("elapsedSince", long.class).isDefault()).isTrue();

        long reading = 5_000_000_000L;
        MujoongClock fixed = fixedReading(reading);

        assertThat(fixed.elapsedSince(reading - 1)).isEqualTo(Duration.ofNanos(1));
        assertThat(fixed.elapsedSince(reading)).isEqualTo(Duration.ZERO);
        assertThat(fixed.elapsedSince(reading + 1)).isEqualTo(Duration.ZERO);
        assertThat(fixed.elapsedSince(reading - Duration.ofSeconds(40).toNanos())).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void elapsedIsADifferenceOfReadingsSoItSurvivesTheCounterWrappingAround() {
        // System.nanoTime은 어떤 long이든 될 수 있고 넘쳐 음수로 돌 수 있다. 차이로 재면 넘침을 건너도 맞다
        MujoongClock wrapped = fixedReading(Long.MIN_VALUE + 5);

        assertThat(wrapped.elapsedSince(Long.MAX_VALUE - 4)).isEqualTo(Duration.ofNanos(10));
        assertThat(fixedReading(-3L).elapsedSince(-Duration.ofMillis(800).toNanos() - 3L))
                .isEqualTo(Duration.ofMillis(800));
        // 넘침 반대쪽(읽기가 시작보다 '작아 보이는' 미래 시작)은 여전히 0으로 자른다
        assertThat(fixedReading(Long.MAX_VALUE - 4).elapsedSince(Long.MIN_VALUE + 5)).isEqualTo(Duration.ZERO);
    }

    /** 단조 값을 고정한 읽기(경과 규칙은 운영 Clock과 같은 인터페이스 기본 메서드다). */
    private static MujoongClock fixedReading(long monotonic) {
        return new MujoongClock() {
            @Override
            public Instant now() {
                return Instant.EPOCH;
            }

            @Override
            public long monotonicNanos() {
                return monotonic;
            }
        };
    }
}
