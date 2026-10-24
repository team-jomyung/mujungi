package kr.mujoong.realtime.judgment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * 공급자 판정 결과 검증 (U11 BR1.3 · BR1.5). Decisions 응답 검증(FT-1023-d)을 통과한 1 · 2순위도 확률 범위 → 선택지 안 → 중복 → 하한 →
 * 최소 차이 순으로 다시 확인한다. 하한 · 차이 비교는 십진수로 해서 경계값(같음)이 부동소수 오차로 흔들리지 않게 한다. 같으면 확정 쪽이다. 라벨 · 확률은 로그에 쓰지 않는다.
 */
public final class JudgeOutcomeValidator {

    private final BigDecimal minConfidence;
    private final BigDecimal minMargin;

    public JudgeOutcomeValidator(double minConfidence, double minMargin) {
        this.minConfidence = BigDecimal.valueOf(minConfidence);
        this.minMargin = BigDecimal.valueOf(minMargin);
    }

    /**
     * 확률 내림차순으로 고른 1 · 2순위를 확정 또는 보류로 바꾼다. 코드가 비었으면 SCHEMA_INVALID 보류, 그다음 확률 범위 → 선택지 안 → 중복 →
     * 하한(minConfidence) → 최소 차이(minMargin)이고 같으면 확정 쪽이다.
     */
    public JudgeOutcome validateRanked(String firstOption, double firstProbability, String secondOption, double secondProbability,
            List<String> allowedOptions, Duration latency, String model, String promptVersion) {
        Ranked first = Ranked.of(firstOption, firstProbability);
        Ranked second = Ranked.of(secondOption, secondProbability);
        if (first == null || second == null) {
            return JudgeOutcome.abstained(AbstainReason.SCHEMA_INVALID, latency, model, promptVersion);
        }
        return check(first, second, allowedOptions, latency, model, promptVersion);
    }

    private JudgeOutcome check(Ranked first, Ranked second, List<String> allowedOptions, Duration latency, String model,
            String promptVersion) {
        if (!first.inUnitInterval() || !second.inUnitInterval()) {
            return JudgeOutcome.abstained(AbstainReason.PROBABILITY_OUT_OF_RANGE, latency, model, promptVersion);
        }
        if (!allowedOptions.contains(first.option) || !allowedOptions.contains(second.option)) {
            return JudgeOutcome.abstained(AbstainReason.OPTION_OUT_OF_SET, latency, model, promptVersion);
        }
        if (first.option.equals(second.option)) {
            return JudgeOutcome.abstained(AbstainReason.DUPLICATE_OPTION, latency, model, promptVersion);
        }
        if (first.probability.compareTo(minConfidence) < 0) {
            return JudgeOutcome.abstainedWithScores(AbstainReason.LOW_CONFIDENCE, first.option, first.p(), second.option, second.p(),
                    latency, model, promptVersion);
        }
        if (first.probability.subtract(second.probability).compareTo(minMargin) < 0) {
            return JudgeOutcome.abstainedWithScores(AbstainReason.LOW_MARGIN, first.option, first.p(), second.option, second.p(),
                    latency, model, promptVersion);
        }
        return JudgeOutcome.confirmed(first.option, first.p(), second.option, second.p(), latency, model, promptVersion);
    }

    private record Ranked(String option, BigDecimal probability) {

        static Ranked of(String option, double value) {
            if (option == null || option.isBlank()) {
                return null;
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return new Ranked(option, BigDecimal.TEN);
            }
            return new Ranked(option, BigDecimal.valueOf(value));
        }

        boolean inUnitInterval() {
            return probability.signum() >= 0 && probability.compareTo(BigDecimal.ONE) <= 0;
        }

        double p() {
            return probability.doubleValue();
        }
    }
}
