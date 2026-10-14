package kr.mujoong.core.report;
import java.util.*;
import kr.mujoong.core.record.ActivityRecordRepository.StoredRecord;
import kr.mujoong.core.result.*;
/** BR9.8: 한 시도의 읽기 스냅숏. 정리 중 자료의 이전 결과는 포함하지 않는다. */
public record ReportInput(int activityNo,StoredRecord record,String status,ActivityResultRepository.Result result,
        List<SceneJudgmentRepository.Scene> scenes,Map<String,Object> structured,List<Map<String,Object>> definitions){
    public boolean done(){return "DONE".equals(status)&&result!=null;}
    @Override public String toString(){return "ReportInput[activityNo="+activityNo+", status="+status+"]";}
}
