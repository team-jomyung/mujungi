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
 * {@code realtime.realtime_session_stop_expression} 데이터 접근 (U1 3.5 Step 16 · BR6.5 · BR6.6 ④ · entities SessionStopExpression).
 *
 * <p>세션 시작 트랜잭션(세션 행 INSERT · SessionStateChanged와 같은 트랜잭션)에서 세션 패키지의 stopExpressions를 한 배치로 쓴다.
 * U1은 세션마다 기본 표현 '무중아 그만할래' 한 행(is_default true)이다. 세션 행 INSERT와 같은 JDBC 경로로 즉시 써서 외래 키 ·
 * 순서가 JPA 플러시 시점에 기대지 않게 한다. 행 값 형식은 JPA 엔터티 {@link SessionStopExpression}이 정한다.
 */
@Repository
public class SessionStopExpressionRepository {

    private static final String INSERT = "insert into realtime.realtime_session_stop_expression"
            + " (id, session_id, expression_text, is_default, sort_order, created_at)"
            + " values (:id, :sessionId, :expressionText, :isDefault, :sortOrder, :createdAt)";

    private static final String SELECT_BY_SESSION = "select id, session_id, expression_text, is_default, sort_order,"
            + " created_at from realtime.realtime_session_stop_expression where session_id = :sessionId"
            + " order by sort_order, id";

    private final NamedParameterJdbcTemplate jdbc;

    public SessionStopExpressionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 세션의 종료 표현을 한 배치로 쓴다. 비어 있으면 아무것도 하지 않는다. */
    public void insertAll(Collection<SessionStopExpression> expressions) {
        Objects.requireNonNull(expressions, "expressions");
        if (expressions.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT, expressions.stream()
                .map(SessionStopExpressionRepository::params)
                .toArray(MapSqlParameterSource[]::new));
    }

    /** 세션의 종료 표현을 정렬 순서로 읽는다. */
    public List<SessionStopExpression> findBySession(UUID sessionId) {
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "sessionId",
                Objects.requireNonNull(sessionId, "sessionId"));
        return jdbc.query(SELECT_BY_SESSION, params, ROW_MAPPER);
    }

    private static MapSqlParameterSource params(SessionStopExpression expression) {
        Objects.requireNonNull(expression, "expression");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", expression.getId());
        ClockParams.uuid(params, "sessionId", expression.getSessionId());
        ClockParams.text(params, "expressionText", expression.getExpressionText());
        params.addValue("isDefault", expression.isDefaultExpression());
        params.addValue("sortOrder", expression.getSortOrder());
        ClockParams.instant(params, "createdAt", expression.getCreatedAt());
        return params;
    }

    private static final RowMapper<SessionStopExpression> ROW_MAPPER = SessionStopExpressionRepository::mapRow;

    private static SessionStopExpression mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SessionStopExpression(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "session_id"),
                rs.getString("expression_text"),
                rs.getBoolean("is_default"),
                rs.getInt("sort_order"),
                ClockParams.instant(rs, "created_at"));
    }
}
