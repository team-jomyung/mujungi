package kr.mujoong.realtime.guardrail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.guardrail_event} 데이터 접근 (U13 FR6.1 · FR6.2 · BR8.1 ~ BR8.3).
 *
 * <ul>
 *   <li>쓰기: {@code insert ... on conflict (id) do update}로 같은 id(= 검사 요청 id)를 덮어쓴다. 다시 보내도 같은 결과다(커밋 실패 뒤 재시도).
 *       호출자의 트랜잭션 안에서만 쓴다(MANDATORY).
 *   <li>삭제: 보호자 기준 전부 지운다(계정 삭제 참가자, 순서 185). 세션 삭제(200)가 외래 키로 막히지 않게 먼저 지운다.
 *   <li><b>읽는 외부 경로가 없다</b>: 보호자 API · 리포트 · 기록 스냅숏은 이 표를 읽지 않는다. 이 클래스의 읽기 메서드는 같은 패키지 시험 확인용
 *       (package-private)이다.
 * </ul>
 */
@Repository
public class GuardrailEventRepository {

    private static final String UPSERT = "insert into realtime.guardrail_event (id, guardian_id, session_id, mode, source_kind,"
            + " decision, reason_code, policy_version, latency_ms, created_at) values (:id, :guardianId, :sessionId, :mode,"
            + " :sourceKind, :decision, :reasonCode, :policyVersion, :latency, :createdAt)"
            + " on conflict (id) do update set mode = excluded.mode, source_kind = excluded.source_kind,"
            + " decision = excluded.decision, reason_code = excluded.reason_code, policy_version = excluded.policy_version,"
            + " latency_ms = excluded.latency_ms";

    private static final String DELETE_BY_GUARDIAN = "delete from realtime.guardrail_event where guardian_id = :guardianId";

    private static final String SELECT_BY_ID = "select * from realtime.guardrail_event where id = :id";

    private static final String COUNT_BY_GUARDIAN = "select count(*) from realtime.guardrail_event where guardian_id = :guardianId";

    private static final RowMapper<GuardrailEventRecord> MAPPER = GuardrailEventRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public GuardrailEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 한 건을 쓰거나 같은 id를 덮어쓴다. 활성 트랜잭션 안에서만 부른다. */
    public void upsert(GuardrailEventRecord record) {
        Objects.requireNonNull(record, "record");
        Transactions.requireActive("GuardrailEventRepository.upsert");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", record.id());
        ClockParams.uuid(params, "guardianId", record.guardianId());
        ClockParams.uuid(params, "sessionId", record.sessionId());
        ClockParams.text(params, "mode", record.mode().name());
        ClockParams.text(params, "sourceKind", record.sourceKind().name());
        ClockParams.text(params, "decision", record.decision().name());
        ClockParams.text(params, "reasonCode", record.reasonCode());
        ClockParams.text(params, "policyVersion", record.policyVersion());
        params.addValue("latency", record.latencyMs(), Types.INTEGER);
        ClockParams.instant(params, "createdAt", record.createdAt());
        jdbc.update(UPSERT, params);
    }

    /** 한 보호자의 제브 검사 기록을 모두 지운다(계정 삭제 참가자). 지운 행 수. 다시 불러도 같다. */
    public int deleteByGuardian(UUID guardianId) {
        Objects.requireNonNull(guardianId, "guardianId");
        return jdbc.update(DELETE_BY_GUARDIAN, ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId));
    }

    /** 시험 확인용: id로 한 건. */
    Optional<GuardrailEventRecord> find(UUID id) {
        List<GuardrailEventRecord> rows = jdbc.query(SELECT_BY_ID, ClockParams.uuid(new MapSqlParameterSource(), "id", id), MAPPER);
        return rows.stream().findFirst();
    }

    /** 시험 확인용: 보호자의 기록 수. */
    long countByGuardian(UUID guardianId) {
        Long count = jdbc.queryForObject(COUNT_BY_GUARDIAN,
                ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId), Long.class);
        return count == null ? 0L : count;
    }

    private static GuardrailEventRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new GuardrailEventRecord(ClockParams.uuid(rs, "id"), ClockParams.uuid(rs, "guardian_id"),
                ClockParams.uuid(rs, "session_id"), GuardrailMode.valueOf(rs.getString("mode")),
                GuardrailEventRecord.SourceKind.valueOf(rs.getString("source_kind")),
                GuardrailEventRecord.Decision.valueOf(rs.getString("decision")), rs.getString("reason_code"),
                rs.getString("policy_version"), rs.getInt("latency_ms"), ClockParams.instant(rs, "created_at"));
    }
}
