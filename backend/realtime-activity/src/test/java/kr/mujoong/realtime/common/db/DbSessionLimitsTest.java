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
@Tag("skeleton")
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
    void oneMillisecondIsTheSmallestLimitAndANegativeLimitIsRejected() {
        // 경계: 1ms는 받고(문장 '1ms'), 1ms − 1ns는 밀리초로 0이 되므로 거부한다
        DbSessionLimits smallest = new DbSessionLimits(Duration.ofMillis(1), Duration.ofMillis(1));
        assertThat(smallest.connectionInitSql()).isEqualTo("select set_config('lock_timeout', '1ms', false),"
                + " set_config('statement_timeout', '1ms', false)");

        assertThatThrownBy(() -> new DbSessionLimits(Duration.ofMillis(1).minusNanos(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(DbSessionLimits.LOCK_TIMEOUT_KEY);
        assertThatThrownBy(() -> new DbSessionLimits(Duration.ofSeconds(1), Duration.ofSeconds(-10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(DbSessionLimits.STATEMENT_TIMEOUT_KEY);
    }

    @Test
    void theKeysAcceptSpringDurationFormatsButAnUnreadableValueFailsStartup() {
        MockEnvironment iso = new MockEnvironment()
                .withProperty(DbSessionLimits.LOCK_TIMEOUT_KEY, "PT1.5S")
                .withProperty(DbSessionLimits.STATEMENT_TIMEOUT_KEY, "7000ms");
        DbSessionLimits limits = new DbSessionLimits(iso);
        assertThat(limits.lockTimeout()).isEqualTo(Duration.ofMillis(1500));
        assertThat(limits.statementTimeout()).isEqualTo(Duration.ofSeconds(7));
        // 단위 없는 숫자는 Spring 규칙대로 밀리초다
        MockEnvironment bare = new MockEnvironment()
                .withProperty(DbSessionLimits.LOCK_TIMEOUT_KEY, "250")
                .withProperty(DbSessionLimits.STATEMENT_TIMEOUT_KEY, "10s");
        assertThat(new DbSessionLimits(bare).lockTimeout()).isEqualTo(Duration.ofMillis(250));

        MockEnvironment unreadable = new MockEnvironment()
                .withProperty(DbSessionLimits.LOCK_TIMEOUT_KEY, "two seconds")
                .withProperty(DbSessionLimits.STATEMENT_TIMEOUT_KEY, "10s");
        assertThatThrownBy(() -> new DbSessionLimits(unreadable))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class)
                .hasMessageContaining(DbSessionLimits.LOCK_TIMEOUT_KEY);
    }

    @Test
    void aBlankExistingInitStatementCountsAsAbsentAndIsReplaced() {
        DbSessionLimits limits = new DbSessionLimits(Duration.ofSeconds(2), Duration.ofSeconds(10));
        try (HikariDataSource blank = new HikariDataSource()) {
            blank.setConnectionInitSql("   ");

            limits.apply(blank);

            assertThat(blank.getConnectionInitSql()).isEqualTo(limits.connectionInitSql());
        }
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
