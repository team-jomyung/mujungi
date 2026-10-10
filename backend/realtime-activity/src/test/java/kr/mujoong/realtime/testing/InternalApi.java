package kr.mujoong.realtime.testing;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.testsupport.http.ContractStubServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Realtime 내부 API 시험 호출기(Step 35, 시험 소스 전용). 실제 서버(무작위 포트)에 JDK HttpClient로 보내고 응답 본문을
 * {@code contracts/schemas}(JSON Schema 2020-12)로 검사한다. 토큰 헤더는 정상 · 없음 · 틀림 · 두 개를 고를 수 있다. 루프백이라 프록시를 쓰지
 * 않는다.
 */
public final class InternalApi {

    /** 토큰 헤더를 붙이는 방식. */
    public enum Token {
        VALID,
        MISSING,
        WRONG,
        DUPLICATED
    }

    /** 응답. */
    public record Reply(int status, HttpResponse<String> raw) {

        public String body() {
            return raw.body();
        }

        /** 헤더 첫 값(없으면 null). */
        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        public JsonNode json() {
            return JSON.readTree(raw.body());
        }

        /** 오류 봉투의 code(없으면 null). */
        public String code() {
            JsonNode node = json().get("code");
            return node == null ? null : node.asString();
        }

        @Override
        public String toString() {
            return "Reply[status=" + status + "]";
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final SchemaRegistry SCHEMAS = registry();

    private final URI base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public InternalApi(URI base, String token) {
        this.base = Objects.requireNonNull(base, "base");
        this.token = Objects.requireNonNull(token, "token");
    }

    public Reply post(String path, String body) {
        return send("POST", path, body, Token.VALID, null);
    }

    public Reply post(String path, String body, Token mode) {
        return send("POST", path, body, mode, null);
    }

    public Reply get(String path) {
        return send("GET", path, null, Token.VALID, null);
    }

    /** 요청을 보낸다. */
    public Reply send(String method, String path, String body, Token mode, String traceId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        switch (mode) {
            case VALID -> builder.header("X-Internal-Token", token);
            case WRONG -> builder.header("X-Internal-Token", token.substring(1) + "A");
            case DUPLICATED -> builder.header("X-Internal-Token", token).header("X-Internal-Token", token);
            case MISSING -> {
                // 헤더 없음
            }
        }
        if (traceId != null) {
            builder.header("X-Trace-Id", traceId);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 본문이 그 스키마 파일(예: {@code internal-session-view.schema.json})에 맞지 않는 곳 목록(맞으면 빈 목록). */
    public static List<String> schemaErrors(String schemaFile, String body) {
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.put("$schema", DRAFT_2020_12);
        wrapper.put("$ref", ContractStubServer.SCHEMA_ID_PREFIX + schemaFile);
        Schema schema = SCHEMAS.getSchema(wrapper);
        return schema.validate(JSON.readTree(body)).stream().map(com.networknt.schema.Error::toString).toList();
    }

    /** U1 자유대화 시작 명령 본문(계약 예시와 같은 모양). */
    public static String startBody(UUID sessionId, UUID guardianId, UUID childId, UUID deviceId) {
        return """
                {"sessionId":"%s","idempotencyKey":"%s","guardianId":"%s","childId":"%s","deviceId":"%s",
                 "activityType":"FREE_TALK",
                 "consentSnapshot":{"policyVersion":"1.0","voiceAndText":true,"guardianView":true,"reportSharing":false,"rawAudio":false},
                 "childContext":{"displayName":"하늘","age":8,"interests":["공룡","그림"]},
                 "stopExpressions":[{"text":"무중아 그만할래","isDefault":true}],
                 "approvedContent":{
                   "CLOSING":{"contentVersionId":"%s","text":"오늘 이야기 즐거웠어. 다음에 또 만나!","audioUrl":"/media/approved/closing-v1.wav"},
                   "WAITING_FILLER":{"contentVersionId":"%s","text":"음, 잠깐만 생각해 볼게.","audioUrl":"/media/approved/waiting-filler-v1.wav"}},
                 "practice":null}
                """.formatted(sessionId, UUID.randomUUID(), guardianId, childId, deviceId, UUID.randomUUID(),
                UUID.randomUUID());
    }

    /** 끝내기 명령 본문. */
    public static String endBody(UUID requestId, String reason) {
        return "{\"requestId\":\"" + requestId + "\",\"reason\":\"" + reason + "\"}";
    }

    private static SchemaRegistry registry() {
        Path dir = schemasDir();
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                .schemas(iri -> iri.startsWith(ContractStubServer.SCHEMA_ID_PREFIX)
                        ? read(dir, iri.substring(ContractStubServer.SCHEMA_ID_PREFIX.length()))
                        : null));
    }

    private static Path schemasDir() {
        String root = System.getProperty("mujoong.repo.root");
        if (root != null && !root.isBlank()) {
            return Path.of(root).resolve("contracts/schemas");
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("contracts/schemas"))) {
                return dir.resolve("contracts/schemas");
            }
        }
        throw new IllegalStateException("contracts/schemas를 찾지 못했다(mujoong.repo.root 미설정)");
    }

    private static String read(Path dir, String name) {
        String file = name.contains("#") ? name.substring(0, name.indexOf('#')) : name;
        Path path = dir.resolve(file);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 응답 본문의 문자열 필드(없거나 null이면 빈 값). */
    public static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asString());
    }
}
