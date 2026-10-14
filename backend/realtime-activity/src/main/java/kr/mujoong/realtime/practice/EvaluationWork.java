package kr.mujoong.realtime.practice;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 반응에 영속하는 예산과 호출 소유권. 재시작에서 시한/횟수를 초기화하지 않는다. */
public record EvaluationWork(String budgetVersion, Duration totalBudget, Instant deadlineAt,
        Duration finalizationReserve, int attemptsStarted, UUID activeAttemptToken,
        Instant activeAttemptDeadlineAt, boolean finalizationClaimed) {
    public EvaluationWork {
        PracticeChecks.text(budgetVersion, "budgetVersion");
        Objects.requireNonNull(totalBudget); Objects.requireNonNull(deadlineAt); Objects.requireNonNull(finalizationReserve);
        PracticeChecks.require(!finalizationReserve.isNegative() && !finalizationReserve.isZero()
                && finalizationReserve.compareTo(totalBudget) < 0, "최종 저장 예약 예산 오류");
        PracticeChecks.require(attemptsStarted >= 0 && attemptsStarted <= 2, "판정 호출 횟수 오류");
        PracticeChecks.require((activeAttemptToken == null) == (activeAttemptDeadlineAt == null), "호출 토큰 시한 불일치");
        PracticeChecks.require(!finalizationClaimed || activeAttemptToken == null, "최종화 이후 호출 금지");
    }
    public Instant cutoff() { return deadlineAt.minus(finalizationReserve); }
}
