package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.ActivityCopyRepository.StateChange;
import kr.mujoong.core.activity.SessionViewService.LiveView;
import kr.mujoong.core.activity.StartActivityService.Context;
import kr.mujoong.core.activity.StartActivityService.StartRequest;
import kr.mujoong.core.activity.StartActivityService.Started;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 시작 · 끝내기 통합 (U1 3.5 Step 28 · BR6.1 ~ BR6.9 · BR6.12 ① · BR6.16 · AC3.1.1 · AC3.1.6 · AC3.1.8 · AC3.1.9 · AC3.3.1 · AC3.3.8 ·
 * RD-14 · NFR4.12). 실제 DB(core_app)와 계약 대역 Realtime(요청을 contracts/schemas로 검사)으로 돈다. 시험 프로필 시한: 시작 300ms ·
 * 끝내기 200ms · 조회 100ms · 차단기 열림 500ms.
 */
@Tag("skeleton")
@Tag("db")
class ActivityStartEndIT {

    private static final String START = "/internal/sessions";
    private static final String END = "/internal/sessions/{sessionId}/end";
    private static final String VIEW = "/internal/sessions/{sessionId}/view";
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static MujoongPostgres postgres;

    private final MutableClock clock = new MutableClock(Instant.parse("2001-04-01T00:00:00Z"));
    private final String token = TestSecrets.internalServiceToken();
    private DbTestSupport db;
    private BusinessFixtures fixtures;
    private ContractStubServer realtime;
    private ApplicationContextRunner runner;

