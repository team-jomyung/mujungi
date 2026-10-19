package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.CookieJar;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.ClaimedDevice;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.core.web.support.WebFixtures.IssuedCode;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Core 내부 API: 토큰 확인 · 연결 보고 · 이벤트 수신 (U1 3.5 Step 34 · 계약 5 · 6절 · BR3.2 · BR3.4 · BR3.9 · BR4.1 · BR5.4 ~ BR5.6 ·
 * BR5.9 · SD-8 · NFR9.6 · 편차 (i)(j)(n)).
 *
 * <p>체인 C-INT: {@code X-Internal-Token}이 정확히 하나이고 맞아야 하며, 통과해도 보호자 · 기기 권한은 생기지 않는다. 본문 한도는 2MB다.
 */
@Tag("skeleton")
@Tag("db")
class CoreInternalApiIT {

    private static final int INTERNAL_LIMIT = 2 * 1024 * 1024;

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static LogCapture logs;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).start();
        logs = LogCapture.attach();
    }

    @AfterAll
    static void stop() {
        if (logs != null) {
            logs.close();
        }
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

    @BeforeEach
    void freshLogWindow() {
        app.clock().advance(Duration.ofMinutes(2));
        app.bean(EventLogger.class).flushMerged();
        logs.clear();
    }

    // ------------------------------------------------------------------ 내부 토큰

    @Test
    void missingWrongOrRepeatedInternalTokensAreTheSame401() {
        double before = tokenFailures();
        String body = "{\"deviceToken\":\"" + TestSecrets.deviceToken() + "\"}";

        List<Result> rejected = List.of(
                app.post("/internal/devices/verify-token").json(body).send(),
                app.post("/internal/devices/verify-token").header("X-Internal-Token", TestSecrets.internalServiceToken())
                        .json(body).send(),
                app.post("/internal/devices/verify-token").internalToken().internalToken().json(body).send(),
                app.post("/internal/events").header("X-Internal-Token", "").json("{}").send());

        assertThat(rejected).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(401);
            assertThat(result.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(result.body()).doesNotContain(app.internalToken());
        });
        assertThat(withoutTrace(rejected.get(0))).as("없음 · 틀림 · 복수가 같은 응답").isEqualTo(withoutTrace(rejected.get(1)))
                .isEqualTo(withoutTrace(rejected.get(2)));
        assertThat(tokenFailures() - before).isEqualTo(4.0);
        assertThat(logs.events(EventCode.SECURITY_INTERNAL_TOKEN_FAILED)).anySatisfy(event ->
                assertThat(LogCapture.attrs(event)).containsEntry("direction", "REALTIME_TO_CORE").containsEntry("statusCode", 401));
        assertThat(logs.lines()).noneMatch(line -> line.contains(app.internalToken()));
    }

    // ------------------------------------------------------------------ verify-token

    @Test
    void verifyReturnsTheOwnersAndTheFirstVerificationClosesClaim() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        UUID childId = db.insertChild(guardianId);
        Guardian guardian = WebFixtures.guardianFor(app, guardianId);
        IssuedCode issued = WebFixtures.issueCode(app, db);
        String bootstrapSecret = issued.jar().get("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow();
        assertThat(WebFixtures.register(app, guardian, issued.code()).status()).isEqualTo(201);
        Result claimed = issued.jar().accept(app.post("/api/device/bootstrap/claim").cookies(issued.jar()).send());
        String token = issued.jar().get("MUJOONG_DEVICE").orElseThrow();
        UUID deviceId = UUID.fromString(String.valueOf(claimed.json().get("deviceId")));

        Result verified = verify(token);

        assertThat(verified.status()).isEqualTo(200);
        assertThat(WebFixtures.schemaErrors("internal-verify-token-response.schema.json", verified.body())).isEmpty();
        assertThat(verified.json()).isEqualTo(Map.of("deviceId", deviceId.toString(), "guardianId", guardianId.toString(),
                "childId", childId.toString(), "registrationStatus", "REGISTERED"));
        assertThat(verified.body()).doesNotContain(token);
        assertThat(firstConnectedAt(deviceId)).as("BR3.2 첫 확인 시각").isEqualTo(app.clock().now());

        CookieJar bootstrap = new CookieJar();
        bootstrap.put("MUJOONG_DEVICE_BOOTSTRAP", bootstrapSecret);
        Result lateClaim = app.post("/api/device/bootstrap/claim").cookies(bootstrap).send();
        assertThat(lateClaim.status()).as("첫 연결 뒤 claim").isEqualTo(410);
        assertThat(lateClaim.code()).isEqualTo("BOOTSTRAP_CLOSED");

        Instant first = firstConnectedAt(deviceId);
        app.clock().advance(Duration.ofSeconds(5));
        assertThat(verify(token).status()).as("연결마다 다시 확인 (j)").isEqualTo(200);
        assertThat(firstConnectedAt(deviceId)).as("첫 연결 시각은 한 번만").isEqualTo(first);
    }

    @Test
    void verifyLooksUpEveryTimeSoAReleasedTokenFailsImmediately() {
        Guardian guardian = WebFixtures.guardian(app, db);
        ClaimedDevice device = WebFixtures.claimedDevice(app, db, guardian);
        Result before = verify(device.token());
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.json().get("childId")).as("아이 없는 보호자").isNull();

        IssuedCode replacement = WebFixtures.issueCode(app, db);
        assertThat(WebFixtures.register(app, guardian, replacement.code()).status()).isEqualTo(201);

        Result after = verify(device.token());
        assertThat(after.status()).as("캐시 없이 해제가 바로 반영 (j)").isEqualTo(401);
        assertThat(after.code()).isEqualTo("INVALID_DEVICE_TOKEN");
        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("kind", "DEVICE_TOKEN").containsEntry("statusCode", 401));
    }

    @Test
    void malformedVerifyRequestsAre400AndUnknownTokensAre401() {
        for (String body : List.of("{}", "{\"deviceToken\":\"\"}", "{\"deviceToken\":\"" + "a".repeat(257) + "\"}",
                "{\"deviceToken\":", "[]")) {
            Result result = app.post("/internal/devices/verify-token").internalToken().json(body).send();
            assertThat(result.status()).as(body.length() > 40 ? "긴 토큰" : body).isEqualTo(400);
            assertThat(result.code()).isEqualTo("VALIDATION_FAILED");
        }
        Result plain = app.post("/internal/devices/verify-token").internalToken()
                .body("deviceToken=x".getBytes(StandardCharsets.UTF_8), "text/plain").send();
        assertThat(plain.status()).isEqualTo(400);

        assertThat(verify(TestSecrets.deviceToken()).code()).isEqualTo("INVALID_DEVICE_TOKEN");
        assertThat(verify("a".repeat(256)).status()).as("상한 정각은 형식 통과, 모르는 토큰 401").isEqualTo(401);
    }

    // ------------------------------------------------------------------ 연결 보고

    @Test
    void connectionReportsNeedStateAndReportedAtAndOnlyNewerReportsWin() {
        Guardian guardian = WebFixtures.guardian(app, db);
        ClaimedDevice device = WebFixtures.claimedDevice(app, db, guardian);
        String path = "/internal/devices/" + device.deviceId() + "/connection";
        Instant reportedAt = app.clock().now();
        String at = ApiResponses.time(reportedAt);

        for (String body : List.of("{\"reportedAt\":\"" + at + "\"}", "{\"state\":\"CONNECTED\"}",
                "{\"state\":\"SLEEPING\",\"reportedAt\":\"" + at + "\"}", "{\"state\":\"CONNECTED\",\"reportedAt\":\"yesterday\"}",
                "{\"state\":\"CONNECTED\",\"reportedAt\":\"" + at + "\",\"rttMs\":-1}", "not json")) {
            Result invalid = app.put(path).internalToken().json(body).send();
            assertThat(invalid.status()).as(body).isEqualTo(400);
            assertThat(invalid.code()).isEqualTo("INVALID_ENVELOPE");
        }
        Result badId = app.put("/internal/devices/not-a-uuid/connection").internalToken()
                .json("{\"state\":\"CONNECTED\",\"reportedAt\":\"" + at + "\"}").send();
        assertThat(badId.status()).isEqualTo(400);
        assertThat(badId.code()).isEqualTo("INVALID_ENVELOPE");

        Result accepted = app.put(path).internalToken().json("{\"state\":\"CONNECTED\",\"reportedAt\":\"" + at
                + "\",\"rttMs\":35,\"recentDisconnects\":1,\"since\":\"" + at + "\",\"lastSeenAt\":\"" + at + "\",\"unknown\":true}")
                .send();
        assertThat(accepted.status()).isEqualTo(204);
        assertThat(accepted.body()).isEmpty();
        assertThat(copy(device.deviceId())).containsEntry("reported_connection_state", "CONNECTED").containsEntry("rtt_ms", 35);

        Result late = app.put(path).internalToken().json("{\"state\":\"DISCONNECTED\",\"reportedAt\":\""
                + ApiResponses.time(reportedAt.minusSeconds(1)) + "\"}").send();
        assertThat(late.status()).as("늦은 보고도 204").isEqualTo(204);
        assertThat(copy(device.deviceId())).as("늦은 보고는 버린다").containsEntry("reported_connection_state", "CONNECTED");

        Result unknownDevice = app.put("/internal/devices/" + UUID.randomUUID() + "/connection").internalToken()
                .json("{\"state\":\"CONNECTED\",\"reportedAt\":\"" + at + "\"}").send();
        assertThat(unknownDevice.status()).as("등록되지 않은 기기 보고는 버린다").isEqualTo(204);
    }

    // ------------------------------------------------------------------ 이벤트 수신

    @Test
    void eventsAreAcknowledgedOnlyAfterTheInboxRowIsCommittedAndDuplicatesConflictsAndUnknownsAreHandled() {
        Guardian guardian = WebFixtures.guardian(app, db);
        ClaimedDevice device = WebFixtures.claimedDevice(app, db, guardian);
        UUID eventId = db.trackInbox(UUID.randomUUID());
        String connected = deviceStatusChanged(eventId, device.deviceId(), guardian.id(), "CONNECTED", app.clock().now());

        Result first = postEvent(connected);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).isEqualTo("{}");
        assertThat(WebFixtures.schemaErrors("empty-object.schema.json", first.body())).isEmpty();
        assertThat(inboxStatus(eventId)).as("ACK 전에 커밋").isIn("RECEIVED", "PROCESSED");
        awaitInboxStatus(eventId, "PROCESSED");
        assertThat(copy(device.deviceId())).containsEntry("reported_connection_state", "CONNECTED");

        Result duplicate = postEvent(connected);
        assertThat(duplicate.status()).as("같은 eventId · 같은 해시").isEqualTo(200);
        Result conflict = postEvent(deviceStatusChanged(eventId, device.deviceId(), guardian.id(), "DISCONNECTED",
                app.clock().now()));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("EVENT_CONFLICT");
        assertThat(copy(device.deviceId())).containsEntry("reported_connection_state", "CONNECTED");
        Integer rows = db.jdbc().queryForObject("select count(*) from core.core_inbox_event where event_id = :id",
                new MapSqlParameterSource("id", eventId), Integer.class);
        assertThat(rows).isEqualTo(1);

        UUID unknownType = db.trackInbox(UUID.randomUUID());
        assertThat(postEvent(envelope(unknownType, "SomethingFromTheFuture", 1, "Device", device.deviceId(), "{\"x\":1}"))
                .status()).as("모르는 eventType").isEqualTo(200);
        assertThat(inboxStatus(unknownType)).isEqualTo("IGNORED");
        UUID newerSchema = db.trackInbox(UUID.randomUUID());
        assertThat(postEvent(envelope(newerSchema, "DeviceStatusChanged", 99, "Device", device.deviceId(), "{\"x\":1}"))
                .status()).as("더 높은 schemaVersion").isEqualTo(200);
        assertThat(inboxStatus(newerSchema)).isEqualTo("IGNORED");
    }

    @Test
    void invalidEnvelopesAre400AndTheInternalBodyLimitIsTwoMegabytes() {
        UUID aggregate = UUID.randomUUID();
        String occurred = ApiResponses.time(app.clock().now());
        List<String> invalid = List.of(
                "{}",
                "{\"eventId\":\"not-a-uuid\",\"eventType\":\"X\",\"schemaVersion\":1,\"occurredAt\":\"" + occurred
                        + "\",\"aggregateType\":\"Device\",\"aggregateId\":\"" + aggregate + "\",\"payload\":{}}",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"X\",\"schemaVersion\":1,\"occurredAt\":\"" + occurred
                        + "\",\"aggregateType\":\"Device\",\"payload\":{}}",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"X\",\"schemaVersion\":0,\"occurredAt\":\"" + occurred
                        + "\",\"aggregateType\":\"Device\",\"aggregateId\":\"" + aggregate + "\",\"payload\":{}}",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"X\",\"schemaVersion\":1,\"occurredAt\":\"later\""
                        + ",\"aggregateType\":\"Device\",\"aggregateId\":\"" + aggregate + "\",\"payload\":{}}",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"X\",\"schemaVersion\":1,\"occurredAt\":\"" + occurred
                        + "\",\"aggregateType\":\"Device\",\"aggregateId\":\"" + aggregate + "\",\"payload\":[]}",
                "not json");
        for (String body : invalid) {
            Result result = postEvent(body);
            assertThat(result.status()).as(body.length() > 60 ? body.substring(0, 60) : body).isEqualTo(400);
            assertThat(result.code()).isEqualTo("INVALID_ENVELOPE");
        }
        Result wrongType = app.post("/internal/events").internalToken().body("{}".getBytes(StandardCharsets.UTF_8), "text/plain")
                .send();
        assertThat(wrongType.code()).isEqualTo("INVALID_ENVELOPE");

        UUID atLimit = db.trackInbox(UUID.randomUUID());
        byte[] exactly = padded(envelope(atLimit, "SomethingFromTheFuture", 1, "Device", aggregate, "{}"), INTERNAL_LIMIT);
        Result accepted = app.post("/internal/events").internalToken().body(exactly, "application/json").send();
        assertThat(accepted.status()).as("정확히 2MB는 받는다").isEqualTo(200);
        UUID overLimit = UUID.randomUUID();
        byte[] over = padded(envelope(overLimit, "SomethingFromTheFuture", 1, "Device", aggregate, "{}"), INTERNAL_LIMIT + 1);
        for (Result result : List.of(
                app.post("/internal/events").internalToken().body(over, "application/json").send(),
                app.post("/internal/events").internalToken().body(over, "application/json").chunked().send())) {
            assertThat(result.status()).isEqualTo(400);
            assertThat(result.code()).isEqualTo("INVALID_ENVELOPE");
        }
        assertThat(inboxStatus(overLimit)).as("한도 초과는 행을 남기지 않는다").isNull();
        Result verifyOver = app.post("/internal/devices/verify-token").internalToken().body(over, "application/json").send();
        assertThat(verifyOver.status()).isEqualTo(400);
        assertThat(verifyOver.code()).isEqualTo("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------------ 도움

    private static Result verify(String token) {
        return app.post("/internal/devices/verify-token").internalToken().json("{\"deviceToken\":\"" + token + "\"}").send();
    }

    private static Result postEvent(String envelope) {
        return app.post("/internal/events").internalToken().json(envelope).send();
    }

    static String envelope(UUID eventId, String type, int schemaVersion, String aggregateType, UUID aggregateId, String payload) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":" + schemaVersion
                + ",\"occurredAt\":\"" + ApiResponses.time(app.clock().now()) + "\",\"aggregateType\":\"" + aggregateType
                + "\",\"aggregateId\":\"" + aggregateId + "\",\"payload\":" + payload + ",\"futureField\":1}";
    }

    private static String deviceStatusChanged(UUID eventId, UUID deviceId, UUID guardianId, String state, Instant since) {
        String at = ApiResponses.time(since);
        return envelope(eventId, "DeviceStatusChanged", 1, "Device", deviceId, "{\"deviceId\":\"" + deviceId
                + "\",\"state\":\"" + state + "\",\"rttMs\":20,\"recentDisconnects\":0,\"since\":\"" + at
                + "\",\"lastSeenAt\":\"" + at + "\",\"guardianId\":\"" + guardianId + "\"}");
    }

    private static String inboxStatus(UUID eventId) {
        List<String> statuses = db.jdbc().queryForList("select status from core.core_inbox_event where event_id = :id",
                new MapSqlParameterSource("id", eventId), String.class);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private static void awaitInboxStatus(UUID eventId, String status) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!status.equals(inboxStatus(eventId))) {
            assertThat(System.nanoTime()).as("Inbox 행이 %s가 되지 않았다", status).isLessThan(deadline);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static Map<String, Object> copy(UUID deviceId) {
        return db.jdbc().queryForMap("select reported_connection_state, rtt_ms from core.core_device where id = :id",
                new MapSqlParameterSource("id", deviceId));
    }

    private static Instant firstConnectedAt(UUID deviceId) {
        java.sql.Timestamp value = db.jdbc().queryForObject("select first_connected_at from core.core_device where id = :id",
                new MapSqlParameterSource("id", deviceId), java.sql.Timestamp.class);
        return value == null ? null : value.toInstant();
    }

    private static double tokenFailures() {
        return app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "internal_token_failed");
    }

    private static Map<String, Object> withoutTrace(Result result) {
        Map<String, Object> copy = new java.util.HashMap<>(result.json());
        copy.remove("traceId");
        return copy;
    }

    private static byte[] padded(String json, int size) {
        byte[] head = json.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[size];
        Arrays.fill(body, (byte) ' ');
        System.arraycopy(head, 0, body, 0, head.length);
        return body;
    }
}
