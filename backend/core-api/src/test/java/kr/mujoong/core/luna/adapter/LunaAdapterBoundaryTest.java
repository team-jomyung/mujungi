package kr.mujoong.core.luna.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.knowledge.BehaviorDefinition;
import kr.mujoong.core.knowledge.PragmaticsKnowledge;
import kr.mujoong.core.luna.CoreLunaClient;
import kr.mujoong.core.luna.LunaFailureReason;
import kr.mujoong.core.luna.LunaHosts;
import kr.mujoong.core.luna.LunaInputSanitizer;
import kr.mujoong.core.luna.LunaProperties;
import kr.mujoong.core.luna.LunaSchemas;
import kr.mujoong.core.luna.LunaTransport;
import kr.mujoong.core.practice.PracticeAccountPort;
import kr.mujoong.core.practice.PracticeSessionContextPort.GoalSnapshot;
import kr.mujoong.core.practice.PracticeSessionContextPort.SessionContext;
import kr.mujoong.core.practice.Scenario;
import kr.mujoong.core.practice.ScenarioConfiguration.ScenarioLimits;
import kr.mujoong.core.practice.ScenarioRepository;
import kr.mujoong.core.practice.ScenarioService;
import kr.mujoong.core.result.ResultAiException;
import kr.mujoong.core.result.ResultTextGateway;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import kr.mujoong.core.result.check.OutputChecker;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;
import kr.mujoong.core.textai.GeneratedScenario;
import kr.mujoong.core.textai.ScenarioPrompt;
import kr.mujoong.core.textai.TextAiException;
import kr.mujoong.core.textai.TextAiGateway;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.luna.LunaResponsesStub.Reply;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기존 인터페이스 어댑터 경계 (U9 FR1.3 · BR3.4 · BR7.2 · BR8.1 · BR8.2 · AC2.4.2 · AC2.4.3 · NFR6.1). 루프백 대역 → CoreLunaClient →
 * 어댑터 → 기존 게이트웨이 · 검사기가 그대로 도는지 본다. 공급자는 대역, 키는 가짜 문자열, 입력은 합성 값이다(R-F1 · R-F3).
 */
@Tag("luna")
class LunaAdapterBoundaryTest {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final String MODEL = "gpt-6-luna";
    private static final String OPPORTUNITY = "친구가 놀이를 그만하고 싶다고 말한다.";

    private LunaResponsesStub stub;
    private MutableClock clock;
    private TestEvents events;
    private ExecutorService executor;
    private TextAiProperties timing;
    private CoreLunaClient client;

