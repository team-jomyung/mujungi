package kr.mujoong.core.practice;

import java.util.UUID;

/** C10 internal-only response; no judgment, desired reply or child identity. */
public record Scenario(UUID scenarioId, String kind, String relationType, String opportunityCondition,
                       String scenarioText, String partnerRoleText, String triggerScript, Validation validation) {
    public record Validation(boolean goalIncluded, boolean triggerIncluded, boolean forbiddenFound) {}
    @Override public String toString() { return "Scenario[scenarioId=" + scenarioId + ", content=***]"; }
}
