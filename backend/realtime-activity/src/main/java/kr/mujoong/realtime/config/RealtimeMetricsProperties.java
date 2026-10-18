package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;

/** NFR7.8 풀 대기 경고 기준. */
public record RealtimeMetricsProperties(Duration dbPoolWaitWarn) {
    public RealtimeMetricsProperties {
        positive(dbPoolWaitWarn, "mujoong.metrics.db-pool-wait-warn");
    }
}
