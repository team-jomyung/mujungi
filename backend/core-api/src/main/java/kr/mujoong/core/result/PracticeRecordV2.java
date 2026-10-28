package kr.mujoong.core.result;

import static kr.mujoong.core.result.ResultData.*;
import java.time.Instant;
import java.util.*;

/** Explicit flowVersion adapter. Unknown facts are ignored, never invented as failed scenes. */
public final class PracticeRecordV2 {
    public static final String VERSION="practice-conversation-v1";
    private static final Set<String> FACTS=Set.of("REASK_RESOLVED","SITUATION_EXPLAINED","FOCUS_HELP","EXPRESSION_EXAMPLE","HELP_ACCEPTED","HELP_DECLINED","COACH_SWITCH","COACH_RETURN","ORIGINAL_SCENE_RETURN","VARIANT_PLAYED","VARIANT_SKIPPED","END_REQUEST","TIMEOUT","TRANSCRIPT_FAILED","PLAYBACK_FAILED","PREPRACTICE_PROVIDED","PREPRACTICE_ATTEMPTED");
    public record Scene(UUID sceneRunId,UUID goalRef,UUID scenarioRef,String sceneKind,Instant startedAt,String liveTiming,List<String> facts,String variantSkipReason,int sceneNo,Instant endedAt){
        public Scene(UUID sceneRunId,UUID goalRef,UUID scenarioRef,String sceneKind,Instant startedAt,String liveTiming,List<String> facts,String variantSkipReason,int sceneNo){
            this(sceneRunId,goalRef,scenarioRef,sceneKind,startedAt,liveTiming,facts,variantSkipReason,sceneNo,null);
        }
    }
    private PracticeRecordV2(){}
    public static boolean present(Map<String,Object> practice){return practice.containsKey("flowVersion");}
    public static List<Scene> read(Map<String,Object> practice){
        if(!VERSION.equals(practice.get("flowVersion"))||!(practice.get("scenes") instanceof List<?>))invalid();
        if(objects(practice.get("scenes")).size()!=((List<?>)practice.get("scenes")).size())invalid();
        List<Scene> result=new ArrayList<>();Set<UUID> seen=new HashSet<>();Map<UUID,Integer> numbers=new HashMap<>();
        for(var row:objects(practice.get("scenes"))){
            UUID id=uuid(row.get("sceneRunId")),goal=uuid(row.get("goalRef"));
            if(id==null||goal==null||!seen.add(id)||!Set.of("BASE","VARIANT").contains(text(row,"sceneKind")))invalid();
            String started=text(row,"startedAt");if(started.isBlank())continue;
            Instant at;try{at=Instant.parse(started);}catch(java.time.DateTimeException bad){throw new ResultAiException("SCHEMA_INVALID");}
            Instant ended=null;
            if(row.get("endedAt")!=null){
                try{ended=Instant.parse(text(row,"endedAt"));}catch(java.time.DateTimeException bad){throw new ResultAiException("SCHEMA_INVALID");}
                if(ended.isBefore(at))invalid();
            }
            String live=text(object(row.get("liveOutcome")),"timing");
            if(!live.isBlank()&&!Set.of("IMMEDIATE","AFTER_REASK","AFTER_HELP","NONE","ABSTAINED").contains(live))invalid();
            Object raw=row.get("facts");if(raw!=null&&!(raw instanceof List<?>))invalid();
            List<String> facts=raw instanceof List<?> list?list.stream().filter(String.class::isInstance).map(String.class::cast).filter(FACTS::contains).distinct().toList():List.of();
            String skip=text(row,"variantSkipReason");if(!skip.isBlank()&&!Set.of("NO_VARIANT","DECLINED","TIME_LIMIT","FOCUS_LIMIT","END_OR_REST").contains(skip))invalid();
            UUID scenario=uuid(row.get("scenarioRef"));if(row.get("scenarioRef")!=null&&scenario==null)invalid();
            result.add(new Scene(id,goal,scenario,text(row,"sceneKind"),at,live.isBlank()?null:live,facts,skip.isBlank()?null:skip,numbers.merge(goal,1,Integer::sum),ended));
        }
        return List.copyOf(result);
    }
    public static List<Map<String,Object>> goals(Map<String,Object> practice){
        Map<UUID,List<Scene>> grouped=new LinkedHashMap<>();for(var scene:read(practice))grouped.computeIfAbsent(scene.goalRef(),id->new ArrayList<>()).add(scene);
        List<Map<String,Object>> goals=new ArrayList<>();
        for(var entry:grouped.entrySet()){
            List<Map<String,Object>> cues=new ArrayList<>();
            for(var scene:entry.getValue()){
                int help=(scene.facts().contains("FOCUS_HELP")?1:0)+(scene.facts().contains("EXPRESSION_EXAMPLE")?1:0);
                for(int i=0;i<help;i++)cues.add(Map.of("givenAt",scene.startedAt().toString(),"consentResponse",scene.facts().contains("HELP_ACCEPTED")?"ACCEPTED":scene.facts().contains("HELP_DECLINED")?"DECLINED":"NO_RESPONSE"));
            }
            Map<String,Object> goal=new LinkedHashMap<>();goal.put("goalSelectionId",entry.getKey().toString());goal.put("displayName","전달");goal.put("order",goals.size()+1);goal.put("flowVersion",VERSION);goal.put("scenes",entry.getValue());goal.put("cues",cues);goals.add(goal);
        }
        return List.copyOf(goals);
    }
    private static void invalid(){throw new ResultAiException("SCHEMA_INVALID");}
}
