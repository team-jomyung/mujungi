package kr.mujoong.core.practice;

import java.util.List;
import java.util.UUID;

/** U4/U5b supplies an authoritative frozen goal snapshot, never the live selection list. */
public interface PracticeSessionContextPort {
    SessionContext require(UUID sessionId);
    record GoalSnapshot(UUID goalSelectionId, String behaviorKey, int behaviorVersion, String relationType,GoalContext goalContext) {
        public GoalSnapshot(UUID goalSelectionId,String behaviorKey,int behaviorVersion,String relationType){this(goalSelectionId,behaviorKey,behaviorVersion,relationType,null);}
        public GoalSnapshot{if(goalContext==null)goalContext=GoalContext.legacy(behaviorKey,behaviorVersion);}
    }
    record SessionContext(UUID sessionId, UUID guardianId, UUID childId, boolean active, List<GoalSnapshot> goals) {
        public SessionContext { goals = List.copyOf(goals); }
    }
}
