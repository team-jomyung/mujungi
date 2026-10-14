package kr.mujoong.core.result;
import static kr.mujoong.core.common.db.ClockParams.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
/** BR4.1~BR4.4: 내부 판정과 보호자용 장면 칩을 분리해 저장한다. */
@Repository
public class SceneJudgmentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public SceneJudgmentRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Scene(UUID goalSelectionId,int goalOrder,int sceneNo,String exposureType,UUID scenarioId,
            String value,String timing,String rationale,String liveLabel,boolean differsFromLive,String notDoneReason,String sceneChip){}
    public void insert(UUID result,UUID session,Scene s,Instant now){
        var p=at(now).addValue("id",UUID.randomUUID()).addValue("result",result).addValue("session",session)
            .addValue("goal",s.goalSelectionId()).addValue("order",s.goalOrder()).addValue("scene",s.sceneNo())
            .addValue("exposure",s.exposureType()).addValue("differs",s.differsFromLive()).addValue("chip",s.sceneChip());
        uuid(p,"scenario",s.scenarioId());text(p,"value",s.value());text(p,"timing",s.timing());text(p,"rationale",s.rationale());text(p,"live",s.liveLabel());text(p,"reason",s.notDoneReason());
        jdbc.update("insert into core.core_scene_judgment(id,result_id,session_id,goal_selection_id,goal_order,scene_no,exposure_type,scenario_id,value,timing,rationale,live_label,differs_from_live,not_done_reason,scene_chip,created_at) values(:id,:result,:session,:goal,:order,:scene,:exposure,:scenario,:value,:timing,:rationale,:live,:differs,:reason,:chip,:now)",p);
    }
    public List<Scene> find(UUID result){return jdbc.query("select * from core.core_scene_judgment where result_id=:id order by goal_order,scene_no",Map.of("id",result),(r,n)->new Scene(uuid(r,"goal_selection_id"),r.getInt("goal_order"),r.getInt("scene_no"),r.getString("exposure_type"),uuid(r,"scenario_id"),r.getString("value"),r.getString("timing"),r.getString("rationale"),r.getString("live_label"),r.getBoolean("differs_from_live"),r.getString("not_done_reason"),r.getString("scene_chip")));}
}
