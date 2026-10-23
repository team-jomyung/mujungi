package kr.mujoong.core.result;

import static kr.mujoong.core.common.db.ClockParams.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** U6 BR1.1~BR1.12: 호출자 트랜잭션에서 선점·세대 검사·동반 상태 갱신을 수행한다. */
@Repository
public class ResultJobRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ResultJobRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public ResultJob submit(String type, UUID subject, UUID guardian, String key, String trigger,
            Instant deadline, Instant now) {
        var p=at(now).addValue("id",UUID.randomUUID()).addValue("type",type).addValue("subject",subject)
                .addValue("guardian",guardian).addValue("key",key).addValue("trigger",trigger);
        instant(p,"deadline",deadline);
        jdbc.update("insert into core.core_result_job(id,job_type,subject_id,guardian_id,idempotency_key,trigger_type,status,"
                + "next_attempt_at,deadline_at,created_at,updated_at) values(:id,:type,:subject,:guardian,:key,:trigger,"
                + "'REQUESTED',:now,:deadline,:now,:now) on conflict do nothing",p);
        List<ResultJob> found=jdbc.query("select * from core.core_result_job where idempotency_key=:key "
                + "or (subject_id=:subject and status in ('REQUESTED','RUNNING')) order by (idempotency_key=:key) desc",p,ResultJobRepository::map);
        ResultJob job=found.stream().findFirst().orElseThrow(()->new IllegalStateException("RESULT_JOB_MISSING"));
        if (job.idempotencyKey().equals(key) && (!job.subjectId().equals(subject)||!job.jobType().equals(type)
                ||!job.guardianId().equals(guardian))) throw new IllegalStateException("RESULT_JOB_KEY_CONFLICT");
        return job;
    }

    public Optional<ResultJob> find(UUID id) {
        return jdbc.query("select * from core.core_result_job where id=:id",Map.of("id",id),ResultJobRepository::map).stream().findFirst();
    }

    public Optional<ResultJob> lock(UUID id) {
        return jdbc.query("select * from core.core_result_job where id=:id for update",Map.of("id",id),ResultJobRepository::map).stream().findFirst();
    }

    public List<ResultJob> claim(String type, int limit, Instant now, Duration timeout) {
        var p=at(now).addValue("type",type).addValue("limit",limit);
        instant(p,"lease",now.plus(timeout));
        List<ResultJob> jobs=jdbc.query("with picked as (select id from core.core_result_job "
                + "where job_type=:type and status='REQUESTED' and next_attempt_at<=:now "
                + "and (deadline_at is null or deadline_at>:now) order by next_attempt_at,id limit :limit for update skip locked) "
                + "update core.core_result_job j set status='RUNNING',attempt_count=attempt_count+1,updated_at=:now,"
                + "lease_until=coalesce(deadline_at,:lease) from picked where j.id=picked.id returning j.*",p,ResultJobRepository::map);
        for(ResultJob job:jobs) {
            jdbc.update("insert into core.core_result_job_attempt(id,job_id,attempt_no,started_at) values(:id,:job,:n,:now)",
                    at(now).addValue("id",UUID.randomUUID()).addValue("job",job.id()).addValue("n",job.attemptCount()));
            synchronize(job,"RUNNING",now);
        }
        return jobs;
    }

    public boolean transition(ResultJob job, String target, String code, Instant next, Instant now, String outcome) {
        var p=at(now).addValue("id",job.id()).addValue("attempt",job.attemptCount()).addValue("target",target);
        text(p,"code",code); instant(p,"next",next);
        int n=jdbc.update("update core.core_result_job set status=:target,last_error_code=:code,updated_at=:now,"
                + "next_attempt_at=coalesce(:next,next_attempt_at),lease_until=null,"
                + "finished_at=case when :target in ('DONE','FAILED') then :now else null end "
                + "where id=:id and status='RUNNING' and attempt_count=:attempt",p);
        if(n==0)return false;
        p.addValue("outcome",outcome);
        jdbc.update("update core.core_result_job_attempt set ended_at=:now,outcome=:outcome,error_code=:code "
                + "where job_id=:id and attempt_no=:attempt and outcome is null",p);
        synchronize(job,target,now);
        return true;
    }

    /**
     * 처리기가 미룬 시도를 REQUESTED로 되돌리고 시도 횟수에서 돌려준다(그 시도 기록도 지운다). 리포트가 결과 정리를 기다리는
     * 동안 시도 횟수 상한(4회)을 쓰지 않게 한다(contracts/deviations.md FT-1023-c).
     */
    public boolean release(ResultJob job, Instant next, Instant now) {
        var p=at(now).addValue("id",job.id()).addValue("attempt",job.attemptCount());
        instant(p,"next",next);
        int n=jdbc.update("update core.core_result_job set status='REQUESTED',attempt_count=attempt_count-1,next_attempt_at=:next,"
                + "lease_until=null,updated_at=:now where id=:id and status='RUNNING' and attempt_count=:attempt",p);
        if(n==0)return false;
        jdbc.update("delete from core.core_result_job_attempt where job_id=:id and attempt_no=:attempt",p);
        synchronize(job,"REQUESTED",now);
        return true;
    }

    public List<ResultJob> unfinished() {
        return jdbc.query("select * from core.core_result_job where status in ('RUNNING','REQUESTED')",
                Map.of(),ResultJobRepository::map);
    }
    public List<ResultJob> expired(Instant now) {
        return jdbc.query("select * from core.core_result_job where status='RUNNING' and lease_until<=:now",at(now),ResultJobRepository::map);
    }
    public int startupCheck(Instant now) {
        int count=0;
        for(ResultJob job:unfinished())if(job.status().equals("RUNNING")&&transition(job,"FAILED","SERVER_RESTART",null,now,"ABANDONED"))count++;
        return count;
    }

    public void expireRequested(ResultJob job, Instant now) {
        int n=jdbc.update("update core.core_result_job set status='FAILED',last_error_code='DEADLINE_EXCEEDED',"
                + "finished_at=:now,updated_at=:now where id=:id and status='REQUESTED' and deadline_at<=:now",
                at(now).addValue("id",job.id()));
        if(n==1)synchronize(job,"FAILED",now);
    }

    private void synchronize(ResultJob job,String status,Instant now) {
        var p=at(now).addValue("id",job.id()).addValue("status",status).addValue("subject",job.subjectId());
        if(job.jobType().equals("SESSION_RESULT")) {
            jdbc.update("update core.core_session_result_state set job_status=:status,updated_at=:now where current_job_id=:id",p);
        } else if(status.equals("DONE")||status.equals("FAILED")) {
            p.addValue("reportStatus",status.equals("DONE")?"READY":"FAILED");
            jdbc.update("update core.core_report set status=:reportStatus,ready_at=case when :status='DONE' then :now else ready_at end "
                    + "where id=:subject and job_id=:id and status='GENERATING'",p);
        }
    }

    private static ResultJob map(ResultSet r,int n)throws SQLException {
        return new ResultJob(uuid(r,"id"),r.getString("job_type"),uuid(r,"subject_id"),uuid(r,"guardian_id"),
                r.getString("idempotency_key"),r.getString("status"),r.getInt("attempt_count"),instant(r,"next_attempt_at"),
                instant(r,"lease_until"),instant(r,"deadline_at"),r.getString("last_error_code"));
    }
}
