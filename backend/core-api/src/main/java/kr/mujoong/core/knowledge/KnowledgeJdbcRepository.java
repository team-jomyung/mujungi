package kr.mujoong.core.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL is authoritative; no query crosses the Core schema. */
@Repository
public class KnowledgeJdbcRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public KnowledgeJdbcRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<BehaviorDefinition> findVersion(String key, int version) {
        return readVersion(key, version, false);
    }
    /**
     * 고정(pin)용 읽기: FOR KEY SHARE. 같은 정의 행을 여러 보호자가 함께 고정해도 서로 줄 서지 않고, 행 삭제 · 키 변경만 막는다(U5a 리뷰 1).
     * 내용 수정은 used_at이 찍힌 뒤 트리거가 막는다.
     */
    public Optional<BehaviorDefinition> readForPin(String key, int version) {
        return readVersion(key, version, true);
    }
    private Optional<BehaviorDefinition> readVersion(String key, int version, boolean lock) {
        String sql = """
                select * from core.core_behavior_definition
                where behavior_key = :key and version = :version
                """ + (lock ? " for key share" : "");
        return jdbc.query(sql, parameters(key, version), (rs, row) -> new BehaviorDefinition(
                ClockParams.uuid(rs, "id"), rs.getString("behavior_key"), rs.getInt("version"),
                rs.getString("display_name"), rs.getString("behavior_definition_text"),
                rs.getString("opportunity_condition"), rs.getString("scenario_condition"),
                rs.getString("judgment_criteria"), rs.getString("status"),
                ClockParams.instant(rs, "created_at"), ClockParams.instant(rs, "used_at")))
                .stream().findFirst();
    }

    public List<SupportItem> findApprovedSupport(String key) {
        return jdbc.query("""
                select id, behavior_key, condition_text, content_text, limitation_text
                from core.core_support_item where behavior_key = :key and status = 'APPROVED'
                order by created_at, id
                """, new MapSqlParameterSource("key", key), (rs, row) -> new SupportItem(
                ClockParams.uuid(rs, "id"), rs.getString("behavior_key"), rs.getString("condition_text"),
                rs.getString("content_text"), rs.getString("limitation_text")));
    }

    /** 처음 고정할 때 한 번만 used_at을 찍는다. 이미 찍혔거나 고정할 수 없는 상태면 0건이다(같은 행을 다시 갱신하지 않는다, U5a 리뷰 1). */
    public int markPinned(String key, int version, Instant now) {
        return jdbc.update("""
                update core.core_behavior_definition set used_at = :now
                where behavior_key = :key and version = :version and used_at is null and status in ('ACTIVE','RETIRED')
                """, ClockParams.instant(parameters(key, version), "now", now));
    }

    private static MapSqlParameterSource parameters(String key, int version) {
        return new MapSqlParameterSource("key", key).addValue("version", version);
    }
}
