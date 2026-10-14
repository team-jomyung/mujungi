package kr.mujoong.realtime.web;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.InternalApi.Reply;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * SIGTERM 정상 종료의 유예 중 응답과 순서 (U1 3.6 수정 · RD-13 · 2.8 T1 ~ T6 · R3-13 · RD-16 DRAINING · NFR4.10 · BT-RD-13 · J304).
 *
 * <p>시험마다 앱을 새로 띄워 실제 문맥 닫기(SIGTERM 때 Spring이 하는 일)를 한다. 시험 전용 '느린 길' 필터(F1b 바로 뒤)로 받아들인 요청을
 * 붙잡아 두면, 종료가 그 요청을 기다리는 동안 커넥터는 열려 있고 새 /internal 요청은 접속 거절(입구 502)이 아니라 앱의 503
 * SERVICE_NOT_READY + {@code Retry-After}다. 새 기기 WSS는 업그레이드 뒤 1013, 열린 WSS는 1001로 닫히고, 진행 중 세션의 공급자 연결은 폴러를
 * 멈추기 전에 닫힌다. 붙잡은 요청이 끝나야 커넥터가 닫히고, 끝나지 않으면 유예({@code spring.lifecycle.timeout-per-shutdown-phase}, 시험
 * 2초) 뒤 포기한다.
 */
@Tag("u1")
@Tag("db")
class GracefulDrainIT {

    /** DB 점검 주기를 길게 둬 Retry-After를 고정한다(1시간 = 3600초). */
    private static final Map<String, String> ARGS = Map.of(
            "mujoong.health.db-check-interval", "1h",
            "mujoong.timing.session.device-ack-timeout", "20s",
            "mujoong.timing.device.disconnect", "40s");

    private RealtimeItEnvironment env;
    private InternalApi api;

    @BeforeEach
    void start() {
        SlowLane.reset(1);
        env = RealtimeItEnvironment.startWith(ARGS,
                context -> context.getBeanFactory().registerSingleton("itSlowLane", SlowLane.registration()));
        api = env.api();
    }

    @AfterEach
    void stop() {
        SlowLane.release();
        if (env != null) {
            env.close();
        }
    }

