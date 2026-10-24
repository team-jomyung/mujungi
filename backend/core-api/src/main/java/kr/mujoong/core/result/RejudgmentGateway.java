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
    /** 기록용 재판정 지시 판. v2는 연습 대화 C안의 인정 규칙(전하는 식 대답 · 할 행동 설명도 수행)을 넣었다(docs/practice-direct-ask.md 6장, FT-1024-b). */
    static final String PROMPT_VERSION = "rejudgment.v2";
    /** 기록용 재판정 지시({@value #PROMPT_VERSION}). 진행 중 판정 J2 · J3 · J5와 같은 인정 규칙을 쓴다. */
    static final String INSTRUCTIONS = "실제로 시작한 각 장면을 기록용으로 재판정한다. 입력 sceneRunId 집합을 정확히 한 번씩 반환한다. "
            + "value는 functional/partial/none/uncertain, functional의 timing은 IMMEDIATE/AFTER_REASK/AFTER_HELP이고 나머지는 null이다. "
            + "단순 재발화 확인만으로 AFTER_REASK를 만들지 않는다. 진행 중 판정 라벨이나 확률을 추측하지 않는다. "
            + "transcript의 AI 줄은 무중의 말이다. 무중은 상황과 상대의 말을 전한 뒤 너라면 뭐라고 말할지 묻고, 필요하면 되묻거나 도움을 준다"
            + "(무중이 장면 속 상대 역할로 말한 기록일 수도 있다). "
            + "아이 대답이 상대에게 직접 하는 말이든, 할 말을 전하는 말(예: 다음에 같이 그리자고 할래)이든, 할 행동을 설명하는 말이든 "
            + "내용이 그 장면 목표의 핵심 기능(targetExpression · fourElements가 가리키는 기능)을 채우면 수행으로 보고 functional로 판정한다. "
            + "말투 · 완성 문장 · 예시와 다른 표현은 수행을 낮추는 이유가 아니다. "
            + "할 말을 정하지 못하고 방법만 묻는 말(예: 뭐라고 말해야 돼?)은 도움 요청이며 수행이 아니다.";
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
            Map<String,Object> row=new LinkedHashMap<>();row.put("sceneRunId",handle);row.put("sceneKind",scene.sceneKind());row.put("facts",scene.facts());row.put("variantSkipReason",scene.variantSkipReason());
            goals.historical(child,scene.goalRef()).ifPresent(goal->{row.put("targetExpression",goal.targetExpression());row.put("fourElements",goal.fourElements());row.put("skillRef",goal.skillRef());});input.add(row);
        }
        access.run();LunaResult response;
        try {
            response = luna.orElseThrow(() -> new ResultAiException("PROVIDER_UNAVAILABLE")).call(LunaRequest.of(LunaPurpose.REJUDGMENT,
                    PROMPT_VERSION, INSTRUCTIONS, Map.of("scenes", input, "transcript", transcript), aliases, deadline));
        }
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
