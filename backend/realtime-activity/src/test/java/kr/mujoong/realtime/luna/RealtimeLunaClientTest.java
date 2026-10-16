package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.luna.LunaResponsesStub.Reply;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Realtime 판정 호출 (U9 BR1.6 · BR1.8 · BR2.4 · BR2.5 · BR3.1 · BR4.1 ~ BR4.4 · BR5.1 · BR6.1 · BR6.2 · NFR1.1 · NFR2.1 · NFR3.3 ·
 * NFR3.5 · NFR4.1). 공급자는 루프백 대역, 키는 가짜 문자열, 입력은 합성 값이다(R-F1 · R-F3). 증거 종류: 테스트 대역(R-M11).
 */
@Tag("u9")
class RealtimeLunaClientTest {

    private static final String MODEL = "gpt-6-luna";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private LunaResponsesStub stub;
    private MutableClock clock;
    private LogCapture capture;
    private EventLogger events;
    private RealtimeLunaClient client;

    @BeforeEach
    void setUp() {
        stub = LunaResponsesStub.start();
        clock = new MutableClock();
        capture = LogCapture.detached();
        events = TestEventLoggers.strict(clock, capture, RealtimeMetrics.inMemory());
        client = RealtimeLunaTestFixtures.client(stub.baseUrl(), Duration.ofSeconds(2), events, clock);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private LunaRequest request(Map<String, Object> input) {
        return new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "judgment-v1", "선택지 중 하나를 고른다.", input, Map.of(),
                clock.now().plusSeconds(10));
    }

    private LunaRequest request() {
        return request(RealtimeLunaTestFixtures.judgmentInput("그거 줘."));
    }

