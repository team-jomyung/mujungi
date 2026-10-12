package kr.mujoong.core.identity.google;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 구글 인가 코드 → id_token 교환 (U2 GuardianIdentity · J21 · R1).
 *
 * <p>토큰 엔드포인트(설정 {@code mujoong.guardian.google.token-uri})에 {@code application/x-www-form-urlencoded}로
 * {@code grant_type=authorization_code · code · client_id · client_secret · redirect_uri}를 보낸다. 대상 주소는 설정 값 하나이고 사용자 입력으로
 * 만들지 않는다(SSRF 없음). 2xx가 아니거나 응답에 id_token이 없거나 시간 초과 · 연결 실패면 빈 값이다. 코드 · 비밀 · 토큰은 로그 · 예외에
 * 남기지 않는다.
 */
public final class GoogleTokenClient {

    private final HttpClient http;
    private final URI tokenUri;
    private final Duration timeout;
    private final JsonCodec json;

    public GoogleTokenClient(URI tokenUri, Duration timeout, JsonCodec json) {
        this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.json = Objects.requireNonNull(json, "json");
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 교환 요청 값. 문자열 표현에 값을 내지 않는다. */
    public record Exchange(String code, String clientId, String clientSecret, String redirectUri) {

        @Override
        public String toString() {
            return "Exchange[***]";
        }
    }

    /** 교환한다. 성공이면 id_token. */
    public Optional<String> exchange(Exchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", exchange.code());
        form.put("client_id", exchange.clientId());
        form.put("client_secret", exchange.clientSecret());
        form.put("redirect_uri", exchange.redirectUri());
        String body = form.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
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
        try {
            Map<?, ?> parsed = json.read(response.body(), Map.class);
            return parsed != null && parsed.get("id_token") instanceof String token && !token.isBlank()
                    ? Optional.of(token)
                    : Optional.empty();
        } catch (JsonCodec.JsonCodecException e) {
            return Optional.empty();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
