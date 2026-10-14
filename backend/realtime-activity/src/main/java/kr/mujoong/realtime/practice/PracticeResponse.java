package kr.mujoong.realtime.practice;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static kr.mujoong.realtime.practice.PracticeTypes.*;

/** 무반응과 판정 대기/실패를 섞지 않는 반응 원본. */
public record PracticeResponse(UUID id, UUID attemptId, UUID scenarioId, ResponseType responseType,
        Integer utteranceSeq, EvaluationState evaluationState, long evaluationGeneration,
        Instant acceptedAt, EvaluationWork work, Label label, String notPerformedReason,
        Instant opportunityOpenedAt, Instant closedAt) {
    public PracticeResponse {
        Objects.requireNonNull(id); Objects.requireNonNull(attemptId); Objects.requireNonNull(scenarioId);
        Objects.requireNonNull(responseType); Objects.requireNonNull(evaluationState); Objects.requireNonNull(acceptedAt);
        PracticeChecks.require(evaluationGeneration > 0, "판정 세대 오류");
        boolean absent = label == Label.NOT_PERFORMED;
        PracticeChecks.require(absent ? utteranceSeq == null && notPerformedReason != null && work == null
                : utteranceSeq != null && utteranceSeq > 0 && notPerformedReason == null && work != null, "반응/무반응 필드 불일치");
        boolean finalized = evaluationState == EvaluationState.FINALIZED;
        PracticeChecks.require(finalized ? label != null && closedAt != null : label == null && closedAt == null,
                "판정 확정 상태 불일치");
        PracticeChecks.require(work == null || work.deadlineAt().equals(acceptedAt.plus(work.totalBudget())), "최초 판정 예산 불일치");
        PracticeChecks.require(closedAt == null || !closedAt.isBefore(acceptedAt), "반응 종료 시각 오류");
    }
    public void requireAttempt(PracticeAttempt attempt) {
        PracticeChecks.require(attempt.id().equals(attemptId) && scenarioId.equals(attempt.scenarioId()), "반응 장면 참조 오류");
        PracticeChecks.require((responseType == ResponseType.NEW_SCENARIO)
                == (attempt.attemptType() == AttemptType.NEW_SCENARIO), "반응 종류와 시도 불일치");
    }
    @Override public String toString() { return "PracticeResponse[id=" + id + ", type=" + responseType + "]"; }
}
