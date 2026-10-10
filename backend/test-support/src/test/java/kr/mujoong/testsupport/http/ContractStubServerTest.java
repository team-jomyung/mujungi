package kr.mujoong.testsupport.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 계약 대역 서버 자기 시험 (U1 3.5 Step 28 · RD-23 · J129). 스키마 위반 요청 거부 · 스크립트 순서대로 응답 · 받은 본문 기록 · 토큰 확인 ·
 * 지연 응답을 본다.
 */
@Tag("u1")
class ContractStubServerTest {

    private static final String END_PATH = "/internal/sessions/{sessionId}/end";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();
    private ContractStubServer server;

    @BeforeEach
    void start() {
        server = ContractStubServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void validRequestGetsScriptedResponsesInOrderAndLastRepeats() throws Exception {
        server.route("POST", END_PATH).requestSchema("internal-session-end-request.schema.json").respond(
                StubResponse.json(202, "{\"status\":\"CLOSING\"}"),
                StubResponse.error(409, ErrorCode.SESSION_NOT_ACTIVE));
        String path = "/internal/sessions/" + UUID.randomUUID() + "/end";
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"GUARDIAN_STOP\"}";

        HttpResponse<String> first = post(path, body);
        HttpResponse<String> second = post(path, body);
        HttpResponse<String> third = post(path, body);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.body()).isEqualTo("{\"status\":\"CLOSING\"}");
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("\"code\":\"SESSION_NOT_ACTIVE\"").contains("\"traceId\":\"");
        assertThat(third.statusCode()).isEqualTo(409);
        assertThat(server.requests("POST", END_PATH)).hasSize(3).allMatch(r -> r.schemaErrors().isEmpty());
    }

    @Test
    void schemaViolationIsRejectedWithoutConsumingScript() throws Exception {
        server.route("POST", END_PATH).requestSchema("internal-session-end-request.schema.json")
                .respond(StubResponse.json(202, "{}"));
        String path = "/internal/sessions/" + UUID.randomUUID() + "/end";

        HttpResponse<String> unknownReason = post(path, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"LATER\"}");
        HttpResponse<String> notJson = post(path, "not-json");
        HttpResponse<String> valid = post(path, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"CORE_ABANDONED\"}");

        assertThat(unknownReason.statusCode()).isEqualTo(400);
        assertThat(unknownReason.body()).contains("VALIDATION_FAILED");
        assertThat(notJson.statusCode()).isEqualTo(400);
        assertThat(valid.statusCode()).isEqualTo(202);
        assertThat(server.violations()).hasSize(2);
        assertThat(server.violations().get(0).schemaErrors()).isNotEmpty();
    }

    @Test
    void recordsBodyHeadersAndQuery() throws Exception {
        server.route("GET", "/internal/sessions/{sessionId}/view").respond(StubResponse.json(200, "{}"));
        UUID sessionId = UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(server.baseUrl().resolve("/internal/sessions/" + sessionId + "/view?sinceSeq=3"))
                .header("X-Trace-Id", "0123456789abcdef0123456789abcdef")
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Trace-Id")).contains("0123456789abcdef0123456789abcdef");
        RecordedRequest recorded = server.requests().get(0);
        assertThat(recorded.method()).isEqualTo("GET");
        assertThat(recorded.path()).isEqualTo("/internal/sessions/" + sessionId + "/view");
        assertThat(recorded.query()).isEqualTo("sinceSeq=3");
        assertThat(recorded.header("x-trace-id")).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(recorded.respondedStatus()).isEqualTo(200);
    }

    @Test
    void internalTokenMustBeExactlyOneMatchingValue() throws Exception {
        String token = TestSecrets.internalServiceToken();
        server.requireInternalToken(token).route("POST", "/internal/events").respond(StubResponse.json(200, "{}"));

        HttpResponse<String> missing = client.send(HttpRequest.newBuilder(server.baseUrl().resolve("/internal/events"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> wrong = client.send(HttpRequest.newBuilder(server.baseUrl().resolve("/internal/events"))
                .header("X-Internal-Token", TestSecrets.internalServiceToken())
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> right = client.send(HttpRequest.newBuilder(server.baseUrl().resolve("/internal/events"))
                .header("X-Internal-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).contains("UNAUTHENTICATED");
        assertThat(right.statusCode()).isEqualTo(200);
        assertThat(server.requests().get(2).toString()).doesNotContain(token);
    }

    @Test
    void delayedResponseLetsCallerTimeOutAndUnknownRouteIs404() throws Exception {
        server.route("POST", "/internal/sessions").respond(StubResponse.json(202, "{\"status\":\"PREPARING\"}")
                .delayed(Duration.ofSeconds(5)));
        HttpRequest slow = HttpRequest.newBuilder(server.baseUrl().resolve("/internal/sessions"))
                .timeout(Duration.ofMillis(200))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();

        assertThatThrownBy(() -> client.send(slow, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(HttpTimeoutException.class);
        List<RecordedRequest> arrived = server.awaitRequests("POST", "/internal/sessions", 1, Duration.ofSeconds(2));
        assertThat(arrived).hasSize(1);
        assertThat(post("/nowhere", "{}").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(server.baseUrl().resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
