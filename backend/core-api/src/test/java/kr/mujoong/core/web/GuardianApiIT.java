package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.identity.SeedIds;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.CoreWebApp.SetCookie;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 보호자 API: 시험 로그인 · CSRF 취득 · 홈 · 기기 보기 · 자유대화 시작 · 진행 조회 · 끝내기 (U1 3.5 Step 34 · 계약 3절 · J119 · BR4.3 · BR4.6 ·
 * BR6.1 · BR6.9 · BR6.15 · BR6.16 · BR8.4 · BR8.5 · SD-5 · SD-6 · SD-9 · NFR9.5 · AC1.1.7 ~ 1.1.9 · AC1.4.2 ~ 1.4.5 · AC2.1.14 · 편차 (u)).
 *
 * <p>시험 로그인을 보려고 dev + 스위치 켬 조합(SD-14 조합 13)으로 띄운다. 시드 보호자 밖의 보호자는 픽스처 행과 저장소에 직접 만든 세션을 쓴다
 * (운영 로그인과 같은 SecurityContext 모양). Realtime은 계약 대역이고 진행 조회는 기본 404(폴백)이다.
 * 시험마다 앱과 시계를 새로 만들고, 앱 종료로 타이머를 마친 뒤 행을 지워 앞 시험의 자동 취소가 다음 시험에 섞이지 않게 한다.
 */
@Tag("skeleton")
@Tag("db")
class GuardianApiIT {

    private static final String LOGIN = "/api/dev/login";
    private DbTestSupport db;
    private BusinessFixtures fixtures;
    private ContractStubServer realtime;
    private CoreWebApp app;
    private final List<UUID> sessionOwners = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() {
        db = DbTestSupport.coreApp();
        BusinessFixtures.applyReferenceSeed(db.postgres());
        fixtures = new BusinessFixtures(db);
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        db.trackGuardian(SeedIds.GUARDIAN);
        sessionOwners.add(SeedIds.GUARDIAN);
        app = CoreWebApp.builder(db.postgres())
                .internalToken(token)
                .realtime(realtime.baseUrl())
                .devLogin(true)
                .start();
    }

    @AfterEach
    void stop() {
        try {
            if (app != null) app.close();
        } finally {
            try {
                if (realtime != null) realtime.close();
            } finally {
                if (db != null) {
                    try {
                        WebFixtures.deleteSessions(db, sessionOwners.toArray(UUID[]::new));
                    } finally {
                        db.close();
                    }
                }
            }
        }
    }

    @Test
    void devLoginOpensASeedGuardianSessionWithFixedCookieAttributesAndRotatesTheSessionId() {
        Result login = app.post(LOGIN).send();

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json()).isEqualTo(Map.of("guardianId", SeedIds.GUARDIAN.toString()));
        assertThat(WebFixtures.schemaErrors("dev-login-response.schema.json", login.body())).isEmpty();
        assertThat(login.header("Cache-Control")).isEqualTo("no-store");
        SetCookie cookie = login.setCookie("MUJOONG_SESSION").orElseThrow();
        assertThat(cookie.has("HttpOnly")).isTrue();
        assertThat(cookie.has("Secure")).isTrue();
        assertThat(cookie.attribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.attribute("Path")).isEqualTo("/");
        assertThat(cookie.attribute("Max-Age")).isEqualTo("2592000");
        assertThat(cookie.has("Domain")).isFalse();
        assertThat(app.get("/api/home").session(cookie.value()).send().status()).isEqualTo(200);

