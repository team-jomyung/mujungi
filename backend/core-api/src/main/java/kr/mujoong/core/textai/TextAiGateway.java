package kr.mujoong.core.textai;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.usage.AiUsageScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** One exit for text AI: minimal typed input, bounded transient retries, independent output checks. */
@Service
public class TextAiGateway {
    private final TextAiProvider provider;
    private final TextAiProperties properties;
    private final ForbiddenExpressionPolicy policy;
    private final MujoongClock clock;
    private final ExecutorService executor;

    public TextAiGateway(TextAiProvider provider, TextAiProperties properties, ForbiddenExpressionPolicy policy,
            MujoongClock clock, @Qualifier("textAiExecutor") ExecutorService executor) {
        this.provider = provider; this.properties = properties; this.policy = policy;
        this.clock = clock; this.executor = executor;
    }

    public GeneratedScenario generateScenario(ScenarioPrompt prompt, Instant deadline) {
        long started = clock.monotonicNanos();
        Duration budget = Duration.between(clock.now(), deadline);
        for (int attempt = 0;; attempt++) {
            Duration remaining = remaining(deadline, started, budget);
            if (remaining.isNegative() || remaining.isZero() || Thread.currentThread().isInterrupted()) {
                throw new TextAiException(TextAiException.Reason.TIMEOUT);
            }
            Duration limit = remaining.compareTo(properties.requestTimeout()) < 0
                    ? remaining : properties.requestTimeout();
            try {
                GeneratedScenario result = call(prompt, limit);
                if (remaining(deadline, started, budget).isNegative()
                        || remaining(deadline, started, budget).isZero()) {
                    throw new TextAiException(TextAiException.Reason.TIMEOUT);
                }
                validate(result);
                if (remaining(deadline, started, budget).compareTo(Duration.ZERO) <= 0) {
                    throw new TextAiException(TextAiException.Reason.TIMEOUT);
                }
                return result;
            } catch (TextAiException failure) {
                if (!failure.retryable() || (failure.reason() != TextAiException.Reason.UNAVAILABLE
                        && failure.reason() != TextAiException.Reason.TIMEOUT)
                        || attempt >= properties.retry().maxRetries()) { throw failure; }
                Duration left = remaining(deadline, started, budget);
                if (left.compareTo(properties.retry().backoff()) <= 0) {
                    throw new TextAiException(TextAiException.Reason.TIMEOUT);
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(properties.retry().backoff().toNanos());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new TextAiException(TextAiException.Reason.TIMEOUT);
                }
            }
        }
    }

    private GeneratedScenario call(ScenarioPrompt prompt, Duration limit) {
        Future<GeneratedScenario> future;
        // 사용량 연결은 실행기 스레드로 옮겨야 Luna 호출부가 읽는다(JIRA-277)
        try { future = executor.submit(AiUsageScope.propagate(() -> provider.generateScenario(prompt, limit))); }
        catch (RejectedExecutionException rejected) { throw new TextAiException(TextAiException.Reason.UNAVAILABLE); }
        try {
            return future.get(limit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new TextAiException(TextAiException.Reason.TIMEOUT, true);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TextAiException(TextAiException.Reason.TIMEOUT);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof TextAiException failure) { throw failure; }
            throw new TextAiException(TextAiException.Reason.UNAVAILABLE);
        }
    }

    private void validate(GeneratedScenario output) {
        if (output == null || invalidMetadata(output.modelVersion()) || invalidMetadata(output.promptVersion())) {
            throw new TextAiException(TextAiException.Reason.INVALID_OUTPUT);
        }
        if (tooLong(output.scenarioText(), 8000) || tooLong(output.triggerScript(), 2000)
                || tooLong(output.opportunityCondition(), 2000) || tooLong(output.relationType(), 200)) {
            throw new TextAiException(TextAiException.Reason.INVALID_OUTPUT);
        }
        policy.requireAllowed(output.scenarioText(), output.triggerScript(), output.relationType(),
                output.opportunityCondition());
    }

    private static boolean tooLong(String value, int maximum) { return value != null && value.length() > maximum; }

    private static boolean invalidMetadata(String value) {
        return value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");
    }

    private Duration remaining(Instant deadline, long started, Duration budget) {
        Duration wall = Duration.between(clock.now(), deadline);
        Duration monotonic = budget.minus(clock.elapsedSince(started));
        return wall.compareTo(monotonic) < 0 ? wall : monotonic;
    }
}
