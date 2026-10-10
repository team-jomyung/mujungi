package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.identity.SeedIds;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Call;
import kr.mujoong.core.web.support.CoreWebApp.CookieJar;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.core.web.support.WebFixtures.IssuedCode;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 계약 일치 (U1 3.5 Step 34 · 계약 2 · 3 · 4.1 · 5 · 6 · 10절 · BR4.6 · BR6.4 · NFR3.1 · BR8.6).
 *
 * <p>계약 OpenAPI 파일이 Core 몫으로 정한 연산과 앱의 처리기가 정확히 같고, 실제 흐름에서 받은 모든 응답이 그 연산의 문서화된 상태 · 본문 스키마 ·
 * {@code x-error-codes}에 맞는지 본다. 요청마다 모르는 필드를 덧붙여 서버가 무시하는지(계약 10절), 응답에 해시 · 토큰 · 판정 내부값 · 08 입력
 * 원문이 없는지, Core가 Realtime에 보낸 요청이 계약 스키마에 맞는지도 함께 본다.
 */
@Tag("u1")
@Tag("db")
class ContractConformanceIT {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final String KNOWN_CONTENT = "놀이터에서 친구와 다툰 일이 있었어요";

    private static DbTestSupport db;
    private static BusinessFixtures fixtures;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static OpenApiContracts contracts;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        BusinessFixtures.applyReferenceSeed(db.postgres());
        fixtures = new BusinessFixtures(db);
        contracts = OpenApiContracts.load();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        db.trackGuardian(SeedIds.GUARDIAN);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).devLogin(true).start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (realtime != null) {
            realtime.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void coreHandlesExactlyTheOperationsTheContractAssignsToIt() {
        Set<String> contracted = new TreeSet<>();
        for (String key : contracts.keys()) {
            // /internal/sessions/**는 Realtime 몫(계약 5절 C8), /internal/events는 두 앱 모두(C11)
            if (!key.contains(" /internal/sessions")) {
                contracted.add(key);
            }
        }
        Set<String> served = new TreeSet<>();
        RequestMappingHandlerMapping mapping = app.context().getBean("requestMappingHandlerMapping",
                RequestMappingHandlerMapping.class);
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("kr.mujoong.core")
                    || handler.getBeanType() == ApiErrorController.class) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            for (var method : info.getMethodsCondition().getMethods()) {
                for (String pattern : info.getPatternValues()) {
                    served.add(method.name() + " " + pattern);
                }
            }
        }

        assertThat(served).isEqualTo(contracted);
    }

    @Test
    void everyResponseOfTheU1FlowsMatchesItsDocumentedStatusSchemaAndErrorCodes() {
        Exchanges exchanges = new Exchanges();
        // 시험 로그인 · CSRF · 홈
        Result login = exchanges.send("POST /api/dev/login", app.post("/api/dev/login").json("{\"unknown\":true}"));
        String seed = login.setCookie("MUJOONG_SESSION").orElseThrow().value();
        exchanges.send("GET /api/session/csrf", app.get("/api/session/csrf"));
        exchanges.send("GET /api/session/csrf", app.get("/api/session/csrf").session(seed));
        exchanges.send("GET /api/health", app.get("/api/health"));
        exchanges.send("GET /api/home", app.get("/api/home"));
        exchanges.send("GET /api/home", app.get("/api/home").session(seed));
        exchanges.send("GET /api/device", app.get("/api/device").session(seed));

        // 기기 웹 · 등록 · claim · me
        Guardian guardian = WebFixtures.guardian(app, db);
        CookieJar device = new CookieJar();
        Result bootstrap = device.accept(exchanges.send("POST /api/device/bootstrap",
                app.post("/api/device/bootstrap").json("{\"unknown\":1}")));
        String code = String.valueOf(bootstrap.json().get("code"));
        WebFixtures.trackDeviceByCode(db, code);
        exchanges.send("GET /api/device/bootstrap/status", app.get("/api/device/bootstrap/status").cookies(device));
        exchanges.send("GET /api/device/bootstrap/status", app.get("/api/device/bootstrap/status"));
        exchanges.send("POST /api/device/bootstrap/claim", app.post("/api/device/bootstrap/claim").cookies(device));
        exchanges.send("POST /api/device/bootstrap/claim", app.post("/api/device/bootstrap/claim"));
        exchanges.send("POST /api/device/register", app.post("/api/device/register").session(guardian.session())
                .json("{\"code\":\"" + code + "\"}"));
        exchanges.send("POST /api/device/register", app.post("/api/device/register").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"code\":\"12\"}"));
        exchanges.send("POST /api/device/register", app.post("/api/device/register").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"code\":\"" + DbTestSupport.randomCode() + "\"}"));
        exchanges.send("POST /api/device/register", app.post("/api/device/register").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"code\":\"" + code + "\",\"deviceName\":\"거실\"}"));
        exchanges.send("POST /api/device/register", app.post("/api/device/register").session(seed)
                .csrf(WebFixtures.csrf(app, seed)).json("{\"code\":\"" + code + "\"}"));
        exchanges.send("GET /api/device/bootstrap/status", app.get("/api/device/bootstrap/status").cookies(device));
        exchanges.send("POST /api/device/bootstrap", app.post("/api/device/bootstrap").cookies(device));
        String bootstrapSecret = device.get("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow();
        Result claim = device.accept(exchanges.send("POST /api/device/bootstrap/claim",
                app.post("/api/device/bootstrap/claim").cookies(device).json("{\"unknown\":1}")));
        UUID deviceId = UUID.fromString(String.valueOf(claim.json().get("deviceId")));
        String token = device.get("MUJOONG_DEVICE").orElseThrow();
        exchanges.send("GET /api/device/me", app.get("/api/device/me").cookies(device));
        exchanges.send("GET /api/device/me", app.get("/api/device/me"));

        // 내부: 토큰 확인 · 연결 보고 · 이벤트
        exchanges.send("POST /internal/devices/verify-token", app.post("/internal/devices/verify-token").internalToken()
                .json("{\"deviceToken\":\"" + token + "\",\"connectionId\":\"x\"}"));
        exchanges.send("POST /internal/devices/verify-token", app.post("/internal/devices/verify-token")
                .json("{\"deviceToken\":\"" + token + "\"}"));
        exchanges.send("POST /internal/devices/verify-token", app.post("/internal/devices/verify-token").internalToken()
                .json("{}"));
        exchanges.send("POST /api/device/bootstrap/claim", app.post("/api/device/bootstrap/claim")
                .cookie("MUJOONG_DEVICE_BOOTSTRAP", TestSecrets.bootstrapSecret()));
        exchanges.send("POST /api/device/bootstrap/claim", app.post("/api/device/bootstrap/claim")
                .cookie("MUJOONG_DEVICE_BOOTSTRAP", bootstrapSecret));
        String now = ApiResponses.time(app.clock().now());
        exchanges.send("PUT /internal/devices/{deviceId}/connection", app.put("/internal/devices/" + deviceId + "/connection")
                .internalToken().json("{\"state\":\"CONNECTED\",\"reportedAt\":\"" + now + "\",\"rttMs\":30,\"extra\":1}"));
        exchanges.send("PUT /internal/devices/{deviceId}/connection", app.put("/internal/devices/" + deviceId + "/connection")
                .internalToken().json("{\"state\":\"CONNECTED\"}"));
        UUID eventId = db.trackInbox(UUID.randomUUID());
        String event = StatusEnvelope.of(eventId, deviceId, now);
        exchanges.send("POST /internal/events", app.post("/internal/events").internalToken().json(event));
        exchanges.send("POST /internal/events", app.post("/internal/events").internalToken().json(event.replace("CONNECTED", "OFFLINE")));
        exchanges.send("POST /internal/events", app.post("/internal/events").internalToken().json("{\"eventId\":1}"));
        exchanges.send("GET /api/device", app.get("/api/device").session(guardian.session()));
        exchanges.send("GET /api/home", app.get("/api/home").session(guardian.session()));

        // 자유대화 · 진행 조회 · 끝내기
        Family family = fixtures.family(app.clock().now().minusSeconds(3_600));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, app.clock().now(), app.clock().now());
        Guardian parent = WebFixtures.guardianFor(app, family.guardianId());
        Result started = exchanges.send("POST /api/activities/free", app.post("/api/activities/free").session(parent.session())
                .csrf(parent.csrf()).json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"context\":{\"knownContent\":\""
                        + KNOWN_CONTENT + "\",\"situationType\":\"FRIEND\",\"sourceType\":\"SEEN\",\"question\":\"어떻게 말할까요?\"},"
                        + "\"mode\":\"future\"}"));
        UUID sessionId = UUID.fromString(String.valueOf(started.json().get("sessionId")));
        exchanges.send("POST /api/activities/free", app.post("/api/activities/free").session(parent.session())
                .csrf(parent.csrf()).json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"));
        exchanges.send("POST /api/activities/free", app.post("/api/activities/free").session(parent.session())
                .csrf(parent.csrf()).json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"context\":{\"knownContent\":\""
                        + "가".repeat(201) + "\"}}"));
        exchanges.send("POST /api/activities/free", app.post("/api/activities/free").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"));
        exchanges.send("GET /api/sessions/{sessionId}", app.get("/api/sessions/" + sessionId).session(parent.session()));
        exchanges.send("GET /api/sessions/{sessionId}", app.get("/api/sessions/" + sessionId + "?sinceSeq=2")
                .session(parent.session()));
        exchanges.send("GET /api/sessions/{sessionId}", app.get("/api/sessions/" + sessionId).session(guardian.session()));
        exchanges.send("POST /api/sessions/{sessionId}/end", app.post("/api/sessions/" + sessionId + "/end")
                .session(parent.session()).csrf(parent.csrf()).json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"why\":1}"));
        exchanges.send("POST /api/sessions/{sessionId}/end", app.post("/api/sessions/" + sessionId + "/end")
                .session(parent.session()).csrf(parent.csrf()).json("{}"));
        exchanges.send("POST /api/sessions/{sessionId}/end", app.post("/api/sessions/" + sessionId + "/end")
                .session(guardian.session()).csrf(guardian.csrf()).json("{\"requestId\":\"" + UUID.randomUUID() + "\"}"));

        List<String> violations = new ArrayList<>();
        for (Exchange exchange : exchanges.all()) {
            violations.addAll(contracts.violations(exchange.operation(), exchange.result()));
        }
        assertThat(violations).isEmpty();
        assertThat(exchanges.statuses()).as("흐름이 성공 · 실패 응답을 모두 지났다")
                .contains(200, 201, 202, 204, 400, 401, 403, 404, 409, 410);

        for (Exchange exchange : exchanges.all()) {
            String body = exchange.result().body();
            assertThat(SHA256_HEX.matcher(body).find()).as("%s 응답에 해시 모양 값", exchange.operation()).isFalse();
            assertThat(body).as(exchange.operation()).doesNotContain(token, KNOWN_CONTENT, "secret", "Hash", "verdictAt",
                    "reportedConnectionState", "staleAfter");
        }
        assertThat(realtime.violations()).as("Core가 Realtime에 보낸 요청은 계약 스키마에 맞다").isEmpty();
    }

    @Test
    void freeTalkContextIsValidatedButNeverStoredOrForwarded() {
        Family family = fixtures.family(app.clock().now().minusSeconds(3_600));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, app.clock().now(), app.clock().now());
        Guardian parent = WebFixtures.guardianFor(app, family.guardianId());
        String marker = KNOWN_CONTENT + " " + UUID.randomUUID();
        realtime.clearRequests();

        Result started = app.post("/api/activities/free").session(parent.session()).csrf(parent.csrf())
                .json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"context\":{\"knownContent\":\"" + marker
                        + "\",\"question\":\"" + marker + "\"}}").send();

        assertThat(started.status()).isEqualTo(202);
        UUID sessionId = UUID.fromString(String.valueOf(started.json().get("sessionId")));
        List<RecordedRequest> starts = realtime.awaitRequests("POST", WebFixtures.RT_START, 1, Duration.ofSeconds(5));
        assertThat(starts).isNotEmpty().allSatisfy(start -> assertThat(start.body()).doesNotContain(marker, "knownContent"));
        String row = db.jdbc().queryForObject("select row_to_json(a)::text from core.core_activity a where session_id = :id",
                new MapSqlParameterSource("id", sessionId), String.class);
        assertThat(row).as("NFR3.1 저장 안 함").doesNotContain(marker);
        List<String> outbox = db.jdbc().queryForList(
                "select payload_json::text from core.core_outbox_event where guardian_id = :g",
                new MapSqlParameterSource("g", family.guardianId()), String.class);
        assertThat(outbox).noneMatch(payload -> payload.contains(marker));
        assertThat(app.get("/api/sessions/" + sessionId).session(parent.session()).send().body()).doesNotContain(marker);
    }

    // ------------------------------------------------------------------ 도움

    /** 연산 키와 응답 한 쌍. */
    private record Exchange(String operation, Result result) {
    }

    /** 흐름에서 받은 응답 모음. */
    private static final class Exchanges {

        private final List<Exchange> all = new ArrayList<>();

        Result send(String operation, Call call) {
            Result result = call.send();
            all.add(new Exchange(operation, result));
            return result;
        }

        List<Exchange> all() {
            return all;
        }

        Set<Integer> statuses() {
            Set<Integer> statuses = new TreeSet<>();
            all.forEach(exchange -> statuses.add(exchange.result().status()));
            return statuses;
        }
    }

    /** DeviceStatusChanged 봉투(모르는 필드 포함). */
    private static final class StatusEnvelope {

        static String of(UUID eventId, UUID deviceId, String at) {
            return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"DeviceStatusChanged\",\"schemaVersion\":1,\"occurredAt\":\""
                    + at + "\",\"aggregateType\":\"Device\",\"aggregateId\":\"" + deviceId + "\",\"payload\":{\"deviceId\":\""
                    + deviceId + "\",\"state\":\"CONNECTED\",\"since\":\"" + at + "\"},\"producer\":\"future\"}";
        }
    }
}
