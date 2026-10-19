package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;
import static kr.mujoong.realtime.config.RealtimeProperties.positiveList;
import static kr.mujoong.realtime.config.RealtimeProperties.required;

import java.time.Duration;
import java.util.List;

/** Outbox 발송과 A1 제어 이벤트 정책. */
public record RealtimeOutboxProperties(
        Duration pollInterval,
        Integer batchSize,
        List<Duration> retryBackoff,
        Integer maxRetries,
        Duration claimLease,
        ControlEventPolicy controlEventPolicy) {

    public RealtimeOutboxProperties {
        positive(pollInterval, "mujoong.outbox.poll-interval");
        positive(batchSize, "mujoong.outbox.batch-size");
        positiveList(retryBackoff, "mujoong.outbox.retry-backoff");
        positive(maxRetries, "mujoong.outbox.max-retries");
        positive(claimLease, "mujoong.outbox.claim-lease");
        required(controlEventPolicy, "mujoong.outbox.control-event-policy");
        retryBackoff = List.copyOf(retryBackoff);
    }

    /** A1 · RD-10. RETRY_FOREVER는 보류 옵션이며 기동 거부는 재무장 장치가 맡는다. */
    public enum ControlEventPolicy {
        REARM_ON_START,
        RETRY_FOREVER
    }
}
