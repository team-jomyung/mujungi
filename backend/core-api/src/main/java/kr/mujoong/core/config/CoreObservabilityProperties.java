package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.invalid;
import static kr.mujoong.core.config.CoreProperties.positive;

import java.time.Duration;

/**
 * OD-4 ownerHash 비밀 · OD-6 지표 스냅샷 · OD-3 반복 병합. 비밀이 비었을 때의 처리(demo · prod 기동 실패,
 * dev 임시 값)는 ownerHash 설정 장치가 맡는다. 문자열 표현에 비밀 값을 내지 않는다.
 *
 * <p>{@code systemSampleInterval}(3.6 수정)은 OD-6의 '앱 안 5초 표본'(CPU 평균 · 메모리 최댓값 · 적체 게이지 갱신) 주기다. 스냅샷
 * 주기보다 길면 구간에 표본이 없을 수 있어 기동을 거부한다.
 */
public record CoreObservabilityProperties(
        String ownerHashSecret,
        Duration metricSnapshotInterval,
        Duration logMergeWindow,
        Duration systemSampleInterval) {

    public CoreObservabilityProperties {
        ownerHashSecret = ownerHashSecret == null ? "" : ownerHashSecret;
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
                + ", metricSnapshotInterval=" + metricSnapshotInterval
                + ", logMergeWindow=" + logMergeWindow
                + ", systemSampleInterval=" + systemSampleInterval + "]";
    }
}
