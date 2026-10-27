package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.usage.AiUsageCollector;
import kr.mujoong.core.usage.AiUsageLink;
import kr.mujoong.core.usage.AiUsageRecord;
import kr.mujoong.core.usage.AiUsageScope;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.luna.LunaResponsesStub.Reply;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * JIRA-277: 실제로 보낸 Luna 시도마다 사용량 한 행. 공급자는 루프백 대역, 키는 가짜 문자열이다(R-F1 · R-F3). 증거 종류: 테스트 대역(R-M11).
 */
@Tag("ai-usage")
class CoreLunaUsageTest {

    private static final String MODEL = "gpt-6-luna";

    private LunaResponsesStub stub;
    private MutableClock clock;
    private List<AiUsageRecord> offered;
    private CoreLunaClient client;

    @BeforeEach
    void setUp() {
        stub = LunaResponsesStub.start();
        clock = new MutableClock();
        offered = new ArrayList<>();
        client = client(record -> offered.add(record));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private CoreLunaClient client(AiUsageCollector usage) {
        TestEvents events = new TestEvents(clock);
        return new CoreLunaClient(LunaTestFixtures.properties(stub.baseUrl()), LunaTestFixtures.timing(),
                LunaSchemas.loadDefault(LunaTestFixtures.JSON), new LunaInputSanitizer(LunaTestFixtures.JSON),
                LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST), events.logger(), clock, LunaTestFixtures.JSON,
                new LunaTestFixtures.ClockSleeper(clock), usage);
    }

    private LunaRequest request(LunaPurpose purpose) {
        return LunaRequest.of(purpose, "usage-test.v1", "지정한 JSON만 출력한다.", Map.of("utterance", "블록을 줬어"), Map.of(),
                clock.now().plusSeconds(10));
    }

    /** Responses 모양 본문에 사용량을 붙인 합성 응답. */
    private static Reply withUsage(String responseId, String outputText, long input, long output) {
        Map<String, Object> message = Map.of("type", "message", "role", "assistant",
                "content", List.of(Map.of("type", "output_text", "text", outputText)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", responseId);
        body.put("object", "response");
        body.put("status", "completed");
        body.put("model", MODEL);
        body.put("output", List.of(message));
        body.put("usage", Map.of("input_tokens", input, "output_tokens", output,
                "input_tokens_details", Map.of("cached_tokens", 0, "cache_write_tokens", 100),
                "output_tokens_details", Map.of("reasoning_tokens", 5)));
        return Reply.json(200, LunaTestFixtures.JSON.write(body));
    }

    @Test
    void aSuccessfulCallRecordsOneCompleteRowLinkedToTheOpenScope() {
        String output = LunaTestFixtures.example("ai-scenario-review-output.schema.json");
        stub.respond(withUsage("resp_a", output, 1200, 80));
        AiUsageLink link = new AiUsageLink(UUID.randomUUID(), UUID.randomUUID());

        AiUsageScope.call(link, () -> client.call(request(LunaPurpose.SCENARIO_REVIEW)));

        assertThat(offered).singleElement().satisfies(record -> {
            assertThat(record.link()).isEqualTo(link);
            assertThat(record.purpose()).isEqualTo("SCENARIO_REVIEW");
            assertThat(record.attemptNo()).isEqualTo(1);
            assertThat(record.outcome()).isEqualTo("OK");
            assertThat(record.status()).isEqualTo(AiUsageRecord.Status.COMPLETE);
            assertThat(record.inputTokens()).isEqualTo(1200L);
            assertThat(record.outputTokens()).isEqualTo(80L);
            assertThat(record.reasoningTokens()).isEqualTo(5L);
            assertThat(record.cacheWriteTokens()).isEqualTo(100L);
            assertThat(record.responseId()).isEqualTo("resp_a");
            assertThat(record.promptVersion()).isEqualTo("usage-test.v1");
        });
        assertThat(stub.requests()).singleElement().satisfies(sent -> assertThat(sent.body())
                .as("연결 대상은 공급자에게 가지 않는다")
                .doesNotContain(link.guardianId().toString()).doesNotContain(link.sessionId().toString()));
    }

    @Test
    void everySentAttemptOfOneCallIsRecordedUnderTheSameCallId() {
        String valid = LunaTestFixtures.example("ai-scenario-review-output.schema.json");
        stub.respond(Reply.status(503), withUsage("resp_b1", "{\"wrong\":true}", 500, 30), withUsage("resp_b2", valid, 520, 40));

        client.call(request(LunaPurpose.SCENARIO_REVIEW));

        assertThat(offered).extracting(AiUsageRecord::attemptNo).containsExactly(1, 2, 3);
        assertThat(offered).extracting(AiUsageRecord::outcome).containsExactly("SERVER_ERROR", "SCHEMA_INVALID", "OK");
        assertThat(offered).extracting(AiUsageRecord::status).containsExactly(AiUsageRecord.Status.UNKNOWN,
                AiUsageRecord.Status.COMPLETE, AiUsageRecord.Status.COMPLETE);
        assertThat(offered).extracting(AiUsageRecord::callId).containsOnly(offered.getFirst().callId());
        assertThat(offered).extracting(AiUsageRecord::id).doesNotHaveDuplicates();
        assertThat(offered).as("열린 범위가 없으면 연결 미확인").allSatisfy(record -> assertThat(record.link()).isNull());
    }

    @Test
    void aRequestStoppedBeforeSendingRecordsNothing() {
        LunaRequest expired = LunaRequest.of(LunaPurpose.RESULT_TEXT, "usage-test.v1", "지정한 JSON만 출력한다.", Map.of("a", "b"),
                Map.of(), clock.now());

        assertThatThrownBy(() -> client.call(expired)).isInstanceOf(LunaException.class);

        assertThat(stub.requestCount()).isZero();
        assertThat(offered).isEmpty();
    }

    @Test
    void aFailingCollectorNeverChangesTheCallResult() {
        String output = LunaTestFixtures.example("ai-scenario-review-output.schema.json");
        stub.respond(withUsage("resp_c", output, 10, 2));
        CoreLunaClient failing = client(record -> {
            throw new IllegalStateException("큐 고장");
        });

        LunaResult result = failing.call(request(LunaPurpose.SCENARIO_REVIEW));

        assertThat(result.outputJson()).isEqualTo(output);
    }

    @Test
    void aDisabledCollectorIsNotAsked() {
        stub.respond(withUsage("resp_d", LunaTestFixtures.example("ai-scenario-review-output.schema.json"), 10, 2));
        List<AiUsageRecord> seen = new ArrayList<>();
        CoreLunaClient disabled = client(new AiUsageCollector() {
            @Override
            public boolean offer(AiUsageRecord record) {
                seen.add(record);
                return true;
            }

            @Override
            public boolean enabled() {
                return false;
            }
        });

        disabled.call(request(LunaPurpose.SCENARIO_REVIEW));

        assertThat(seen).isEmpty();
    }
}
