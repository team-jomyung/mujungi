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
    /**
     * 생성 메타. {@code basePreservation}은 보존 BASE를 어떻게 썼는지다(없으면 생성 · 검토한 그대로 발급): {@code BASE_REPLACED} 다음 생성이 고쳐 쓴 BASE를
     * 승인 BASE로 되돌려 변형과 함께 발급, {@code BASE_ONLY_EARLIER_REVIEW} 변형을 빼고 단독 승인된 BASE를 추가 검토 없이 발급,
     * {@code BASE_ONLY_REVIEWED} 변형을 빼고 BASE만 다시 검토해 발급.
     */
    public record Generation(String model, String promptVersion, boolean graphContextUsed, int calls, String kgVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String basePreservation) {
        public Generation(String model, String promptVersion, boolean graphContextUsed, int calls) {
            this(model, promptVersion, graphContextUsed, calls, null, null);
        }
        public Generation(String model, String promptVersion, boolean graphContextUsed, int calls, String kgVersion) {
            this(model, promptVersion, graphContextUsed, calls, kgVersion, null);
        }
    }
    public record Validation(boolean goalIncluded, boolean triggerIncluded, boolean forbiddenFound) {}
    @Override public String toString() { return "Scenario[scenarioId=" + scenarioId + ", content=***]"; }
}
