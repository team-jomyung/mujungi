package kr.mujoong.core.practice.start;

import java.util.Objects;
import java.util.UUID;

/** 준비 원본의 불변 값. 새 저장/API 계약은 I5 합의 전 미연결이다. */
public record FrozenGoal(UUID goalSelectionId, String displayName, String behaviorKey, int behaviorVersion,
        String judgmentCriteriaSnapshot, int savedSortOrder, int selectionRank, int selectedCount,
        boolean favoriteAtPreparation, int favoriteMultiplier, int weight, int order,kr.mujoong.core.practice.GoalContext goalContext) {
    public FrozenGoal(UUID goalSelectionId,String displayName,String behaviorKey,int behaviorVersion,String judgmentCriteriaSnapshot,
            int savedSortOrder,int selectionRank,int selectedCount,boolean favoriteAtPreparation,int favoriteMultiplier,int weight,int order){
        this(goalSelectionId,displayName,behaviorKey,behaviorVersion,judgmentCriteriaSnapshot,savedSortOrder,selectionRank,selectedCount,favoriteAtPreparation,favoriteMultiplier,weight,order,null);
    }
    public FrozenGoal {
        if(goalContext==null)goalContext=kr.mujoong.core.practice.GoalContext.legacy(behaviorKey,behaviorVersion);
        if(!goalContext.carrierKey().equals(behaviorKey)||goalContext.carrierVersion()!=behaviorVersion)throw new IllegalArgumentException("목표 운반 식별자 불일치");
        Objects.requireNonNull(goalSelectionId);
        if (displayName == null || displayName.isBlank() || behaviorKey == null || behaviorKey.isBlank()
                || judgmentCriteriaSnapshot == null || judgmentCriteriaSnapshot.isBlank()) throw new IllegalArgumentException("고정 목표의 승인 정의 필요");
        if (behaviorVersion < 1 || selectedCount < 1 || selectionRank < 1 || selectionRank > selectedCount
                || order < 1 || order > selectedCount || favoriteMultiplier != (favoriteAtPreparation ? 2 : 1)
                || weight != Math.multiplyExact(favoriteMultiplier, selectedCount - selectionRank + 1))
            throw new IllegalArgumentException("고정 목표의 순위·가중치 오류");
    }
    @Override public String toString() { return "FrozenGoal[id=" + goalSelectionId + ", order=" + order + "]"; }
}
