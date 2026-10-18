package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.positive;

import java.time.Duration;

/** RD-16 · OD-11 준비 상태 DB 점검. */
public record CoreHealthProperties(Duration dbCheckInterval, Integer dbFailureThreshold) {
    public CoreHealthProperties {
        positive(dbCheckInterval, "mujoong.health.db-check-interval");
        positive(dbFailureThreshold, "mujoong.health.db-failure-threshold");
    }
}
