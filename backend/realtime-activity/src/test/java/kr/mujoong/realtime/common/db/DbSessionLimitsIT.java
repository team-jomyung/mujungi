package kr.mujoong.realtime.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.common.db.DbRetry.Idempotency;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.session.SessionChange;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Realtime DB 연결 상한 · 재시도 통합 시험(U1 3.5 Step 18 · RD-22).
 *
 * <p>증명: 앱 풀의 모든 연결에 lock_timeout · statement_timeout이 걸린다 · 잠금을 오래 기다리면 55P03으로 끊기고 재시도 대상으로
 * 분류된다 · 멱등 전이는 잠금이 풀린 뒤 재시도로 성립한다 · 비멱등은 한 번에 실패를 올린다. 3.6 수정(BT-TC-10)에서 더한 셋: 문장 상한을
 * 넘은 문장은 57014로 끊기고 멱등이어도 다시 하지 않는다 · 잠금이 끝내 안 풀리면 재시도 상한(첫 시도 + 2) 뒤 마지막 55P03을 앞 오류와 함께
 * 올린다 · 바깥 트랜잭션 안에서는 재시도하지 않는다(되돌려진 트랜잭션은 이어 쓸 수 없다).
 */
@Tag("u1")
@Tag("db")
class DbSessionLimitsIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    /** 시험용으로 줄인 락 대기 상한(운영 2초). */
    private static final Duration SHORT_LOCK_TIMEOUT = Duration.ofMillis(200);
    /** 시험용으로 줄인 문장 상한(운영 10초). */
    private static final Duration SHORT_STATEMENT_TIMEOUT = Duration.ofMillis(200);
    /** 문장 취소(statement_timeout) SQLSTATE. */
    private static final String QUERY_CANCELED = "57014";

    private static RealtimeDb db;

    @BeforeAll
    static void openDatabase() {
        db = RealtimeDb.open();
    }

    @AfterAll
    static void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void everyPooledConnectionCarriesBothTimeouts() throws Exception {
        // 동시에 셋을 쥐어 풀이 실제 연결을 여러 개 열게 한다 — 초기화 문장은 첫 연결만이 아니라 새 연결마다 걸린다
        try (Connection first = db.dataSource().getConnection(); Connection second = db.dataSource().getConnection();
                Connection third = db.dataSource().getConnection()) {
            for (Connection connection : List.of(first, second, third)) {
                try (Statement statement = connection.createStatement()) {
                    assertThat(show(statement, "lock_timeout")).isEqualTo("2s");
                    assertThat(show(statement, "statement_timeout")).isEqualTo("10s");
                }
            }
        }
    }

    @Test
    void aLongLockWaitFailsWith55P03AndAnIdempotentTransitionSucceedsOnRetry() throws Exception {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("하늘", 8, List.of()), null, null);
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();

        try (HikariDataSource shortWait = RealtimeDb.limitedPool(db.postgres(), "realtime-short-lock-it",
                new DbSessionLimits(SHORT_LOCK_TIMEOUT, Duration.ofSeconds(10)))) {
            ActivitySessionRepository impatient = new ActivitySessionRepository(new NamedParameterJdbcTemplate(shortWait));
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> holder = pool.submit(() -> db.inTransaction(() -> {
                    sessions.findForUpdate(session.id()).orElseThrow();
                    locked.countDown();
                    await(release);
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

                // 비멱등: 락 대기 초과를 그대로 올린다
                DbRetry noJitter = new DbRetry(2, () -> 0L, duration -> { });
                assertThatThrownBy(() -> noJitter.execute(Idempotency.NON_IDEMPOTENT, () -> impatient.transition(
                        session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0, SessionChange.none())))
                        .isInstanceOf(DataAccessException.class)
                        .satisfies(error -> assertThat(SqlErrors.sqlState(error)).contains(SqlErrors.LOCK_NOT_AVAILABLE))
                        .satisfies(error -> assertThat(SqlErrors.isRetryable(error)).isTrue());

                // 멱등: 첫 시도가 55P03이면 기다리는 동안 잠금이 풀리고 다음 시도가 성립한다
                AtomicInteger attempts = new AtomicInteger();
                DbRetry releasing = new DbRetry(2, () -> 0L, duration -> release.countDown());
                Object changed = releasing.execute(Idempotency.IDEMPOTENT, () -> {
                    attempts.incrementAndGet();
                    if (attempts.get() > 1) {
                        waitFor(holder);
                    }
                    return impatient.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0,
                            SessionChange.none()).orElseThrow();
                });
                assertThat(changed).isNotNull();
                assertThat(attempts).hasValue(2);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
        assertThat(new ActivitySessionRepository(db.jdbc()).find(session.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.DEVICE_ACK);
    }

    @Test
    void aStatementOverTheStatementLimitIsCancelledWith57014AndNeverRetried() {
        try (HikariDataSource shortStatements = RealtimeDb.limitedPool(db.postgres(), "realtime-short-statement-it",
                new DbSessionLimits(Duration.ofSeconds(2), SHORT_STATEMENT_TIMEOUT))) {
            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(shortStatements);
            assertThat(jdbc.queryForObject("show statement_timeout", new MapSqlParameterSource(), String.class))
                    .isEqualTo("200ms");
            AtomicInteger attempts = new AtomicInteger();
            DbRetry retry = new DbRetry(2, () -> 0L, duration -> { });

            // 문장 상한 초과(57014)는 RD-22 재시도 대상이 아니다: 멱등이어도 한 번만 시도하고 그대로 올린다
            assertThatThrownBy(() -> retry.execute(Idempotency.IDEMPOTENT, () -> {
                attempts.incrementAndGet();
                return jdbc.queryForObject("select pg_sleep(5)", new MapSqlParameterSource(), Object.class);
            })).isInstanceOf(DataAccessException.class)
                    .satisfies(error -> assertThat(SqlErrors.sqlState(error)).contains(QUERY_CANCELED))
                    .satisfies(error -> assertThat(SqlErrors.isRetryable(error)).isFalse());
            assertThat(attempts).hasValue(1);
            // 끊긴 연결은 풀로 돌아가 다음 문장이 정상으로 돈다
            assertThat(jdbc.queryForObject("select 1", new MapSqlParameterSource(), Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void aLockThatIsNeverReleasedExhaustsTheRetriesAndKeepsEveryAttemptError() throws Exception {
        NewActivitySession session = insertedSession();
        try (HikariDataSource shortWait = shortLockPool("realtime-exhaust-it")) {
            ActivitySessionRepository impatient = new ActivitySessionRepository(new NamedParameterJdbcTemplate(shortWait));
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger pauses = new AtomicInteger();
            DbRetry retry = new DbRetry(2, () -> 0L, duration -> pauses.incrementAndGet());

            whileLocked(session, () -> assertThatThrownBy(() -> retry.execute(Idempotency.IDEMPOTENT, () -> {
                attempts.incrementAndGet();
                return impatient.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0,
                        SessionChange.none());
            })).isInstanceOf(DataAccessException.class)
                    .satisfies(error -> assertThat(SqlErrors.sqlState(error)).contains(SqlErrors.LOCK_NOT_AVAILABLE))
                    .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1)
                            .allSatisfy(previous -> assertThat(SqlErrors.isRetryable(previous)).isTrue())));

            // 첫 시도 + 재시도 2회, 시도 사이 대기 2회. 앞 시도의 오류는 마지막 오류의 suppressed 사슬에 남는다
            assertThat(attempts).hasValue(3);
            assertThat(pauses).hasValue(2);
        }
        assertThat(new ActivitySessionRepository(db.jdbc()).find(session.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.PREPARING);
    }

    @Test
    void insideAnOuterTransactionTheRetryHandsTheFirstLockFailureToThatBoundary() throws Exception {
        NewActivitySession session = insertedSession();
        try (HikariDataSource shortWait = shortLockPool("realtime-outer-tx-it")) {
            ActivitySessionRepository impatient = new ActivitySessionRepository(new NamedParameterJdbcTemplate(shortWait));
            TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(shortWait));
            AtomicInteger attempts = new AtomicInteger();
            DbRetry retry = new DbRetry(2, () -> 0L, duration -> { });

            whileLocked(session, () -> assertThatThrownBy(() -> outer.executeWithoutResult(status ->
                    retry.execute(Idempotency.IDEMPOTENT, () -> {
                        attempts.incrementAndGet();
                        return impatient.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0,
                                SessionChange.none());
                    }))).isInstanceOf(DataAccessException.class)
                    .satisfies(error -> assertThat(SqlErrors.sqlState(error)).contains(SqlErrors.LOCK_NOT_AVAILABLE)));

            // 되돌려진 바깥 트랜잭션 안에서 다시 하면 25P02가 되므로 첫 실패를 바깥 경계에 넘긴다
            assertThat(attempts).hasValue(1);
        }
        assertThat(new ActivitySessionRepository(db.jdbc()).find(session.id()).orElseThrow().status())
                .isEqualTo(SessionStatus.PREPARING);
    }

    /** PREPARING 세션 행 하나를 커밋한다. */
    private static NewActivitySession insertedSession() {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("하늘", 8, List.of()), null, null);
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        return session;
    }

    /** 락 대기 상한을 줄인 앱 역할 풀(문장 상한은 운영 값). */
    private static HikariDataSource shortLockPool(String name) {
        return RealtimeDb.limitedPool(db.postgres(), name, new DbSessionLimits(SHORT_LOCK_TIMEOUT, Duration.ofSeconds(10)));
    }

    /** 다른 트랜잭션이 세션 행을 {@code FOR UPDATE}로 쥔 동안 {@code body}를 돌리고, 끝나면 잠금을 풀고 그 트랜잭션을 마친다. */
    private static void whileLocked(NewActivitySession session, Runnable body) throws Exception {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> db.inTransaction(() -> {
                sessions.findForUpdate(session.id()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            body.run();
            release.countDown();
            waitFor(holder);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static String show(Statement statement, String setting) throws Exception {
        try (ResultSet rs = statement.executeQuery("show " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 잠금을 쥔 트랜잭션이 커밋을 마칠 때까지 기다린다. */
    private static void waitFor(Future<?> holder) {
        try {
            holder.get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("잠금 보유 트랜잭션이 끝나지 않았다", error);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시험 래치 대기 시간 초과");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
