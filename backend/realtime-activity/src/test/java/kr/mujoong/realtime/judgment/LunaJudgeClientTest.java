package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.luna.LunaException;
import kr.mujoong.realtime.luna.LunaFailureReason;
import kr.mujoong.realtime.luna.LunaHosts;
import kr.mujoong.realtime.luna.LunaInputSanitizer;
import kr.mujoong.realtime.luna.LunaProperties;
import kr.mujoong.realtime.luna.LunaSchemas;
import kr.mujoong.realtime.luna.LunaTransport;
import kr.mujoong.realtime.luna.RealtimeLunaClient;
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
 * Luna 판정 구현 (U11 BR1.1 · BR1.4 · BR1.7 · BR4.3). 공급자는 루프백 대역, 키는 가짜 문자열, 입력은 합성 값이다(R-F1 · R-F3). 증거 종류:
 * 테스트 대역(R-M11) — 실제 공급자 성공이 아니다.
 */
@Tag("u11")
class LunaJudgeClientTest {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final JudgmentPrompt PROMPT = new JudgmentPrompt("prompt-v1", "선택지 중 하나만 고른다.");
    private static final List<String> OPTIONS = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE");
    private static final String CONFIRMING = "{\"first\":{\"option\":\"FULL\",\"probability\":0.8},"
            + "\"second\":{\"option\":\"PARTIAL\",\"probability\":0.1}}";

    private LunaResponsesStub stub;
    private MutableClock clock;
    private LunaJudgeClient judge;

    @BeforeEach
    void setUp() {
        stub = LunaResponsesStub.start();
        clock = new MutableClock();
        judge = clientWithBudget(Duration.ofSeconds(4));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private LunaJudgeClient clientWithBudget(Duration budget) {
        URI base = stub.baseUrl();
        LunaProperties properties = new LunaProperties("gpt-6-luna", base, 1_000_000,
                new LunaProperties.Purposes(new LunaProperties.PurposeOptions(4000, "medium")), true);
        EventLogger events = TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory());
        RealtimeLunaClient luna = new RealtimeLunaClient(properties, budget, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(base, LunaHosts.Mode.TEST, () -> "test-key-not-real-u11", 1_000_000), events, clock, JSON);
        return new LunaJudgeClient(luna::call, PROMPT, new JudgeOutcomeValidator(0.6, 0.15, JSON), clock);
    }

    private JudgeRequest request(String utterance, Duration remaining) {
        return new JudgeRequest(JudgmentPoint.J2, "judgment-v1", OPTIONS, utterance,
                List.of(new JudgeRequest.RecentTurn(JudgeRequest.Speaker.PARTNER, "이것 봐, 높지?")),
                new JudgeRequest.Criteria("필요한 것과 이유를 함께 말해 부탁한다", "무엇이 필요한지와 왜 필요한지가 드러나면 충분히 전달"),
                Map.of(), false, clock.now().plus(remaining));
    }

