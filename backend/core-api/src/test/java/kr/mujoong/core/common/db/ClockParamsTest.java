package kr.mujoong.core.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * {@link ClockParams} 단위 시험(U1 3.5 Step 17 · R1 · R2 · NFR6.4 · J115). {@code :now}는 주입한 Clock에서 한 번 읽고, 시각은 UTC
 * {@code timestamptz}로, NULL 인자는 SQL 형을 함께 넘긴다. 열 읽기는 NULL과 실제 0을 가르고 어느 오프셋 표기든 같은 순간으로 읽는다(3.6
 * 수정 분량, JDBC {@link ResultSet} 대역 — J344).
 */
@Tag("skeleton")
class ClockParamsTest {

    private static final Instant T = Instant.parse("2001-03-01T09:30:15.123Z");

    @Test
    void nowIsReadOnceFromTheInjectedClockAsUtc() {
        MutableClock clock = new MutableClock(T);
        MapSqlParameterSource params = ClockParams.from(clock);
        clock.advance(Duration.ofSeconds(5));

        assertThat(params.getValue(ClockParams.NOW)).isEqualTo(OffsetDateTime.of(2001, 3, 1, 9, 30, 15, 123_000_000,
                ZoneOffset.UTC));
        assertThat(params.getSqlType(ClockParams.NOW)).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(ClockParams.at(T).getValue(ClockParams.NOW)).isEqualTo(ClockParams.timestamptz(T));
    }

    @Test
    void nullableArgumentsCarryTheirSqlTypes() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.instant(params, "at", null);
        ClockParams.uuid(params, "id", null);
        ClockParams.integer(params, "n", null);
        ClockParams.text(params, "s", null);

        assertThat(params.getValue("at")).isNull();
        assertThat(params.getSqlType("at")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(params.getSqlType("id")).isEqualTo(Types.OTHER);
        assertThat(params.getSqlType("n")).isEqualTo(Types.INTEGER);
        assertThat(params.getSqlType("s")).isEqualTo(Types.VARCHAR);
        assertThat(ClockParams.timestamptz(null)).isNull();
    }

    @Test
    void collectionsBecomeArraysForAnyComparisons() {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuidArray(params, "ids", List.of(id));
        ClockParams.textArray(params, "types", List.of("DeviceReleased"));
        assertThat((UUID[]) params.getValue("ids")).containsExactly(id);
        assertThat((String[]) params.getValue("types")).containsExactly("DeviceReleased");
        assertThatThrownBy(() -> ClockParams.uuidArray(params, "x", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void missingClockOrNowIsRejected() {
        assertThatThrownBy(() -> ClockParams.from(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ClockParams.at(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void columnReadersKeepRealValuesIncludingZeroAndReadAnyOffsetAsTheSameInstant() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        when(rs.getObject("at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.of(2001, 3, 1, 18, 30, 15, 123_000_000, ZoneOffset.ofHours(9)));
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getInt("n")).thenReturn(0);
        when(rs.wasNull()).thenReturn(false);

        assertThat(ClockParams.instant(rs, "at")).as("+09:00 표기도 같은 순간").isEqualTo(T);
        assertThat(ClockParams.uuid(rs, "id")).isEqualTo(id);
        assertThat(ClockParams.nullableInt(rs, "n")).as("NULL이 아닌 0은 0").isZero();
    }

    @Test
    void nullColumnsReadAsNullAndTheNullFlagIsCheckedAfterTheIntRead() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("at", OffsetDateTime.class)).thenReturn(null);
        when(rs.getObject("id", UUID.class)).thenReturn(null);
        // 드라이버는 NULL 정수를 0으로 돌려주고 wasNull로만 알린다
        when(rs.getInt("n")).thenReturn(0);
        when(rs.wasNull()).thenReturn(true);

        assertThat(ClockParams.instant(rs, "at")).isNull();
        assertThat(ClockParams.uuid(rs, "id")).isNull();
        assertThat(ClockParams.nullableInt(rs, "n")).as("0 + NULL 표지는 null").isNull();
        InOrder order = inOrder(rs);
        order.verify(rs).getInt("n");
        order.verify(rs).wasNull();
    }
}
