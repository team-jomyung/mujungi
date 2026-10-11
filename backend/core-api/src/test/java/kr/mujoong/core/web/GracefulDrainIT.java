package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.startup.DrainingLifecycle;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * SIGTERM 정상 종료의 유예 중 응답 (U1 3.6 수정 · RD-13 · 2.8 T1 ~ T6 · R3-13 · RD-16 DRAINING · NFR4.10 · BT-RD-13 · J321).
 *
 * <p>시험마다 앱을 새로 띄워 실제 문맥 닫기(SIGTERM 때 Spring이 하는 일)를 한다. 시험 전용 '느린 길' 필터(F1b 바로 뒤)로 받아들인 요청을
 * 붙잡아 두면, 종료가 그 요청을 기다리는 동안 커넥터는 열려 있고 새 REST · {@code /internal/events} 요청은 접속 거절(입구 502)이 아니라 앱의 503
 * SERVICE_NOT_READY + {@code Retry-After}다. 붙잡은 요청이 끝나야 커넥터가 닫히고, 끝나지 않으면 유예({@code
 * spring.lifecycle.timeout-per-shutdown-phase}, 시험 2초) 뒤 포기한다.
 */
@Tag("u1")
@Tag("db")
class GracefulDrainIT {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static String token;

    private CoreWebApp app;

    @BeforeAll
    static void prepare() {
        db = DbTestSupport.coreApp();
        token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
    }

