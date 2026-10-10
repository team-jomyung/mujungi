package kr.mujoong.core.common.db;

import static kr.mujoong.core.common.db.DbTestSupport.EPOCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.core.common.db.DbRetry.Idempotency;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DbSessionLimits}와 {@link DbRetry}를 실제 PostgreSQL로 확인한다(U1 3.5 Step 17 · RD-22 · NFR7.6).
 *
 * <p>증명: 새 연결마다 lock_timeout · statement_timeout이 설정 값으로 걸린다, 락 대기 초과는 실제 55P03이고 재시도 대상이다, 멱등 연산은
 * 락이 풀린 뒤 재시도로 성공한다, 문장 시간 초과(57014)는 재시도하지 않는다, 설정 키가 없거나 0이면 기동이 실패한다.
 */
@Tag("u1")
@Tag("db")
class DbSessionLimitsIT {

    private static final Duration LOCK_TIMEOUT = Duration.ofMillis(200);
    private static final Duration STATEMENT_TIMEOUT = Duration.ofMillis(1_500);

    private DbTestSupport db;
    private HikariDataSource limited;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        limited = new HikariDataSource();
        limited.setPoolName("db-session-limits-it");
        limited.setJdbcUrl(db.postgres().jdbcUrl());
        limited.setUsername(db.postgres().username(Role.CORE_APP));
        limited.setPassword(db.postgres().password(Role.CORE_APP));
        limited.setMaximumPoolSize(2);
        limited.setMinimumIdle(0);
        Object processed = new DbSessionLimits(LOCK_TIMEOUT, STATEMENT_TIMEOUT)
                .postProcessBeforeInitialization(limited, "dataSource");
        assertThat(processed).isSameAs(limited);
    }

    @AfterEach
    void tearDown() {
        limited.close();
        db.close();
    }

    @Test
    void everyNewConnectionCarriesTheConfiguredTimeouts() {
        JdbcTemplate jdbc = new JdbcTemplate(limited);
        assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("200ms");
        assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("1500ms");
        assertThat(limited.getConnectionInitSql()).contains("lock_timeout").contains("statement_timeout");
    }

    @Test
    void aLockWaitPastTheLimitIsRetryableAndSucceedsOnceTheLockIsReleased() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> holder = db.startTx(new AtomicInteger(), status -> {
            int rows = db.jdbc().update("update core.core_guardian set updated_at = :now where id = :id",
                    ClockParams.at(EPOCH.plusSeconds(1)).addValue("id", guardianId));
            locked.countDown();
            awaitLatch(release);
            return rows;
        });
        awaitLatch(locked);

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(limited));
        JdbcTemplate jdbc = new JdbcTemplate(limited);
        AtomicInteger attempts = new AtomicInteger();
        // 첫 대기에서 잡고 있던 쪽을 풀어 준다(지터 대기 자리에서 시험이 락을 놓는다)
        DbRetry retry = new DbRetry(2, () -> 0L, wait -> {
            release.countDown();
            DbTestSupport.await(holder);
        });

        Integer rows = retry.execute(Idempotency.IDEMPOTENT, () -> tx.execute(status -> {
            attempts.incrementAndGet();
            return jdbc.update("update core.core_guardian set status = 'ACTIVE' where id = ?", guardianId);
        }));

        assertThat(rows).isEqualTo(1);
        assertThat(attempts).as("첫 시도는 55P03, 두 번째에 성공").hasValue(2);
    }

    @Test
    void aLockWaitPastTheLimitSurfacesAs55P03WhenNotRetried() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> holder = db.startTx(new AtomicInteger(), status -> {
            int rows = db.jdbc().update("update core.core_guardian set updated_at = :now where id = :id",
                    ClockParams.at(EPOCH.plusSeconds(2)).addValue("id", guardianId));
            locked.countDown();
            awaitLatch(release);
            return rows;
        });
        awaitLatch(locked);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(limited);
            assertThatThrownBy(() -> new DbRetry(2, () -> 0L, wait -> { }).run(Idempotency.NON_IDEMPOTENT,
                    () -> jdbc.update("update core.core_guardian set status = 'ACTIVE' where id = ?", guardianId)))
                    .isInstanceOf(DataAccessException.class)
                    .satisfies(error -> {
                        assertThat(SqlErrors.sqlState(error)).contains(SqlErrors.LOCK_NOT_AVAILABLE);
                        assertThat(SqlErrors.isRetryable(error)).isTrue();
                    });
        } finally {
            release.countDown();
            DbTestSupport.await(holder);
        }
    }

    @Test
    void aStatementPastTheLimitIsCancelledAndNotRetried() {
        JdbcTemplate jdbc = new JdbcTemplate(limited);
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> new DbRetry(2, () -> 0L, wait -> { }).run(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            jdbc.queryForObject("select pg_sleep(5)::text", String.class);
        })).isInstanceOf(DataAccessException.class)
                .satisfies(error -> assertThat(SqlErrors.sqlState(error)).contains("57014"));
        assertThat(attempts).hasValue(1);
    }

    @Test
    void limitsAreBoundFromConfigurationKeysAndInvalidSetupsFailFast() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DbSessionLimits.LOCK_TIMEOUT_KEY, "2s")
                .withProperty(DbSessionLimits.STATEMENT_TIMEOUT_KEY, "10s");
        DbSessionLimits fromKeys = new DbSessionLimits(environment);
        assertThat(fromKeys.lockTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(fromKeys.statementTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(fromKeys.connectionInitSql()).contains("'2000ms'").contains("'10000ms'");

        assertThatThrownBy(() -> new DbSessionLimits(new MockEnvironment()
                .withProperty(DbSessionLimits.LOCK_TIMEOUT_KEY, "2s")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(DbSessionLimits.STATEMENT_TIMEOUT_KEY);
        assertThatThrownBy(() -> new DbSessionLimits(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(DbSessionLimits.LOCK_TIMEOUT_KEY);
        assertThatThrownBy(() -> new DbSessionLimits(Duration.ofSeconds(1), Duration.ofNanos(10)))
                .isInstanceOf(IllegalArgumentException.class);

        // 다른 초기화 문장을 숨은 덮어쓰기로 지우지 않는다
        try (HikariDataSource other = new HikariDataSource()) {
            other.setConnectionInitSql("select 1");
            assertThatThrownBy(() -> fromKeys.apply(other)).isInstanceOf(IllegalStateException.class);
        }
        // 같은 문장이 이미 있으면(두 번 적용) 그대로 둔다. Hikari가 아닌 빈은 손대지 않는다
        try (HikariDataSource twice = new HikariDataSource()) {
            fromKeys.apply(twice);
            fromKeys.apply(twice);
            assertThat(twice.getConnectionInitSql()).isEqualTo(fromKeys.connectionInitSql());
        }
        Object notADataSource = new Object();
        assertThat(fromKeys.postProcessBeforeInitialization(notADataSource, "other")).isSameAs(notADataSource);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("15초 안에 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리는 중 인터럽트", e);
        }
    }
}
