package kr.mujoong.core.child;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.child.StopExpressionRules.Expression;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 추가 종료 표현 {@code core.core_stop_expression} 저장소 (U2 ChildProfile · FR2.4 · R9).
 *
 * <p>통째 교체만 한다: 같은 트랜잭션에서 그 아이의 행을 지우고 순서대로 다시 넣는다(sort_order 1 ~ n, (child_id, sort_order) 유일은 지연
 * 검사). 트랜잭션은 호출자가 연다. 시각은 앱 Clock 값이다.
 */
@Repository
public class StopExpressionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StopExpressionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 저장된 표현 한 행. */
    public record StoredExpression(UUID id, String text, int sortOrder) {

        /** 표현 글은 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "StoredExpression[id=" + id + ", sortOrder=" + sortOrder + "]";
        }
    }

    /** 아이의 표현(순서대로). */
    public List<StoredExpression> findByChild(UUID childId) {
        return jdbc.query("""
                select id, expression_text, sort_order from core.core_stop_expression
                 where child_id = :childId order by sort_order
                """, ClockParams.uuid(new MapSqlParameterSource(), "childId", Objects.requireNonNull(childId, "childId")),
                (rs, row) -> new StoredExpression(ClockParams.uuid(rs, "id"), rs.getString("expression_text"),
                        rs.getInt("sort_order")));
    }

    /** 아이의 표현을 통째로 바꾼다(트랜잭션 안). */
    public void replaceAll(UUID childId, List<Expression> expressions, Instant now) {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(now, "now");
        deleteByChild(childId);
        int order = 1;
        for (Expression expression : expressions) {
            MapSqlParameterSource params = ClockParams.at(now);
            ClockParams.uuid(params, "id", UUID.randomUUID());
            ClockParams.uuid(params, "childId", childId);
            ClockParams.text(params, "text", expression.text());
            ClockParams.text(params, "normalized", expression.normalized());
            ClockParams.integer(params, "sortOrder", order++);
            jdbc.update("""
                    insert into core.core_stop_expression (id, child_id, expression_text, normalized_text, sort_order,
                                                           created_at, updated_at)
                    values (:id, :childId, :text, :normalized, :sortOrder, :now, :now)
                    """, params);
        }
    }

    /** 아이의 표현을 모두 지운다. */
    public int deleteByChild(UUID childId) {
        return jdbc.update("delete from core.core_stop_expression where child_id = :childId",
                ClockParams.uuid(new MapSqlParameterSource(), "childId", Objects.requireNonNull(childId, "childId")));
    }
}
