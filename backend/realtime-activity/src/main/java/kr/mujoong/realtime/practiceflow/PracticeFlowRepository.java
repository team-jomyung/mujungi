package kr.mujoong.realtime.practiceflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.Fact;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_practice_scene_run} · {@code realtime.realtime_practice_help} 데이터 접근 (U12 FR4.1 · BR7.1 · BR7.3 · BR7.4).
 *
 * <ul>
 *   <li>쓰기는 호출자의 트랜잭션 안에서만 한다(MANDATORY, RD-22). 시각은 호출자가 넘긴 {@code MujoongClock} 값이고 DB 시각 함수는 쓰지 않는다(R2).
 *   <li>원문을 받는 메서드가 없다: 분류 코드 · 길이 · 횟수 · 시각만 오간다(R-F14 · BR7.1). 이 클래스는 로그를 쓰지 않는다.
 *   <li>삭제는 보호자 기준 도움 → 장면 실행 순서로 지운다(외래 키 RESTRICT). 보호자 삭제 참여자(순서 190)와 시험이 쓴다.
 *   <li>레거시 V4 표(U5b)는 읽지도 쓰지도 않는다(BR10.1).
 * </ul>
 */
@Repository
public class PracticeFlowRepository {

    private static final String INSERT_RUN = "insert into realtime.realtime_practice_scene_run (id, session_id, guardian_id,"
            + " goal_ref, goal_index, scenario_ref, scene_kind, started_at, ended_at, live_timing, variant_skip_reason,"
            + " retry_count, reexplain_count) values (:id, :sessionId, :guardianId, :goalRef, :goalIndex, :scenarioRef,"
            + " :sceneKind, :startedAt, :endedAt, :liveTiming, :skipReason, :retryCount, :reexplainCount)";

    private static final String APPEND_FACT = "update realtime.realtime_practice_scene_run set"
            + " facts = array_append(facts, cast(:kind as text)), fact_ats = array_append(fact_ats, :at) where id = :id";

    private static final String UPDATE_OUTCOME = "update realtime.realtime_practice_scene_run set ended_at = :endedAt,"
            + " live_timing = :liveTiming, variant_skip_reason = :skipReason, retry_count = :retryCount,"
            + " reexplain_count = :reexplainCount where id = :id";

    private static final String INSERT_HELP = "insert into realtime.realtime_practice_help (id, scene_run_id, guardian_id, kind,"
            + " role, example_given, consent_answer, content_chars, occurred_at) values (:id, :sceneRunId, :guardianId, :kind,"
            + " :role, :exampleGiven, :consentAnswer, :contentChars, :occurredAt)";

    private static final String SELECT_RUNS = "select id, session_id, guardian_id, goal_ref, goal_index, scenario_ref, scene_kind,"
            + " started_at, ended_at, live_timing, variant_skip_reason, retry_count, reexplain_count"
            + " from realtime.realtime_practice_scene_run where session_id = :sessionId order by started_at, id";

    private static final String SELECT_FACTS = "select r.id as run_id, f.fkind as kind, f.fat as fact_at"
            + " from realtime.realtime_practice_scene_run r, unnest(r.facts, r.fact_ats) with ordinality as f(fkind, fat, n)"
            + " where r.session_id = :sessionId order by r.started_at, r.id, f.n";

    private static final String SELECT_HELPS = "select h.id, h.scene_run_id, h.guardian_id, h.kind, h.role, h.example_given,"
            + " h.consent_answer, h.content_chars, h.occurred_at from realtime.realtime_practice_help h"
            + " join realtime.realtime_practice_scene_run r on r.id = h.scene_run_id where r.session_id = :sessionId"
            + " order by h.occurred_at, h.id";

    private static final String EXISTS_BY_SESSION = "select exists (select 1 from realtime.realtime_practice_scene_run"
            + " where session_id = :sessionId)";

    private static final String DELETE_HELPS = "delete from realtime.realtime_practice_help where guardian_id = :guardianId";
    private static final String DELETE_RUNS = "delete from realtime.realtime_practice_scene_run where guardian_id = :guardianId";

    private static final RowMapper<HelpRecord> HELP_MAPPER = PracticeFlowRepository::mapHelp;

