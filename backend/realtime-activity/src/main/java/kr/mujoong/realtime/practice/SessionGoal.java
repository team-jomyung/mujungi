package kr.mujoong.realtime.practice;

import java.util.Objects;
import java.util.UUID;
import static kr.mujoong.realtime.practice.PracticeTypes.*;

/** 시작 원본의 정의와 순서는 불변이며 진행 변경은 새 값으로 표현한다. */
public record SessionGoal(UUID id, UUID sessionId, UUID guardianId, UUID goalSelectionId,
        int order, String displayName, String behaviorKey, int behaviorVersion,
        String judgmentCriteriaSnapshot, GoalStatus status, Stage currentStage,
        long generation, String skipReason) {
    public SessionGoal {
        Objects.requireNonNull(id); Objects.requireNonNull(sessionId);
        Objects.requireNonNull(guardianId); Objects.requireNonNull(goalSelectionId);
        PracticeChecks.text(displayName, "displayName"); PracticeChecks.text(behaviorKey, "behaviorKey");
        PracticeChecks.text(judgmentCriteriaSnapshot, "judgmentCriteriaSnapshot");
        Objects.requireNonNull(status);
        PracticeChecks.require(order > 0 && behaviorVersion > 0 && generation >= 0, "목표 순서/버전/세대 오류");
        PracticeChecks.require(status != GoalStatus.SKIPPED || skipReason != null, "미실시 사유 필요");
    }
    public SessionGoal progress(GoalStatus next, Stage stage, String reason) {
        return new SessionGoal(id, sessionId, guardianId, goalSelectionId, order, displayName,
                behaviorKey, behaviorVersion, judgmentCriteriaSnapshot, next, stage, generation + 1, reason);
    }
    @Override public String toString() { return "SessionGoal[id=" + id + ", order=" + order + "]"; }
}
