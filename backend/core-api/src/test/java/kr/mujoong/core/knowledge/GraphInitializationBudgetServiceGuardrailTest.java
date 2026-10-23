package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.contract.time.SystemMujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 초기화 전용 마감과 업무2초의 분리. 합성 HTTP/가변 시계와 실제 localhost 지연을 구분한다. */
@Tag("service-guardrail")
class GraphInitializationBudgetServiceGuardrailTest {
    private static final Duration ORDINARY = Duration.ofSeconds(2);
    private static final Duration STARTUP = Duration.ofSeconds(10);

    @Test
    void successfulPreparationUsesOneDeadlineAndOnlyThenBecomesReady() throws Exception {
        var h = new Harness();
        h.step = (request, n) -> h.clock.advance(Duration.ofSeconds(1));
        h.graph.prepareSchema();
        assertThat(h.timeouts()).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(9),
                Duration.ofSeconds(8), Duration.ofSeconds(7), Duration.ofSeconds(6),
                Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(3), Duration.ofSeconds(2));
        assertThat(h.requests.getLast().method()).isEqualTo("DELETE");
        h.graph.prepareSchema();
        assertThat(h.requests).hasSize(9);
    }

    @Test
    void productionConfigurationAllowsAPlanOverTwoSecondsButOrdinaryRequestsStillTimeOut() throws Exception {
        var slowBegin = new AtomicBoolean();
        var delayedPlan = new AtomicBoolean();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean begin = exchange.getRequestURI().getPath().equals("/db/neo4j/tx");
                if ((input.contains("EXPLAIN ") && delayedPlan.compareAndSet(false, true))
                        || (begin && slowBegin.get())) {
                    // 의도적인 지연 응답 대역. 임의 워밍업/재시도가 아니라 HTTP timeout의 실제 경계를 검사한다.
                    var release = new CountDownLatch(1);
                    scheduler.schedule(release::countDown, 2300, TimeUnit.MILLISECONDS);
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic response stalled");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                byte[] body = responseBody().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) { output.write(body); }
                } catch (java.io.IOException timedOutClient) {
                    // 일반2초 timeout으로 상대 연결이 닫히는 것이 이 대역의 예상 결과다.
                    exchange.close();
                }
            });
            server.start();
            try {
                var core = mock(CoreProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
                when(core.timing().knowledge().graphBudget()).thenReturn(ORDINARY);
                when(core.timing().knowledge().graphInitializationBudget()).thenReturn(STARTUP);
                long began = System.nanoTime();
                var graph = new KnowledgeConfiguration().childKnowledgeGraph(
                        new KnowledgeProperties(true, "http://127.0.0.1:" + server.getAddress().getPort()), core,
                        new MockEnvironment().withProperty("NEO4J_AUTH", "synthetic/credential"), new SystemMujoongClock());
                assertThat(Duration.ofNanos(System.nanoTime() - began)).isGreaterThan(ORDINARY).isLessThan(STARTUP);
                assertThat(delayedPlan).isTrue();
                slowBegin.set(true);
                assertThatThrownBy(graph::begin).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Graph operation unresolved");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void cumulativeWorkCannotRestartTheBudgetAtEachStage() throws Exception {
        var h = new Harness();
        h.step = (request, n) -> h.clock.advance(Duration.ofSeconds(2));
        assertThatThrownBy(h.graph::prepareSchema).isInstanceOf(IllegalStateException.class)
                .hasMessage("Graph initialization deadline expired");
        assertThat(h.timeouts()).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(8),
                Duration.ofSeconds(6), Duration.ofSeconds(4), Duration.ofSeconds(2), ORDINARY);
        assertThat(h.requests.getLast().method()).isEqualTo("DELETE");
        h.assertNotReadyAndPrepareAgain();
    }

    @Test
    void completionExactlyAtDeadlineCannotOpenReady() throws Exception {
        var h = new Harness();
        h.step = (request, n) -> { if (n == 9) h.clock.advance(STARTUP); };
        assertThatThrownBy(h.graph::prepareSchema).isInstanceOf(IllegalStateException.class)
                .hasMessage("Graph initialization deadline expired");
        assertThat(h.requests).hasSize(9);
        h.assertNotReadyAndPrepareAgain();
    }

    @Test
    void transportTimeoutKeepsReadyClosedAndCleansOnlyTheKnownTransaction() throws Exception {
        var h = new Harness();
        h.step = (request, n) -> {
            if (n == 6) throw new HttpTimeoutException("synthetic timeout");
        };
        assertThatThrownBy(h.graph::prepareSchema).isInstanceOf(IllegalStateException.class)
                .hasMessage("Graph operation unresolved");
        assertThat(h.requests).hasSize(7);
        assertThat(h.requests.getLast().method()).isEqualTo("DELETE");
        assertThat(h.requests.getLast().timeout()).contains(ORDINARY);
        h.assertNotReadyAndPrepareAgain();
    }

    @Test
    void interruptionPermitsBoundedCleanupAndRestoresTheInterruptFlag() throws Exception {
        var h = new Harness();
        h.step = (request, n) -> {
            if (n == 6) throw new InterruptedException("synthetic interruption");
            if (n == 7) assertThat(Thread.currentThread().isInterrupted()).isFalse();
        };
        try {
            assertThatThrownBy(h.graph::prepareSchema).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Graph operation interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(h.requests).hasSize(7);
            assertThat(h.requests.getLast().method()).isEqualTo("DELETE");
            assertThat(h.requests.getLast().timeout()).contains(ORDINARY);
        } finally {
            Thread.interrupted();
        }
        h.assertNotReadyAndPrepareAgain();
    }

    @Test
    void cleanupFailureIsBoundedAndCannotHideTheOriginalStartupFailure() throws Exception {
        var h = new Harness();
        h.step = (request, n) -> {
            if (n == 6) throw new HttpTimeoutException("synthetic preparation failure");
            if (n == 7) throw new HttpTimeoutException("synthetic cleanup failure");
        };
        assertThatThrownBy(h.graph::prepareSchema).isInstanceOf(IllegalStateException.class)
                .hasMessage("Graph operation unresolved").satisfies(failure ->
                        assertThat(failure.getSuppressed()).singleElement().isInstanceOf(IllegalStateException.class));
        assertThat(h.requests).hasSize(7);
        assertThat(h.requests.getLast().timeout()).contains(ORDINARY);
        h.assertNotReadyAndPrepareAgain();
    }

    @Test
    void everyOrdinaryReadWriteBeginCommitAndCleanupKeepsTwoSeconds() throws Exception {
        var h = new Harness();
        h.graph.prepareSchema();
        h.requests.clear();
        UUID child = UUID.randomUUID();
        String tx = h.graph.begin();
        h.graph.replaceBase(tx, child, GraphModelTest.catalog());
        h.graph.writeGeneratedSeed(tx, child, GraphModelTest.seed("seed:G0001", KnowledgeSeed.Origin.GENERATED));
        h.graph.deleteSeeds(tx, child, List.of("seed:S1"));
        h.graph.deleteChild(tx, child);
        h.graph.commit(tx);
        h.graph.terminate(tx);
        // 빈 조회 응답은 기존 읽기 트랜잭션의 종료도 검사한다.
        h.emptyRows = true;
        assertThat(h.graph.seeds(child)).isEmpty();
        assertThat(h.requests).hasSize(10);
        assertThat(h.timeouts()).containsOnly(ORDINARY);
    }

    private static String responseBody() {
        return "{\"commit\":\"http://127.0.0.1/db/neo4j/tx/17/commit\",\"errors\":[],"
                + "\"results\":[{\"data\":[{\"row\":[\"" + "a".repeat(64) + "\"]}]}]}";
    }

    @FunctionalInterface
    private interface Step {
        void send(HttpRequest request, int number) throws Exception;
    }

    private static final class Harness {
        final MutableClock clock = new MutableClock();
        final List<HttpRequest> requests = new ArrayList<>();
        final Neo4jChildKnowledgeGraph graph = new Neo4jChildKnowledgeGraph(URI.create("http://127.0.0.1"),
                "synthetic/credential", ORDINARY, STARTUP, clock);
        Step step = (request, n) -> { };
        boolean emptyRows;

        @SuppressWarnings("unchecked")
        Harness() throws Exception {
            var client = mock(HttpClient.class);
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
                HttpRequest request = call.getArgument(0);
                requests.add(request);
                step.send(request, requests.size());
                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(200);
                when(response.body()).thenReturn(emptyRows
                        ? "{\"commit\":\"http://127.0.0.1/db/neo4j/tx/17/commit\",\"errors\":[],\"results\":[]}"
                        : responseBody());
                return response;
            });
            var field = Neo4jChildKnowledgeGraph.class.getDeclaredField("client");
            field.setAccessible(true);
            field.set(graph, client);
        }

        List<Duration> timeouts() { return requests.stream().map(request -> request.timeout().orElseThrow()).toList(); }

        void assertNotReadyAndPrepareAgain() {
            int before = requests.size();
            step = (request, n) -> { };
            graph.prepareSchema();
            assertThat(requests).hasSize(before + 9);
            graph.prepareSchema();
            assertThat(requests).hasSize(before + 9);
        }
    }
}
