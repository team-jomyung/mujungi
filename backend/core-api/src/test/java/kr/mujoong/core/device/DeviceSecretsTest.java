package kr.mujoong.core.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import kr.mujoong.contract.secret.SecretCompare;
import kr.mujoong.core.device.DeviceSecrets.Secret;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** bootstrap 비밀 128비트 · 기기 토큰 256비트 · base64url · 해시만 저장 (BR1.5 · BR3.3 · NFR9.4). */
@Tag("u1")
class DeviceSecretsTest {

    private final DeviceSecrets secrets = new DeviceSecrets();

    @Test
    void bootstrapSecretIs128BitBase64UrlWithoutPadding() {
        Secret secret = secrets.newBootstrapSecret();

        assertThat(secret.value()).matches("^[A-Za-z0-9_-]+$").doesNotContain("=");
        assertThat(Base64.getUrlDecoder().decode(secret.value())).hasSize(16);
        assertThat(DeviceSecrets.looksLikeSecret(secret.value())).isTrue();
    }

    @Test
    void deviceTokenIs256BitBase64Url() {
        Secret token = secrets.newDeviceToken();

        assertThat(Base64.getUrlDecoder().decode(token.value())).hasSize(32);
        assertThat(token.value()).hasSize(43);
    }

    @Test
    void storedFormIsSha256HexOnly() {
        Secret token = secrets.newDeviceToken();

        assertThat(token.hash()).matches("^[0-9a-f]{64}$").isEqualTo(DeviceSecrets.hash(token.value()));
        assertThat(token.hash()).doesNotContain(token.value());
        assertThat(SecretCompare.matchesHash(token.value(), token.hash())).isTrue();
        assertThat(SecretCompare.matchesHash(secrets.newDeviceToken().value(), token.hash())).isFalse();
    }

    @Test
    void secretsAreUniqueAcrossDraws() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(secrets.newBootstrapSecret().value());
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void toStringMasksTheValue() {
        Secret token = secrets.newDeviceToken();

        assertThat(token.toString()).doesNotContain(token.value());
    }

    @Test
    void malformedCookieValuesAreNotSecrets() {
        assertThat(DeviceSecrets.looksLikeSecret(null)).isFalse();
        assertThat(DeviceSecrets.looksLikeSecret("short")).isFalse();
        assertThat(DeviceSecrets.looksLikeSecret("has spaces in the cookie value")).isFalse();
        assertThat(DeviceSecrets.looksLikeSecret("x".repeat(129))).isFalse();
        assertThatThrownBy(() -> new Secret(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
