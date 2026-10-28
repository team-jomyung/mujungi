package kr.mujoong.core.practice;

import java.util.*;
import kr.mujoong.core.result.ResultData;

/** Same-child, same-goal history projection. No identifiers, judgment labels, or parent notes reach generation. */
public final class PracticeContinuity {
    private PracticeContinuity() {}
    public record Context(List<Map<String,Object>> supportHistory,List<String> childEvidence) {
        public Context {supportHistory=List.copyOf(supportHistory);childEvidence=List.copyOf(childEvidence);}
        @Override public String toString(){return "PracticeContinuity[redacted]";}
    }
    static Map<String,Object> support(Map<String,Object> scene,Map<String,Object> setting) {
        var allowed=Set.of("REASK_RESOLVED","SITUATION_EXPLAINED","FOCUS_HELP","EXPRESSION_EXAMPLE","HELP_DECLINED",
                "COACH_RETURN","ORIGINAL_SCENE_RETURN","TIMEOUT","TRANSCRIPT_FAILED","PLAYBACK_FAILED","PREPRACTICE_PROVIDED","PREPRACTICE_ATTEMPTED");
        var facts=scene.get("facts") instanceof List<?> values ? values.stream().filter(String.class::isInstance)
                .map(String.class::cast).filter(allowed::contains).distinct().toList():List.<String>of();
        String strategy=facts.contains("PLAYBACK_FAILED")||facts.contains("TRANSCRIPT_FAILED") ? "CHECK_INPUT_OR_PLAYBACK"
                : facts.contains("SITUATION_EXPLAINED") ? "SIMPLER_CONTEXT_SAME_GOAL"
                : facts.contains("FOCUS_HELP") ? "FAMILIAR_CONTEXT_HELP_ONLY_IF_NEEDED"
                : facts.contains("REASK_RESOLVED") ? "CONTEXTUAL_CLARIFICATION_WITHOUT_DRILL"
                : facts.contains("PREPRACTICE_PROVIDED") ? "APPLY_AFTER_PREPRACTICE_NOT_BASELINE"
                : "START_WITH_ROLE_DIALOGUE_NO_EXTRA_HELP";
        var result=new LinkedHashMap<String,Object>();result.put("observedSupport",facts);result.put("nextRoundStrategy",strategy);
        // Technical failures are not evidence of inability. Timing remains a performed-action observation, not a score.
        if(!facts.contains("PLAYBACK_FAILED")&&!facts.contains("TRANSCRIPT_FAILED")) {
            String timing=ResultData.text(ResultData.object(scene.get("liveOutcome")),"timing");
            if(Set.of("IMMEDIATE","AFTER_REASK","AFTER_HELP","NONE","ABSTAINED").contains(timing))result.put("observedTiming",timing);
        }
        var coordinates=new LinkedHashMap<String,Object>();
        for(String key:List.of("place","topic","relation")) {
            String value=ResultData.text(setting,key);if(!value.isBlank())coordinates.put(key,value.substring(0,Math.min(200,value.length())));
        }
        result.put("previousSetting",coordinates);return Map.copyOf(result);
    }
    static List<String> verifiedChildEvidence(List<Map<String,Object>> evidence,List<Map<String,Object>> transcript) {
        var result=new ArrayList<String>();
        for(var item:evidence) {
            long seq=ResultData.number(item,"utteranceSeq");String quote=ResultData.text(item,"quote");
            if(seq<=0||quote.isBlank()||quote.length()>300)continue;
            boolean verified=transcript.stream().anyMatch(turn->"CHILD".equals(turn.get("speaker"))
                    && !Boolean.TRUE.equals(turn.get("uncertain")) && ResultData.number(turn,"seq")==seq
                    && ResultData.text(turn,"text").contains(quote));
            if(verified&&!result.contains(quote))result.add(quote);
            if(result.size()==2)break;
        }
        return List.copyOf(result);
    }
}
