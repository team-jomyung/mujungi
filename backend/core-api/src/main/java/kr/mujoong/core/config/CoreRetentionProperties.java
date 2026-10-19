package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.positive;

import java.time.Duration;

/** FAILED 행 본문 보존. */
public record CoreRetentionProperties(Duration failedPayload) {
    public CoreRetentionProperties {
        positive(failedPayload, "mujoong.retention.failed-payload");
    }
}
