package kr.mujoong.realtime.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Realtime DB 연결 상한 단위 시험(U1 3.5 Step 18 · RD-22). DB 없이 Hikari 설정에 걸리는 초기화 문장을 본다. 실제 연결의 값과 락 대기
 * 초과 → 재시도는 {@code DbSessionLimitsIT}가 본다.
 */
@Tag("u1")
class DbSessionLimitsTest {

    @Test
    void theLimitsComeFromTheTwoSettingKeysAndBecomeOneInitStatement() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DbSessionLimits.LOCK_TIMEOUT_KEY, "2s")
                .withProperty(DbSessionLimits.STATEMENT_TIMEOUT_KEY, "10s");

        DbSessionLimits limits = new DbSessionLimits(environment);

        assertThat(limits.lockTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(limits.statementTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(limits.connectionInitSql()).isEqualTo("select set_config('lock_timeout', '2000ms', false),"
                + " set_config('statement_timeout', '10000ms', false)");
    }

    @Test
    void aMissingOrNonPositiveLimitFailsStartup() {
        MockEnvironment missing = new MockEnvironment().withProperty(DbSessionLimits.LOCK_TIMEOUT_KEY, "2s");

        assertThatThrownBy(() -> new DbSessionLimits(missing)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DbSessionLimits.STATEMENT_TIMEOUT_KEY);
        assertThatThrownBy(() -> new DbSessionLimits(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DbSessionLimits(Duration.ofSeconds(1), Duration.ofNanos(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DbSessionLimits(null, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAppDataSourceGetsTheStatementButAForeignInitStatementIsNotOverwritten() {
        DbSessionLimits limits = new DbSessionLimits(Duration.ofSeconds(2), Duration.ofSeconds(10));
        try (HikariDataSource app = new HikariDataSource(); HikariDataSource foreign = new HikariDataSource()) {
            Object processed = limits.postProcessBeforeInitialization(app, "dataSource");
            assertThat(processed).isSameAs(app);
            assertThat(app.getConnectionInitSql()).isEqualTo(limits.connectionInitSql());
            // 같은 문장이면 다시 걸어도 된다
            limits.apply(app);

            foreign.setConnectionInitSql("select 1");
            assertThatThrownBy(() -> limits.apply(foreign)).isInstanceOf(IllegalStateException.class);
            assertThat(foreign.getConnectionInitSql()).isEqualTo("select 1");
        }
        // DataSource가 아닌 빈은 그대로 지나간다
        Object other = new Object();
        assertThat(limits.postProcessBeforeInitialization(other, "other")).isSameAs(other);
        assertThatThrownBy(() -> limits.apply(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void clockParamsBindUtcTimesAndTypedNulls() {
        Instant now = Instant.parse("2026-10-15T09:00:00Z");
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.instant(params, "none", null);
        ClockParams.uuid(params, "noId", null);
        ClockParams.integer(params, "count", null);
        ClockParams.text(params, "name", null);

        assertThat(params.getValue(ClockParams.NOW)).isEqualTo(ClockParams.timestamptz(now));
        assertThat(ClockParams.timestamptz(now).getOffset().getTotalSeconds()).isZero();
        assertThat(ClockParams.timestamptz(null)).isNull();
        assertThat(params.getValue("none")).isNull();
        assertThat(params.getSqlType("none")).isEqualTo(java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(params.getSqlType("noId")).isEqualTo(java.sql.Types.OTHER);
        assertThat(params.getSqlType("count")).isEqualTo(java.sql.Types.INTEGER);
        assertThat(params.getSqlType("name")).isEqualTo(java.sql.Types.VARCHAR);
        assertThatThrownBy(() -> ClockParams.at(null)).isInstanceOf(NullPointerException.class);
        // 시험 묶음이 쓰는 운영 기본값과 같은 상한
        assertThat(RealtimeDb.LOCK_TIMEOUT).isEqualTo(Duration.ofSeconds(2));
    }
}
