package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.luna.LunaDecision;
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
import kr.mujoong.testsupport.luna.LunaDecisionsStub;
import kr.mujoong.testsupport.luna.LunaDecisionsStub.Reply;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Decisions 판정 구현 (FT-1023-d, U11 BR1.1 ~ BR1.7 · BR4.3과 같은 계약). 공급자는 {@link LunaDecisionsStub} 루프백 대역, 키는 가짜 문자열, 입력은
 * 합성 값이다(R-F1 · R-F3). 증거 종류: 테스트 대역(R-M11) — 실제 Decisions API 성공이 아니다.
 */
@Tag("judgment-turn")
class DecisionsJudgeClientTest {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String MODEL = "gpt-6-luna";
    private static final String NAME = DecisionsJudgeClient.QUESTION_NAME;
    private static final LiveContentResources.JudgmentOptionSet OPTION_SET = LiveContentResources.loadDefault().judgment();
    private static final JudgmentPrompt PROMPT = JudgmentPrompt.load(OPTION_SET);
    private static final List<String> OPTIONS = OPTION_SET.points().get(LiveContentResources.JudgmentPoint.J2).optionCodes();

    private LunaDecisionsStub stub;
    private MutableClock clock;
    private LogCapture capture;
    private DecisionsJudgeClient judge;

