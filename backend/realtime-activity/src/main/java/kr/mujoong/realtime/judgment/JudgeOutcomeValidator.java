package kr.mujoong.realtime.judgment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * 공급자 판정 결과 검증 (U11 BR1.3 · BR1.5). Decisions 응답 검증(FT-1023-d)을 통과한 1 · 2순위도 확률 범위 → 선택지 안 → 중복 → 하한 →
 * 최소 차이 순으로 다시 확인한다. 하한 · 차이 비교는 십진수로 해서 경계값(같음)이 부동소수 오차로 흔들리지 않게 한다. 같으면 확정 쪽이다. 라벨 · 확률은 로그에 쓰지 않는다.
 *
 * <p>같은 걸음 묶음(ai-io.md 10.10, 편차 FT-1024-f): 판정을 요청한 연습 흐름이 지금 단계에서 같은 다음 걸음으로 처리하는 선택지 묶음을 요청에 실으면,
 * 1 · 2순위가 같은 묶음일 때 하한은 두 확률의 합으로 보고 두 선택지 사이의 최소 차이는 보지 않는다. 어느 쪽이 1순위여도 흐름이 하는 일이 같아서 둘
 * 사이의 망설임은 보류할 까닭이 아니다. 확정 결과의 1순위는 모델이 낸 1순위 그대로다. 1 · 2순위가 다른 묶음이거나 묶음이 없으면 지금 규칙 그대로다.
 *
 * <p>요청 하한(ai-io.md 10.10, 편차 FT-1024-h): 요청이 이 판정에만 쓸 낮춘 하한을 실으면(C안 기회 J2) 하한 자리에 그 값을 쓴다 — 1순위 하나를 볼
 * 때도, 같은 걸음 묶음의 합을 볼 때도 같은 하한이다. 낮추기만 한다: 전역 하한보다 크거나 같으면(또는 수가 아니거나 0 ~ 1 밖이면) 무시하고 전역 하한을
 * 쓴다. 최소 차이는 언제나 전역 값이다.
 */
public final class JudgeOutcomeValidator {

    private final BigDecimal minConfidence;
    private final BigDecimal minMargin;

    public JudgeOutcomeValidator(double minConfidence, double minMargin) {
        this.minConfidence = BigDecimal.valueOf(minConfidence);
        this.minMargin = BigDecimal.valueOf(minMargin);
    }

    /** 같은 걸음 묶음이 없는 판정(지금 규칙 그대로). */
    public JudgeOutcome validateRanked(String firstOption, double firstProbability, String secondOption, double secondProbability,
            List<String> allowedOptions, Duration latency, String model, String promptVersion) {
        return validateRanked(firstOption, firstProbability, secondOption, secondProbability, allowedOptions, List.of(), latency, model,
                promptVersion);
    }

    /**
     * 확률 내림차순으로 고른 1 · 2순위를 확정 또는 보류로 바꾼다. 코드가 비었으면 SCHEMA_INVALID 보류, 그다음 확률 범위 → 선택지 안 → 중복 →
     * 하한(minConfidence) → 최소 차이(minMargin)이고 같으면 확정 쪽이다. 1순위 혼자로 하한 · 최소 차이를 넘지 못해도 1 · 2순위가 같은 묶음이고
     * 두 확률의 합이 하한 이상이면 묶음 합 확정({@link JudgeOutcome#sameStepSum()})이다.
     *
     * @param sameStepGroups 요청이 실어 온 같은 걸음 묶음(없으면 빈 목록)
     */
    public JudgeOutcome validateRanked(String firstOption, double firstProbability, String secondOption, double secondProbability,
            List<String> allowedOptions, List<List<String>> sameStepGroups, Duration latency, String model, String promptVersion) {
        return validateRanked(firstOption, firstProbability, secondOption, secondProbability, allowedOptions, sameStepGroups, null,
                latency, model, promptVersion);
    }

    /**
     * {@link #validateRanked(String, double, String, double, List, List, Duration, String, String)}에 요청 하한을 더한 것.
     *
     * @param requestedMinConfidence 요청이 이 판정에만 쓰자고 실은 낮춘 하한(없으면 null). 전역 하한보다 낮을 때만 하한 자리에 쓴다
     */
    public JudgeOutcome validateRanked(String firstOption, double firstProbability, String secondOption, double secondProbability,
            List<String> allowedOptions, List<List<String>> sameStepGroups, Double requestedMinConfidence, Duration latency, String model,
            String promptVersion) {
        Ranked first = Ranked.of(firstOption, firstProbability);
        Ranked second = Ranked.of(secondOption, secondProbability);
        if (first == null || second == null) {
            return JudgeOutcome.abstained(AbstainReason.SCHEMA_INVALID, latency, model, promptVersion);
        }
        return check(first, second, allowedOptions, sameStepGroups == null ? List.of() : sameStepGroups, floor(requestedMinConfidence),
                latency, model, promptVersion);
    }

    /** 이 판정의 하한: 요청 하한이 0 ~ 1 안의 수이고 전역 하한보다 낮으면 그 값, 아니면 전역 하한(낮추기만 한다). */
    private BigDecimal floor(Double requested) {
        if (requested == null || requested.isNaN() || requested.isInfinite() || requested < 0.0 || requested > 1.0) {
            return minConfidence;
        }
        BigDecimal value = BigDecimal.valueOf(requested);
        return value.compareTo(minConfidence) < 0 ? value : minConfidence;
    }

    private JudgeOutcome check(Ranked first, Ranked second, List<String> allowedOptions, List<List<String>> sameStepGroups,
            BigDecimal floor, Duration latency, String model, String promptVersion) {
        if (!first.inUnitInterval() || !second.inUnitInterval()) {
            return JudgeOutcome.abstained(AbstainReason.PROBABILITY_OUT_OF_RANGE, latency, model, promptVersion);
        }
        if (!allowedOptions.contains(first.option) || !allowedOptions.contains(second.option)) {
            return JudgeOutcome.abstained(AbstainReason.OPTION_OUT_OF_SET, latency, model, promptVersion);
        }
        if (first.option.equals(second.option)) {
            return JudgeOutcome.abstained(AbstainReason.DUPLICATE_OPTION, latency, model, promptVersion);
        }
        boolean confident = first.probability.compareTo(floor) >= 0;
        boolean separated = first.probability.subtract(second.probability).compareTo(minMargin) >= 0;
        if (confident && separated) {
            return JudgeOutcome.confirmed(first.option, first.p(), second.option, second.p(), latency, model, promptVersion);
        }
        if (sameStep(sameStepGroups, first.option, second.option)
                && first.probability.add(second.probability).compareTo(floor) >= 0) {
            return JudgeOutcome.confirmedBySameStep(first.option, first.p(), second.option, second.p(), latency, model, promptVersion);
        }
        return JudgeOutcome.abstainedWithScores(confident ? AbstainReason.LOW_MARGIN : AbstainReason.LOW_CONFIDENCE, first.option,
                first.p(), second.option, second.p(), latency, model, promptVersion);
    }

    /** 두 선택지가 같은 묶음에 드는가. */
    private static boolean sameStep(List<List<String>> groups, String first, String second) {
        for (List<String> group : groups) {
            if (group.contains(first) && group.contains(second)) {
                return true;
            }
        }
        return false;
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
