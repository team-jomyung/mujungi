package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.positive;

import java.time.Duration;

/** 풀 대기 경고 기준. */
public record CoreMetricsProperties(Duration dbPoolWaitWarn) {
    public CoreMetricsProperties {
        positive(dbPoolWaitWarn, "mujoong.metrics.db-pool-wait-warn");
    }
}