    @BeforeEach
    void setUp() {
        stub = LunaDecisionsStub.start();
        clock = new MutableClock();
        capture = LogCapture.detached();
        judge = clientWithBudget(Duration.ofSeconds(4));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private RealtimeLunaClient luna(Duration budget) {
        URI base = stub.baseUrl();
        LunaProperties properties = new LunaProperties(MODEL, base, 1_000_000,
                new LunaProperties.Purposes(new LunaProperties.PurposeOptions(4000, "medium")), true,
                LunaProperties.JudgeEndpoint.DECISIONS);
        EventLogger events = TestEventLoggers.strict(clock, capture, RealtimeMetrics.inMemory());
        return new RealtimeLunaClient(properties, budget, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(base, LunaHosts.Mode.TEST, () -> "test-key-not-real-ft1023d", 1_000_000), events, clock, JSON);
    }

    private DecisionsJudgeClient clientWithBudget(Duration budget) {
        return new DecisionsJudgeClient(luna(budget)::decide, PROMPT, new JudgeOutcomeValidator(0.6, 0.15, JSON), clock);
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

    /** J2 선택지 판 순서대로 확률을 붙인다. */
    private static Map<String, Double> probabilities(double... values) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < OPTIONS.size(); i++) {
            map.put(OPTIONS.get(i), i < values.length ? values[i] : 0.0);
        }
        return map;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aConfirmingDecisionCallsTheProviderOnceWithTheQuestionFromThePromptAndTheDataInTheInput() throws Exception {
        stub.respond(Reply.choice(MODEL, NAME, "FULL", probabilities(0.8, 0.1, 0.05, 0.03, 0.01, 0.01)));

        JudgeOutcome outcome = run(request("그거 줘. 높이 쌓고 싶어서.", Duration.ofSeconds(4)));

        assertThat(outcome.isConfirmed()).isTrue();
        assertThat(outcome.firstOption()).isEqualTo("FULL");
        assertThat(outcome.firstProbability()).isEqualTo(0.8);
        assertThat(outcome.secondOption()).isEqualTo("PARTIAL");
        assertThat(outcome.secondProbability()).isEqualTo(0.1);
        assertThat(outcome.model()).isEqualTo(MODEL);
        assertThat(outcome.promptVersion()).isEqualTo("prompt-service-v2.decisions-v1");
        assertThat(stub.requestCount()).isEqualTo(1);

        LunaResponsesStub.RecordedRequest sent = stub.requests().getFirst();
        assertThat(sent.violations()).isEmpty();
        Map<String, Object> body = sent.json();
        assertThat(body.keySet()).as("store · temperature · reasoning · instructions 같은 없는 필드는 보내지 않는다")
                .containsExactly("model", "input", "questions");
        List<Map<String, Object>> questions = (List<Map<String, Object>>) body.get("questions");
        assertThat(questions).singleElement().satisfies(question -> {
            assertThat(question).containsEntry("type", "choice").containsEntry("name", NAME);
            String instructions = (String) question.get("instructions");
            assertThat(instructions).contains("분류기").contains("아이의 가장 마지막 말(childUtterance) 하나만 판정한다")
                    .contains("[J2] " + OPTION_SET.points().get(LiveContentResources.JudgmentPoint.J2).question())
                    .doesNotContain("[J3] ").doesNotContain("그거 줘").doesNotContain("이것 봐");
            List<Map<String, Object>> choices = (List<Map<String, Object>>) question.get("choices");
            assertThat(choices).extracting(choice -> choice.get("value")).containsExactlyElementsOf(OPTIONS);
            for (int i = 0; i < choices.size(); i++) {
                assertThat(choices.get(i)).containsEntry("description",
                        OPTION_SET.points().get(LiveContentResources.JudgmentPoint.J2).options().get(i).definition());
            }
        });
        Map<String, Object> part = ((List<Map<String, Object>>) ((List<Map<String, Object>>) body.get("input")).getFirst()
                .get("content")).getFirst();
        assertThat(part).containsEntry("type", "input_json");
        Map<String, Object> data = (Map<String, Object>) part.get("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("judgmentPoint", "optionSetVersion", "options", "childUtterance",
                "recentTurns", "criteria");
        assertThat(data).containsEntry("childUtterance", "그거 줘. 높이 쌓고 싶어서.").containsEntry("options", OPTIONS);
        assertThat(LogCapture.attrs(capture.events(EventCode.LUNA_CALL).getFirst()))
                .containsEntry("lunaOutcome", "OK").containsEntry("attempt", 1)
                .containsEntry("promptVersion", "prompt-service-v2.decisions-v1");
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(1);
        assertThat(capture.anyLineContains("높이 쌓고")).isFalse();
    }

    @Test
    void identifiersAndTheChildNameNeverLeaveTheProcess() throws Exception {
        stub.respond(Reply.choice(MODEL, NAME, "FULL", probabilities(0.8, 0.1)));
        JudgeRequest withAlias = new JudgeRequest(JudgmentPoint.J2, "judgment-v1", OPTIONS, "하준이는 그거 줘", List.of(), null,
                Map.of("하준", "아이"), false, clock.now().plusSeconds(4));

        run(withAlias);

        assertThat(stub.anyBodyContains("하준")).isFalse();
        assertThat(stub.anyBodyContains("아이이는 그거 줘")).isTrue();
        for (String forbidden : List.of("childId", "guardianId", "sessionId", "deviceId", "turnId", "store", "temperature")) {
            assertThat(stub.anyBodyContains(forbidden)).as(forbidden).isFalse();
        }
    }

    @Test
    void theSameLowerBoundsAsTheResponsesPathTurnWeakAnswersIntoAbstentionsWithScores() throws Exception {
        stub.respond(Reply.choice(MODEL, NAME, "FULL", probabilities(0.55, 0.2, 0.25)),
                Reply.choice(MODEL, NAME, "FULL", probabilities(0.7, 0.6)),
                Reply.choice(MODEL, NAME, "FULL", probabilities(0.6, 0.45)));

        JudgeOutcome lowConfidence = run(request("음", Duration.ofSeconds(4)));
        JudgeOutcome lowMargin = run(request("음", Duration.ofSeconds(4)));
        JudgeOutcome boundary = run(request("음", Duration.ofSeconds(4)));

        assertThat(lowConfidence.abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(lowConfidence.firstOption()).isEqualTo("FULL");
        assertThat(lowConfidence.secondOption()).as("2순위는 확률 내림차순(판 순서 아님)").isEqualTo("HELP");
        assertThat(lowMargin.abstainReason()).isEqualTo(AbstainReason.LOW_MARGIN);
        assertThat(boundary.isConfirmed()).as("하한 · 최소 차이와 같으면 확정(Responses 경로와 같은 경계)").isTrue();
    }

    @Test
    void aTieKeepsTheProvidersChoiceFirst() throws Exception {
        stub.respond(Reply.choice(MODEL, NAME, "PARTIAL", probabilities(0.4, 0.4, 0.2)));

        JudgeOutcome outcome = run(request("그거", Duration.ofSeconds(4)));

        assertThat(outcome.firstOption()).isEqualTo("PARTIAL");
        assertThat(outcome.secondOption()).isEqualTo("FULL");
        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
    }

    @Test
    void refusalsUnknownChoicesMissingProbabilitiesAndProviderErrorsAreAbstentions() throws Exception {
        Map<String, Double> missing = new LinkedHashMap<>(probabilities(0.9, 0.05));
        missing.remove(OPTIONS.getLast());
        stub.respond(Reply.refusal(MODEL, NAME),
                Reply.choice(MODEL, NAME, "MAYBE", probabilities(0.9, 0.05)),
                Reply.choice(MODEL, NAME, "FULL", missing),
                Reply.rateLimited(1),
                Reply.status(503));

        List<AbstainReason> reasons = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            JudgeOutcome outcome = run(request("그거 줘", Duration.ofSeconds(4)));
            assertThat(outcome.isConfirmed()).isFalse();
            reasons.add(outcome.abstainReason());
        }

        assertThat(reasons).containsExactly(AbstainReason.PROVIDER_ERROR, AbstainReason.SCHEMA_INVALID, AbstainReason.SCHEMA_INVALID,
                AbstainReason.PROVIDER_ERROR, AbstainReason.PROVIDER_ERROR);
        assertThat(stub.requestCount()).as("판정마다 한 번, 재시도 없음").isEqualTo(5);
        assertThat(capture.events(EventCode.LUNA_CALL)).extracting(line -> LogCapture.attrs(line).get("lunaOutcome"))
                .containsExactly("REFUSED", "SCHEMA_INVALID", "SCHEMA_INVALID", "RATE_LIMITED", "SERVER_ERROR");
    }

    @Test
    void aSlowProviderIsCutOffAtTheBudgetAndAnExpiredDeadlineNeverCalls() throws Exception {
        judge = clientWithBudget(Duration.ofMillis(300));
        stub.respond(Reply.choice(MODEL, NAME, "FULL", probabilities(0.8, 0.1)).delayed(Duration.ofSeconds(3)));

        JudgeOutcome slow = run(request("그거 줘", Duration.ofSeconds(4)));
        JudgeOutcome expired = run(request("그거 줘", Duration.ZERO));

        assertThat(slow.abstainReason()).isEqualTo(AbstainReason.TIMEOUT);
        assertThat(expired.abstainReason()).isEqualTo(AbstainReason.TIMEOUT);
        assertThat(stub.requestCount()).as("마감이 지난 판정은 부르지 않는다").isEqualTo(1);
    }

    @Test
    void unreadableInputAbstainsWithoutCallingTheProvider() throws Exception {
        JudgeOutcome blank = run(request("  ", Duration.ofSeconds(4)));
        JudgeRequest uncertain = new JudgeRequest(JudgmentPoint.J2, "judgment-v1", OPTIONS, "그거", List.of(), null, Map.of(), true,
                clock.now().plusSeconds(4));
        JudgeOutcome low = judge.judge(uncertain, UUID.randomUUID()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(blank.abstainReason()).isEqualTo(AbstainReason.UNREADABLE_INPUT);
        assertThat(low.abstainReason()).isEqualTo(AbstainReason.UNREADABLE_INPUT);
        assertThat(stub.requestCount()).isZero();
        assertThat(capture.events(EventCode.LUNA_CALL)).isEmpty();
    }

    @Test
    void cancellingTheOutcomeCancelsTheProviderCall() {
        CompletableFuture<LunaDecision> inFlight = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        DecisionsJudgeClient fake = new DecisionsJudgeClient((request, question) -> {
            calls.incrementAndGet();
            return inFlight;
        }, PROMPT, new JudgeOutcomeValidator(0.6, 0.15, JSON), clock);

        CompletionStage<JudgeOutcome> outcome = fake.judge(request("그거 줘", Duration.ofSeconds(4)), UUID.randomUUID());
        outcome.toCompletableFuture().cancel(true);

        assertThat(inFlight.isCancelled()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void anUnexpectedFailureWhileStartingTheCallIsAnAbstentionNotAnException() throws Exception {
        DecisionsJudgeClient broken = new DecisionsJudgeClient((request, question) -> {
            throw new IllegalStateException("대역 실패");
        }, PROMPT, new JudgeOutcomeValidator(0.6, 0.15, JSON), clock);

        JudgeOutcome outcome = broken.judge(request("그거 줘", Duration.ofSeconds(4)), UUID.randomUUID()).toCompletableFuture()
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.PROVIDER_ERROR);
        assertThat(broken.available()).isTrue();
    }
}
