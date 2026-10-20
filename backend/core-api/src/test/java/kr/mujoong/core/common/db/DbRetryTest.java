package kr.mujoong.core.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import kr.mujoong.core.common.db.DbRetry.Idempotency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link DbRetry} 단위 시험(U1 3.5 Step 17 · RD-22 · J83 · J124). 실제로 기다리지 않는 대기 장치와 고정 난수를 쓴다.
 *
 * <p>증명: 멱등 연산만 재시도(40P01 · 40001 · 55P03), 비멱등은 즉시 실패, 재시도 2회 상한, 그 밖의 SQLSTATE · 비 DB 오류는 재시도 없음,
 * 열린 트랜잭션 안에서는 재시도하지 않음, 지터 0 ~ 50ms, 대기 중 인터럽트.
 */
@Tag("skeleton")
class DbRetryTest {

    private final List<Duration> sleeps = new ArrayList<>();

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void idempotentWorkIsRetriedOnDeadlockUntilItSucceeds() {
        DbRetry retry = new DbRetry(2, fixedRandom(17), sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.execute(Idempotency.IDEMPOTENT, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new PessimisticLockingFailureException("교착", sqlState("40P01"));
            }
            return "등록됨";
        });

        assertThat(result).isEqualTo("등록됨");
        assertThat(attempts).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(17), Duration.ofMillis(17));
        assertThat(retry.maxRetries()).isEqualTo(2);
    }

    @Test
    void runCompletesVoidWorkAfterARetry() {
        DbRetry retry = new DbRetry(2, fixedRandom(3), sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        retry.run(Idempotency.IDEMPOTENT, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("락 대기 초과", sqlState("55P03"));
            }
        });

        assertThat(attempts).hasValue(2);
        assertThat(sleeps).containsExactly(Duration.ofMillis(3));
    }

    @Test
    void nonIdempotentWorkFailsImmediatelyEvenOnARetryableState() {
        DbRetry retry = new DbRetry(2, fixedRandom(0), sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(Idempotency.NON_IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("락 대기 초과", sqlState("55P03"));
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(attempts).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesStopAtTheLimitAndTheLastErrorCarriesTheEarlierOnes() {
        DbRetry retry = new DbRetry(2, fixedRandom(5), sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw new PessimisticLockingFailureException("직렬화 실패 " + attempts.get(), sqlState("40001"));
        })).isInstanceOf(PessimisticLockingFailureException.class)
                .hasMessageContaining("직렬화 실패 3")
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1)
                        .allSatisfy(suppressed -> assertThat(suppressed.getSuppressed()).hasSize(1)));

        assertThat(attempts).as("첫 시도 + 재시도 2회").hasValue(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void otherStatesAndNonDatabaseErrorsAreNotRetried() {
        DbRetry retry = new DbRetry(2, fixedRandom(0), sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw new DataIntegrityViolationException("유일 위반", sqlState("23505"));
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw new QueryTimeoutException("문장 시간 초과", sqlState("57014"));
        })).isInstanceOf(QueryTimeoutException.class);
        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("업무 오류");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(attempts).hasValue(3);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void anOpenOuterTransactionIsNeverRetriedBecauseItIsAlreadyAborted() {
        DbRetry retry = new DbRetry(2, fixedRandom(0), sleeps::add);
        AtomicInteger attempts = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw new PessimisticLockingFailureException("교착", sqlState("40P01"));
        })).isInstanceOf(PessimisticLockingFailureException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void jitterStaysBetweenZeroAndFiftyMilliseconds() {
        List<Duration> observed = new ArrayList<>();
        for (long drawn : new long[] {0, 50, Long.MAX_VALUE}) {
            DbRetry retry = new DbRetry(1, boundedRandom(drawn), observed::add);
            AtomicInteger attempts = new AtomicInteger();
            retry.execute(Idempotency.IDEMPOTENT, () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new CannotAcquireLockException("락", sqlState("55P03"));
                }
                return attempts.get();
            });
        }
        assertThat(observed).containsExactly(Duration.ZERO, Duration.ofMillis(50), Duration.ofMillis(50));
        assertThat(observed).allSatisfy(wait -> assertThat(wait).isBetween(Duration.ZERO,
                Duration.ofMillis(DbRetry.MAX_JITTER_MILLIS)));
    }

    @Test
    void anInterruptWhileWaitingStopsRetryingAndKeepsTheInterruptFlag() {
        DbRetry retry = new DbRetry(2, fixedRandom(1), duration -> {
            throw new InterruptedException("정지");
        });
        try {
            assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
                throw new PessimisticLockingFailureException("교착", sqlState("40P01"));
            })).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void constructorRejectsInvalidArguments() {
        assertThatThrownBy(() -> new DbRetry(-1, fixedRandom(0), sleeps::add)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DbRetry(0, null, sleeps::add)).isInstanceOf(NullPointerException.class);
        DbRetry noRetry = new DbRetry(0, fixedRandom(0), sleeps::add);
        assertThatThrownBy(() -> noRetry.run(Idempotency.IDEMPOTENT, () -> {
            throw new PessimisticLockingFailureException("교착", sqlState("40P01"));
        })).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(sleeps).isEmpty();
        assertThatThrownBy(() -> noRetry.execute(null, () -> 1)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ 도우미

    private static SQLException sqlState(String state) {
        return new SQLException("시험 오류", state);
    }

    /** {@code nextLong(bound)}가 늘 {@code value}를 돌려주는 난수(상한 안의 값). */
    private static RandomGenerator fixedRandom(long value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return value;
            }

            @Override
            public long nextLong(long bound) {
                return value;
            }
        };
    }

    /** 뽑은 값을 상한 안으로 접는 난수(경계 확인용). */
    private static RandomGenerator boundedRandom(long drawn) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return drawn;
            }

            @Override
            public long nextLong(long bound) {
                return Math.min(drawn, bound - 1);
            }
        };
    }
}
