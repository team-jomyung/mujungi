package kr.mujoong.core.knowledge;

import java.time.Instant;
import java.util.UUID;

/** An immutable, version-addressed public catalog snapshot (ADR-012). */
public record BehaviorDefinition(UUID id, String behaviorKey, int version, String displayName,
        String behaviorDefinitionText, String opportunityCondition, String scenarioCondition,
        String judgmentCriteria, String status, Instant createdAt, Instant usedAt) {

    public boolean complete() {
        return present(displayName) && present(behaviorDefinitionText) && present(opportunityCondition)
                && present(scenarioCondition) && present(judgmentCriteria);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