    private final NamedParameterJdbcTemplate jdbc;

    public PracticeFlowRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 삭제 건수(보호자 기준). */
    public record DeletedCounts(int helps, int sceneRuns) {
    }

    /** 장면 실행 한 행을 쓴다(처음 사실이 있으면 이어서 쌓는다). 활성 트랜잭션 안에서만 부른다. */
    public void insertSceneRun(SceneRunRecord run) {
        Objects.requireNonNull(run, "run");
        Transactions.requireActive("PracticeFlowRepository.insertSceneRun");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", run.id());
        ClockParams.uuid(params, "sessionId", run.sessionId());
        ClockParams.uuid(params, "guardianId", run.guardianId());
        ClockParams.uuid(params, "goalRef", run.goalRef());
        ClockParams.integer(params, "goalIndex", run.goalIndex());
        ClockParams.uuid(params, "scenarioRef", run.scenarioRef());
        ClockParams.text(params, "sceneKind", run.sceneKind().name());
        ClockParams.instant(params, "startedAt", run.startedAt());
        ClockParams.instant(params, "endedAt", run.endedAt());
        ClockParams.text(params, "liveTiming", name(run.liveTiming()));
        ClockParams.text(params, "skipReason", name(run.variantSkipReason()));
        ClockParams.integer(params, "retryCount", run.retryCount());
        ClockParams.integer(params, "reexplainCount", run.reexplainCount());
        jdbc.update(INSERT_RUN, params);
        for (Fact fact : run.facts()) {
            appendFact(run.id(), fact.kind(), fact.at());
        }
    }

    /** 일어난 사실 하나를 그 장면 실행의 끝에 쌓는다(순서 보존). 그 장면 행이 없으면 거짓. */
    public boolean appendFact(UUID sceneRunId, FactKind kind, Instant at) {
        Objects.requireNonNull(sceneRunId, "sceneRunId");
        Objects.requireNonNull(kind, "kind");
        Transactions.requireActive("PracticeFlowRepository.appendFact");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", sceneRunId);
        ClockParams.text(params, "kind", kind.name());
        ClockParams.instant(params, "at", Objects.requireNonNull(at, "at"));
        return jdbc.update(APPEND_FACT, params) == 1;
    }

    /**
     * 장면의 결과를 덮어쓴다(끝 시각 · 진행 중 판정 시점 · 변형 생략 사유 · 횟수). 같은 값으로 다시 불러도 같다(멱등). 그 장면 행이 없으면 거짓.
     */
    public boolean updateOutcome(UUID sceneRunId, Instant endedAt, LiveTiming liveTiming, VariantSkipReason skipReason,
            int retryCount, int reexplainCount) {
        Objects.requireNonNull(sceneRunId, "sceneRunId");
        Transactions.requireActive("PracticeFlowRepository.updateOutcome");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", sceneRunId);
        ClockParams.instant(params, "endedAt", endedAt);
        ClockParams.text(params, "liveTiming", name(liveTiming));
        ClockParams.text(params, "skipReason", name(skipReason));
        ClockParams.integer(params, "retryCount", retryCount);
        ClockParams.integer(params, "reexplainCount", reexplainCount);
        return jdbc.update(UPDATE_OUTCOME, params) == 1;
    }

    /** 도움 · 역할 전환 기록 한 건을 쓴다(분류 코드와 길이만). 활성 트랜잭션 안에서만 부른다. */
    public void insertHelp(HelpRecord help) {
        Objects.requireNonNull(help, "help");
        Transactions.requireActive("PracticeFlowRepository.insertHelp");
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", help.id());
        ClockParams.uuid(params, "sceneRunId", help.sceneRunId());
        ClockParams.uuid(params, "guardianId", help.guardianId());
        ClockParams.text(params, "kind", help.kind().name());
        ClockParams.text(params, "role", help.role().name());
        params.addValue("exampleGiven", help.exampleGiven(), java.sql.Types.BOOLEAN);
        ClockParams.text(params, "consentAnswer", name(help.consentAnswer()));
        ClockParams.integer(params, "contentChars", help.contentChars());
        ClockParams.instant(params, "occurredAt", help.occurredAt());
        jdbc.update(INSERT_HELP, params);
    }

