package kr.mujoong.core.identity.google;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 구글 id_token 읽기 · 확인 (U2 GuardianIdentity · R1 · U2-J2 · 리뷰 1).
 *
 * <p>서명을 먼저 확인한다: 머리의 {@code alg}가 RS256이고 {@code kid}가 구글 JWKS에 있는 열쇠여야 하며 그 열쇠로 SHA256withRSA 서명이 맞아야
 * 한다({@code none} · HS256 · 모르는 kid · 서명 불일치는 모두 거절). 토큰 엔드포인트 주소 설정이 틀리거나 응답이 바뀌어도 서명 없는 토큰으로는 세션을
 * 얻지 못한다. 그다음 내용을 확인한다: iss가 구글, aud에 우리 client id, exp가 지금보다 뒤, sub가 있음. 하나라도 어긋나면 빈 값이다.
 * 토큰 · sub는 로그에 남기지 않는다.
 */
public final class GoogleIdTokenReader {

    /** 구글 발급자(두 표기 모두 정상). */
    public static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    /** 받는 서명 방식(구글 id_token). */
    public static final String ALGORITHM = "RS256";

    private final JsonCodec json;

    public GoogleIdTokenReader(JsonCodec json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 확인된 구글 사용자(sub만, 이메일 없음 U2-J3). */
    public record GoogleIdentity(String subject) {

        public GoogleIdentity {
            Objects.requireNonNull(subject, "subject");
        }

        @Override
        public String toString() {
            return "GoogleIdentity[***]";
        }
    }

    /**
     * 읽고 확인한다.
     *
     * @param idToken 토큰 응답의 id_token(JWS compact)
     * @param keys 구글 서명 열쇠(kid → 공개 열쇠, {@link GoogleKeysClient})
     * @param clientId 우리 client id
     * @param now 확인 시각(Clock)
     */
    public Optional<GoogleIdentity> read(String idToken, Map<String, PublicKey> keys, String clientId, Instant now) {
        if (idToken == null || keys == null || clientId == null || clientId.isBlank() || now == null) {
            return Optional.empty();
        }
        String[] parts = idToken.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return Optional.empty();
        }
        Optional<Map<?, ?>> header = decode(parts[0]);
        if (header.isEmpty() || !signedByGoogle(header.get(), parts, keys)) {
            return Optional.empty();
        }
        return decode(parts[1]).flatMap(claims -> identity(claims, clientId, now));
    }

    /** 토큰 머리의 kid(열쇠 캐시가 모르는 kid인지 보려고 서명 확인 전에 읽는다). 형식이 틀리면 빈 값. */
    public Optional<String> keyId(String idToken) {
        if (idToken == null) {
            return Optional.empty();
        }
        String[] parts = idToken.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty()) {
            return Optional.empty();
        }
        return decode(parts[0]).map(header -> header.get("kid")).filter(String.class::isInstance).map(String.class::cast);
    }

    private static boolean signedByGoogle(Map<?, ?> header, String[] parts, Map<String, PublicKey> keys) {
        if (!ALGORITHM.equals(header.get("alg")) || !(header.get("kid") instanceof String kid) || !keys.containsKey(kid)) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keys.get(kid));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    private static Optional<GoogleIdentity> identity(Map<?, ?> claims, String clientId, Instant now) {
        if (!ISSUERS.contains(String.valueOf(claims.get("iss")))) {
            return Optional.empty();
        }
        if (!audienceContains(claims.get("aud"), clientId)) {
            return Optional.empty();
        }
        if (!(claims.get("exp") instanceof Number exp) || !Instant.ofEpochSecond(exp.longValue()).isAfter(now)) {
            return Optional.empty();
        }
        if (!(claims.get("sub") instanceof String subject) || subject.isBlank() || subject.length() > 255) {
            return Optional.empty();
        }
        return Optional.of(new GoogleIdentity(subject));
    }

    private Optional<Map<?, ?>> decode(String part) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
            Map<?, ?> value = json.read(text, Map.class);
            return Optional.ofNullable(value);
        } catch (IllegalArgumentException | JsonCodec.JsonCodecException e) {
            return Optional.empty();
        }
    }

    private static boolean audienceContains(Object audience, String clientId) {
        if (audience instanceof String single) {
            return clientId.equals(single);
        }
        if (audience instanceof List<?> many) {
            return many.stream().anyMatch(clientId::equals);
        }
        return false;
    }
}
