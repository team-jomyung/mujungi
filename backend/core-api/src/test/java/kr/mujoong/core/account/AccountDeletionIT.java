package kr.mujoong.core.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.account.DeletionCommander.Step;
import kr.mujoong.core.account.DeletionRequestRepository.DeletionRequest;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.NewActivity;
import kr.mujoong.core.common.db.DbTestSupport;
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
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 계정 삭제 접수 · 지휘 통합 시험(U2 AccountDeletion · FR1.6 · AC1.6.1 · AC1.6.3 ~ AC1.6.7 · R10 ~ R12 · 편차 (U2-d)(U2-h)).
 *
 * <p>실제 앱(Outbox 발송기 · Inbox 처리기 포함)과 Realtime 계약 대역으로 접수 → 차단 · 로그아웃 → 지휘(세션 끝 · Core 참가자 ·
 * DeletionRequested) → DeletionCompleted 수신 → 마무리(보호자 · 연결 · 세션 삭제, DONE)까지 본다. 지휘 주기는 1시간으로 두어 배경 실행이 끼지 않게
 * 하고 시험이 {@link DeletionCommander#process}를 직접 부르며 가변 Clock을 앞당긴다.
 */
@Tag("u2")
@Tag("db")
class AccountDeletionIT {

    private static final Duration TICK = Duration.ofHours(1);
    private static final Duration SESSION_WAIT = Duration.ofHours(2);

    private static DbTestSupport db;
    private static BusinessFixtures fixtures;
    private static ContractStubServer realtime;
    private static ContractStubServer google;
    private static CoreWebApp app;
    private static OpenApiContracts contracts;

    private final List<String> violations = new ArrayList<>();
    private final List<UUID> sessionsOf = new ArrayList<>();

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
                .property("mujoong.timing.account.deletion-session-wait", "2h")
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
        WebFixtures.deleteSessions(db, sessionsOf.toArray(UUID[]::new));
        assertThat(violations).as("응답이 parent-api.yaml 연산 계약과 맞다").isEmpty();
    }

    @Test
    void acceptingBlocksEveryApiLogsOutEverySessionAndIsIdempotent() {
        Guardian guardian = WebFixtures.guardian(app, db);
        sessionsOf.add(guardian.id());
        String otherDevice = app.sessionCookieFor(guardian.id());

        Result accepted = send("DELETE /api/me", app.call("DELETE", "/api/me").session(guardian.session()).csrf(guardian.csrf()));

        assertThat(accepted.status()).isEqualTo(202);
        UUID requestId = UUID.fromString(String.valueOf(accepted.json().get("deletionRequestId")));
        assertThat(accepted.setCookie("MUJOONG_SESSION").orElseThrow().attribute("Max-Age")).isEqualTo("0");
        assertThat(send("GET /api/me", app.get("/api/me").session(guardian.session())).status()).as("응답 뒤 첫 호출 401")
                .isEqualTo(401);
        assertThat(send("GET /api/me", app.get("/api/me").session(otherDevice)).status()).as("다른 기기도 로그아웃").isEqualTo(401);
        assertThat(single("select status from core.core_guardian where id = :g", guardian.id())).isEqualTo("DELETION_REQUESTED");
        assertThat(single("select status from core.core_data_deletion_request where id = :g", requestId)).isEqualTo("REQUESTED");

        // 접수된 보호자의 세션이 남아 있어도(시험이 새로 만든 세션) /api/me 외에는 403, /api/me는 '삭제 처리 중'만
        String lingering = app.sessionCookieFor(guardian.id());
        assertThat(send("GET /api/child", app.get("/api/child").session(lingering)).code())
                .isEqualTo("ACCOUNT_DELETION_IN_PROGRESS");
        assertThat(send("GET /api/home", app.get("/api/home").session(lingering)).code())
                .isEqualTo("ACCOUNT_DELETION_IN_PROGRESS");
        assertThat(send("GET /api/me", app.get("/api/me").session(lingering)).json())
                .containsEntry("deletionStatus", "IN_PROGRESS").containsEntry("childRegistered", false);
        Result again = send("DELETE /api/me", app.call("DELETE", "/api/me").session(lingering)
                .csrf(WebFixtures.csrf(app, lingering)));
        assertThat(again.status()).isEqualTo(202);
        assertThat(again.json()).as("멱등: 같은 요청 id").containsEntry("deletionRequestId", requestId.toString());
        assertThat(count("select count(*) from core.core_data_deletion_request where guardian_id = :g", guardian.id()))
                .as("요청 행은 하나").isEqualTo(1);
        assertThat(send("GET /api/me", app.get("/api/me").session(lingering)).status()).isEqualTo(401);
    }

    @Test
    void theCommanderDeletesCoreDataAsksRealtimeAndFinishesAfterDeletionCompleted() {
        String subject = "sub-" + UUID.randomUUID();
        U2Fixtures.issueFor(google, subject, app.clock().now());
        Result login = send("POST /api/auth/google", app.post("/api/auth/google").json(U2Fixtures.loginBody("c")));
        UUID guardianId = UUID.fromString(String.valueOf(login.json().get("guardianId")));
        db.trackGuardian(guardianId);
        sessionsOf.add(guardianId);
        String session = login.setCookie("MUJOONG_SESSION").orElseThrow().value();
        String csrf = WebFixtures.csrf(app, session);
        send("PUT /api/consents", app.put("/api/consents").session(session).csrf(csrf)
                .json(consentBody()));
        send("POST /api/child", app.post("/api/child").session(session).csrf(csrf)
                .json("{\"displayName\":\"하늘\",\"age\":8,\"interests\":[\"공룡\"]}"));
        send("PUT /api/child/stop-expressions", app.put("/api/child/stop-expressions").session(session).csrf(csrf)
                .json("{\"items\":[{\"text\":\"이제 그만\"}]}"));
        UUID device = fixtures.registeredDevice(guardianId, ConnectionState.CONNECTED, app.clock().now(), app.clock().now());
        UUID childId = UUID.fromString(single("select id::text from core.core_child where guardian_id = :g", guardianId));
        ActivityCopyRepository copies = app.bean(ActivityCopyRepository.class);
        UUID endedSession = UUID.randomUUID();
        copies.insertRequested(new NewActivity(endedSession, guardianId, childId, device, ActivityType.FREE_TALK,
                UUID.randomUUID().toString(), "hash", app.clock().now()));
        db.jdbc().update("update core.core_activity set status = 'ENDED' where session_id = :g",
                new MapSqlParameterSource("g", endedSession));

        UUID requestId = accept(session, csrf);
        DeletionCommander commander = app.bean(DeletionCommander.class);

        assertThat(commander.process(requestId)).as("진행 세션 없음 → 곧바로 Core 삭제").isEqualTo(Step.REALTIME_REQUESTED);
        for (String table : List.of("core_child", "core_device", "core_activity")) {
            assertThat(count("select count(*) from core." + table + " where guardian_id = :g", guardianId)).as(table).isZero();
        }
        assertThat(count("select count(*) from core.core_consent where actor_guardian_id = :g", guardianId)).isZero();
        assertThat(count("select count(*) from core.core_stop_expression where child_id = :g", childId)).isZero();
        assertThat(count("select count(*) from core.core_social_account where guardian_id = :g", guardianId))
                .as("완료 전에는 같은 구글 계정을 식별한다").isEqualTo(1);
        Optional<RecordedRequest> event = WebFixtures.awaitEvent(realtime, "DeletionRequested", requestId, Duration.ofSeconds(10));
        assertThat(event).as("DeletionRequested가 Realtime에 갔다").isPresent();
        assertThat(event.get().json().path("payload").path("guardianId").asString()).isEqualTo(guardianId.toString());
        assertThat(WebFixtures.awaitEvent(realtime, "DeviceReleased", device, Duration.ofSeconds(10))).isPresent();

        app.clock().advance(TICK);
        assertThat(commander.process(requestId)).as("DeletionCompleted 전에는 기다린다").isEqualTo(Step.WAITING);

        // 삭제 처리 중 같은 구글 계정 로그인: 쿠키 없이 '삭제 처리 중'만(AC1.6.4)
        U2Fixtures.issueFor(google, subject, app.clock().now());
        Result whileDeleting = send("POST /api/auth/google", app.post("/api/auth/google").json(U2Fixtures.loginBody("c2")));
        assertThat(whileDeleting.json()).containsEntry("deletionStatus", "IN_PROGRESS").containsEntry("guardianId",
                guardianId.toString());
        assertThat(whileDeleting.headerValues("Set-Cookie")).isEmpty();

        deliverDeletionCompleted(requestId, guardianId);
        awaitRealtimeCompleted(requestId);
        assertThat(commander.process(requestId)).isEqualTo(Step.DONE);

        DeletionRequest done = app.bean(DeletionRequestRepository.class).findById(requestId).orElseThrow();
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.completedAt()).isNotNull();
        assertThat(count("select count(*) from core.core_guardian where id = :g", guardianId)).isZero();
        assertThat(count("select count(*) from core.core_social_account where guardian_id = :g", guardianId)).isZero();
        assertThat(count("select count(*) from core.core_spring_session where principal_name = :g", guardianId.toString()))
                .isZero();
        assertThat(commander.process(requestId)).as("완료된 요청은 다시 집지 않는다").isEqualTo(Step.SKIPPED);

        // 완료 뒤 같은 구글 계정은 새 보호자로 02부터(AC1.6.5)
        U2Fixtures.issueFor(google, subject, app.clock().now());
        Result fresh = send("POST /api/auth/google", app.post("/api/auth/google").json(U2Fixtures.loginBody("c3")));
        UUID freshId = UUID.fromString(String.valueOf(fresh.json().get("guardianId")));
        db.trackGuardian(freshId);
        sessionsOf.add(freshId);
        assertThat(freshId).isNotEqualTo(guardianId);
        assertThat(fresh.json()).containsEntry("consentStatus", "NOT_GIVEN").containsEntry("deletionStatus", "NONE");
    }

    @Test
    void anInProgressSessionIsEndedWithConsentWithdrawnBeforeDataIsDeleted() {
        Guardian guardian = WebFixtures.guardian(app, db);
        sessionsOf.add(guardian.id());
        UUID childId = db.insertChild(guardian.id());
        UUID device = fixtures.registeredDevice(guardian.id(), ConnectionState.CONNECTED, app.clock().now(), app.clock().now());
        UUID sessionId = UUID.randomUUID();
        app.bean(ActivityCopyRepository.class).insertRequested(new NewActivity(sessionId, guardian.id(), childId, device,
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), "hash", app.clock().now()));
        realtime.clearRequests();

        UUID requestId = accept(guardian.session(), guardian.csrf());
        DeletionCommander commander = app.bean(DeletionCommander.class);

        assertThat(commander.process(requestId)).isEqualTo(Step.SESSION_END_REQUESTED);
        List<RecordedRequest> ends = realtime.requests("POST", WebFixtures.RT_END);
        assertThat(ends).hasSize(1);
        assertThat(ends.get(0).path()).contains(sessionId.toString());
        assertThat(ends.get(0).json().path("reason").asString()).isEqualTo("CONSENT_WITHDRAWN");
        assertThat(count("select count(*) from core.core_child where guardian_id = :g", guardian.id()))
                .as("세션 끝 확인 전에는 지우지 않는다").isEqualTo(1);

        app.clock().advance(TICK);
        assertThat(commander.process(requestId)).as("시한 안 · 아직 진행 중").isEqualTo(Step.WAITING);
        assertThat(realtime.requests("POST", WebFixtures.RT_END)).as("끝내기는 한 번만").hasSize(1);

        app.clock().advance(SESSION_WAIT);
        assertThat(commander.process(requestId)).as("시한이 지나면 데이터 삭제로").isEqualTo(Step.REALTIME_REQUESTED);
        assertThat(count("select count(*) from core.core_activity where guardian_id = :g", guardian.id())).isZero();
        assertThat(count("select count(*) from core.core_child where guardian_id = :g", guardian.id())).isZero();
    }

    @Test
    void aFailingStepIsRecordedAndRetriedOnTheNextRound() {
        Guardian guardian = WebFixtures.guardian(app, db);
        sessionsOf.add(guardian.id());
        UUID requestId = accept(guardian.session(), guardian.csrf());
        // 다른 보호자의 활동 행이 이 보호자의 아이를 가리키게 해 아이 참가자를 외래 키로 실패시킨다
        UUID childId = db.insertChild(guardian.id());
        Guardian other = WebFixtures.guardian(app, db);
        sessionsOf.add(other.id());
        UUID otherDevice = fixtures.registeredDevice(other.id(), ConnectionState.CONNECTED, app.clock().now(), app.clock().now());
        UUID blocking = UUID.randomUUID();
        app.bean(ActivityCopyRepository.class).insertRequested(new NewActivity(blocking, other.id(), childId, otherDevice,
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), "hash", app.clock().now()));
        db.jdbc().update("update core.core_activity set status = 'ENDED' where session_id = :s",
                new MapSqlParameterSource("s", blocking));
        DeletionCommander commander = app.bean(DeletionCommander.class);

        assertThat(commander.process(requestId)).isEqualTo(Step.FAILED);
        DeletionRequest failed = app.bean(DeletionRequestRepository.class).findById(requestId).orElseThrow();
        assertThat(failed.attemptCount()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("DataIntegrityViolationException");
        assertThat(failed.realtimeRequestedAt()).isNull();

        db.jdbc().update("delete from core.core_activity where session_id = :s", new MapSqlParameterSource("s", blocking));
        app.clock().advance(TICK);
        assertThat(commander.process(requestId)).isEqualTo(Step.REALTIME_REQUESTED);
        assertThat(app.bean(DeletionRequestRepository.class).findById(requestId).orElseThrow().lastError()).isNull();
    }

    @Test
    void deletionCompletedForAnUnknownOrNotYetRequestedDeletionChangesNothing() {
        Guardian guardian = WebFixtures.guardian(app, db);
        sessionsOf.add(guardian.id());
        UUID requestId = accept(guardian.session(), guardian.csrf());

        deliverDeletionCompleted(requestId, guardian.id());
        deliverDeletionCompleted(UUID.randomUUID(), guardian.id());
        awaitInboxProcessed(2);

        assertThat(app.bean(DeletionRequestRepository.class).findById(requestId).orElseThrow().realtimeCompletedAt())
                .as("Realtime에 요청하기 전의 완료는 반영하지 않는다").isNull();
        List<Step> steps = app.bean(DeletionCommander.class).runOnce();
        assertThat(steps).contains(Step.REALTIME_REQUESTED);
    }

    // ------------------------------------------------------------------ 도움

    private UUID accept(String session, String csrf) {
        Result accepted = send("DELETE /api/me", app.call("DELETE", "/api/me").session(session).csrf(csrf));
        assertThat(accepted.status()).isEqualTo(202);
        return UUID.fromString(String.valueOf(accepted.json().get("deletionRequestId")));
    }

    private final List<UUID> deliveredEvents = new ArrayList<>();

    private void deliverDeletionCompleted(UUID requestId, UUID guardianId) {
        JsonCodec json = JsonCodec.shared();
        UUID eventId = db.trackInbox(UUID.randomUUID());
        deliveredEvents.add(eventId);
        String body = json.encodeEventEnvelope(json.envelopeFor(eventId,
                new DeletionCompleted(requestId, guardianId, app.clock().now(), new DeletionCompleted.Counts(1, 2, 0, 0)),
                app.clock().now(), null));
        Result result = app.post("/internal/events").internalToken().json(body).send();
        assertThat(result.status()).isEqualTo(200);
    }

    private void awaitRealtimeCompleted(UUID requestId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (app.bean(DeletionRequestRepository.class).findById(requestId).orElseThrow().realtimeCompletedAt() != null) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("DeletionCompleted가 10초 안에 반영되지 않았다");
    }

    private void awaitInboxProcessed(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer processed = db.jdbc().queryForObject(
                    "select count(*) from core.core_inbox_event where event_id = any(:ids) and status = 'PROCESSED'",
                    new MapSqlParameterSource("ids", deliveredEvents.toArray(UUID[]::new)), Integer.class);
            if (processed != null && processed >= expected) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Inbox 처리기가 10초 안에 끝나지 않았다");
    }

    private Result send(String operation, Call call) {
        Result result = call.send();
        violations.addAll(contracts.violations(operation, result));
        return result;
    }

    private static String consentBody() {
        return "{\"policyVersion\":\"1.0\",\"items\":[{\"type\":\"VOICE_AND_TEXT\",\"agreed\":true},"
                + "{\"type\":\"GUARDIAN_VIEW\",\"agreed\":true},{\"type\":\"REPORT_SHARING\",\"agreed\":true},"
                + "{\"type\":\"RAW_AUDIO\",\"agreed\":true}]}";
    }

    private static String single(String sql, Object value) {
        return db.jdbc().queryForObject(sql, new MapSqlParameterSource("g", value), String.class);
    }

    private static int count(String sql, Object value) {
        Integer counted = db.jdbc().queryForObject(sql, new MapSqlParameterSource("g", value), Integer.class);
        return counted == null ? -1 : counted;
    }
}
