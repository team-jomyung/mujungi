package kr.mujoong.realtime.judgment;

import java.math.BigDecimal;
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
 * {@code realtime.realtime_judgment_live} 데이터 접근 (U11 FR2.1 · FR2.3 · BR2.1 · BR2.2 · BR2.4 · BR2.5).
 *
 * <ul>
 *   <li>쓰기: {@code insert ... on conflict (id) do update}로 같은 id(= turnId)를 덮어쓴다. 다시 보내도 같은 결과다(커밋 실패 뒤 재시도).
 *       호출자의 트랜잭션 안에서만 쓴다(MANDATORY).
 *   <li>삭제: 보호자 기준 전부 지운다(계정 삭제 참가자, 순서 180). 세션 삭제(200)가 외래 키로 막히지 않게 먼저 지운다.
 *   <li><b>읽는 외부 경로가 없다</b>(BR2.2): 보호자 API · 리포트 · 기록 스냅숏은 이 표를 읽지 않는다. 이 클래스의 읽기 메서드는 같은 패키지
 *       시험 확인용(package-private)이다.
 * </ul>
 */
@Repository
public class JudgmentLiveRepository {

    private static final String UPSERT = "insert into realtime.realtime_judgment_live (id, guardian_id, session_id, judgment_point,"
            + " trigger_kind, goal_ref, scene_ref, input_transcript_seq, option_set_version, prompt_version, model, outcome,"
            + " abstain_reason, turn_opened, first_option, first_probability, second_option, second_probability, latency_millis,"
            + " next_action, held_output_transcript, created_at) values (:id, :guardianId, :sessionId, :point, :trigger, :goalRef,"
            + " :sceneRef, :seq, :optionSet, :prompt, :model, :outcome, :reason, :opened, :firstOption, :firstProbability,"
            + " :secondOption, :secondProbability, :latency, :nextAction, :held, :createdAt)"
            + " on conflict (id) do update set judgment_point = excluded.judgment_point, trigger_kind = excluded.trigger_kind,"
            + " goal_ref = excluded.goal_ref, scene_ref = excluded.scene_ref, input_transcript_seq = excluded.input_transcript_seq,"
            + " option_set_version = excluded.option_set_version, prompt_version = excluded.prompt_version, model = excluded.model,"
            + " outcome = excluded.outcome, abstain_reason = excluded.abstain_reason, turn_opened = excluded.turn_opened,"
            + " first_option = excluded.first_option, first_probability = excluded.first_probability,"
            + " second_option = excluded.second_option, second_probability = excluded.second_probability,"
            + " latency_millis = excluded.latency_millis, next_action = excluded.next_action,"
            + " held_output_transcript = excluded.held_output_transcript";

    private static final String DELETE_BY_GUARDIAN = "delete from realtime.realtime_judgment_live where guardian_id = :guardianId";

    private static final String SELECT_BY_ID = "select * from realtime.realtime_judgment_live where id = :id";

    private static final String COUNT_BY_GUARDIAN = "select count(*) from realtime.realtime_judgment_live where guardian_id = :guardianId";

    private static final RowMapper<JudgmentLiveRecord> MAPPER = JudgmentLiveRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public JudgmentLiveRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 한 건을 쓰거나 같은 id를 덮어쓴다. 활성 트랜잭션 안에서만 부른다. */
    public void upsert(JudgmentLiveRecord record) {
        Objects.requireNonNull(record, "record");
        Transactions.requireActive("JudgmentLiveRepository.upsert");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", record.id());
        ClockParams.uuid(params, "guardianId", record.guardianId());
        ClockParams.uuid(params, "sessionId", record.sessionId());
        ClockParams.text(params, "point", record.point().name());
        ClockParams.text(params, "trigger", record.trigger().name());
        ClockParams.uuid(params, "goalRef", record.goalRef());
        ClockParams.uuid(params, "sceneRef", record.sceneRef());
        ClockParams.integer(params, "seq", record.inputTranscriptSeq());
        ClockParams.text(params, "optionSet", record.optionSetVersion());
        ClockParams.text(params, "prompt", record.promptVersion());
        ClockParams.text(params, "model", record.model());
        ClockParams.text(params, "outcome", record.outcome().name());
        ClockParams.text(params, "reason", record.abstainReason() == null ? null : record.abstainReason().name());
        params.addValue("opened", record.turnOpened(), Types.BOOLEAN);
        ClockParams.text(params, "firstOption", record.firstOption());
        params.addValue("firstProbability", decimal(record.firstProbability()), Types.NUMERIC);
        ClockParams.text(params, "secondOption", record.secondOption());
        params.addValue("secondProbability", decimal(record.secondProbability()), Types.NUMERIC);
        params.addValue("latency", (int) Math.min(Integer.MAX_VALUE, record.latencyMillis()), Types.INTEGER);
        ClockParams.text(params, "nextAction", record.nextAction().name());
        ClockParams.text(params, "held", record.heldOutputTranscript());
        ClockParams.instant(params, "createdAt", record.createdAt());
        jdbc.update(UPSERT, params);
    }

    /** 한 보호자의 판정 기록을 모두 지운다(계정 삭제 참가자). 지운 행 수. 다시 불러도 같다. */
    public int deleteByGuardian(UUID guardianId) {
        Objects.requireNonNull(guardianId, "guardianId");
        return jdbc.update(DELETE_BY_GUARDIAN, ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId));
    }

    /** 시험 확인용: id로 한 건. */
    Optional<JudgmentLiveRecord> find(UUID id) {
        List<JudgmentLiveRecord> rows = jdbc.query(SELECT_BY_ID, ClockParams.uuid(new MapSqlParameterSource(), "id", id), MAPPER);
        return rows.stream().findFirst();
    }

    /** 시험 확인용: 보호자의 기록 수. */
    long countByGuardian(UUID guardianId) {
        Long count = jdbc.queryForObject(COUNT_BY_GUARDIAN,
                ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId), Long.class);
        return count == null ? 0L : count;
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static Double probability(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static JudgmentLiveRecord map(ResultSet rs, int rowNum) throws SQLException {
        String reason = rs.getString("abstain_reason");
        return new JudgmentLiveRecord(ClockParams.uuid(rs, "id"), ClockParams.uuid(rs, "guardian_id"), ClockParams.uuid(rs, "session_id"),
                JudgmentPoint.valueOf(rs.getString("judgment_point")), JudgmentTrigger.valueOf(rs.getString("trigger_kind")),
                ClockParams.uuid(rs, "goal_ref"), ClockParams.uuid(rs, "scene_ref"), rs.getInt("input_transcript_seq"),
                rs.getString("option_set_version"), rs.getString("prompt_version"), rs.getString("model"),
                JudgmentOutcomeKind.valueOf(rs.getString("outcome")), reason == null ? null : AbstainReason.valueOf(reason),
                rs.getBoolean("turn_opened"), rs.getString("first_option"), probability(rs, "first_probability"),
                rs.getString("second_option"), probability(rs, "second_probability"), rs.getLong("latency_millis"),
                NextAction.valueOf(rs.getString("next_action")), rs.getString("held_output_transcript"),
                ClockParams.instant(rs, "created_at"));
    }
}
