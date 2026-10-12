package kr.mujoong.core.identity.google;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 구글 id_token 서명 열쇠(JWKS) 읽기 (U2 GuardianIdentity · 리뷰 1 · R1 · U2-J2).
 *
 * <p>설정 {@code mujoong.guardian.google.jwks-uri}(https만, 시험은 루프백 http)를 읽는다. 캐시는 {@link GoogleKeyCache}가 맡고, 이 클래스는
 * 응답의 {@code Cache-Control: max-age}를 함께 돌려준다({@code no-store} · {@code no-cache} · 없음이면 0). RSA 서명 열쇠({@code kty RSA},
 * {@code use}가 있으면 {@code sig}, {@code alg}가 있으면 {@code RS256})만 {@code kid}별로 담고 나머지 항목은 건너뛴다. 2xx가 아니거나 형식이
 * 틀리거나 쓸 열쇠가 하나도 없으면 빈 값이다.
 */
public final class GoogleKeysClient {

    private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age\\s*=\\s*\"?(\\d{1,10})\"?\\s*(?:,|$)");
    private static final Pattern NO_CACHE = Pattern.compile("(?:^|,)\\s*(no-store|no-cache)\\s*(?:,|$|=)");

    /** 읽은 열쇠와 응답이 허락한 캐시 시간. */
    public record KeySet(Map<String, PublicKey> keys, Duration maxAge) {

        public KeySet {
            keys = Map.copyOf(keys);
            Objects.requireNonNull(maxAge, "maxAge");
        }
    }

    private final HttpClient http;
    private final URI jwksUri;
    private final Duration timeout;
    private final JsonCodec json;

    public GoogleKeysClient(URI jwksUri, Duration timeout, JsonCodec json) {
        this.jwksUri = Objects.requireNonNull(jwksUri, "jwksUri");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.json = Objects.requireNonNull(json, "json");
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 열쇠를 읽는다(kid → 공개 열쇠 + 캐시 시간). */
    public Optional<KeySet> fetch() {
        HttpRequest request = HttpRequest.newBuilder(jwksUri).timeout(timeout).header("Accept", "application/json").GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            return Optional.empty();
        }
        Map<?, ?> document;
        try {
            document = json.read(response.body(), Map.class);
        } catch (JsonCodec.JsonCodecException e) {
            return Optional.empty();
        }
        if (document == null || !(document.get("keys") instanceof List<?> entries)) {
            return Optional.empty();
        }
        Map<String, PublicKey> keys = new HashMap<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> jwk) {
                rsaSigningKey(jwk).ifPresent(key -> keys.put((String) jwk.get("kid"), key));
            }
        }
        return keys.isEmpty()
                ? Optional.empty()
                : Optional.of(new KeySet(keys, maxAge(response.headers().firstValue("Cache-Control").orElse(""))));
    }

    /** {@code Cache-Control}의 max-age(초). no-store · no-cache · 없음 · 형식 오류는 0(캐시하지 않음). */
    static Duration maxAge(String cacheControl) {
        String value = cacheControl == null ? "" : cacheControl.toLowerCase(Locale.ROOT);
        if (NO_CACHE.matcher(value).find()) {
            return Duration.ZERO;
        }
        Matcher matcher = MAX_AGE.matcher(value);
        return matcher.find() ? Duration.ofSeconds(Long.parseLong(matcher.group(1))) : Duration.ZERO;
    }

    /** JWK 하나를 RSA 서명 열쇠로 읽는다. 조건에 맞지 않으면 빈 값. */
    static Optional<PublicKey> rsaSigningKey(Map<?, ?> jwk) {
        boolean usable = "RSA".equals(jwk.get("kty"))
                && jwk.get("kid") instanceof String kid && !kid.isBlank()
                && (jwk.get("use") == null || "sig".equals(jwk.get("use")))
                && (jwk.get("alg") == null || "RS256".equals(jwk.get("alg")));
        if (!usable || !(jwk.get("n") instanceof String modulus) || !(jwk.get("e") instanceof String exponent)) {
            return Optional.empty();
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, decoder.decode(modulus)),
                    new BigInteger(1, decoder.decode(exponent)));
            return Optional.of(KeyFactory.getInstance("RSA").generatePublic(spec));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }
}
