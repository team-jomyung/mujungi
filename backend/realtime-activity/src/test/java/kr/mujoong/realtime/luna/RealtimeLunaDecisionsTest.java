package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
 * Realtime Decisions 호출 (FT-1023-d). Responses 호출과 같은 단계(정리 · 스키마 · 시한 · 보내기 전 거절 · 분류 · {@code LUNA_CALL})를 지나는지와
 * Decisions 본문 · 응답 검증을 본다. 공급자는 {@link LunaDecisionsStub} 루프백 대역, 키는 가짜 문자열, 입력은 합성 값이다(R-F1 · R-F3). 증거
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
        return new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "prompt-v1.decisions-v1", "아이의 가장 마지막 말 하나만 판정한다.", input, aliases,
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
        assertThat(decision.promptVersion()).isEqualTo("prompt-v1.decisions-v1");
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
                .containsEntry("promptVersion", "prompt-v1.decisions-v1")
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
        LunaRequest expired = new LunaRequest(LunaPurpose.JUDGMENT_LIVE, "prompt-v1.decisions-v1", "i",
                RealtimeLunaTestFixtures.judgmentInput("x"), Map.of(), clock.now());
        RealtimeLunaClient strictHost = new RealtimeLunaClient(RealtimeLunaTestFixtures.properties(stub.baseUrl()), Duration.ofSeconds(2),
                LunaSchemas.loadDefault(RealtimeLunaTestFixtures.JSON), new LunaInputSanitizer(RealtimeLunaTestFixtures.JSON),
                RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.STRICT), events, clock, RealtimeLunaTestFixtures.JSON);
        RealtimeLunaClient noKey = new RealtimeLunaClient(RealtimeLunaTestFixtures.properties(stub.baseUrl()), Duration.ofSeconds(2),
                LunaSchemas.loadDefault(RealtimeLunaTestFixtures.JSON), new LunaInputSanitizer(RealtimeLunaTestFixtures.JSON),
                new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> " ", 1000), events, clock, RealtimeLunaTestFixtures.JSON);

        assertThat(failure(client.decide(request(extra, Map.of()), question()).toCompletableFuture()).reason())
                .isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        assertThat(failure(client.decide(expired, question()).toCompletableFuture()).reason()).isEqualTo(LunaFailureReason.TIMEOUT);
        assertThat(failure(strictHost.decide(request(), question()).toCompletableFuture()).reason())
                .isEqualTo(LunaFailureReason.REQUEST_REJECTED);
        assertThat(failure(noKey.decide(request(), question()).toCompletableFuture()).reason()).isEqualTo(LunaFailureReason.AUTH_FAILED);
        assertThat(failure(client.decide(request(), null).toCompletableFuture()).reason())
                .as("준비 중 예기치 못한 오류도 던지지 않는다").isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);

        assertThat(stub.requestCount()).isZero();
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(4)
                .allSatisfy(line -> assertThat(LogCapture.attrs(line)).containsEntry("attempt", 0));
        assertThat(capture.anyLineContains("보호자 메모")).isFalse();
    }

    @Test
    void theDecisionsEndpointSharesTheBaseUrlAndHostRules() {
        LunaTransport transport = RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST);

        assertThat(transport.endpoint(LunaTransport.Endpoint.DECISIONS).toString()).isEqualTo(stub.baseUrl() + "/decisions");
        assertThat(transport.endpoint().toString()).isEqualTo(stub.baseUrl() + "/responses");
        assertThat(RealtimeLunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.STRICT)
                .rejectBeforeSend(LunaTransport.Endpoint.DECISIONS, Duration.ofSeconds(1))).contains(LunaFailureReason.REQUEST_REJECTED);
    }
}
