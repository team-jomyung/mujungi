package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;

/** DISCONNECTED 점검 주기 · U3 불안정 판정 끊김 횟수(창 안 이 횟수 이상이면 UNSTABLE). */
public record RealtimeDeviceProperties(Duration disconnectCheckInterval, Integer unstableDisconnectCount) {
    public RealtimeDeviceProperties {
        positive(disconnectCheckInterval, "mujoong.device.disconnect-check-interval");
        positive(unstableDisconnectCount, "mujoong.device.unstable-disconnect-count");
    }
}
