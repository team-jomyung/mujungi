package kr.mujoong.core.identity.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 구글 id_token 확인(U2 · R1 · U2-J2): iss · aud · exp · sub만 보고 서명은 TLS 직접 교환을 믿는다. */
@Tag("u2")
class GoogleIdTokenReaderTest {

    static final String CLIENT_ID = "test-client.apps.invalid";
    static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private final GoogleIdTokenReader reader = new GoogleIdTokenReader(JsonCodec.shared());

    @Test
    void aGoogleIssuedTokenForOurClientThatHasNotExpiredGivesTheSubject() {
        assertThat(reader.read(token(claims("https://accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "sub-1")),
                CLIENT_ID, NOW)).hasValueSatisfying(identity -> assertThat(identity.subject()).isEqualTo("sub-1"));
        assertThat(reader.read(token(claims("accounts.google.com", List.of("other", CLIENT_ID), NOW.plusSeconds(60), "sub-2")),
                CLIENT_ID, NOW)).isPresent();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "sub-1")), CLIENT_ID, NOW)
                .orElseThrow().toString()).doesNotContain("sub-1");
    }

    @Test
    void wrongIssuerAudienceExpiryOrSubjectIsRejected() {
        assertThat(reader.read(token(claims("https://evil.invalid", CLIENT_ID, NOW.plusSeconds(60), "s")), CLIENT_ID, NOW))
                .isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", "other", NOW.plusSeconds(60), "s")), CLIENT_ID, NOW))
                .isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", 7, NOW.plusSeconds(60), "s")), CLIENT_ID, NOW))
                .isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW, "s")), CLIENT_ID, NOW))
                .as("exp가 지금이면 만료").isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), " ")), CLIENT_ID, NOW))
                .isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "x".repeat(256))),
                CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read(token(Map.of("iss", "accounts.google.com", "aud", CLIENT_ID, "sub", "s")), CLIENT_ID, NOW))
                .as("exp 없음").isEmpty();
    }

    @Test
    void malformedTokensAndMissingInputsAreRejected() {
        assertThat(reader.read(null, CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read("a.b", CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read("a..c", CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read("a.%%%.c", CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read("a." + b64("not json") + ".c", CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "s")), " ", NOW)).isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "s")), CLIENT_ID, null))
                .isEmpty();
    }

    static Map<String, Object> claims(String iss, Object aud, Instant exp, String sub) {
        return Map.of("iss", iss, "aud", aud, "exp", exp.getEpochSecond(), "sub", sub);
    }

    /** 서명 칸은 비밀이 아닌 자리표시자다(서명을 검증하지 않는다, U2-J2). */
    static String token(Map<String, Object> claims) {
        return b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + b64(JsonCodec.shared().write(claims)) + ".c2lnbmF0dXJl";
    }

    static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
