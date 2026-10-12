package kr.mujoong.realtime.gateway;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_device_disconnect_event} 데이터 접근 — 기기 끊김 사건 (U3 L1 · design.md 3절 · U3-R1 · U3-J2 · AC2.1.4 ·
 * AC2.1.10).
 *
 * <p>끊김 한 건 = HELLO_OK를 받은 연결이 서버 아닌 쪽 사유로 닫힘({@link Kind#CLOSED}, 닫기 코드 기록) 또는 40초 무음으로 DISCONNECTED가
 * 됨({@link Kind#SILENT}). 「끄기」(1000)와 4001 대체는 적지 않는다(U3-J2). 불안정 판정은 {@code mujoong.timing.device.unstable-disconnect-window}
 * (5분) 창 안의 건수를 센다. 시각은 호출자가 Clock에서 읽은 값만 쓴다(R2).
 */
@Repository
public class DeviceDisconnectRepository {

    /** 끊김 종류(V2 CHECK). */
    public enum Kind {
        CLOSED,
        SILENT
    }

    /** 한 행. */
    public record DisconnectEvent(UUID id, UUID deviceId, UUID connectionId, Kind kind, Integer closeCode,
            Instant occurredAt) {

        public DisconnectEvent {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (kind == Kind.SILENT && closeCode != null) {
                throw new IllegalArgumentException("무음 끊김에는 닫기 코드가 없다");
            }
        }
    }

    private static final String COLUMNS = "id, device_id, connection_id, kind, close_code, occurred_at";

    private static final String INSERT = "insert into realtime.realtime_device_disconnect_event"
            + " (id, device_id, connection_id, kind, close_code, occurred_at, created_at)"
            + " values (:id, :deviceId, :connectionId, :kind, :closeCode, :occurredAt, :now)";

    private static final String COUNT_SINCE = "select count(*) from realtime.realtime_device_disconnect_event"
            + " where device_id = :deviceId and occurred_at > :cutoff";

    private static final String SELECT_BY_DEVICE = "select " + COLUMNS + " from realtime.realtime_device_disconnect_event"
            + " where device_id = :deviceId order by occurred_at, id";

    private static final String DELETE_BY_DEVICE = "delete from realtime.realtime_device_disconnect_event"
            + " where device_id = :deviceId";

    private static final String DELETE_BY_GUARDIAN = "delete from realtime.realtime_device_disconnect_event"
            + " where device_id in (select device_id from realtime.realtime_device_connection where guardian_id = :guardianId"
            + " union select device_id from realtime.realtime_activity_session where guardian_id = :guardianId)";

    private static final String DELETE_BEFORE = "delete from realtime.realtime_device_disconnect_event"
            + " where occurred_at <= :cutoff";

    private final NamedParameterJdbcTemplate jdbc;

    public DeviceDisconnectRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 끊김 한 건을 적는다.
     *
     * @param now created_at(Clock)
     */
    public void record(DisconnectEvent event, Instant now) {
        Objects.requireNonNull(event, "event");
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", event.id());
        ClockParams.uuid(params, "deviceId", event.deviceId());
        ClockParams.uuid(params, "connectionId", event.connectionId());
        ClockParams.text(params, "kind", event.kind().name());
        ClockParams.integer(params, "closeCode", event.closeCode());
        ClockParams.instant(params, "occurredAt", event.occurredAt());
        jdbc.update(INSERT, params);
    }

    /**
     * 창 안 끊김 횟수(U3-R1): {@code occurred_at > now − window}인 건수. 창 경계의 건은 세지 않는다(5분 정각에 난 끊김은 창 밖).
     */
    public int countRecent(UUID deviceId, Instant now, Duration window) {
        Objects.requireNonNull(now, "now");
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window는 0보다 커야 한다");
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        ClockParams.instant(params, "cutoff", now.minus(window));
        Long count = jdbc.queryForObject(COUNT_SINCE, params, Long.class);
        return count == null ? 0 : (int) Math.min(Integer.MAX_VALUE, count);
    }

    /** 기기의 끊김 사건 전부(시각 순, 시험 · 진단). */
    public List<DisconnectEvent> findByDevice(UUID deviceId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        return jdbc.query(SELECT_BY_DEVICE, params, ROW_MAPPER);
    }

    /**
     * 보호자 기기들(연결 원장 · 세션에 남은 기기)의 끊김 기록을 지운다(삭제 참가자, U3-J16). 연결 원장 행을 지우기 전에 부른다. 지운 행 수.
     */
    public int deleteByGuardian(UUID guardianId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        return jdbc.update(DELETE_BY_GUARDIAN, params);
    }

    /** 기기의 끊김 기록을 지운다(삭제 참가자, U3-J16). 지운 행 수. */
    public int deleteByDevice(UUID deviceId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        return jdbc.update(DELETE_BY_DEVICE, params);
    }

    /** 창보다 오래된 사건을 지운다(정리 작업). 지운 행 수. */
    public int deleteOlderThan(Instant cutoff) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.instant(params, "cutoff", Objects.requireNonNull(cutoff, "cutoff"));
        return jdbc.update(DELETE_BEFORE, params);
    }

    private static final RowMapper<DisconnectEvent> ROW_MAPPER = DeviceDisconnectRepository::mapRow;

    private static DisconnectEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DisconnectEvent(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "device_id"),
                ClockParams.uuid(rs, "connection_id"),
                Kind.valueOf(rs.getString("kind")),
                ClockParams.nullableInt(rs, "close_code"),
                ClockParams.instant(rs, "occurred_at"));
    }
}
