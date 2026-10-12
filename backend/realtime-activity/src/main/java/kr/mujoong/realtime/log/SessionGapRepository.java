package kr.mujoong.realtime.log;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * {@code realtime.realtime_session_gap} 데이터 접근 — 세션 안 끊김 구간 (U3 L1 · ConversationLog · U3-R11 · U3-J18 · AC3.2.10(b) ·
 * AC6.6.4 · 계약 LiveView.gaps).
 *
 * <p>DISCONNECT 구간은 묶인 기기 연결이 끊긴 시각에 열리고 새 연결이 다시 붙은 시각에 닫힌다. 세션이 ABORTED로 끝나면 열린 채 남는다
 * (ended_at 없음 = 복구하지 못함). UNCONFIRMED는 자리만 둔다(U4). 전사 원문은 담지 않는다. 시각은 호출자가 Clock에서 읽은 값만 쓴다(R2).
 */
@Repository
public class SessionGapRepository {

    /** 구간 종류(V2 CHECK · 계약 Gap.kind). */
    public enum Kind {
        DISCONNECT,
        UNCONFIRMED
    }

    /** 한 행. */
    public record SessionGapRow(UUID id, UUID sessionId, Kind kind, Instant startedAt, Instant endedAt) {

        /** 아직 열려 있는가(복구 전 또는 복구 못 함). */
        public boolean isOpen() {
            return endedAt == null;
        }
    }

    private static final String COLUMNS = "id, session_id, kind, started_at, ended_at";

    private static final String INSERT = "insert into realtime.realtime_session_gap"
            + " (id, session_id, kind, started_at, created_at) values (:id, :sessionId, :kind, :startedAt, :now)";

    private static final String CLOSE = "update realtime.realtime_session_gap set ended_at = :endedAt"
            + " where id = :id and ended_at is null and started_at <= :endedAt";

    private static final String SELECT_BY_SESSION = "select " + COLUMNS + " from realtime.realtime_session_gap"
            + " where session_id = :sessionId order by started_at, id";

    private static final String DELETE_BY_SESSIONS = "delete from realtime.realtime_session_gap"
            + " where session_id = any(:sessionIds)";

    private final NamedParameterJdbcTemplate jdbc;

    public SessionGapRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 구간을 연다.
     *
     * @param now created_at(Clock)
     */
    public void open(UUID id, UUID sessionId, Kind kind, Instant startedAt, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", Objects.requireNonNull(id, "id"));
        ClockParams.uuid(params, "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
        ClockParams.text(params, "kind", Objects.requireNonNull(kind, "kind").name());
        ClockParams.instant(params, "startedAt", Objects.requireNonNull(startedAt, "startedAt"));
        jdbc.update(INSERT, params);
    }

    /** 구간을 닫는다. 이미 닫혔거나 시작보다 이른 시각이면 바꾸지 않는다(멱등). 닫았으면 true. */
    public boolean close(UUID id, Instant endedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", Objects.requireNonNull(id, "id"));
        ClockParams.instant(params, "endedAt", Objects.requireNonNull(endedAt, "endedAt"));
        return jdbc.update(CLOSE, params) == 1;
    }

    /** 세션의 구간들(시작 순, 12 조회 gaps). */
    public List<SessionGapRow> findBySession(UUID sessionId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
        return jdbc.query(SELECT_BY_SESSION, params, ROW_MAPPER);
    }

    /** 세션들의 구간을 지운다(삭제 참가자, U3-J16). 지운 행 수. */
    public int deleteBySessions(List<UUID> sessionIds) {
        Objects.requireNonNull(sessionIds, "sessionIds");
        if (sessionIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuidArray(params, "sessionIds", sessionIds);
        return jdbc.update(DELETE_BY_SESSIONS, params);
    }

    private static final RowMapper<SessionGapRow> ROW_MAPPER = SessionGapRepository::mapRow;

    private static SessionGapRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SessionGapRow(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "session_id"),
                Kind.valueOf(rs.getString("kind")),
                ClockParams.instant(rs, "started_at"),
                ClockParams.instant(rs, "ended_at"));
    }
}
