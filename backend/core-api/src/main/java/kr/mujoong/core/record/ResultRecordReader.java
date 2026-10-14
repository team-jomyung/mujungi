package kr.mujoong.core.record;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.ActivityCopyRepository;
import org.springframework.stereotype.Service;
/** 결과 처리용 내부 읽기 포트. 원음 참조와 종료 원문은 읽지 않는다. */
@Service
public class ResultRecordReader {
    private final ActivityRecordRepository records;
    private final ActivityCopyRepository copies;
    private final JsonCodec json;
    public ResultRecordReader(ActivityRecordRepository records,ActivityCopyRepository copies,JsonCodec json){this.records=records;this.copies=copies;this.json=json;}
    public Optional<Input> find(UUID session,UUID guardian){return records.findOwned(session,guardian).map(this::input);}
    public Optional<ActivityRecordRepository.StoredRecord> findForChild(UUID session,UUID child){return records.findForChild(session,child);}
    public List<Input> missing(){return records.missingResultHeads().stream().map(this::input).toList();}
    public List<ActivityRecordRepository.StoredRecord> reportRecords(UUID guardian,UUID child){return records.reportRecords(guardian,child);}
    public Input input(ActivityRecordRepository.StoredRecord r){
        var copy=copies.findOwned(r.sessionId(),r.guardianId()).orElse(null);
        return new Input(r,copy==null?null:copy.status().name(),copy==null?null:copy.failureCode(),
                !json.read(r.transcriptJson(),List.class).isEmpty());
    }
    public record Input(ActivityRecordRepository.StoredRecord record,String copyStatus,String failureCode,boolean hasUtterance){}
}
