package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;
import static kr.mujoong.realtime.config.RealtimeProperties.positiveList;

import java.time.Duration;
import java.util.List;

/** RD-9 · BR5.5 ~ BR5.8 Inbox 처리. */
public record RealtimeInboxProperties(
        Duration pollInterval,
        List<Duration> retryBackoff,
        Integer maxRetries,
        Duration claimLease,
        Integer handlerThreads,
        Integer batchSize) {

    public RealtimeInboxProperties {
        positive(pollInterval, "mujoong.inbox.poll-interval");
        positiveList(retryBackoff, "mujoong.inbox.retry-backoff");
        positive(maxRetries, "mujoong.inbox.max-retries");
        positive(claimLease, "mujoong.inbox.claim-lease");
        positive(handlerThreads, "mujoong.inbox.handler-threads");
        positive(batchSize, "mujoong.inbox.batch-size");
        retryBackoff = List.copyOf(retryBackoff);
    }
}
