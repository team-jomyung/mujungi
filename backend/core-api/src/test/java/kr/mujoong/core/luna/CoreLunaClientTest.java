package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.luna.LunaResponsesStub.Reply;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Core 공통 호출 (U9 BR1.6 · BR1.8 · BR2.5 · BR3.1 ~ BR3.6 · BR4.1 ~ BR4.4 · BR5.1 · BR6.1 · BR6.2 · NFR1.1 ~ NFR1.3 · NFR2.1 ·
 * NFR3.3 · NFR3.5). 공급자는 루프백 대역, 입력은 합성 값, 키는 가짜 문자열이다(R-F1 · R-F3). 증거 종류: 테스트 대역(R-M11).
 */
@Tag("u9")
class CoreLunaClientTest {

    private static final String CHILD_NAME = "하늘이";
    private static final String MODEL = "gpt-6-luna";

    private LunaResponsesStub stub;
    private MutableClock clock;
    private TestEvents events;
    private LunaTestFixtures.ClockSleeper sleeper;
    private CoreLunaClient client;

    @BeforeEach
    void setUp() {
        stub = LunaResponsesStub.start();
        clock = new MutableClock();
        events = new TestEvents(clock);
        sleeper = new LunaTestFixtures.ClockSleeper(clock);
        client = LunaTestFixtures.client(stub.baseUrl(), clock, events, sleeper, LunaTestFixtures.timing());
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private Map<String, Object> input() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("utterance", CHILD_NAME + "가 블록을 줬어");
        input.put("sessionId", "00000000-0000-4000-8000-000000000001");
        input.put("goalName", "부탁하기");
        return input;
    }

    private LunaRequest request(LunaPurpose purpose) {
        return LunaRequest.of(purpose, "u9-test.v1", "지정한 JSON만 출력한다.", input(), Map.of(CHILD_NAME, "아이"),
                clock.now().plusSeconds(10));
    }

    private LunaException failure(LunaRequest request) {
        try {
            client.call(request);
        } catch (LunaException e) {
            return e;
        }
        throw new AssertionError("실패해야 한다");
    }

    private List<Map<String, Object>> lunaCalls() {
        return events.capture().events(EventCode.LUNA_CALL);
    }

    @Test
    void aValidContractOutputSucceedsWithModelVersionAndAttemptAndASingleAuditLine() {
        String output = LunaTestFixtures.example("ai-scenario-review-output.schema.json");
        stub.respond(Reply.ok("gpt-6-luna-2026-10", output));

        LunaResult result = client.call(request(LunaPurpose.SCENARIO_REVIEW));

        assertThat(result.outputJson()).isEqualTo(output);
        assertThat(result.responseModel()).isEqualTo("gpt-6-luna-2026-10");
        assertThat(result.promptVersion()).isEqualTo("u9-test.v1");
        assertThat(result.attemptsUsed()).isEqualTo(1);
        assertThat(result.schemaRetried()).isFalse();
        assertThat(result.toString()).isEqualTo("LunaResult[redacted]");
        assertThat(lunaCalls()).singleElement().satisfies(line -> assertThat(LogCapture.attrs(line))
                .containsEntry("lunaPurpose", "SCENARIO_REVIEW").containsEntry("lunaOutcome", "OK")
                .containsEntry("model", MODEL).containsEntry("responseModel", "gpt-6-luna-2026-10")
                .containsEntry("promptVersion", "u9-test.v1").containsEntry("sentFields", "utterance,goalName")
                .containsEntry("attempt", 1).containsKey("latencyMs"));
    }

