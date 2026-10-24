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
import java.util.Optional;
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
import kr.mujoong.testsupport.luna.LunaDecisionsStub;
import kr.mujoong.testsupport.luna.LunaDecisionsStub.Reply;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Realtime 판정 호출 — Decisions API (U9 BR1.6 · BR2.4 · BR2.5 · BR3.1 · BR4.1 ~ BR4.4 · BR5.1 · BR6.1 · BR6.2 · NFR3.3 · NFR3.5,
 * FT-1023-d). 정리 · 요청 스키마 · 시한 · 보내기 전 거절 · 전송 · 분류 · {@code LUNA_CALL} 단계와 Decisions 본문 · 응답 검증, 전송 계층 규칙을 본다. 공급자는 {@link LunaDecisionsStub} 루프백 대역, 키는 가짜 문자열, 입력은 합성 값이다(R-F1 · R-F3). 증거
 * 종류: 테스트 대역(R-M11) — 공식 문서 형식 기준이며 실공급자 성공이 아니다.
 */
@Tag("luna")
class RealtimeLunaDecisionsTest {

    private static final String MODEL = "gpt-6-luna";
    private static final String NAME = "judgment";
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final List<String> OPTIONS = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE");

    private LunaDecisionsStub stub;
    private MutableClock clock;
    private LogCapture capture;
    private EventLogger events;
    private RealtimeLunaClient client;

    @BeforeEach
    void setUp() {
        stub = LunaDecisionsStub.start();
        clock = new MutableClock();
        capture = LogCapture.detached();
        events = TestEventLoggers.strict(clock, capture, RealtimeMetrics.inMemory());
        client = RealtimeLunaTestFixtures.client(stub.baseUrl(), Duration.ofSeconds(2), events, clock);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private LunaRequest request(Map<String, Object> input, Map<String, String> aliases) {
        return new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "prompt-service-v3", "아이의 가장 마지막 말 하나만 판정한다.", input, aliases,
                clock.now().plusSeconds(10));
    }

    private LunaRequest request() {
        return request(RealtimeLunaTestFixtures.judgmentInput("그거 줘."), Map.of());
    }

    private static LunaDecisionQuestion question() {
        List<LunaDecisionQuestion.Choice> choices = new ArrayList<>();
        for (String option : OPTIONS) {
            choices.add(new LunaDecisionQuestion.Choice(option, option.equals("FULL") ? "핵심 기능이 다 드러남" : null));
        }
        return new LunaDecisionQuestion(NAME, choices);
    }

