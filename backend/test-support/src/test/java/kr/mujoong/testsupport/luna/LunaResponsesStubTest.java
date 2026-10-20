package kr.mujoong.testsupport.luna;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Responses API 루프백 대역 자기 시험 (U9 Step 8 · BR9.1 · 검토 R-02 요청 검사 모드). */
@Tag("luna")
class LunaResponsesStubTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(LunaResponsesStub stub, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stub.baseUrl() + "/responses"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.shared().write(body)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-6-luna");
        body.put("instructions", "지시");
        body.put("input", "{\"a\":1}");
        body.put("text", Map.of("format", Map.of("type", "json_object")));
        body.put("store", false);
        return body;
    }

    @Test
    void scriptedRepliesAreServedInOrderAndTheLastOneRepeats() throws Exception {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            stub.respond(LunaResponsesStub.Reply.rateLimited(7), LunaResponsesStub.Reply.ok("gpt-6-luna", "{\"x\":1}"));

            HttpResponse<String> first = post(stub, validBody());
            HttpResponse<String> second = post(stub, validBody());
            HttpResponse<String> third = post(stub, validBody());

            assertThat(first.statusCode()).isEqualTo(429);
            assertThat(first.headers().firstValue("Retry-After")).contains("7");
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(second.body()).contains("\"status\":\"completed\"", "\"model\":\"gpt-6-luna\"", "output_text");
            assertThat(third.statusCode()).isEqualTo(200);
            assertThat(stub.requestCount()).isEqualTo(3);
            assertThat(stub.requests().getFirst().json()).containsEntry("store", false);
            assertThat(stub.baseUrl().getHost()).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void requestCheckRejectsTemperatureMissingStoreAndUnsupportedSchemaKeywords() throws Exception {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            Map<String, Object> withTemperature = validBody();
            withTemperature.put("temperature", 0.2);
            Map<String, Object> withoutStore = validBody();
            withoutStore.remove("store");
            Map<String, Object> badSchema = validBody();
            badSchema.put("text", Map.of("format", Map.of("type", "json_schema", "name", "x", "strict", false,
                    "schema", Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string", "minLength", 1)),
                            "$id", "https://x.invalid"))));

            assertThat(post(stub, withTemperature).statusCode()).isEqualTo(400);
            assertThat(post(stub, withoutStore).statusCode()).isEqualTo(400);
            assertThat(post(stub, badSchema).statusCode()).isEqualTo(400);
            assertThat(post(stub, validBody()).statusCode()).isEqualTo(200);

            List<LunaResponsesStub.RecordedRequest> requests = stub.requests();
            assertThat(requests.get(0).violations()).anyMatch(v -> v.startsWith("temperature"));
            assertThat(requests.get(1).violations()).anyMatch(v -> v.startsWith("store"));
            assertThat(requests.get(2).violations()).anyMatch(v -> v.contains("/$id"))
                    .anyMatch(v -> v.contains("/properties/a/minLength"));
            assertThat(requests.get(3).violations()).isEmpty();
        }
    }

    @Test
    void strictSchemasMustCloseEveryObjectAndRequireEveryProperty() throws Exception {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            Map<String, Object> open = validBody();
            open.put("text", Map.of("format", Map.of("type", "json_schema", "name", "x", "strict", true,
                    "schema", Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string")),
                            "required", List.of()))));
            Map<String, Object> closed = validBody();
            closed.put("text", Map.of("format", Map.of("type", "json_schema", "name", "x", "strict", true,
                    "schema", Map.of("type", "object", "additionalProperties", false,
                            "properties", Map.of("a", Map.of("type", "string")), "required", List.of("a")))));

            assertThat(post(stub, open).statusCode()).isEqualTo(400);
            assertThat(post(stub, closed).statusCode()).isEqualTo(200);
            assertThat(stub.requests().getFirst().violations()).hasSize(2);
        }
    }

    @Test
    void failureRepliesHaveTheResponsesShapes() throws Exception {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            stub.respond(LunaResponsesStub.Reply.incomplete("m"), LunaResponsesStub.Reply.refusal("m"),
                    LunaResponsesStub.Reply.noModel("{}"), LunaResponsesStub.Reply.emptyOutput("m"),
                    LunaResponsesStub.Reply.redirect("https://elsewhere.invalid/v1/responses"));

            assertThat(post(stub, validBody()).body()).contains("\"status\":\"incomplete\"", "max_output_tokens");
            assertThat(post(stub, validBody()).body()).contains("\"type\":\"refusal\"");
            assertThat(post(stub, validBody()).body()).doesNotContain("\"model\"");
            assertThat(post(stub, validBody()).body()).contains("\"text\":\"\"");
            HttpResponse<String> redirect = post(stub, validBody());
            assertThat(redirect.statusCode()).isEqualTo(302);
            assertThat(redirect.headers().firstValue("Location")).contains("https://elsewhere.invalid/v1/responses");
            assertThat(stub.anyBodyContains("지시")).isTrue();
        }
    }
}
