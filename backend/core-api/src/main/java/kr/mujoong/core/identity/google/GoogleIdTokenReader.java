package kr.mujoong.core.identity.google;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 구글 id_token 읽기 · 확인 (U2 GuardianIdentity · R1 · U2-J2).
 *
 * <p>서버가 구글 토큰 엔드포인트와 TLS로 직접 받은 토큰이라 서명 대신 TLS 서버 확인을 믿고(OIDC Core 3.1.3.7 6번), 내용만 확인한다:
 * iss가 구글, aud에 우리 client id, exp가 지금보다 뒤, sub가 있음. 하나라도 어긋나면 빈 값이다. 토큰 · sub는 로그에 남기지 않는다.
 */
public final class GoogleIdTokenReader {

    /** 구글 발급자(두 표기 모두 정상). */
    public static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

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
     * @param clientId 우리 client id
     * @param now 확인 시각(Clock)
     */
    public Optional<GoogleIdentity> read(String idToken, String clientId, Instant now) {
        if (idToken == null || clientId == null || clientId.isBlank() || now == null) {
            return Optional.empty();
        }
        String[] parts = idToken.split("\\.", -1);
        if (parts.length != 3 || parts[1].isEmpty()) {
            return Optional.empty();
        }
        Map<?, ?> claims;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            claims = json.read(payload, Map.class);
        } catch (IllegalArgumentException | JsonCodec.JsonCodecException e) {
            return Optional.empty();
        }
        if (claims == null || !ISSUERS.contains(String.valueOf(claims.get("iss")))) {
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