    @Test
    void theRequestHasStoreFalseNoTemperatureSeparateInstructionsAndTheRequestSchemaCopy() {
        stub.respond(Reply.ok(MODEL, LunaTestFixtures.example("ai-rejudgment-output.schema.json")));

        client.call(request(LunaPurpose.REJUDGMENT));

        LunaResponsesStub.RecordedRequest sent = stub.requests().getFirst();
        Map<String, Object> body = sent.json();
        assertThat(sent.violations()).as("대역 요청 검사 모드 통과(비지원 키워드 · temperature · store)").isEmpty();
        assertThat(body).containsEntry("store", false).doesNotContainKey("temperature")
                .containsEntry("instructions", "지정한 JSON만 출력한다.").containsEntry("model", MODEL)
                .containsEntry("max_output_tokens", 4000).containsEntry("reasoning", Map.of("effort", "medium"));
        assertThat((String) body.get("input")).as("데이터는 input 자리의 JSON 문자열(BR2.5)")
                .contains("아이가 블록을 줬어", "부탁하기").doesNotContain(CHILD_NAME, "sessionId", "지정한 JSON");
        @SuppressWarnings("unchecked")
        Map<String, Object> format = (Map<String, Object>) ((Map<String, Object>) body.get("text")).get("format");
        assertThat(format).containsEntry("type", "json_schema").containsEntry("name", "rejudgment").containsEntry("strict", true);
    }

    @Test
    void eachResponseFailureIsClassifiedAndNeverReturnedAsSuccess() {
        Map<Reply, LunaFailureReason> cases = new LinkedHashMap<>();
        cases.put(Reply.incomplete(MODEL), LunaFailureReason.LENGTH_EXCEEDED);
        cases.put(Reply.emptyOutput(MODEL), LunaFailureReason.EMPTY_OUTPUT);
        cases.put(Reply.refusal(MODEL), LunaFailureReason.REFUSED);
        cases.put(Reply.noModel(LunaTestFixtures.example("ai-rejudgment-output.schema.json")), LunaFailureReason.MODEL_MISSING);
        cases.put(Reply.ok(MODEL, "{\"scenes\":[{\"sceneRunId\":\"x\",\"value\":\"none\",\"timing\":null}]}"),
                LunaFailureReason.SCHEMA_INVALID);
        cases.put(Reply.status(500), LunaFailureReason.SERVER_ERROR);
        cases.put(Reply.status(401), LunaFailureReason.AUTH_FAILED);
        cases.put(Reply.status(403), LunaFailureReason.AUTH_FAILED);
        cases.put(Reply.status(400), LunaFailureReason.REQUEST_REJECTED);
        cases.put(Reply.redirect("https://elsewhere.invalid/v1/responses"), LunaFailureReason.REQUEST_REJECTED);
        cases.put(Reply.json(200, "not json"), LunaFailureReason.SERVER_ERROR);
        cases.put(Reply.json(200, "{\"status\":\"failed\",\"model\":\"m\",\"output\":[]}"), LunaFailureReason.SERVER_ERROR);

        for (Map.Entry<Reply, LunaFailureReason> entry : cases.entrySet()) {
            stub.respond(entry.getKey());
            LunaException failure = failure(request(LunaPurpose.REJUDGMENT));
            assertThat(failure.reason()).as(entry.getValue().name()).isEqualTo(entry.getValue());
            assertThat(failure.attemptsUsed()).as("JOB_ATTEMPT는 1회(BR3.3)").isEqualTo(1);
        }
        assertThat(stub.requestCount()).isEqualTo(cases.size());
        assertThat(lunaCalls()).hasSize(cases.size()).allSatisfy(line -> assertThat(line.get("level")).isEqualTo("WARN"));
    }

    @Test
    void requestBoundedRetriesATransientFailureOnceAndASchemaMismatchOnceWithinThreeAttempts() {
        String valid = LunaTestFixtures.example("ai-scenario-review-output.schema.json");
        stub.respond(Reply.status(503), Reply.ok(MODEL, "{\"wrong\":true}"), Reply.ok(MODEL, valid));

        LunaResult result = client.call(request(LunaPurpose.SCENARIO_REVIEW));

        assertThat(result.attemptsUsed()).isEqualTo(3);
        assertThat(result.schemaRetried()).isTrue();
        assertThat(sleeper.waits).as("기술 재시도 대기 = backoff, 스키마 재요청은 대기 없음").containsExactly(Duration.ofMillis(20));
        assertThat(lunaCalls()).extracting(line -> LogCapture.attrs(line).get("lunaOutcome"))
                .containsExactly("SERVER_ERROR", "SCHEMA_INVALID", "OK");
        assertThat(lunaCalls()).extracting(line -> LogCapture.attrs(line).get("attempt")).containsExactly(1, 2, 3);

        stub.respond(Reply.status(500), Reply.status(502), Reply.ok(MODEL, valid));
        LunaException twice = failure(request(LunaPurpose.SCENARIO_REVIEW));
        assertThat(twice.reason()).as("기술 재시도는 1회뿐").isEqualTo(LunaFailureReason.SERVER_ERROR);
        assertThat(twice.attemptsUsed()).isEqualTo(2);
    }

