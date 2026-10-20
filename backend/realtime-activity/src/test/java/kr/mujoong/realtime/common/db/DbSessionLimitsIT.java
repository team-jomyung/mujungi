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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
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

/**
 * Realtime DB 연결 상한 · 재시도 통합 시험(U1 3.5 Step 18 · RD-22).
 *
 * <p>증명: 앱 풀의 모든 연결에 lock_timeout · statement_timeout이 걸린다 · 잠금을 오래 기다리면 55P03으로 끊기고 재시도 대상으로
 * 분류된다 · 멱등 전이는 잠금이 풀린 뒤 재시도로 성립한다 · 비멱등은 한 번에 실패를 올린다. 3.6 수정(BT-TC-10)에서 더한 것: 문장 상한을
 * 넘은 문장은 57014로 끊기고 멱등이어도 다시 하지 않는다. 3.6 수정 리뷰 반영(J366): 재시도 판정만 되풀이하던 두 시험(DbRetryTest와 같은
 * 판정)을 실제 DB에서만 보이는 동작으로 바꿨다 — 실제 교착은 운영 락 대기 상한보다 먼저 40P01로 끊기고 진 쪽이 재시도로 성립한다 · 상한 안에
 * 풀린 잠금은 기다렸다 첫 시도에 성립한다.
 */
