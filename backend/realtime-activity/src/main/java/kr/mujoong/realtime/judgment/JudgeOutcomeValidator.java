package kr.mujoong.realtime.judgment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 공급자 판정 결과 검증 (U11 BR1.3 · BR1.5). 스키마 통과 뒤에도 확률 범위 → 선택지 안 → 중복 → 하한 → 최소 차이 순으로 다시 확인한다. 하한 · 차이
 * 비교는 십진수로 해서 경계값(같음)이 부동소수 오차로 흔들리지 않게 한다. 같으면 확정 쪽이다. 라벨 · 확률은 로그에 쓰지 않는다.
 */
public final class JudgeOutcomeValidator {

    private final BigDecimal minConfidence;
    private final BigDecimal minMargin;
    private final JsonCodec json;

    public JudgeOutcomeValidator(double minConfidence, double minMargin, JsonCodec json) {
        this.minConfidence = BigDecimal.valueOf(minConfidence);
        this.minMargin = BigDecimal.valueOf(minMargin);
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 결과 JSON을 {@code allowedOptions}에 비춰 확정 또는 보류로 바꾼다. 읽을 수 없으면 SCHEMA_INVALID 보류다. */
    public JudgeOutcome validate(String outputJson, List<String> allowedOptions, Duration latency, String model, String promptVersion) {
        Ranked first;
        Ranked second;
        try {
            Map<?, ?> root = json.read(outputJson, Map.class);
            first = Ranked.of(root.get("first"));
            second = Ranked.of(root.get("second"));
        } catch (RuntimeException unreadable) {
            return JudgeOutcome.abstained(AbstainReason.SCHEMA_INVALID, latency, model, promptVersion);
        }
        if (first == null || second == null) {
            return JudgeOutcome.abstained(AbstainReason.SCHEMA_INVALID, latency, model, promptVersion);
        }
        return check(first, second, allowedOptions, latency, model, promptVersion);
    }

    /**
     * 이미 고른 1 · 2순위를 {@link #validate}와 같은 순서 · 같은 하한으로 확인한다(Decisions 판정 경로, FT-1023-d). 확률 범위 → 선택지 안 → 중복 →
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

        static Ranked of(Object node) {
            if (!(node instanceof Map<?, ?> map) || !(map.get("option") instanceof String option) || option.isBlank()
                    || !(map.get("probability") instanceof Number number)) {
                return null;
            }
            return of(option, number.doubleValue());
        }

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
