package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.luna.LunaDecision;
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
import kr.mujoong.testsupport.luna.LunaDecisionsStub;
import kr.mujoong.testsupport.luna.LunaDecisionsStub.Reply;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 진행 중 판정 구현 — Decisions API (U11 BR1.1 ~ BR1.7 · BR4.3, FT-1023-d). 공급자는 {@link LunaDecisionsStub} 루프백 대역, 키는 가짜 문자열, 입력은
 * 합성 값이다(R-F1 · R-F3). 증거 종류: 테스트 대역(R-M11) — 실제 Decisions API 성공이 아니다.
 */
@Tag("judgment-turn")
@Tag("service-guardrail")
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
        LunaProperties properties = new LunaProperties(MODEL, base, 1_000_000, true);
        EventLogger events = TestEventLoggers.strict(clock, capture, RealtimeMetrics.inMemory());
        return new RealtimeLunaClient(properties, budget, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(base, LunaHosts.Mode.TEST, () -> "test-key-not-real-ft1023d", 1_000_000), events, clock, JSON);
    }

    private DecisionsJudgeClient clientWithBudget(Duration budget) {
        return new DecisionsJudgeClient(luna(budget)::decide, PROMPT, new JudgeOutcomeValidator(0.6, 0.15), clock);
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
    void usageConnectionReceivesTheExistingJudgmentTurnId() throws Exception {
        UUID turn = UUID.randomUUID();
        var received = new AtomicReference<UUID>();
        var client = DecisionsJudgeClient.withUsage((request, question, turnId) -> {
            received.set(turnId);
            var ranked = probabilities(0.8, 0.1, 0.05, 0.03, 0.01, 0.01).entrySet().stream()
                    .map(entry -> new LunaDecision.Probability(entry.getKey(), entry.getValue())).toList();
            return CompletableFuture.completedFuture(new LunaDecision("FULL", ranked, MODEL, PROMPT.version(), Duration.ZERO, 1));
        }, PROMPT, new JudgeOutcomeValidator(0.6, 0.15), clock);

        var outcome = client.judge(request("그거 줘. 높이 쌓고 싶어서.", Duration.ofSeconds(4)), turn)
                .toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(received.get()).isEqualTo(turn);
        assertThat(outcome.isConfirmed()).isTrue();
        assertThat(outcome.firstOption()).isEqualTo("FULL");
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
        assertThat(outcome.promptVersion()).isEqualTo("prompt-service-v3");
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
                    .endsWith("[J2] " + OPTION_SET.points().get(LiveContentResources.JudgmentPoint.J2).question())
                    .doesNotContain("[J3] ").doesNotContain("그거 줘").doesNotContain("이것 봐")
                    .as("Responses 출력 형식 문장이 없다").doesNotContain("first").doesNotContain("second").doesNotContain("JSON만");
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
                .containsEntry("promptVersion", "prompt-service-v3");
        assertThat(capture.events(EventCode.LUNA_CALL)).hasSize(1);
        assertThat(capture.anyLineContains("높이 쌓고")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMujoongLineOfTheDirectAskPracticePassesTheRequestContractAndKeepsItsSpeaker() throws Exception {
        LiveContentResources.JudgmentOptionSet direct = LiveContentResources.loadFor(false).judgment();
        DecisionsJudgeClient c = new DecisionsJudgeClient(luna(Duration.ofSeconds(4))::decide, JudgmentPrompt.load(direct),
                new JudgeOutcomeValidator(0.6, 0.15), clock);
        stub.respond(Reply.choice(MODEL, NAME, "FULL", probabilities(0.8, 0.1)));
        JudgeRequest request = new JudgeRequest(JudgmentPoint.J2, direct.version(), OPTIONS, "다음에 같이 그리자고 할래.",
                List.of(new JudgeRequest.RecentTurn(JudgeRequest.Speaker.MUJOONG, "그런데 친구가 손이 아프대. 너라면 친구한테 뭐라고 말할래?")),
                new JudgeRequest.Criteria("친구의 사정을 듣고 마음을 살피며 다른 방법을 말한다", "사정을 살피는 말과 다른 방법이 드러나면 충분히 전달"),
                Map.of(), false, clock.now().plusSeconds(4));

        JudgeOutcome outcome = c.judge(request, UUID.randomUUID()).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(outcome.isConfirmed()).as("C안 화자 MUJOONG이 판정 요청 계약(ai-judgment-request)을 지난다").isTrue();
        assertThat(outcome.promptVersion()).isEqualTo("prompt-service-v4");
        Map<String, Object> body = stub.requests().getFirst().json();
        Map<String, Object> part = ((List<Map<String, Object>>) ((List<Map<String, Object>>) body.get("input")).getFirst()
                .get("content")).getFirst();
        Map<String, Object> data = (Map<String, Object>) part.get("data");
        assertThat((List<Map<String, Object>>) data.get("recentTurns")).singleElement()
                .satisfies(turn -> assertThat(turn).containsEntry("speaker", "MUJOONG"));
        assertThat(data).containsEntry("optionSetVersion", "judgment-service-v2");
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
    void theLowerBoundsTurnWeakAnswersIntoAbstentionsWithScores() throws Exception {
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
        assertThat(boundary.isConfirmed()).as("하한 · 최소 차이와 같으면 확정").isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRequestsSameStepGroupsReachTheValidatorButNeverTheProvider() throws Exception {
        // J2 판 순서: FULL · PARTIAL · HELP · NOT_UNDERSTOOD · IRRELEVANT · UNREADABLE. C안 기회 단계에서 다시 설명으로 가는 두 코드가 한 묶음이다
        List<List<String>> groups = List.of(List.of("NOT_UNDERSTOOD", "IRRELEVANT"));
        stub.respond(Reply.choice(MODEL, NAME, "NOT_UNDERSTOOD", probabilities(0.05, 0.1, 0.1, 0.4, 0.3, 0.05)),
                Reply.choice(MODEL, NAME, "HELP", probabilities(0.02, 0.05, 0.54, 0.3, 0.05, 0.04)));
        JudgeRequest base = request("음 무슨 말이야", Duration.ofSeconds(4));
        JudgeRequest withGroups = new JudgeRequest(base.point(), base.optionSetVersion(), base.options(), base.childUtterance(),
                base.recentTurns(), base.criteria(), base.aliases(), base.uncertain(), base.deadline(), groups);

        JudgeOutcome sameStep = run(withGroups);
        JudgeOutcome differentSteps = run(withGroups);

        assertThat(sameStep.isConfirmed()).isTrue();
        assertThat(sameStep.sameStepSum()).isTrue();
        assertThat(sameStep.firstOption()).isEqualTo("NOT_UNDERSTOOD");
        assertThat(sameStep.secondOption()).isEqualTo("IRRELEVANT");
        assertThat(differentSteps.abstainReason()).as("5회차 J2: HELP 0.54 · NOT_UNDERSTOOD 0.30은 다른 걸음이라 그대로 보류")
                .isEqualTo(AbstainReason.LOW_CONFIDENCE);
        for (LunaResponsesStub.RecordedRequest sent : stub.requests()) {
            Map<String, Object> part = ((List<Map<String, Object>>) ((List<Map<String, Object>>) sent.json().get("input")).getFirst()
                    .get("content")).getFirst();
            assertThat(((Map<String, Object>) part.get("data")).keySet()).as("묶음은 공급자 입력이 아니다")
                    .containsExactlyInAnyOrder("judgmentPoint", "optionSetVersion", "options", "childUtterance", "recentTurns", "criteria");
            assertThat(sent.violations()).isEmpty();
        }
        assertThat(stub.anyBodyContains("sameStep")).isFalse();
        assertThat(withGroups.toString()).doesNotContain("NOT_UNDERSTOOD");
    }

    @Test
    void sameStepGroupsMustBeTwoOrMoreRequestedOptionsEachInOneGroup() {
        JudgeRequest base = request("그거", Duration.ofSeconds(4));
        java.util.function.Function<List<List<String>>, JudgeRequest> with = groups -> new JudgeRequest(base.point(),
                base.optionSetVersion(), base.options(), base.childUtterance(), base.recentTurns(), base.criteria(), base.aliases(),
                base.uncertain(), base.deadline(), groups);

        assertThat(with.apply(null).sameStepGroups()).isEmpty();
        assertThat(base.sameStepGroups()).as("묶음 없는 생성자").isEmpty();
        assertThat(with.apply(List.of(List.of("NOT_UNDERSTOOD", "IRRELEVANT"))).sameStepGroups())
                .containsExactly(List.of("NOT_UNDERSTOOD", "IRRELEVANT"));
        assertThatThrownBy(() -> with.apply(List.of(List.of("FULL")))).as("하나뿐인 묶음").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with.apply(List.of(List.of("FULL", "COPIED")))).as("J2 선택지 밖 코드")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with.apply(List.of(List.of("FULL", "FULL")))).as("묶음 안 중복").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with.apply(List.of(List.of("FULL", "PARTIAL"), List.of("PARTIAL", "HELP")))).as("묶음끼리 겹침")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 요청 하한 (ai-io.md 10.10, 편차 FT-1024-h) ----

    private static JudgeRequest withFloor(JudgeRequest base, Double floor) {
        return new JudgeRequest(base.point(), base.optionSetVersion(), base.options(), base.childUtterance(), base.recentTurns(),
                base.criteria(), base.aliases(), base.uncertain(), base.deadline(), List.of(), floor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRequestsLoweredFloorReachesTheValidatorButNeverTheProvider() throws Exception {
        // 5회차 J2(실제 API + 합성 아이 음성): 일부만 대답 PARTIAL 0.48(2순위와 차이 0.20), 도움 요청 HELP 0.44. J2 판 순서: FULL · PARTIAL · HELP …
        stub.respond(Reply.choice(MODEL, NAME, "PARTIAL", probabilities(0.28, 0.48, 0.1, 0.06, 0.05, 0.03)),
                Reply.choice(MODEL, NAME, "HELP", probabilities(0.05, 0.1, 0.44, 0.3, 0.08, 0.03)),
                Reply.choice(MODEL, NAME, "PARTIAL", probabilities(0.28, 0.48, 0.1, 0.06, 0.05, 0.03)));
        JudgeRequest base = request("그거 줘", Duration.ofSeconds(4));
        JudgeRequest floored = withFloor(base, 0.45);

        JudgeOutcome partial = run(floored);
        JudgeOutcome help = run(floored);
        JudgeOutcome global = run(base);

        assertThat(partial.isConfirmed()).as("낮춘 하한 0.45 이상 · 최소 차이 0.15 이상").isTrue();
        assertThat(partial.firstOption()).isEqualTo("PARTIAL");
        assertThat(partial.firstProbability()).isEqualTo(0.48);
        assertThat(partial.sameStepSum()).isFalse();
        assertThat(help.abstainReason()).as("낮춘 하한에도 못 미친다").isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(global.abstainReason()).as("하한을 싣지 않은 요청은 전역 하한 0.6 그대로").isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(global.firstProbability()).isEqualTo(0.48);
        for (LunaResponsesStub.RecordedRequest sent : stub.requests()) {
            Map<String, Object> part = ((List<Map<String, Object>>) ((List<Map<String, Object>>) sent.json().get("input")).getFirst()
                    .get("content")).getFirst();
            assertThat(((Map<String, Object>) part.get("data")).keySet()).as("낮춘 하한은 공급자 입력이 아니다")
                    .containsExactlyInAnyOrder("judgmentPoint", "optionSetVersion", "options", "childUtterance", "recentTurns", "criteria");
            assertThat(sent.violations()).isEmpty();
        }
        assertThat(stub.anyBodyContains("minConfidence")).isFalse();
        assertThat(stub.requestCount()).isEqualTo(3);
    }

    @Test
    void theLoweredFloorOfTheRequestIsAProbabilityOrNothing() {
        JudgeRequest base = request("그거", Duration.ofSeconds(4));

        assertThat(base.minConfidence()).as("하한 없는 생성자").isNull();
        assertThat(withFloor(base, null).minConfidence()).isNull();
        assertThat(withFloor(base, 0.45).minConfidence()).isEqualTo(0.45);
        assertThat(new JudgeRequest(base.point(), base.optionSetVersion(), base.options(), base.childUtterance(), base.recentTurns(),
                base.criteria(), base.aliases(), base.uncertain(), base.deadline(), List.of(List.of("NOT_UNDERSTOOD", "IRRELEVANT")))
                .minConfidence()).as("묶음만 싣는 생성자").isNull();
        for (double bad : new double[] {Double.NaN, -0.01, 1.01, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> withFloor(base, bad)).as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
        }
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
        }, PROMPT, new JudgeOutcomeValidator(0.6, 0.15), clock);

        CompletionStage<JudgeOutcome> outcome = fake.judge(request("그거 줘", Duration.ofSeconds(4)), UUID.randomUUID());
        outcome.toCompletableFuture().cancel(true);

        assertThat(inFlight.isCancelled()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void anUnexpectedFailureWhileStartingTheCallIsAnAbstentionNotAnException() throws Exception {
        DecisionsJudgeClient broken = new DecisionsJudgeClient((request, question) -> {
            throw new IllegalStateException("대역 실패");
        }, PROMPT, new JudgeOutcomeValidator(0.6, 0.15), clock);

        JudgeOutcome outcome = broken.judge(request("그거 줘", Duration.ofSeconds(4)), UUID.randomUUID()).toCompletableFuture()
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.PROVIDER_ERROR);
        assertThat(broken.available()).isTrue();
    }

    @Test
    void anAnswerArrivingJustBeforeTheFourSecondLimitIsStillUsed() throws Exception {
        stub.respond(Reply.choice(MODEL, NAME, "FULL", probabilities(0.8, 0.1)));

        JudgeOutcome outcome = run(request("그거 줘.", Duration.ofMillis(3_999)));

        assertThat(outcome.isConfirmed()).isTrue();
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void aRejectedCallIsAProviderErrorAbstentionWithOneCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DecisionsJudgeClient rejecting = new DecisionsJudgeClient((request, question) -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new LunaException(LunaFailureReason.RATE_LIMITED, 1));
        }, PROMPT, new JudgeOutcomeValidator(0.6, 0.15), clock);

        JudgeOutcome outcome = rejecting.judge(request("그거 줘", Duration.ofSeconds(4)), UUID.randomUUID()).toCompletableFuture()
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.PROVIDER_ERROR);
        assertThat(DecisionsJudgeClient.abstainReason(new LunaException(LunaFailureReason.TIMEOUT, 0)))
                .isEqualTo(AbstainReason.TIMEOUT);
        assertThat(DecisionsJudgeClient.abstainReason(new java.util.concurrent.CompletionException(
                new LunaException(LunaFailureReason.SCHEMA_INVALID, 1)))).isEqualTo(AbstainReason.SCHEMA_INVALID);
        assertThat(calls).hasValue(1);
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
        UnavailableJudgeClient unavailable = new UnavailableJudgeClient(PROMPT.version());

        JudgeOutcome outcome = unavailable.judge(request("그거 줘.", Duration.ofSeconds(4)), UUID.randomUUID()).toCompletableFuture()
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(unavailable.available()).isFalse();
        assertThat(judge.available()).isTrue();
        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.NO_PROVIDER);
        assertThat(outcome.model()).isNull();
        assertThat(outcome.promptVersion()).isEqualTo("prompt-service-v3");
        assertThat(stub.requestCount()).isZero();
    }
}
