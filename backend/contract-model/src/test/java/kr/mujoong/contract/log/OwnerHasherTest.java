package kr.mujoong.contract.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ownerHash = HMAC-SHA-256(비밀, 소유 id)의 16진 앞 12자(OD-4 · NFR9.15 · J124). 비밀은 실행마다 만든다. */
@Tag("skeleton")
class OwnerHasherTest {

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void hashIsFirst12HexOfHmacSha256OverTheIdText() throws Exception {
        String secret = randomSecret();
        UUID owner = UUID.randomUUID();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(owner.toString().getBytes(StandardCharsets.UTF_8)))
                .substring(0, 12);

        OwnerHasher hasher = new OwnerHasher(secret);

        assertThat(hasher.hash(owner)).isEqualTo(expected).hasSize(OwnerHasher.HASH_HEX_LENGTH).matches("^[0-9a-f]{12}$");
        assertThat(hasher.hash(owner.toString())).isEqualTo(expected);
    }

    @Test
    void sameSecretGivesSameHashAcrossInstancesAndDifferentSecretDiffers() {
        String secret = randomSecret();
        UUID owner = UUID.randomUUID();

        assertThat(new OwnerHasher(secret).hash(owner)).isEqualTo(new OwnerHasher(secret).hash(owner));
        assertThat(new OwnerHasher(randomSecret()).hash(owner)).isNotEqualTo(new OwnerHasher(secret).hash(owner));
        assertThat(new OwnerHasher(secret).hash(UUID.randomUUID())).isNotEqualTo(new OwnerHasher(secret).hash(owner));
    }

    @Test
    void missingOwnerGivesNull() {
        OwnerHasher hasher = OwnerHasher.ephemeral();

        assertThat(hasher.hash((UUID) null)).isNull();
        assertThat(hasher.hash((String) null)).isNull();
    }

    @Test
    void secretShorterThan32BytesOrBlankIsRejectedWithoutEchoingIt() {
        String shortSecret = "s".repeat(OwnerHasher.MIN_SECRET_BYTES - 1);

        assertThatThrownBy(() -> new OwnerHasher(shortSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortSecret);
        assertThatThrownBy(() -> new OwnerHasher(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnerHasher(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OwnerHasher("s".repeat(OwnerHasher.MIN_SECRET_BYTES)).hash("x")).hasSize(12);
    }

    @Test
    void ephemeralHashersDifferPerInstanceAndToStringHidesTheSecret() {
        UUID owner = UUID.randomUUID();
        String secret = randomSecret();

        assertThat(OwnerHasher.ephemeral().hash(owner)).isNotEqualTo(OwnerHasher.ephemeral().hash(owner));
        assertThat(new OwnerHasher(secret).toString()).doesNotContain(secret).isEqualTo("OwnerHasher[secret=***]");
    }
}
