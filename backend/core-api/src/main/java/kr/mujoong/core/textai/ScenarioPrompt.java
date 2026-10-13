package kr.mujoong.core.textai;
import java.util.List;
import java.util.Objects;
/** Minimal request: no child name, IDs, transcript, guardian input, or judgment criteria. */
public record ScenarioPrompt(String goalDisplayName, String behaviorDefinition, String opportunityCondition,
        String scenarioCondition, String relationType, int age, List<String> interests, String previousScenarioText) {
    public ScenarioPrompt {
        requireText(goalDisplayName); requireText(behaviorDefinition); requireText(opportunityCondition);
        requireText(scenarioCondition); requireText(relationType);
        if (age < 6 || age > 12) { throw new IllegalArgumentException("Unsupported child age"); }
        interests = List.copyOf(Objects.requireNonNull(interests, "interests"));
    }
    private static void requireText(String value) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("Incomplete scenario input"); }
    }
    @Override public String toString() { return "ScenarioPrompt[redacted]"; }
}
