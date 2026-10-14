package kr.mujoong.realtime.practice;

import java.time.Duration;
import java.time.Instant;

/** 운영 예약 R은 기본값 없이 검증된 호출자가 명시한다. 진행 작업은 저장된 예산을 재사용한다. */
public record EvaluationBudget(String version, Duration total, Duration call, Duration reserve, Duration retryDelay) {
    public EvaluationBudget {
        PracticeChecks.text(version, "예산 버전");
        PracticeChecks.require(total != null && total.isPositive() && total.toNanos() <= 12_000_000_000L,
                "전체 판정 예산은 양수이며 12초 이하여야 한다");
        PracticeChecks.require(call != null && call.isPositive() && call.toNanos() <= 5_000_000_000L,
                "개별 판정 예산은 양수이며 5초 이하여야 한다");
        PracticeChecks.require(reserve != null && reserve.isPositive() && reserve.compareTo(total) < 0,
                "검증된 저장 예약이 필요하다");
        PracticeChecks.require(retryDelay != null && !retryDelay.isNegative(), "재시도 간격 오류");
    }
    public EvaluationWork begin(Instant acceptedAt) {
        return new EvaluationWork(version, total, acceptedAt.plus(total), reserve, 0, null, null, false);
    }
}
