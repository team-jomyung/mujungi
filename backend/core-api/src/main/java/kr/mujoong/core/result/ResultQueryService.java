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
    private kr.mujoong.core.practicegoal.GoalCandidateRepository generated;
    private kr.mujoong.core.consent.ConsentPolicyService goalConsent;
    @org.springframework.beans.factory.annotation.Autowired
    void generatedGoals(kr.mujoong.core.practicegoal.GoalCandidateRepository repository,kr.mujoong.core.consent.ConsentPolicyService consent){generated=repository;goalConsent=consent;}
    public ResultQueryService(ActivityCopyRepository copies,ResultRecordReader records,SessionResultStateRepository states,ActivityResultRepository results,SceneJudgmentRepository scenes,CandidateRegistry candidates){this.copies=copies;this.records=records;this.states=states;this.results=results;this.scenes=scenes;this.candidates=candidates;}
    public record Status(String sessionStatus,boolean confirmed,String jobStatus){}
    public record Sentence(UUID goalSelectionId,String text){}
    public record Scene(int sceneNo,String exposureType,String sceneChip,String chipText,String notDoneReason){}
    public record TimedScene(int sceneNo,String exposureType,String sceneChip,String chipText,String notDoneReason,String timing){}
    public record GeneratedCandidate(UUID recommendationId,String displayName,String displayEvidence,List<CandidateRegistry.Evidence> evidence,boolean selected,UUID goalSelectionId){}
    public record Goal(UUID goalSelectionId,String displayName,int order,List<?> scenes,Map<String,Object> help){}
    public record Practice(List<Goal> goals){}
    public record Result(String jobStatus,String resultLevel,List<Sentence> sentences,Practice practice,List<Map<String,Object>> tips,String noTipReason,List<?> candidates){@Override public String toString(){return "Result[jobStatus="+jobStatus+"]";}}
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
                var publicScenes=rows.stream().filter(s->s.goalSelectionId().equals(id)).map(s->PracticeRecordV2.VERSION.equals(s.flowVersion())?(Object)new TimedScene(s.sceneNo(),s.exposureType(),s.sceneChip(),SceneReportMapper.publicText(s),s.notDoneReason(),s.timing()):new Scene(s.sceneNo(),s.exposureType(),s.sceneChip(),SceneReportMapper.publicText(s),s.notDoneReason())).toList();
                var help=helps.stream().filter(h->id.toString().equals(h.get("goalSelectionId"))).findFirst().map(h->object(h.get("help"))).orElse(Map.of("count",0,"consent","NONE"));
                String name=text(goal,"displayName");if(generated!=null&&PracticeRecordV2.VERSION.equals(goal.get("flowVersion")))name=generated.selection(guardian,id).filter(s->s.childId().equals(record.get().record().childId())).map(s->s.displayName()).orElse(name);
                goals.add(new Goal(id,name,(int)number(goal,"order"),publicScenes,help));
            }
            practice=new Practice(goals);
        }
        List<?> publicCandidates=candidates.viewForResult(session,guardian);
        if(generated!=null&&record.isPresent()&&kr.mujoong.contract.json.JsonCodec.shared().read(stored.pendingCandidatesJson(),Object.class) instanceof Map<?,?>){
            UUID child=record.get().record().childId();publicCandidates=goalConsent!=null&&!goalConsent.check(guardian).ok()?List.of():generated.candidates(child,session).stream().filter(v->generatedSource(v,stored.id())).map(v->{var selected=generated.activeSelection(child,v.candidateId());return new GeneratedCandidate(v.id(),v.content().displayName(),v.content().displayEvidence(),v.content().evidence().stream().map(e->new CandidateRegistry.Evidence(e.utteranceSeq(),"CHILD_RECALL")).toList(),selected.isPresent(),selected.map(s->s.id()).orElse(null));}).toList();
        }
        return new Result(status.jobStatus(),stored.level(),sentences,practice,transcript(stored.tipsJson()),stored.noTipReason(),publicCandidates);
    }
    private boolean generatedSource(kr.mujoong.core.practicegoal.GoalCandidateRepository.Version version,UUID result){return generated.fromResult(version.candidateId(),result);}
}
