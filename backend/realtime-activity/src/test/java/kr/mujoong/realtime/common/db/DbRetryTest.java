package kr.mujoong.realtime.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.realtime.common.db.DbRetry.Idempotency;
import kr.mujoong.realtime.config.RealtimeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

/**
 * Realtime DB 재시도 · 예외 분류 단위 시험(U1 3.5 Step 18 · RD-22 · J83 · J124). DB 없이 SQLSTATE를 담은 예외로 확인한다.
 *
 * <p>증명: 40P01 · 40001 · 55P03만 멱등 연산에서 재시도 · 비멱등은 즉시 실패 · 상한(2회) 뒤 마지막 오류와 앞 오류(suppressed) ·
 * 지터 0 ~ 50ms · 바깥 트랜잭션이 열려 있으면 재시도하지 않음 · 대기 중 인터럽트 보존.
 */
@Tag("u1")
class DbRetryTest {

    private final List<Duration> sleeps = new ArrayList<>();

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void anIdempotentOperationIsRetriedOnADeadlockAndThenSucceeds() {
        DbRetry retry = new DbRetry(2, () -> 7L, sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.execute(Idempotency.IDEMPOTENT, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw dbError(SqlErrors.DEADLOCK_DETECTED);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(retry.maxRetries()).isEqualTo(2);
        // 시도 사이마다 한 번, 지터는 0 ~ 50ms 안
        assertThat(sleeps).hasSize(2)
                .allMatch(sleep -> !sleep.isNegative() && sleep.toMillis() <= DbRetry.MAX_JITTER_MILLIS);
    }

    @Test
    void retriesStopAtTheLimitAndKeepEarlierErrorsAsSuppressed() {
        DbRetry retry = new DbRetry(2, () -> 60L, sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw dbError(SqlErrors.LOCK_NOT_AVAILABLE);
        })).isInstanceOf(DataAccessException.class)
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));

        assertThat(attempts).hasValue(3);
        assertThat(sleeps).hasSize(2).allMatch(sleep -> sleep.toMillis() >= 0 && sleep.toMillis() <= DbRetry.MAX_JITTER_MILLIS);
    }

    @Test
    void nonIdempotentOperationsAndOtherErrorsAreNotRetried() {
        DbRetry retry = new DbRetry(2, () -> 1L, sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(Idempotency.NON_IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw dbError(SqlErrors.SERIALIZATION_FAILURE);
        })).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw dbError(SqlErrors.UNIQUE_VIOLATION);
        })).isInstanceOf(DataAccessException.class);
        // 문장 시간 초과(57014)도 재시도 대상이 아니다
        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw dbError("57014");
        })).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("업무 오류");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(attempts).hasValue(4);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void anOpenOuterTransactionIsNeverRetriedFromInside() {
        DbRetry retry = new DbRetry(2, () -> 1L, sleeps::add);
        AtomicInteger attempts = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            throw dbError(SqlErrors.DEADLOCK_DETECTED);
        })).isInstanceOf(DataAccessException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void anInterruptedWaitStopsRetryingAndKeepsTheInterruptFlag() {
        DbRetry retry = new DbRetry(2, () -> 1L, duration -> {
            throw new InterruptedException();
        });

        assertThatThrownBy(() -> retry.run(Idempotency.IDEMPOTENT, () -> {
            throw dbError(SqlErrors.DEADLOCK_DETECTED);
        })).isInstanceOf(IllegalStateException.class);

        assertThat(Thread.interrupted()).isTrue();
        assertThatThrownBy(() -> new DbRetry(-1, () -> 1L, sleeps::add)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retry.execute(null, () -> 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theApplicationBeanTakesItsRetryCountFromTheLimitsSetting() {
        RealtimeProperties properties = mock(RealtimeProperties.class);
        when(properties.limits()).thenReturn(new RealtimeProperties.Limits(DataSize.ofKilobytes(8),
                DataSize.ofMegabytes(2), 50, 1000, 100, 200, 2, 3, Duration.ofSeconds(2), Duration.ofSeconds(10), 1));
        DbRetry retry = new DbRetry(properties);
        AtomicInteger attempts = new AtomicInteger();

        // 운영 대기 장치(0 ~ 50ms 실제 대기)로 한 번 다시 한다
        int result = retry.execute(Idempotency.IDEMPOTENT, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw dbError(SqlErrors.SERIALIZATION_FAILURE);
            }
            return attempts.get();
        });

        assertThat(retry.maxRetries()).isEqualTo(1);
        assertThat(result).isEqualTo(2);
    }

    @Test
    void sqlErrorsReadTheStateAndConstraintFromTheCauseChainOnly() {
        PSQLException unique = new PSQLException(new ServerErrorMessage(
                "SERROR\u0000C23505\u0000Mduplicate key value violates unique constraint\u0000"
                        + "nrealtime_activity_session_pk\u0000"));
        DataIntegrityViolationException translated = new DataIntegrityViolationException("번역됨", unique);

        assertThat(SqlErrors.sqlState(translated)).contains(SqlErrors.UNIQUE_VIOLATION);
        assertThat(SqlErrors.isUniqueViolation(translated)).isTrue();
        assertThat(SqlErrors.isForeignKeyViolation(translated)).isFalse();
        assertThat(SqlErrors.isRetryable(translated)).isFalse();
        assertThat(SqlErrors.constraintName(translated)).contains("realtime_activity_session_pk");
        // 드라이버 예외가 아니면 제약 이름이 없다
        assertThat(SqlErrors.constraintName(dbError(SqlErrors.FOREIGN_KEY_VIOLATION))).isEmpty();
        assertThat(SqlErrors.isForeignKeyViolation(dbError(SqlErrors.FOREIGN_KEY_VIOLATION))).isTrue();
        assertThat(SqlErrors.sqlState(new IllegalStateException("원인 없음"))).isEmpty();
        assertThat(SqlErrors.sqlState(null)).isEmpty();
    }

    private static DataAccessException dbError(String sqlState) {
        return new CannotAcquireLockException("DB 오류", new SQLException("서버 오류", sqlState));
    }
}
