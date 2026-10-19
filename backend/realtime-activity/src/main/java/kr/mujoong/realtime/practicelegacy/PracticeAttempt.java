package kr.mujoong.realtime.practicelegacy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;

/** 발급과 실제 노출, 장면 없는 생성 실패를 구분한다. */
public record PracticeAttempt(UUID id, UUID sessionGoalId, AttemptType attemptType,
        AttemptState state, UUID scenarioId, UUID previousScenarioId, Instant exposedAt,
        PreparationFailure preparationFailure) {
    public PracticeAttempt {
        Objects.requireNonNull(id); Objects.requireNonNull(sessionGoalId);
        Objects.requireNonNull(attemptType); Objects.requireNonNull(state);
        boolean prepared = state != AttemptState.PREPARING && state != AttemptState.PREPARATION_FAILED;
        PracticeChecks.require(prepared == (scenarioId != null), "장면 준비 상태와 참조 불일치");
        PracticeChecks.require((state == AttemptState.PREPARATION_FAILED) == (preparationFailure != null), "준비 실패 사유 불일치");
        PracticeChecks.require(exposedAt == null || prepared, "미생성 장면 노출 금지");
        PracticeChecks.require(state != AttemptState.EXPOSED || exposedAt != null, "노출 증거 필요");
        PracticeChecks.require((attemptType == AttemptType.NEW_SCENARIO) == (previousScenarioId != null), "원 상황 참조 불일치");
        PracticeChecks.require(scenarioId == null || !scenarioId.equals(previousScenarioId), "새 상황 재사용 금지");
    }
}
