package kr.mujoong.realtime.judgment;

import java.time.Duration;
import java.util.Objects;

/**
 * 판정 한 번의 결과 (U11 entities JudgeOutcome · BR1.3 ~ BR1.5). 확정 · 보류 · 장애 취소 · 중단 · 대체 · 세션 이탈 중 하나다. 확정이면 1 · 2순위
 * 코드와 확률이 모두 있고, 보류 사유는 보류일 때만 있으며, 나머지 끝은 확률 · 사유를 쓰지 않는다. 보류 중 확률이 남는 것은 LOW_CONFIDENCE와
 * LOW_MARGIN뿐이다(기록용이며 확정 행동에 쓰지 않는다, NFR2). 문자열 표현은 라벨 · 확률을 숨긴다.
 *
 * @param model 공급자가 돌려준 모델 이름. 공급자를 부르지 않았으면 null
 */
public record JudgeOutcome(
        JudgmentOutcomeKind kind,
        String firstOption,
        Double firstProbability,
        String secondOption,
        Double secondProbability,
        AbstainReason abstainReason,
        Duration latency,
        String model,
        String promptVersion) {

    public JudgeOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(latency, "latency");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency는 0 이상이다");
        }
        Objects.requireNonNull(promptVersion, "promptVersion");
        boolean hasOptions = firstOption != null && firstProbability != null && secondOption != null && secondProbability != null;
        boolean anyOption = firstOption != null || firstProbability != null || secondOption != null || secondProbability != null;
        if (anyOption && !hasOptions) {
            throw new IllegalArgumentException("1 · 2순위 코드와 확률은 함께 있거나 함께 없어야 한다");
        }
        switch (kind) {
            case CONFIRMED -> {
                if (!hasOptions || abstainReason != null) {
                    throw new IllegalArgumentException("확정은 1 · 2순위가 모두 있고 보류 사유가 없어야 한다");
                }
            }
            case ABSTAINED -> {
                if (abstainReason == null) {
                    throw new IllegalArgumentException("보류는 사유가 있어야 한다");
                }
                if (hasOptions && abstainReason != AbstainReason.LOW_CONFIDENCE && abstainReason != AbstainReason.LOW_MARGIN) {
                    throw new IllegalArgumentException("확률은 LOW_CONFIDENCE · LOW_MARGIN 보류에만 남긴다");
                }
            }
            default -> {
                if (hasOptions || abstainReason != null) {
                    throw new IllegalArgumentException("확정 · 보류가 아닌 끝은 확률 · 사유를 쓰지 않는다");
                }
            }
        }
    }

    /** 확정 결과. */
    public static JudgeOutcome confirmed(String first, double firstP, String second, double secondP, Duration latency, String model,
            String promptVersion) {
        return new JudgeOutcome(JudgmentOutcomeKind.CONFIRMED, first, firstP, second, secondP, null, latency, model, promptVersion);
    }

    /** 확률 없는 보류. */
    public static JudgeOutcome abstained(AbstainReason reason, Duration latency, String model, String promptVersion) {
        return new JudgeOutcome(JudgmentOutcomeKind.ABSTAINED, null, null, null, null, reason, latency, model, promptVersion);
    }

    /** 확률이 남는 보류(LOW_CONFIDENCE · LOW_MARGIN). */
    public static JudgeOutcome abstainedWithScores(AbstainReason reason, String first, double firstP, String second, double secondP,
            Duration latency, String model, String promptVersion) {
        return new JudgeOutcome(JudgmentOutcomeKind.ABSTAINED, first, firstP, second, secondP, reason, latency, model, promptVersion);
    }

    /** 확정 · 보류가 아닌 끝(FAULT_CANCELLED · STOPPED · SUPERSEDED · SESSION_EXITED). */
    public static JudgeOutcome ended(JudgmentOutcomeKind kind, Duration latency, String promptVersion) {
        return new JudgeOutcome(kind, null, null, null, null, null, latency, null, promptVersion);
    }

    public boolean isConfirmed() {
        return kind == JudgmentOutcomeKind.CONFIRMED;
    }

    @Override
    public String toString() {
        return "JudgeOutcome[kind=" + kind + (abstainReason == null ? "" : ", reason=" + abstainReason) + "]";
    }
}
