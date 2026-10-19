package kr.mujoong.realtime.practicelegacy;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 독립 부품의 타입 있는 설정. 실제 판정 빈 연결은 별도 승인 후 수행한다. */
@ConfigurationProperties("mujoong.timing.practice")
public record PracticeProperties(Duration firstResponseWait, Duration helpConsentWait, Duration cueBudget,
        Duration afterCueResponseWait, Duration newScenarioResponseWait, Duration scenarioBudget,
        Duration judgeBudget, Duration judgeTotalBudget, Duration judgeFinalizationReserve,
        Duration judgeRetryDelay, String judgeBudgetVersion) {
    public PracticeProperties {
        new PracticeTiming(firstResponseWait, helpConsentWait, cueBudget, afterCueResponseWait, newScenarioResponseWait, scenarioBudget);
        PracticeChecks.text(judgeBudgetVersion, "판정 예산 버전");
        PracticeChecks.require(judgeBudget != null && judgeBudget.isPositive() && judgeBudget.toNanos() <= 5_000_000_000L, "판정 호출 한도 오류");
        PracticeChecks.require(judgeTotalBudget != null && judgeTotalBudget.isPositive() && judgeTotalBudget.toNanos() <= 12_000_000_000L, "판정 전체 한도 오류");
        PracticeChecks.require(judgeRetryDelay != null && !judgeRetryDelay.isNegative(), "재시도 간격 오류");
        if (judgeFinalizationReserve != null) new EvaluationBudget(judgeBudgetVersion, judgeTotalBudget, judgeBudget, judgeFinalizationReserve, judgeRetryDelay);
    }
    public PracticeTiming timing() {
        return new PracticeTiming(firstResponseWait, helpConsentWait, cueBudget, afterCueResponseWait, newScenarioResponseWait, scenarioBudget);
    }
    public Optional<EvaluationBudget> evaluationBudget() {
        return Optional.ofNullable(judgeFinalizationReserve)
                .map(reserve -> new EvaluationBudget(judgeBudgetVersion, judgeTotalBudget, judgeBudget, reserve, judgeRetryDelay));
    }
}