    @Test
    void noRetryWhenTheWaitIsNotShorterThanTheTimeLeftOrTheFailureIsAuthOrTheClassForbidsIt() {
        stub.respond(Reply.rateLimited(60), Reply.ok(MODEL, LunaTestFixtures.example("ai-seed-output.schema.json")));
        LunaException rateLimited = failure(request(LunaPurpose.SEED_GENERATION));
        assertThat(rateLimited.reason()).isEqualTo(LunaFailureReason.RATE_LIMITED);
        assertThat(rateLimited.attemptsUsed()).as("Retry-After 60초 ≥ 남은 10초(NFR1.3)").isEqualTo(1);
        assertThat(sleeper.waits).isEmpty();

        stub.respond(Reply.rateLimited(1), Reply.ok(MODEL, LunaTestFixtures.example("ai-seed-output.schema.json")));
        assertThat(client.call(request(LunaPurpose.SEED_GENERATION)).attemptsUsed()).isEqualTo(2);
        assertThat(sleeper.waits).as("대기 = max(backoff, Retry-After)").containsExactly(Duration.ofSeconds(1));

        stub.respond(Reply.status(401), Reply.ok(MODEL, "{}"));
        assertThat(failure(request(LunaPurpose.SEED_GENERATION)).attemptsUsed()).as("401 재시도 없음(BR3.5)").isEqualTo(1);

        stub.respond(Reply.status(500), Reply.ok(MODEL, "{}"));
        LunaException jobAttempt = failure(request(LunaPurpose.RESULT_TEXT));
        assertThat(jobAttempt.attemptsUsed()).as("JOB_ATTEMPT 1회(BR3.3)").isEqualTo(1);
        stub.respond(Reply.status(500), Reply.ok(MODEL, "{}"));
        LunaRequest legacy = LunaRequest.legacy(LunaPurpose.SCENARIO_GENERATION, "v1", "JSON", input(), Map.of(),
                clock.now().plusSeconds(10));
        assertThat(failure(legacy).attemptsUsed()).as("기존 어댑터 요청 1회(BR3.4)").isEqualTo(1);
    }