    private static LunaException failure(CompletableFuture<LunaResult> future) {
        try {
            future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(LunaException.class);
            return (LunaException) e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("실패해야 한다");
    }

    @Test
    void aValidJudgmentCompletesAsynchronouslyWithTheProbabilitiesUntouched() throws Exception {
        String output = RealtimeLunaTestFixtures.example("ai-judgment-output.schema.json");
        stub.respond(Reply.ok(MODEL, output));

        LunaResult result = client.call(request()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(result.outputJson()).isEqualTo(output).contains("0.71");
        assertThat(result.responseModel()).isEqualTo(MODEL);
        assertThat(result.attemptsUsed()).isEqualTo(1);
        assertThat(result.toString()).isEqualTo("LunaResult[redacted]");
        Map<String, Object> body = stub.requests().getFirst().json();
        assertThat(stub.requests().getFirst().violations()).isEmpty();
        assertThat(body).containsEntry("store", false).doesNotContainKey("temperature")
                .containsEntry("instructions", "선택지 중 하나를 고른다.");
        assertThat((String) body.get("input")).contains("그거 줘.").doesNotContain("선택지 중 하나를");
        assertThat(LogCapture.attrs(capture.events(EventCode.LUNA_CALL).getFirst()))
                .containsEntry("lunaPurpose", "JUDGMENT_LIVE").containsEntry("lunaOutcome", "OK").containsEntry("attempt", 1)
                .containsEntry("sentFields", "judgmentPoint,optionSetVersion,options,childUtterance,recentTurns,criteria")
                .doesNotContainKey("responseModel").containsKey("latencyMs");
    }

    @Test
    void everyFailureIsReturnedAfterExactlyOneAttempt() {
        List<Reply> replies = List.of(Reply.status(500), Reply.rateLimited(1), Reply.status(401), Reply.status(400),
                Reply.incomplete(MODEL), Reply.emptyOutput(MODEL), Reply.refusal(MODEL),
                Reply.noModel(RealtimeLunaTestFixtures.example("ai-judgment-output.schema.json")),
                Reply.ok(MODEL, "{\"first\":{\"option\":\"FULL\",\"probability\":2}}"), Reply.json(200, "<html>"),
                Reply.json(200, "{\"status\":\"cancelled\",\"model\":\"m\"}"), Reply.redirect("https://x.invalid/"));
        List<LunaFailureReason> expected = List.of(LunaFailureReason.SERVER_ERROR, LunaFailureReason.RATE_LIMITED,
                LunaFailureReason.AUTH_FAILED, LunaFailureReason.REQUEST_REJECTED, LunaFailureReason.LENGTH_EXCEEDED,
                LunaFailureReason.EMPTY_OUTPUT, LunaFailureReason.REFUSED, LunaFailureReason.MODEL_MISSING,
                LunaFailureReason.SCHEMA_INVALID, LunaFailureReason.SERVER_ERROR, LunaFailureReason.SERVER_ERROR,
                LunaFailureReason.REQUEST_REJECTED);
        List<LunaFailureReason> actual = new ArrayList<>();

        for (Reply reply : replies) {
            stub.respond(reply);
            LunaException failure = failure(client.call(request()).toCompletableFuture());
            assertThat(failure.attemptsUsed()).isEqualTo(1);
            actual.add(failure.reason());
        }

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(stub.requestCount()).as("NONE: 재시도 없음(BR3.1)").isEqualTo(replies.size());
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(replies.size());
    }

    @Test
    void theLiveBudgetBoundsTheAttemptAndCancelsTheRequest() {
        RealtimeLunaClient tight = RealtimeLunaTestFixtures.client(stub.baseUrl(), Duration.ofMillis(300), events, clock);
        stub.respond(Reply.ok(MODEL, RealtimeLunaTestFixtures.example("ai-judgment-output.schema.json"))
                .delayed(Duration.ofSeconds(3)));
        long started = System.nanoTime();

        LunaException timeout = failure(tight.call(request()).toCompletableFuture());

        assertThat(timeout.reason()).isEqualTo(LunaFailureReason.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("live-budget 300ms 안에서 끝남").isLessThan(Duration.ofSeconds(2));
        assertThat(LogCapture.attrs(capture.events(EventCode.LUNA_CALL).getFirst())).containsEntry("lunaOutcome", "TIMEOUT");
    }

    @Test
    void aCallerCancellationCancelsTheInFlightRequest() throws Exception {
        stub.respond(Reply.ok(MODEL, "{}").delayed(Duration.ofSeconds(5)));

        CompletableFuture<LunaResult> pending = client.call(request()).toCompletableFuture();
        assertThat(pending.cancel(true)).isTrue();

        assertThat(pending.isCancelled()).isTrue();
        assertThat(stub.requestCount()).isLessThanOrEqualTo(1);
    }

    @Test
    void requestSchemaViolationsForbiddenInputAndExpiredDeadlinesNeverCallTheProvider() {
        Map<String, Object> extra = new LinkedHashMap<>(RealtimeLunaTestFixtures.judgmentInput("x"));
        extra.put("guardianNote", "보호자 메모");
        extra.put("unknownField", "x");
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("x", new RealtimeLunaInputAndSchemaTest.Unserializable());
        LunaRequest expired = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "judgment-v1", "i",
                RealtimeLunaTestFixtures.judgmentInput("x"), Map.of(), clock.now());

        assertThat(failure(client.call(request(extra)).toCompletableFuture()).reason())
                .as("요청 스키마 위반(additionalProperties false)").isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        assertThat(failure(client.call(request(broken)).toCompletableFuture()).reason()).isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        LunaException noTime = failure(client.call(expired).toCompletableFuture());

        assertThat(noTime.reason()).isEqualTo(LunaFailureReason.TIMEOUT);
        assertThat(noTime.attemptsUsed()).isZero();
        assertThat(stub.requestCount()).isZero();
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(3)
                .allSatisfy(line -> assertThat(LogCapture.attrs(line)).containsEntry("attempt", 0));
        assertThat(capture.anyLineContains("보호자 메모")).isFalse();
    }

    @Test
    void aRefusalBeforeSendingIsNotCountedAsAnAttempt() {
        LunaTransport strictHost = RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.STRICT);
        LunaTransport noKey = new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> null, 1000);
        stub.respond(Reply.ok(MODEL, RealtimeLunaTestFixtures.example("ai-judgment-output.schema.json")));

        LunaException rejected = failure(clientWith(strictHost).call(request()).toCompletableFuture());
        LunaException unauthorized = failure(clientWith(noKey).call(request()).toCompletableFuture());

        assertThat(rejected.reason()).isEqualTo(LunaFailureReason.REQUEST_REJECTED);
        assertThat(unauthorized.reason()).isEqualTo(LunaFailureReason.AUTH_FAILED);
        assertThat(List.of(rejected.attemptsUsed(), unauthorized.attemptsUsed())).as("검토 R-04: 보내지 않았으면 0").containsOnly(0);
        assertThat(stub.requestCount()).isZero();
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(2)
                .allSatisfy(line -> assertThat(LogCapture.attrs(line)).containsEntry("attempt", 0));
        assertThat(strictHost.rejectBeforeSend(Duration.ofSeconds(1))).contains(LunaFailureReason.REQUEST_REJECTED);
        assertThat(RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST).rejectBeforeSend(Duration.ZERO))
                .contains(LunaFailureReason.TIMEOUT);
    }

    @Test
    void anUnexpectedRuntimeErrorBeforeSendingBecomesAFailedStageInsteadOfAThrow() {
        LunaSchemas broken = mock(LunaSchemas.class);
        when(broken.isValidRequest(any(), any())).thenThrow(new IllegalStateException("합성 입력 본문이 담긴 메시지"));
        RealtimeLunaClient fragile = new RealtimeLunaClient(RealtimeLunaTestFixtures.properties(stub.baseUrl()), Duration.ofSeconds(2),
                broken, new LunaInputSanitizer(JsonCodec.shared()), RealtimeLunaTestFixtures.transport(stub.baseUrl(),
                LunaHosts.Mode.TEST), events, clock, JsonCodec.shared());

        AtomicReference<CompletionStage<LunaResult>> returned = new AtomicReference<>();
        assertThatCode(() -> returned.set(fragile.call(request()))).as("검토 R-05: 호출 스레드로 던지지 않는다").doesNotThrowAnyException();
        LunaException failure = failure(returned.get().toCompletableFuture());

        assertThat(failure.reason()).isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        assertThat(failure.attemptsUsed()).isZero();
        assertThat(failure.getMessage()).doesNotContain("합성 입력");
        assertThat(stub.requestCount()).isZero();
        assertThat(capture.events(EventCode.APP_ERROR)).singleElement()
                .satisfies(line -> assertThat(LogCapture.attrs(line)).containsEntry("errorKind", "IllegalStateException"));
        assertThat(capture.anyLineContains("합성 입력")).isFalse();

        assertThat(failure(client.call(null).toCompletableFuture()).reason()).as("null 요청도 실패 완료")
                .isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        EventLogger failingLog = mock(EventLogger.class);
        doThrow(new IllegalStateException("기록 실패 대역")).when(failingLog).appError(any(), any(Throwable.class));
        RealtimeLunaClient silent = RealtimeLunaTestFixtures.client(stub.baseUrl(), Duration.ofSeconds(2), failingLog, clock);
        assertThat(failure(silent.call(null).toCompletableFuture()).reason()).as("기록이 실패해도 던지지 않는다")
                .isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
    }

    private RealtimeLunaClient clientWith(LunaTransport transport) {
        return new RealtimeLunaClient(RealtimeLunaTestFixtures.properties(stub.baseUrl()), Duration.ofSeconds(2),
                LunaSchemas.loadDefault(JsonCodec.shared()), new LunaInputSanitizer(JsonCodec.shared()), transport, events, clock,
                JsonCodec.shared());
    }

    @Test
    void auditLinesCarryOnlyTheAllowedAttributesAndNeverTheBodyOrKey() {
        stub.respond(Reply.ok("gpt-6-luna-judge-2026", RealtimeLunaTestFixtures.example("ai-judgment-output.schema.json")),
                Reply.ok(MODEL, "{\"secretText\":\"대역 본문 문장\"}"));

        client.call(request(RealtimeLunaTestFixtures.judgmentInput("민감한 합성 전사"))).toCompletableFuture().join();
        failure(client.call(request()).toCompletableFuture());

        assertThat(capture.anyLineContains("민감한 합성 전사")).isFalse();
        assertThat(capture.anyLineContains("대역 본문 문장")).isFalse();
        assertThat(capture.anyLineContains(RealtimeLunaTestFixtures.FAKE_KEY)).isFalse();
        assertThat(capture.events(EventCode.LUNA_CALL)).allSatisfy(line -> assertThat(LogCapture.attrs(line).keySet()).isSubsetOf(
                "lunaPurpose", "lunaOutcome", "model", "responseModel", "promptVersion", "sentFields", "latencyMs", "attempt"));
        assertThat(LogCapture.attrs(capture.events(EventCode.LUNA_CALL).getFirst()))
                .containsEntry("responseModel", "gpt-6-luna-judge-2026");
        assertThat(capture.events(EventCode.LUNA_CALL).get(1).get("level")).isEqualTo("WARN");
    }

    @Test
    void transportRefusesForeignHostsOversizedBodiesAndCarriesTheKeyOnlyInTheHeader() throws Exception {
        try (LunaResponsesStub big = LunaResponsesStub.start()) {
            // 전송 계층만 보는 시험이라 요청 본문은 최소 모양("{}")이다. 요청 검사는 호출부 시험이 본다
            big.withoutRequestCheck().respond(Reply.oversized(MODEL, 1_100_000));
            LunaTransport strict = RealtimeLunaTestFixtures.transport(big.baseUrl(), LunaHosts.Mode.STRICT);
            LunaTransport test = RealtimeLunaTestFixtures.transport(big.baseUrl(), LunaHosts.Mode.TEST);
            LunaTransport noKey = new LunaTransport(big.baseUrl(), LunaHosts.Mode.TEST, () -> null, 10);

            assertThat(reason(strict.send("{}", Duration.ofSeconds(1)))).isEqualTo(LunaFailureReason.REQUEST_REJECTED);
            assertThat(reason(noKey.send("{}", Duration.ofSeconds(1)))).isEqualTo(LunaFailureReason.AUTH_FAILED);
            assertThat(reason(test.send("{}", Duration.ZERO))).isEqualTo(LunaFailureReason.TIMEOUT);
            assertThat(big.requestCount()).isZero();
            assertThat(reason(test.send("{}", Duration.ofSeconds(10)))).isEqualTo(LunaFailureReason.LENGTH_EXCEEDED);
            assertThat(big.requests().getFirst().header("authorization")).isEqualTo("Bearer " + RealtimeLunaTestFixtures.FAKE_KEY);
        }
        LunaTransport closed = RealtimeLunaTestFixtures.transport(URI.create("http://127.0.0.1:9/v1/"), LunaHosts.Mode.TEST);
        assertThat(closed.endpoint().toString()).isEqualTo("http://127.0.0.1:9/v1/responses");
        assertThat(reason(closed.send("{}", Duration.ofSeconds(5)))).isEqualTo(LunaFailureReason.TRANSPORT_ERROR);
        assertThat(LunaTransport.retryAfter(HttpHeaders.of(Map.of("Retry-After", List.of("3")), (a, b) -> true)))
                .contains(Duration.ofSeconds(3));
        assertThat(LunaTransport.retryAfter(HttpHeaders.of(Map.of("Retry-After", List.of("soon")), (a, b) -> true))).isEmpty();
        assertThatThrownBy(() -> new LunaTransport(URI.create("http://127.0.0.1/v1"), LunaHosts.Mode.TEST, () -> "k", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LunaTransport.RawResponse(200, "본문", java.util.Optional.empty()).toString()).doesNotContain("본문");
    }

    @Test
    void recordsValidateTheirValues() {
        assertThatThrownBy(() -> new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "v 1", "i", Map.of(), Map.of(), clock.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "v1", "", Map.of(), Map.of(), clock.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "v1", "i", Map.of(), null, clock.now()).toString())
                .isEqualTo("LunaRequest[redacted]");
        assertThatThrownBy(() -> new LunaResult("{}", MODEL, "v1", Duration.ZERO, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaException(LunaFailureReason.TIMEOUT, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LunaFailureReason.SERVER_ERROR.isTransient()).isTrue();
        assertThat(LunaFailureReason.AUTH_FAILED.isTransient()).isFalse();
        assertThat(JsonCodec.shared()).isNotNull();
    }

    private static LunaFailureReason reason(CompletableFuture<LunaTransport.RawResponse> future) {
        try {
            future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return ((LunaException) e.getCause()).reason();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("실패해야 한다");
    }
}