    private static Map<String, Double> probabilities(double... values) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            map.put(OPTIONS.get(i), values[i]);
        }
        return map;
    }

    private static Reply confirming() {
        return Reply.choice(MODEL, NAME, "FULL", probabilities(0.8, 0.1, 0.04, 0.03, 0.02, 0.01));
    }

    private static LunaException failure(CompletableFuture<LunaDecision> future) {
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
    @SuppressWarnings("unchecked")
    void aValidDecisionSendsTheMinimalFieldsToTheDecisionsEndpointAndReturnsTheProbabilities() throws Exception {
        stub.respond(confirming());

        LunaDecision decision = client.decide(request(), question()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(decision.choice()).isEqualTo("FULL");
        assertThat(decision.probabilities()).extracting(LunaDecision.Probability::value).containsExactlyElementsOf(OPTIONS);
        assertThat(decision.probabilities().getFirst().probability()).isEqualTo(0.8);
        assertThat(decision.responseModel()).isEqualTo(MODEL);
        assertThat(decision.promptVersion()).isEqualTo("prompt-service-v3");
        assertThat(decision.toString()).isEqualTo("LunaDecision[redacted]");

        LunaResponsesStub.RecordedRequest sent = stub.requests().getFirst();
        assertThat(sent.path()).isEqualTo(LunaDecisionsStub.PATH);
        assertThat(sent.violations()).isEmpty();
        assertThat(sent.header("Authorization")).isEqualTo("Bearer " + RealtimeLunaTestFixtures.FAKE_KEY);
        Map<String, Object> body = sent.json();
        assertThat(body.keySet()).containsExactly("model", "input", "questions");
        assertThat(body).containsEntry("model", MODEL);
        List<Map<String, Object>> questions = (List<Map<String, Object>>) body.get("questions");
        assertThat(questions).hasSize(1);
        assertThat(questions.getFirst()).containsEntry("type", "choice").containsEntry("name", NAME)
                .containsEntry("instructions", "아이의 가장 마지막 말 하나만 판정한다.");
        List<Map<String, Object>> choices = (List<Map<String, Object>>) questions.getFirst().get("choices");
        assertThat(choices).extracting(choice -> choice.get("value")).containsExactlyElementsOf(OPTIONS);
        assertThat(choices.getFirst()).containsEntry("description", "핵심 기능이 다 드러남");
        assertThat(choices.get(1)).doesNotContainKey("description");
        assertThat(String.valueOf(questions.getFirst().get("instructions"))).doesNotContain("그거 줘.");
        List<Map<String, Object>> input = (List<Map<String, Object>>) body.get("input");
        assertThat(input).hasSize(1);
        assertThat(input.getFirst()).containsEntry("role", "user");
        Map<String, Object> part = ((List<Map<String, Object>>) input.getFirst().get("content")).getFirst();
        assertThat(part).containsEntry("type", "input_json");
        assertThat(((Map<String, Object>) part.get("data")).keySet())
                .containsExactly("judgmentPoint", "optionSetVersion", "options", "childUtterance", "recentTurns", "criteria");
        assertThat(LogCapture.attrs(capture.events(EventCode.LUNA_CALL).getFirst()))
                .containsEntry("lunaPurpose", "JUDGMENT_LIVE").containsEntry("lunaOutcome", "OK").containsEntry("attempt", 1)
                .containsEntry("promptVersion", "prompt-service-v3")
                .containsEntry("sentFields", "judgmentPoint,optionSetVersion,options,childUtterance,recentTurns,criteria")
                .doesNotContainKey("responseModel");
        assertThat(capture.anyLineContains("그거 줘.")).isFalse();
    }

    @Test
    void theChildNameIsReplacedAndForbiddenKeysAreDroppedBeforeLeaving() throws Exception {
        stub.respond(confirming());
        Map<String, Object> input = new LinkedHashMap<>(RealtimeLunaTestFixtures.judgmentInput("하준이는 그거 줘."));

        client.decide(request(input, Map.of("하준", "아이")), question()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(stub.anyBodyContains("하준")).isFalse();
        assertThat(stub.anyBodyContains("아이이는 그거 줘.")).isTrue();
    }

    @Test
    void everyFailureIsReturnedAfterExactlyOneAttemptWithTheExistingReasonCodes() {
        Map<String, Object> noProbabilities = new LinkedHashMap<>();
        noProbabilities.put("type", "choice");
        noProbabilities.put("name", NAME);
        noProbabilities.put("choice", "FULL");
        List<Reply> replies = List.of(
                Reply.status(500), Reply.rateLimited(1), Reply.status(401), Reply.status(403), Reply.status(400),
                Reply.status(302).withHeader("Location", "https://x.invalid/"),
                Reply.json(200, "<html>"),
                Reply.answers(MODEL, List.of()),
                Reply.refusal(MODEL, NAME),
                Reply.choice(null, NAME, "FULL", probabilities(0.8, 0.1, 0.04, 0.03, 0.02, 0.01)),
                Reply.choice(MODEL, NAME, "MAYBE", probabilities(0.8, 0.1, 0.04, 0.03, 0.02, 0.01)),
                Reply.choice(MODEL, NAME, "FULL", probabilities(0.8, 0.1, 0.1)),
                Reply.choice(MODEL, NAME, "FULL", probabilities(1.2, 0.1, 0.04, 0.03, 0.02, 0.01)),
                Reply.choice(MODEL, NAME, "PARTIAL", probabilities(0.8, 0.1, 0.04, 0.03, 0.02, 0.01)),
                Reply.choice(MODEL, "other", "FULL", probabilities(0.8, 0.1, 0.04, 0.03, 0.02, 0.01)),
                Reply.answers(MODEL, List.of(noProbabilities)));
        List<LunaFailureReason> expected = List.of(
                LunaFailureReason.SERVER_ERROR, LunaFailureReason.RATE_LIMITED, LunaFailureReason.AUTH_FAILED,
                LunaFailureReason.AUTH_FAILED, LunaFailureReason.REQUEST_REJECTED,
                LunaFailureReason.REQUEST_REJECTED,
                LunaFailureReason.SERVER_ERROR,
                LunaFailureReason.EMPTY_OUTPUT,
                LunaFailureReason.REFUSED,
                LunaFailureReason.MODEL_MISSING,
                LunaFailureReason.SCHEMA_INVALID,
                LunaFailureReason.SCHEMA_INVALID,
                LunaFailureReason.SCHEMA_INVALID,
                LunaFailureReason.SCHEMA_INVALID,
                LunaFailureReason.EMPTY_OUTPUT,
                LunaFailureReason.SCHEMA_INVALID);
        List<LunaFailureReason> actual = new ArrayList<>();

        for (Reply reply : replies) {
            stub.respond(reply);
            LunaException failure = failure(client.decide(request(), question()).toCompletableFuture());
            assertThat(failure.attemptsUsed()).isEqualTo(1);
            actual.add(failure.reason());
        }

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(stub.requestCount()).as("NONE: 재시도 없음(BR3.1)").isEqualTo(replies.size());
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(replies.size());
    }

    @Test
    void theLiveBudgetBoundsTheAttempt() {
        RealtimeLunaClient tight = RealtimeLunaTestFixtures.client(stub.baseUrl(), Duration.ofMillis(300), events, clock);
        stub.respond(confirming().delayed(Duration.ofSeconds(3)));
        long started = System.nanoTime();

        LunaException timeout = failure(tight.decide(request(), question()).toCompletableFuture());

        assertThat(timeout.reason()).isEqualTo(LunaFailureReason.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(LogCapture.attrs(capture.events(EventCode.LUNA_CALL).getFirst())).containsEntry("lunaOutcome", "TIMEOUT");
    }

    @Test
    void aCallerCancellationCancelsTheInFlightRequest() {
        stub.respond(confirming().delayed(Duration.ofSeconds(5)));

        CompletableFuture<LunaDecision> pending = client.decide(request(), question()).toCompletableFuture();

        assertThat(pending.cancel(true)).isTrue();
        assertThat(pending.isCancelled()).isTrue();
        assertThat(stub.requestCount()).isLessThanOrEqualTo(1);
    }

    @Test
    void forbiddenInputExpiredDeadlinesAndPreSendRefusalsNeverCallTheProvider() {
        Map<String, Object> extra = new LinkedHashMap<>(RealtimeLunaTestFixtures.judgmentInput("x"));
        extra.put("guardianNote", "보호자 메모");
        extra.put("unknownField", "x");
        LunaRequest expired = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "prompt-service-v3", "i",
                RealtimeLunaTestFixtures.judgmentInput("x"), Map.of(), clock.now());
        RealtimeLunaClient strictHost = new RealtimeLunaClient(RealtimeLunaTestFixtures.properties(stub.baseUrl()), Duration.ofSeconds(2),
                LunaSchemas.loadDefault(RealtimeLunaTestFixtures.JSON), new LunaInputSanitizer(RealtimeLunaTestFixtures.JSON),
                RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.STRICT), events, clock, RealtimeLunaTestFixtures.JSON);
        RealtimeLunaClient noKey = new RealtimeLunaClient(RealtimeLunaTestFixtures.properties(stub.baseUrl()), Duration.ofSeconds(2),
                LunaSchemas.loadDefault(RealtimeLunaTestFixtures.JSON), new LunaInputSanitizer(RealtimeLunaTestFixtures.JSON),
                new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> " ", 1000), events, clock, RealtimeLunaTestFixtures.JSON);

        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("x", new RealtimeLunaInputAndSchemaTest.Unserializable());

        assertThat(failure(client.decide(request(extra, Map.of()), question()).toCompletableFuture()).reason())
                .as("요청 스키마 위반(additionalProperties false)").isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        assertThat(failure(client.decide(request(broken, Map.of()), question()).toCompletableFuture()).reason())
                .as("직렬화할 수 없는 입력").isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        LunaException noTime = failure(client.decide(expired, question()).toCompletableFuture());
        assertThat(noTime.reason()).isEqualTo(LunaFailureReason.TIMEOUT);
        assertThat(noTime.attemptsUsed()).as("검토 R-04: 보내지 않았으면 0").isZero();
        assertThat(failure(strictHost.decide(request(), question()).toCompletableFuture()).reason())
                .isEqualTo(LunaFailureReason.REQUEST_REJECTED);
        assertThat(failure(noKey.decide(request(), question()).toCompletableFuture()).reason()).isEqualTo(LunaFailureReason.AUTH_FAILED);
        assertThat(failure(client.decide(request(), null).toCompletableFuture()).reason())
                .as("준비 중 예기치 못한 오류도 던지지 않는다").isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);

        assertThat(stub.requestCount()).isZero();
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(5)
                .allSatisfy(line -> assertThat(LogCapture.attrs(line)).containsEntry("attempt", 0));
        assertThat(capture.anyLineContains("보호자 메모")).isFalse();
    }

    @Test
    void anUnexpectedRuntimeErrorBeforeSendingBecomesAFailedStageInsteadOfAThrow() {
        LunaSchemas broken = mock(LunaSchemas.class);
        when(broken.isValidRequest(any(), any())).thenThrow(new IllegalStateException("합성 입력 본문이 담긴 메시지"));
        RealtimeLunaClient fragile = new RealtimeLunaClient(RealtimeLunaTestFixtures.properties(stub.baseUrl()), Duration.ofSeconds(2),
                broken, new LunaInputSanitizer(JsonCodec.shared()), RealtimeLunaTestFixtures.transport(stub.baseUrl(),
                LunaHosts.Mode.TEST), events, clock, JsonCodec.shared());

        AtomicReference<CompletionStage<LunaDecision>> returned = new AtomicReference<>();
        assertThatCode(() -> returned.set(fragile.decide(request(), question()))).as("검토 R-05: 호출 스레드로 던지지 않는다")
                .doesNotThrowAnyException();
        LunaException failure = failure(returned.get().toCompletableFuture());

        assertThat(failure.reason()).isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        assertThat(failure.attemptsUsed()).isZero();
        assertThat(failure.getMessage()).doesNotContain("합성 입력");
        assertThat(stub.requestCount()).isZero();
        assertThat(capture.events(EventCode.APP_ERROR)).singleElement()
                .satisfies(line -> assertThat(LogCapture.attrs(line)).containsEntry("errorKind", "IllegalStateException"));
        assertThat(capture.anyLineContains("합성 입력")).isFalse();

        assertThat(failure(client.decide(null, question()).toCompletableFuture()).reason()).as("null 요청도 실패 완료")
                .isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        EventLogger failingLog = mock(EventLogger.class);
        doThrow(new IllegalStateException("기록 실패 대역")).when(failingLog).appError(any(), any(Throwable.class));
        RealtimeLunaClient silent = RealtimeLunaTestFixtures.client(stub.baseUrl(), Duration.ofSeconds(2), failingLog, clock);
        assertThat(failure(silent.decide(null, question()).toCompletableFuture()).reason()).as("기록이 실패해도 던지지 않는다")
                .isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
    }

    @Test
    void auditLinesCarryOnlyTheAllowedAttributesAndNeverTheBodyOrKey() {
        stub.respond(Reply.choice("gpt-6-luna-judge-2026", NAME, "FULL", probabilities(0.8, 0.1, 0.04, 0.03, 0.02, 0.01)),
                Reply.json(200, "{\"model\":\"m\",\"secretText\":\"대역 본문 문장\",\"answers\":[]}"));

        client.decide(request(RealtimeLunaTestFixtures.judgmentInput("민감한 합성 전사"), Map.of()), question()).toCompletableFuture()
                .join();
        failure(client.decide(request(), question()).toCompletableFuture());

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
    void theTransportSendsOnlyToTheDecisionsEndpointRefusesForeignHostsAndOversizedBodies() throws Exception {
        try (LunaDecisionsStub big = LunaDecisionsStub.start()) {
            // 전송 계층만 보는 시험이라 요청 본문은 최소 모양("{}")이다. 요청 검사는 호출부 시험이 본다
            big.withoutRequestCheck().respond(Reply.json(200, "{\"pad\":\"" + "x".repeat(1_100_000) + "\"}"));
            LunaTransport strict = RealtimeLunaTestFixtures.transport(big.baseUrl(), LunaHosts.Mode.STRICT);
            LunaTransport test = RealtimeLunaTestFixtures.transport(big.baseUrl(), LunaHosts.Mode.TEST);
            LunaTransport noKey = new LunaTransport(big.baseUrl(), LunaHosts.Mode.TEST, () -> null, 10);

            assertThat(test.endpoint().toString()).isEqualTo(big.baseUrl() + "/decisions");
            assertThat(reason(strict.send("{}", Duration.ofSeconds(1)))).isEqualTo(LunaFailureReason.REQUEST_REJECTED);
            assertThat(reason(noKey.send("{}", Duration.ofSeconds(1)))).isEqualTo(LunaFailureReason.AUTH_FAILED);
            assertThat(reason(test.send("{}", Duration.ZERO))).isEqualTo(LunaFailureReason.TIMEOUT);
            assertThat(strict.rejectBeforeSend(Duration.ofSeconds(1))).contains(LunaFailureReason.REQUEST_REJECTED);
            assertThat(test.rejectBeforeSend(Duration.ZERO)).contains(LunaFailureReason.TIMEOUT);
            assertThat(big.requestCount()).isZero();
            assertThat(reason(test.send("{}", Duration.ofSeconds(10)))).isEqualTo(LunaFailureReason.LENGTH_EXCEEDED);
            assertThat(big.requests().getFirst().path()).isEqualTo(LunaDecisionsStub.PATH);
            assertThat(big.requests().getFirst().header("authorization")).isEqualTo("Bearer " + RealtimeLunaTestFixtures.FAKE_KEY);
        }
        LunaTransport closed = RealtimeLunaTestFixtures.transport(URI.create("http://127.0.0.1:9/v1/"), LunaHosts.Mode.TEST);
        assertThat(closed.endpoint().toString()).isEqualTo("http://127.0.0.1:9/v1/decisions");
        assertThat(reason(closed.send("{}", Duration.ofSeconds(5)))).isEqualTo(LunaFailureReason.TRANSPORT_ERROR);
        assertThat(LunaTransport.retryAfter(HttpHeaders.of(Map.of("Retry-After", List.of("3")), (a, b) -> true)))
                .contains(Duration.ofSeconds(3));
        assertThat(LunaTransport.retryAfter(HttpHeaders.of(Map.of("Retry-After", List.of("soon")), (a, b) -> true))).isEmpty();
        assertThatThrownBy(() -> new LunaTransport(URI.create("http://127.0.0.1/v1"), LunaHosts.Mode.TEST, () -> "k", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LunaTransport.RawResponse(200, "본문", Optional.empty()).toString()).doesNotContain("본문");
    }

    @Test
    void recordsValidateTheirValues() {
        assertThatThrownBy(() -> new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "v 1", "i", Map.of(), Map.of(), clock.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "v1", "", Map.of(), Map.of(), clock.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "v1", "i", Map.of(), null, clock.now()).toString())
                .isEqualTo("LunaRequest[redacted]");
        assertThatThrownBy(() -> new LunaException(LunaFailureReason.TIMEOUT, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LunaFailureReason.SERVER_ERROR.isTransient()).isTrue();
        assertThat(LunaFailureReason.AUTH_FAILED.isTransient()).isFalse();
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
