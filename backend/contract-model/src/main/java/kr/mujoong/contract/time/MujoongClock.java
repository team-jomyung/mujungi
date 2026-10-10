package kr.mujoong.contract.time;

import java.time.Duration;
import java.time.Instant;

/**
 * 모든 시각의 유일한 원천(RD-2 · NFR6.4 · BR8.10). 생성자 주입으로만 받는다.
 *
 * <p>{@link #now()}는 UTC 시각(비교 · 정렬 · 시한 판정 · 저장에 쓴다, R2), {@link #monotonicNanos()}는 관측용 지연 길이를
 * 재는 단조 읽기다(R3). 운영은 {@link SystemMujoongClock}, 시험은 가변 Clock(test-support {@code MutableClock})이다.
 * 시스템 시각을 직접 부르는 곳은 {@link SystemMujoongClock} 한 곳뿐이다(ArchUnit ⑤, J113).
 */
public interface MujoongClock {

    /** 지금 UTC 시각. */
    Instant now();

    /** 단조 읽기(나노초). 값 자체는 뜻이 없고 두 읽기의 차이만 쓴다. */
    long monotonicNanos();

    /** {@code startNanos}(앞서 읽은 단조 값) 뒤 지난 시간. 음수가 되지 않는다. */
    default Duration elapsedSince(long startNanos) {
        long elapsed = monotonicNanos() - startNanos;
        return Duration.ofNanos(Math.max(0L, elapsed));
    }
}
