package kr.mujoong.core.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;

/**
 * U2 시험 준비물(시험 소스 전용): 참조 시드 002 적용, 가짜 구글 토큰 · 서명 열쇠 엔드포인트, 시험 id_token. 값은 모두 합성이고 비밀 · 실제 도메인이
 * 없다(client id · redirect는 예약 TLD .invalid, client secret과 서명 열쇠는 실행마다 만든 값).
 */
public final class ParentAccountFixtures {

    /** 시험 client id(비밀 아님). */
    public static final String CLIENT_ID = "u2-test-client.apps.invalid";
    /** 시험 redirect uri(허용 origin과 같은 예약 도메인). */
    public static final String REDIRECT_URI = "https://mujoong.invalid/parent/login/callback";
    /** 가짜 토큰 엔드포인트 경로. */
    public static final String TOKEN_PATH = "/token";
    /** 가짜 서명 열쇠(JWKS) 경로. */
    public static final String JWKS_PATH = "/certs";
    /** 시험 열쇠 id. */
    public static final String KID = "u2-test-key";

    /** 실행마다 만든 시험 서명 열쇠(저장소에 없는 값). */
    private static final KeyPair KEYS = newKeyPair();

    private ParentAccountFixtures() {
    }

    /** 실행마다 새 RSA 열쇠 쌍. */
    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("시험 RSA 열쇠를 만들지 못했다", e);
        }
    }

    /** 시험 서명 열쇠의 공개 열쇠. */
    public static PublicKey publicKey() {
        return KEYS.getPublic();
    }

    /** 공개 열쇠 하나짜리 JWKS 문서. */
    public static String jwks(String kid, PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        return JsonCodec.shared().write(Map.of("keys", List.of(Map.of("kty", "RSA", "use", "sig", "alg", "RS256", "kid", kid,
                "n", unsigned(rsa.getModulus()), "e", unsigned(rsa.getPublicExponent())))));
    }

    /** 머리 · 내용을 열쇠로 서명한 JWS compact. */
    public static String sign(String headerJson, Map<String, Object> claims, PrivateKey key) {
        String signingInput = b64(headerJson) + "." + b64(JsonCodec.shared().write(claims));
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("시험 토큰에 서명하지 못했다", e);
        }
    }

    /** 시험 열쇠로 서명한 id_token 내용. */
    public static String signedToken(Map<String, Object> claims) {
        return sign("{\"alg\":\"RS256\",\"kid\":\"" + KID + "\",\"typ\":\"JWT\"}", claims, KEYS.getPrivate());
    }

    /** 실행마다 만든 client secret(저장소 · 로그에 없는 값). */
    public static String clientSecret() {
        return "s-" + UUID.randomUUID();
    }

    /** 참조 시드 002(약관 4종 · 세션 문구 자리)를 mujoong_owner로 멱등 적용한다. */
    public static void applyU2Seed(MujoongPostgres postgres) {
        try (Connection owner = postgres.connect(Role.OWNER); Statement statement = owner.createStatement()) {
            statement.execute(Files.readString(postgres.repoRoot().resolve("infra/seed/002-u2-reference.sql"),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("U2 참조 시드 적용 실패", e);
        }
    }

    /** 가짜 구글 토큰 엔드포인트 + 서명 열쇠(JWKS) 엔드포인트(요청 · 응답 기록). */
    public static ContractStubServer googleTokenEndpoint() {
        ContractStubServer google = ContractStubServer.start();
        google.route("POST", TOKEN_PATH).respond(StubResponse.json(400, "{\"error\":\"invalid_grant\"}"));
        google.route("GET", JWKS_PATH).respond(StubResponse.json(200, jwks(KID, KEYS.getPublic())));
        return google;
    }

    /** 토큰 엔드포인트가 이 sub의 id_token을 주게 한다. */
    public static void issueFor(ContractStubServer google, String subject, Instant now) {
        google.route("POST", TOKEN_PATH).respond(StubResponse.json(200,
                "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"id_token\":\"" + idToken(CLIENT_ID, subject, now.plusSeconds(300))
                        + "\"}"));
    }

    /** 시험 id_token(시험 열쇠로 서명, U2-J2). */
    public static String idToken(String audience, String subject, Instant expiresAt) {
        return signedToken(Map.of("iss", "https://accounts.google.com", "aud", audience,
                "exp", expiresAt.getEpochSecond(), "sub", subject));
    }

    /** 구글 로그인 요청 본문. */
    public static String loginBody(String code) {
        return "{\"code\":\"" + code + "\",\"redirectUri\":\"" + REDIRECT_URI + "\"}";
    }

    /** JSON 글의 base64url(패딩 없음). */
    public static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** JWK의 부호 없는 큰 정수(앞의 부호 바이트 0을 뺀다). */
    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