    @Test
    void newInternalRequestsDuringTheDrainGet503WithRetryAfterInsteadOfARefusedConnection() throws Exception {
        ReadinessState readiness = env.app.bean(ReadinessState.class);
        CompletableFuture<Reply> held = holdOneRequest();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(env.app::close);
        awaitDraining(readiness);

        Reply view = api.get("/internal/sessions/" + UUID.randomUUID() + "/view");
        Reply start = api.post("/internal/sessions", "{}", InternalApi.Token.MISSING);

        for (Reply reply : new Reply[] {view, start}) {
            assertThat(reply.status()).isEqualTo(503);
            assertThat(reply.code()).isEqualTo("SERVICE_NOT_READY");
            assertThat(reply.header("Retry-After")).isEqualTo("3600");
            assertThat(reply.json().get("traceId").asString()).isEqualTo(reply.header("X-Trace-Id"));
            assertThat(InternalApi.schemaErrors("error-envelope.schema.json", reply.body())).isEmpty();
        }
        assertThat(closing).as("받아들인 요청이 끝날 때까지 종료가 기다린다").isNotDone();
        SlowLane.release();
        assertThat(held.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(200);
        closing.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void internalEventsDuringTheDrainAre503SoTheSenderRetriesAndNothingIsStored() throws Exception {
        ReadinessState readiness = env.app.bean(ReadinessState.class);
        CompletableFuture<Reply> held = holdOneRequest();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(env.app::close);
        awaitDraining(readiness);
        UUID eventId = UUID.randomUUID();

        Reply events = api.post("/internal/events", "{\"eventId\":\"" + eventId + "\",\"eventType\":\"DeviceReleased\"}");

        assertThat(events.status()).isEqualTo(503);
        assertThat(events.code()).isEqualTo("SERVICE_NOT_READY");
        assertThat(events.header("Retry-After")).isEqualTo("3600");
        Long stored = env.db.single("select count(*) from realtime.realtime_inbox_event where event_id = :id",
                new MapSqlParameterSource("id", eventId), Long.class);
        assertThat(stored).isZero();
        SlowLane.release();
        held.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        closing.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void anOpenDeviceSocketGets1001AndANewOneDuringTheDrainGets1013() throws Exception {
        DeviceSimulatorClient open = env.connectDevice();
        ReadinessState readiness = env.app.bean(ReadinessState.class);
        CompletableFuture<Reply> held = holdOneRequest();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(env.app::close);
        awaitDraining(readiness);

        assertThat(open.awaitClose(WAIT)).as("T2: 열린 WSS는 1001").contains(CloseCode.GOING_AWAY.code());
        DeviceSimulatorClient late = DeviceSimulatorClient.open(env.app.deviceUri(), RealtimeItEnvironment.ORIGIN,
                env.deviceToken, WAIT);
        assertThat(late.awaitClose(WAIT)).as("T1: 커넥터는 열려 있어 업그레이드 뒤 1013").contains(
                CloseCode.TRY_AGAIN_LATER.code());

        SlowLane.release();
        held.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        closing.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void theConnectorClosesOnlyAfterEveryAdmittedRequestHasFinished() throws Exception {
        SlowLane.reset(2);
        ReadinessState readiness = env.app.bean(ReadinessState.class);
        CompletableFuture<Reply> first = CompletableFuture.supplyAsync(() -> api.get(SlowLane.PATH));
        CompletableFuture<Reply> second = CompletableFuture.supplyAsync(() -> api.get(SlowLane.PATH));
        assertThat(SlowLane.awaitEntered()).isTrue();
        CompletableFuture<Long> closedAt = CompletableFuture.supplyAsync(() -> {
            env.app.close();
            return System.nanoTime();
        });
        awaitDraining(readiness);

        long releasedAt = System.nanoTime();
        SlowLane.release();

        assertThat(first.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(second.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(closedAt.get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("종료는 붙잡힌 요청을 놓아준 뒤에 끝난다")
                .isGreaterThan(releasedAt);
        assertThatThrownBy(() -> api.get("/internal/sessions/" + UUID.randomUUID() + "/view"))
                .as("유예가 끝난 뒤에야 접속 거절").isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void anIdleShutdownClosesTheSocketAndTheProviderAndWritesTheLastSnapshotWithinTheGrace() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        assertThat(env.start(sessionId)).isEqualTo(new StartSessionHandler.Accepted(SessionStatus.PREPARING));
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(command.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitTrue(() -> env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow().status()
                == SessionStatus.ACTIVE, "ACTIVE");
        awaitTrue(() -> env.provider.openConnections() == 1, "공급자 연결");
        Duration grace = env.app.context().getEnvironment().getProperty("spring.lifecycle.timeout-per-shutdown-phase",
                Duration.class);
        kr.mujoong.realtime.startup.DrainingLifecycle draining =
                env.app.bean(kr.mujoong.realtime.startup.DrainingLifecycle.class);

        try (LogCapture logs = LogCapture.attach()) {
            long started = System.nanoTime();
            draining.stop();
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took).as("기다릴 요청이 없으면 유예를 다 쓰지 않는다").isLessThan(grace);
            assertThat(env.provider.await("session.close", 1, WAIT)).as("T3: 공급자에 닫기 요청 한 번").hasSize(1);
            assertThat(device.awaitClose(WAIT)).as("T2: 열린 WSS는 1001").contains(CloseCode.GOING_AWAY.code());
            Map<String, Object> last = LogCapture.attrs(logs.last(EventCode.METRIC_SNAPSHOT).orElseThrow());
            assertThat(last).containsKeys("outboxPending", "inboxDead", "poolActive", "openConnections",
                    "sessionsActive").doesNotContainKeys("restTotal", "breakerOpenSec");
            assertThat(logs.events(EventCode.APP_ERROR)).noneMatch(line -> {
                Object kind = LogCapture.attrs(line).get("errorKind");
                return "DrainTimeout".equals(kind) || "ProviderCloseTimeout".equals(kind);
            });
        }
        awaitTrue(() -> env.provider.openConnections() == 0, "공급자 연결 닫힘");
        assertThat(env.db.single("select status from realtime.realtime_activity_session where id = :id",
                new MapSqlParameterSource("id", sessionId), String.class))
                .as("진행 중 세션은 닫지 않는다(다음 기동 S3a의 몫)").isEqualTo("ACTIVE");
    }

    @Test
    void aStuckRequestIsGivenUpWhenTheGraceRunsOut() throws Exception {
        CompletableFuture<Reply> held = holdOneRequest();
        Duration grace = env.app.context().getEnvironment().getProperty("spring.lifecycle.timeout-per-shutdown-phase",
                Duration.class);
        assertThat(grace).as("시험 유예(application-test.yml)").isEqualTo(Duration.ofSeconds(2));
        kr.mujoong.realtime.startup.DrainingLifecycle draining =
                env.app.bean(kr.mujoong.realtime.startup.DrainingLifecycle.class);

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

    private CompletableFuture<Reply> holdOneRequest() throws InterruptedException {
        CompletableFuture<Reply> held = CompletableFuture.supplyAsync(() -> api.get(SlowLane.PATH));
        assertThat(SlowLane.awaitEntered()).as("느린 길 요청이 F1b를 지나 붙잡혔다").isTrue();
        return held;
    }

    private static void awaitDraining(ReadinessState readiness) {
        awaitTrue(() -> readiness.state() == ReadinessState.State.DRAINING, "DRAINING");
    }

    /** 시험 전용 느린 길: F1b 바로 뒤에서 {@link #PATH} 요청을 놓아줄 때까지 붙잡는다(이 시험의 앱 문맥에만 등록한다). */
    static final class SlowLane {

        static final String PATH = "/test/slow-lane";

        private static volatile CountDownLatch entered = new CountDownLatch(1);
        private static volatile CountDownLatch release = new CountDownLatch(1);

        private SlowLane() {
        }

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

        static FilterRegistrationBean<Filter> registration() {
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
