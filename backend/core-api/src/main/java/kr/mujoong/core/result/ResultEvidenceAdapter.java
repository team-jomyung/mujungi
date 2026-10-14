package kr.mujoong.core.result;
import java.util.List;
import java.util.UUID;
import kr.mujoong.core.practice.CandidateEvidencePort;
import kr.mujoong.core.record.ResultRecordReader;
import org.springframework.stereotype.Component;
/** BR6.5: 후보 근거를 실제 아이 발화에서 확인한다. 잠금·외부 호출 없음. */
@Component
public class ResultEvidenceAdapter implements CandidateEvidencePort {
    private final ResultRecordReader records;
    public ResultEvidenceAdapter(ResultRecordReader records){this.records=records;}
    public boolean verifies(UUID child,UUID session,List<Evidence> evidence){
        if(child==null||session==null||evidence==null||evidence.isEmpty())return false;
        var record=records.findForChild(session,child).orElse(null);if(record==null)return false;
        var quotes=ResultData.childQuotes(ResultData.transcript(record.transcriptJson()));
        return evidence.stream().allMatch(e->e!=null&&session.equals(e.sessionId())&&"CHILD_RECALL".equals(e.evidenceType())&&quotes.containsKey(e.utteranceSequenceNo()));
    }
}
