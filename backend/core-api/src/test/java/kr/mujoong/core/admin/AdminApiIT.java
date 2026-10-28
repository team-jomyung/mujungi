package kr.mujoong.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.web.support.AppOutputCapture;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * JIRA-280 관리자 조회 API 끝-끝(Core + Testcontainers PostgreSQL, Realtime은 계약 스텁).
 *
 * <p>다른 시험 자료와 섞이지 않게 활동은 2019-05-14(Asia/Seoul)에 두고, 집계는 이 시험 보호자로 좁혀 본다. 모든 응답은 계약 스키마로 대조한다.
 */
@Tag("admin")
@Tag("db")
class AdminApiIT {

    private static final String DAY = "2019-05-14";
    private static final Instant NOON = Instant.parse("2019-05-14T03:00:00Z");
    private static final String FACTS = "/internal/sessions/admin-facts";
    private static final String USAGE = "/internal/sessions/admin-usage";
    private static final String CHILD_LINE = "공룡 보러 가고 싶어";

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static OpenApiContracts contracts;
    private static AppOutputCapture output;

    private final List<String> violations = new ArrayList<>();
    private UUID admin;
    private UUID guardian;
    private UUID child;
    private UUID device;
    private UUID freeTalk;
    private UUID practice;
    private UUID restartFailed;
    private UUID neverStarted;
    private UUID declined;
    private UUID sceneRun1;
    private UUID sceneRun2;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        contracts = OpenApiContracts.load();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).start();
        output = AppOutputCapture.attach();
    }

    @AfterAll
    static void stop() {
        if (output != null) {
            output.close();
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
    void family() {
        admin = db.insertGuardian("ACTIVE");
        adminRow(admin, "ACTIVE");
        guardian = db.insertGuardian("ACTIVE");
        child = db.insertChild(guardian);
        device = device(guardian);
        freeTalk = activity("FREE_TALK", "ENDED", "CHILD_STOP", null, NOON, NOON.plusSeconds(300));
        record(freeTalk, "FREE_TALK", "ENDED", "CHILD_STOP", transcript());
        practice = activity("PRACTICE", "ENDED_UNCONFIRMED", "UNCONFIRMED", null, NOON.plusSeconds(600), NOON.plusSeconds(1200));
        record(practice, "PRACTICE", "ENDED_UNCONFIRMED", "UNCONFIRMED", "[]");
        sceneRun1 = UUID.randomUUID();
        sceneRun2 = UUID.randomUUID();
        scenes(practice);
        restartFailed = activity("FREE_TALK", "FAILED", null, "SERVER_RESTART", NOON.plusSeconds(1800), NOON.plusSeconds(1900));
        record(restartFailed, "FREE_TALK", "FAILED", null, transcript());
        neverStarted = activity("PRACTICE", "FAILED", null, "DEVICE_NO_ACK", null, null);
        declined = activity("FREE_TALK", "DECLINED", "DECLINED", null, null, NOON.plusSeconds(2400));
        record(declined, "FREE_TALK", "DECLINED", "DECLINED", "[]");
        coreUsage(practice, guardian, "SCENARIO_GENERATION", 10_000L, 2_000L);

        realtime.route("POST", FACTS).requestSchema("internal-admin-facts-request.schema.json")
                .respond(StubResponse.json(200, factsBody()));
        realtime.route("POST", USAGE).requestSchema("internal-admin-usage-request.schema.json")
                .respond(StubResponse.json(200, usageBody()));
        realtime.clearRequests();
    }

    @AfterEach
    void cleanUp() {
        db.cleanUp();
        assertThat(violations).as("계약 위반").isEmpty();
        assertThat(realtime.violations()).as("Realtime 내부 요청 계약 위반").isEmpty();
    }

    @Test
    void everyAdminOperationRejectsANonAdminAndARevokedAdminWith403() {
        UUID revoked = db.insertGuardian("ACTIVE");
        adminRow(revoked, "REVOKED");
        String range = "?from=" + DAY + "&to=" + DAY;
        Map<String, String> operations = Map.of(
                "GET /api/admin/me", "/api/admin/me",
                "GET /api/admin/overview", "/api/admin/overview" + range,
                "GET /api/admin/activities", "/api/admin/activities" + range,
                "GET /api/admin/activities/{sessionId}", "/api/admin/activities/" + freeTalk,
                "GET /api/admin/guardians", "/api/admin/guardians" + range,
                "GET /api/admin/quality", "/api/admin/quality" + range,
                "GET /api/admin/costs", "/api/admin/costs" + range,
                "GET /api/admin/costs/{costId}", "/api/admin/costs/" + practice);
        for (UUID who : List.of(guardian, revoked)) {
            String session = WebFixtures.guardianFor(app, who).session();
            operations.forEach((key, path) -> {
                Result result = checked(key, app.get(path).session(session).send());
                assertThat(result.status()).as(key).isEqualTo(403);
                assertThat(result.json()).as(key).containsEntry("code", ErrorCode.ADMIN_FORBIDDEN.name());
            });
        }
        assertThat(realtime.requests()).as("권한 없는 요청은 Realtime까지 가지 않는다").isEmpty();
    }

    @Test
    void anActiveAdminIsIdentified() {
        Result result = checked("GET /api/admin/me", adminGet("/api/admin/me"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.json()).containsEntry("role", "ADMIN");
    }

    @Test
    void overviewCountsStartedActivitiesAndKeepsUnconfirmedEndsApartFromNormalEnds() {
        Map<String, Object> body = ok("GET /api/admin/overview", "/api/admin/overview" + range() + "&guardianId=" + guardian);

        assertThat(body).containsEntry("activityCount", 3).containsEntry("freeTalkCount", 2).containsEntry("practiceCount", 1)
                .containsEntry("activeChildren", 1).containsEntry("childTurns", 4).containsEntry("aiTurns", 2);
        assertThat(endReasons(body)).containsEntry("ENDED", 1).containsEntry("ENDED_UNCONFIRMED", 1)
                .containsEntry("INTERRUPTED", 1).containsEntry("DECLINED", 1).containsEntry("NOT_STARTED", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> averages = (Map<String, Object>) body.get("averageSeconds");
        // 300초 · 600초 · 100초의 평균. 시작 실패 · 참여 거절은 빠진다
        assertThat(((Number) averages.get("all")).doubleValue()).isCloseTo(1000.0 / 3, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(averages.get("practice")).isEqualTo(600.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) body.get("daily");
        assertThat(daily).singleElement().satisfies(day -> assertThat(day).containsEntry("date", DAY)
                .containsEntry("freeTalk", 2).containsEntry("practice", 1));
    }

    @Test
    void activityStatusSeparatesStartFailuresFromInterruptionsAfterStart() {
        Map<String, Object> all = ok("GET /api/admin/activities", "/api/admin/activities" + range() + "&guardianId=" + guardian);
        Map<String, Map<String, Object>> byId = byId(all);

        assertThat(all).containsEntry("total", 5).containsEntry("page", 0).containsEntry("pageSize", 8);
        assertThat(byId.get(freeTalk.toString())).containsEntry("status", "COMPLETED").containsEntry("endCategory", "ENDED");
        assertThat(byId.get(practice.toString())).containsEntry("status", "COMPLETED")
                .containsEntry("endCategory", "ENDED_UNCONFIRMED").containsEntry("endReason", "종료 확인 안 됨");
        assertThat(byId.get(restartFailed.toString())).as("발화가 남은 서버 재시작 실패는 진행 중 중단")
                .containsEntry("status", "INTERRUPTED").containsEntry("endReason", "서버 재시작으로 중단");
        assertThat(byId.get(neverStarted.toString())).containsEntry("status", "FAILED_TO_START")
                .containsEntry("endCategory", "NOT_STARTED").containsEntry("durationSeconds", null)
                .containsEntry("childTurns", null);
        assertThat(byId.get(declined.toString())).containsEntry("status", "FAILED_TO_START").containsEntry("endCategory", "DECLINED");

        Map<String, Object> failed = ok("GET /api/admin/activities",
                "/api/admin/activities" + range() + "&guardianId=" + guardian + "&status=FAILED_TO_START");
        assertThat(failed).containsEntry("total", 2);
        Map<String, Object> second = ok("GET /api/admin/activities",
                "/api/admin/activities" + range() + "&guardianId=" + guardian + "&page=1&pageSize=2");
        // 시작 전 두 활동(요청 시각 같음, id 순) → 서버 재시작 중단 → 연습 → 자유대화
        assertThat(ids(second)).as("활동 시각 내림차순 두 번째 쪽").containsExactly(restartFailed.toString(), practice.toString());
    }

    @Test
    void activityDetailShowsTheTranscriptAndEveryViewLeavesOneAuditLine() {
        int before = audits().size();

        Map<String, Object> first = ok("GET /api/admin/activities/{sessionId}", "/api/admin/activities/" + freeTalk);
        ok("GET /api/admin/activities/{sessionId}", "/api/admin/activities/" + freeTalk);

        assertThat(first).containsEntry("transcriptAvailable", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) first.get("transcript");
        assertThat(lines).extracting(line -> line.get("speaker")).containsExactly("CHILD", "AI", "CHILD");
        assertThat(lines.getFirst()).containsEntry("text", CHILD_LINE);
        List<String> audits = audits();
        assertThat(audits).as("다시 열어도 한 줄씩 남는다(병합 없음)").hasSize(before + 2);
        assertThat(audits).allMatch(line -> line.contains("\"viewerHash\"") && line.contains("core-api/AdminQuery"))
                .noneMatch(line -> line.contains(CHILD_LINE) || line.contains(freeTalk.toString()) || line.contains(admin.toString()));
    }

    @Test
    void declinedHasNoTranscriptAndAccountsBeingDeletedAreHidden() {
        Map<String, Object> body = ok("GET /api/admin/activities/{sessionId}", "/api/admin/activities/" + declined);
        assertThat(body).containsEntry("transcriptAvailable", false).containsEntry("transcript", List.of());

        db.jdbc().update("update core.core_guardian set status = 'DELETION_REQUESTED' where id = :id",
                ClockParams.uuid(new MapSqlParameterSource(), "id", guardian));
        Result hidden = checked("GET /api/admin/activities/{sessionId}", adminGet("/api/admin/activities/" + freeTalk));
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(ok("GET /api/admin/activities", "/api/admin/activities" + range() + "&guardianId=" + guardian))
                .containsEntry("total", 0);
    }

    @Test
    void practiceScenesCountHelpOncePerSceneAndReadDeclineFromTheSceneFact() {
        Map<String, Object> body = ok("GET /api/admin/quality", "/api/admin/quality" + range() + "&guardianId=" + guardian);

        assertThat(body).containsEntry("startedPractices", 1).containsEntry("helpExecutionCount", 1)
                .containsEntry("focusExamples", 1).containsEntry("retryCount", 1).containsEntry("recordsTotal", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> acceptance = (Map<String, Object>) body.get("acceptance");
        // 수락은 역할 전환 · 초점 도움 두 행에 있지만 장면당 한 번만 센다. 거절은 도움 행 없이 사실로만 남는다
        assertThat(acceptance).containsEntry("accepted", 1).containsEntry("declined", 1).containsEntry("unknown", 0)
                .containsEntry("unanswered", null).containsEntry("rate", 0.5);

        Map<String, Object> detail = ok("GET /api/admin/activities/{sessionId}", "/api/admin/activities/" + practice);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) detail.get("scenes");
        assertThat(scenes).hasSize(2);
        assertThat(scenes.get(0)).containsEntry("judgment", "SUCCESS").containsEntry("helpOffer", "OFFERED")
                .containsEntry("helpResponse", "ACCEPT").containsEntry("retryCount", 1).containsEntry("kind", "BASE");
        assertThat(scenes.get(1)).containsEntry("judgment", "FAILURE").containsEntry("helpResponse", "DECLINE")
                .containsEntry("helpRecords", List.of());
        assertThat(realtime.requests("POST", FACTS)).allSatisfy(request -> assertThat(request.json().toString())
                .contains(practice.toString()).doesNotContain(freeTalk.toString()));
    }

    @Test
    void costsAreUsdStringsAndTheRealtimeOutageIsAnErrorNotZero() {
        // 서버 재시작 실패 활동의 결과 문장 호출: 입력 · 출력은 알지만 캐시 읽기를 모름
        coreUsage(restartFailed, guardian, "RESULT_TEXT", 2_000L, 400L, null);
        Map<String, Object> body = ok("GET /api/admin/costs", "/api/admin/costs" + range() + "&guardianId=" + guardian);
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) body.get("activities");
        Map<String, Map<String, Object>> items = byId(page);
        // 준비 (10,000 × 0.10 + 2,000 × 0.50) ÷ 1,000,000 = 0.002 + 판정 (4,200 × 0.10 + 1,200 × 0.01) ÷ 1,000,000
        assertThat(items.get(practice.toString())).containsEntry("estimatedUsd", "0.002432").containsEntry("category", "ACTIVITY")
                .as("입력만 아는 판정 호출의 100토큰도 표시하되 금액에는 넣지 않는다").containsEntry("inputTokens", 15_500)
                .containsEntry("status", "PARTIAL");
        // 음성 300초 = 300 ÷ 60 × 0.05
        assertThat(items.get(freeTalk.toString())).containsEntry("estimatedUsd", "0.25").containsEntry("status", "COMPLETE");
        assertThat(body.get("otherCosts")).as("필터가 있으면 활동 외 항목은 싣지 않는다").isEqualTo(List.of());
        // (2,000 × 0.10 + 400 × 0.50) ÷ 1,000,000: 모르는 캐시 읽기는 0으로 계산하되 완결로 보이지 않는다
        assertThat(items.get(restartFailed.toString())).containsEntry("estimatedUsd", "0.0004").containsEntry("status", "PARTIAL");
        assertThat(page).as("사용량이 없는 기간 안 활동 2건도 목록에 든다").containsEntry("total", 5);
        for (UUID silent : List.of(neverStarted, declined)) {
            assertThat(items.get(silent.toString())).containsEntry("status", "NOT_COLLECTED").containsEntry("category", "ACTIVITY")
                    .containsEntry("estimatedUsd", null).containsEntry("inputTokens", null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) body.get("totals");
        assertThat(totals).as("미수집 활동은 합계를 바꾸지 않는다").containsEntry("activityUsd", "0.252832");

        Map<String, Object> detail = ok("GET /api/admin/costs/{costId}", "/api/admin/costs/" + practice);
        assertThat(detail).containsEntry("estimatedUsd", "0.002432");
        List<Object> phases = ((List<?>) detail.get("phases")).stream().map(phase -> ((Map<?, ?>) phase).get("phase"))
                .map(Object.class::cast).toList();
        assertThat(phases).containsExactly("PREPARATION", "JUDGMENT");

        realtime.route("POST", USAGE).respond(StubResponse.error(500, ErrorCode.INTERNAL_ERROR));
        Result down = checked("GET /api/admin/costs", adminGet("/api/admin/costs" + range()));
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.json()).containsEntry("code", ErrorCode.REALTIME_UNAVAILABLE.name());
    }

    @Test
    void guardiansListRegisteredDevicesWithTheChild() {
        Map<String, Object> body = ok("GET /api/admin/guardians", "/api/admin/guardians" + range() + "&guardianId=" + guardian);

        assertThat(body).containsEntry("total", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = ((List<Map<String, Object>>) body.get("items")).getFirst();
        assertThat(entry.get("id")).isEqualTo(guardian.toString());
        assertThat(entry.get("activityCount")).isEqualTo(5);
        assertThat(entry.get("name")).isEqualTo("보호자 " + guardian.toString().substring(0, 6));
        @SuppressWarnings("unchecked")
        Map<String, Object> deviceEntry = ((List<Map<String, Object>>) entry.get("devices")).getFirst();
        assertThat(deviceEntry).containsEntry("id", device.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) deviceEntry.get("children");
        assertThat(children).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("id", child.toString()).containsEntry("activityCount", 5));
    }

    @Test
    void deviceFilterSearchAndGuardianCountsFollowTheDeviceAndTheStatusClassification() {
        UUID oldDevice = releasedDevice(guardian);
        UUID onOldDevice = activityOn(oldDevice, "FREE_TALK", "ENDED", "GUARDIAN_STOP", NOON.plusSeconds(60), NOON.plusSeconds(120));
        String base = "/api/admin/activities" + range() + "&guardianId=" + guardian;

        assertThat(ids(ok("GET /api/admin/activities", base + "&deviceId=" + oldDevice))).containsExactly(onOldDevice.toString());
        assertThat(ok("GET /api/admin/activities", base + "&deviceId=" + device)).containsEntry("total", 5);
        assertThat(ids(ok("GET /api/admin/activities", "/api/admin/activities" + range() + "&query="
                + oldDevice.toString().substring(0, 13)))).as("기기 id 앞부분 검색").containsExactly(onOldDevice.toString());

        Map<String, Object> all = guardianEntry("");
        assertThat(all.get("activityCount")).as("보호자 수는 해제된 기기의 활동까지").isEqualTo(6);
        assertThat(childCountOnRegisteredDevice(all)).as("기기 아래 아이 수는 그 기기의 활동만").isEqualTo(5);
        Map<String, Object> completed = guardianEntry("&status=COMPLETED");
        assertThat(completed.get("activityCount")).as("활동 목록과 같은 상태 분류").isEqualTo(3);
        assertThat(childCountOnRegisteredDevice(completed)).isEqualTo(2);
        assertThat(ok("GET /api/admin/activities", base + "&status=COMPLETED")).containsEntry("total", 3);
    }

    @Test
    void malformedFiltersAreValidationFailures() {
        Result tooLong = checked("GET /api/admin/activities", adminGet("/api/admin/activities?from=2018-01-01&to=2019-05-14"));
        Result noDates = checked("GET /api/admin/overview", adminGet("/api/admin/overview"));

        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(noDates.status()).isEqualTo(400);
        assertThat(checked("GET /api/admin/costs/{costId}", adminGet("/api/admin/costs/" + UUID.randomUUID())).status())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ 도움

    private static String range() {
        return "?from=" + DAY + "&to=" + DAY + "&timezone=Asia/Seoul";
    }

    private Result adminGet(String path) {
        return app.get(path).session(WebFixtures.guardianFor(app, admin).session()).send();
    }

    private Map<String, Object> ok(String key, String path) {
        Result result = checked(key, adminGet(path));
        assertThat(result.status()).as(key + " " + result.body()).isEqualTo(200);
        return result.json();
    }

    private Result checked(String key, Result result) {
        violations.addAll(contracts.violations(key, result));
        return result;
    }

    private static List<String> audits() {
        return output.lines().stream().filter(line -> line.contains("ADMIN_RECORD_VIEWED")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byId(Map<String, Object> page) {
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) page.get("items")) {
            byId.put((String) item.get("id"), item);
        }
        return byId;
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream().map(item -> (String) item.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> endReasons(Map<String, Object> body) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) body.get("endReasons")) {
            counts.put((String) row.get("code"), ((Number) row.get("count")).intValue());
        }
        return counts;
    }

    private static String transcript() {
        return """
                [{"seq":1,"speaker":"CHILD","text":"%s","uncertain":false,"startedAt":"2019-05-14T03:00:10Z","endedAt":"2019-05-14T03:00:12Z"},
                 {"seq":2,"speaker":"AI","text":"좋아, 어떤 공룡?","uncertain":false,"startedAt":"2019-05-14T03:00:13Z","endedAt":"2019-05-14T03:00:15Z"},
                 {"seq":3,"speaker":"CHILD","text":"티라노","uncertain":false,"startedAt":"2019-05-14T03:00:16Z","endedAt":"2019-05-14T03:00:17Z"}]
                """.formatted(CHILD_LINE);
    }

    private String factsBody() {
        return """
                {"sessions":[{"sessionId":"%s","sceneRuns":[
                  {"sceneRunId":"%s","sceneKind":"BASE","liveTiming":"AFTER_HELP","retryCount":1,"reexplainCount":0,
                   "facts":["HELP_ACCEPTED","COACH_SWITCH","FOCUS_HELP"],
                   "helps":[{"helpId":"%s","kind":"ROLE_SWITCH","consentAnswer":"ACCEPT","exampleGiven":false},
                            {"helpId":"%s","kind":"FOCUS_HELP","consentAnswer":"ACCEPT","exampleGiven":true}]},
                  {"sceneRunId":"%s","sceneKind":"BASE","liveTiming":"NONE","retryCount":0,"reexplainCount":0,
                   "facts":["HELP_DECLINED"],"helps":[]}]}]}
                """.formatted(practice, sceneRun1, UUID.randomUUID(), UUID.randomUUID(), sceneRun2);
    }

    private String usageBody() {
        return """
                {"usage":[
                  {"sessionId":"%s","source":"VOICE","model":"gpt-live-1","calls":1,"measuredCalls":1,"knownCalls":1,
                   "completeCalls":1,"cacheUnknownCalls":0,"voiceSeconds":300.0,"lastObservedAt":"2019-05-14T03:05:00Z"},
                  {"sessionId":"%s","source":"LUNA","model":"gpt-6-luna","calls":4,"measuredCalls":3,"knownCalls":4,
                   "completeCalls":3,"cacheUnknownCalls":0,"inputTokens":5500,"outputTokens":60,"cachedInputTokens":1200,"reasoningTokens":0,
                   "measuredInputTokens":5400,"measuredOutputTokens":60,"measuredCachedInputTokens":1200,
                   "lastObservedAt":"2019-05-14T03:15:00Z"}]}
                """.formatted(freeTalk, practice);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> guardianEntry(String extra) {
        Map<String, Object> body = ok("GET /api/admin/guardians", "/api/admin/guardians" + range() + "&guardianId=" + guardian + extra);
        return ((List<Map<String, Object>>) body.get("items")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static int childCountOnRegisteredDevice(Map<String, Object> guardianEntry) {
        Map<String, Object> registered = ((List<Map<String, Object>>) guardianEntry.get("devices")).getFirst();
        Map<String, Object> kid = ((List<Map<String, Object>>) registered.get("children")).getFirst();
        return ((Number) kid.get("activityCount")).intValue();
    }

    private UUID releasedDevice(UUID owner) {
        UUID id = UUID.randomUUID();
        db.jdbc().update("""
                insert into core.core_device (id, guardian_id, registration_code, code_issued_at, code_expires_at,
                    bootstrap_secret_hash, registration_status, registered_at, released_at, release_reason, created_at, updated_at)
                values (:id, :guardian, :code, :t, :t, :hash, 'RELEASED', :t, :t, 'REREGISTERED', :t, :t)
                """, params().addValue("id", id).addValue("guardian", owner).addValue("code", DbTestSupport.randomCode())
                .addValue("hash", DbTestSupport.randomHash()));
        return id;
    }

    private void adminRow(UUID id, String status) {
        db.jdbc().update("""
                insert into core.core_admin_account (guardian_id, status, created_at, updated_at) values (:id, :status, :t, :t)
                """, params().addValue("id", id).addValue("status", status));
    }

    private UUID device(UUID owner) {
        UUID id = UUID.randomUUID();
        db.jdbc().update("""
                insert into core.core_device (id, guardian_id, registration_code, code_issued_at, code_expires_at,
                    bootstrap_secret_hash, registration_status, registered_at, created_at, updated_at)
                values (:id, :guardian, :code, :t, :t, :hash, 'REGISTERED', :t, :t, :t)
                """, params().addValue("id", id).addValue("guardian", owner).addValue("code", DbTestSupport.randomCode())
                .addValue("hash", DbTestSupport.randomHash()));
        return id;
    }

    private UUID activity(String type, String status, String endReason, String failureCode, Instant startedAt, Instant endedAt) {
        return activityOn(device, type, status, endReason, failureCode, startedAt, endedAt);
    }

    private UUID activityOn(UUID onDevice, String type, String status, String endReason, Instant startedAt, Instant endedAt) {
        return activityOn(onDevice, type, status, endReason, null, startedAt, endedAt);
    }

    private UUID activityOn(UUID onDevice, String type, String status, String endReason, String failureCode, Instant startedAt,
            Instant endedAt) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource p = params().addValue("id", id).addValue("guardian", guardian).addValue("child", child)
                .addValue("device", onDevice).addValue("type", type).addValue("status", status)
                .addValue("key", UUID.randomUUID().toString()).addValue("hash", DbTestSupport.randomHash());
        ClockParams.text(p, "reason", endReason);
        ClockParams.text(p, "failure", failureCode);
        ClockParams.instant(p, "requested", startedAt == null ? NOON.plusSeconds(3000) : startedAt.minusSeconds(5));
        ClockParams.instant(p, "started", startedAt);
        ClockParams.instant(p, "ended", endedAt);
        db.jdbc().update("""
                insert into core.core_activity (session_id, guardian_id, child_id, device_id, activity_type, status, end_reason,
                    failure_code, start_idempotency_key, request_hash, requested_at, started_at, ended_at, created_at, updated_at)
                values (:id, :guardian, :child, :device, :type, :status, :reason, :failure, :key, :hash, :requested, :started,
                    :ended, :t, :t)
                """, p);
        return id;
    }

    private void record(UUID session, String type, String finalStatus, String endReason, String transcript) {
        MapSqlParameterSource p = params().addValue("session", session).addValue("guardian", guardian).addValue("child", child)
                .addValue("type", type).addValue("status", finalStatus).addValue("transcript", transcript)
                .addValue("snapshot", UUID.randomUUID()).addValue("hash", "sha256:" + DbTestSupport.randomHash());
        ClockParams.text(p, "reason", endReason);
        db.jdbc().update("""
                insert into core.core_activity_record (session_id, guardian_id, child_id, activity_type, final_status, end_reason,
                    confirmed, started_at, ended_at, listed_at, transcript_json, gaps_json, audio_refs_json, source_snapshot_id,
                    snapshot_hash, recorded_at, created_at, updated_at)
                values (:session, :guardian, :child, :type, :status, :reason, false, null, null, :t, cast(:transcript as jsonb),
                    '[]'::jsonb, '[]'::jsonb, :snapshot, :hash, :t, :t, :t)
                """, p);
    }

    private void scenes(UUID session) {
        UUID job = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        MapSqlParameterSource p = params().addValue("job", job).addValue("result", result).addValue("session", session)
                .addValue("guardian", guardian).addValue("child", child).addValue("key", "admin-it-" + job)
                .addValue("run1", sceneRun1).addValue("run2", sceneRun2).addValue("goal", UUID.randomUUID());
        db.jdbc().update("""
                insert into core.core_result_job (id, job_type, subject_id, guardian_id, idempotency_key, trigger_type, status,
                    attempt_count, next_attempt_at, created_at, updated_at, finished_at)
                values (:job, 'SESSION_RESULT', :session, :guardian, :key, 'ACTIVITY_RECORDED', 'DONE', 1, :t, :t, :t, :t)
                """, p);
        db.jdbc().update("""
                insert into core.core_activity_result (id, session_id, version, job_id, result_level, sentences_json, help_json,
                    tips_json, matching_log_json, pending_candidates_json, created_at)
                values (:result, :session, 1, :job, 'FULL', '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, '[]'::jsonb, :t)
                """, p);
        db.jdbc().update("""
                insert into core.core_session_result_state (session_id, guardian_id, child_id, activity_type, eligibility,
                    current_job_id, job_status, current_result_id, run_ordinal, created_at, updated_at)
                values (:session, :guardian, :child, 'PRACTICE', 'ELIGIBLE', :job, 'DONE', :result, 1, :t, :t)
                """, p);
        db.jdbc().update("""
                insert into core.core_scene_judgment (id, result_id, session_id, goal_selection_id, goal_order, scene_no,
                    exposure_type, value, timing, live_label, differs_from_live, not_done_reason, scene_chip, created_at,
                    scene_run_id)
                values (gen_random_uuid(), :result, :session, :goal, 1, 1, 'ORIGINAL', 'functional', 'AFTER_HELP', 'functional',
                        false, null, 'DONE_AFTER_HELP', :t, :run1),
                       (gen_random_uuid(), :result, :session, :goal, 1, 2, 'ORIGINAL', 'none', null, 'none', false, null,
                        'NEXT_TIME', :t, :run2)
                """, p);
    }

    private void coreUsage(UUID session, UUID owner, String purpose, long input, long outputTokens) {
        coreUsage(session, owner, purpose, input, outputTokens, 0L);
    }

    private void coreUsage(UUID session, UUID owner, String purpose, long input, long outputTokens, Long cached) {
        db.jdbc().update("""
                insert into core.core_ai_usage (id, call_id, attempt_no, purpose, guardian_id, session_id, prompt_version, model,
                    outcome, input_tokens, output_tokens, cached_input_tokens, cache_write_tokens, reasoning_tokens,
                    capture_status, observed_at, created_at)
                values (:id, :call, 1, :purpose, :guardian, :session, 'admin-it', 'gpt-6-luna', 'OK', :input, :output, :cached, 0, 0,
                        'COMPLETE', :observed, :t)
                """, params().addValue("id", UUID.randomUUID()).addValue("call", UUID.randomUUID()).addValue("purpose", purpose)
                .addValue("guardian", owner).addValue("session", session).addValue("input", input).addValue("output", outputTokens)
                .addValue("cached", cached, java.sql.Types.BIGINT)
                .addValue("observed", ClockParams.timestamptz(NOON.plusSeconds(500))));
    }

    private static MapSqlParameterSource params() {
        return ClockParams.at(NOON).addValue("t", ClockParams.timestamptz(NOON));
    }
}
