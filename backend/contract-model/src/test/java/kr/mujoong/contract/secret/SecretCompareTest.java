package kr.mujoong.contract.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 비밀 비교 · 해시(SD-8 · BR1.5 · BR3.3 · NFR9.4). 비교할 값은 실행마다 만든다. */
@Tag("skeleton")
class SecretCompareTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void sha256HexMatchesTheKnownVectorAndIsLowercase64Chars() {
        // FIPS 180-2 "abc" 시험 벡터
        assertThat(SecretCompare.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SecretCompare.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(SecretCompare.sha256Hex("abc"));
        assertThat(SecretCompare.sha256Hex("")).matches("^[0-9a-f]{64}$");
        assertThat(SecretCompare.sha256("abc")).hasSize(32);
    }

    @Test
    void constantTimeEqualsComparesContentNotIdentity() {
        String received = randomValue();
        String expected = new String(received.toCharArray());

        assertThat(SecretCompare.constantTimeEquals(received, expected)).isTrue();
        assertThat(SecretCompare.constantTimeEquals(randomValue(), expected)).isFalse();
        assertThat(SecretCompare.constantTimeEquals(received + "x", expected)).isFalse();
        assertThat(SecretCompare.constantTimeEquals("", "")).isTrue();
    }

    @Test
    void nullOnEitherSideIsNeverEqual() {
        assertThat(SecretCompare.constantTimeEquals(null, "x")).isFalse();
        assertThat(SecretCompare.constantTimeEquals("x", null)).isFalse();
        assertThat(SecretCompare.constantTimeEquals(null, null)).isFalse();
    }

    @Test
    void rawSecretMatchesOnlyItsStoredHash() {
        String raw = randomValue();
        String stored = SecretCompare.sha256Hex(raw);

        assertThat(SecretCompare.matchesHash(raw, stored)).isTrue();
        assertThat(SecretCompare.matchesHash(raw + "x", stored)).isFalse();
        assertThat(SecretCompare.matchesHash(null, stored)).isFalse();
        assertThat(SecretCompare.matchesHash(raw, null)).isFalse();
    }

    @Test
    void storedHashOfTheWrongShapeNeverMatches() {
        String stored = SecretCompare.sha256Hex("v");

        assertThat(SecretCompare.matchesHash("v", stored.toUpperCase())).isFalse();
        assertThat(SecretCompare.matchesHash("v", stored.substring(1))).isFalse();
        assertThat(SecretCompare.matchesHash("v", "z".repeat(64))).isFalse();
    }

    @Test
    void hashingNullIsRejectedWithoutEchoingInput() {
        assertThatThrownBy(() -> SecretCompare.sha256((byte[]) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCompare.sha256((String) null)).isInstanceOf(IllegalArgumentException.class);
    }
}
