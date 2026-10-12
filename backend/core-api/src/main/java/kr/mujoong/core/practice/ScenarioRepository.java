package kr.mujoong.core.practice;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.practice.PracticeSessionContextPort.GoalSnapshot;
import kr.mujoong.core.practice.PracticeSessionContextPort.SessionContext;
import kr.mujoong.core.textai.GeneratedScenario;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** All writes run under the guardian lock in the service's transaction. No external schema access. */
@Repository
public class ScenarioRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;
    public ScenarioRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) { this.jdbc = jdbc; this.json = json; }

    public record Stored(Scenario scenario, UUID guardianId, UUID childId, UUID sessionId, UUID goalSelectionId,
                         String behaviorKey, int behaviorVersion, String status) {}
    public record Claim(UUID claimId, Scenario replay) {}
    private record RequestState(UUID claimId, Instant until, String status, UUID result) {}

    public Claim claim(String key, SessionContext context, ScenarioService.Request request, Instant now, Instant deadline) {
        var params = params(key, context, request, now);
        List<RequestState> rows = jdbc.query("select * from core.core_scenario_request where request_key=:key", params,
                (rs,n) -> new RequestState(ClockParams.uuid(rs,"claim_id"), ClockParams.instant(rs,"lease_until"),
                        rs.getString("status"), ClockParams.uuid(rs,"result_scenario_id")));
        if (!rows.isEmpty()) {
            RequestState old = rows.getFirst();
            if (old.status().equals("READY")) return new Claim(old.claimId(), require(old.result()).scenario());
            if (old.status().equals("FAILED")) throw CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);
            if (old.until().isAfter(now)) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
        }
        UUID claim = UUID.randomUUID();
        params.addValue("claim", claim).addValue("deadline", ClockParams.timestamptz(deadline));
        jdbc.update("""
                insert into core.core_scenario_request
                (request_key,guardian_id,child_id,session_id,goal_selection_id,kind,previous_scenario_id,
                 claim_id,lease_until,status,result_scenario_id,created_at,updated_at)
                values (:key,:guardian,:child,:session,:goal,:kind,:previous,:claim,:deadline,'GENERATING',null,:now,:now)
                on conflict(request_key) do update set claim_id=:claim,lease_until=:deadline,status='GENERATING',updated_at=:now
                """, params);
        return new Claim(claim, null);
    }

    private MapSqlParameterSource params(String key, SessionContext context, ScenarioService.Request request, Instant now) {
        return ClockParams.at(now).addValue("key", key).addValue("guardian", context.guardianId())
                .addValue("child", context.childId()).addValue("session", request.sessionId())
                .addValue("goal", request.goalSelectionId()).addValue("kind", request.kind())
                .addValue("previous", request.previousScenarioId());
    }

    public Optional<Stored> find(UUID id) {
        return jdbc.query("select * from core.core_scenario where id=:id", new MapSqlParameterSource("id",id),
                (rs,n) -> map(rs)).stream().findFirst();
    }
    public Stored require(UUID id) { return find(id).orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND)); }
    private Stored map(ResultSet rs) throws SQLException {
        Scenario scenario = new Scenario(ClockParams.uuid(rs,"id"), rs.getString("kind"), rs.getString("relation_type"),
                rs.getString("opportunity_condition"),rs.getString("scenario_text"),rs.getString("partner_role_text"),
                rs.getString("trigger_script"),json.read(rs.getString("validation_json"),Scenario.Validation.class));
        return new Stored(scenario, ClockParams.uuid(rs,"guardian_id"),ClockParams.uuid(rs,"child_id"),
                ClockParams.uuid(rs,"session_id"),ClockParams.uuid(rs,"goal_selection_id"),rs.getString("behavior_key"),
                rs.getInt("behavior_version"),rs.getString("status"));
    }
    public boolean seen(UUID child, String fingerprint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from core.core_scenario where child_id=:child and content_fingerprint=:fingerprint)
                """, new MapSqlParameterSource("child",child).addValue("fingerprint",fingerprint), Boolean.class));
    }
    public Scenario save(String key, UUID claim, SessionContext context, GoalSnapshot goal, ScenarioService.Request request,
                         GeneratedScenario output, Instant now) {
        var params = params(key,context,request,now).addValue("claim",claim);
        Integer owns = jdbc.queryForObject("""
                select count(*) from core.core_scenario_request where request_key=:key and claim_id=:claim
                and status='GENERATING' and lease_until > :now
                """, params,Integer.class);
        if (owns == null || owns != 1) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
        Scenario scenario = new Scenario(UUID.randomUUID(),request.kind(),output.relationType(),output.opportunityCondition(),
                ScenarioContentPolicy.childText(output.scenarioText()),ScenarioContentPolicy.PARTNER_ROLE,output.triggerScript(),
                new Scenario.Validation(true,true,false));
        params.addValue("id",scenario.scenarioId()).addValue("behavior",goal.behaviorKey()).addValue("version",goal.behaviorVersion())
                .addValue("relation",scenario.relationType()).addValue("opportunity",scenario.opportunityCondition())
                .addValue("scene",scenario.scenarioText()).addValue("role",scenario.partnerRoleText())
                .addValue("trigger",scenario.triggerScript()).addValue("validation",json.write(scenario.validation()))
                .addValue("fingerprint",ScenarioContentPolicy.fingerprint(output.scenarioText(),output.triggerScript()))
                .addValue("model",output.modelVersion()).addValue("prompt",output.promptVersion());
        jdbc.update("""
                insert into core.core_scenario(id,guardian_id,child_id,session_id,goal_selection_id,behavior_key,behavior_version,
                kind,previous_scenario_id,relation_type,opportunity_condition,scenario_text,partner_role_text,trigger_script,
                validation_json,content_fingerprint,status,model_version,prompt_version,created_at)
                values (:id,:guardian,:child,:session,:goal,:behavior,:version,:kind,:previous,:relation,:opportunity,
                :scene,:role,:trigger,cast(:validation as jsonb),:fingerprint,'ISSUED',:model,:prompt,:now)
                """, params);
        jdbc.update("""
                update core.core_scenario_request set status='READY',result_scenario_id=:id,updated_at=:now
                where request_key=:key and claim_id=:claim
                """,params);
        return scenario;
    }
    public void release(String key, UUID claim, boolean failed, Instant now) {
        jdbc.update("""
                update core.core_scenario_request set status=:status,lease_until=:now,updated_at=:now
                where request_key=:key and claim_id=:claim and status='GENERATING'
                """, ClockParams.at(now).addValue("key",key).addValue("claim",claim)
                .addValue("status",failed ? "FAILED" : "GENERATING"));
    }
    public int expose(UUID id, UUID session, Instant exposedAt) {
        return jdbc.update("""
                update core.core_scenario set status='EXPOSED',exposed_session_id=:session,exposed_at=:now
                where id=:id and status='ISSUED'
                """, ClockParams.at(exposedAt).addValue("id",id).addValue("session",session));
    }
}
