package kr.mujoong.realtime.observability;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 보안 사건 로그 분당 상한 (U1 3.5 Step 26 · OD-3 4번 · SD-15 '로그 홍수 방지' ② · OD-13).
 *
 * <p>같은 type(사건 코드)의 보안 사건 줄이 한 분(UTC 분 경계)에 {@code mujoong.security.event-log-rate-cap}(60줄)을 넘으면 그 분의 나머지
 * 줄은 쓰지 않는다. 지표는 상한과 무관하게 실제 건수를 센다(호출자 몫). 시각은 주입한 Clock 값이다. 스레드 안전하다.
 */
public final class SecurityEventCap {

    private final int capPerMinute;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public SecurityEventCap(int capPerMinute) {
        if (capPerMinute <= 0) {
            throw new IllegalArgumentException("보안 사건 상한은 0보다 커야 한다");
        }
        this.capPerMinute = capPerMinute;
    }

    /** {@code type}의 줄을 지금 써도 되는가(호출이 곧 한 줄로 센다). */
    public synchronized boolean allow(String type, Instant now) {
        Objects.requireNonNull(type, "type");
        long minute = Math.floorDiv(Objects.requireNonNull(now, "now").getEpochSecond(), 60L);
        Bucket bucket = buckets.get(type);
        if (bucket == null || bucket.minute != minute) {
            bucket = new Bucket(minute);
            buckets.put(type, bucket);
        }
        bucket.count++;
        return bucket.count <= capPerMinute;
    }

    /** 분당 상한. */
    public int capPerMinute() {
        return capPerMinute;
    }

    private static final class Bucket {
        private final long minute;
        private int count;

        Bucket(long minute) {
            this.minute = minute;
        }
    }
}
