package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.identity.SeedIds;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.CookieJar;
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

/**
 * 보안 필터 체인 (U1 3.5 Step 34 · SD-4 · SD-6 · SD-8 · SD-11 · BR8.8 · NFR9.3 · NFR9.6 · J75(a)(g) · 편차 (e)).
 *
 * <p>경로가 인증 수단을 정한다: CSRF 면제는 정확한 경로뿐이고({@code /api/device/**} 통째 면제 없음), 익명 변경 요청은 CSRF 판정 전에 401,
 * 인증된 요청의 틀린 토큰 · 다른 origin은 403, 내부 토큰 · 기기 쿠키 · 보호자 세션은 서로의 경로에서 권한이 아니며, 맞는 체인이 없으면 404,
 * 준비 상태가 아니면 인증보다 먼저 503이다. 시험 로그인(CSRF 면제 1번)을 보려고 dev + 스위치 켬 조합으로 띄운다(SD-14 조합 13).
 * 구글 교환(면제 2번)은 U2 경로라 U1에는 없다.
 */
@Tag("u1")
@Tag("db")
class SecurityChainIT {

    private static final String CHECK_INTERVAL_SECONDS = "3600";
    private static final String CODE_FORMAT_ONLY = "{\"code\":\"!\"}";

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static LogCapture logs;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        db.trackGuardian(SeedIds.GUARDIAN);
        app = CoreWebApp.builder(db.postgres())
                .internalToken(token)
                .realtime(realtime.baseUrl())
                .devLogin(true)
                .property("mujoong.health.db-check-interval", CHECK_INTERVAL_SECONDS + "s")
                .start();
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
            WebFixtures.deleteSessions(db, SeedIds.GUARDIAN);
            db.close();
        }
    }

    /** 사건 병합 창 · 보안 사건 분당 상한을 시험마다 새로 시작한다(같은 사건의 첫 줄이 바로 나오게). */
    @BeforeEach
    void freshLogWindow() {
        app.clock().advance(Duration.ofMinutes(2));
        app.bean(EventLogger.class).flushMerged();
        logs.clear();
    }

    @Test
    void csrfExemptionIsExactlyTheListedPaths() {
        Result login = app.post("/api/dev/login").send();
        assertThat(login.status()).as("면제 1번 시험 로그인").isEqualTo(200);

        CookieJar device = new CookieJar();
        Result bootstrap = device.accept(app.post("/api/device/bootstrap").send());
        assertThat(bootstrap.status()).as("면제 3번 코드 받기").isEqualTo(201);
        WebFixtures.trackDeviceByCode(db, String.valueOf(bootstrap.json().get("code")));
        Result claim = app.post("/api/device/bootstrap/claim").cookies(device).send();
        assertThat(claim.status()).as("면제 4번 claim(등록 전)").isEqualTo(409);
        assertThat(claim.code()).isEqualTo("NOT_REGISTERED_YET");
        Result internal = app.post("/internal/events").internalToken().json("{}").send();
        assertThat(internal.status()).as("면제 5번 내부 경로").isEqualTo(400);
        assertThat(internal.code()).isEqualTo("INVALID_ENVELOPE");

        Guardian guardian = WebFixtures.guardian(app, db);
        UUID unknownSession = UUID.randomUUID();
        List<Result> withoutToken = List.of(
                app.post("/api/device/register").session(guardian.session()).json(CODE_FORMAT_ONLY).send(),
                app.post("/api/activities/free").session(guardian.session()).json("{\"requestId\":\"" + UUID.randomUUID() + "\"}").send(),
                app.post("/api/sessions/" + unknownSession + "/end").session(guardian.session())
                        .json("{\"requestId\":\"" + UUID.randomUUID() + "\"}").send(),
                app.post("/api/device/anything").session(guardian.session()).json("{}").send(),
                app.put("/api/device/bootstrap").session(guardian.session()).json("{}").send(),
                app.post("/api/device/bootstrap/status").session(guardian.session()).send(),
                app.post("/api/health").session(guardian.session()).send());
        assertThat(withoutToken).allSatisfy(result -> {
            assertThat(result.status()).as("면제 밖 변경 요청은 CSRF 필수").isEqualTo(403);
            assertThat(result.code()).isEqualTo("CSRF_TOKEN_INVALID");
        });

        Result unknownDevicePath = app.post("/api/device/anything").session(guardian.session()).csrf(guardian.csrf())
                .json("{}").send();
        assertThat(unknownDevicePath.status()).as("토큰이 맞으면 처리기 없는 경로 404").isEqualTo(404);
        assertThat(unknownDevicePath.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void anonymousMutatingRequestsAre401BeforeTheCsrfDecisionAndCreateNoSession() {
        double before = authFailures();

        Result register = app.post("/api/device/register").json(CODE_FORMAT_ONLY).send();
        Result unknownCookie = app.post("/api/device/register")
                .session(java.util.Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)))
                .json(CODE_FORMAT_ONLY).send();
        Result home = app.get("/api/home").send();
        Result csrf = app.get("/api/session/csrf").send();

        for (Result result : List.of(register, unknownCookie, home, csrf)) {
            assertThat(result.status()).isEqualTo(401);
            assertThat(result.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(result.headerValues("Set-Cookie")).as("익명 401은 세션을 만들지 않는다").isEmpty();
            assertThat(result.header("Cache-Control")).isEqualTo("no-store");
        }
        assertThat(authFailures() - before).isEqualTo(4.0);
        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("kind", "GUARDIAN_SESSION")
                .containsEntry("statusCode", 401));
    }

    @Test
    void wrongOrMissingCsrfTokenOfAnAuthenticatedGuardianIs403() {
        Guardian guardian = WebFixtures.guardian(app, db);
        Guardian other = WebFixtures.guardian(app, db);
        String end = "/api/sessions/" + UUID.randomUUID() + "/end";
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";

        Result missing = app.post(end).session(guardian.session()).json(body).send();
        Result wrong = app.post(end).session(guardian.session()).csrf("not-a-token").json(body).send();
        Result othersToken = app.post(end).session(guardian.session()).csrf(other.csrf()).json(body).send();
        Result formField = app.post(end).session(guardian.session())
                .body(("_csrf=" + guardian.csrf()).getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded")
                .send();
        for (Result result : List.of(missing, wrong, othersToken, formField)) {
            assertThat(result.status()).isEqualTo(403);
            assertThat(result.code()).isEqualTo("CSRF_TOKEN_INVALID");
            assertThat(result.json().get("traceId")).isEqualTo(result.header("X-Trace-Id"));
        }
        assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("kind", "GUARDIAN_SESSION")
                .containsEntry("statusCode", 403));

        Result passed = app.post(end).session(guardian.session()).csrf(guardian.csrf()).json(body).send();
        assertThat(passed.status()).as("맞는 토큰은 통과해 서비스가 404로 답한다").isEqualTo(404);
        Result refreshed = app.post(end).session(guardian.session()).csrf(WebFixtures.csrf(app, guardian.session()))
                .json(body).send();
        assertThat(refreshed.status()).as("다시 받은 마스킹 토큰도 유효").isEqualTo(404);
    }

    @Test
    void foreignOrNullOriginOnAMutatingRequestIs403WithAnOriginEvent() {
        Guardian guardian = WebFixtures.guardian(app, db);
        String end = "/api/sessions/" + UUID.randomUUID() + "/end";
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";
        double before = app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "origin_denied");

        Result foreign = app.post(end).session(guardian.session()).csrf(guardian.csrf())
                .header("Origin", "https://attacker.invalid").json(body).send();
        Result nullOrigin = app.post(end).session(guardian.session()).csrf(guardian.csrf())
                .header("Origin", "null").json(body).send();
        Result twoOrigins = app.post(end).session(guardian.session()).csrf(guardian.csrf())
                .header("Origin", CoreWebApp.ALLOWED_ORIGIN).header("Origin", "https://attacker.invalid").json(body).send();
        for (Result result : List.of(foreign, nullOrigin, twoOrigins)) {
            assertThat(result.status()).isEqualTo(403);
            assertThat(result.code()).isEqualTo("CSRF_TOKEN_INVALID");
        }
        assertThat(app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "origin_denied") - before)
                .isEqualTo(3.0);
        assertThat(logs.events(EventCode.SECURITY_ORIGIN_DENIED)).anySatisfy(event -> assertThat(LogCapture.attrs(event))
                .containsEntry("channel", "REST")
                .containsEntry("statusCode", 403));

        Result same = app.post(end).session(guardian.session()).csrf(guardian.csrf())
                .header("Origin", CoreWebApp.ALLOWED_ORIGIN).json(body).send();
        assertThat(same.status()).as("허용 origin은 통과").isEqualTo(404);
        Result readOnly = app.get("/api/device").session(guardian.session()).header("Origin", "https://attacker.invalid").send();
        assertThat(readOnly.status()).as("조회 요청은 Origin을 보지 않는다").isEqualTo(200);
        assertThat(readOnly.header("Access-Control-Allow-Origin")).as("CORS 헤더 없음").isNull();
    }

    @Test
    void credentialsOfOneChainGrantNothingOnAnother() {
        Guardian guardian = WebFixtures.guardian(app, db);
        double internalFailures = app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "internal_token_failed");

        Result internalTokenOnGuardianApi = app.get("/api/home").internalToken().send();
        assertThat(internalTokenOnGuardianApi.status()).as("내부 토큰은 보호자 권한이 아니다").isEqualTo(401);
        Result deviceCookieOnGuardianApi = app.get("/api/home").cookie("MUJOONG_DEVICE", TestSecrets.deviceToken()).send();
        assertThat(deviceCookieOnGuardianApi.status()).as("기기 쿠키는 보호자 권한이 아니다").isEqualTo(401);

        Result sessionOnDeviceMe = app.get("/api/device/me").session(guardian.session()).send();
        assertThat(sessionOnDeviceMe.status()).as("보호자 세션은 기기 인증이 아니다").isEqualTo(401);
        assertThat(sessionOnDeviceMe.code()).isEqualTo("DEVICE_NOT_REGISTERED");
        Result sessionOnStatus = app.get("/api/device/bootstrap/status").session(guardian.session()).send();
        assertThat(sessionOnStatus.status()).isEqualTo(401);
        assertThat(sessionOnStatus.code()).isEqualTo("BOOTSTRAP_NOT_FOUND");

        Result sessionOnInternal = app.post("/internal/events").session(guardian.session()).csrf(guardian.csrf())
                .json("{}").send();
        assertThat(sessionOnInternal.status()).as("보호자 세션 · CSRF는 내부 권한이 아니다").isEqualTo(401);
        assertThat(sessionOnInternal.code()).isEqualTo("UNAUTHENTICATED");
        Result verifyWithoutToken = app.post("/internal/devices/verify-token").cookie("MUJOONG_DEVICE", TestSecrets.deviceToken())
                .json("{\"deviceToken\":\"" + TestSecrets.deviceToken() + "\"}").send();
        assertThat(verifyWithoutToken.status()).isEqualTo(401);
        assertThat(app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "internal_token_failed")
                - internalFailures).isEqualTo(2.0);
        assertThat(logs.events(EventCode.SECURITY_INTERNAL_TOKEN_FAILED)).anySatisfy(event ->
                assertThat(LogCapture.attrs(event)).containsEntry("direction", "REALTIME_TO_CORE").containsEntry("statusCode", 401));
    }

    @Test
    void requestsMatchingNoChainAre404AndDocumentationIsNotPublic() {
        Guardian guardian = WebFixtures.guardian(app, db);
        List<Result> unmatched = List.of(
                app.get("/").send(),
                app.get("/index.html").send(),
                app.get("/v3/api-docs").send(),
                app.get("/swagger-ui/index.html").send(),
                app.post("/device").json("{}").send(),
                app.get("/actuator/prometheus").send(),
                app.get("/error").send());
        assertThat(unmatched).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(404);
            assertThat(result.code()).isEqualTo("NOT_FOUND");
            assertThat(result.json().get("traceId")).isEqualTo(result.header("X-Trace-Id"));
        });

        Result unknownApi = app.get("/api/nothing-here").session(guardian.session()).send();
        assertThat(unknownApi.status()).isEqualTo(404);
        assertThat(unknownApi.code()).isEqualTo("NOT_FOUND");
        Result wrongMethod = app.call("DELETE", "/api/home").session(guardian.session()).csrf(guardian.csrf()).send();
        assertThat(wrongMethod.status()).as("405도 404 봉투").isEqualTo(404);
        Result unknownInternal = app.get("/internal/nothing-here").internalToken().send();
        assertThat(unknownInternal.status()).isEqualTo(404);
        assertThat(unknownInternal.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void preflightGetsNoCorsGrant() {
        Result preflight = app.call("OPTIONS", "/api/device/register")
                .header("Origin", "https://attacker.invalid")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-CSRF-TOKEN, Content-Type")
                .send();

        assertThat(preflight.status()).isEqualTo(401);
        assertThat(preflight.headers().keySet()).noneMatch(name -> name.startsWith("access-control-"));
    }

    @Test
    void firewallAndContainerRejectionsAreA400Envelope() {
        List<Result> rejected = List.of(
                app.get("/api/..;/internal/events").send(),
                app.get("/api/%2e%2e/internal/events").send(),
                app.get("/api/home;jsessionid=1").send(),
                app.get("/api//home").send(),
                app.get("/api/home%2F..%2Fhealth").send(),
                app.get("/api/home%00").send());

        assertThat(rejected).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(400);
            assertThat(result.header("Content-Type")).startsWith("application/json");
            assertThat(result.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(result.json()).containsOnlyKeys("code", "message", "traceId");
            assertThat(result.json().get("traceId")).isEqualTo(result.header("X-Trace-Id"));
            assertThat(result.body()).doesNotContain("Tomcat", "internal", "jsessionid");
        });
    }

    @Test
    void notReadyIsAnswered503BeforeAnyAuthenticationDecision() {
        ReadinessState readiness = app.bean(ReadinessState.class);
        try {
            readiness.recordDbCheck(false);
            readiness.recordDbCheck(false);
            assertThat(readiness.recordDbCheck(false)).isEqualTo(ReadinessState.State.NOT_READY);

            double before = authFailures();
            List<Result> results = List.of(
                    app.get("/api/home").send(),
                    app.post("/api/device/register").json(CODE_FORMAT_ONLY).send(),
                    app.get("/api/device/me").send(),
                    app.get("/internal/nothing-here").send(),
                    app.get("/no-chain").send());
            assertThat(results).allSatisfy(result -> {
                assertThat(result.status()).isEqualTo(503);
                assertThat(result.code()).isEqualTo("SERVICE_NOT_READY");
                assertThat(result.header("Retry-After")).isEqualTo(CHECK_INTERVAL_SECONDS);
            });
            assertThat(authFailures()).as("인증 판정까지 가지 않는다").isEqualTo(before);
        } finally {
            assertThat(readiness.recordDbCheck(true)).isEqualTo(ReadinessState.State.READY);
        }
        assertThat(app.get("/api/home").send().status()).isEqualTo(401);
    }

    private static double authFailures() {
        return app.bean(CoreMetrics.class).count("mujoong.security.event", "type", "auth_failed");
    }
}
