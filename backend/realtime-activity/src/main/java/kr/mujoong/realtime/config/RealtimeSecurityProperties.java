package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.origin;
import static kr.mujoong.realtime.config.RealtimeProperties.positive;

/** BR3.6 · SD-7 WSS Origin 정확 일치(환경 변수 MUJOONG_SECURITY_ALLOWED_ORIGIN) · OD-3 보안 사건 상한. */
public record RealtimeSecurityProperties(String allowedOrigin, Integer eventLogRateCap) {
    public RealtimeSecurityProperties {
        allowedOrigin = origin(allowedOrigin, "mujoong.security.allowed-origin");
        positive(eventLogRateCap, "mujoong.security.event-log-rate-cap");
    }
}
