package kr.mujoong.realtime.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_session_event} 데이터 접근 (U1 3.5 Step 16 · entities SessionEvent · BR6.8 · BR6.9 · PD-5).
 *
 * <p>추가만 하는 기록이다(덮어쓰기 없음). 끝내기 수락의 requestId는 {@code op_id}에, 0행으로 진 전이 · 허용되지 않은 전이는
 * 기록만 남긴다(BR6.8 violation). 세션 행과 같은 트랜잭션 또는 기록기 플러시 배치(PD-5)에서 쓰이므로 JDBC 한 문장 · 배치로 쓰고
 * JPA 쓰기 지연(플러시 시점)에 기대지 않는다. 행 값 형식은 JPA 엔터티 {@link SessionEvent}가 정한다(S2 validate 대상).
 * payload에는 전사 원문을 넣지 않는다(NFR12.3).
 */
@Repository
public class SessionEventRepository {

    private static final String INSERT = "insert into realtime.realtime_session_event"
            + " (id, session_id, event_type, op_id, payload_json, created_at)"
            + " values (:id, :sessionId, :eventType, :opId, cast(:payload as jsonb), :createdAt)";

    private static final String SELECT_BY_SESSION = "select id, session_id, event_type, op_id, payload_json, created_at"
            + " from realtime.realtime_session_event where session_id = :sessionId order by created_at, id";

    private final NamedParameterJdbcTemplate jdbc;

    public SessionEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 한 행을 추가한다(세션 행이 먼저 있어야 한다, 외래 키). */
    public void insert(SessionEvent event) {
        jdbc.update(INSERT, params(event));
    }

    /** 여러 행을 한 배치로 추가한다(기록기 플러시, PD-5). 비어 있으면 아무것도 하지 않는다. */
    public void insertAll(Collection<SessionEvent> events) {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT, events.stream().map(SessionEventRepository::params).toArray(MapSqlParameterSource[]::new));
    }

    /** 세션의 기록을 시각 순으로 읽는다. */
    public List<SessionEvent> findBySession(UUID sessionId) {
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "sessionId",
                Objects.requireNonNull(sessionId, "sessionId"));
        return jdbc.query(SELECT_BY_SESSION, params, ROW_MAPPER);
    }

    private static MapSqlParameterSource params(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", event.getId());
        ClockParams.uuid(params, "sessionId", event.getSessionId());
        ClockParams.text(params, "eventType", event.getEventType());
        ClockParams.text(params, "opId", event.getOpId());
        ClockParams.text(params, "payload", event.getPayloadJson());
        ClockParams.instant(params, "createdAt", event.getCreatedAt());
        return params;
    }

    private static final RowMapper<SessionEvent> ROW_MAPPER = SessionEventRepository::mapRow;

    private static SessionEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SessionEvent(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "session_id"),
                rs.getString("event_type"),
                rs.getString("op_id"),
                rs.getString("payload_json"),
                ClockParams.instant(rs, "created_at"));
    }
}
