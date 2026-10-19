package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;

/** 메시징 정리. */
public record RealtimeCleanupProperties(Duration messagingInterval, Integer batchSize) {
    public RealtimeCleanupProperties {
        positive(messagingInterval, "mujoong.cleanup.messaging-interval");
        positive(batchSize, "mujoong.cleanup.batch-size");
    }
}
