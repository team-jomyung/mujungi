package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.web.support.AppOutputCapture;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.core.web.support.WebFixtures.IssuedCode;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 로그 · 봉투 · 지표 · 전달된 Outbox 위생과 보안 사건 (U1 3.5 Step 34 · SD-15 · NFR9.8 · NFR9.14 · BR8.7 · BR5.11 · BR5.13 · OD-3 · OD-13).
 *
 * <p>실제 흐름(코드 받기 → 등록 → claim → 토큰 확인 → 연결 보고 → 시작 → 진행 조회(전사 중계) → 끝내기 → 재등록 → DeviceReleased 전달)과 보안
 * 사건 5종을 돈 뒤, 앱이 표준 출력에 쓰는 줄 · 오류 봉투 · Prometheus 지표 · 전달 끝난 Outbox 행에 내부 토큰 · 기기 토큰 · bootstrap 비밀 ·
 * 세션 쿠키 · CSRF 토큰 · 전체 등록 코드 · 08 입력 원문 · 전사 · 원시 id가 하나도 없는지 본다. 시험 로그인 차단 사건을 보려고 dev가 아닌
 * 조합으로 띄운다. 3.6 수정 분량: 거절된 본문(JSON 아님 · 봉투 위반 · 모르는 enum · 형식 위반 코드 · 길이 초과 서술)에 넣은 표지가 오류 봉투 ·
 * 출력 줄 어디에도 되울리지 않는다(SD-11 · BR5.13: 본문 · payload는 로그 · 예외 메시지에 넣지 않는다).
 */
@Tag("u1")
@Tag("db")
class CoreLogHygieneIT {

    private static final String CONTEXT_MARKER = "08원문표지";
    private static final String TRANSCRIPT_MARKER = "전사표지문장";

