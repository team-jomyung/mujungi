package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.origin;
import static kr.mujoong.core.config.CoreProperties.positive;

import java.time.Duration;

/** SD-5 세션 쿠키 수명 · SD-6 허용 origin(환경 변수 MUJOONG_SECURITY_ALLOWED_ORIGIN) · OD-3 보안 사건 상한. */
public record CoreSecurityProperties(Duration sessionCookieMaxAge, String allowedOrigin, Integer eventLogRateCap) {
    public CoreSecurityProperties {
        positive(sessionCookieMaxAge, "mujoong.security.session-cookie-max-age");
        allowedOrigin = origin(allowedOrigin, "mujoong.security.allowed-origin");
        positive(eventLogRateCap, "mujoong.security.event-log-rate-cap");
    }
}
