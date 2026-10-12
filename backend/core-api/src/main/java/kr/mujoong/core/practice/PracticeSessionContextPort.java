package kr.mujoong.core.practice;

import java.util.List;
import java.util.UUID;

/** U4/U5b supplies an authoritative frozen goal snapshot, never the live selection list. */
public interface PracticeSessionContextPort {
    SessionContext require(UUID sessionId);
    record GoalSnapshot(UUID goalSelectionId, String behaviorKey, int behaviorVersion, String relationType) {}
    record SessionContext(UUID sessionId, UUID guardianId, UUID childId, boolean active, List<GoalSnapshot> goals) {
        public SessionContext { goals = List.copyOf(goals); }
    }
}
