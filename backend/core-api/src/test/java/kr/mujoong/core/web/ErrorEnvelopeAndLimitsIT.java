package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
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
 * 오류 봉투 · traceId · 본문 한도 · 입력 미반사 · 시각 형식 (U1 3.5 Step 34 · BR8.6 · BR8.10 · OD-7 · SD-10 · SD-11 · 편차 (o)(r)).
 *
 * <p>모든 오류는 {@code {code, message, traceId}} 셋뿐이고 traceId는 응답 헤더 {@code X-Trace-Id}와 같다. 클라이언트가 보낸 traceId는 형식이
 * 맞고 하나일 때만 이어 쓴다. {@code /api/**} 본문은 64KB(65,536바이트)까지이고 넘으면 길이 선언 여부와 상관없이 413이 아니라 400 봉투다.
 */
@Tag("u1")
@Tag("db")
class ErrorEnvelopeAndLimitsIT {

    private static final int REST_LIMIT = 64 * 1024;
    private static final String TIME = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?Z$";

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

    @Test
    void everyErrorIsTheThreeFieldEnvelopeWithTheHeaderTraceId() {
        Guardian guardian = WebFixtures.guardian(app, db);
        List<Result> errors = List.of(
                app.get("/api/home").send(),
                app.post("/api/device/register").session(guardian.session()).json("{}").send(),
                app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf()).json("{\"code\":\"1\"}").send(),
                app.get("/api/sessions/" + UUID.randomUUID()).session(guardian.session()).send(),
                app.get("/no-such-path").send(),
                app.post("/internal/events").json("{}").send(),
                app.get("/api/device/me").send());

        assertThat(errors).extracting(Result::status).containsExactly(401, 403, 400, 404, 404, 401, 401);
        assertThat(errors).allSatisfy(result -> {
            assertThat(result.json()).containsOnlyKeys("code", "message", "traceId");
            assertThat(WebFixtures.schemaErrors("error-envelope.schema.json", result.body())).isEmpty();
            assertThat(result.header("X-Trace-Id")).matches("^[0-9a-f]{32}$").isEqualTo(result.json().get("traceId"));
            assertThat(result.header("Content-Type")).startsWith("application/json");
            assertThat(result.header("Cache-Control")).isEqualTo("no-store");
        });
    }

    @Test
    void aWellFormedSingleTraceIdIsKeptAndAnythingElseIsReplaced() {
        String valid = DbTestSupport.randomTraceId();
        Result kept = app.get("/api/home").header("X-Trace-Id", valid).send();
        assertThat(kept.header("X-Trace-Id")).isEqualTo(valid);
        assertThat(kept.json().get("traceId")).isEqualTo(valid);

        for (String forged : List.of("forged-trace", valid.toUpperCase(java.util.Locale.ROOT), valid + "0",
                "<script>alert(1)</script>", "")) {
            Result replaced = app.get("/api/home").header("X-Trace-Id", forged).send();
            assertThat(replaced.header("X-Trace-Id")).as("위조 '%s'", forged).matches("^[0-9a-f]{32}$")
                    .isNotEqualTo(forged).isEqualTo(replaced.json().get("traceId"));
            assertThat(replaced.body()).doesNotContain("script", "forged");
        }
        Result twoHeaders = app.get("/api/home").header("X-Trace-Id", valid).header("X-Trace-Id", DbTestSupport.randomTraceId())
                .send();
        assertThat(twoHeaders.header("X-Trace-Id")).as("두 개면 새 값").isNotEqualTo(valid).matches("^[0-9a-f]{32}$");
        Result success = app.get("/api/health").header("X-Trace-Id", "nope").send();
        assertThat(success.header("X-Trace-Id")).as("성공 응답도 헤더를 단다").matches("^[0-9a-f]{32}$");
    }

    @Test
    void restBodiesOverSixtyFourKilobytesAre400WithOrWithoutContentLength() {
        Guardian guardian = WebFixtures.guardian(app, db);
        String unknownCode = DbTestSupport.randomCode();
        byte[] exactlyAtLimit = padded("{\"code\":\"" + unknownCode + "\"}", REST_LIMIT);
        byte[] overLimit = padded("{\"code\":\"" + unknownCode + "\"}", REST_LIMIT + 1);

        Result atLimit = app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .body(exactlyAtLimit, "application/json").send();
        assertThat(atLimit.status()).as("정확히 64KB는 통과해 서비스가 답한다").isEqualTo(404);
        assertThat(atLimit.code()).isEqualTo("CODE_NOT_FOUND");

        Result declared = app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .body(overLimit, "application/json").send();
        Result chunked = app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .body(overLimit, "application/json").chunked().send();
        Result anonymous = app.post("/api/device/register").body(overLimit, "application/json").send();
        for (Result result : List.of(declared, chunked, anonymous)) {
            assertThat(result.status()).as("413이 아니라 400 (r)").isEqualTo(400);
            assertThat(result.code()).isEqualTo("VALIDATION_FAILED");
        }
        Result chunkedAtLimit = app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .body(exactlyAtLimit, "application/json").chunked().send();
        assertThat(chunkedAtLimit.status()).isEqualTo(404);
    }

    @Test
    void bodiesThatAreNotJsonObjectsAre400() {
        Guardian guardian = WebFixtures.guardian(app, db);
        String end = "/api/sessions/" + UUID.randomUUID() + "/end";
        List<Result> results = List.of(
                app.post(end).session(guardian.session()).csrf(guardian.csrf()).body(bytes("requestId=1"), "text/plain").send(),
                app.post(end).session(guardian.session()).csrf(guardian.csrf()).body(bytes("{\"requestId\":1}"), null).send(),
                app.post(end).session(guardian.session()).csrf(guardian.csrf()).json("not json").send(),
                app.post(end).session(guardian.session()).csrf(guardian.csrf()).json("[]").send(),
                app.post(end).session(guardian.session()).csrf(guardian.csrf()).json("{\"requestId\":").send(),
                app.post(end).session(guardian.session()).csrf(guardian.csrf()).send());

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(400);
            assertThat(result.code()).isEqualTo("VALIDATION_FAILED");
        });
    }

    @Test
    void anUnexpectedFailureIs500InternalErrorWithoutStackOrMessageAndOneAppErrorLine() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        UUID childId = db.insertChild(guardianId);
        db.jdbc().update("update core.core_child set interests_json = '{\"x\":1}'::jsonb where id = :id",
                new MapSqlParameterSource("id", childId));
        String session = app.sessionCookieFor(guardianId);

        Result failed = app.get("/api/home").session(session).send();

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.json()).containsOnlyKeys("code", "message", "traceId");
        assertThat(failed.body()).doesNotContain("Exception", "at kr.", "JsonCodec", "interests", "\"x\"", "tools.jackson");
        assertThat(logs.events(EventCode.APP_ERROR)).anySatisfy(event -> {
            assertThat(event.get("traceId")).isEqualTo(failed.json().get("traceId"));
            assertThat(LogCapture.attrs(event)).containsOnlyKeys("errorKind");
        });
        assertThat(logs.lines()).noneMatch(line -> line.contains("at kr.mujoong") || line.contains("\"x\":1"));
        WebFixtures.deleteSessions(db, guardianId);
    }

    @Test
    void inputIsNeverReflectedInErrorResponses() {
        Guardian guardian = WebFixtures.guardian(app, db);
        String marker = "<img src=x onerror=alert(1)>";
        List<Result> results = List.of(
                app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                        .json("{\"code\":\"" + marker + "\"}").send(),
                app.get("/api/sessions/" + java.net.URLEncoder.encode(marker, StandardCharsets.UTF_8)).session(guardian.session())
                        .send(),
                app.get("/api/sessions/" + UUID.randomUUID() + "?sinceSeq=" + java.net.URLEncoder.encode(marker,
                        StandardCharsets.UTF_8)).session(guardian.session()).send(),
                app.post("/api/activities/free").session(guardian.session()).csrf(guardian.csrf())
                        .json("{\"idempotencyKey\":\"" + marker + "\"}").send(),
                app.get("/api/" + java.net.URLEncoder.encode(marker, StandardCharsets.UTF_8)).session(guardian.session()).send());

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isBetween(400, 404);
            assertThat(result.body()).doesNotContain("img", "onerror", "alert", "sinceSeq", "idempotencyKey");
            assertThat(result.json()).containsOnlyKeys("code", "message", "traceId");
        });
        assertThat(logs.lines()).noneMatch(line -> line.contains("onerror"));
    }

    @Test
    void timesAreUtcIsoWithAtMostMillisecondsTruncatedNotRounded() {
        app.clock().setInstant(Instant.parse("2027-01-01T00:00:00.123987654Z"));

        Result issued = app.post("/api/device/bootstrap").send();

        assertThat(issued.status()).isEqualTo(201);
        WebFixtures.trackDeviceByCode(db, String.valueOf(issued.json().get("code")));
        assertThat(String.valueOf(issued.json().get("expiresAt"))).matches(TIME).isEqualTo("2027-01-02T00:00:00.123Z");
        String cookie = issued.setCookie("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow().value();
        Result status = app.get("/api/device/bootstrap/status").cookie("MUJOONG_DEVICE_BOOTSTRAP", cookie).send();
        assertThat(String.valueOf(status.json().get("expiresAt"))).isEqualTo("2027-01-02T00:00:00.123Z");

        app.clock().setInstant(Instant.parse("2027-01-03T09:00:00Z"));
        Result whole = app.post("/api/device/bootstrap").send();
        WebFixtures.trackDeviceByCode(db, String.valueOf(whole.json().get("code")));
        assertThat(String.valueOf(whole.json().get("expiresAt"))).matches(TIME).isEqualTo("2027-01-04T09:00:00Z");
    }

    // ------------------------------------------------------------------ 도움

    /** JSON 객체 뒤를 공백으로 채워 정확히 {@code size} 바이트로 만든다. */
    private static byte[] padded(String json, int size) {
        byte[] head = bytes(json);
        byte[] body = new byte[size];
        Arrays.fill(body, (byte) ' ');
        System.arraycopy(head, 0, body, 0, head.length);
        return body;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
