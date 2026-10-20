package kr.mujoong.core.identity.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.support.ParentAccountFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 구글 id_token 확인(U2 · R1 · U2-J2 · 리뷰 1): 구글 JWKS 열쇠로 RS256 서명을 먼저 확인하고 iss · aud · exp · sub를 본다. 열쇠는 실행마다 만든
 * 시험 열쇠다.
 */
@Tag("parent-account")
class GoogleIdTokenReaderTest {

    static final String CLIENT_ID = "test-client.apps.invalid";
    static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    static final KeyPair GOOGLE = ParentAccountFixtures.newKeyPair();
    static final Map<String, PublicKey> KEYS = Map.of("k1", GOOGLE.getPublic());

    private final GoogleIdTokenReader reader = new GoogleIdTokenReader(JsonCodec.shared());

    @Test
    void aGoogleSignedTokenForOurClientThatHasNotExpiredGivesTheSubject() {
        assertThat(reader.read(token(claims("https://accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "sub-1")),
                KEYS, CLIENT_ID, NOW)).hasValueSatisfying(identity -> assertThat(identity.subject()).isEqualTo("sub-1"));
        assertThat(reader.read(token(claims("accounts.google.com", List.of("other", CLIENT_ID), NOW.plusSeconds(60), "sub-2")),
                KEYS, CLIENT_ID, NOW)).isPresent();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "sub-1")), KEYS, CLIENT_ID,
                NOW).orElseThrow().toString()).doesNotContain("sub-1");
    }

    @Test
    void anUnsignedForgedOrForeignKeySignatureIsRejected() {
        Map<String, Object> good = claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "s");
        String signed = token(good);
        String[] parts = signed.split("\\.");

        assertThat(reader.read(parts[0] + "." + parts[1] + ".c2ln", KEYS, CLIENT_ID, NOW)).as("서명 자리표시자").isEmpty();
        assertThat(reader.read(ParentAccountFixtures.b64("{\"alg\":\"none\",\"kid\":\"k1\"}") + "." + parts[1] + "." + parts[2], KEYS,
                CLIENT_ID, NOW)).as("alg none").isEmpty();
        assertThat(reader.read(ParentAccountFixtures.sign("{\"alg\":\"HS256\",\"kid\":\"k1\"}", good, GOOGLE.getPrivate()), KEYS, CLIENT_ID,
                NOW)).as("RS256이 아님").isEmpty();
        assertThat(reader.read(ParentAccountFixtures.sign("{\"alg\":\"RS256\"}", good, GOOGLE.getPrivate()), KEYS, CLIENT_ID, NOW))
                .as("kid 없음").isEmpty();
        assertThat(reader.read(ParentAccountFixtures.sign("{\"alg\":\"RS256\",\"kid\":\"k2\"}", good, GOOGLE.getPrivate()), KEYS,
                CLIENT_ID, NOW)).as("모르는 kid").isEmpty();
        assertThat(reader.read(ParentAccountFixtures.sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", good, ParentAccountFixtures.newKeyPair().getPrivate()),
                KEYS, CLIENT_ID, NOW)).as("다른 열쇠로 서명").isEmpty();
        String tampered = parts[0] + "." + ParentAccountFixtures.b64(JsonCodec.shared().write(
                claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "someone-else"))) + "." + parts[2];
        assertThat(reader.read(tampered, KEYS, CLIENT_ID, NOW)).as("내용을 바꾼 토큰").isEmpty();
        assertThat(reader.read(parts[0] + "." + parts[1] + ".%%%", KEYS, CLIENT_ID, NOW)).as("서명 형식 오류").isEmpty();
        assertThat(reader.read(signed, Map.of(), CLIENT_ID, NOW)).as("열쇠 없음").isEmpty();
        assertThat(reader.read(signed, null, CLIENT_ID, NOW)).isEmpty();
    }

    @Test
    void wrongIssuerAudienceExpiryOrSubjectIsRejected() {
        assertThat(reader.read(token(claims("https://evil.invalid", CLIENT_ID, NOW.plusSeconds(60), "s")), KEYS, CLIENT_ID, NOW))
                .isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", "other", NOW.plusSeconds(60), "s")), KEYS, CLIENT_ID, NOW))
                .isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", 7, NOW.plusSeconds(60), "s")), KEYS, CLIENT_ID, NOW))
                .isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW, "s")), KEYS, CLIENT_ID, NOW))
                .as("exp가 지금이면 만료").isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), " ")), KEYS, CLIENT_ID, NOW))
                .isEmpty();
        assertThat(reader.read(token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "x".repeat(256))),
                KEYS, CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read(token(Map.of("iss", "accounts.google.com", "aud", CLIENT_ID, "sub", "s")), KEYS, CLIENT_ID, NOW))
                .as("exp 없음").isEmpty();
    }

    @Test
    void malformedTokensAndMissingInputsAreRejected() {
        String good = token(claims("accounts.google.com", CLIENT_ID, NOW.plusSeconds(60), "s"));
        assertThat(reader.read(null, KEYS, CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read("a.b", KEYS, CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read("a..c", KEYS, CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read("%%%.b.c", KEYS, CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read(ParentAccountFixtures.b64("not json") + ".b.c", KEYS, CLIENT_ID, NOW)).isEmpty();
        assertThat(reader.read(good, KEYS, " ", NOW)).isEmpty();
        assertThat(reader.read(good, KEYS, CLIENT_ID, null)).isEmpty();
    }

    static Map<String, Object> claims(String iss, Object aud, Instant exp, String sub) {
        return Map.of("iss", iss, "aud", aud, "exp", exp.getEpochSecond(), "sub", sub);
    }

    /** 시험 열쇠(kid k1)로 서명한 토큰. */
    static String token(Map<String, Object> claims) {
        return ParentAccountFixtures.sign("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}", claims, GOOGLE.getPrivate());
    }
}