    @BeforeEach
    void setUp() {
        stub = LunaResponsesStub.start();
        clock = new MutableClock();
        events = new TestEvents(clock);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        timing = new TextAiProperties(Duration.ofMillis(800), new TextAiProperties.Retry(1, Duration.ofMillis(20)),
                new TextAiProperties.Result(Duration.ofSeconds(3), 0, 0.2, "v1"));
        LunaProperties.PurposeOptions options = new LunaProperties.PurposeOptions(4000, "medium");
        LunaProperties properties = new LunaProperties(MODEL, stub.baseUrl(), 1_000_000,
                new LunaProperties.Purposes(options, options, options, options, options, options));
        client = new CoreLunaClient(properties, timing, LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> "test-key-not-real-u9", 1_000_000),
                events.logger(), clock, JSON, duration -> clock.advance(duration));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        stub.close();
    }

    // ------------------------------------------------------------------ TextAiProvider → TextAiGateway → ScenarioService

    private TextAiGateway textGateway() {
        return new TextAiGateway(new LunaTextAiProvider(client, clock, JSON), timing, new ForbiddenExpressionPolicy(), clock,
                executor);
    }

    private static ScenarioPrompt prompt() {
        return new ScenarioPrompt("그만하자는 말 받아들이기", "멈추겠다고 말한다.", OPPORTUNITY, "장난감 놀이", "PEER", 8,
                List.of("공룡"), null);
    }

    private static String scenarioJson(String material) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("scenarioText", material + " 놀이 중이야. " + OPPORTUNITY + " 이제 그만할래.");
        output.put("triggerScript", "이제 그만할래.");
        output.put("relationType", "PEER");
        output.put("opportunityCondition", OPPORTUNITY);
        return JSON.write(output);
    }

    @Test
    void scenarioGenerationGoesThroughTheGatewayAndKeepsModelAndPromptVersions() {
        stub.respond(Reply.ok("gpt-6-luna-2026-10", scenarioJson("블록")));

        GeneratedScenario generated = textGateway().generateScenario(prompt(), clock.now().plusSeconds(15));

        assertThat(generated.triggerScript()).isEqualTo("이제 그만할래.");
        assertThat(generated.modelVersion()).isEqualTo("gpt-6-luna-2026-10");
        assertThat(generated.promptVersion()).isEqualTo(LunaTextAiProvider.PROMPT_VERSION);
        Map<String, Object> body = stub.requests().getFirst().json();
        assertThat(body.get("text")).as("기존 경로는 json_object(BR4.2)").isEqualTo(Map.of("format", Map.of("type", "json_object")));
        assertThat((String) body.get("input")).contains("그만하자는 말 받아들이기").doesNotContain("공룡", "interests");
        assertThat((String) body.get("instructions")).contains("JSON 객체 하나만 출력한다");
        assertThat(stub.requests().getFirst().violations()).isEmpty();
    }

    @Test
    void theGatewayRetriesOnlyWhatTheBr81TableMarksRetryable() {
        stub.respond(Reply.status(500), Reply.ok(MODEL, scenarioJson("공")));
        assertThat(textGateway().generateScenario(prompt(), clock.now().plusSeconds(15)).scenarioText()).contains("공");
        assertThat(stub.requestCount()).as("500 → UNAVAILABLE(retryable) → 게이트웨이 재시도 1회, 어댑터 안 재시도 없음").isEqualTo(2);

        stub = restart();
        stub.respond(Reply.status(401));
        assertThatThrownBy(() -> textGateway().generateScenario(prompt(), clock.now().plusSeconds(15)))
                .isInstanceOfSatisfying(TextAiException.class, e -> {
                    assertThat(e.reason()).isEqualTo(TextAiException.Reason.UNAVAILABLE);
                    assertThat(e.retryable()).isFalse();
                });
        assertThat(stub.requestCount()).isEqualTo(1);

        stub = restart();
        stub.respond(Reply.incomplete(MODEL));
        assertThatThrownBy(() -> textGateway().generateScenario(prompt(), clock.now().plusSeconds(15)))
                .isInstanceOfSatisfying(TextAiException.class,
                        e -> assertThat(e.reason()).isEqualTo(TextAiException.Reason.INVALID_OUTPUT));
        assertThat(stub.requestCount()).isEqualTo(1);

        stub = restart();
        stub.respond(Reply.ok(MODEL, scenarioJson("모래")).delayed(Duration.ofSeconds(3)));
        assertThatThrownBy(() -> textGateway().generateScenario(prompt(), clock.now().plusSeconds(15)))
                .isInstanceOfSatisfying(TextAiException.class, e -> assertThat(e.reason()).isEqualTo(TextAiException.Reason.TIMEOUT));
        assertThat(stub.requestCount()).as("TIMEOUT(retryable) → 게이트웨이 재시도 1회").isEqualTo(2);
    }

    @Test
    void theBr81AndBr82DecisionTablesCoverEveryFailureReason() {
        Map<LunaFailureReason, String> text = new LinkedHashMap<>();
        Map<LunaFailureReason, String> result = new LinkedHashMap<>();
        for (LunaFailureReason reason : LunaFailureReason.values()) {
            TextAiException failure = LunaTextAiProvider.translate(reason);
            text.put(reason, failure.reason() + "/" + failure.retryable());
            result.put(reason, LunaResultTextProvider.translate(reason));
        }

        assertThat(text).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry(LunaFailureReason.TIMEOUT, "TIMEOUT/true"),
                Map.entry(LunaFailureReason.TRANSPORT_ERROR, "UNAVAILABLE/true"),
                Map.entry(LunaFailureReason.RATE_LIMITED, "UNAVAILABLE/true"),
                Map.entry(LunaFailureReason.SERVER_ERROR, "UNAVAILABLE/true"),
                Map.entry(LunaFailureReason.EMPTY_OUTPUT, "UNAVAILABLE/true"),
                Map.entry(LunaFailureReason.REQUEST_REJECTED, "UNAVAILABLE/false"),
                Map.entry(LunaFailureReason.AUTH_FAILED, "UNAVAILABLE/false"),
                Map.entry(LunaFailureReason.FORBIDDEN_INPUT, "UNAVAILABLE/false"),
                Map.entry(LunaFailureReason.LENGTH_EXCEEDED, "INVALID_OUTPUT/false"),
                Map.entry(LunaFailureReason.REFUSED, "INVALID_OUTPUT/false"),
                Map.entry(LunaFailureReason.SCHEMA_INVALID, "INVALID_OUTPUT/false"),
                Map.entry(LunaFailureReason.MODEL_MISSING, "INVALID_OUTPUT/false")));
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry(LunaFailureReason.TIMEOUT, "TIMEOUT"),
                Map.entry(LunaFailureReason.EMPTY_OUTPUT, "SCHEMA_INVALID"),
                Map.entry(LunaFailureReason.LENGTH_EXCEEDED, "SCHEMA_INVALID"),
                Map.entry(LunaFailureReason.REFUSED, "SCHEMA_INVALID"),
                Map.entry(LunaFailureReason.SCHEMA_INVALID, "SCHEMA_INVALID"),
                Map.entry(LunaFailureReason.TRANSPORT_ERROR, "PROVIDER_ERROR"),
                Map.entry(LunaFailureReason.RATE_LIMITED, "PROVIDER_ERROR"),
                Map.entry(LunaFailureReason.SERVER_ERROR, "PROVIDER_ERROR"),
                Map.entry(LunaFailureReason.REQUEST_REJECTED, "PROVIDER_ERROR"),
                Map.entry(LunaFailureReason.AUTH_FAILED, "PROVIDER_ERROR"),
                Map.entry(LunaFailureReason.MODEL_MISSING, "PROVIDER_ERROR"),
                Map.entry(LunaFailureReason.FORBIDDEN_INPUT, "INTERNAL")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void scenarioServiceIssuesALunaScenarioAndMapsAnUnavailableProviderToServiceNotReady() {
        UUID guardian = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        SessionContext context = new SessionContext(session, guardian, child, true,
                List.of(new GoalSnapshot(goal, "ACCEPT_STOP", 1, "PEER")));
        ScenarioRepository repository = mock(ScenarioRepository.class);
        PracticeAccountPort accounts = mock(PracticeAccountPort.class);
        PragmaticsKnowledge knowledge = mock(PragmaticsKnowledge.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        BehaviorDefinition definition = new BehaviorDefinition(UUID.randomUUID(), "ACCEPT_STOP", 1, "그만하자는 말 받아들이기",
                "멈추겠다고 말한다.", OPPORTUNITY, "장난감 놀이", "수용 반응", "ACTIVE", clock.now(), clock.now());
        when(knowledge.pinVersion("ACCEPT_STOP", 1)).thenReturn(definition);
        when(accounts.requireActive(any(), anyBoolean()))
                .thenReturn(new PracticeAccountPort.AccountContext(guardian, child, 8, List.of("공룡")));
        when(repository.claim(anyString(), any(), any(), any(), any())).thenReturn(new ScenarioRepository.Claim(UUID.randomUUID(), null));
        AtomicReference<GeneratedScenario> saved = new AtomicReference<>();
        Scenario issued = mock(Scenario.class);
        when(repository.save(anyString(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(5));
            return issued;
        });
        when(tx.execute(any())).thenAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0))
                .doInTransaction(mock(TransactionStatus.class)));
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
        ScenarioService service = new ScenarioService(repository, id -> context, id -> null, accounts, knowledge, textGateway(),
                clock, tx, new ScenarioLimits(Duration.ofSeconds(15)));
        ScenarioService.Request request = new ScenarioService.Request(session, goal, "ORIGINAL", null);

        stub.respond(Reply.ok(MODEL, scenarioJson("블록")));
        assertThat(service.generate(request)).isSameAs(issued);
        assertThat(saved.get().modelVersion()).isEqualTo(MODEL);
        assertThat(saved.get().scenarioText()).contains(OPPORTUNITY);

        stub.respond(Reply.status(403));
        assertThatThrownBy(() -> service.generate(request)).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_NOT_READY));
    }

    // ------------------------------------------------------------------ ResultTextProvider → ResultTextGateway → OutputChecker

    private ResultTextGateway resultGateway() {
        return new ResultTextGateway(new LunaResultTextProvider(client, clock), timing, clock, executor, JSON);
    }

    @Test
    void resultTextKeepsTheGatewaySanitizeRestoreAndSchemaPath() {
        UUID goalSelection = UUID.randomUUID();
        stub.respond(Reply.ok(MODEL, "{\"sentences\":[{\"goalSelectionId\":\"참조1\",\"text\":\"아이가 블록을 건넸어요.\","
                + "\"citations\":[{\"utteranceSeq\":1,\"quote\":\"이거 줄게\"}]}]}"));
        Map<String, Object> data = Map.of("goalSelectionId", goalSelection, "childName", "하늘이",
                "turns", List.of(Map.of("utteranceSeq", 1, "text", "하늘이가 이거 줄게")));

        Map<String, Object> output = resultGateway().call("sentences", data, Map.of("하늘이", "아이"), clock.now().plusSeconds(10));

        @SuppressWarnings("unchecked")
        Map<String, Object> sentence = ((List<Map<String, Object>>) output.get("sentences")).getFirst();
        assertThat(sentence).as("참조 복원(AC2.4.3 경로)").containsEntry("goalSelectionId", goalSelection.toString());
        LunaResponsesStub.RecordedRequest sent = stub.requests().getFirst();
        assertThat(sent.body()).doesNotContain(goalSelection.toString(), "하늘이", "temperature").contains("참조1");
        assertThat(sent.json()).containsEntry("store", false);
        assertThat(sent.violations()).isEmpty();
        assertThat(events.capture().events(EventCode.LUNA_CALL)).singleElement()
                .satisfies(line -> assertThat(line.toString()).contains("RESULT_TEXT").doesNotContain("하늘이", "블록"));
    }

    @Test
    void resultSchemaAndProviderFailuresBecomeTheExistingResultCodes() {
        stub.respond(Reply.ok(MODEL, "{\"sentences\":[{\"unknown\":1}]}"));
        assertThatThrownBy(() -> resultGateway().call("sentences", Map.of("turns", List.of()), Map.of(), clock.now().plusSeconds(10)))
                .isInstanceOfSatisfying(ResultAiException.class, e -> assertThat(e.code()).isEqualTo("SCHEMA_INVALID"));

        stub.respond(Reply.status(502));
        assertThatThrownBy(() -> resultGateway().call("sentences", Map.of("turns", List.of()), Map.of(), clock.now().plusSeconds(10)))
                .isInstanceOfSatisfying(ResultAiException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_ERROR"));

        stub.respond(Reply.emptyOutput(MODEL));
        assertThatThrownBy(() -> resultGateway().call("sentences", Map.of("turns", List.of()), Map.of(), clock.now().plusSeconds(10)))
                .isInstanceOfSatisfying(ResultAiException.class, e -> assertThat(e.code()).isEqualTo("SCHEMA_INVALID"));
        assertThat(stub.requestCount()).as("JOB_ATTEMPT · 어댑터 1회, 재시도는 결과 작업 몫").isEqualTo(3);
    }

    @Test
    void outputCheckerStillRunsItsDictionaryAndClassifyPassOverLuna() {
        OutputChecker checker = new OutputChecker(new ForbiddenDictionary(), resultGateway());
        stub.respond(Reply.ok(MODEL, "{\"results\":[{\"id\":\"t1\",\"violation\":false,\"category\":null,\"span\":null},"
                + "{\"id\":\"t2\",\"violation\":true,\"category\":\"PRESSURE\",\"span\":\"꼭\"}]}"));

        Map<String, String> checked = checker.check(List.of(
                new OutputChecker.Draft("t1", "아이가 블록을 건넸어요.", List.of(), "대체 문장 1"),
                new OutputChecker.Draft("t2", "아이가 차례를 기다렸어요.", List.of(), "대체 문장 2"),
                new OutputChecker.Draft("t3", "", List.of(), "대체 문장 3")), clock.now().plusSeconds(10));

        assertThat(checked).containsEntry("t1", "아이가 블록을 건넸어요.").containsEntry("t2", "대체 문장 2")
                .containsEntry("t3", "대체 문장 3");
        assertThat((String) stub.requests().getFirst().json().get("input")).contains("t1", "t2").doesNotContain("t3");
    }

    private LunaResponsesStub restart() {
        stub.close();
        LunaResponsesStub fresh = LunaResponsesStub.start();
        LunaProperties.PurposeOptions options = new LunaProperties.PurposeOptions(4000, "medium");
        client = new CoreLunaClient(new LunaProperties(MODEL, fresh.baseUrl(), 1_000_000,
                new LunaProperties.Purposes(options, options, options, options, options, options)), timing,
                LunaSchemas.loadDefault(JSON), new LunaInputSanitizer(JSON),
                new LunaTransport(fresh.baseUrl(), LunaHosts.Mode.TEST, () -> "test-key-not-real-u9", 1_000_000),
                events.logger(), clock, JSON, duration -> clock.advance(duration));
        return fresh;
    }
}
