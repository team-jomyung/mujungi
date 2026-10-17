package kr.mujoong.core.practice;

import java.util.UUID;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import kr.mujoong.core.textai.ScenarioScript;

/** C10 internal-only response; no judgment, desired reply or child identity. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Scenario(UUID scenarioId, String kind, String relationType, String opportunityCondition,
                       String scenarioText, String partnerRoleText, String triggerScript, Validation validation,
                       String scriptVersion, List<ScenarioScript.Scene> scenes, Review selfReview, Generation generation) {
    public Scenario(UUID scenarioId, String kind, String relationType, String opportunityCondition,
            String scenarioText, String partnerRoleText, String triggerScript, Validation validation) {
        this(scenarioId, kind, relationType, opportunityCondition, scenarioText, partnerRoleText, triggerScript,
                validation, null, null, null, null);
    }
    public Scenario { scenes = scenes == null ? null : List.copyOf(scenes); }
    public record Review(String verdict, String model, String promptVersion) {}
    public record Generation(String model, String promptVersion, boolean graphContextUsed, int calls,String kgVersion) {
        public Generation(String model,String promptVersion,boolean graphContextUsed,int calls){this(model,promptVersion,graphContextUsed,calls,null);}
    }
    public record Validation(boolean goalIncluded, boolean triggerIncluded, boolean forbiddenFound) {}
    @Override public String toString() { return "Scenario[scenarioId=" + scenarioId + ", content=***]"; }
}
