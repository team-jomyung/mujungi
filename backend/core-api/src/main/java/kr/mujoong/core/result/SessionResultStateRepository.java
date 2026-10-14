package kr.mujoong.core.result;

import static kr.mujoong.core.common.db.ClockParams.*;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** U6 BR2: 세션당 한 머리 행과 실패 상태의 원자적 재시도. */
@Repository
public class SessionResultStateRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public SessionResultStateRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean insert(UUID session,UUID guardian,UUID child,String type,boolean eligible,Instant now){
        return jdbc.update("insert into core.core_session_result_state(session_id,guardian_id,child_id,activity_type,eligibility,"
                + "job_status,run_ordinal,created_at,updated_at) values(:s,:g,:c,:type,:eligible,:status,:ordinal,:now,:now) "
                + "on conflict(session_id) do nothing",at(now).addValue("s",session).addValue("g",guardian).addValue("c",child)
                .addValue("type",type).addValue("eligible",eligible?"ELIGIBLE":"NOT_ELIGIBLE")
                .addValue("status",eligible?"REQUESTED":"NONE").addValue("ordinal",eligible?1:0))==1;
    }
    public Optional<State> find(UUID session,UUID guardian){return select(session,guardian,false);}
    public Optional<State> lock(UUID session,UUID guardian){return select(session,guardian,true);}
    private Optional<State> select(UUID session,UUID guardian,boolean lock){
        return jdbc.query("select * from core.core_session_result_state where session_id=:s and guardian_id=:g"
                +(lock?" for update":""),Map.of("s",session,"g",guardian),(r,n)->new State(uuid(r,"session_id"),
                        uuid(r,"guardian_id"),uuid(r,"child_id"),r.getString("activity_type"),r.getString("job_status"),
                        uuid(r,"current_job_id"),uuid(r,"current_result_id"),r.getInt("run_ordinal"))).stream().findFirst();
    }
    public void attach(UUID session,UUID job,int ordinal,Instant now){
        jdbc.update("update core.core_session_result_state set current_job_id=:job,job_status='REQUESTED',"
                +"run_ordinal=:ordinal,updated_at=:now where session_id=:s",at(now).addValue("s",session)
                .addValue("job",job).addValue("ordinal",ordinal));
    }
    public boolean retry(UUID session,UUID job,int ordinal,Instant now){
        return jdbc.update("update core.core_session_result_state set current_job_id=:job,job_status='REQUESTED',run_ordinal=:ordinal,updated_at=:now where session_id=:s and job_status='FAILED' and run_ordinal=:previous",
                at(now).addValue("s",session).addValue("job",job).addValue("ordinal",ordinal).addValue("previous",ordinal-1))==1;
    }
    public record State(UUID sessionId,UUID guardianId,UUID childId,String activityType,String jobStatus,
            UUID currentJobId,UUID currentResultId,int runOrdinal){}
}
