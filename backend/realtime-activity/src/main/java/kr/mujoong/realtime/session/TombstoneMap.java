package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Core가 취소한 sessionId의 묘비 (U1 3.5 Step 23 · BR6.6 마지막 문장 · BR6.7 (가) · R3-9 · J111 (v)).
 *
 * <p>Core 취소(CORE_ABANDONED)가 시작 명령보다 먼저 오면 세션이 없으므로 묘비만 남기고 202로 답한다. 묘비가 살아 있는
 * {@code mujoong.session.tombstone-ttl}(10분) 동안 같은 sessionId의 늦은 시작은 레지스트리를 만지기 전(0단계)에 409
 * SESSION_NOT_ACTIVE로 거절한다. Realtime 메모리 값이라 재시작하면 사라진다(그때 늦게 만들어진 세션은 BR6.14의 재취소로 닫힌다).
 */
@Component
public class TombstoneMap {

    private final ConcurrentHashMap<UUID, Instant> stones = new ConcurrentHashMap<>();
    private final MujoongClock clock;
    private final Duration ttl;

    @Autowired
    public TombstoneMap(MujoongClock clock, RealtimeProperties properties) {
        this(clock, properties.session().tombstoneTtl());
    }

    public TombstoneMap(MujoongClock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("tombstone-ttl은 0보다 커야 한다");
        }
    }

    /** 묘비를 세운다(이미 있으면 만료를 늘리지 않는다). */
    public void add(UUID sessionId) {
        stones.putIfAbsent(Objects.requireNonNull(sessionId, "sessionId"), clock.now().plus(ttl));
    }

    /** 살아 있는 묘비가 있는가(만료된 것은 지운다). */
    public boolean contains(UUID sessionId) {
        Instant expiresAt = stones.get(sessionId);
        if (expiresAt == null) {
            return false;
        }
        if (!clock.now().isBefore(expiresAt)) {
            stones.remove(sessionId, expiresAt);
            return false;
        }
        return true;
    }

    /** 만료된 묘비를 지운다(maintenance). 지운 개수. */
    public int purgeExpired() {
        Instant now = clock.now();
        int removed = 0;
        for (Map.Entry<UUID, Instant> entry : stones.entrySet()) {
            if (!now.isBefore(entry.getValue()) && stones.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    /** 살아 있는 묘비 수(시험 · 진단). */
    public int size() {
        return stones.size();
    }
}
