package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;

/**
 * 묘비 10분 · R3-6 고아 세션 점검 · 타이머 전이 실패 재시도 간격. U4b: 계정 삭제 처리기의 진행 세션 강제 정지
 * 대기 상한 5초.
 */
public record RealtimeSessionProperties(Duration tombstoneTtl, Duration orphanCheckInterval, Duration timerTick,
        Duration deletionStopWait) {
    public RealtimeSessionProperties {
        positive(tombstoneTtl, "mujoong.session.tombstone-ttl");
        positive(orphanCheckInterval, "mujoong.session.orphan-check-interval");
        positive(timerTick, "mujoong.session.timer-tick");
        positive(deletionStopWait, "mujoong.session.deletion-stop-wait");
    }
}
