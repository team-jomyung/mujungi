package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.invalid;
import static kr.mujoong.realtime.config.RealtimeProperties.nonNegative;

/**
 * U3 전사 표시 기준(AC3.2.10 · U3-J17): 공급자가 준 아이 전사 신뢰도(0 ~ 100)가 이 값보다 낮으면 {@code review_needed}
 * (12 '불확실' 표시). 신뢰도를 주지 않는 공급자에서는 판정하지 않는다.
 */
public record RealtimeTranscriptProperties(Integer uncertainBelowPercent) {
    public RealtimeTranscriptProperties {
        nonNegative(uncertainBelowPercent, "mujoong.transcript.uncertain-below-percent");
        if (uncertainBelowPercent > RealtimeObservabilityProperties.MAX_PERCENT) {
            throw invalid("설정 키는 0 이상 100 이하여야 한다: mujoong.transcript.uncertain-below-percent");
        }
    }
}
