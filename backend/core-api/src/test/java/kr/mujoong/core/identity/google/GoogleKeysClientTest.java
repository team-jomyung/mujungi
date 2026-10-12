package kr.mujoong.core.identity.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.support.U2Fixtures;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 구글 서명 열쇠(JWKS) 읽기(U2 · 리뷰 1 · U2-J2): RSA 서명 열쇠만 kid별로, 실패 · 빈 목록은 빈 값. 가짜 엔드포인트(루프백 무작위 포트). */
@Tag("u2")
class GoogleKeysClientTest {

    private final ContractStubServer google = ContractStubServer.start();

    @AfterEach
    void stop() {
        google.close();
    }

    @Test
    void rsaSigningKeysAreReadByKid() {
        PublicKey key = U2Fixtures.newKeyPair().getPublic();
        Map<String, Object> rsa = jwk(U2Fixtures.jwks("k1", key));
        String document = JsonCodec.shared().write(Map.of("keys", List.of(rsa,
                Map.of("kty", "EC", "kid", "ec", "crv", "P-256", "x", "AA", "y", "AA"),
                with(rsa, "kid", "enc", "use", "enc"),
                with(rsa, "kid", "hs", "alg", "HS256"),
                with(rsa, "kid", "bad", "n", "%%%"),
                Map.of("kty", "RSA", "kid", "no-n", "e", "AQAB"),
                "not an object")));
        google.route("GET", "/certs").respond(StubResponse.json(200, document));

        GoogleKeysClient.KeySet fetched = client().fetch().orElseThrow();
        Map<String, PublicKey> keys = fetched.keys();

        assertThat(keys).containsOnlyKeys("k1");
        assertThat(keys.get("k1")).isEqualTo(key);
        assertThat(fetched.maxAge()).as("Cache-Control 없음 → 캐시하지 않음").isZero();
    }

    @Test
    void cacheControlMaxAgeIsReadFromTheResponse() throws IOException {
        // 2차 리뷰 4: 구글 certs 응답의 max-age만큼 캐시한다(대역은 루프백 무작위 포트)
        String document = U2Fixtures.jwks("k1", U2Fixtures.newKeyPair().getPublic());
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/certs", exchange -> {
            byte[] body = document.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=21600, must-revalidate, no-transform");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/certs");
            GoogleKeysClient.KeySet fetched = new GoogleKeysClient(uri, Duration.ofSeconds(2), JsonCodec.shared())
                    .fetch().orElseThrow();
            assertThat(fetched.maxAge()).isEqualTo(Duration.ofHours(6));
            assertThat(fetched.keys()).containsOnlyKeys("k1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maxAgeParsingIsStrictAndNoCacheWins() {
        assertThat(GoogleKeysClient.maxAge("public, max-age=21600, must-revalidate")).isEqualTo(Duration.ofSeconds(21600));
        assertThat(GoogleKeysClient.maxAge("MAX-AGE=60")).isEqualTo(Duration.ofSeconds(60));
        assertThat(GoogleKeysClient.maxAge("max-age=\"30\"")).isEqualTo(Duration.ofSeconds(30));
        assertThat(GoogleKeysClient.maxAge("no-store, max-age=60")).isZero();
        assertThat(GoogleKeysClient.maxAge("max-age=60, no-cache")).isZero();
        assertThat(GoogleKeysClient.maxAge("s-maxage=60")).as("다른 지시어").isZero();
        assertThat(GoogleKeysClient.maxAge("max-age=-1")).isZero();
        assertThat(GoogleKeysClient.maxAge("max-age=abc")).isZero();
        assertThat(GoogleKeysClient.maxAge("")).isZero();
        assertThat(GoogleKeysClient.maxAge(null)).isZero();
    }

    @Test
    void anUnreachableFailingOrEmptyKeySetIsEmpty() {
        google.route("GET", "/certs").respond(StubResponse.json(500, "{}"));
        assertThat(client().fetch()).isEmpty();
        google.route("GET", "/certs").respond(StubResponse.json(200, "not json"));
        assertThat(client().fetch()).isEmpty();
        google.route("GET", "/certs").respond(StubResponse.json(200, "{\"keys\":\"x\"}"));
        assertThat(client().fetch()).isEmpty();
        google.route("GET", "/certs").respond(StubResponse.json(200, "{\"keys\":[]}"));
        assertThat(client().fetch()).isEmpty();
        assertThat(new GoogleKeysClient(URI.create("http://127.0.0.1:9/certs"), Duration.ofSeconds(2), JsonCodec.shared())
                .fetch()).as("연결 실패").isEmpty();
    }

    private GoogleKeysClient client() {
        return new GoogleKeysClient(URI.create(google.baseUrl() + "/certs"), Duration.ofSeconds(2), JsonCodec.shared());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jwk(String jwks) {
        Map<String, Object> document = JsonCodec.shared().read(jwks, Map.class);
        return ((List<Map<String, Object>>) document.get("keys")).get(0);
    }

    private static Map<String, Object> with(Map<String, Object> base, String k1, String v1, String k2, String v2) {
        Map<String, Object> copy = new java.util.HashMap<>(base);
        copy.put(k1, v1);
        copy.put(k2, v2);
        return copy;
    }
}