    private static DbTestSupport db;
    private static BusinessFixtures fixtures;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static LogCapture events;
    private static AppOutputCapture output;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        BusinessFixtures.applyReferenceSeed(db.postgres());
        fixtures = new BusinessFixtures(db);
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).start();
        events = LogCapture.attach();
        output = AppOutputCapture.attach();
    }

    @AfterAll
    static void stop() {
        if (output != null) {
            output.close();
        }
        if (events != null) {
            events.close();
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
        freshWindow();
        events.clear();
    }

    @Test
    void aFullFlowLeavesNoSecretsInOutputEnvelopesMetricsOrDeliveredOutbox() {
        List<String> secrets = new ArrayList<>();
        List<Result> responses = new ArrayList<>();
        secrets.add(app.internalToken());

        Family family = fixtures.family(app.clock().now().minusSeconds(3_600));
        Guardian parent = WebFixtures.guardianFor(app, family.guardianId());
        secrets.addAll(List.of(parent.session(), CoreWebApp.sessionId(parent.session()), parent.csrf(),
                family.guardianId().toString(), family.childId().toString()));

        IssuedCode first = WebFixtures.issueCode(app, db);
        String bootstrapSecret = first.jar().get("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow();
        secrets.addAll(List.of(first.code(), bootstrapSecret));
        responses.add(WebFixtures.register(app, parent, first.code()));
        Result claim = first.jar().accept(app.post("/api/device/bootstrap/claim").cookies(first.jar()).send());
        responses.add(claim);
        String deviceToken = first.jar().get("MUJOONG_DEVICE").orElseThrow();
        UUID deviceId = UUID.fromString(String.valueOf(claim.json().get("deviceId")));
        secrets.addAll(List.of(deviceToken, deviceId.toString()));
        responses.add(app.get("/api/device/me").cookies(first.jar()).send());
        responses.add(app.post("/internal/devices/verify-token").internalToken()
                .json("{\"deviceToken\":\"" + deviceToken + "\"}").send());
        report(deviceId, "CONNECTED");

        Result started = app.post("/api/activities/free").session(parent.session()).csrf(parent.csrf())
                .json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"context\":{\"knownContent\":\"" + CONTEXT_MARKER + "\"}}")
                .send();
        assertThat(started.status()).isEqualTo(202);
        UUID sessionId = UUID.fromString(String.valueOf(started.json().get("sessionId")));
        secrets.addAll(List.of(CONTEXT_MARKER, sessionId.toString()));
        realtime.route("GET", WebFixtures.RT_VIEW).respond(StubResponse.json(200, viewWithTranscript(sessionId)));
        Result relayed = app.get("/api/sessions/" + sessionId).session(parent.session()).send();
        assertThat(relayed.body()).as("전사는 보호자 화면에는 간다").contains(TRANSCRIPT_MARKER);
        responses.add(app.post("/api/sessions/" + sessionId + "/end").session(parent.session()).csrf(parent.csrf())
                .json("{\"requestId\":\"" + UUID.randomUUID() + "\"}").send());

        app.clock().advance(Duration.ofSeconds(1));
        report(deviceId, "DISCONNECTED");
        IssuedCode second = WebFixtures.issueCode(app, db);
        secrets.addAll(List.of(second.code(), second.jar().get("MUJOONG_DEVICE_BOOTSTRAP").orElseThrow()));
        responses.add(WebFixtures.register(app, parent, second.code()));
        WebFixtures.awaitEvent(realtime, "DeviceReleased", deviceId, Duration.ofSeconds(10))
                .orElseThrow(() -> new AssertionError("DeviceReleased 미전달"));
        awaitOutboxPublished(family.guardianId());

        responses.addAll(securityEventFlows(secrets));
        responses.add(app.get("/api/sessions/" + sessionId).session(parent.session()).header("X-Trace-Id", parent.csrf()).send());
        app.bean(EventLogger.class).flushMerged();

        List<String> lines = output.lines();
        assertThat(lines).as("흐름이 실제로 줄을 남겼다").isNotEmpty();
        for (String secret : secrets) {
            assertThat(lines).as("출력 줄에 비밀 · 원문 · 원시 id").noneMatch(line -> line.contains(secret));
        }
        assertThat(lines).noneMatch(line -> line.contains(TRANSCRIPT_MARKER));
        assertThat(lines).allSatisfy(line -> assertThat(line).startsWith("{\"ts\":").endsWith("}"));

        for (Result response : responses) {
            for (String secret : List.of(app.internalToken(), deviceToken, bootstrapSecret, parent.session(), parent.csrf())) {
                assertThat(response.body()).doesNotContain(secret);
            }
            if (response.status() >= 400) {
                assertThat(response.json()).containsOnlyKeys("code", "message", "traceId");
                for (String secret : secrets) {
                    assertThat(response.body()).as("오류 봉투").doesNotContain(secret);
                }
            }
        }

        String metrics = app.management("/actuator/prometheus").body();
        for (String secret : secrets) {
            assertThat(metrics).as("지표").doesNotContain(secret);
        }
        assertThat(metrics).doesNotContain(TRANSCRIPT_MARKER);

        List<Map<String, Object>> outbox = db.jdbc().queryForList(
                "select status, payload_json::text as payload, purged_at from core.core_outbox_event where guardian_id = :g",
                new MapSqlParameterSource("g", family.guardianId()));
        assertThat(outbox).isNotEmpty().allSatisfy(row -> {
            for (String secret : List.of(deviceToken, bootstrapSecret, first.code(), second.code(), CONTEXT_MARKER,
                    TRANSCRIPT_MARKER, parent.session())) {
                assertThat(String.valueOf(row.get("payload"))).doesNotContain(secret);
            }
            if ("PUBLISHED".equals(row.get("status"))) {
                assertThat(row.get("payload")).as("전달 뒤 payload 비움(BR5.11)").isEqualTo("{}");
                assertThat(row.get("purged_at")).isNotNull();
            }
        });
    }

    @Test
    void theFiveSecurityEventsAreLoggedAndCounted() {
        List<Result> responses = securityEventFlows(new ArrayList<>());
        assertThat(responses).isNotEmpty();

        assertThat(events.events(EventCode.SECURITY_AUTH_FAILED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("kind", "GUARDIAN_SESSION").containsEntry("statusCode", 403));
        assertThat(events.events(EventCode.SECURITY_RATE_LIMITED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("limiter", "REGISTER_FAILURE").containsEntry("statusCode", 429));
        assertThat(events.events(EventCode.SECURITY_ORIGIN_DENIED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("channel", "REST").containsEntry("statusCode", 403));
        assertThat(events.events(EventCode.SECURITY_DEV_LOGIN_BLOCKED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("statusCode", 404));
        assertThat(events.events(EventCode.SECURITY_INTERNAL_TOKEN_FAILED)).anySatisfy(event ->
                assertThat(LogCapture.attrs(event)).containsEntry("direction", "REALTIME_TO_CORE").containsEntry("statusCode", 401));
        assertThat(events.events()).filteredOn(event -> String.valueOf(event.get("event")).startsWith("SECURITY_"))
                .allSatisfy(event -> assertThat(event.get("component")).asString().startsWith("core-api/"));

        String metrics = app.management("/actuator/prometheus").body();
        for (String type : List.of("auth_failed", "rate_limited", "origin_denied", "dev_login_blocked", "internal_token_failed")) {
            assertThat(metrics).as(type).containsPattern("mujoong_security_event_total\\{[^}]*type=\"" + type + "\"[^}]*\\} [1-9]");
        }
    }

    @Test
    void registerCodeFailuresCount400404And409ButNot201() {
        Guardian owner = WebFixtures.guardian(app, db);
        Guardian other = WebFixtures.guardian(app, db);
        IssuedCode taken = WebFixtures.issueCode(app, db);
        assertThat(WebFixtures.register(app, owner, taken.code()).status()).isEqualTo(201);
        IssuedCode fresh = WebFixtures.issueCode(app, db);
        freshWindow();
        events.clear();

        List<Integer> statuses = new ArrayList<>();
        for (String code : List.of("12", DbTestSupport.randomCode(), taken.code(), fresh.code())) {
            freshWindow();
            statuses.add(WebFixtures.register(app, other, code).status());
        }

        assertThat(statuses).containsExactly(400, 404, 409, 201);
        List<Object> counted = events.events(EventCode.SECURITY_AUTH_FAILED).stream()
                .map(LogCapture::attrs)
                .filter(attrs -> "REGISTER_CODE".equals(attrs.get("kind")))
                .map(attrs -> attrs.get("statusCode"))
                .toList();
        assertThat(counted).containsExactly(400, 404, 409);
        assertThat(output.lines()).noneMatch(line -> line.contains(taken.code()) || line.contains(fresh.code()));
    }

    @Test
    void repeatedIdenticalSecurityEventsAreMergedIntoOneRepeatLine() {
        double before = app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "auth_failed");

        for (int i = 0; i < 5; i++) {
            assertThat(app.get("/api/home").send().status()).isEqualTo(401);
        }
        List<Map<String, Object>> immediate = guardianSessionFailures();
        assertThat(immediate).as("창 안의 첫 줄만 바로").hasSize(1);
        assertThat(LogCapture.attrs(immediate.get(0))).doesNotContainKey("repeat");

        app.clock().advance(Duration.ofSeconds(1));
        app.bean(EventLogger.class).flushMerged();

        List<Map<String, Object>> merged = guardianSessionFailures();
        assertThat(merged).hasSize(2);
        assertThat(LogCapture.attrs(merged.get(1))).containsEntry("repeat", 4).containsEntry("statusCode", 401);
        assertThat(app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "auth_failed") - before)
                .as("카운터는 실제 건수").isEqualTo(5.0);
    }

    @Test
    void rejectedBodiesAreNeverEchoedIntoEnvelopesOrOutputLines() {
        String marker = "거절본문표지" + UUID.randomUUID();
        Guardian guardian = WebFixtures.guardian(app, db);
        UUID deviceId = UUID.randomUUID();

        List<Result> rejected = new ArrayList<>();
        rejected.add(app.post("/internal/events").internalToken().json("not json " + marker).send());
        rejected.add(app.post("/internal/events").internalToken()
                .json("{\"eventId\":\"" + marker + "\",\"eventType\":\"DeviceStatusChanged\",\"payload\":{\"note\":\"" + marker
                        + "\"}}").send());
        rejected.add(app.put("/internal/devices/" + deviceId + "/connection").internalToken()
                .json("{\"state\":\"" + marker + "\",\"reportedAt\":\"" + marker + "\"}").send());
        rejected.add(app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"code\":\"" + marker + "\"}").send());
        rejected.add(app.post("/api/activities/free").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"context\":{\"knownContent\":\"" + marker
                        + "가".repeat(200) + "\"}}").send());
        // 병합 창에 남은 반복 줄까지 내보낸다
        freshWindow();

        assertThat(rejected).extracting(Result::status).containsExactly(400, 400, 400, 400, 400);
        for (Result result : rejected) {
            assertThat(result.json()).containsOnlyKeys("code", "message", "traceId");
            assertThat(result.body()).as("오류 봉투에 입력값이 없다").doesNotContain(marker);
        }
        assertThat(rejected).extracting(Result::code)
                .containsExactly("INVALID_ENVELOPE", "INVALID_ENVELOPE", "INVALID_ENVELOPE", "VALIDATION_FAILED",
                        "VALIDATION_FAILED");
        assertThat(output.lines()).as("출력 줄에 거절된 본문 조각이 없다").noneMatch(line -> line.contains(marker));
    }

    // ------------------------------------------------------------------ 도움

    /** 보안 사건 5종을 낸다(각각 새 병합 창에서). 쓴 비밀은 {@code secrets}에 더한다. */
    private static List<Result> securityEventFlows(List<String> secrets) {
        List<Result> responses = new ArrayList<>();
        Guardian guardian = WebFixtures.guardian(app, db);
        secrets.addAll(List.of(guardian.session(), guardian.csrf(), guardian.id().toString()));

        freshWindow();
        responses.add(app.post("/api/device/register").session(guardian.session()).csrf("wrong").json("{\"code\":\"12\"}").send());
        freshWindow();
        responses.add(app.post("/api/device/register").session(guardian.session()).csrf(guardian.csrf())
                .header("Origin", "https://attacker.invalid").json("{\"code\":\"12\"}").send());
        freshWindow();
        responses.add(app.post("/internal/events").header("X-Internal-Token", guardian.csrf()).json("{}").send());
        freshWindow();
        responses.add(app.post("/api/dev/login").send());
        freshWindow();
        for (int i = 0; i < 10; i++) {
            String code = DbTestSupport.randomCode();
            secrets.add(code);
            responses.add(WebFixtures.register(app, guardian, code));
        }
        responses.add(WebFixtures.register(app, guardian, DbTestSupport.randomCode()));
        assertThat(responses.getLast().status()).as("11번째 실패 한도").isEqualTo(429);
        assertThat(responses.get(3).status()).as("dev가 아니면 시험 로그인 404").isEqualTo(404);
        return responses;
    }

    private static void freshWindow() {
        app.clock().advance(Duration.ofMinutes(1).plusSeconds(1));
        app.bean(EventLogger.class).flushMerged();
    }

    private static List<Map<String, Object>> guardianSessionFailures() {
        return events.events(EventCode.SECURITY_AUTH_FAILED).stream()
                .filter(event -> "GUARDIAN_SESSION".equals(LogCapture.attrs(event).get("kind")))
                .toList();
    }

    private static void report(UUID deviceId, String state) {
        String now = ApiResponses.time(app.clock().now());
        Result result = app.put("/internal/devices/" + deviceId + "/connection").internalToken()
                .json("{\"state\":\"" + state + "\",\"reportedAt\":\"" + now + "\"}").send();
        assertThat(result.status()).isEqualTo(204);
    }

    private static String viewWithTranscript(UUID sessionId) {
        String now = ApiResponses.time(app.clock().now());
        return "{\"sessionId\":\"" + sessionId + "\",\"activityType\":\"FREE_TALK\",\"status\":\"ACTIVE\",\"confirmed\":false,"
                + "\"requestedAt\":\"" + now + "\",\"statusVersion\":2,\"serverTime\":\"" + now + "\",\"gaps\":[],"
                + "\"items\":[{\"seq\":1,\"speaker\":\"CHILD\",\"text\":\"" + TRANSCRIPT_MARKER + "\",\"final\":true,"
                + "\"uncertain\":false,\"startedAt\":\"" + now + "\"}],\"nextSeq\":1}";
    }

    private static void awaitOutboxPublished(UUID guardianId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            Integer pending = db.jdbc().queryForObject(
                    "select count(*) from core.core_outbox_event where guardian_id = :g and status <> 'PUBLISHED'",
                    new MapSqlParameterSource("g", guardianId), Integer.class);
            if (pending != null && pending == 0) {
                return;
            }
            assertThat(System.nanoTime()).as("Outbox가 전달되지 않았다").isLessThan(deadline);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
