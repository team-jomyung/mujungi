package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.invalid;
import static kr.mujoong.realtime.config.RealtimeProperties.nonNegative;
import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;

/**
 * OD-4 ownerHash 비밀 · OD-8 지연 로그 표본률 · OD-6 지표 스냅샷 · OD-3 반복 병합. 문자열 표현에 비밀 값을
 * 내지 않는다.
 *
 * <p>{@code systemSampleInterval}(3.6 수정 J302, Core J326과 같은 키)은 OD-6의 '앱 안 5초 표본'(CPU 평균 · 메모리 최댓값 · 풀 사용 ·
 * 적체 · 연결 상태 게이지 갱신) 주기다. 스냅샷 주기보다 길면 구간에 표본이 없을 수 있어 기동을 거부한다.
 */
public record RealtimeObservabilityProperties(
        String ownerHashSecret,
        Integer latencyLogSampleRate,
        Duration metricSnapshotInterval,
        Duration logMergeWindow,
        Duration systemSampleInterval) {

    static final int MAX_PERCENT = 100;

    public RealtimeObservabilityProperties {
        ownerHashSecret = ownerHashSecret == null ? "" : ownerHashSecret;
        nonNegative(latencyLogSampleRate, "mujoong.observability.latency-log-sample-rate");
        if (latencyLogSampleRate > MAX_PERCENT) {
            throw invalid("설정 키는 0 이상 100 이하여야 한다: mujoong.observability.latency-log-sample-rate");
        }
        positive(metricSnapshotInterval, "mujoong.observability.metric-snapshot-interval");
        positive(logMergeWindow, "mujoong.observability.log-merge-window");
        positive(systemSampleInterval, "mujoong.observability.system-sample-interval");
        if (systemSampleInterval.compareTo(metricSnapshotInterval) > 0) {
            throw invalid("mujoong.observability.system-sample-interval은 "
                    + "mujoong.observability.metric-snapshot-interval 이하여야 한다");
        }
    }

    @Override
    public String toString() {
        return "Observability[ownerHashSecret=" + (ownerHashSecret.isEmpty() ? "<empty>" : "<redacted>")
                + ", latencyLogSampleRate=" + latencyLogSampleRate
                + ", metricSnapshotInterval=" + metricSnapshotInterval
                + ", logMergeWindow=" + logMergeWindow
                + ", systemSampleInterval=" + systemSampleInterval + "]";
    }
}
