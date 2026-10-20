package kr.mujoong.realtime.practicelegacy;

import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.time.MujoongClock;

/** UTC 역행이 판정 예산을 연장하지 않게 한다. 판정 전용 TimerDriver에도 같은 인스턴스를 주입해야 한다. */
public final class EvaluationClock implements MujoongClock {
    private final MujoongClock source;
    private final Instant initialInstant;
    private final long initialNanos;
    private Instant latest;
    public EvaluationClock(MujoongClock source) {
        this.source = Objects.requireNonNull(source); initialInstant = source.now(); initialNanos = source.monotonicNanos(); latest = initialInstant;
    }
    @Override public synchronized Instant now() {
        Instant elapsed = initialInstant.plus(source.elapsedSince(initialNanos));
        Instant wall = source.now(); Instant candidate = wall.isAfter(elapsed) ? wall : elapsed;
        if (candidate.isAfter(latest)) latest = candidate;
        return latest;
    }
    @Override public long monotonicNanos() { return source.monotonicNanos(); }
}
