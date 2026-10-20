package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.unitInterval;

/**
 * U11 진행 중 판정 보류 기준 (시간 값이 아니라 timing.md에 두지 않는다). 1순위 확률이 {@code minConfidence}보다 작거나 1 · 2순위
 * 확률 차이가 {@code minMargin}보다 작으면 판정 보류다(같은 값은 확정 쪽). 둘 다 0 이상 1 이하다. 판정 시한은 {@code timing.judgment.live-budget}이다.
 */
public record RealtimeJudgmentProperties(Double minConfidence, Double minMargin) {
    public RealtimeJudgmentProperties {
        unitInterval(minConfidence, "mujoong.judgment.min-confidence");
        unitInterval(minMargin, "mujoong.judgment.min-margin");
    }
}