@Tag("skeleton")
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

    /**
     * 실제 교착(3.6 수정 J366): 두 트랜잭션이 두 세션 행을 엇갈린 순서로 잠그면 PostgreSQL 교착 감지(deadlock_timeout, 기본 1초)가 한쪽을
     * 끊는다. 운영 락 대기 상한(2초)보다 감지가 먼저라 55P03이 아니라 40P01이고, 드라이버 예외가 Spring 번역을 거쳐도 SQLSTATE가 남아
     * 재시도 대상이다. 시도마다 새 트랜잭션을 여는 멱등 재시도(DbRetry 사용 규칙)로 진 쪽도 이긴 쪽이 커밋한 뒤 성립한다.
     */
    @Test
    void aRealDeadlockIsReportedAs40P01BeforeTheLockLimitAndTheLoserSucceedsOnItsRetry() throws Exception {
        Duration deadlockTimeout = Duration.ofMillis(db.single(
                "select setting::bigint from pg_settings where name = 'deadlock_timeout'", new MapSqlParameterSource(),
                Long.class));
        assertThat(deadlockTimeout).as("교착 감지가 운영 락 대기 상한보다 먼저다(전제)").isLessThan(RealtimeDb.LOCK_TIMEOUT);
        NewActivitySession first = insertedSession();
        NewActivitySession second = insertedSession();
        CyclicBarrier bothHoldTheirFirstLock = new CyclicBarrier(2);
        List<RuntimeException> failures = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        DbRetry retry = new DbRetry(2, () -> 0L, duration -> { });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> forward = pool.submit(() -> lockBothThenTransition(retry, first.id(), second.id(),
                    bothHoldTheirFirstLock, failures, attempts));
            Future<Boolean> backward = pool.submit(() -> lockBothThenTransition(retry, second.id(), first.id(),
                    bothHoldTheirFirstLock, failures, attempts));

            assertThat(forward.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(backward.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).as("교착은 한쪽만 끊는다").singleElement().satisfies(error -> {
            assertThat(error).isInstanceOf(DataAccessException.class);
            assertThat(SqlErrors.sqlState(error)).contains(SqlErrors.DEADLOCK_DETECTED);
            assertThat(SqlErrors.isRetryable(error)).isTrue();
        });
        assertThat(attempts).as("두 쪽의 첫 시도 + 진 쪽 재시도 1회").hasValue(3);
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        assertThat(sessions.find(first.id()).orElseThrow().status()).isEqualTo(SessionStatus.DEVICE_ACK);
        assertThat(sessions.find(second.id()).orElseThrow().status()).isEqualTo(SessionStatus.DEVICE_ACK);
    }

    /**
     * 경계(3.6 수정 J366): 락 대기 상한은 '기다리지 않음'이 아니라 '이만큼까지 기다림'이다. 기다리는 문장이 실제로 잠금 대기(pg_stat_activity
     * wait_event_type = Lock)에 든 것을 본 뒤 상한(2초) 안에 잠금을 풀면 첫 시도가 55P03 없이 성립하고 재시도하지 않는다.
     */
    @Test
    void aLockReleasedWithinTheLimitIsWaitedForAndTheFirstAttemptSucceedsWithoutARetry() throws Exception {
        NewActivitySession session = insertedSession();
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        DbRetry retry = new DbRetry(2, () -> 0L, duration -> {
            throw new IllegalStateException("상한 안에 풀린 잠금은 재시도하지 않는다");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> db.inTransaction(() -> {
                sessions.findForUpdate(session.id()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> waiter = pool.submit(() -> retry.execute(Idempotency.IDEMPOTENT, () -> db.inTransaction(() -> {
                attempts.incrementAndGet();
                waiterPid.set(backendPid());
                return sessions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0,
                        SessionChange.none()).isPresent();
            })));

            awaitLockWait(waiterPid);
            assertThat(waiter).as("잠금이 풀리기 전에는 끝나지 않는다").isNotDone();
            release.countDown();
            waitFor(holder);
            assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(attempts).hasValue(1);
        assertThat(sessions.find(session.id()).orElseThrow().status()).isEqualTo(SessionStatus.DEVICE_ACK);
    }

    /**
     * 한 시도 = 한 트랜잭션: {@code lockedFirst} 행을 잠그고(첫 시도면 상대도 첫 잠금을 쥘 때까지 기다린 뒤) {@code lockedSecond} 행을 잠근 다음
     * {@code lockedFirst}를 DEVICE_ACK으로 전이한다. 시도의 실패는 {@code failures}에 남기고 그대로 올린다(재시도 판단은 DbRetry).
     */
    private static boolean lockBothThenTransition(DbRetry retry, UUID lockedFirst, UUID lockedSecond,
            CyclicBarrier bothHoldTheirFirstLock, List<RuntimeException> failures, AtomicInteger attempts) {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        AtomicInteger mine = new AtomicInteger();
        return retry.execute(Idempotency.IDEMPOTENT, () -> {
            attempts.incrementAndGet();
            boolean firstAttempt = mine.incrementAndGet() == 1;
            try {
                return db.inTransaction(() -> {
                    sessions.findForUpdate(lockedFirst).orElseThrow();
                    if (firstAttempt) {
                        await(bothHoldTheirFirstLock);
                    }
                    sessions.findForUpdate(lockedSecond).orElseThrow();
                    return sessions.transition(lockedFirst, SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, T0,
                            SessionChange.none()).isPresent();
                });
            } catch (RuntimeException failure) {
                failures.add(failure);
                throw failure;
            }
        });
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

    /** 지금 연결(트랜잭션에 묶인 연결)의 백엔드 pid. */
    private static int backendPid() {
        Integer pid = db.single("select pg_backend_pid()", new MapSqlParameterSource(), Integer.class);
        return pid == null ? -1 : pid;
    }

    /** {@code pid}가 정해지고 그 백엔드가 잠금 대기에 들어갈 때까지 기다린다(상한 10초, 짐작한 시간을 자지 않는다). */
    private static void awaitLockWait(AtomicInteger pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() > 0) {
                List<String> waits = db.jdbc().queryForList(
                        "select coalesce(wait_event_type, '') from pg_stat_activity where pid = :pid",
                        new MapSqlParameterSource("pid", pid.get()), String.class);
                if (!waits.isEmpty() && "Lock".equals(waits.get(0))) {
                    return;
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        throw new AssertionError("백엔드 pid " + pid.get() + "가 10초 안에 잠금 대기에 들어가지 않았다");
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("두 트랜잭션이 첫 잠금을 쥐지 못했다", failure);
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