    @BeforeAll
    static void database() {
        postgres = MujoongPostgres.sharedOrAbort();
        BusinessFixtures.applyReferenceSeed(postgres);
    }

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        fixtures = new BusinessFixtures(db);
        realtime = ContractStubServer.start().requireInternalToken(token);
        realtime.route("POST", START).requestSchema("internal-session-start-request.schema.json")
                .respond(StubResponse.json(202, "{\"status\":\"PREPARING\"}"));
        realtime.route("POST", END).requestSchema("internal-session-end-request.schema.json")
                .respond(StubResponse.json(202, "{}"));
        realtime.route("GET", VIEW).respond(StubResponse.error(404, ErrorCode.NOT_FOUND));
        runner = CoreBusinessContext.runner(postgres, clock, realtime.baseUrl(), token);
    }

    @AfterEach
    void tearDown() {
        realtime.close();
        db.close();
    }

    @Test
    void emptyContextStartCreatesRequestedCopyAndSendsOneValidPackage() {
        Family family = readyFamily();
        runner.run(context -> {
            Started started = start(context).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));

            assertThat(started.status()).isEqualTo(SessionStatus.REQUESTED);
            ActivityCopy copy = activities(context).findById(started.sessionId()).orElseThrow();
            assertThat(copy.status()).isEqualTo(SessionStatus.REQUESTED);
            assertThat(copy.statusVersion()).isZero();
            List<RecordedRequest> starts = realtime.requests("POST", START);
            assertThat(starts).hasSize(1);
            assertThat(starts.get(0).schemaErrors()).isEmpty();
            assertThat(starts.get(0).header("x-trace-id")).matches("^[0-9a-f]{32}$");
            assertThat(starts.get(0).json().path("sessionId").asString()).isEqualTo(started.sessionId().toString());
        });
    }

    @Test
    void rejectionsFollowConsentThenInProgressThenDeviceAndTerminalCopiesDoNotBlock() {
        Family family = readyFamily();
        runner.run(context -> {
            StartActivityService service = start(context);
            fixtures.consent(family.guardianId(), family.childId(), "RAW_AUDIO", "WITHDRAWN", clock.now().minusSeconds(1));
            expect(() -> service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)),
                    ErrorCode.CONSENT_MISSING);
            fixtures.consent(family.guardianId(), family.childId(), "RAW_AUDIO", "GIVEN", clock.now());

            Started first = service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));
            expect(() -> service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)),
                    ErrorCode.SESSION_IN_PROGRESS);

            activities(context).applyStateChange(new StateChange(first.sessionId(), SessionStatus.ENDED, 9, null, null, true,
                    clock.now()), clock.now());
            db.jdbc().update("update core.core_device set reported_connection_state = 'UNSTABLE' where guardian_id = :g",
                    new MapSqlParameterSource("g", family.guardianId()));
            expect(() -> service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)),
                    ErrorCode.DEVICE_NOT_STABLE);
            assertThat(realtime.requests("POST", START)).hasSize(1);
        });
    }

    @Test
    void sameKeyConcurrentlyGivesOneSessionAndOtherKeyIsRejected() {
        Family family = readyFamily();
        realtime.route("POST", START).requestSchema("internal-session-start-request.schema.json")
                .respond(StubResponse.json(202, "{\"status\":\"PREPARING\"}").delayed(Duration.ofMillis(100)));
        runner.run(context -> {
            StartActivityService service = start(context);
            StartRequest request = new StartRequest(UUID.randomUUID(), new Context("같은 내용", null, null, null));
            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<Object> a = CompletableFuture.supplyAsync(() -> attempt(go, () -> service.start(
                    family.guardianId(), request)));
            CompletableFuture<Object> b = CompletableFuture.supplyAsync(() -> attempt(go, () -> service.start(
                    family.guardianId(), request)));
            go.countDown();
            Object ra = a.get(10, TimeUnit.SECONDS);
            Object rb = b.get(10, TimeUnit.SECONDS);

            assertThat(ra).isInstanceOf(Started.class);
            assertThat(rb).isEqualTo(ra);
            assertThat(realtime.requests("POST", START)).hasSize(1);
            expect(() -> service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)),
                    ErrorCode.SESSION_IN_PROGRESS);
            assertThat(db.jdbc().queryForObject("select count(*) from core.core_activity where guardian_id = :g",
                    new MapSqlParameterSource("g", family.guardianId()), Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void requestedWithoutPreparingIsClosedAfterTenSecondsAndCancelled() {
        Family family = readyFamily();
        runner.run(context -> {
            StartRequest request = new StartRequest(UUID.randomUUID(), null);
            Started started = start(context).start(family.guardianId(), request);
            PreparingTimeoutChecker checker = context.getBean(PreparingTimeoutChecker.class);

            clock.advance(Duration.ofMillis(999));
            assertThat(checker.runOnce()).isZero();
            clock.advance(Duration.ofMillis(2));
            assertThat(checker.runOnce()).isEqualTo(1);

            ActivityCopy closed = activities(context).findById(started.sessionId()).orElseThrow();
            assertThat(closed.status()).isEqualTo(SessionStatus.FAILED);
            assertThat(closed.localFailed()).isTrue();
            assertThat(closed.startResultCode()).isEqualTo(202);
            RecordedRequest cancel = realtime.requests("POST", END).get(0);
            assertThat(cancel.path()).isEqualTo("/internal/sessions/" + started.sessionId() + "/end");
            assertThat(cancel.json().path("reason").asString()).isEqualTo("CORE_ABANDONED");
            assertThat(cancel.json().path("requestId").asString()).isEqualTo(started.sessionId().toString());
            assertThat(start(context).start(family.guardianId(), request)).isEqualTo(started);
        });
    }

    @Test
    void realtimeRefusalsAreTranslatedAndTimeoutCancelsAfterOneRetry() {
        Family family = readyFamily();
        runner.run(context -> {
            StartActivityService service = start(context);
            realtime.route("POST", START).respond(StubResponse.error(423, ErrorCode.DEVICE_NOT_CONNECTED));
            UUID key423 = UUID.randomUUID();
            expect(() -> service.start(family.guardianId(), new StartRequest(key423, null)), ErrorCode.DEVICE_NOT_STABLE);
            assertThat(activities(context).findByIdempotencyKey(family.guardianId(), key423.toString()).orElseThrow()
                    .startResultCode()).isEqualTo(423);

            realtime.route("POST", START).respond(StubResponse.error(409, ErrorCode.SESSION_IN_PROGRESS));
            expect(() -> service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)),
                    ErrorCode.SESSION_IN_PROGRESS);

            realtime.route("POST", START).respond(StubResponse.json(202, "{\"status\":\"PREPARING\"}")
                    .delayed(Duration.ofSeconds(2)));
            UUID key503 = UUID.randomUUID();
            expect(() -> service.start(family.guardianId(), new StartRequest(key503, null)),
                    ErrorCode.REALTIME_UNAVAILABLE);
            ActivityCopy failed = activities(context).findByIdempotencyKey(family.guardianId(), key503.toString())
                    .orElseThrow();
            assertThat(failed.startResultCode()).isEqualTo(503);
            assertThat(realtime.requests("POST", START).stream()
                    .filter(r -> r.json().path("sessionId").asString().equals(failed.sessionId().toString()))).hasSize(2);
            List<RecordedRequest> cancels = realtime.awaitRequests("POST", END, 1, WAIT);
            assertThat(cancels).hasSize(1);
            assertThat(cancels.get(0).json().path("reason").asString()).isEqualTo("CORE_ABANDONED");
            expect(() -> service.start(family.guardianId(), new StartRequest(key503, null)),
                    ErrorCode.REALTIME_UNAVAILABLE);
        });
    }

    @Test
    void breakerOpensAfterThreeFailuresAndHalfOpensAfterOpenDuration() {
        Family family = readyFamily();
        realtime.route("POST", START).respond(StubResponse.error(503, ErrorCode.SERVICE_NOT_READY));
        runner.run(context -> {
            StartActivityService service = start(context);
            for (int i = 0; i < 3; i++) {
                expect(() -> service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)),
                        ErrorCode.REALTIME_UNAVAILABLE);
            }
            expect(() -> service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)),
                    ErrorCode.REALTIME_UNAVAILABLE);
            assertThat(realtime.requests("POST", START)).hasSize(3);

            realtime.route("POST", START).respond(StubResponse.json(202, "{\"status\":\"PREPARING\"}"));
            clock.advance(Duration.ofMillis(500));
            assertThat(service.start(family.guardianId(), new StartRequest(UUID.randomUUID(), null)).status())
                    .isEqualTo(SessionStatus.REQUESTED);
            assertThat(realtime.requests("POST", START)).hasSize(4);
            assertThat(context.getBean(SessionStartBreaker.class).state()).isEqualTo(SessionStartBreaker.State.CLOSED);
        });
    }

    @Test
    void endFollowsResponseTableAndOwnership() {
        Family family = readyFamily();
        runner.run(context -> {
            Started started = start(context).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));
            activities(context).applyStateChange(new StateChange(started.sessionId(), SessionStatus.ACTIVE, 3, null, null,
                    false, clock.now()), clock.now());
            EndSessionService ends = context.getBean(EndSessionService.class);
            realtime.route("POST", END).requestSchema("internal-session-end-request.schema.json")
                    .respond(StubResponse.json(202, "{\"status\":\"CLOSING\"}"));
            UUID requestId = UUID.randomUUID();

            assertThat(ends.end(family.guardianId(), started.sessionId(), requestId).status())
                    .isEqualTo(SessionStatus.CLOSING);
            assertThat(ends.end(family.guardianId(), started.sessionId(), requestId).status())
                    .isEqualTo(SessionStatus.CLOSING);
            assertThat(realtime.requests("POST", END)).hasSize(2)
                    .allMatch(r -> r.json().path("requestId").asString().equals(requestId.toString())
                            && r.json().path("reason").asString().equals("GUARDIAN_STOP"));

            realtime.route("POST", END).respond(StubResponse.error(409, ErrorCode.SESSION_NOT_ACTIVE));
            expect(() -> ends.end(family.guardianId(), started.sessionId(), UUID.randomUUID()), ErrorCode.SESSION_NOT_ACTIVE);
            realtime.route("POST", END).respond(StubResponse.json(202, "{}").delayed(Duration.ofSeconds(2)));
            expect(() -> ends.end(family.guardianId(), started.sessionId(), UUID.randomUUID()),
                    ErrorCode.REALTIME_UNAVAILABLE);
            expect(() -> ends.end(UUID.randomUUID(), started.sessionId(), UUID.randomUUID()), ErrorCode.NOT_FOUND);

            activities(context).applyStateChange(new StateChange(started.sessionId(), SessionStatus.ENDED, 5, null, null,
                    true, clock.now()), clock.now());
            int before = realtime.requests("POST", END).size();
            expect(() -> ends.end(family.guardianId(), started.sessionId(), UUID.randomUUID()), ErrorCode.SESSION_NOT_ACTIVE);
            assertThat(realtime.requests("POST", END)).hasSize(before);
        });
    }

    @Test
    void staleActiveCopyUnknownToRealtimeDoesNotLockTheAccount() {
        Family family = readyFamily();
        runner.run(context -> {
            Started old = start(context).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));
            activities(context).applyStateChange(new StateChange(old.sessionId(), SessionStatus.ACTIVE, 4, null, null, false,
                    clock.now()), clock.now());

            Started fresh = start(context).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));

            assertThat(fresh.sessionId()).isNotEqualTo(old.sessionId());
            ActivityCopy closed = activities(context).findById(old.sessionId()).orElseThrow();
            assertThat(closed.status()).isEqualTo(SessionStatus.FAILED);
            assertThat(closed.localFailed()).isTrue();
            assertThat(closed.failureCode()).isNull();
            assertThat(realtime.requests("GET", VIEW)).hasSize(1);
        });
    }

    @Test
    void losingTheGuardianSessionNeverEndsOrCancelsTheActivity() {
        Family family = readyFamily();
        runner.run(context -> {
            Started started = start(context).start(family.guardianId(), new StartRequest(UUID.randomUUID(), null));
            activities(context).applyStateChange(new StateChange(started.sessionId(), SessionStatus.ACTIVE, 3, null, null,
                    false, clock.now()), clock.now());
            String principal = family.guardianId().toString();
            String primaryId = UUID.randomUUID().toString();
            MapSqlParameterSource session = new MapSqlParameterSource()
                    .addValue("pid", primaryId).addValue("sid", UUID.randomUUID().toString()).addValue("p", principal)
                    .addValue("t", clock.now().toEpochMilli());
            db.jdbc().update("""
                    insert into core.core_spring_session (primary_id, session_id, creation_time, last_access_time,
                        max_inactive_interval, expiry_time, principal_name)
                    values (:pid, :sid, :t, :t, 1800, :t + 1800000, :p)
                    """, session);
            db.jdbc().update("delete from core.core_spring_session where primary_id = :pid", session);
            realtime.route("GET", VIEW).respond(StubResponse.json(200, """
                    {"sessionId":"%s","activityType":"FREE_TALK","status":"ACTIVE","confirmed":false,
                     "requestedAt":"2001-04-01T00:00:00Z","statusVersion":3,"serverTime":"2001-04-01T00:00:01Z",
                     "gaps":[],"items":[],"nextSeq":0}
                    """.formatted(started.sessionId())));

            LiveView afterRelogin = context.getBean(SessionViewService.class).view(family.guardianId(), started.sessionId(),
                    0);

            assertThat(afterRelogin.status()).isEqualTo(SessionStatus.ACTIVE);
            assertThat(afterRelogin.stale()).isFalse();
            assertThat(realtime.requests("POST", END)).isEmpty();
        });
    }

    // ------------------------------------------------------------------ 도움

    private Family readyFamily() {
        Family family = fixtures.family(clock.now().minusSeconds(3_600));
        fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        return family;
    }

    private static StartActivityService start(ApplicationContext context) {
        return context.getBean(StartActivityService.class);
    }

    private static ActivityCopyRepository activities(ApplicationContext context) {
        return context.getBean(ActivityCopyRepository.class);
    }

    private static Object attempt(CountDownLatch go, java.util.function.Supplier<Started> body) {
        try {
            go.await(5, TimeUnit.SECONDS);
            return body.get();
        } catch (CoreApiException e) {
            return e.code();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        }
    }

    private static void expect(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