    @Test
    void noRetryWhenTheTimeLeftAfterTheWaitIsBelowTheBackoffWindow() {
        // 시험 값: backoff 1초(최소 시도 창). 대역 응답은 빠르고 시험 시계는 대기 대역만 앞당긴다
        TextAiProperties wideBackoff = new TextAiProperties(Duration.ofSeconds(2), new TextAiProperties.Retry(1, Duration.ofSeconds(1)),
                new TextAiProperties.Result(Duration.ofSeconds(3), 0, 0.2, "v1"));
        CoreLunaClient tight = LunaTestFixtures.client(stub.baseUrl(), clock, events, sleeper, wideBackoff);
        String valid = LunaTestFixtures.example("ai-seed-output.schema.json");
        stub.respond(Reply.status(503), Reply.ok(MODEL, valid));

        LunaException tooLittle = failure(tight, LunaRequest.of(LunaPurpose.SEED_GENERATION, "v1", "JSON", input(), Map.of(),
                clock.now().plusMillis(1500)));

        assertThat(tooLittle.reason()).as("남은 1.5초 > 대기 1초지만 대기 뒤 0.5초 < backoff 1초(검토 R-03)")
                .isEqualTo(LunaFailureReason.SERVER_ERROR);
        assertThat(tooLittle.attemptsUsed()).isEqualTo(1);
        assertThat(sleeper.waits).isEmpty();
        assertThat(stub.requestCount()).isEqualTo(1);

        stub.respond(Reply.status(503), Reply.ok(MODEL, valid));
        LunaResult enough = tight.call(LunaRequest.of(LunaPurpose.SEED_GENERATION, "v1", "JSON", input(), Map.of(),
                clock.now().plusMillis(2500)));
        assertThat(enough.attemptsUsed()).as("대기 뒤 1.5초 ≥ backoff 1초면 재시도").isEqualTo(2);
        assertThat(sleeper.waits).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aRefusalBeforeSendingIsNotCountedAsAnAttempt() {
        LunaProperties props = LunaTestFixtures.properties(stub.baseUrl());
        CoreLunaClient strictHost = new CoreLunaClient(props, LunaTestFixtures.timing(), LunaSchemas.loadDefault(LunaTestFixtures.JSON),
                new LunaInputSanitizer(LunaTestFixtures.JSON), LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.STRICT),
                events.logger(), clock, LunaTestFixtures.JSON, sleeper);
        CoreLunaClient blankKey = new CoreLunaClient(props, LunaTestFixtures.timing(), LunaSchemas.loadDefault(LunaTestFixtures.JSON),
                new LunaInputSanitizer(LunaTestFixtures.JSON), new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> " ", 1000),
                events.logger(), clock, LunaTestFixtures.JSON, sleeper);
        stub.respond(Reply.ok(MODEL, LunaTestFixtures.example("ai-scenario-review-output.schema.json")));

        LunaException rejected = failure(strictHost, request(LunaPurpose.SCENARIO_REVIEW));
        LunaException noKey = failure(blankKey, request(LunaPurpose.SCENARIO_REVIEW));

