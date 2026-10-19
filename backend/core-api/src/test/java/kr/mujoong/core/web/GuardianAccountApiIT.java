package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.activity.ApprovedContentCatalog;
import kr.mujoong.core.child.ChildInput;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.child.ChildProfileService.ChildContext;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentPolicyService.GateResult;
import kr.mujoong.core.consent.PolicyTermsReader;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.ParentAccountFixtures;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Call;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.core.web.support.WebFixtures.Guardian;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
@Tag("parent-account")
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
        ParentAccountFixtures.applyU2Seed(db.postgres());
        fixtures = new BusinessFixtures(db);
        contracts = OpenApiContracts.load();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        google = ParentAccountFixtures.googleTokenEndpoint();
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl())
                .property("mujoong.guardian.google.client-id", ParentAccountFixtures.CLIENT_ID)
                .property("mujoong.guardian.google.client-secret", ParentAccountFixtures.clientSecret())
                .property("mujoong.guardian.google.redirect-uri", ParentAccountFixtures.REDIRECT_URI)
                .property("mujoong.guardian.google.token-uri", google.baseUrl() + ParentAccountFixtures.TOKEN_PATH)
                .property("mujoong.guardian.google.jwks-uri", google.baseUrl() + ParentAccountFixtures.JWKS_PATH)
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
        ParentAccountFixtures.issueFor(google, subject, app.clock().now());
        google.clearRequests();

        Result first = send("POST /api/auth/google", app.post("/api/auth/google").header("Origin", CoreWebApp.ALLOWED_ORIGIN)
                .json(ParentAccountFixtures.loginBody("code-1")));

        assertThat(first.status()).isEqualTo(200);
        UUID guardianId = track(first);
        assertThat(first.json()).containsEntry("consentStatus", "NOT_GIVEN").containsEntry("childRegistered", false)
                .containsEntry("deletionStatus", "NONE");
        CoreWebApp.SetCookie cookie = first.setCookie("MUJOONG_SESSION").orElseThrow();
        assertThat(cookie.has("HttpOnly")).isTrue();
        assertThat(cookie.attribute("SameSite")).isEqualTo("Lax");
        // 서버가 code를 교환했다(브라우저는 토큰을 보지 않는다)
        RecordedRequest exchange = google.requests("POST", ParentAccountFixtures.TOKEN_PATH).get(0);
        assertThat(exchange.body()).contains("grant_type=authorization_code", "code=code-1", "client_id=", "redirect_uri=");
        assertThat(first.body()).doesNotContain(subject, "id_token");

        Result again = send("POST /api/auth/google", app.post("/api/auth/google").session(cookie.value())
                .json(ParentAccountFixtures.loginBody("code-2")));
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
        ParentAccountFixtures.issueFor(google, subject, app.clock().now());
        google.clearRequests();
        List<Result> failures = List.of(
                send("POST /api/auth/google", app.post("/api/auth/google").json("{\"redirectUri\":\"" + ParentAccountFixtures.REDIRECT_URI + "\"}")),
                send("POST /api/auth/google", app.post("/api/auth/google").json("not json")),
                send("POST /api/auth/google", app.post("/api/auth/google")
                        .json("{\"code\":\"c\",\"redirectUri\":\"https://evil.invalid/cb\"}")),
                send("POST /api/auth/google", app.post("/api/auth/google").header("Origin", "https://evil.invalid")
                        .json(ParentAccountFixtures.loginBody("c"))));
        assertThat(google.requests("POST", ParentAccountFixtures.TOKEN_PATH)).as("형식 · redirect · Origin 실패는 구글을 부르지 않는다").isEmpty();

        google.route("POST", ParentAccountFixtures.TOKEN_PATH).respond(StubResponse.json(400, "{\"error\":\"invalid_grant\"}"));
        Result rejectedByGoogle = send("POST /api/auth/google", app.post("/api/auth/google").json(ParentAccountFixtures.loginBody("c")));
        google.route("POST", ParentAccountFixtures.TOKEN_PATH).respond(StubResponse.json(200,
                "{\"id_token\":\"" + ParentAccountFixtures.idToken("someone-else", subject, app.clock().now().plusSeconds(60)) + "\"}"));
        Result wrongAudience = send("POST /api/auth/google", app.post("/api/auth/google").json(ParentAccountFixtures.loginBody("c")));
        google.route("POST", ParentAccountFixtures.TOKEN_PATH).respond(StubResponse.json(200, "{\"access_token\":\"x\"}"));
        Result noIdToken = send("POST /api/auth/google", app.post("/api/auth/google").json(ParentAccountFixtures.loginBody("c")));

        List<Result> all = new ArrayList<>(failures);
        all.addAll(List.of(rejectedByGoogle, wrongAudience, noIdToken));
        assertThat(all).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(401);
            assertThat(result.code()).isEqualTo("GOOGLE_AUTH_FAILED");
            assertThat(result.headerValues("Set-Cookie")).isEmpty();
        });
        assertThat(count("select count(*) from core.core_social_account where provider_user_id = :s", subject)).isZero();
    }

    @Test
    void aTokenWithoutAValidGoogleSignatureIsRejected() {
        // 리뷰 1: 토큰 응답이 바뀌어도(설정 실수 · 중간자) 서명 없는 id_token으로는 세션을 얻을 수 없다
        String subject = "sub-" + UUID.randomUUID();
        String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(JsonCodec.shared().write(Map.of(
                "iss", "https://accounts.google.com", "aud", ParentAccountFixtures.CLIENT_ID,
                "exp", app.clock().now().plusSeconds(300).getEpochSecond(), "sub", subject)).getBytes(StandardCharsets.UTF_8));
        for (String header : List.of("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", "{\"alg\":\"none\"}")) {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes(StandardCharsets.UTF_8))
                    + "." + claims + ".c2ln";
            google.route("POST", ParentAccountFixtures.TOKEN_PATH).respond(StubResponse.json(200, "{\"id_token\":\"" + token + "\"}"));

            Result forged = send("POST /api/auth/google", app.post("/api/auth/google").json(ParentAccountFixtures.loginBody("c")));

            assertThat(forged.status()).as(header).isEqualTo(401);
            assertThat(forged.code()).isEqualTo("GOOGLE_AUTH_FAILED");
            assertThat(forged.headerValues("Set-Cookie")).isEmpty();
        }
        assertThat(count("select count(*) from core.core_social_account where provider_user_id = :s", subject)).isZero();
    }

    @Test
    void everyLoginFailureIsLoggedWithItsReasonAndAForeignOriginAsAnOriginEvent() {
        // 리뷰 4: 까닭은 kind로 남기고 Origin 불일치는 SECURITY_ORIGIN_DENIED(REST)다. code · 토큰 · sub는 남기지 않는다
        String subject = "sub-" + UUID.randomUUID();
        try (LogCapture logs = LogCapture.attach()) {
            send("POST /api/auth/google", app.post("/api/auth/google").json("not json"));
            send("POST /api/auth/google", app.post("/api/auth/google")
                    .json("{\"code\":\"secret-code\",\"redirectUri\":\"https://evil.invalid/cb\"}"));
            google.route("POST", ParentAccountFixtures.TOKEN_PATH).respond(StubResponse.json(400, "{\"error\":\"invalid_grant\"}"));
            send("POST /api/auth/google", app.post("/api/auth/google").json(ParentAccountFixtures.loginBody("secret-code")));
            google.route("POST", ParentAccountFixtures.TOKEN_PATH).respond(StubResponse.json(200,
                    "{\"id_token\":\"" + ParentAccountFixtures.idToken("someone-else", subject, app.clock().now().plusSeconds(60)) + "\"}"));
            send("POST /api/auth/google", app.post("/api/auth/google").json(ParentAccountFixtures.loginBody("secret-code")));
            send("POST /api/auth/google", app.post("/api/auth/google").header("Origin", "https://evil.invalid")
                    .json(ParentAccountFixtures.loginBody("secret-code")));

            assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).extracting(event -> LogCapture.attrs(event).get("kind"))
                    .contains("GOOGLE_BAD_REQUEST", "GOOGLE_REDIRECT_MISMATCH", "GOOGLE_EXCHANGE_FAILED", "GOOGLE_TOKEN_INVALID")
                    .doesNotContain("GUARDIAN_SESSION");
            assertThat(logs.events(EventCode.SECURITY_AUTH_FAILED)).allSatisfy(event ->
                    assertThat(LogCapture.attrs(event)).containsEntry("statusCode", 401));
            assertThat(logs.events(EventCode.SECURITY_ORIGIN_DENIED)).singleElement().satisfies(event ->
                    assertThat(LogCapture.attrs(event)).containsEntry("channel", "REST").containsEntry("statusCode", 401));
            assertThat(logs.lines()).noneMatch(line -> line.contains("secret-code") || line.contains(subject));
        }
    }

    // ------------------------------------------------------------------ 02 동의

    @Test
    void consentAndChildWritesAreRefusedOnceTheAccountIsMarkedForDeletion() {
        // 리뷰 9: 요청 단계 확인(GuardianPrincipal)을 지난 뒤 다른 기기의 삭제 접수가 커밋된 경우를 서비스 직접 호출로 재현한다
        Guardian guardian = WebFixtures.guardian(app, db);
        trackSession(guardian.id());
        Guardian withChild = WebFixtures.guardian(app, db);
        trackSession(withChild.id());
        db.insertChild(withChild.id());
        MapSqlParameterSource ids = new MapSqlParameterSource("ids", new UUID[] {guardian.id(), withChild.id()});
        db.jdbc().update("update core.core_guardian set status = 'DELETION_REQUESTED' where id = any(:ids)", ids);
        ConsentPolicyService consents = app.bean(ConsentPolicyService.class);
        ChildProfileService profiles = app.bean(ChildProfileService.class);
        List<ConsentPolicyService.Agreement> four = List.of(new ConsentPolicyService.Agreement("VOICE_AND_TEXT", true),
                new ConsentPolicyService.Agreement("GUARDIAN_VIEW", true), new ConsentPolicyService.Agreement("REPORT_SHARING", true),
                new ConsentPolicyService.Agreement("RAW_AUDIO", true));

        List<ThrowingCallable> writes = List.of(
                () -> consents.give(guardian.id(), "1.0", four),
                () -> profiles.register(guardian.id(), new ChildInput.Valid("하늘", 8, List.of())),
                () -> profiles.update(withChild.id(), null, 9, null),
                () -> profiles.replaceStopExpressions(withChild.id(), List.of("끝낼래")));
        for (ThrowingCallable write : writes) {
            assertThatThrownBy(write).isInstanceOfSatisfying(CoreApiException.class,
                    refused -> assertThat(refused.code()).isEqualTo(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS));
        }
        assertThat(count("select count(*) from core.core_consent where actor_guardian_id = :g", guardian.id())).isZero();
        assertThat(count("select count(*) from core.core_child where guardian_id = :g", guardian.id())).isZero();
        assertThat(count("select age from core.core_child where guardian_id = :g", withChild.id())).as("수정되지 않음").isEqualTo(8);
        assertThat(count("select count(*) from core.core_stop_expression s join core.core_child c on c.id = s.child_id"
                + " where c.guardian_id = :g", withChild.id())).isZero();
    }

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
    void theSingleCatalogPinsTheLatestApprovedVersionAndSharesThePolicyVersionWithTheGate() {
        // 리뷰 10: 승인 문구를 읽는 곳은 U1 ApprovedContentCatalog 하나다(약관 · 정책 버전 · 세션 문구 고정이 같은 곳)
        ApprovedContentCatalog catalog = app.bean(ApprovedContentCatalog.class);
        assertThat(app.bean(PolicyTermsReader.class).currentPolicyVersion()).isEqualTo(catalog.currentPolicyVersion())
                .contains("1.0");
        String code = "U2_TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        UUID contentId = UUID.randomUUID();
        UUID retired = UUID.randomUUID();
        UUID approved = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource("code", code).addValue("content", contentId)
                .addValue("retired", retired).addValue("approved", approved).addValue("draft", draft);
        try {
            db.jdbc().update("insert into core.core_approved_content (id, content_code, content_type, created_at)"
                    + " values (:content, :code, 'APPROVED_AUDIO', now())", params);
            db.jdbc().update("insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path,"
                    + " status, created_at) values (:retired, :content, 1, '첫 문구', null, 'RETIRED', now()),"
                    + " (:approved, :content, 2, '둘째 문구', 'u2-test-v2.wav', 'APPROVED', now()),"
                    + " (:draft, :content, 3, '셋째 문구', null, 'DRAFT', now())", params);

            Map<String, ApprovedContentCatalog.PinnedContent> pinned = catalog.pin(List.of(code, "NO_SUCH_CODE"));

            assertThat(pinned).containsOnlyKeys(code);
            assertThat(pinned.get(code).contentVersionId()).as("RETIRED · DRAFT가 아니라 APPROVED 버전").isEqualTo(approved);
            assertThat(pinned.get(code).text()).isEqualTo("둘째 문구");
            assertThat(pinned.get(code).audioUrl()).isEqualTo("/media/approved/u2-test-v2.wav");
            assertThat(pinned.get(code).toString()).doesNotContain("둘째 문구");
            assertThat(catalog.latestApproved("POLICY_TERMS_RAW_AUDIO")).hasValueSatisfying(terms ->
                    assertThat(terms.audioUrl()).as("약관은 음성이 없다").isNull());
        } finally {
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
