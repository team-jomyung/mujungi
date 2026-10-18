package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.invalid;
import static kr.mujoong.core.config.CoreProperties.nonNegative;
import static kr.mujoong.core.config.CoreProperties.positive;

import java.time.Duration;
import org.springframework.util.unit.DataSize;

/** BR1.6 · (r) · BR3.9 · RD-22 한도. */
public record CoreLimitsProperties(
        Integer rateCounterMaxKeys,
        DataSize restBodyMax,
        DataSize internalEventBodyMax,
        Integer backgroundDbConcurrency,
        Duration dbLockTimeout,
        Duration dbStatementTimeout,
        Integer dbRetryAttempts,
        Integer resultWorkers) {

    public CoreLimitsProperties {
        positive(rateCounterMaxKeys, "mujoong.limits.rate-counter-max-keys");
        positive(restBodyMax, "mujoong.limits.rest-body-max");
        positive(internalEventBodyMax, "mujoong.limits.internal-event-body-max");
        positive(backgroundDbConcurrency, "mujoong.limits.background-db-concurrency");
        positive(dbLockTimeout, "mujoong.limits.db-lock-timeout");
        positive(dbStatementTimeout, "mujoong.limits.db-statement-timeout");
        nonNegative(dbRetryAttempts, "mujoong.limits.db-retry-attempts");
        positive(resultWorkers, "mujoong.limits.result-workers");
        if (resultWorkers != 2 || resultWorkers > backgroundDbConcurrency) {
            throw invalid("mujoong.limits.result-workers는 전용 스레드 둘이며 DB 동시성 이하여야 한다");
        }
    }
}