    /** 세션의 장면 실행을 시작 시각 순으로, 사실을 쌓인 순서대로 읽는다(정산 · 복구가 쓴다). */
    public List<SceneRunRecord> findSceneRuns(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "sessionId", sessionId);
        List<SceneRunRecord> runs = jdbc.query(SELECT_RUNS, params, PracticeFlowRepository::mapRun);
        if (runs.isEmpty()) {
            return runs;
        }
        Map<UUID, List<Fact>> facts = new LinkedHashMap<>();
        jdbc.query(SELECT_FACTS, params, rs -> {
            facts.computeIfAbsent(ClockParams.uuid(rs, "run_id"), id -> new ArrayList<>())
                    .add(new Fact(FactKind.valueOf(rs.getString("kind")), ClockParams.instant(rs, "fact_at")));
        });
        List<SceneRunRecord> withFacts = new ArrayList<>(runs.size());
        for (SceneRunRecord run : runs) {
            withFacts.add(new SceneRunRecord(run.id(), run.sessionId(), run.guardianId(), run.goalRef(), run.goalIndex(),
                    run.scenarioRef(), run.sceneKind(), run.startedAt(), run.endedAt(), run.liveTiming(),
                    run.variantSkipReason(), run.retryCount(), run.reexplainCount(), facts.getOrDefault(run.id(), List.of())));
        }
        return List.copyOf(withFacts);
    }

    /** 세션의 도움 · 역할 전환 기록을 발생 순으로 읽는다. */
    public List<HelpRecord> findHelps(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return jdbc.query(SELECT_HELPS, ClockParams.uuid(new MapSqlParameterSource(), "sessionId", sessionId), HELP_MAPPER);
    }

    /** 이 세션에 새 연습 흐름의 장면 실행이 하나라도 있는가(정산 복구가 새 흐름 · 레거시를 가른다). */
    public boolean hasSceneRuns(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Boolean exists = jdbc.queryForObject(EXISTS_BY_SESSION,
                ClockParams.uuid(new MapSqlParameterSource(), "sessionId", sessionId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 한 보호자의 새 연습 흐름 기록을 모두 지운다(보호자 삭제 참여자, 순서 190): 도움 → 장면 실행 순서(외래 키 RESTRICT). 호출자의 트랜잭션 안에서
     * 한다. 다시 불러도 같다(0건도 성공).
     */
    public DeletedCounts deleteByGuardian(UUID guardianId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Transactions.requireActive("PracticeFlowRepository.deleteByGuardian");
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId);
        int helps = jdbc.update(DELETE_HELPS, params);
        int runs = jdbc.update(DELETE_RUNS, params);
        return new DeletedCounts(helps, runs);
    }

    private static SceneRunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new SceneRunRecord(ClockParams.uuid(rs, "id"), ClockParams.uuid(rs, "session_id"),
                ClockParams.uuid(rs, "guardian_id"), ClockParams.uuid(rs, "goal_ref"), rs.getInt("goal_index"),
                ClockParams.uuid(rs, "scenario_ref"), SceneKind.valueOf(rs.getString("scene_kind")),
                ClockParams.instant(rs, "started_at"), ClockParams.instant(rs, "ended_at"),
                enumOrNull(LiveTiming.class, rs.getString("live_timing")),
                enumOrNull(VariantSkipReason.class, rs.getString("variant_skip_reason")), rs.getInt("retry_count"),
                rs.getInt("reexplain_count"), List.of());
    }

    private static HelpRecord mapHelp(ResultSet rs, int rowNum) throws SQLException {
        return new HelpRecord(ClockParams.uuid(rs, "id"), ClockParams.uuid(rs, "scene_run_id"),
                ClockParams.uuid(rs, "guardian_id"), HelpKind.valueOf(rs.getString("kind")),
                FlowRole.valueOf(rs.getString("role")), rs.getBoolean("example_given"),
                enumOrNull(ConsentAnswer.class, rs.getString("consent_answer")), rs.getInt("content_chars"),
                ClockParams.instant(rs, "occurred_at"));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
