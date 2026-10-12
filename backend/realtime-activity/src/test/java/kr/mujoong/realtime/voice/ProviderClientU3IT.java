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
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 공급자 선 형식 U3 추가분 통합 시험(편차 (aa) · U3-R5 · U3-R7 · U3-J11 · U3-J12 · U3-J17).
 *
 * <p>실제 JDK WebSocket 클라이언트와 가짜 공급자 WS 서버로 응답 취소 · 지시 교정이 정한 키만 갖고 나가는지, 확정 전사의 선택 필드
 * {@code confidence}를 0 ~ 1일 때만 읽는지 본다.
 */
@Tag("u3")
class ProviderClientU3IT {

    private static final JsonCodec JSON = JsonCodec.shared();

    @Test
    void cancelAndCorrectionGoOnTheWireWithOnlyTheirKeysAndConfidenceIsReadOnlyWhenInRange() throws Exception {
        try (FakeProviderWsServer server = FakeProviderWsServer.start()) {
            ProviderClient client = new ProviderClient(server.uri(), TestSecrets.base64Url(24), JSON, Duration.ofSeconds(2));
            try {
                List<ProviderEvent> events = new CopyOnWriteArrayList<>();
                ProviderPort.ProviderSession session = client.open(events::add, Duration.ofSeconds(2));
                ProviderRequestFactory factory = new ProviderRequestFactory();
                session.start(factory.sessionStart(new ChildContext("하늘", 8, List.of("공룡"))));
                awaitTrue(() -> events.stream().anyMatch(ProviderEvent.Started.class::isInstance), "session.started");

                session.cancelResponse(factory.responseCancel(ProviderResponseId.of("resp-1").orElseThrow()));
                session.cancelResponse(factory.responseCancel(null));
                session.correctInstructions(factory.instructionsCorrect(DialogInstructions.Correction.PRESSURE));

                List<JsonNode> cancels = server.await("session.response.cancel", 2, WAIT);
                List<JsonNode> corrections = server.await("session.instructions.correct", 1, WAIT);
                assertThat(cancels).hasSize(2);
                assertThat(keys(cancels.get(0))).containsExactlyInAnyOrder("type", "responseId");
                assertThat(cancels.get(0).get("responseId").asString()).isEqualTo("resp-1");
                assertThat(keys(cancels.get(1))).as("id를 모르면 지금 응답 취소(키 없음)").containsExactly("type");
                assertThat(corrections).singleElement().satisfies(correction -> {
                    assertThat(keys(correction)).containsExactlyInAnyOrder("type", "text");
                    assertThat(correction.get("text").asString())
                            .isEqualTo(DialogInstructions.Correction.PRESSURE.text());
                });

                server.emit(done("i1", "응", 0.42));
                server.emit(done("i2", "좋아", 1.7));
                server.emit(done("i3", "몰라", -0.1));
                server.emit(done("i4", "글쎄", null));
                awaitTrue(() -> events.stream().filter(ProviderEvent.InputTranscriptDone.class::isInstance).count() == 4,
                        "확정 전사 4건");

                List<Double> confidences = new ArrayList<>();
                events.stream().filter(ProviderEvent.InputTranscriptDone.class::isInstance)
                        .map(ProviderEvent.InputTranscriptDone.class::cast)
                        .forEach(event -> confidences.add(event.confidence()));
                assertThat(confidences).containsExactly(0.42, null, null, null);
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
        // 숫자가 아닌 confidence가 와도 확정 전사는 잃지 않는다(신뢰도만 없는 것으로 본다)
        assertThat(ProviderClient.parse(JSON, "{\"type\":\"session.input_transcript.done\",\"itemId\":\"i\","
                + "\"transcript\":\"네\",\"confidence\":\"높음\"}"))
                .isEqualTo(new ProviderEvent.InputTranscriptDone("i", "네", null));
        assertThat(ProviderClient.parse(JSON, "{\"type\":\"session.input_transcript.done\",\"itemId\":\"i\","
                + "\"transcript\":\"네\",\"confidence\":{\"v\":1}}"))
                .isEqualTo(new ProviderEvent.InputTranscriptDone("i", "네", null));
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