        assertThat(rejected.reason()).isEqualTo(LunaFailureReason.REQUEST_REJECTED);
        assertThat(noKey.reason()).isEqualTo(LunaFailureReason.AUTH_FAILED);
        assertThat(List.of(rejected.attemptsUsed(), noKey.attemptsUsed())).as("검토 R-04: 보내지 않았으면 0").containsOnly(0);
        assertThat(stub.requestCount()).isZero();
        assertThat(lunaCalls()).extracting(line -> LogCapture.attrs(line).get("lunaOutcome"))
                .containsExactly("REQUEST_REJECTED", "AUTH_FAILED");
        assertThat(lunaCalls()).allSatisfy(line -> assertThat(LogCapture.attrs(line)).containsEntry("attempt", 0));
        assertThat(sleeper.waits).isEmpty();
    }

    private static LunaException failure(CoreLunaClient target, LunaRequest request) {
        return assertFailure(() -> target.call(request));
    }

    @Test
    void anExpiredDeadlineOrATimeoutNeverWaitsPastTheBudget() {
        LunaRequest expired = LunaRequest.of(LunaPurpose.SEED_GENERATION, "v1", "JSON", input(), Map.of(), clock.now());

        LunaException noTime = failure(expired);

        assertThat(noTime.reason()).isEqualTo(LunaFailureReason.TIMEOUT);
        assertThat(noTime.attemptsUsed()).isZero();
        assertThat(stub.requestCount()).isZero();
        assertThat(LogCapture.attrs(lunaCalls().getFirst())).containsEntry("attempt", 0).containsEntry("lunaOutcome", "TIMEOUT");

        CoreLunaClient fast = LunaTestFixtures.client(stub.baseUrl(), clock, events, sleeper,
                LunaTestFixtures.timing(Duration.ofMillis(300)));
        stub.respond(Reply.ok(MODEL, "{}").delayed(Duration.ofSeconds(3)));
        long started = System.nanoTime();
        LunaException slow = assertFailure(() -> fast.call(LunaRequest.legacy(LunaPurpose.SCENARIO_GENERATION, "v1", "JSON",
                input(), Map.of(), clock.now().plusSeconds(10))));
        assertThat(slow.reason()).isEqualTo(LunaFailureReason.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("시한 = min(마감, 목적 키 300ms)").isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void forbiddenInputIsNeverSentAndTheAuditCarriesNoBodyNameOrKey() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("broken", new LunaInputSanitizerTest.Unserializable());
        LunaRequest rejected = LunaRequest.of(LunaPurpose.RESULT_TEXT, "v1", "JSON", bad, Map.of(), clock.now().plusSeconds(10));
        stub.respond(Reply.ok(MODEL, "{\"sentences\":[\"" + CHILD_NAME + " 대역 본문\"]}"));

        LunaException forbidden = failure(rejected);
        client.call(request(LunaPurpose.RESULT_TEXT));

        assertThat(forbidden.reason()).isEqualTo(LunaFailureReason.FORBIDDEN_INPUT);
        assertThat(stub.requestCount()).as("정리 실패는 미호출").isEqualTo(1);
        assertThat(LogCapture.attrs(lunaCalls().getFirst())).containsEntry("attempt", 0).containsEntry("lunaOutcome", "FORBIDDEN_INPUT");
        LogCapture capture = events.capture();
        assertThat(capture.anyLineContains("대역 본문")).isFalse();
        assertThat(capture.anyLineContains(CHILD_NAME)).isFalse();
        assertThat(capture.anyLineContains(LunaTestFixtures.FAKE_KEY)).isFalse();
        assertThat(capture.anyLineContains("블록")).isFalse();
        assertThat(lunaCalls()).allSatisfy(line -> assertThat(LogCapture.attrs(line).keySet()).isSubsetOf(
                "lunaPurpose", "lunaOutcome", "model", "responseModel", "promptVersion", "sentFields", "latencyMs", "attempt"));
        assertThat(rejected.toString()).isEqualTo("LunaRequest[redacted]");
    }

    @Test
    void legacyRequestsAskForAJsonObjectAndAcceptAnyObject() {
        stub.respond(Reply.ok(MODEL, "{\"scenarioText\":\"s\"}"));

        LunaResult result = client.call(LunaRequest.legacy(LunaPurpose.SCENARIO_GENERATION, "scenario-generation.v1", "JSON",
                input(), Map.of(), clock.now().plusSeconds(10)));

        assertThat(result.outputJson()).isEqualTo("{\"scenarioText\":\"s\"}");
        assertThat(stub.requests().getFirst().json().get("text")).isEqualTo(Map.of("format", Map.of("type", "json_object")));
        assertThat(LogCapture.attrs(lunaCalls().getFirst())).doesNotContainKey("responseModel");
        stub.respond(Reply.ok(MODEL, "[1,2]"));
        assertThat(failure(LunaRequest.legacy(LunaPurpose.SCENARIO_GENERATION, "v1", "JSON", input(), Map.of(),
                clock.now().plusSeconds(10))).reason()).isEqualTo(LunaFailureReason.SCHEMA_INVALID);
    }

    @Test
    void requestRecordsValidateTheirInputs() {
        Instant deadline = clock.now();
        assertThatThrownBy(() -> LunaRequest.of(LunaPurpose.RESULT_TEXT, "bad version", "i", Map.of(), Map.of(), deadline))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LunaRequest.of(LunaPurpose.RESULT_TEXT, "v1", " ", Map.of(), Map.of(), deadline))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaResult("{}", MODEL, "v1", Duration.ZERO, 4, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaException(LunaFailureReason.TIMEOUT, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LunaRequest.of(LunaPurpose.RESULT_TEXT, "v1", "i", Map.of(), null, deadline).aliases()).isEmpty();
        assertThat(LunaFailureReason.EMPTY_OUTPUT.isTransient()).isTrue();
        assertThat(LunaFailureReason.TIMEOUT.isTransient()).isFalse();
    }

    private static LunaException assertFailure(Runnable call) {
        try {
            call.run();
        } catch (LunaException e) {
            return e;
        }
        throw new AssertionError("실패해야 한다");
    }
}
