package kr.mujoong.core.practice;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
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
import kr.mujoong.core.textai.ScenarioScript;
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
                         String behaviorKey, int behaviorVersion, String status, String goalContextJson) {
        public Stored(Scenario scenario, UUID guardianId, UUID childId, UUID sessionId, UUID goalSelectionId,
                String behaviorKey, int behaviorVersion, String status) {
            this(scenario,guardianId,childId,sessionId,goalSelectionId,behaviorKey,behaviorVersion,status,null);
        }
    }
    public record Details(String scriptVersion, List<ScenarioScript.Scene> scenes, Scenario.Review review,
            Scenario.Generation generation, String goalContextJson) {}
    public record ExposureText(String scenarioText, String triggerScript,String skillRef,
            kr.mujoong.core.knowledge.VariantPlanner.Context graphContext) {
        public ExposureText(String scenarioText,String triggerScript){this(scenarioText,triggerScript,null,null);}
        public ExposureText {
            if(scenarioText==null || scenarioText.isBlank() || scenarioText.length()>8000
                    || triggerScript==null || triggerScript.isBlank() || triggerScript.length()>2000)
                throw CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);
        }
        @Override public String toString() { return "ExposureText[redacted]"; }
    }
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
                rs.getString("trigger_script"),json.read(rs.getString("validation_json"),Scenario.Validation.class),
                rs.getString("script_version"), readScenes(rs.getString("scenes_json")),
                readOptional(rs.getString("self_review_json"),Scenario.Review.class),
                readOptional(rs.getString("generation_json"),Scenario.Generation.class));
        return new Stored(scenario, ClockParams.uuid(rs,"guardian_id"),ClockParams.uuid(rs,"child_id"),
                ClockParams.uuid(rs,"session_id"),ClockParams.uuid(rs,"goal_selection_id"),rs.getString("behavior_key"),
                rs.getInt("behavior_version"),rs.getString("status"),rs.getString("goal_context_json"));
    }
    private <T> T readOptional(String value,Class<T> type) { return value==null?null:json.read(value,type); }
    private List<ScenarioScript.Scene> readScenes(String value) {
        return value==null?null:List.of(json.read(value,ScenarioScript.Scene[].class));
    }
    /** R-02: 전용 읽기 트랜잭션의 LOCAL timeout. 공용 JDBC 상태를 변경하지 않는다. */
    public List<ExposureText> recentExposed(UUID guardian,UUID child,Instant startedAt,Duration timeout) {
        if(timeout==null || timeout.isZero() || timeout.isNegative())
            throw CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource()));
        var read=new org.springframework.transaction.support.TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return read.execute(status->{
            jdbc.queryForObject("select set_config('statement_timeout',:timeout,true)",
                    new MapSqlParameterSource("timeout",Math.max(1,timeout.toMillis())+"ms"),String.class);
            var rows=jdbc.query("""
                    select scenario_text,trigger_script,scenes_json,goal_context_json from core.core_scenario
                    where guardian_id=:guardian and child_id=:child and status='EXPOSED'
                    and exposed_at is not null and exposed_at<=:started
                    order by exposed_at desc,id desc limit 3
                    """,new MapSqlParameterSource("guardian",guardian).addValue("child",child)
                    .addValue("started",ClockParams.timestamptz(startedAt)),
                    (rs,n)->exposure(rs));
            if(rows.stream().mapToInt(r->r.scenarioText().length()+r.triggerScript().length()).sum()>30000)
                throw CoreApiException.of(ErrorCode.SCENARIO_GEN_FAILED);
            return List.copyOf(rows);
        });
    }
    private ExposureText exposure(ResultSet rs)throws SQLException {
        var scenes=readScenes(rs.getString("scenes_json"));
        var goal=readOptional(rs.getString("goal_context_json"),GoalContext.class);
        var base=scenes==null||scenes.isEmpty()?null:scenes.getFirst();
        return new ExposureText(rs.getString("scenario_text"),rs.getString("trigger_script"),
                goal==null?null:goal.skillRef(),base==null||base.preservation()==null?null:ScenarioGraphContext.coordinates(base));
    }
    public boolean seen(UUID child, String fingerprint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from core.core_scenario where child_id=:child and content_fingerprint=:fingerprint)
                """, new MapSqlParameterSource("child",child).addValue("fingerprint",fingerprint), Boolean.class));
    }
    /**
     * 발급 대본 한 행의 값. 저장과 교차 계약 시험(contracts/fixtures)이 같은 조립을 쓴다. 레거시 칸은 고지 붙인 본문과 고정 역할 지시이고,
     * 새 흐름 칸(scriptVersion · scenes · selfReview · generation)은 생성 결과를 그대로 싣는다.
     */
    public static Scenario issued(UUID id, String kind, GeneratedScenario output, Details details) {
        return new Scenario(id, kind, output.relationType(), output.opportunityCondition(),
                ScenarioContentPolicy.childText(output.scenarioText()), ScenarioContentPolicy.PARTNER_ROLE, output.triggerScript(),
                new Scenario.Validation(true, true, false),
                details == null ? null : details.scriptVersion(),
                details == null ? null : details.scenes(),
                details == null ? null : details.review(),
                details == null ? null : details.generation());
    }
    public Scenario save(String key, UUID claim, SessionContext context, GoalSnapshot goal, ScenarioService.Request request,
                         GeneratedScenario output, Instant now) {
        return save(key,claim,context,goal,request,output,now,null);
    }
    public Scenario save(String key, UUID claim, SessionContext context, GoalSnapshot goal, ScenarioService.Request request,
                         GeneratedScenario output, Instant now, Details details) {
        return save(key,claim,context,goal,request,output,now,details,null);
    }
    /** 이 트랜잭션에만 잔여 시간을 적용하고 커밋 직전에 새 시계로 검사한다. */
    public void beginDeadline(ScenarioDeadline deadline){
        limitSql(deadline);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void beforeCommit(boolean readOnly){deadline.check();}
                });
    }
    public void limitSql(ScenarioDeadline deadline){
        deadline.check();long millis=deadline.remaining().toMillis();
        if(millis<=0)throw ScenarioDeadline.failed();
        jdbc.queryForList("""
                select set_config(name,(case when setting::bigint=0 then :millis
                else least(setting::bigint,:millis) end)::text||'ms',true)
                from pg_settings where name in ('statement_timeout','lock_timeout')
                """,new MapSqlParameterSource("millis",millis),String.class);
        deadline.check();
    }
    public Scenario save(String key, UUID claim, SessionContext context, GoalSnapshot goal, ScenarioService.Request request,
                         GeneratedScenario output, Instant now, Details details,ScenarioDeadline deadline) {
        if(deadline!=null)limitSql(deadline);
        var params = params(key,context,request,now).addValue("claim",claim);
        Integer owns = jdbc.queryForObject("""
                select count(*) from core.core_scenario_request where request_key=:key and claim_id=:claim
                and status='GENERATING' and lease_until > :now
                """, params,Integer.class);
        if (owns == null || owns != 1) throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY);
        if(deadline!=null)limitSql(deadline);
        Scenario scenario = issued(UUID.randomUUID(), request.kind(), output, details);
        params.addValue("id",scenario.scenarioId()).addValue("behavior",goal.behaviorKey()).addValue("version",goal.behaviorVersion())
                .addValue("relation",scenario.relationType()).addValue("opportunity",scenario.opportunityCondition())
                .addValue("scene",scenario.scenarioText()).addValue("role",scenario.partnerRoleText())
                .addValue("trigger",scenario.triggerScript()).addValue("validation",json.write(scenario.validation()))
                .addValue("fingerprint",ScenarioContentPolicy.fingerprint(output.scenarioText()))
                .addValue("model",output.modelVersion()).addValue("prompt",output.promptVersion())
                .addValue("scriptVersion",scenario.scriptVersion())
                .addValue("scenes",scenario.scenes()==null?null:json.write(scenario.scenes()))
                .addValue("review",scenario.selfReview()==null?null:json.write(scenario.selfReview()))
                .addValue("generation",scenario.generation()==null?null:json.write(scenario.generation()))
                .addValue("goalContext",details==null?null:details.goalContextJson());
        jdbc.update("""
                insert into core.core_scenario(id,guardian_id,child_id,session_id,goal_selection_id,behavior_key,behavior_version,
                kind,previous_scenario_id,relation_type,opportunity_condition,scenario_text,partner_role_text,trigger_script,
                validation_json,content_fingerprint,status,model_version,prompt_version,created_at,
                script_version,scenes_json,self_review_json,generation_json,goal_context_json)
                values (:id,:guardian,:child,:session,:goal,:behavior,:version,:kind,:previous,:relation,:opportunity,
                :scene,:role,:trigger,cast(:validation as jsonb),:fingerprint,'ISSUED',:model,:prompt,:now,
                :scriptVersion,cast(:scenes as jsonb),cast(:review as jsonb),cast(:generation as jsonb),cast(:goalContext as jsonb))
                """, params);
        if(deadline!=null)limitSql(deadline);
        jdbc.update("""
                update core.core_scenario_request set status='READY',result_scenario_id=:id,updated_at=:now
                where request_key=:key and claim_id=:claim
                """,params);
        if(deadline!=null)deadline.check();
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
