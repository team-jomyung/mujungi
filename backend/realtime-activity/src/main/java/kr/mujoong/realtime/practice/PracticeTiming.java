package kr.mujoong.realtime.practice;

import java.time.Duration;

/** 첫 반응/동의/Cue 예약과 도움 후·새 상황의 로컬 제안을 구분해 주입한다. */
public record PracticeTiming(Duration firstResponse, Duration helpConsent, Duration cueStart,
        Duration afterCueResponse, Duration newScenarioResponse, Duration scenarioPreparation) {
    public PracticeTiming {
        for (Duration value : new Duration[] {firstResponse, helpConsent, cueStart, afterCueResponse,
                newScenarioResponse, scenarioPreparation}) {
            PracticeChecks.require(value != null && value.isPositive(), "연습 시간 설정은 양수여야 한다");
        }
    }
}
