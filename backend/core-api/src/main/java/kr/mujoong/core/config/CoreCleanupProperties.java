package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.positive;

import java.time.Duration;

/** BR5.11 · BR1.4 정리 작업. */
public record CoreCleanupProperties(
        Duration messagingInterval,
        Duration deviceExpiryInterval,
        Duration expiredDeviceRetention,
        Integer batchSize) {

    public CoreCleanupProperties {
        positive(messagingInterval, "mujoong.cleanup.messaging-interval");
        positive(deviceExpiryInterval, "mujoong.cleanup.device-expiry-interval");
        positive(expiredDeviceRetention, "mujoong.cleanup.expired-device-retention");
        positive(batchSize, "mujoong.cleanup.batch-size");
    }
}
