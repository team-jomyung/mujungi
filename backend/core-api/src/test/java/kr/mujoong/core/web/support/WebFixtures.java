package kr.mujoong.core.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Step 34 API 시험의 공통 준비물(시험 소스 전용): 계약 대역 Realtime, 응답 스키마 검사, CSRF 취득, 시험 행 정리.
 */
public final class WebFixtures {

    /** Realtime 내부 경로(계약 대역에 등록). */
    public static final String RT_START = "/internal/sessions";
    public static final String RT_END = "/internal/sessions/{sessionId}/end";
    public static final String RT_VIEW = "/internal/sessions/{sessionId}/view";
    public static final String RT_EVENTS = "/internal/events";

    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();
    private static volatile SchemaRegistry registry;

    private WebFixtures() {
    }

    /** 내부 토큰을 요구하고 요청을 계약 스키마로 검사하는 Realtime 대역(시작 202 · 끝내기 202 CLOSING · 조회 404 · 이벤트 200). */
    public static ContractStubServer realtime(String internalToken) {
        ContractStubServer realtime = ContractStubServer.start().requireInternalToken(internalToken);
        realtime.route("POST", RT_START).requestSchema("internal-session-start-request.schema.json")
                .respond(StubResponse.json(202, "{\"status\":\"PREPARING\"}"));
        realtime.route("POST", RT_END).requestSchema("internal-session-end-request.schema.json")
                .respond(StubResponse.json(202, "{\"status\":\"CLOSING\"}"));
        realtime.route("GET", RT_VIEW).respond(StubResponse.error(404, kr.mujoong.contract.error.ErrorCode.NOT_FOUND));
        realtime.route("POST", RT_EVENTS).requestSchema("event-envelope.schema.json")
                .respond(StubResponse.json(200, "{}"));
        return realtime;
    }

    /** 새 ACTIVE 보호자(정리 대상 등록)와 그 세션 쿠키 · CSRF 토큰. */
    public static Guardian guardian(CoreWebApp app, DbTestSupport db) {
        return guardianFor(app, db.insertGuardian("ACTIVE"));
    }

    /** 이미 있는 보호자 행의 세션 쿠키 · CSRF 토큰. */
    public static Guardian guardianFor(CoreWebApp app, UUID guardianId) {
        String session = app.sessionCookieFor(guardianId);
        return new Guardian(guardianId, session, csrf(app, session));
    }

    /** 시험 보호자 한 명(쿠키 · 토큰 값은 실행마다 생성). */
    public record Guardian(UUID id, String session, String csrf) {

        @Override
        public String toString() {
            return "Guardian[id=" + id + "]";
        }
    }

    /** 새 기기 웹 하나가 코드를 받는다(201이어야 한다, 정리 대상 등록). 쿠키 상자에 bootstrap 쿠키가 들어 있다. */
    public static IssuedCode issueCode(CoreWebApp app, DbTestSupport db) {
        CoreWebApp.CookieJar jar = new CoreWebApp.CookieJar();
        Result result = jar.accept(app.post("/api/device/bootstrap").send());
        assertThat(result.status()).as("코드 받기").isEqualTo(201);
        String code = String.valueOf(result.json().get("code"));
        trackDeviceByCode(db, code);
        return new IssuedCode(code, jar);
    }

