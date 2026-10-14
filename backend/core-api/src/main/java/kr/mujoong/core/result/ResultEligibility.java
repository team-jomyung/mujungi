package kr.mujoong.core.result;
import kr.mujoong.core.record.RecordEligibility;
/** BR2.2: 접수·조회가 공유하는 순수 판정. */
public final class ResultEligibility {
    private ResultEligibility(){}
    public enum Decision { ELIGIBLE,UNDECIDED,NOT_ELIGIBLE }
    public static Decision decide(String finalStatus,boolean hasUtterance,String copyStatus,String failureCode){
        if(!hasUtterance)return Decision.NOT_ELIGIBLE;
        if(RecordEligibility.RESULT_END_STATUSES.contains(finalStatus))return Decision.ELIGIBLE;
        if("FAILED".equals(finalStatus)){
            if(!"FAILED".equals(copyStatus))return Decision.UNDECIDED;
            if(RecordEligibility.restartFailed(finalStatus,copyStatus,failureCode,true))return Decision.ELIGIBLE;
        }
        return Decision.NOT_ELIGIBLE;
    }
}
