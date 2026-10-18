package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.httpUrl;
import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.net.URI;
import java.time.Duration;

/** RD-14 Realtime → Core 호출 제한과 상태 보고 라운드 건너뛰기(R3-2). 주소 키는 J109. */
public record RealtimeInternalHttpProperties(
        URI coreBaseUrl,
        Duration connectTimeout,
        Duration verifyTokenTimeout,
        Duration deviceStatusTimeout,
        Duration eventPublishTimeout,
        Integer statusRoundSkipThreshold) {

    public RealtimeInternalHttpProperties {
        httpUrl(coreBaseUrl, "mujoong.internal-http.core-base-url");
        positive(connectTimeout, "mujoong.internal-http.connect-timeout");
        positive(verifyTokenTimeout, "mujoong.internal-http.verify-token-timeout");
        positive(deviceStatusTimeout, "mujoong.internal-http.device-status-timeout");
        positive(eventPublishTimeout, "mujoong.internal-http.event-publish-timeout");
        positive(statusRoundSkipThreshold, "mujoong.internal-http.status-round-skip-threshold");
    }
}
