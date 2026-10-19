package kr.mujoong.realtime.voice;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 공급자 선 형식 U3 추가분 통합 시험(편차 (aa) → (U11-a) · U3-R7 · U3-J12 · U3-J17, 설계 개정 A1 · A7).
 *
 * <p>실제 JDK WebSocket 클라이언트와 가짜 공급자 WS 서버로 지시 추가가 정한 키({@code type · event_id · delegation_id · content})만 갖고 나가는지,
 * 응답 요청 type이 나가지 않는지, 공급자가 보내지 않는 입력 전사 완료 사건은 앱 사건으로 읽지 않고 모르는 type으로만 세는지 본다. 신뢰도 변환 규칙
 * (0 ~ 1일 때만)은 그대로 남아 있다.
 */
@Tag("voice-session")
class ProviderClientVoiceSessionIT {

    private static final JsonCodec JSON = JsonCodec.shared();

    @Test
    void instructionsAppendGoesOnTheWireWithOnlyItsKeysAndNoResponseCreateExists() throws Exception {
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), TestSecrets.base64Url(24), JSON, Duration.ofSeconds(2));
            try {
                List<ProviderEvent> events = new CopyOnWriteArrayList<>();
                ProviderPort.ProviderSession session = client.open(events::add, Duration.ofSeconds(2));
                ProviderRequestFactory factory = new ProviderRequestFactory();
                session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of("공룡"))));
                awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Started.class::isInstance), "session.started");

                session.appendInstructions(factory.instructionsAppend("mj-1", DialogInstructions.Correction.PRESSURE));

                List<JsonNode> corrections = server.await("session.instructions.append", 1, WAIT);
                assertThat(server.received("response.create")).as("응답 요청 type은 없다(client 위임 세션은 거절한다)").isEmpty();
                assertThat(server.received()).extracting(node -> node.get("type").asString())
                        .as("보낸 type이 모두 공급자 지원 목록 안이다").allMatch(FakeProviderWsServer.SUPPORTED_TYPES::contains);
                assertThat(corrections).singleElement().satisfies(correction -> {
                    assertThat(keys(correction)).containsExactlyInAnyOrder("type", "event_id", "delegation_id", "content");
                    assertThat(correction.get("delegation_id").isNull()).as("null이어도 키는 있다").isTrue();
                    assertThat(correction.get("content").asString())
                            .isEqualTo(InstructionTemplates.correctionContent(DialogInstructions.Correction.PRESSURE));
                });
                // 지시 확인 사건: client_event_id가 우리가 실은 event_id와 맞고 start_ms · end_ms가 온다
                awaitTrue(() -> events.stream().anyMatch(ProviderEvent.InstructionsAppended.class::isInstance), "지시 확인 사건");
                assertThat(events.stream().filter(ProviderEvent.InstructionsAppended.class::isInstance).findFirst().orElseThrow())
                        .isInstanceOfSatisfying(ProviderEvent.InstructionsAppended.class, ack -> {
                            assertThat(ack.clientEventId()).isEqualTo("mj-1");
                            assertThat(ack.startMs()).isGreaterThanOrEqualTo(0L);
                            assertThat(ack.endMs()).isGreaterThan(ack.startMs());
                        });

                // 공급자는 입력 전사 완료 사건을 보내지 않는다(설계 개정 A7): 와도 앱 사건으로 읽지 않는다
                server.emit(done("i1", "응", 0.42));
                server.emit(Map.of("type", "session.input_transcript.delta", "delta", "응", "start_ms", 10, "end_ms", 20));
                awaitTrue(() -> events.stream().anyMatch(ProviderEvent.InputTranscriptDelta.class::isInstance), "입력 전사 조각");
                assertThat(events).noneMatch(ProviderEvent.InputTranscriptDone.class::isInstance);
                session.close(factory.sessionClose());
            } finally {
                client.destroy();
            }
        }
    }

    @Test
    void theConfidenceFilterKeepsOnlyFiniteValuesBetweenZeroAndOne() {
        assertThat(ProviderClient.confidence(0.0)).isEqualTo(0.0);
        assertThat(ProviderClient.confidence(1.0)).isEqualTo(1.0);
        assertThat(ProviderClient.confidence(0.5)).isEqualTo(0.5);
        assertThat(ProviderClient.confidence(null)).isNull();
        assertThat(ProviderClient.confidence(Double.NaN)).isNull();
        assertThat(ProviderClient.confidence(1.0000001)).isNull();
        assertThat(ProviderClient.confidence(-0.0000001)).isNull();
        assertThat(ProviderClient.confidence(1)).isEqualTo(1.0);
        assertThat(ProviderClient.confidence("0.9")).as("문자열은 숫자로 보지 않는다").isNull();
        // 입력 전사 완료 사건은 공급자가 보내지 않는다: 앱 사건으로 읽지 않고 무시한다(모르는 type으로 센다)
        assertThat(ProviderClient.parse(JSON, "{\"type\":\"session.input_transcript.done\",\"itemId\":\"i\","
                + "\"transcript\":\"네\",\"confidence\":\"높음\"}")).isNull();
    }

    private static Map<String, Object> done(String itemId, String transcript, Double confidence) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "session.input_transcript.done");
        event.put("itemId", itemId);
        event.put("transcript", transcript);
        if (confidence != null) {
            event.put("confidence", confidence);
        }
        return event;
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.properties().forEach(entry -> keys.add(entry.getKey()));
        return keys;
    }
}
