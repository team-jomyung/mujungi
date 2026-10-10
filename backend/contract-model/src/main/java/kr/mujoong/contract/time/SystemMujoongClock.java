package kr.mujoong.contract.time;

import java.time.Clock;
import java.time.Instant;

/**
 * 운영 Clock(RD-2). 시스템 UTC 시계와 시스템 단조 시계를 감싸는 유일한 어댑터다(R1 허용 목록, ArchUnit ⑤).
 * 호스트 시계가 뛰는 문제는 호스트 시간 동기에 맡긴다(R4).
 */
public final class SystemMujoongClock implements MujoongClock {

    private final Clock utc = Clock.systemUTC();

    @Override
    public Instant now() {
        return utc.instant();
    }

    @Override
    public long monotonicNanos() {
        return System.nanoTime();
    }
}
