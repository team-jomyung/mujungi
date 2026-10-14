package kr.mujoong.core.result;

import static kr.mujoong.core.common.db.ClockParams.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** BR2.3 · BR4.5: 결과 버전과 후보 재등록 표시를 같은 결과 행에 보관한다. */
@Repository
public class ActivityResultRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ActivityResultRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Result(UUID id,UUID sessionId,int version,UUID jobId,String level,String sentencesJson,
            String helpJson,String tipsJson,String noTipReason,String matchingLogJson,String pendingCandidatesJson,
            Instant candidatesRegisteredAt,Instant createdAt,Instant invalidatedAt) {
        @Override public String toString(){return "Result[id="+id+"]";}
    }
    public Result insert(UUID session,UUID job,String level,String sentences,String help,String tips,String noTipReason,
            String matching,String candidates,Instant now) {
        int version=jdbc.queryForObject("select coalesce(max(version),0)+1 from core.core_activity_result where session_id=:session",
                Map.of("session",session),Integer.class);
        UUID id=UUID.randomUUID();
        var p=at(now).addValue("id",id).addValue("session",session).addValue("job",job).addValue("version",version)
                .addValue("level",level).addValue("sentences",sentences).addValue("help",help).addValue("tips",tips)
                .addValue("matching",matching).addValue("candidates",candidates);
        text(p,"reason",noTipReason);
        jdbc.update("insert into core.core_activity_result(id,session_id,version,job_id,result_level,sentences_json,help_json,tips_json,"
                + "no_tip_reason,matching_log_json,pending_candidates_json,created_at) values(:id,:session,:version,:job,:level,"
                + "cast(:sentences as jsonb),cast(:help as jsonb),cast(:tips as jsonb),:reason,cast(:matching as jsonb),cast(:candidates as jsonb),:now)",p);
        jdbc.update("update core.core_session_result_state set current_result_id=:id,updated_at=:now where session_id=:session and current_job_id=:job",p);
        return find(id).orElseThrow();
    }
    public Optional<Result> find(UUID id){return query("where id=:id",Map.of("id",id)).stream().findFirst();}
    public Optional<Result> current(UUID session){return query("where id=(select current_result_id from core.core_session_result_state where session_id=:session) and invalidated_at is null",Map.of("session",session)).stream().findFirst();}
    public List<Result> pendingCandidates(){return query("where candidates_registered_at is null and invalidated_at is null and exists(select 1 from core.core_session_result_state s where s.current_result_id=core_activity_result.id and s.job_status='DONE') order by created_at limit 100",Map.of());}
    public void candidatesRegistered(UUID id,Instant now){jdbc.update("update core.core_activity_result set candidates_registered_at=:now where id=:id and candidates_registered_at is null",at(now).addValue("id",id));}
    public void invalidate(UUID id,Instant now){jdbc.update("update core.core_activity_result set invalidated_at=:now where id=:id and invalidated_at is null",at(now).addValue("id",id));}
    private List<Result> query(String suffix,Map<String,?> params){return jdbc.query("select * from core.core_activity_result "+suffix,params,(r,n)->new Result(uuid(r,"id"),uuid(r,"session_id"),r.getInt("version"),uuid(r,"job_id"),r.getString("result_level"),r.getString("sentences_json"),r.getString("help_json"),r.getString("tips_json"),r.getString("no_tip_reason"),r.getString("matching_log_json"),r.getString("pending_candidates_json"),instant(r,"candidates_registered_at"),instant(r,"created_at"),instant(r,"invalidated_at")));}
}
