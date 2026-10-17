package kr.mujoong.realtime.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 실제 제브 어댑터 (U13 FR2.1 · FR1.2 · BR2.1 ~ BR2.3 · BR2.6 · BR2.8). 공급자는 루프백 가짜 제브 서버, 키는 가짜 문자열, 입력은 합성 값이다(R-F1 ·
 * R-F3). 실제 제브 응답 형식은 아직 모르므로 변환의 형식 가정({@link JevGuardrailClient}의 클래스 설명)을 이 시험이 못 박는다 — 실호출이 다르면 어댑터의
 * 변환과 이 시험만 고친다. 이 파일의 성공은 테스트 대역 증거다(R-M11).
 */
@Tag("u13")
class JevGuardrailClientTest {

    private static final String FAKE_KEY = "fake-jev-key-not-real-u13";
    private static final String MODEL = "jev-model-1";
    private static final String POLICY = "jev-guardrail-v1";
    private static final Duration BUDGET = Duration.ofMillis(800);
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final JsonCodec JSON = JsonCodec.shared();

    private final MutableClock clock = new MutableClock();
    private final AtomicReference<String> key = new AtomicReference<>(FAKE_KEY);
    private final LiveContentResources.GuardrailPolicy policy = LiveContentResources.loadDefault().guardrail();
    private FakeJev jev;
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        jev = FakeJev.start();
        logs = LogCapture.attach();
    }

    @AfterEach
    void tearDown() {
        logs.close();
        jev.close();
    }

    private JevGuardrailClient client(String baseUrl, String model, Duration budget, JevHosts.Mode hostMode) {
        return new JevGuardrailClient(new GuardrailProperties("REAL", baseUrl, model), hostMode, key::get,
                version -> policy.version().equals(version) ? Optional.of(policy.systemPrompt()) : Optional.empty(), budget, clock, JSON);
    }

    private JevGuardrailClient client(Duration budget) {
        return client(jev.baseUrl(), MODEL, budget, JevHosts.Mode.TEST);
    }

    private static GuardrailRequest request() {
        return GuardrailRequest.of(GuardrailRequest.Mode.FREE_TALK, "응, 같이 해 보자!", POLICY, List.of(
                new GuardrailRequest.ContextTurn(GuardrailRequest.Speaker.CHILD, "나도 해도 돼?"),
                new GuardrailRequest.ContextTurn(GuardrailRequest.Speaker.MUJOONG, "무엇을 하고 싶어?")));
    }

    private GuardrailVerdict check(JevGuardrailClient client) throws Exception {
        return client.check(request(), clock.now().plus(BUDGET)).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    private static String answer(String decision, String categories, String flags, String reason, String version, String extra) {
        return "{\"decision\":\"" + decision + "\",\"categories\":" + categories + ",\"policyFlags\":" + flags + ",\"reasonCode\":\""
                + reason + "\",\"policyVersion\":\"" + version + "\"" + extra + "}";
    }

    private static String allowBody() {
        return answer("ALLOW", "[]", "[]", "OK", POLICY, ",\"reviewNeeded\":false");
    }

    private void assertFailed(GuardrailVerdict verdict, GuardrailVerdict.Failure failure) {
        assertThat(verdict.decision()).isEqualTo(GuardrailVerdict.Decision.FAILED);
        assertThat(verdict.failure()).isEqualTo(failure);
        assertThat(verdict.isAllow()).as("실패는 자동 ALLOW가 되지 않는다").isFalse();
        assertThat(verdict.policyVersion()).isEqualTo(POLICY);
    }

    @Test
    void anAllowAnswerIsConvertedAndTheRequestShapeIsPinned() throws Exception {
        jev.respond(FakeJev.Reply.ok(allowBody()));
        try (JevGuardrailClient client = client(BUDGET)) {
            GuardrailVerdict verdict = check(client);

            assertThat(verdict.decision()).isEqualTo(GuardrailVerdict.Decision.ALLOW);
            assertThat(verdict.isAllow()).isTrue();
            assertThat(verdict.reasonCode()).isEqualTo("OK");
            assertThat(verdict.policyVersion()).isEqualTo(POLICY);
        }
        assertThat(jev.requests()).hasSize(1);
        FakeJev.Recorded recorded = jev.requests().getFirst();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo(JevGuardrailClient.CHECK_PATH);
        assertThat(recorded.authorization()).isEqualTo("Bearer " + FAKE_KEY);
        Map<String, Object> body = recorded.json();
        assertThat(body.keySet()).as("식별자 필드 없음 · 닫힌 모양").containsExactlyInAnyOrder("model", "systemPrompt", "mode", "candidateText",
                "recentContext", "policyVersion");
        assertThat(body.get("model")).isEqualTo(MODEL);
        assertThat(body.get("systemPrompt")).isEqualTo(policy.systemPrompt());
        assertThat(body.get("mode")).isEqualTo("FREE_TALK");
        assertThat(body.get("candidateText")).isEqualTo("응, 같이 해 보자!");
        assertThat(body.get("policyVersion")).isEqualTo(POLICY);
        assertThat(body.get("recentContext")).isEqualTo(List.of(Map.of("speaker", "CHILD", "text", "나도 해도 돼?"),
                Map.of("speaker", "MUJOONG", "text", "무엇을 하고 싶어?")));
    }

    @Test
    void blockAndReviewAnswersKeepCategoriesFlagsAndReasonCodes() throws Exception {
        jev.respond(
                FakeJev.Reply.ok(answer("BLOCK", "[\"GENERALIZATION\",\"PRESSURE\"]", "[\"UNSUPPORTED_CHILD_TRAIT_CLAIM\"]",
                        "UNSUPPORTED_GENERALIZATION", POLICY, ",\"reviewNeeded\":false")),
                FakeJev.Reply.ok(answer("REVIEW", "[]", "[]", "CONTEXT_UNCLEAR", POLICY, ",\"reviewNeeded\":true")));
        try (JevGuardrailClient client = client(BUDGET)) {
            GuardrailVerdict block = check(client);
            GuardrailVerdict review = check(client);

            assertThat(block.decision()).isEqualTo(GuardrailVerdict.Decision.BLOCK);
            assertThat(block.categories()).containsExactly("GENERALIZATION", "PRESSURE");
            assertThat(block.policyFlags()).containsExactly("UNSUPPORTED_CHILD_TRAIT_CLAIM");
            assertThat(block.reasonCode()).isEqualTo("UNSUPPORTED_GENERALIZATION");
            assertThat(block.eventDecision()).isEqualTo(GuardrailEventRecord.Decision.BLOCK);
            assertThat(review.decision()).isEqualTo(GuardrailVerdict.Decision.REVIEW);
            assertThat(review.reasonCode()).isEqualTo("CONTEXT_UNCLEAR");
            assertThat(review.isAllow()).isFalse();
        }
    }

    @Test
    void theContractExampleOfTheResultSchemaIsTheAssumedResponseFormat() throws Exception {
        String example = ContractExamples.firstExample("guardrail-result.schema.json");
        jev.respond(FakeJev.Reply.ok(example));
        try (JevGuardrailClient client = client(BUDGET)) {
            GuardrailVerdict verdict = check(client);

            assertThat(verdict.decision()).as("응답 형식 가정: 최상위에 decision · categories · policyFlags · reasonCode · policyVersion")
                    .isEqualTo(GuardrailVerdict.Decision.BLOCK);
            assertThat(verdict.categories()).containsExactly("GENERALIZATION");
            assertThat(verdict.reasonCode()).isEqualTo("UNSUPPORTED_GENERALIZATION");
        }
    }

    @Test
    void aSlowAnswerEndsAsTimeoutWithOneCallAndNoRetry() throws Exception {
        jev.respond(FakeJev.Reply.ok(allowBody()).delayed(Duration.ofSeconds(3)));
        try (JevGuardrailClient client = client(Duration.ofMillis(200))) {
            long started = System.nanoTime();
            GuardrailVerdict verdict = client.check(request(), clock.now().plus(Duration.ofMillis(200))).toCompletableFuture()
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertFailed(verdict, GuardrailVerdict.Failure.TIMEOUT);
            assertThat(verdict.eventDecision()).isEqualTo(GuardrailEventRecord.Decision.TIMEOUT);
            assertThat(took).as("상한에서 끊고 서버 지연을 기다리지 않는다").isLessThan(Duration.ofSeconds(2));
        }
        assertThat(jev.requestCount()).as("재시도 없음").isEqualTo(1);
    }

    @Test
    void theDeadlineBoundaryUsesTheInjectedClockAndTheSmallerOfDeadlineAndBudget() throws Exception {
        jev.respond(FakeJev.Reply.ok(allowBody()));
        try (JevGuardrailClient client = client(BUDGET)) {
            // 마감이 지금이거나 이미 지났으면 보내지 않고 TIMEOUT
            assertFailed(client.check(request(), clock.now()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS),
                    GuardrailVerdict.Failure.TIMEOUT);
            Instant deadline = clock.now().plus(BUDGET);
            clock.advance(BUDGET);
            assertFailed(client.check(request(), deadline).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS),
                    GuardrailVerdict.Failure.TIMEOUT);
            assertThat(jev.requestCount()).isZero();

            // 마감이 시한보다 가까우면 마감까지만 기다린다: 남은 시간 150ms, 서버는 2초 뒤에 답한다
            jev.respond(FakeJev.Reply.ok(allowBody()).delayed(Duration.ofSeconds(2)));
            long started = System.nanoTime();
            GuardrailVerdict nearDeadline = client.check(request(), clock.now().plus(Duration.ofMillis(150))).toCompletableFuture()
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertFailed(nearDeadline, GuardrailVerdict.Failure.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));

            // 시한 안에 오는 답은 시한 경계 직전의 마감에서도 받는다
            jev.respond(FakeJev.Reply.ok(allowBody()));
            assertThat(client.check(request(), clock.now().plus(Duration.ofMillis(799))).toCompletableFuture()
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS).isAllow()).isTrue();
        }
    }

    @Test
    void transportFailuresAreBlockingResultsWithoutRetryAndRedirectsAreNotFollowed() throws Exception {
        try (FakeJev elsewhere = FakeJev.start(); JevGuardrailClient client = client(BUDGET)) {
            jev.respond(FakeJev.Reply.status(500));
            assertFailed(check(client), GuardrailVerdict.Failure.TRANSPORT_ERROR);
            assertThat(jev.requestCount()).as("500에도 재시도 없음").isEqualTo(1);

            jev.respond(FakeJev.Reply.status(429));
            assertFailed(check(client), GuardrailVerdict.Failure.TRANSPORT_ERROR);

            jev.respond(FakeJev.Reply.redirect(elsewhere.baseUrl() + JevGuardrailClient.CHECK_PATH));
            assertFailed(check(client), GuardrailVerdict.Failure.TRANSPORT_ERROR);
            assertThat(elsewhere.requestCount()).as("리다이렉트를 따르지 않는다").isZero();

            jev.respond(FakeJev.Reply.ok("x".repeat(JevGuardrailClient.MAX_RESPONSE_BYTES + 10)));
            assertFailed(check(client), GuardrailVerdict.Failure.TRANSPORT_ERROR);
        }
        String closedBase = jev.baseUrl();
        jev.close();
        try (JevGuardrailClient client = client(closedBase, MODEL, BUDGET, JevHosts.Mode.TEST)) {
            assertFailed(check(client), GuardrailVerdict.Failure.TRANSPORT_ERROR);
        }
    }

    @Test
    void malformedAnswersAreInvalidJsonNotAllow() throws Exception {
        List<String> bad = List.of(
                "not json",
                "[1,2]",
                "null",
                "{}",
                answer("MAYBE", "[]", "[]", "OK", POLICY, ""),
                answer("ALLOW", "[\"UNKNOWN_CATEGORY\"]", "[]", "OK", POLICY, ""),
                answer("ALLOW", "\"GENERALIZATION\"", "[]", "OK", POLICY, ""),
                answer("ALLOW", "[]", "[\"lower_case\"]", "OK", POLICY, ""),
                answer("ALLOW", "[]", "[]", "bad reason!", POLICY, ""),
                "{\"decision\":\"ALLOW\",\"categories\":[],\"reasonCode\":\"OK\"}",
                answer("ALLOW", "[]", "[]", "OK", POLICY, ",\"reviewNeeded\":\"yes\""),
                answer("ALLOW", "[]", "[]", "OK", POLICY, ",\"reviewNeeded\":true"));
        try (JevGuardrailClient client = client(BUDGET)) {
            for (String body : bad) {
                jev.respond(FakeJev.Reply.ok(body));
                GuardrailVerdict verdict = check(client);
                assertFailed(verdict, GuardrailVerdict.Failure.INVALID_JSON);
                assertThat(verdict.eventDecision()).isEqualTo(GuardrailEventRecord.Decision.ERROR);
            }
        }
        assertThat(jev.requestCount()).as("각 검사는 호출 한 번, 재시도 없음").isEqualTo(bad.size());
    }

    @Test
    void aDifferentPolicyVersionInTheAnswerIsAPolicyMismatchEvenForAnAllow() throws Exception {
        jev.respond(FakeJev.Reply.ok(answer("ALLOW", "[]", "[]", "OK", "guardrail-v0", "")));
        try (JevGuardrailClient client = client(BUDGET)) {
            assertFailed(check(client), GuardrailVerdict.Failure.POLICY_MISMATCH);
        }
    }

    @Test
    void itRefusesToSendWhenTheKeyIsBlankTheModelIsAMovingAliasOrThePolicyHasNoPrompt() throws Exception {
        try (JevGuardrailClient client = client(BUDGET)) {
            key.set(" ");
            assertThat(client.available()).isFalse();
            assertFailed(check(client), GuardrailVerdict.Failure.UNAVAILABLE);
            key.set(null);
            assertFailed(check(client), GuardrailVerdict.Failure.UNAVAILABLE);
            key.set(FAKE_KEY);
            assertThat(client.available()).isTrue();

            GuardrailRequest otherPolicy = GuardrailRequest.of(GuardrailRequest.Mode.FREE_TALK, "안녕", "other-version", List.of());
            GuardrailVerdict unknownPolicy = client.check(otherPolicy, clock.now().plus(BUDGET)).toCompletableFuture()
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertThat(unknownPolicy.failure()).isEqualTo(GuardrailVerdict.Failure.INVALID_REQUEST);
        }
        try (JevGuardrailClient alias = client(jev.baseUrl(), "jev-latest", BUDGET, JevHosts.Mode.TEST)) {
            assertThat(alias.available()).isFalse();
            assertFailed(check(alias), GuardrailVerdict.Failure.UNAVAILABLE);
        }
        assertThat(jev.requestCount()).as("거절한 검사는 공급자를 부르지 않는다").isZero();
    }

    @Test
    void aHostOutsideTheAllowListIsRejectedRightBeforeSendingEvenWithAWorkingServer() throws Exception {
        jev.respond(FakeJev.Reply.ok(allowBody()));
        // 루프백 서버는 STRICT(허용 호스트 + https만)에서 허용 밖이다
        try (JevGuardrailClient strict = client(jev.baseUrl(), MODEL, BUDGET, JevHosts.Mode.STRICT)) {
            assertThat(strict.available()).isFalse();
            assertFailed(check(strict), GuardrailVerdict.Failure.TRANSPORT_ERROR);
        }
        try (JevGuardrailClient garbage = client("not a url", MODEL, BUDGET, JevHosts.Mode.TEST)) {
            assertFailed(check(garbage), GuardrailVerdict.Failure.TRANSPORT_ERROR);
        }
        assertThat(jev.requestCount()).isZero();
    }

    @Test
    void theKeyIsReadPerCallAndItsValueNeverAppearsInTheBodyTheResultsOrTheLogs() throws Exception {
        jev.respond(FakeJev.Reply.ok(allowBody()));
        try (JevGuardrailClient client = client(BUDGET)) {
            GuardrailVerdict first = check(client);
            key.set("second-fake-key-u13");
            GuardrailVerdict second = check(client);

            assertThat(jev.requests().get(0).authorization()).isEqualTo("Bearer " + FAKE_KEY);
            assertThat(jev.requests().get(1).authorization()).as("보관하지 않고 호출 때마다 읽는다").isEqualTo("Bearer second-fake-key-u13");
            List<String> surfaces = new ArrayList<>(List.of(first.toString(), second.toString(), client.toString()));
            jev.requests().forEach(recorded -> surfaces.add(recorded.body()));
            surfaces.forEach(text -> assertThat(text).doesNotContain(FAKE_KEY).doesNotContain("second-fake-key-u13"));
        }
        assertThat(logs.lines()).as("어댑터는 아무것도 기록하지 않는다").noneMatch(line -> line.contains(FAKE_KEY) || line.contains("second-fake-key"));
        assertThat(logs.lines()).noneMatch(line -> line.contains("같이 해 보자"));
    }

    /** 계약 스키마 파일의 첫 예시를 읽는 도우미. */
    private static final class ContractExamples {

        @SuppressWarnings("unchecked")
        static String firstExample(String schemaFile) throws IOException {
            java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();
            Map<String, Object> document = JSON.read(java.nio.file.Files.readString(root.resolve("contracts/schemas/" + schemaFile)),
                    Map.class);
            return JSON.write(((List<Object>) document.get("examples")).getFirst());
        }
    }

    /** 가짜 제브 서버(루프백 무작위 포트, 대본 순서대로 답하고 마지막을 되풀이한다). 실제 제브가 아니다(R-F1). */
    private static final class FakeJev implements AutoCloseable {

        record Reply(int status, String body, Map<String, String> headers, Duration delay) {

            static Reply ok(String body) {
                return new Reply(200, body, Map.of(), Duration.ZERO);
            }

            static Reply status(int status) {
                return new Reply(status, "{}", Map.of(), Duration.ZERO);
            }

            static Reply redirect(String location) {
                return new Reply(302, "", Map.of("Location", location), Duration.ZERO);
            }

            Reply delayed(Duration by) {
                return new Reply(status, body, headers, by);
            }
        }

        record Recorded(String method, String path, String authorization, String body) {

            @SuppressWarnings("unchecked")
            Map<String, Object> json() {
                return JSON.read(body, Map.class);
            }
        }

        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final List<Reply> script = new CopyOnWriteArrayList<>();
        private final List<Recorded> requests = new CopyOnWriteArrayList<>();
        private int next;

        private FakeJev(HttpServer server) {
            this.server = server;
        }

        static FakeJev start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                FakeJev fake = new FakeJev(server);
                server.createContext("/", fake::handle);
                server.setExecutor(fake.executor);
                server.start();
                return fake;
            } catch (IOException e) {
                throw new IllegalStateException("가짜 제브 서버를 띄우지 못했다", e);
            }
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        synchronized void respond(Reply... replies) {
            script.clear();
            script.addAll(List.of(replies));
            next = 0;
        }

        List<Recorded> requests() {
            return requests;
        }

        int requestCount() {
            return requests.size();
        }

        private synchronized Reply nextReply() {
            if (script.isEmpty()) {
                return Reply.status(500);
            }
            Reply reply = script.get(Math.min(next, script.size() - 1));
            next++;
            return reply;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            Reply reply = nextReply();
            try {
                if (!reply.delay().isZero()) {
                    Thread.sleep(reply.delay().toMillis());
                }
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException clientGone) {
                // 시간 초과로 클라이언트가 먼저 끊은 연결이다(시험 대역의 정상 경로)
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
