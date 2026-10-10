package kr.mujoong.core.device;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;

/**
 * 기기 판정 (U1 3.5 Step 19 · BR4.3 · BR4.4 · BR2.4 · RD-6 · NFR4.3 · AC2.1.1 ~ AC2.1.6 · AC1.4.2 ~ AC1.4.4).
 *
 * <p>읽을 때 등록 축 + 사본 + 최신성으로 계산하고 첫 해당이 이긴다:
 *
 * <ol>
 *   <li>활성(REGISTERED) 기기 없음 → NONE · NO_DEVICE
 *   <li>사본 없음(한 번도 보고되지 않음) → UNRESPONSIVE · DEVICE_UNRESPONSIVE
 *   <li>{@code now − reported_at > core.device-status-stale}(30초) → UNKNOWN · DEVICE_STATUS_UNKNOWN(30초 정각은 아직 신선)
 *   <li>CONNECTED → STABLE · canStart true
 *   <li>UNSTABLE → UNSTABLE · DEVICE_UNSTABLE
 *   <li>DISCONNECTED → DISCONNECTED · DEVICE_DISCONNECTED
 *   <li>UNRESPONSIVE 또는 OFFLINE(그리고 모르는 값) → UNRESPONSIVE · DEVICE_UNRESPONSIVE
 * </ol>
 *
 * <p>canStart = 활성 기기 있음 ∧ STABLE(BR4.4). 교체 등록(BR2.4)은 STABLE · UNSTABLE일 때만 막는다. 판정 내부값(시각 차 등)은 응답에
 * 내보내지 않는다(BR4.6).
 */
public record DeviceVerdict(NetworkState network, boolean canStart, ReasonCode reasonCode) {

    /** 05 · 04 네트워크 줄 상태(DeviceView.network.state). */
    public enum NetworkState {
        STABLE,
        UNSTABLE,
        DISCONNECTED,
        UNRESPONSIVE,
        NONE,
        UNKNOWN
    }

    /** 시작할 수 없는 이유(DeviceView.verdict.reasonCode · 탭 잠금 사유). */
    public enum ReasonCode {
        NO_DEVICE,
        DEVICE_UNSTABLE,
        DEVICE_DISCONNECTED,
        DEVICE_UNRESPONSIVE,
        DEVICE_STATUS_UNKNOWN
    }

    public DeviceVerdict {
        Objects.requireNonNull(network, "network");
        if (canStart != (reasonCode == null)) {
            throw new IllegalArgumentException("시작 가능이면 사유가 없고, 불가면 사유가 있어야 한다");
        }
    }

    /**
     * 판정한다.
     *
     * @param active 보호자의 활성 기기(없으면 null)
     * @param now 주입한 Clock 값
     * @param stale {@code mujoong.timing.core.device-status-stale}
     */
    public static DeviceVerdict evaluate(DeviceRecord active, Instant now, Duration stale) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(stale, "stale");
        if (active == null || active.registrationStatus() != RegistrationStatus.REGISTERED) {
            return blocked(NetworkState.NONE, ReasonCode.NO_DEVICE);
        }
        if (!active.hasConnectionCopy()) {
            return blocked(NetworkState.UNRESPONSIVE, ReasonCode.DEVICE_UNRESPONSIVE);
        }
        if (isStale(active.reportedAt(), now, stale)) {
            return blocked(NetworkState.UNKNOWN, ReasonCode.DEVICE_STATUS_UNKNOWN);
        }
        ConnectionState state = active.reportedConnectionState();
        return switch (state) {
            case CONNECTED -> new DeviceVerdict(NetworkState.STABLE, true, null);
            case UNSTABLE -> blocked(NetworkState.UNSTABLE, ReasonCode.DEVICE_UNSTABLE);
            case DISCONNECTED -> blocked(NetworkState.DISCONNECTED, ReasonCode.DEVICE_DISCONNECTED);
            case UNRESPONSIVE, OFFLINE, UNRECOGNIZED -> blocked(NetworkState.UNRESPONSIVE, ReasonCode.DEVICE_UNRESPONSIVE);
        };
    }

    /** {@code now − observedAt}이 {@code stale}을 넘었는가(같으면 아직 신선, BR4.3 ③ · BR6.15). */
    public static boolean isStale(Instant observedAt, Instant now, Duration stale) {
        return Duration.between(Objects.requireNonNull(observedAt, "observedAt"), now).compareTo(stale) > 0;
    }

    /** 교체 등록을 허용하는가(BR2.4: 연결 중(STABLE · UNSTABLE)이 아니면 허용). */
    public boolean allowsReplacement() {
        return network != NetworkState.STABLE && network != NetworkState.UNSTABLE;
    }

    private static DeviceVerdict blocked(NetworkState network, ReasonCode reason) {
        return new DeviceVerdict(network, false, reason);
    }
}