    /** 보호자가 코드를 등록한다(05a). */
    public static Result register(CoreWebApp app, Guardian guardian, String code) {
        return app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"code\":\"" + code + "\"}").send();
    }

    /** 코드 받기 → 등록 → claim까지 마친 기기(쿠키 상자에 기기 토큰). */
    public static ClaimedDevice claimedDevice(CoreWebApp app, DbTestSupport db, Guardian guardian) {
        IssuedCode issued = issueCode(app, db);
        Result registered = register(app, guardian, issued.code());
        assertThat(registered.status()).as("등록").isEqualTo(201);
        Result claimed = issued.jar().accept(app.post("/api/device/bootstrap/claim").cookies(issued.jar()).send());
        assertThat(claimed.status()).as("claim").isEqualTo(200);
        UUID deviceId = UUID.fromString(String.valueOf(claimed.json().get("deviceId")));
        return new ClaimedDevice(deviceId, issued.code(), issued.jar().get("MUJOONG_DEVICE").orElseThrow(), issued.jar());
    }

    /** 받은 코드와 기기 웹 쿠키 상자. */
    public record IssuedCode(String code, CoreWebApp.CookieJar jar) {

        @Override
        public String toString() {
            return "IssuedCode[code=***]";
        }
    }

    /** claim을 마친 기기. */
    public record ClaimedDevice(UUID deviceId, String code, String token, CoreWebApp.CookieJar jar) {

        @Override
        public String toString() {
            return "ClaimedDevice[deviceId=" + deviceId + "]";
        }
    }

    /** Realtime 대역이 {@code eventType} · {@code aggregateId} 이벤트를 받을 때까지 기다린다(없으면 빈 값). */
    public static Optional<RecordedRequest> awaitEvent(
            ContractStubServer realtime, String eventType, UUID aggregateId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<RecordedRequest> received = realtime.requests("POST", RT_EVENTS);
            Optional<RecordedRequest> found = received.stream()
                    .filter(request -> eventType.equals(request.json().path("eventType").asString())
                            && aggregateId.toString().equals(request.json().path("aggregateId").asString()))
                    .findFirst();
            long remaining = deadline - System.nanoTime();
            if (found.isPresent() || remaining <= 0) {
                return found;
            }
            realtime.awaitRequests("POST", RT_EVENTS, received.size() + 1, Duration.ofNanos(remaining));
        }
    }

    /** 세션 쿠키로 CSRF 토큰을 받는다(200이어야 한다). */
    public static String csrf(CoreWebApp app, String sessionCookie) {
        Result result = app.get("/api/session/csrf").session(sessionCookie).send();
        assertThat(result.status()).as("csrf 취득").isEqualTo(200);
        return String.valueOf(result.json().get("token"));
    }

    /** 응답 본문이 계약 스키마에 맞는지(위반 목록, 맞으면 빈 목록). */
    public static List<String> schemaErrors(String schemaFile, String body) {
        Schema schema = SCHEMAS.computeIfAbsent(schemaFile, WebFixtures::load);
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JacksonException e) {
            return List.of("body: JSON이 아니다");
        }
        return schema.validate(node).stream().map(com.networknt.schema.Error::toString).toList();
    }

    private static Schema load(String schemaFile) {
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.put("$schema", DRAFT_2020_12);
        wrapper.put("$ref", ContractStubServer.SCHEMA_ID_PREFIX + schemaFile);
        return registry().getSchema(wrapper);
    }

    private static SchemaRegistry registry() {
        if (registry == null) {
            synchronized (WebFixtures.class) {
                if (registry == null) {
                    Path dir = repoRoot().resolve("contracts/schemas");
                    registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                            .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                            .schemas(iri -> iri.startsWith(ContractStubServer.SCHEMA_ID_PREFIX)
                                    ? read(dir, iri.substring(ContractStubServer.SCHEMA_ID_PREFIX.length()))
                                    : null));
                }
            }
        }
        return registry;
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

    /** 저장소 루트(시험 JVM 시스템 속성). */
    public static Path repoRoot() {
        String root = System.getProperty("mujoong.repo.root");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("mujoong.repo.root가 없다");
        }
        return Path.of(root);
    }

    // ------------------------------------------------------------------ 정리

    /** 코드로 기기 행을 찾아 정리 대상에 넣는다(bootstrap만 한 행은 보호자가 없어 보호자 정리로 지워지지 않는다). */
    public static UUID trackDeviceByCode(DbTestSupport db, String code) {
        List<UUID> ids = db.jdbc().queryForList(
                "select id from core.core_device where registration_code = :code",
                new MapSqlParameterSource("code", code), UUID.class);
        ids.forEach(db::trackDevice);
        return ids.isEmpty() ? null : ids.getLast();
    }

    /** 보호자들의 Spring Session 행(속성 행은 CASCADE)을 지운다. */
    public static void deleteSessions(DbTestSupport db, UUID... guardians) {
        if (guardians.length == 0) {
            return;
        }
        String[] names = Arrays.stream(guardians).map(UUID::toString).toArray(String[]::new);
        db.jdbc().update("delete from core.core_spring_session where principal_name = any(:names)",
                new MapSqlParameterSource("names", names));
    }
}
