package kr.mujoong.core.record;
import java.util.Set;
/** BR2.2 · BR14.1: 목록과 결과 접수에서 쓰는 공통 조건. */
public final class RecordEligibility {
    private RecordEligibility(){}
    public static final Set<String> RESULT_END_STATUSES=Set.of("ENDED","ENDED_UNCONFIRMED","ABORTED");
    public static final String RESTART_FAILURE="SERVER_RESTART";
    public static final String LISTED_SQL="(final_status in ('ENDED','ENDED_UNCONFIRMED','ABORTED','DECLINED') or "
        +"(final_status='FAILED' and jsonb_array_length(transcript_json)>0 and exists(select 1 from core.core_activity a "
        +"where a.session_id=core_activity_record.session_id and a.status='FAILED' and a.failure_code='SERVER_RESTART')))";
    public static boolean restartFailed(String finalStatus,String copyStatus,String failureCode,boolean hasUtterance){
        return "FAILED".equals(finalStatus)&&"FAILED".equals(copyStatus)&&RESTART_FAILURE.equals(failureCode)&&hasUtterance;
    }
}
