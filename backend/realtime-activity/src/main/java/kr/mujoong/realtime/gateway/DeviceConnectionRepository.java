package kr.mujoong.realtime.gateway;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_device_connection} 데이터 접근 — 기기 연결 상태 원장 (U1 3.5 Step 16 · BR3.7 · BR3.8 · BR4.8 ·
 * BR6.13 · J55 · J125 · SQ-12 ~ SQ-14).
 *
 * <ul>
 *   <li>교체(BR3.7): HELLO_OK 시점에 한 트랜잭션에서 행을 {@code FOR UPDATE}로 잠그고(첫 접속이면 먼저 만든다) 새 연결 값으로
 *       덮는다. 잠금을 나중에 얻은 연결이 이기며 기대값 비교는 없다({@link #handover}).
 *   <li>교체 이후의 기록(HEARTBEAT 시각, DISCONNECTED · UNRESPONSIVE · OFFLINE, HEARTBEAT에 따른 CONNECTED 복귀)은
 *       {@code WHERE device_id = :d AND connection_id = :cid} 조건부이고 0행이면 기록 없이 버린다 — 대체된 옛 연결의 닫힘이 새 연결
 *       상태를 덮지 않는다. 상태가 바뀐 경우에만 바뀐 행을 돌려주므로 호출자는 그때만 DeviceStatusChanged를 같은 트랜잭션에 쓴다.
 *   <li>예외는 기기 해제(BR3.8) 하나로 {@code device_id}만 조건이고 connection_id를 비운다.
 * </ul>
 * 시각은 호출자가 Clock에서 읽은 값만 쓴다(R2). last_seen_at은 HEARTBEAT를 받을 때(와 교체 때) 쓴다(J125).
 */
@Repository
public class DeviceConnectionRepository {

    /** 40초 무음이면 DISCONNECTED가 되는 상태(BR4.8 ②). OFFLINE · DISCONNECTED는 대상이 아니다. */
    public static final Set<ConnectionState> DISCONNECT_CANDIDATES =
            EnumSet.of(ConnectionState.CONNECTED, ConnectionState.UNSTABLE, ConnectionState.UNRESPONSIVE);

    /** UNRESPONSIVE로 바꿀 수 있는 상태(BR4.8 ③ 명령 시한, 이미 끊김 · 꺼짐이면 바꾸지 않는다). */
    public static final Set<ConnectionState> UNRESPONSIVE_CANDIDATES =
            EnumSet.of(ConnectionState.CONNECTED, ConnectionState.UNSTABLE);

    /** 같은 연결의 다음 HEARTBEAT가 CONNECTED로 되돌리는 상태(J55 · BR4.8). */
    public static final Set<ConnectionState> RECOVERABLE =
            EnumSet.of(ConnectionState.UNRESPONSIVE, ConnectionState.DISCONNECTED);

    /** 정상 닫기(1000 · 1001)가 OFFLINE으로 바꾸는 상태(OFFLINE 말고 전부). */
    public static final Set<ConnectionState> CLOSABLE = EnumSet.of(ConnectionState.CONNECTED, ConnectionState.UNSTABLE,
            ConnectionState.DISCONNECTED, ConnectionState.UNRESPONSIVE);

    /** 재시작 때 DISCONNECTED로 되돌리는 상태(BR6.13 S3b). */
    public static final Set<ConnectionState> LIVE_AT_RESTART = EnumSet.of(ConnectionState.CONNECTED, ConnectionState.UNSTABLE);

    private static final String COLUMNS = "device_id, guardian_id, child_id, connection_state, connection_id,"
            + " connection_since, last_seen_at, last_rtt_ms, recent_disconnects, updated_at";

    private static final String SELECT_BY_DEVICE = "select " + COLUMNS + " from realtime.realtime_device_connection"
            + " where device_id = :deviceId";

    private static final String SELECT_ALL = "select " + COLUMNS + " from realtime.realtime_device_connection"
            + " order by device_id";

    private static final String COUNT_BY_STATE = "select connection_state, count(*) as devices"
            + " from realtime.realtime_device_connection group by connection_state";

    // 첫 접속이면 OFFLINE 행을 먼저 만든다. 동시에 만들려는 다른 연결은 유일 색인에서 기다렸다가 아무것도 하지 않는다
    private static final String CREATE_IF_ABSENT = "insert into realtime.realtime_device_connection"
            + " (device_id, guardian_id, child_id, connection_state, recent_disconnects, updated_at)"
            + " values (:deviceId, :guardianId, :childId, 'OFFLINE', 0, :now)"
            + " on conflict (device_id) do nothing";

    private static final String LOCK = SELECT_BY_DEVICE + " for update";

    private static final String MARK_CONNECTED = "update realtime.realtime_device_connection"
            + " set guardian_id = :guardianId, child_id = :childId, connection_state = 'CONNECTED',"
            + " connection_id = :connectionId, connection_since = :now, last_seen_at = :now, updated_at = :now"
            + " where device_id = :deviceId"
            + " returning " + COLUMNS;

    private static final String MARK_SEEN = "update realtime.realtime_device_connection"
            + " set last_seen_at = :seenAt, last_rtt_ms = coalesce(:rttMs, last_rtt_ms),"
            + " updated_at = greatest(updated_at, :seenAt)"
            + " where device_id = :deviceId and connection_id = :connectionId"
            + " and (last_seen_at is null or last_seen_at <= :seenAt)";

    private static final String CHANGE_STATE = "update realtime.realtime_device_connection"
            + " set connection_state = :to, updated_at = :now,"
            + " connection_id = case when :clearConnectionId then null else connection_id end"
            + " where device_id = :deviceId and connection_id = :connectionId and connection_state = any(:from)"
            + " returning " + COLUMNS;

    // 점검 시각 − 40초 이전에 마지막으로 본 연결만(BR4.8 ②). 점검이 읽은 뒤 도착한 HEARTBEAT는 last_seen_at을 올려 0행이 된다
    private static final String MARK_DISCONNECTED = "update realtime.realtime_device_connection"
            + " set connection_state = 'DISCONNECTED', updated_at = :now"
            + " where device_id = :deviceId and connection_id = :connectionId and connection_state = any(:from)"
            + " and last_seen_at <= :cutoff"
            + " returning " + COLUMNS;

    private static final String SELECT_SILENT = "select " + COLUMNS + " from realtime.realtime_device_connection"
            + " where connection_state = any(:from) and last_seen_at <= :cutoff order by device_id";

    private static final String RELEASE = "update realtime.realtime_device_connection"
            + " set connection_state = 'OFFLINE', connection_id = null, updated_at = :now"
            + " where device_id = :deviceId and (connection_state <> 'OFFLINE' or connection_id is not null)"
            + " returning " + COLUMNS;

    private static final String RESET_AFTER_RESTART = "update realtime.realtime_device_connection"
            + " set connection_state = 'DISCONNECTED', connection_id = null, updated_at = :now"
            + " where connection_state = any(:from)"
            + " returning " + COLUMNS;

    private final NamedParameterJdbcTemplate jdbc;

    public DeviceConnectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 기기의 연결 행(한 번도 접속하지 않았으면 비어 있음, Core는 사본 없음으로 본다 BR4.3 ②). */
    public Optional<DeviceConnectionRow> find(UUID deviceId) {
        return single(jdbc.query(SELECT_BY_DEVICE, deviceParam(deviceId), ROW_MAPPER));
    }

    /** 연결 행 전부(10초 상태 보고는 OFFLINE까지 모두 보낸다, BR4.8). 보고의 reportedAt은 호출자가 읽기 직전 시각이다. */
    public List<DeviceConnectionRow> findAll() {
        return jdbc.query(SELECT_ALL, new MapSqlParameterSource(), ROW_MAPPER);
    }

    /**
     * 상태별 기기 수({@code mujoong_device_connection_state{state}}, NFR4.15 · 3.6 수정 J299). 한 번도 접속하지 않은 기기는 행이 없어 세지
     * 않는다(연결 원장이 아는 기기 분포다). 기기 수가 시연 규모(수십 행)라 순차 읽기 한 번이다.
     */
    public Map<ConnectionState, Long> countByState() {
        Map<ConnectionState, Long> counts = new EnumMap<>(ConnectionState.class);
        jdbc.query(COUNT_BY_STATE, new MapSqlParameterSource(), (RowCallbackHandler) rs -> counts.merge(
                ConnectionState.fromWire(rs.getString("connection_state")), rs.getLong("devices"), Long::sum));
        return counts;
    }

    /**
     * HELLO_OK 교체(BR3.7 · SQ-12). 활성 트랜잭션 안에서 행을 잠그고(없으면 만든 뒤) 새 연결로 CONNECTED · connection_since ·
     * last_seen_at = now를 쓴다. 옛 연결 값은 결과의 {@code previous}에 있다(첫 접속이면 null). 호출자는 같은 트랜잭션에
     * DeviceStatusChanged를 쓰고, 커밋 뒤 레지스트리를 바꾸고 옛 소켓을 4001로 닫은 다음 HELLO_OK를 보낸다((l) · R3-7).
     *
     * @param guardianId verify-token 응답의 보호자
     * @param childId verify-token 응답의 아이(없으면 null)
     */
    public Handover handover(UUID deviceId, UUID connectionId, UUID guardianId, UUID childId, Instant now) {
        Transactions.requireActive("DeviceConnectionRepository.handover");
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        ClockParams.uuid(params, "connectionId", Objects.requireNonNull(connectionId, "connectionId"));
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        ClockParams.uuid(params, "childId", childId);
        boolean created = jdbc.update(CREATE_IF_ABSENT, params) == 1;
        DeviceConnectionRow locked = single(jdbc.query(LOCK, params, ROW_MAPPER))
                .orElseThrow(() -> new IllegalStateException("연결 행을 만든 뒤 잠그지 못했다"));
        DeviceConnectionRow current = single(jdbc.query(MARK_CONNECTED, params, ROW_MAPPER))
                .orElseThrow(() -> new IllegalStateException("잠근 연결 행을 갱신하지 못했다"));
        return new Handover(created ? null : locked, current);
    }

    /**
     * 같은 연결의 기기 메시지 시각을 쓴다(HEARTBEAT, J125 · SQ-13). 옛 연결 · 더 이른 시각이면 0행이다.
     *
     * @param rttMs 직전 왕복 시간(없으면 기존 값 유지)
     * @return 썼으면 true
     */
    public boolean markSeen(UUID deviceId, UUID connectionId, Instant seenAt, Integer rttMs) {
        return jdbc.update(MARK_SEEN, seenParams(new LastSeen(deviceId, connectionId, seenAt, rttMs))) == 1;
    }

    /** 기록기가 모은 기기별 마지막 시각을 한 배치로 쓴다(PD-5 표 2.4-A). 반환은 실제로 쓴 행 수. */
    public int markSeenAll(Collection<LastSeen> seen) {
        Objects.requireNonNull(seen, "seen");
        if (seen.isEmpty()) {
            return 0;
        }
        int[] counts = jdbc.batchUpdate(MARK_SEEN,
                seen.stream().map(DeviceConnectionRepository::seenParams).toArray(MapSqlParameterSource[]::new));
        int written = 0;
        for (int count : counts) {
            written += count > 0 ? 1 : 0;
        }
        return written;
    }

    /** 같은 연결의 다음 HEARTBEAT: UNRESPONSIVE · DISCONNECTED → CONNECTED(J55). 바뀌었으면 바뀐 행. */
    public Optional<DeviceConnectionRow> markConnectedAgain(UUID deviceId, UUID connectionId, Instant now) {
        return changeState(deviceId, connectionId, RECOVERABLE, ConnectionState.CONNECTED, false, now);
    }

    /** 명령 시한(BR4.8 ③ · BR6.12 ② ③): CONNECTED · UNSTABLE → UNRESPONSIVE. 바뀌었으면 바뀐 행. */
    public Optional<DeviceConnectionRow> markUnresponsive(UUID deviceId, UUID connectionId, Instant now) {
        return changeState(deviceId, connectionId, UNRESPONSIVE_CANDIDATES, ConnectionState.UNRESPONSIVE, false, now);
    }

    /** 정상 닫기(기기 1000 · 브라우저 1001, BR4.8 ②): 즉시 OFFLINE, connection_id를 비운다. 옛 연결이면 비어 있음. */
    public Optional<DeviceConnectionRow> markClosed(UUID deviceId, UUID connectionId, Instant now) {
        return changeState(deviceId, connectionId, CLOSABLE, ConnectionState.OFFLINE, true, now);
    }

    /**
     * 40초 무음 연결을 DISCONNECTED로 바꾼다(BR4.8 ②). 조건은 같은 연결이고 {@code last_seen_at <= checkedAt − disconnectAfter}다.
     *
     * @param checkedAt 점검 시각(Clock)
     * @param disconnectAfter {@code mujoong.timing.device.disconnect}
     */
    public Optional<DeviceConnectionRow> markDisconnected(UUID deviceId, UUID connectionId, Instant checkedAt,
            Duration disconnectAfter) {
        MapSqlParameterSource params = ClockParams.at(checkedAt);
        ClockParams.uuid(params, "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        ClockParams.uuid(params, "connectionId", Objects.requireNonNull(connectionId, "connectionId"));
        ClockParams.textArray(params, "from", names(DISCONNECT_CANDIDATES));
        ClockParams.instant(params, "cutoff", cutoff(checkedAt, disconnectAfter));
        return single(jdbc.query(MARK_DISCONNECTED, params, ROW_MAPPER));
    }

    /** 40초 무음 후보(SQ-14, 행 수는 기기 수라 순차 스캔). 바꾸기는 행마다 {@link #markDisconnected}로 다시 조건을 건다. */
    public List<DeviceConnectionRow> findSilent(Instant checkedAt, Duration disconnectAfter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.textArray(params, "from", names(DISCONNECT_CANDIDATES));
        ClockParams.instant(params, "cutoff", cutoff(checkedAt, disconnectAfter));
        return jdbc.query(SELECT_SILENT, params, ROW_MAPPER);
    }

    /**
     * 기기 해제(BR3.8: 재등록 · 계정 삭제): {@code device_id}만 조건으로 OFFLINE · connection_id 비움(재시작 뒤 DISCONNECTED 행
     * 포함). 이미 OFFLINE이면 비어 있음(멱등, 상태 이벤트 없음).
     */
    public Optional<DeviceConnectionRow> release(UUID deviceId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        return single(jdbc.query(RELEASE, params, ROW_MAPPER));
    }

    /** 재시작 정리(BR6.13 S3b): CONNECTED · UNSTABLE 행을 DISCONNECTED로, connection_id를 비운다. 바뀐 행 목록. */
    public List<DeviceConnectionRow> resetAfterRestart(Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.textArray(params, "from", names(LIVE_AT_RESTART));
        return jdbc.query(RESET_AFTER_RESTART, params, ROW_MAPPER);
    }

    private Optional<DeviceConnectionRow> changeState(UUID deviceId, UUID connectionId, Set<ConnectionState> from,
            ConnectionState to, boolean clearConnectionId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        ClockParams.uuid(params, "connectionId", Objects.requireNonNull(connectionId, "connectionId"));
        ClockParams.textArray(params, "from", names(from));
        ClockParams.text(params, "to", to.name());
        params.addValue("clearConnectionId", clearConnectionId);
        return single(jdbc.query(CHANGE_STATE, params, ROW_MAPPER));
    }

    private static Instant cutoff(Instant checkedAt, Duration disconnectAfter) {
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (disconnectAfter == null || disconnectAfter.isNegative() || disconnectAfter.isZero()) {
            throw new IllegalArgumentException("disconnectAfter는 0보다 커야 한다");
        }
        return checkedAt.minus(disconnectAfter);
    }

    private static MapSqlParameterSource seenParams(LastSeen seen) {
        Objects.requireNonNull(seen, "seen");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "deviceId", seen.deviceId());
        ClockParams.uuid(params, "connectionId", seen.connectionId());
        ClockParams.instant(params, "seenAt", seen.seenAt());
        ClockParams.integer(params, "rttMs", seen.rttMs());
        return params;
    }

    private static MapSqlParameterSource deviceParam(UUID deviceId) {
        return ClockParams.uuid(new MapSqlParameterSource(), "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
    }

    private static List<String> names(Set<ConnectionState> states) {
        return states.stream().map(Enum::name).sorted().toList();
    }

    private static <T> Optional<T> single(List<T> rows) {
        if (rows.size() > 1) {
            throw new IllegalStateException("기기 하나의 질의가 여러 행을 돌려줬다");
        }
        return rows.stream().findFirst();
    }

    private static final RowMapper<DeviceConnectionRow> ROW_MAPPER = DeviceConnectionRepository::mapRow;

    private static DeviceConnectionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceConnectionRow(
                ClockParams.uuid(rs, "device_id"),
                ClockParams.uuid(rs, "guardian_id"),
                ClockParams.uuid(rs, "child_id"),
                ConnectionState.fromWire(rs.getString("connection_state")),
                ClockParams.uuid(rs, "connection_id"),
                ClockParams.instant(rs, "connection_since"),
                ClockParams.instant(rs, "last_seen_at"),
                ClockParams.nullableInt(rs, "last_rtt_ms"),
                rs.getInt("recent_disconnects"),
                ClockParams.instant(rs, "updated_at"));
    }

    /**
     * 교체 결과.
     *
     * @param previous 잠근 시점의 옛 값(첫 접속이면 null). 옛 connection_id가 있고 그 소켓이 살아 있으면 4001 대상이다
     * @param current 새 연결 값으로 덮은 행
     */
    public record Handover(DeviceConnectionRow previous, DeviceConnectionRow current) {

        /** 첫 접속(행을 새로 만들었다). */
        public boolean firstConnection() {
            return previous == null;
        }

        /** 대체할 옛 연결 식별자(없으면 null). */
        public UUID replacedConnectionId() {
            return previous == null ? null : previous.connectionId();
        }
    }

    /**
     * 기기 메시지 시각 한 건.
     *
     * @param rttMs 직전 왕복 시간(없으면 null)
     */
    public record LastSeen(UUID deviceId, UUID connectionId, Instant seenAt, Integer rttMs) {

        public LastSeen {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(seenAt, "seenAt");
            if (rttMs != null && rttMs < 0) {
                throw new IllegalArgumentException("rttMs는 0 이상이어야 한다");
            }
        }
    }
}