    @BeforeEach
    void start() {
        SlowLane.reset(1);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).source(SlowLane.class)
                .start();
    }

    @AfterEach
    void stop() {
        SlowLane.release();
        if (app != null) {
            app.close();
        }
    }

    @AfterAll
    static void finish() {
        if (realtime != null) {
            realtime.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void newRestRequestsDuringTheDrainGet503WithRetryAfterInsteadOfARefusedConnection() throws Exception {
        ReadinessState readiness = app.bean(ReadinessState.class);
        CompletableFuture<Result> held = holdOneRequest();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(app::close);
        awaitDraining(readiness);

        for (Result result : List.of(app.get("/api/health").send(), app.get("/api/home").send(),
                app.post("/api/device/bootstrap").send())) {
            assertServiceNotReady(result, readiness);
        }

        assertThat(closing).as("받아들인 요청이 끝날 때까지 종료가 기다린다").isNotDone();
        SlowLane.release();
        assertThat(held.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(200);
        closing.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void internalEventsDuringTheDrainAre503SoTheSenderRetriesAndNothingIsStored() throws Exception {
        ReadinessState readiness = app.bean(ReadinessState.class);
        CompletableFuture<Result> held = holdOneRequest();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(app::close);
        awaitDraining(readiness);
        UUID eventId = UUID.randomUUID();

        Result events = app.post("/internal/events").internalToken()
                .json("{\"eventId\":\"" + eventId + "\",\"eventType\":\"SessionStateChanged\"}").send();

        assertServiceNotReady(events, readiness);
        Long stored = db.jdbc().queryForObject("select count(*) from core.core_inbox_event where event_id = :id",
                new MapSqlParameterSource("id", eventId), Long.class);
        assertThat(stored).isZero();
        SlowLane.release();
        held.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        closing.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void theConnectorClosesOnlyAfterEveryAdmittedRequestHasFinished() throws Exception {
        SlowLane.reset(2);
        ReadinessState readiness = app.bean(ReadinessState.class);
        CompletableFuture<Result> first = CompletableFuture.supplyAsync(() -> app.get(SlowLane.PATH).send());
        CompletableFuture<Result> second = CompletableFuture.supplyAsync(() -> app.get(SlowLane.PATH).send());
        assertThat(SlowLane.awaitEntered()).isTrue();
        CompletableFuture<Long> closedAt = CompletableFuture.supplyAsync(() -> {
            app.close();
            return System.nanoTime();
        });
        awaitDraining(readiness);

        long releasedAt = System.nanoTime();
        SlowLane.release();

        assertThat(first.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(second.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(closedAt.get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("종료는 붙잡힌 요청을 놓아준 뒤에 끝난다")
                .isGreaterThan(releasedAt);
        assertThatThrownBy(() -> app.get("/api/health").send()).as("유예가 끝난 뒤에야 접속 거절")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anIdleShutdownSkipsTheGraceAndWritesTheLastMetricSnapshot() {
        Duration grace = grace();
        DrainingLifecycle draining = app.bean(DrainingLifecycle.class);
        try (LogCapture logs = LogCapture.attach()) {
            long started = System.nanoTime();
            draining.stop();
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took).as("기다릴 요청이 없으면 유예를 다 쓰지 않는다").isLessThan(grace);
            Map<String, Object> last = LogCapture.attrs(logs.last(EventCode.METRIC_SNAPSHOT).orElseThrow());
            assertThat(last).containsKeys("outboxPending", "inboxDead", "poolActive", "restTotal", "viewTotal",
                    "breakerOpenSec");
            assertThat(last.values()).allSatisfy(value -> assertThat(value).isInstanceOf(Number.class));
            assertThat(logs.events(EventCode.APP_ERROR)).noneMatch(line -> "DrainTimeout"
                    .equals(LogCapture.attrs(line).get("errorKind")));
        }
        assertThat(app.bean(ReadinessState.class).state()).isEqualTo(ReadinessState.State.DRAINING);
    }

    @Test
    void aStuckRequestIsGivenUpWhenTheGraceRunsOut() throws Exception {
        Duration grace = grace();
        CompletableFuture<Result> held = holdOneRequest();
        DrainingLifecycle draining = app.bean(DrainingLifecycle.class);
        try (LogCapture logs = LogCapture.attach()) {
            long started = System.nanoTime();
            draining.stop();
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took).as("유예만큼 기다리고 더 기다리지 않는다").isGreaterThanOrEqualTo(grace)
                    .isLessThan(grace.plus(WAIT));
            assertThat(logs.events(EventCode.APP_ERROR)).anyMatch(line -> "DrainTimeout"
                    .equals(LogCapture.attrs(line).get("errorKind")));
        }
        assertThat(held).as("포기해도 붙잡힌 요청을 끊지는 않는다(뒤 단계 graceful · SIGKILL의 몫)").isNotDone();
        SlowLane.release();
        assertThat(held.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(200);
    }

    private CompletableFuture<Result> holdOneRequest() throws InterruptedException {
        CompletableFuture<Result> held = CompletableFuture.supplyAsync(() -> app.get(SlowLane.PATH).send());
        assertThat(SlowLane.awaitEntered()).as("느린 길 요청이 F1b를 지나 붙잡혔다").isTrue();
        return held;
    }

    private Duration grace() {
        Duration grace = app.context().getEnvironment().getProperty(DrainingLifecycle.GRACE_KEY, Duration.class);
        assertThat(grace).as("시험 유예(application-test.yml)").isEqualTo(Duration.ofSeconds(2));
        return grace;
    }

    private static void awaitDraining(ReadinessState readiness) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (readiness.state() != ReadinessState.State.DRAINING) {
            if (System.nanoTime() > deadline) {
                fail("10초 안에 DRAINING이 되지 않았다");
            }
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        }
    }

    private static void assertServiceNotReady(Result result, ReadinessState readiness) {
        assertThat(result.status()).isEqualTo(503);
        assertThat(result.code()).isEqualTo("SERVICE_NOT_READY");
        assertThat(result.header("Retry-After")).isEqualTo(Long.toString(readiness.retryAfterSeconds()));
        assertThat(result.json().get("traceId")).isEqualTo(result.header("X-Trace-Id"));
        assertThat(result.json()).containsOnlyKeys("code", "message", "traceId");
    }

    /**
     * 시험 전용 느린 길: F1b 바로 뒤에서 {@link #PATH} 요청을 놓아줄 때까지 붙잡는다(설정 클래스 표지가 없어 앱 스캔에 걸리지 않고 이 시험 앱에만
     * 소스로 들어간다).
     */
    static class SlowLane {

        static final String PATH = "/api/test/slow-lane";

        private static volatile CountDownLatch entered = new CountDownLatch(1);
        private static volatile CountDownLatch release = new CountDownLatch(1);

        static void reset(int requests) {
            entered = new CountDownLatch(requests);
            release = new CountDownLatch(1);
        }

        static boolean awaitEntered() throws InterruptedException {
            return entered.await(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        static void release() {
            release.countDown();
        }

        @Bean
        FilterRegistrationBean<Filter> slowLaneFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                if (!PATH.equals(((HttpServletRequest) request).getRequestURI())) {
                    chain.doFilter(request, response);
                    return;
                }
                CountDownLatch gate = release;
                entered.countDown();
                try {
                    gate.await(WAIT.toSeconds() * 3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                HttpServletResponse http = (HttpServletResponse) response;
                http.setStatus(200);
                http.setContentType("application/json");
                http.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            });
            registration.setOrder(ReadinessGateFilter.ORDER + 1);
            return registration;
        }
    }
}
