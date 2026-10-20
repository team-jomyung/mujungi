package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 공급자 해석기가 실호출로 관측한 수신 형식을 앱 이벤트로 바꾸는지 (U11 FR12.2 · BR11.3 · 편차 (U11-a)). */
@Tag("judgment-turn")
class ProviderReaderWireTest {

    private static final JsonCodec JSON = JsonCodec.shared();

    private static ProviderEvent parse(String message) {
        return ProviderClient.parse(JSON, message);
    }

    @Test
    void continuousDigitalSilenceIsSeparateFromEvenTheQuietestSpeech() {
        assertThat(parse("{\"type\":\"session.output_audio.delta\",\"delta\":\"AAAAAA==\"}"))
                .isEqualTo(new ProviderEvent.OutputSilenceDelta(4));
        assertThat(parse("{\"type\":\"session.output_audio.delta\",\"delta\":\"AQAAAA==\"}"))
                .isInstanceOf(ProviderEvent.OutputAudioDelta.class);
        // An odd PCM length remains invalid audio for the existing gate; it is not accepted as silence.
        assertThat(parse("{\"type\":\"session.output_audio.delta\",\"delta\":\"AA==\"}"))
                .isInstanceOf(ProviderEvent.OutputAudioDelta.class);
    }

    @Test
    void outputAudioIsReadFromDeltaWithNoResponseId() {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        String message = "{\"type\":\"session.output_audio.delta\",\"delta\":\"" + Base64.getEncoder().encodeToString(pcm) + "\"}";

        ProviderEvent event = parse(message);

        assertThat(event).isInstanceOf(ProviderEvent.OutputAudioDelta.class);
        assertThat(((ProviderEvent.OutputAudioDelta) event).audio()).isEqualTo(pcm);
    }

    @Test
    void anUndecodableAudioChunkIsDroppedNotFatal() {
        assertThat(parse("{\"type\":\"session.output_audio.delta\",\"delta\":\"@@@ not base64 @@@\"}")).isNull();
        assertThat(parse("{\"type\":\"session.output_audio.delta\"}")).as("delta 없음 = 빈 조각").isInstanceOf(
                ProviderEvent.OutputAudioDelta.class);
    }

    @Test
    void errorEventsBecomeErrorWithTheCodeOnlyAndNeverTheMessage() {
        ProviderEvent event = parse("{\"type\":\"error\",\"event_id\":\"e1\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"code\":\"invalid_value\",\"message\":\"Invalid value: 'x'\",\"param\":\"type\"}}");

        assertThat(event).isEqualTo(new ProviderEvent.Error("invalid_value"));
        assertThat(event.toString()).doesNotContain("Invalid value");
        assertThat(parse("{\"type\":\"error\"}")).as("본문 없는 오류도 오류다").isEqualTo(new ProviderEvent.Error(""));
        assertThat(parse("{\"type\":\"session.error\",\"code\":\"x\"}")).as("옛 session.error는 더 이상 모르는 type").isNull();
    }

    @Test
    void usageUpdatedCarriesTheSecondsOnly() {
        ProviderEvent event = parse("{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":12.5},"
                + "\"context_window\":{\"usage_ratio\":0.1},\"event_id\":\"e\"}");

        assertThat(event).isEqualTo(new ProviderEvent.UsageUpdated(12.5));
        assertThat(parse("{\"type\":\"session.usage.updated\"}")).isNull();
        assertThat(parse("{\"type\":\"session.usage.updated\",\"usage\":{\"seconds\":-1}}")).isNull();
    }

    @Test
    void startedReadsTheProviderSessionIdFromTheNestedSession() {
        assertThat(parse("{\"type\":\"session.started\",\"event_id\":\"e\",\"session\":{\"id\":\"sess_1\",\"model\":\"m\"}}"))
                .isEqualTo(new ProviderEvent.Started("sess_1"));
        assertThat(parse("{\"type\":\"session.started\"}")).isEqualTo(new ProviderEvent.Started(null));
        assertThat(parse("{\"type\":\"session.closed\",\"reason\":\"client\"}")).isInstanceOf(ProviderEvent.Closed.class);
    }

    @Test
    void unknownTypesAreCountedAndNeverTurnIntoEvents() {
        AtomicInteger unknown = new AtomicInteger();

        assertThat(ProviderClient.parse(JSON, "{\"type\":\"session.output.done\",\"responseId\":\"r1\"}", unknown::incrementAndGet))
                .as("응답 끝 사건은 실호출에서 관측되지 않아 모르는 type이다").isNull();
        assertThat(ProviderClient.parse(JSON, "{\"type\":\"something.new\"}", unknown::incrementAndGet)).isNull();
        assertThat(ProviderClient.parse(JSON, "not json", unknown::incrementAndGet)).as("깨진 본문은 세지 않는다").isNull();
        assertThat(ProviderClient.parse(JSON, "{\"x\":1}", unknown::incrementAndGet)).isNull();

        assertThat(unknown).hasValue(2);
    }

    @Test
    void theReaderCountsUnknownMessagesAndStillDeliversKnownOnes() {
        java.util.List<ProviderEvent> events = new java.util.ArrayList<>();
        ProviderClient.Reader reader = new ProviderClient.Reader(events::add, JSON);
        java.net.http.WebSocket socket = org.mockito.Mockito.mock(java.net.http.WebSocket.class);

        reader.onText(socket, "{\"type\":\"session.started\"}", true);
        reader.onText(socket, "{\"type\":\"mystery\"}", true);
        reader.onText(socket, "{\"type\":\"session.output_audio.delta\",\"delta\":\"AQI=\"}", true);

        assertThat(events).hasSize(2);
        assertThat(reader.unknownTypeCount()).isEqualTo(1);
    }
}
