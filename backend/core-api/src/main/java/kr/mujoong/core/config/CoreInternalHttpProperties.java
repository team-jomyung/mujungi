package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.httpUrl;
import static kr.mujoong.core.config.CoreProperties.nonNegative;
import static kr.mujoong.core.config.CoreProperties.positive;

import java.net.URI;
import java.time.Duration;

/** Core → Realtime 호출 제한과 session_start 차단기. 주소 키는 . */
public record CoreInternalHttpProperties(
        URI realtimeBaseUrl,
        Duration connectTimeout,
        Duration sessionStartTimeout,
        Integer sessionStartRetries,
        Duration sessionEndTimeout,
        Duration sessionViewTimeout,
        Duration eventPublishTimeout,
        Integer breakerFailureThreshold,
        Duration breakerOpenDuration) {

    public CoreInternalHttpProperties {
        httpUrl(realtimeBaseUrl, "mujoong.internal-http.realtime-base-url");
        positive(connectTimeout, "mujoong.internal-http.connect-timeout");
        positive(sessionStartTimeout, "mujoong.internal-http.session-start-timeout");
        nonNegative(sessionStartRetries, "mujoong.internal-http.session-start-retries");
        positive(sessionEndTimeout, "mujoong.internal-http.session-end-timeout");
        positive(sessionViewTimeout, "mujoong.internal-http.session-view-timeout");
        positive(eventPublishTimeout, "mujoong.internal-http.event-publish-timeout");
        positive(breakerFailureThreshold, "mujoong.internal-http.breaker-failure-threshold");
        positive(breakerOpenDuration, "mujoong.internal-http.breaker-open-duration");
    }
}
