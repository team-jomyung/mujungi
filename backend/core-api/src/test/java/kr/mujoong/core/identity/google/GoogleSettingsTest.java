package kr.mujoong.core.identity.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import kr.mujoong.core.config.CoreGuardianProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 구글 토큰 엔드포인트 주소 규칙(U2 · 리뷰 1 · R1): 서명 확인 열쇠와 토큰을 받는 주소는 https만, 평문 http는 루프백 시험 주소만 받는다.
 * 설정 실수로 평문 · 남의 호스트에서 토큰을 받으면 기동을 거부한다.
 */
@Tag("u2")
class GoogleSettingsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @ParameterizedTest
    @ValueSource(strings = {"http://oauth2.example.invalid/token", "http://10.0.0.1/token", "ftp://127.0.0.1/token",
            "https:///token", "file:///token"})
    void aPlainOrForeignTokenUriIsRejectedAtStartup(String tokenUri) {
        assertThatThrownBy(() -> google(URI.create(tokenUri), URI.create("https://keys.example.invalid/certs")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.guardian.google.token-uri")
                .hasMessageNotContaining("example");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://keys.example.invalid/certs", "http://192.168.0.1/certs", "ftp://localhost/certs"})
    void aPlainOrForeignKeySetUriIsRejectedAtStartup(String jwksUri) {
        assertThatThrownBy(() -> google(URI.create("https://oauth2.example.invalid/token"), URI.create(jwksUri)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.guardian.google.jwks-uri");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://oauth2.example.invalid/token", "http://127.0.0.1:41234/token",
            "http://localhost:41234/token", "http://[::1]:41234/token"})
    void httpsOrALoopbackTestAddressIsAccepted(String uri) {
        CoreGuardianProperties.Google google =google(URI.create(uri), URI.create(uri.replace("/token", "/certs")));

        assertThat(google.tokenUri()).isEqualTo(URI.create(uri));
        assertThat(google.jwksUri().getPath()).isEqualTo("/certs");
        assertThatThrownBy(() -> google(URI.create(uri), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStringFormStillHidesTheSecret() {
        assertThat(new CoreGuardianProperties.Google("id", "secret-value", "https://mujoong.invalid/cb",
                URI.create("https://oauth2.example.invalid/token"), URI.create("https://keys.example.invalid/certs"), TIMEOUT)
                .toString()).doesNotContain("secret-value").contains("<redacted>", "jwksUri=");
    }

    private static CoreGuardianProperties.Google google(URI tokenUri, URI jwksUri) {
        return new CoreGuardianProperties.Google("id", "secret", "https://mujoong.invalid/cb", tokenUri, jwksUri, TIMEOUT);
    }
}
