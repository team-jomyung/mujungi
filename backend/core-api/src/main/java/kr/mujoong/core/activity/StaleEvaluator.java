package kr.mujoong.core.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.device.DeviceVerdict;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * '확인 불가'(stale) 판정 (U1 3.5 Step 22 · RD-6 · NFR4.3 · BR4.3 ③ · BR6.15 · OD-10).
 *
 * <p>읽을 때 계산한다: 마지막으로 받은 시각에서 {@code mujoong.timing.core.device-status-stale}(30초)을 넘으면 stale이다(정각은 아직 신선).
 * 전이를 만들지 않는다. 조회 한 번의 실패는 받은 시각을 바꾸지 않으므로 그것만으로는 stale이 아니다.
 *
 * <p>기기 판정(04 · 05 · 시작 검사)은 기기별 메모리 래치로 '신선 → 확인 불가'로 바뀌는 첫 판정에만
 * {@code mujoong_timeout_fired_total{type=device_status_stale}}과 TIMEOUT_FIRED(ownerHash = deviceId)를 남긴다. 재시작하면 래치가 비어 한 번
 * 더 셀 수 있다(OD-9). 12 진행 조회의 stale은 같은 기준으로 계산만 한다.
 */
@Component
public class StaleEvaluator {

    /** 래치가 들고 있는 기기 수 상한(넘으면 비우고 다시 센다). */
    static final int MAX_TRACKED_DEVICES = 10_000;

    private final Duration stale;
    private final CoreMetrics metrics;
    private final EventLogger events;
    private final Map<UUID, Boolean> deviceStale = new ConcurrentHashMap<>();

    @Autowired
    public StaleEvaluator(CoreProperties properties, CoreMetrics metrics, EventLogger events) {
        this(properties.timing().core().deviceStatusStale(), metrics, events);
    }

    public StaleEvaluator(Duration stale, CoreMetrics metrics, EventLogger events) {
        if (stale == null || stale.isZero() || stale.isNegative()) {
            throw new IllegalArgumentException("stale 기준은 0보다 커야 한다");
        }
        this.stale = stale;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 기준 시간. */
    public Duration threshold() {
        return stale;
    }

    /** {@code observedAt} 뒤 기준 시간을 넘었는가. */
    public boolean isStale(Instant observedAt, Instant now) {
        return DeviceVerdict.isStale(observedAt, now, stale);
    }

    /**
     * 12 진행 조회의 stale(BR6.15): 받은 시각이 없으면(한 번도 받지 못함) 요청 시각을 기준으로 본다. 종료 상태 세션은 더 바뀌지 않으므로 stale이
     * 아니다.
     */
    public boolean isSessionStale(Instant observedAt, Instant requestedAt, boolean terminal, Instant now) {
        if (terminal) {
            return false;
        }
        Instant baseline = observedAt != null ? observedAt : requestedAt;
        return baseline != null && isStale(baseline, now);
    }

    /**
     * 기기 판정 결과를 래치에 반영하고 '신선 → 확인 불가' 첫 판정이면 시한 발동으로 센다.
     *
     * @param verdict 이번 판정
     * @param reportedAt 사본의 마지막 보고 시각(없으면 null)
     */
    public void observeDevice(UUID deviceId, DeviceVerdict verdict, Instant reportedAt, Instant now) {
        if (deviceId == null || verdict == null) {
            return;
        }
        boolean staleNow = verdict.network() == DeviceVerdict.NetworkState.UNKNOWN;
        if (deviceStale.size() >= MAX_TRACKED_DEVICES && !deviceStale.containsKey(deviceId)) {
            deviceStale.clear();
        }
        Boolean previous = deviceStale.put(deviceId, staleNow);
        if (staleNow && !Boolean.TRUE.equals(previous)) {
            metrics.timeoutFired("DEVICE_STATUS_STALE");
            long elapsedMs = reportedAt == null ? 0L : Math.max(0L, Duration.between(reportedAt, now).toMillis());
            events.log(EventCode.TIMEOUT_FIRED, EventLog.Component.DEVICE_REGISTRY, deviceId, entry -> entry
                    .attr(AttrKey.TYPE, "DEVICE_STATUS_STALE")
                    .attr(AttrKey.THRESHOLD_MS, stale.toMillis())
                    .attr(AttrKey.ELAPSED_MS, elapsedMs));
        }
    }
}
