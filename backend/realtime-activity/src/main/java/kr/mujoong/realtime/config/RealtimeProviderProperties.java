package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.net.URI;
import java.time.Duration;

/** RD-5 · J110 공급자 연결. url은 demo · prod 프로필 파일 또는 환경 변수 MUJOONG_PROVIDER_URL(J141). */
public record RealtimeProviderProperties(URI url, Duration connectTimeout) {
    public RealtimeProviderProperties {
        positive(connectTimeout, "mujoong.provider.connect-timeout");
    }
}
