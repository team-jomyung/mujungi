package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.catalog.ApprovedContentVersion;
import kr.mujoong.core.catalog.ContentCatalogService;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.child.ChildProfileService.ChildContext;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentPolicyService.GateResult;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.U2Fixtures;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Call;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * U2 보호자 계정 API 통합 시험(실제 Tomcat · 보안 체인 · Spring Session JDBC · PostgreSQL · 가짜 구글 토큰 엔드포인트).
 *
 * <p>증명: AC1.1.1 ~ AC1.1.4 · AC1.1.7(구글만) · AC1.1.8(남의 자원 없음) · AC1.2.2 · AC1.2.4 · AC1.2.9 · AC1.3.1 ~ AC1.3.9 · AC1.5.2 ·
 * AC2.3.4 ~ AC2.3.6 · R1 ~ R9 · 편차 (U2-a) ~ (U2-i). 모든 응답을 parent-api.yaml 연산 계약(상태 · 스키마 · x-error-codes)과 대조한다.
 */
@Tag("u2")
@Tag("db")
class GuardianAccountApiIT {

    private static DbTestSupport db;
    private static BusinessFixtures fixtures;
    private static ContractStubServer realtime;
    private static ContractStubServer google;
    private static CoreWebApp app;
    private static OpenApiContracts contracts;

