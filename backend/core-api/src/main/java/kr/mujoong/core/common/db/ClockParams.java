package kr.mujoong.core.common.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 질의 인자 묶음과 시각 변환 (U1 3.5 Step 15 · R1 · R2 · NFR6.4 · J83 · J115).
 *
 * <p>모든 시각은 앱 Clock({@link MujoongClock})이 정하고 SQL에는 {@code :now}로 바인딩한다. DB 시각 함수는 쓰지 않는다
 * (NoSqlNowTest가 질의 문자열을 검사한다). 시각은 {@code timestamptz}이고 JDBC에는 UTC {@link OffsetDateTime}으로 넘긴다.
 * NULL이 될 수 있는 인자는 SQL 형을 함께 적어 PostgreSQL이 형을 추론하지 않아도 되게 한다.
 */
public final class ClockParams {

    /** SQL의 현재 시각 인자 이름({@code :now}). */
    public static final String NOW = "now";

    private ClockParams() {
    }

    /** 주입한 Clock을 한 번 읽어 {@code :now}를 묶은 인자 묶음. 한 업무 연산은 이 값 하나를 끝까지 쓴다. */
    public static MapSqlParameterSource from(MujoongClock clock) {
        return at(Objects.requireNonNull(clock, "clock").now());
    }

    /** {@code :now}를 묶은 새 인자 묶음. */
    public static MapSqlParameterSource at(Instant now) {
        return instant(new MapSqlParameterSource(), NOW, Objects.requireNonNull(now, "now"));
    }

    /** 시각 인자(NULL 허용)를 {@code timestamptz}로 더한다. */
    public static MapSqlParameterSource instant(MapSqlParameterSource source, String name, Instant value) {
        return source.addValue(name, timestamptz(value), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /** UUID 인자(NULL 허용)를 더한다. */
    public static MapSqlParameterSource uuid(MapSqlParameterSource source, String name, UUID value) {
        return source.addValue(name, value, Types.OTHER);
    }

    /** 정수 인자(NULL 허용)를 더한다. */
    public static MapSqlParameterSource integer(MapSqlParameterSource source, String name, Integer value) {
        return source.addValue(name, value, Types.INTEGER);
    }

    /** 문자열 인자(NULL 허용)를 더한다. */
    public static MapSqlParameterSource text(MapSqlParameterSource source, String name, String value) {
        return source.addValue(name, value, Types.VARCHAR);
    }

    /** UUID 목록을 {@code = any(:name)}용 배열로 더한다. */
    public static MapSqlParameterSource uuidArray(MapSqlParameterSource source, String name, Collection<UUID> values) {
        return source.addValue(name, Objects.requireNonNull(values, name).toArray(UUID[]::new));
    }

    /** 문자열 목록을 {@code = any(:name)}용 배열로 더한다. */
    public static MapSqlParameterSource textArray(MapSqlParameterSource source, String name, Collection<String> values) {
        return source.addValue(name, Objects.requireNonNull(values, name).toArray(String[]::new));
    }

    /** {@link Instant}를 UTC {@link OffsetDateTime}으로(NULL은 NULL). */
    public static OffsetDateTime timestamptz(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    /** {@code timestamptz} 열을 {@link Instant}로 읽는다(NULL은 NULL). */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** {@code uuid} 열을 읽는다(NULL은 NULL). */
    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** NULL일 수 있는 정수 열을 읽는다. */
    public static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
