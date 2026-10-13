package kr.mujoong.core.practice;
import java.time.Instant;
import java.util.UUID;
/** Factual presentation metadata supplied by U4. Null means not connected, never fabricated zero. */
@FunctionalInterface
public interface GoalMetadataPort {
    Metadata lookup(UUID childId, UUID goalSelectionId, UUID sourceSessionId);
    record Metadata(String sourceActivityType, Instant sourceActivityAt, Integer practiceCount) {
        public static Metadata unavailable() { return new Metadata(null,null,null); }
    }
}
