package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.positive;
import static kr.mujoong.core.config.CoreProperties.positiveList;

import java.time.Duration;
import java.util.List;

/** Inbox 처리. */
public record CoreInboxProperties(
        Duration pollInterval,
        List<Duration> retryBackoff,
        Integer maxRetries,
        Duration claimLease,
        Integer handlerThreads,
        Integer batchSize) {

    public CoreInboxProperties {
        positive(pollInterval, "mujoong.inbox.poll-interval");
        positiveList(retryBackoff, "mujoong.inbox.retry-backoff");
        positive(maxRetries, "mujoong.inbox.max-retries");
        positive(claimLease, "mujoong.inbox.claim-lease");
        positive(handlerThreads, "mujoong.inbox.handler-threads");
        positive(batchSize, "mujoong.inbox.batch-size");
        retryBackoff = List.copyOf(retryBackoff);
    }
}