        Result again = app.post(LOGIN).session(cookie.value()).send();
        String rotated = again.setCookie("MUJOONG_SESSION").orElseThrow().value();
        assertThat(rotated).as("로그인마다 세션 ID 교체(고정 방지)").isNotEqualTo(cookie.value());
        assertThat(CoreWebApp.sessionId(rotated)).isNotEqualTo(CoreWebApp.sessionId(cookie.value()));
        assertThat(app.get("/api/home").session(cookie.value()).send().status()).as("앞 세션은 무효").isEqualTo(401);
        assertThat(app.get("/api/home").session(rotated).send().status()).isEqualTo(200);
    }

    @Test
    void csrfTokenNeedsASessionIsNoStoreAndRefreshesTheSessionCookie() {
        assertThat(app.get("/api/session/csrf").send().status()).isEqualTo(401);
        String session = newSession(db.insertGuardian("ACTIVE"));

        Result csrf = app.get("/api/session/csrf").session(session).send();

        assertThat(csrf.status()).isEqualTo(200);
        assertThat(csrf.json()).containsOnlyKeys("token", "headerName");
        assertThat(csrf.json().get("headerName")).isEqualTo("X-CSRF-TOKEN");
        assertThat(WebFixtures.schemaErrors("csrf-response.schema.json", csrf.body())).isEmpty();
        assertThat(csrf.header("Cache-Control")).isEqualTo("no-store");
        SetCookie refreshed = csrf.setCookie("MUJOONG_SESSION").orElseThrow();
        assertThat(refreshed.value()).as("같은 세션, Max-Age만 새로").isEqualTo(session);
        assertThat(refreshed.attribute("Max-Age")).isEqualTo("2592000");
    }

    @Test
    void homeShowsTheSeedChildTheDeviceViewAndTheLock() {
        String session = app.post(LOGIN).send().setCookie("MUJOONG_SESSION").orElseThrow().value();

        Result home = app.get("/api/home").session(session).send();

        assertThat(home.status()).isEqualTo(200);
        assertThat(WebFixtures.schemaErrors("home-response.schema.json", home.body())).isEmpty();
        Map<String, Object> body = home.json();
        assertThat(body).containsOnlyKeys("child", "device", "currentSession", "lock");
        assertThat(body.get("child")).isEqualTo(Map.of(
                "childId", SeedIds.CHILD.toString(), "displayName", "하늘", "age", 8, "interests", List.of("공룡", "그림")));
        assertThat(body.get("currentSession")).isNull();
        assertThat(body.get("lock")).isEqualTo(Map.of("locked", true, "reasonCode", "NO_DEVICE"));
        assertThat(nested(body, "device", "registration")).containsEntry("status", "NONE");
        assertThat(nested(body, "device", "verdict")).containsEntry("canStart", false).containsEntry("reasonCode", "NO_DEVICE");

        UUID childless = db.insertGuardian("ACTIVE");
        Result noChild = app.get("/api/home").session(newSession(childless)).send();
        assertThat(noChild.json().get("child")).isNull();
        assertThat(WebFixtures.schemaErrors("home-response.schema.json", noChild.body())).isEmpty();
    }

    @Test
    void deviceViewFollowsTheReportedCopyAndStalenessWithoutExposingInternals() {
        Family family = fixtures.family(app.clock().now().minusSeconds(3_600));
        String session = newSession(family.guardianId());
        Instant reportedAt = app.clock().now();
        UUID deviceId = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, reportedAt, reportedAt);
        String code = db.jdbc().queryForObject("select registration_code from core.core_device where id = :id",
                new MapSqlParameterSource("id", deviceId), String.class);

        Result stable = app.get("/api/device").session(session).send();
        assertThat(stable.status()).isEqualTo(200);
        assertThat(WebFixtures.schemaErrors("device-view.schema.json", stable.body())).isEmpty();
        assertThat(nested(stable.json(), "registration")).containsEntry("status", "REGISTERED")
                .containsEntry("codePrefix", code.substring(0, 4));
        assertThat(nested(stable.json(), "network")).containsEntry("state", "STABLE").containsEntry("rttMs", 100)
                .containsEntry("lastReportedAt", ApiResponses.time(reportedAt));
        assertThat(nested(stable.json(), "verdict")).containsEntry("canStart", true).containsEntry("reasonCode", null);
        assertThat(stable.body()).doesNotContain(code, "hash", "Hash", "token", "bootstrap", deviceId.toString());
        Result home = app.get("/api/home").session(session).send();
        assertThat(nested(home.json(), "lock")).as("AC1.4.2 열림").containsEntry("locked", false).containsEntry("reasonCode", null);

        app.clock().advance(Duration.ofSeconds(3));
        assertThat(nested(app.get("/api/device").session(session).send().json(), "network"))
                .as("정각 3초는 아직 신선").containsEntry("state", "STABLE");
        app.clock().advance(Duration.ofMillis(1));
        Result stale = app.get("/api/device").session(session).send();
        assertThat(nested(stale.json(), "network")).containsEntry("state", "UNKNOWN");
        assertThat(nested(stale.json(), "verdict")).containsEntry("canStart", false).containsEntry("reasonCode", "DEVICE_STATUS_UNKNOWN");

        report(deviceId, "DISCONNECTED");
        Result disconnected = app.get("/api/home").session(session).send();
        assertThat(disconnected.json().get("lock")).as("AC1.4.3 잠금 + 사유")
                .isEqualTo(Map.of("locked", true, "reasonCode", "DEVICE_DISCONNECTED"));
        assertThat(nested(disconnected.json(), "device", "network")).containsEntry("state", "DISCONNECTED");
    }

    @Test
    void freeTalkStartIs202AndLiveViewFallsBackToTheCopyOrRelaysRealtime() {
        Family family = readyFamily();
        String session = newSession(family.guardianId());
        String csrf = WebFixtures.csrf(app, session);
        String request = "{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"context\":{\"situationType\":\"FRIEND\"},\"extra\":1}";

        Result started = app.post("/api/activities/free").session(session).csrf(csrf).json(request).send();

        assertThat(started.status()).isEqualTo(202);
        assertThat(WebFixtures.schemaErrors("activity-free-response.schema.json", started.body())).isEmpty();
        assertThat(started.json().get("status")).isEqualTo("REQUESTED");
        UUID sessionId = UUID.fromString(String.valueOf(started.json().get("sessionId")));
        assertThat(realtime.awaitRequests("POST", WebFixtures.RT_START, 1, Duration.ofSeconds(5)))
                .anySatisfy(start -> assertThat(start.json().path("sessionId").asString()).isEqualTo(sessionId.toString()));
        Result repeated = app.post("/api/activities/free").session(session).csrf(csrf).json(request).send();
        assertThat(repeated.status()).isEqualTo(202);
        assertThat(repeated.json()).as("같은 idempotencyKey는 같은 응답").isEqualTo(started.json());

        double fallbacks = app.bean(CoreMetrics.class).count("mujoong.session.view.fallback");
        Result fallback = app.get("/api/sessions/" + sessionId + "?sinceSeq=5").session(session).send();
        assertThat(fallback.status()).isEqualTo(200);
        assertThat(WebFixtures.schemaErrors("live-view.schema.json", fallback.body())).isEmpty();
        Map<String, Object> view = fallback.json();
        assertThat(view).containsEntry("sessionId", sessionId.toString()).containsEntry("activityType", "FREE_TALK")
                .containsEntry("status", "REQUESTED").containsEntry("confirmed", false).containsEntry("statusVersion", 0)
                .containsEntry("requestedAt", ApiResponses.time(app.clock().now()))
                .containsEntry("serverTime", ApiResponses.time(app.clock().now()))
                .containsEntry("stale", false).containsEntry("gaps", List.of()).containsEntry("items", List.of())
                .containsEntry("nextSeq", 5).containsEntry("technicalPause", null);
        assertThat(view.get("observedAt")).as("한 번도 받지 못함").isNull();
        assertThat(app.bean(CoreMetrics.class).count("mujoong.session.view.fallback") - fallbacks).isEqualTo(1.0);
        assertThat(fallback.body()).as("08 입력 원문 없음").doesNotContain("FRIEND", "context");

        realtime.route("GET", WebFixtures.RT_VIEW).respond(StubResponse.json(200, internalView(sessionId)));
        Result relayed = app.get("/api/sessions/" + sessionId).session(session).send();
        assertThat(relayed.status()).isEqualTo(200);
        assertThat(WebFixtures.schemaErrors("live-view.schema.json", relayed.body())).isEmpty();
        assertThat(relayed.json()).containsEntry("nextSeq", 1).containsEntry("stale", false)
                .containsEntry("observedAt", ApiResponses.time(app.clock().now()));
        assertThat((List<?>) relayed.json().get("items")).hasSize(1);

        for (String bad : List.of("-1", "abc", "1.5")) {
            Result invalid = app.get("/api/sessions/" + sessionId + "?sinceSeq=" + bad).session(session).send();
            assertThat(invalid.status()).as("sinceSeq=%s", bad).isEqualTo(400);
            assertThat(invalid.code()).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void endIs202ClosingAndAnotherGuardiansIdsLookExactlyLikeUnknownIds() {
        Family owner = readyFamily();
        String ownerSession = newSession(owner.guardianId());
        String ownerCsrf = WebFixtures.csrf(app, ownerSession);
        UUID sessionId = startFreeTalk(ownerSession, ownerCsrf);
        UUID stranger = db.insertGuardian("ACTIVE");
        String strangerSession = newSession(stranger);
        String strangerCsrf = WebFixtures.csrf(app, strangerSession);
        String endBody = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";
        realtime.clearRequests();

        Result othersView = app.get("/api/sessions/" + sessionId).session(strangerSession).send();
        Result unknownView = app.get("/api/sessions/" + UUID.randomUUID()).session(strangerSession).send();
        Result othersEnd = app.post("/api/sessions/" + sessionId + "/end").session(strangerSession).csrf(strangerCsrf)
                .json(endBody).send();
        Result unknownEnd = app.post("/api/sessions/" + UUID.randomUUID() + "/end").session(strangerSession).csrf(strangerCsrf)
                .json(endBody).send();
        for (Result[] pair : List.of(new Result[] {othersView, unknownView}, new Result[] {othersEnd, unknownEnd})) {
            assertThat(pair[0].status()).isEqualTo(404).isEqualTo(pair[1].status());
            assertThat(withoutTrace(pair[0].json())).as("남의 id와 없는 id는 같은 본문").isEqualTo(withoutTrace(pair[1].json()));
            assertThat(headerNames(pair[0])).isEqualTo(headerNames(pair[1]));
            assertThat(pair[0].body()).doesNotContain(owner.guardianId().toString(), owner.childId().toString());
        }
        assertThat(nested(app.get("/api/device").session(strangerSession).send().json(), "registration"))
                .as("남의 기기는 보이지 않는다").containsEntry("status", "NONE");
        assertThat(realtime.requests("POST", WebFixtures.RT_END)).as("남의 끝내기는 Realtime에 가지 않는다").isEmpty();

        Result ended = app.post("/api/sessions/" + sessionId + "/end").session(ownerSession).csrf(ownerCsrf).json(endBody).send();
        assertThat(ended.status()).isEqualTo(202);
        assertThat(ended.json()).isEqualTo(Map.of("status", "CLOSING"));
        assertThat(WebFixtures.schemaErrors("session-end-response.schema.json", ended.body())).isEmpty();
        assertThat(realtime.requests("POST", WebFixtures.RT_END)).singleElement()
                .satisfies(end -> assertThat(end.schemaErrors()).isEmpty());

        Result malformed = app.post("/api/sessions/" + sessionId + "/end").session(ownerSession).csrf(ownerCsrf)
                .json("{\"requestId\":\"not-a-uuid\"}").send();
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.code()).isEqualTo("VALIDATION_FAILED");
        Result notUuid = app.get("/api/sessions/not-a-uuid").session(ownerSession).send();
        assertThat(notUuid.status()).isEqualTo(404);
    }

    @Test
    void anExpiredGuardianSessionIs401AndLeavesTheRunningActivityAlone() {
        Family family = readyFamily();
        String session = newSession(family.guardianId());
        UUID sessionId = startFreeTalk(session, WebFixtures.csrf(app, session));
        postEvent(sessionStateChanged(sessionId, family, "ACTIVE", 1));
        awaitStatus(session, sessionId, "ACTIVE");
        realtime.clearRequests();

        WebFixtures.deleteSessions(db, family.guardianId());

        Result view = app.get("/api/sessions/" + sessionId).session(session).send();
        assertThat(view.status()).as("AC1.1.9 로그인 유지 만료").isEqualTo(401);
        assertThat(view.code()).isEqualTo("UNAUTHENTICATED");
        Result end = app.post("/api/sessions/" + sessionId + "/end").session(session).json("{}").send();
        assertThat(end.status()).isEqualTo(401);
        assertThat(realtime.requests()).as("BR6.16 세션 만료는 끝내기 · 취소를 보내지 않는다").isEmpty();
        assertThat(copyStatus(sessionId)).isEqualTo("ACTIVE");

        String again = newSession(family.guardianId());
        Result resumed = app.get("/api/sessions/" + sessionId).session(again).send();
        assertThat(resumed.status()).as("재로그인 → 진행 중 세션").isEqualTo(200);
        assertThat(resumed.json().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void deletionRequestedGuardianGets403AndAMissingGuardianRowGets401() {
        UUID deleting = db.insertGuardian("ACTIVE");
        String session = newSession(deleting);
        db.jdbc().update("update core.core_guardian set status = 'DELETION_REQUESTED' where id = :id",
                new MapSqlParameterSource("id", deleting));
        for (String path : List.of("/api/home", "/api/device")) {
            Result result = app.get(path).session(session).send();
            assertThat(result.status()).as(path).isEqualTo(403);
            assertThat(result.code()).isEqualTo("ACCOUNT_DELETION_IN_PROGRESS");
        }

        UUID ghost = UUID.randomUUID();
        Result orphan = app.get("/api/home").session(newSession(ghost)).send();
        assertThat(orphan.status()).as("세션만 남은 계정").isEqualTo(401);
        assertThat(orphan.code()).isEqualTo("UNAUTHENTICATED");
    }

    // ------------------------------------------------------------------ 도움

    private Family readyFamily() {
        Family family = fixtures.family(app.clock().now().minusSeconds(3_600));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, app.clock().now(), app.clock().now());
        return family;
    }

    private String newSession(UUID guardianId) {
        sessionOwners.add(guardianId);
        return app.sessionCookieFor(guardianId);
    }

    private UUID startFreeTalk(String session, String csrf) {
        Result started = app.post("/api/activities/free").session(session).csrf(csrf)
                .json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}").send();
        assertThat(started.status()).as("시작").isEqualTo(202);
        return UUID.fromString(String.valueOf(started.json().get("sessionId")));
    }

    private void report(UUID deviceId, String state) {
        String now = ApiResponses.time(app.clock().now());
        Result result = app.put("/internal/devices/" + deviceId + "/connection").internalToken()
                .json("{\"state\":\"" + state + "\",\"reportedAt\":\"" + now + "\"}").send();
        assertThat(result.status()).isEqualTo(204);
    }

    private String internalView(UUID sessionId) {
        String now = ApiResponses.time(app.clock().now());
        return "{\"sessionId\":\"" + sessionId + "\",\"activityType\":\"FREE_TALK\",\"status\":\"ACTIVE\",\"endReason\":null,"
                + "\"failureCode\":null,\"confirmed\":false,\"requestedAt\":\"" + now + "\",\"startedAt\":\"" + now + "\","
                + "\"endedAt\":null,\"statusVersion\":3,\"serverTime\":\"" + now + "\",\"technicalPause\":null,"
                + "\"silenceWarning\":null,\"practice\":null,\"gaps\":[],\"items\":[{\"seq\":1,\"speaker\":\"AI\","
                + "\"text\":\"안녕\",\"final\":true,\"uncertain\":false,\"startedAt\":\"" + now + "\",\"endedAt\":\"" + now + "\"}],"
                + "\"nextSeq\":1}";
    }

    String sessionStateChanged(UUID sessionId, Family family, String status, int version) {
        String now = ApiResponses.time(app.clock().now());
        return "{\"eventId\":\"" + db.trackInbox(UUID.randomUUID()) + "\",\"eventType\":\"SessionStateChanged\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"" + now + "\",\"aggregateType\":\"ActivitySession\",\"aggregateId\":\"" + sessionId + "\","
                + "\"payload\":{\"sessionId\":\"" + sessionId + "\",\"guardianId\":\"" + family.guardianId() + "\",\"childId\":\""
                + family.childId() + "\",\"activityType\":\"FREE_TALK\",\"status\":\"" + status + "\",\"statusVersion\":" + version
                + ",\"confirmed\":false,\"at\":\"" + now + "\"}}";
    }

    private void postEvent(String envelope) {
        Result result = app.post("/internal/events").internalToken().json(envelope).send();
        assertThat(result.status()).as("이벤트 수신").isEqualTo(200);
    }

    private void awaitStatus(String session, UUID sessionId, String status) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!status.equals(copyStatus(sessionId))) {
            assertThat(System.nanoTime()).as("사본이 %s가 되지 않았다", status).isLessThan(deadline);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(app.get("/api/sessions/" + sessionId).session(session).send().json().get("status")).isEqualTo(status);
    }

    private String copyStatus(UUID sessionId) {
        return db.jdbc().queryForObject("select status from core.core_activity where session_id = :id",
                new MapSqlParameterSource("id", sessionId), String.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> body, String... path) {
        Map<String, Object> current = body;
        for (String key : path) {
            current = (Map<String, Object>) current.get(key);
        }
        return current;
    }

    private static Map<String, Object> withoutTrace(Map<String, Object> envelope) {
        Map<String, Object> copy = new java.util.HashMap<>(envelope);
        copy.remove("traceId");
        return copy;
    }

    private static Set<String> headerNames(Result result) {
        Set<String> names = new java.util.TreeSet<>(result.headers().keySet());
        names.remove(":status");
        return names;
    }
}
