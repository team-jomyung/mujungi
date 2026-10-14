package kr.mujoong.core.result;
import static kr.mujoong.core.result.ResultData.*;
import java.util.*;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.practice.CandidateRegistry;
import kr.mujoong.core.record.RecordEligibility;
import kr.mujoong.core.record.ResultRecordReader;
import org.springframework.stereotype.Service;
/** BR8: 현재 결과의 공개 칸만 조립한다. 내부 판정·구조화·08 원문은 DTO에 없다. */
@Service
public class ResultQueryService {
    private final ActivityCopyRepository copies;private final ResultRecordReader records;private final SessionResultStateRepository states;
    private final ActivityResultRepository results;private final SceneJudgmentRepository scenes;private final CandidateRegistry candidates;
    public ResultQueryService(ActivityCopyRepository copies,ResultRecordReader records,SessionResultStateRepository states,ActivityResultRepository results,SceneJudgmentRepository scenes,CandidateRegistry candidates){this.copies=copies;this.records=records;this.states=states;this.results=results;this.scenes=scenes;this.candidates=candidates;}
    public record Status(String sessionStatus,boolean confirmed,String jobStatus){}
    public record Sentence(UUID goalSelectionId,String text){}
    public record Scene(int sceneNo,String exposureType,String sceneChip,String chipText,String notDoneReason){}
    public record Goal(UUID goalSelectionId,String displayName,int order,List<Scene> scenes,Map<String,Object> help){}
    public record Practice(List<Goal> goals){}
    public record Result(String jobStatus,String resultLevel,List<Sentence> sentences,Practice practice,List<Map<String,Object>> tips,String noTipReason,List<CandidateRegistry.ResultCandidateView> candidates){@Override public String toString(){return "Result[jobStatus="+jobStatus+"]";}}
    public Status status(UUID guardian,UUID session){
        var copy=copies.findOwned(session,guardian).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
        var head=states.find(session,guardian);if(head.isPresent())return new Status(copy.status().name(),copy.confirmed(),head.get().jobStatus());
        var record=records.find(session,guardian);String status;
        if(record.isPresent()){var input=record.get();status=ResultEligibility.decide(input.record().finalStatus(),input.hasUtterance(),input.copyStatus(),input.failureCode())==ResultEligibility.Decision.NOT_ELIGIBLE?"NONE":"REQUESTED";}
        else status=RecordEligibility.RESULT_END_STATUSES.contains(copy.status().name())||("FAILED".equals(copy.status().name())&&"SERVER_RESTART".equals(copy.failureCode()))?"REQUESTED":"NONE";
        return new Status(copy.status().name(),copy.confirmed(),status);
    }
    public Result result(UUID guardian,UUID session){
        var status=status(guardian,session);var current=results.current(session);
        if(current.isEmpty())return new Result(status.jobStatus(),null,List.of(),null,List.of(),null,List.of());
        var stored=current.get();var sentences=transcript(stored.sentencesJson()).stream().map(s->new Sentence(uuid(s.get("goalSelectionId")),text(s,"text"))).toList();
        Practice practice=null;var record=records.find(session,guardian);
        if(record.isPresent()&&record.get().record().activityType().equals("PRACTICE")){
            var rows=scenes.find(stored.id());var helps=transcript(stored.helpJson());var goals=new ArrayList<Goal>();
            for(var goal:goals(practice(record.get().record().practiceJson()))){UUID id=uuid(goal.get("goalSelectionId"));if(id==null)continue;
                var publicScenes=rows.stream().filter(s->s.goalSelectionId().equals(id)).map(s->new Scene(s.sceneNo(),s.exposureType(),s.sceneChip(),SceneChipMapper.text(s.sceneChip(),s.notDoneReason()),s.notDoneReason())).toList();
                var help=helps.stream().filter(h->id.toString().equals(h.get("goalSelectionId"))).findFirst().map(h->object(h.get("help"))).orElse(Map.of("count",0,"consent","NONE"));
                goals.add(new Goal(id,text(goal,"displayName"),(int)number(goal,"order"),publicScenes,help));
            }
            practice=new Practice(goals);
        }
        return new Result(status.jobStatus(),stored.level(),sentences,practice,transcript(stored.tipsJson()),stored.noTipReason(),candidates.viewForResult(session,guardian));
    }
}
