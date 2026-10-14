package kr.mujoong.core.result;
import java.util.*;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** BR13: 보호자 → 작업 잠금 뒤 U6 하위 행을 지우며 되풀이해도 안전하다. */
@Component @Order(DeletionParticipant.ORDER_RESULT)
public class ResultDeletionParticipant implements DeletionParticipant {
    private final NamedParameterJdbcTemplate jdbc;private final GuardianWriteLock guardians;private final TransactionTemplate tx;
    public ResultDeletionParticipant(NamedParameterJdbcTemplate jdbc,GuardianWriteLock guardians,PlatformTransactionManager transactions){this.jdbc=jdbc;this.guardians=guardians;tx=new TransactionTemplate(transactions);}
    public Map<String,Integer> deleteByGuardian(UUID guardian,UUID request){Objects.requireNonNull(request);return tx.execute(t->{
        guardians.statusForUpdate(guardian);Map<String,UUID> p=Map.of("guardian",guardian);Map<String,Integer> counts=new LinkedHashMap<>();
        jdbc.queryForList("select id from core.core_result_job where guardian_id=:guardian order by id for update",p,UUID.class);
        for(String table:List.of("report_citation","report_section","report_activity"))counts.put(table,jdbc.update("delete from core.core_"+table+" where report_id in(select id from core.core_report where guardian_id=:guardian)",p));
        counts.put("report",jdbc.update("delete from core.core_report where guardian_id=:guardian",p));
        for(String table:List.of("scene_judgment","structured_experience"))counts.put(table,jdbc.update("delete from core.core_"+table+" where result_id in(select r.id from core.core_activity_result r join core.core_result_job j on j.id=r.job_id where j.guardian_id=:guardian)",p));
        // 현재 결과 FK를 가진 머리를 결과보다 먼저 지운다.
        counts.put("session_result_state",jdbc.update("delete from core.core_session_result_state where guardian_id=:guardian",p));
        counts.put("activity_result",jdbc.update("delete from core.core_activity_result where job_id in(select id from core.core_result_job where guardian_id=:guardian)",p));
        counts.put("result_job_attempt",jdbc.update("delete from core.core_result_job_attempt where job_id in(select id from core.core_result_job where guardian_id=:guardian)",p));
        counts.put("result_job",jdbc.update("delete from core.core_result_job where guardian_id=:guardian",p));
        return Map.copyOf(counts);
    });}
}