    private JudgeOutcome run(JudgeRequest request) throws Exception {
        return judge.judge(request, UUID.randomUUID()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void aConfirmingAnswerCallsLunaExactlyOnceWithOnlyThePermittedFields() throws Exception {
        stub.respond(Reply.ok("gpt-6-luna", CONFIRMING));

        JudgeOutcome outcome = run(request("그거 줘.", Duration.ofSeconds(4)));

        assertThat(outcome.isConfirmed()).isTrue();
        assertThat(outcome.firstOption()).isEqualTo("FULL");
        assertThat(outcome.model()).isEqualTo("gpt-6-luna");
        assertThat(outcome.promptVersion()).isEqualTo("prompt-v1");
        assertThat(stub.requestCount()).isEqualTo(1);
        LunaResponsesStub.RecordedRequest sent = stub.requests().getFirst();
        assertThat(sent.violations()).isEmpty();
        assertThat(sent.json()).containsEntry("store", false).doesNotContainKey("temperature")
                .containsEntry("instructions", "선택지 중 하나만 고른다.");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = JSON.read((String) sent.json().get("input"), Map.class);
        assertThat(input.keySet()).containsExactlyInAnyOrder("judgmentPoint", "optionSetVersion", "options", "childUtterance",
                "recentTurns", "criteria");
        assertThat(input.get("options")).isEqualTo(OPTIONS);
    }

    @Test
    void theChildNameIsReplacedByTheAliasBeforeLeavingTheProcess() throws Exception {
        stub.respond(Reply.ok("gpt-6-luna", CONFIRMING));
        JudgeRequest withAlias = new JudgeRequest(JudgmentPoint.J1, "judgment-v1", List.of("PARTICIPATE", "REST"), "하준이는 할래",
                List.of(), null, Map.of("하준", "아이"), false, clock.now().plusSeconds(4));

        run(withAlias);

        assertThat(stub.anyBodyContains("하준")).isFalse();
        assertThat(stub.anyBodyContains("아이")).isTrue();
    }

    @Test
    void anAnswerArrivingJustBeforeTheFourSecondLimitIsStillUsed() throws Exception {
        stub.respond(Reply.ok("gpt-6-luna", CONFIRMING));

        JudgeOutcome outcome = run(request("그거 줘.", Duration.ofMillis(3_999)));

        assertThat(outcome.isConfirmed()).isTrue();
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void whenTheFourSecondsAreAlreadyUsedUpTheJudgmentTimesOutWithoutCallingTheProvider() throws Exception {
        JudgeOutcome outcome = run(request("그거 줘.", Duration.ZERO));

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.TIMEOUT);
        assertThat(stub.requestCount()).isZero();
    }

    @Test
    void aSlowProviderIsCutOffAtTheBudgetAndNeverRetried() throws Exception {
        stub.respond(Reply.ok("gpt-6-luna", CONFIRMING).delayed(Duration.ofSeconds(5)));
        judge = clientWithBudget(Duration.ofMillis(300));

        JudgeOutcome outcome = run(request("그거 줘.", Duration.ofSeconds(4)));

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.TIMEOUT);
        assertThat(outcome.isConfirmed()).isFalse();
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void aProviderErrorIsAnAbstainWithOneCallAndNoRetry() throws Exception {
        stub.respond(Reply.status(500), Reply.ok("gpt-6-luna", CONFIRMING));

        JudgeOutcome outcome = run(request("그거 줘.", Duration.ofSeconds(4)));

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.PROVIDER_ERROR);
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void aSchemaBreakingAnswerIsSchemaInvalidWithOneCall() throws Exception {
        stub.respond(Reply.ok("gpt-6-luna", "{\"first\":{\"option\":\"FULL\",\"probability\":2}}"));

        JudgeOutcome outcome = run(request("그거 줘.", Duration.ofSeconds(4)));

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.SCHEMA_INVALID);
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void anOptionOutsideTheRequestIsAbstainedEvenThoughTheSchemaPasses() throws Exception {
        stub.respond(Reply.ok("gpt-6-luna",
                "{\"first\":{\"option\":\"OTHER\",\"probability\":0.9},\"second\":{\"option\":\"FULL\",\"probability\":0.05}}"));

        assertThat(run(request("그거 줘.", Duration.ofSeconds(4))).abstainReason()).isEqualTo(AbstainReason.OPTION_OUT_OF_SET);
    }

    @Test
    void anEmptyOrUncertainTranscriptNeverReachesTheProvider() throws Exception {
        assertThat(run(request("  ", Duration.ofSeconds(4))).abstainReason()).isEqualTo(AbstainReason.UNREADABLE_INPUT);
        JudgeRequest uncertain = new JudgeRequest(JudgmentPoint.J1, "judgment-v1", List.of("PARTICIPATE", "REST"), "응", List.of(), null,
                Map.of(), true, clock.now().plusSeconds(4));
        assertThat(run(uncertain).abstainReason()).isEqualTo(AbstainReason.UNREADABLE_INPUT);
        assertThat(stub.requestCount()).isZero();
    }

    @Test
    void failuresThatThrowSynchronouslyOrRejectAreStillAbstains() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JudgeOutcomeValidator validator = new JudgeOutcomeValidator(0.6, 0.15, JSON);
        LunaJudgeClient throwing = new LunaJudgeClient(request -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }, PROMPT, validator, clock);
        LunaJudgeClient rejecting = new LunaJudgeClient(request -> CompletableFuture.failedFuture(
                new LunaException(LunaFailureReason.RATE_LIMITED, 1)), PROMPT, validator, clock);
        JudgeRequest request = request("그거 줘.", Duration.ofSeconds(4));

        JudgeOutcome thrown = throwing.judge(request, UUID.randomUUID()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);
        JudgeOutcome rejected = rejecting.judge(request, UUID.randomUUID()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(thrown.abstainReason()).isEqualTo(AbstainReason.PROVIDER_ERROR);
        assertThat(rejected.abstainReason()).isEqualTo(AbstainReason.PROVIDER_ERROR);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cancellingTheOutcomeCancelsTheProviderCall() {
        CompletableFuture<kr.mujoong.realtime.luna.LunaResult> inFlight = new CompletableFuture<>();
        LunaJudgeClient cancellable = new LunaJudgeClient(request -> inFlight, PROMPT, new JudgeOutcomeValidator(0.6, 0.15, JSON), clock);

        CompletableFuture<JudgeOutcome> outcome = cancellable.judge(request("그거 줘.", Duration.ofSeconds(4)), UUID.randomUUID())
                .toCompletableFuture();
        outcome.cancel(true);

        assertThat(inFlight.isCancelled()).isTrue();
    }

    @Test
    void theRequestValueRejectsWhatTheContractForbids() {
        Map<String, String> none = Map.of();
        assertThatThrownBy(() -> new JudgeRequest(JudgmentPoint.J1, "judgment-v1", List.of("A", "A"), "x", List.of(), null, none, false,
                clock.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JudgeRequest(JudgmentPoint.J6, "judgment-v1", List.of("A"), "x", List.of(),
                new JudgeRequest.Criteria("a", "b"), none, false, clock.now())).isInstanceOf(IllegalArgumentException.class);
        List<JudgeRequest.RecentTurn> four = java.util.Collections.nCopies(4, new JudgeRequest.RecentTurn(JudgeRequest.Speaker.CHILD, "x"));
        assertThatThrownBy(() -> new JudgeRequest(JudgmentPoint.J2, "judgment-v1", List.of("A"), "x", four, null, none, false,
                clock.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(request("비밀 전사", Duration.ofSeconds(4)).toString()).doesNotContain("비밀 전사");
    }

    @Test
    void theUnavailableClientReportsNoProviderWithoutAnyCall() throws Exception {
        UnavailableJudgeClient unavailable = new UnavailableJudgeClient("prompt-v1");

        JudgeOutcome outcome = unavailable.judge(request("그거 줘.", Duration.ofSeconds(4)), UUID.randomUUID()).toCompletableFuture()
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(unavailable.available()).isFalse();
        assertThat(judge.available()).isTrue();
        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.NO_PROVIDER);
        assertThat(outcome.model()).isNull();
        assertThat(stub.requestCount()).isZero();
    }
}