    private final List<String> violations = new ArrayList<>();
    private final List<UUID> loggedIn = new ArrayList<>();

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        BusinessFixtures.applyReferenceSeed(db.postgres());
        U2Fixtures.applyU2Seed(db.postgres());
        fixtures = new BusinessFixtures(db);
        contracts = OpenApiContracts.load();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        google = U2Fixtures.googleTokenEndpoint();
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl())
                .property("mujoong.guardian.google.client-id", U2Fixtures.CLIENT_ID)
                .property("mujoong.guardian.google.client-secret", U2Fixtures.clientSecret())
                .property("mujoong.guardian.google.redirect-uri", U2Fixtures.REDIRECT_URI)
                .property("mujoong.guardian.google.token-uri", google.baseUrl() + U2Fixtures.TOKEN_PATH)
                .property("mujoong.timing.account.deletion-tick", "1h")
                .start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        if (google != null) {
            google.close();
        }
        if (realtime != null) {
            realtime.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void cleanUp() {
        WebFixtures.deleteSessions(db, loggedIn.toArray(UUID[]::new));
        assertThat(violations).as("응답이 parent-api.yaml 연산 계약과 맞다").isEmpty();
    }

    // ------------------------------------------------------------------ 01 구글 로그인

    @Test
    void firstGoogleLoginCreatesAGuardianAndASessionAndTheSameAccountComesBackAsTheSameGuardian() {
        String subject = "sub-" + UUID.randomUUID();
        U2Fixtures.issueFor(google, subject, app.clock().now());
        google.clearRequests();

        Result first = send("POST /api/auth/google", app.post("/api/auth/google").header("Origin", CoreWebApp.ALLOWED_ORIGIN)
                .json(U2Fixtures.loginBody("code-1")));

        assertThat(first.status()).isEqualTo(200);
        UUID guardianId = track(first);
        assertThat(first.json()).containsEntry("consentStatus", "NOT_GIVEN").containsEntry("childRegistered", false)
                .containsEntry("deletionStatus", "NONE");
        CoreWebApp.SetCookie cookie = first.setCookie("MUJOONG_SESSION").orElseThrow();
        assertThat(cookie.has("HttpOnly")).isTrue();
        assertThat(cookie.attribute("SameSite")).isEqualTo("Lax");
        // 서버가 code를 교환했다(브라우저는 토큰을 보지 않는다)
        RecordedRequest exchange = google.requests("POST", U2Fixtures.TOKEN_PATH).get(0);
        assertThat(exchange.body()).contains("grant_type=authorization_code", "code=code-1", "client_id=", "redirect_uri=");
        assertThat(first.body()).doesNotContain(subject, "id_token");

        Result again = send("POST /api/auth/google", app.post("/api/auth/google").session(cookie.value())
                .json(U2Fixtures.loginBody("code-2")));
        assertThat(UUID.fromString(String.valueOf(again.json().get("guardianId")))).isEqualTo(guardianId);
        String renewed = again.setCookie("MUJOONG_SESSION").orElseThrow().value();
        assertThat(CoreWebApp.sessionId(renewed)).as("로그인 때 세션 ID를 바꾼다").isNotEqualTo(CoreWebApp.sessionId(cookie.value()));
        assertThat(send("GET /api/me", app.get("/api/me").session(cookie.value())).status()).as("앞 세션은 무효").isEqualTo(401);
        assertThat(send("GET /api/me", app.get("/api/me").session(renewed)).json()).containsEntry("guardianId", guardianId.toString());
        assertThat(count("select count(*) from core.core_social_account where guardian_id = :g", guardianId)).isEqualTo(1);
    }

    @Test
    void everyGoogleLoginFailureIs401WithoutACookieAndWithoutAGuardian() {
        String subject = "sub-" + UUID.randomUUID();
        U2Fixtures.issueFor(google, subject, app.clock().now());
        google.clearRequests();
        List<Result> failures = List.of(
                send("POST /api/auth/google", app.post("/api/auth/google").json("{\"redirectUri\":\"" + U2Fixtures.REDIRECT_URI + "\"}")),
                send("POST /api/auth/google", app.post("/api/auth/google").json("not json")),
                send("POST /api/auth/google", app.post("/api/auth/google")
                        .json("{\"code\":\"c\",\"redirectUri\":\"https://evil.invalid/cb\"}")),
                send("POST /api/auth/google", app.post("/api/auth/google").header("Origin", "https://evil.invalid")
                        .json(U2Fixtures.loginBody("c"))));
        assertThat(google.requests("POST", U2Fixtures.TOKEN_PATH)).as("형식 · redirect · Origin 실패는 구글을 부르지 않는다").isEmpty();

        google.route("POST", U2Fixtures.TOKEN_PATH).respond(StubResponse.json(400, "{\"error\":\"invalid_grant\"}"));
        Result rejectedByGoogle = send("POST /api/auth/google", app.post("/api/auth/google").json(U2Fixtures.loginBody("c")));
        google.route("POST", U2Fixtures.TOKEN_PATH).respond(StubResponse.json(200,
                "{\"id_token\":\"" + U2Fixtures.idToken("someone-else", subject, app.clock().now().plusSeconds(60)) + "\"}"));
        Result wrongAudience = send("POST /api/auth/google", app.post("/api/auth/google").json(U2Fixtures.loginBody("c")));
        google.route("POST", U2Fixtures.TOKEN_PATH).respond(StubResponse.json(200, "{\"access_token\":\"x\"}"));
        Result noIdToken = send("POST /api/auth/google", app.post("/api/auth/google").json(U2Fixtures.loginBody("c")));

        List<Result> all = new ArrayList<>(failures);
        all.addAll(List.of(rejectedByGoogle, wrongAudience, noIdToken));
        assertThat(all).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(401);
            assertThat(result.code()).isEqualTo("GOOGLE_AUTH_FAILED");
            assertThat(result.headerValues("Set-Cookie")).isEmpty();
        });
        assertThat(count("select count(*) from core.core_social_account where provider_user_id = :s", subject)).isZero();
    }

    // ------------------------------------------------------------------ 02 동의

    @Test
    void theCurrentPolicyHasFourItemsWithTheAiNoticeAndConsentingToAllFourGivesTheGate() {
        Guardian guardian = WebFixtures.guardian(app, db);
        trackSession(guardian.id());

        Result policy = send("GET /api/policies/current", app.get("/api/policies/current").session(guardian.session()));
        assertThat(policy.status()).isEqualTo(200);
        assertThat(policy.body()).contains("생성형 AI 안내", "무중은 생성형 AI가 아이와 대화하는 서비스예요.");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) policy.json().get("items");
        assertThat(items).extracting(item -> item.get("type"))
                .containsExactly("VOICE_AND_TEXT", "GUARDIAN_VIEW", "REPORT_SHARING", "RAW_AUDIO");
        assertThat(policy.body()).doesNotContain("termsVersionId");

        Result before = send("GET /api/consents", app.get("/api/consents").session(guardian.session()));
        assertThat(before.json()).containsEntry("status", "NOT_GIVEN").containsEntry("policyVersion", "1.0");

        String version = String.valueOf(policy.json().get("policyVersion"));
        assertThat(send("PUT /api/consents", app.put("/api/consents").session(guardian.session())
                .json(consentBody(version, true))).status()).as("CSRF 없음").isEqualTo(403);
        List<String> invalid = List.of(
                "{\"policyVersion\":\"" + version + "\",\"items\":[{\"type\":\"VOICE_AND_TEXT\",\"agreed\":true}]}",
                consentBody(version, false),
                consentBody("0.1", true),
                consentBody(version, true).replace("RAW_AUDIO", "VOICE_AND_TEXT"),
                consentBody(version, true).replace("RAW_AUDIO", "MARKETING"),
                "[]");
        for (String body : invalid) {
            Result rejected = send("PUT /api/consents", app.put("/api/consents").session(guardian.session())
                    .csrf(guardian.csrf()).json(body));
            assertThat(rejected.status()).as(body).isEqualTo(400);
        }
        assertThat(count("select count(*) from core.core_consent where actor_guardian_id = :g", guardian.id())).isZero();

        Result saved = send("PUT /api/consents", app.put("/api/consents").session(guardian.session()).csrf(guardian.csrf())
                .json(consentBody(version, true)));
        assertThat(saved.status()).isEqualTo(204);
        Result again = send("PUT /api/consents", app.put("/api/consents").session(guardian.session()).csrf(guardian.csrf())
                .json(consentBody(version, true)));
        assertThat(again.status()).as("이미 동의했으면 그대로 204").isEqualTo(204);
        assertThat(count("select count(*) from core.core_consent where actor_guardian_id = :g and child_id is null"
                + " and terms_version_id is not null and consent_status = 'GIVEN'", guardian.id())).isEqualTo(4);
        assertThat(send("GET /api/consents", app.get("/api/consents").session(guardian.session())).json())
                .containsEntry("status", "GIVEN");
        assertThat(send("GET /api/me", app.get("/api/me").session(guardian.session())).json())
                .containsEntry("consentStatus", "GIVEN").containsEntry("childRegistered", false);
    }

    @Test
    void consentGivenBeforeTheChildIsRegisteredOpensTheU1StartGate() {
        Guardian guardian = WebFixtures.guardian(app, db);
        trackSession(guardian.id());
        assertThat(send("PUT /api/consents", app.put("/api/consents").session(guardian.session()).csrf(guardian.csrf())
                .json(consentBody("1.0", true))).status()).isEqualTo(204);
        assertThat(send("POST /api/child", app.post("/api/child").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"displayName\":\"하늘\",\"age\":8,\"interests\":[\"공룡\"]}")).status()).isEqualTo(201);
        fixtures.registeredDevice(guardian.id(), ConnectionState.CONNECTED, app.clock().now(), app.clock().now());

        Result started = send("POST /api/activities/free", app.post("/api/activities/free").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"));

        assertThat(started.status()).as("동의 행의 child_id가 비어 있어도 보호자 기준으로 통과(U2-J10)").isEqualTo(202);
    }

    // ------------------------------------------------------------------ 03 아이 정보

    @Test
    void theChildIsRegisteredOnceEditedInPlaceAndValidated() {
        Guardian guardian = WebFixtures.guardian(app, db);
        trackSession(guardian.id());
        assertThat(send("GET /api/child", app.get("/api/child").session(guardian.session())).code())
                .isEqualTo("CHILD_NOT_REGISTERED");
        for (String body : List.of("{\"displayName\":\"\",\"age\":8}", "{\"displayName\":\"하늘\",\"age\":13}",
                "{\"displayName\":\"하늘\",\"age\":8,\"interests\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}",
                "{\"displayName\":\"가나다라마바사아자차카\",\"age\":8}", "{\"age\":8}", "x")) {
            assertThat(send("POST /api/child", app.post("/api/child").session(guardian.session()).csrf(guardian.csrf())
                    .json(body)).status()).as(body).isEqualTo(400);
        }

        Result created = send("POST /api/child", app.post("/api/child").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"displayName\":\" 하늘 \",\"age\":8,\"interests\":[\"공룡\",\"블록 놀이\"]}"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json()).containsEntry("displayName", "하늘").containsEntry("age", 8)
                .containsEntry("interests", List.of("공룡", "블록 놀이"));
        String childId = String.valueOf(created.json().get("childId"));
        assertThat(send("POST /api/child", app.post("/api/child").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"displayName\":\"바다\",\"age\":9}")).code()).isEqualTo("CHILD_EXISTS");

        Result edited = send("PATCH /api/child", app.call("PATCH", "/api/child").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"age\":9}"));
        assertThat(edited.json()).containsEntry("childId", childId).containsEntry("age", 9).containsEntry("displayName", "하늘");
        assertThat(send("PATCH /api/child", app.call("PATCH", "/api/child").session(guardian.session()).csrf(guardian.csrf())
                .json("{}")).status()).isEqualTo(400);
        assertThat(send("GET /api/child", app.get("/api/child").session(guardian.session())).json())
                .containsEntry("age", 9).containsEntry("childId", childId);
        assertThat(send("GET /api/home", app.get("/api/home").session(guardian.session())).body()).contains(childId);
        assertThat(send("GET /api/me", app.get("/api/me").session(guardian.session())).json())
                .containsEntry("childRegistered", true);

        // 남의 보호자는 이 아이를 볼 수 없다(경로에 고를 id가 없다, AC1.1.8)
        Guardian other = WebFixtures.guardian(app, db);
        trackSession(other.id());
        Result othersView = send("GET /api/child", app.get("/api/child").session(other.session()));
        assertThat(othersView.code()).isEqualTo("CHILD_NOT_REGISTERED");
        assertThat(othersView.body()).doesNotContain(childId, "하늘");
        assertThat(send("PATCH /api/child", app.call("PATCH", "/api/child").session(other.session()).csrf(other.csrf())
                .json("{\"age\":10}")).code()).isEqualTo("CHILD_NOT_REGISTERED");
    }

    // ------------------------------------------------------------------ SET-01 종료 표현

    @Test
    void stopExpressionsAreReplacedAsAWholeAndValidated() {
        Guardian guardian = WebFixtures.guardian(app, db);
        trackSession(guardian.id());
        assertThat(send("GET /api/child/stop-expressions", app.get("/api/child/stop-expressions").session(guardian.session()))
                .code()).isEqualTo("CHILD_NOT_REGISTERED");
        assertThat(send("PUT /api/child/stop-expressions", app.put("/api/child/stop-expressions").session(guardian.session())
                .csrf(guardian.csrf()).json("{\"items\":[]}")).code()).isEqualTo("CHILD_NOT_REGISTERED");
        db.insertChild(guardian.id());

        Result empty = send("GET /api/child/stop-expressions",
                app.get("/api/child/stop-expressions").session(guardian.session()));
        assertThat(empty.json()).containsEntry("default", "무중아 그만할래").containsEntry("items", List.of());

        assertThat(put(guardian, "{\"items\":[{\"text\":\"이제 그만\"},{\"text\":\"끝낼래\"}]}").status()).isEqualTo(204);
        Result saved = send("GET /api/child/stop-expressions", app.get("/api/child/stop-expressions").session(guardian.session()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) saved.json().get("items");
        assertThat(items).extracting(item -> item.get("text")).containsExactly("이제 그만", "끝낼래");
        assertThat(items).extracting(item -> item.get("sortOrder")).containsExactly(1, 2);

        for (String body : List.of("{\"items\":[{\"text\":\"이제 그만\"},{\"text\":\"이제그만\"}]}",
                "{\"items\":[{\"text\":\"무중아 그만할래\"}]}", "{\"items\":[{\"text\":\"가\"}]}",
                "{\"items\":[{\"text\":\"하나\"},{\"text\":\"둘둘\"},{\"text\":\"셋셋\"},{\"text\":\"넷넷\"},{\"text\":\"다섯\"},"
                        + "{\"text\":\"여섯\"}]}", "{\"items\":[null]}", "{}")) {
            assertThat(put(guardian, body).status()).as(body).isEqualTo(400);
        }
        assertThat(count("select count(*) from core.core_stop_expression s join core.core_child c on c.id = s.child_id"
                + " where c.guardian_id = :g", guardian.id())).as("거절은 저장된 목록을 바꾸지 않는다").isEqualTo(2);

        assertThat(put(guardian, "{\"items\":[{\"text\":\"끝낼래\"}]}").status()).isEqualTo(204);
        assertThat(put(guardian, "{\"items\":[]}").status()).isEqualTo(204);
        assertThat(count("select count(*) from core.core_stop_expression s join core.core_child c on c.id = s.child_id"
                + " where c.guardian_id = :g", guardian.id())).isZero();
    }

    // ------------------------------------------------------------------ SET0 로그아웃

    @Test
    void logoutEndsOnlyThisSessionAndNeedsCsrf() {
        Guardian guardian = WebFixtures.guardian(app, db);
        trackSession(guardian.id());
        String otherDevice = app.sessionCookieFor(guardian.id());

        assertThat(send("POST /api/auth/logout", app.post("/api/auth/logout").session(guardian.session())).status())
                .as("CSRF 없음").isEqualTo(403);
        Result out = send("POST /api/auth/logout", app.post("/api/auth/logout").session(guardian.session())
                .csrf(guardian.csrf()));

        assertThat(out.status()).isEqualTo(204);
        assertThat(out.setCookie("MUJOONG_SESSION").orElseThrow().attribute("Max-Age")).isEqualTo("0");
        assertThat(send("GET /api/me", app.get("/api/me").session(guardian.session())).status()).isEqualTo(401);
        assertThat(send("GET /api/me", app.get("/api/me").session(otherDevice)).status()).as("다른 기기 세션은 그대로")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ 앱 안 포트(계약 8절)

    @Test
    void portsGiveTheGateSnapshotAndTheChildContextWithTheDefaultStopExpressionFirst() {
        Guardian guardian = WebFixtures.guardian(app, db);
        trackSession(guardian.id());
        ConsentPolicyService consentPolicy = app.bean(ConsentPolicyService.class);
        ChildProfileService profiles = app.bean(ChildProfileService.class);

        GateResult closed = consentPolicy.check(guardian.id());
        assertThat(closed.ok()).isFalse();
        assertThat(closed.snapshot().policyVersion()).isEqualTo("1.0");
        assertThat(profiles.childContext(guardian.id())).isEmpty();

        send("PUT /api/consents", app.put("/api/consents").session(guardian.session()).csrf(guardian.csrf())
                .json(consentBody("1.0", true)));
        send("POST /api/child", app.post("/api/child").session(guardian.session()).csrf(guardian.csrf())
                .json("{\"displayName\":\"하늘\",\"age\":8,\"interests\":[\"공룡\"]}"));
        put(guardian, "{\"items\":[{\"text\":\"끝낼래\"}]}");

        GateResult open = consentPolicy.check(guardian.id());
        assertThat(open.ok()).isTrue();
        assertThat(open.snapshot()).isEqualTo(new ConsentPolicyService.Snapshot("1.0", true, true, true, true));
        ChildContext context = profiles.childContext(guardian.id()).orElseThrow();
        assertThat(context.displayName()).isEqualTo("하늘");
        assertThat(context.interests()).containsExactly("공룡");
        assertThat(context.stopExpressions()).containsExactly(new ChildProfileService.StopExpression("무중아 그만할래", true),
                new ChildProfileService.StopExpression("끝낼래", false));
        assertThat(context.toString()).doesNotContain("하늘", "공룡");
    }

    @Test
    void theCatalogAddsVersionsWithoutOverwritingAndApprovalRetiresThePreviousOne() {
        ContentCatalogService catalog = app.bean(ContentCatalogService.class);
        String code = "U2_TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        try {
            ApprovedContentVersion first = catalog.addVersion(code, "APPROVED_AUDIO", "첫 문구", null);
            assertThat(first.getVersion()).isEqualTo(1);
            assertThat(catalog.latestApproved(code)).as("DRAFT는 읽기 대상이 아니다").isEmpty();
            assertThat(catalog.approve(first.getId())).isTrue();
            assertThat(catalog.approve(first.getId())).as("이미 승인").isFalse();

            ApprovedContentVersion second = catalog.addVersion(code, "APPROVED_AUDIO", "둘째 문구", "u2-test-v2.wav");
            assertThat(second.getVersion()).isEqualTo(2);
            assertThat(catalog.pin(List.of(code)).get(code).contentVersionId()).as("승인 전에는 앞 버전").isEqualTo(first.getId());
            assertThat(catalog.approve(second.getId())).isTrue();

            Map<String, ContentCatalogService.PinnedContent> pinned = catalog.pin(List.of(code, "NO_SUCH_CODE"));
            assertThat(pinned).containsOnlyKeys(code);
            assertThat(pinned.get(code).contentVersionId()).isEqualTo(second.getId());
            assertThat(pinned.get(code).text()).isEqualTo("둘째 문구");
            assertThat(pinned.get(code).audioUrl()).isEqualTo("/media/approved/u2-test-v2.wav");
            assertThat(db.jdbc().queryForObject("select status from core.core_approved_content_version where id = :id",
                    new MapSqlParameterSource("id", first.getId()), String.class)).isEqualTo("RETIRED");
            assertThat(catalog.approve(UUID.randomUUID())).isFalse();
        } finally {
            MapSqlParameterSource params = new MapSqlParameterSource("code", code);
            db.jdbc().update("delete from core.core_approved_content_version where content_id in"
                    + " (select id from core.core_approved_content where content_code = :code)", params);
            db.jdbc().update("delete from core.core_approved_content where content_code = :code", params);
        }
    }

    @Test
    void anonymousCallsToTheU2OperationsAre401() {
        for (String key : List.of("GET /api/me", "GET /api/policies/current", "GET /api/consents", "GET /api/child",
                "GET /api/child/stop-expressions")) {
            Result result = send(key, app.get(key.substring(4)));
            assertThat(result.status()).as(key).isEqualTo(401);
        }
        assertThat(send("DELETE /api/me", app.call("DELETE", "/api/me")).status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ 도움

    private Result put(Guardian guardian, String body) {
        return send("PUT /api/child/stop-expressions", app.put("/api/child/stop-expressions").session(guardian.session())
                .csrf(guardian.csrf()).json(body));
    }

    static String consentBody(String version, boolean agreed) {
        return "{\"policyVersion\":\"" + version + "\",\"items\":["
                + "{\"type\":\"VOICE_AND_TEXT\",\"agreed\":" + agreed + "},{\"type\":\"GUARDIAN_VIEW\",\"agreed\":true},"
                + "{\"type\":\"REPORT_SHARING\",\"agreed\":true},{\"type\":\"RAW_AUDIO\",\"agreed\":true}]}";
    }

    private Result send(String operation, Call call) {
        Result result = call.send();
        violations.addAll(contracts.violations(operation, result));
        return result;
    }

    private UUID track(Result login) {
        UUID guardianId = UUID.fromString(String.valueOf(login.json().get("guardianId")));
        db.trackGuardian(guardianId);
        loggedIn.add(guardianId);
        return guardianId;
    }

    private void trackSession(UUID guardianId) {
        loggedIn.add(guardianId);
    }

    private static int count(String sql, Object value) {
        Integer counted = db.jdbc().queryForObject(sql, new MapSqlParameterSource(sql.contains(":s") ? "s" : "g", value),
                Integer.class);
        return counted == null ? -1 : counted;
    }
}
