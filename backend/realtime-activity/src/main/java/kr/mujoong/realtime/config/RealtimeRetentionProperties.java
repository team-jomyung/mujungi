package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;

/** FAILED 행 본문 보존. */
public record RealtimeRetentionProperties(Duration failedPayload) {
    public RealtimeRetentionProperties {
        positive(failedPayload, "mujoong.retention.failed-payload");
    }
}
