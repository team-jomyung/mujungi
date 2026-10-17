package kr.mujoong.core.practicegoal;

import java.util.Optional;
import java.util.UUID;

/** C-DS10: child-scoped, pinned goal context for U10. */
public interface SavedGoalReader {
    Optional<SavedGoalView> find(UUID childId,UUID selectionId);
    record SavedGoalView(UUID selectionId,UUID childId,Kind kind,String candidateRef,Integer candidateVersion,
            String targetExpression,FourElements fourElements,String skillRef,String legacyBehaviorKey,Integer legacyBehaviorVersion) {
        public enum Kind {CANDIDATE,LEGACY_BEHAVIOR}
        @Override public String toString(){return "SavedGoalView[id="+selectionId+",kind="+kind+"]";}
    }
}
