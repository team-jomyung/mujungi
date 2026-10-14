package kr.mujoong.core.result;
import java.util.*;
import kr.mujoong.core.record.ActivityRecordRepository.StoredRecord;
/** BR5.5: 결과 수준은 공급자와 무관하게 서버에서 계산한다. */
public final class ResultLevelCalculator {
    private ResultLevelCalculator(){}
    public static String before(StoredRecord record,List<Map<String,Object>> transcript,Map<String,Object> practice,boolean consent){
        if(!consent||"CONSENT_WITHDRAWN".equals(record.endReason()))return "INSUFFICIENT";
        boolean valid=record.activityType().equals("FREE_TALK")?transcript.stream().filter(ResultData::meaningful).count()>=3
                :ResultData.goals(practice).stream().anyMatch(ResultLevelCalculator::firstResponse);
        return valid?"PARTIAL":"INSUFFICIENT";
    }
    public static String after(StoredRecord record,Map<String,Object> practice,Map<String,Object> structured){
        boolean normal=record.finalStatus().equals("ENDED")&&record.confirmed()&&record.endReason()!=null&&Set.of("CHILD_STOP","GUARDIAN_STOP","SILENCE").contains(record.endReason());
        boolean enough=record.activityType().equals("FREE_TALK")?structured.values().stream().flatMap(value->value instanceof Map<?,?>?java.util.stream.Stream.of(ResultData.object(value)):ResultData.objects(value).stream()).anyMatch(v->"CHILD_RECALL".equals(v.get("source")))
                :!ResultData.goals(practice).isEmpty()&&ResultData.goals(practice).stream().allMatch(ResultLevelCalculator::firstResponse);
        return normal&&enough?"FULL":"PARTIAL";
    }
    private static boolean firstResponse(Map<String,Object> goal){return ResultData.objects(goal.get("responses")).stream().anyMatch(r->Set.of("FIRST","NEW_SCENARIO").contains(ResultData.text(r,"responseType"))&&ResultData.number(r,"utteranceSeq")>0);}
}
