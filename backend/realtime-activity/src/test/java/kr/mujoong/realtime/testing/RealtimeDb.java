package kr.mujoong.realtime.testing;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import kr.mujoong.realtime.common.db.DbSessionLimits;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Realtime 데이터 접근 시험(Step 18)의 DB 묶음(시험 소스 전용). 공용 {@link MujoongPostgres}(실제 init 스크립트 + 두 마이그레이션
 * 위치)에 앱 역할 {@code realtime_app}으로 붙는 Hikari 풀을 만들고, 운영과 같은 연결 상한({@link DbSessionLimits}: lock_timeout 2초
 * · statement_timeout 10초, RD-22)을 건다. 트랜잭션은 운영처럼 Spring 트랜잭션 관리자로 열어 저장소의 MANDATORY 확인이 실제로
 * 동작한다.
 *
 * <p>시험 클래스는 JVM 안에서 차례로 돌므로 각 시험 앞뒤에 {@link #wipe()}로 realtime 표 열 개(V1 일곱 + U3 V2 셋)를 비운다(폴러 · 전체 조회 시험이
 * 다른 시험 행을 보지 않게).
 */
public final class RealtimeDb implements AutoCloseable {

    /** 운영 기본값과 같은 락 대기 상한(application.yml {@code mujoong.limits.db-lock-timeout}). */
    public static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
    /** 운영 기본값과 같은 문장 상한(application.yml {@code mujoong.limits.db-statement-timeout}). */
    public static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(10);

    private static final int POOL_SIZE = 8;

    /** 외래 키 순서(자식 → 부모)로 비울 표. */
    private static final List<String> TABLES = List.of(
            // U4b V3: 결과 스냅숏은 세션 행을 가리키므로 먼저 비운다
            "realtime.realtime_result_input_snapshot",
            "realtime.realtime_transcript",
            "realtime.realtime_session_event",
            "realtime.realtime_session_stop_expression",
            "realtime.realtime_audio_recording",
            "realtime.realtime_session_gap",
            "realtime.realtime_activity_session",
            "realtime.realtime_device_connection",
            "realtime.realtime_device_disconnect_event",
            "realtime.realtime_outbox_event",
            "realtime.realtime_inbox_event");

    private final MujoongPostgres postgres;
    private final HikariDataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    private RealtimeDb(MujoongPostgres postgres) {
        this.postgres = postgres;
        this.dataSource = limitedPool(postgres, "realtime-repository-it",
                new DbSessionLimits(LOCK_TIMEOUT, STATEMENT_TIMEOUT));
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * 운영과 같은 방식으로 상한을 건 {@code realtime_app} 풀을 만든다: 설정만 한 풀(아직 시작 전)에 {@link DbSessionLimits}를 걸고 첫
     * 연결에서 시작한다(Boot DataSource와 빈 후처리기 순서와 같다). 호출자가 닫는다.
     */
    public static HikariDataSource limitedPool(MujoongPostgres postgres, String poolName, DbSessionLimits limits) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName(poolName);
        pool.setJdbcUrl(postgres.jdbcUrl());
        pool.setUsername(postgres.username(Role.REALTIME_APP));
        pool.setPassword(postgres.password(Role.REALTIME_APP));
        pool.setMaximumPoolSize(POOL_SIZE);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        limits.apply(pool);
        return pool;
    }

    /** 공용 DB(없으면 시험을 건너뛴다)에 붙는다. */
    public static RealtimeDb open() {
        return new RealtimeDb(MujoongPostgres.sharedOrAbort());
    }

    public MujoongPostgres postgres() {
        return postgres;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    public TransactionTemplate transactions() {
        return transactions;
    }

    /** 한 트랜잭션에서 실행하고 커밋한다(예외면 되돌린다). */
    public <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    /** 한 트랜잭션에서 실행하고 커밋한다(예외면 되돌린다). */
    public void inTransaction(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    /** 한 트랜잭션에서 실행한 뒤 언제나 되돌린다. */
    public void rolledBack(Runnable work) {
        transactions.executeWithoutResult(status -> {
            work.run();
            status.setRollbackOnly();
        });
    }

    /** realtime 표 일곱 개를 비운다(앱 역할의 DELETE 권한, TRUNCATE는 앱에 없다 SD-13). */
    public void wipe() {
        for (String table : TABLES) {
            jdbc.update("delete from " + table, new MapSqlParameterSource());
        }
    }

    /** 한 값 질의(검증용). */
    public <T> T single(String sql, MapSqlParameterSource params, Class<T> type) {
        return jdbc.queryForObject(sql, params, type);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
