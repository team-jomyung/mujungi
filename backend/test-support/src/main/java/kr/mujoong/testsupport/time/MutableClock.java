package kr.mujoong.testsupport.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.time.MujoongClock;

/**
 * 시험용 가변 Clock(RD-2 · RD-23 · NFR6.2). 실제 시간을 기다리지 않고 시각을 앞당긴다.
 *
 * <p>{@link #advance(Duration)}는 UTC 시각과 단조 읽기를 같은 양만큼 앞당긴다. {@link #setInstant(Instant)}는 호스트 시계가 뛴 상황처럼
 * UTC 시각만 바꾸고 단조 읽기는 그대로 둔다(R4). 스레드 안전하다.
 */
public final class MutableClock implements MujoongClock {

    /** 기본 시작 시각(밀리초 경계, 실행 시각에 기대지 않게 고정). */
    public static final Instant DEFAULT_START = Instant.parse("2026-10-15T00:00:00Z");

    private Instant now;
    private long monotonicNanos;

    /** {@link #DEFAULT_START}에서 시작한다. */
    public MutableClock() {
        this(DEFAULT_START);
    }

    public MutableClock(Instant start) {
        this.now = Objects.requireNonNull(start, "start");
        this.monotonicNanos = 0L;
    }

    @Override
    public synchronized Instant now() {
        return now;
    }

    @Override
    public synchronized long monotonicNanos() {
        return monotonicNanos;
    }

    /** UTC 시각과 단조 읽기를 {@code duration}만큼 앞당긴다. 음수는 거절한다. */
    public synchronized void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Clock은 뒤로 앞당길 수 없다(setInstant를 쓴다)");
        }
        now = now.plus(duration);
        monotonicNanos += duration.toNanos();
    }

    /** {@code millis} 밀리초 앞당긴다. */
    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }

    /** UTC 시각만 바꾼다(호스트 시계 점프 모사). 단조 읽기는 그대로다. */
    public synchronized void setInstant(Instant instant) {
        this.now = Objects.requireNonNull(instant, "instant");
    }
}
