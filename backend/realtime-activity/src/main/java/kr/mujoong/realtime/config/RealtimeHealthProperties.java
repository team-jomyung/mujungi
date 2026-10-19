package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;

/** RD-16 · OD-11 준비 상태 DB 점검. */
public record RealtimeHealthProperties(Duration dbCheckInterval, Integer dbFailureThreshold) {
    public RealtimeHealthProperties {
        positive(dbCheckInterval, "mujoong.health.db-check-interval");
        positive(dbFailureThreshold, "mujoong.health.db-failure-threshold");
    }
}
