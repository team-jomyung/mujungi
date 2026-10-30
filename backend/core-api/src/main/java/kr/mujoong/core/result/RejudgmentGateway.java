package kr.mujoong.core.result;

import static kr.mujoong.core.result.ResultData.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.practicegoal.PinnedSavedGoalReader;
import org.springframework.stereotype.Service;

/** One job-attempt call covers exactly the actually started scenes. */
@Service
public class RejudgmentGateway {
    private final Optional<CoreLunaClient> luna;
    private final PinnedSavedGoalReader goals;
    private final JsonCodec json=JsonCodec.shared();
    public RejudgmentGateway(Optional<CoreLunaClient> luna,PinnedSavedGoalReader goals){this.luna=luna;this.goals=goals;}
    Optional<String> targetExpression(UUID child,UUID selection){return goals.historical(child,selection).map(goal->goal.targetExpression());}
    public List<SceneJudgmentRepository.Scene> judge(UUID child,Map<String,Object> practice,List<Map<String,Object>> transcript,Map<String,String> aliases,Instant deadline,Runnable access){
        var scenes=PracticeRecordV2.read(practice);if(scenes.isEmpty())return List.of();
        Map<String,PracticeRecordV2.Scene> handles=new LinkedHashMap<>();List<Map<String,Object>> input=new ArrayList<>();
        for(var scene:scenes){
            if(goals.belongsToAnotherChild(child,scene.goalRef()))throw new ResultAiException("SCHEMA_INVALID");
            // Ephemeral UUID handles satisfy the schema without exposing stored scene/selection identifiers.
            String handle=UUID.randomUUID().toString();handles.put(handle,scene);
            Map<String,Object> row=new LinkedHashMap<>();row.put("sceneRunId",handle);row.put("sceneKind",scene.sceneKind());row.put("startedAt",scene.startedAt().toString());if(scene.endedAt()!=null)row.put("endedAt",scene.endedAt().toString());row.put("facts",scene.facts());row.put("variantSkipReason",scene.variantSkipReason());
            goals.historical(child,scene.goalRef()).ifPresent(goal->{row.put("targetExpression",goal.targetExpression());row.put("fourElements",goal.fourElements());row.put("skillRef",goal.skillRef());});input.add(row);
        }
        access.run();LunaResult response;
        transcript=PracticeEvidenceTranscript.forAnalysis(practice,transcript);
        try{response=luna.orElseThrow(()->new ResultAiException("PROVIDER_UNAVAILABLE")).call(LunaRequest.of(LunaPurpose.REJUDGMENT,"rejudgment.v2","실제로 시작한 각 장면을 기록용으로 재판정한다. 입력 sceneRunId 집합을 정확히 한 번씩 반환한다. value는 functional/partial/none/uncertain, functional의 timing은 IMMEDIATE/AFTER_REASK/AFTER_HELP이고 나머지는 null이다. 단순 재발화 확인만으로 AFTER_REASK를 만들지 않는다. 진행 중 판정 라벨이나 확률을 추측하지 않는다. 각 장면의 startedAt~endedAt 안에 있는 아이 발화로만 판단한다. 해당 장면의 아이 발화가 없거나 해석할 수 없으면 uncertain이며 timing은 null이다. PREPRACTICE_PROVIDED와 PREPRACTICE_ATTEMPTED는 사전 표현 연습의 사실이다. 사전 연습에서 한 말은 상황극 수행 근거에서 제외한다. PREPRACTICE_PROVIDED 장면의 IMMEDIATE는 사전 연습 후 장면 첫 반응을 뜻하며, 평소 독립 능력이나 장기 향상이 아니다. 사전 연습만으로 장면 안 도움인 AFTER_HELP를 만들지 않는다.",Map.of("scenes",input,"transcript",transcript),aliases,deadline));}
        catch(LunaException failed){throw new ResultAiException(failed.reason()==LunaFailureReason.TIMEOUT?"TIMEOUT":"PROVIDER_ERROR");}
        return validated(object(json.read(response.outputJson(),Map.class)),handles);
    }
    static List<SceneJudgmentRepository.Scene> validated(Map<String,Object> output,Map<String,PracticeRecordV2.Scene> handles){
        if(!(output.get("scenes") instanceof List<?> raw)||raw.size()!=handles.size())invalid();
        var seen=new HashSet<String>();var result=new ArrayList<SceneJudgmentRepository.Scene>();var orders=new LinkedHashMap<UUID,Integer>();
        for(var input:handles.values())orders.computeIfAbsent(input.goalRef(),key->orders.size()+1);
        for(var row:objects(output.get("scenes"))){
            String id=text(row,"sceneRunId"),value=text(row,"value");var input=handles.get(id);
            if(input==null||!seen.add(id)||!Set.of("functional","partial","none","uncertain").contains(value))invalid();
            String timing=row.get("timing")==null?null:text(row,"timing");
            if("functional".equals(value)?timing==null||!Set.of("IMMEDIATE","AFTER_REASK","AFTER_HELP").contains(timing):timing!=null)invalid();
            boolean differs=differs(input.liveTiming(),timing);
            int order=orders.computeIfAbsent(input.goalRef(),key->orders.size()+1);
            result.add(new SceneJudgmentRepository.Scene(input.goalRef(),order,input.sceneNo(),"BASE".equals(input.sceneKind())?"ORIGINAL":"NEW_SCENARIO",input.scenarioRef(),value,timing,null,null,differs,null,SceneChipMapper.chip(value,timing),input.sceneRunId(),PracticeRecordV2.VERSION,input.facts(),input.variantSkipReason()));
        }
        if(seen.size()!=handles.size())invalid();
        return result.stream().sorted(Comparator.comparingInt(SceneJudgmentRepository.Scene::goalOrder).thenComparingInt(SceneJudgmentRepository.Scene::sceneNo)).toList();
    }
    static boolean differs(String live,String judged){if(live==null||"ABSTAINED".equals(live))return false;return !Objects.equals("NONE".equals(live)?null:live,judged);}
    private static void invalid(){throw new ResultAiException("SCHEMA_INVALID");}
}
