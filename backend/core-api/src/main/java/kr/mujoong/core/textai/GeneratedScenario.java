package kr.mujoong.core.textai;
/** Provider output is untrusted until gateway and caller validation complete. */
public record GeneratedScenario(String scenarioText, String partnerRoleText, String triggerScript,
        String relationType, String opportunityCondition, String modelVersion, String promptVersion) {
    @Override public String toString() { return "GeneratedScenario[redacted]"; }
}
