package kr.mujoong.core.common.db;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 짧은 DB 재시도 (U1 3.5 Step 15 · RD-22 · J83 · J124).
 *
 * <p>교착(40P01) · 직렬화 실패(40001) · 락 대기 초과(55P03)에 한해, 멱등이라고 표시한 연산만
 * {@code mujoong.limits.db-retry-attempts}(2)회까지 다시 한다. 매번 0 ~ {@value #MAX_JITTER_MILLIS}ms 무작위로 기다린다
 * (지터 상한은 J124가 허용한 코드 상수). 멱등이 아닌 연산과 그 밖의 오류는 곧바로 위로 올린다.
 *
 * <p>되돌려진 트랜잭션은 이어서 쓸 수 없으므로 시도 하나가 트랜잭션 하나다: 호출자는 트랜잭션 밖에서 부르고 시도 안에서
 * 트랜잭션을 연다(예: {@code dbRetry.execute(IDEMPOTENT, () -> transactionTemplate.execute(...))}). 바깥 트랜잭션이 이미
 * 열려 있으면 재시도하지 않고 오류를 그대로 올려 바깥 경계가 판단하게 한다.
 */
@Component
public class DbRetry {

    /** 재시도 사이 무작위 대기 상한(ms, J124 허용 상수). */
    public static final long MAX_JITTER_MILLIS = 50;

    /** 연산이 다시 해도 같은 결과인가. */
    public enum Idempotency {
        /** 키 멱등 시작 · 등록 · 이벤트 처리기 · Outbox/Inbox 상태 갱신처럼 다시 해도 결과가 같다. */
        IDEMPOTENT,
        /** 다시 하면 결과가 달라질 수 있다. 재시도하지 않는다. */
        NON_IDEMPOTENT
    }

    /** 대기 장치(시험은 실제로 기다리지 않는 대역을 쓴다). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final int maxRetries;
    private final RandomGenerator random;
    private final Sleeper sleeper;

    /** 운영: 재시도 횟수는 {@code mujoong.limits.db-retry-attempts}, 대기는 실제 스레드 대기. */
    @Autowired
    public DbRetry(CoreProperties properties) {
        this(properties.limits().dbRetryAttempts(), () -> ThreadLocalRandom.current().nextLong(),
                duration -> Thread.sleep(duration));
    }

    /**
     * @param maxRetries 첫 시도 뒤 다시 할 수 있는 횟수(0 이상)
     * @param random 지터 난수원
     * @param sleeper 대기 장치
     */
    public DbRetry(int maxRetries, RandomGenerator random, Sleeper sleeper) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries는 0 이상이어야 한다");
        }
        this.maxRetries = maxRetries;
        this.random = Objects.requireNonNull(random, "random");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** 첫 시도 뒤 다시 할 수 있는 횟수. */
    public int maxRetries() {
        return maxRetries;
    }

    /** 결과 없는 연산을 실행한다. */
    public void run(Idempotency idempotency, Runnable attempt) {
        Objects.requireNonNull(attempt, "attempt");
        execute(idempotency, () -> {
            attempt.run();
            return null;
        });
    }

    /**
     * 시도를 실행하고, 멱등이며 재시도 대상 SQLSTATE이면 상한까지 다시 한다. 상한을 넘거나 재시도 대상이 아니면 마지막 오류를 올린다
     * (앞 시도의 오류는 suppressed로 붙인다).
     */
    public <T> T execute(Idempotency idempotency, Supplier<T> attempt) {
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(attempt, "attempt");
        RuntimeException previous = null;
        for (int retry = 0; ; retry++) {
            try {
                return attempt.get();
            } catch (RuntimeException error) {
                if (previous != null) {
                    error.addSuppressed(previous);
                }
                if (!shouldRetry(idempotency, error, retry)) {
                    throw error;
                }
                previous = error;
                pause();
            }
        }
    }

    private boolean shouldRetry(Idempotency idempotency, RuntimeException error, int retriesSoFar) {
        return idempotency == Idempotency.IDEMPOTENT
                && retriesSoFar < maxRetries
                && SqlErrors.isRetryable(error)
                && !TransactionSynchronizationManager.isActualTransactionActive();
    }

    private void pause() {
        Duration jitter = Duration.ofMillis(random.nextLong(MAX_JITTER_MILLIS + 1));
        try {
            sleeper.sleep(jitter);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DB 재시도 대기 중 인터럽트", interrupted);
        }
    }
}
