package kr.mujoong.contract.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.WsEnvelope.Decoded;
import kr.mujoong.contract.ws.WsEnvelope.Outcome;
import kr.mujoong.contract.ws.message.Ack;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.Heartbeat;
import kr.mujoong.contract.ws.message.HeartbeatAck;
import kr.mujoong.contract.ws.message.Hello;
import kr.mujoong.contract.ws.message.HelloOk;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.OutputEnd;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.contract.ws.message.WsError;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 기기 WSS 제어 봉투(계약 4.2 · J27 · J123 · 편차 (s)): seq · opId 4종 한정 · sessionId · ACK.ackOf · 4400 대 ERROR 갈림. */
@Tag("u1")
class WsEnvelopeCodecTest {

    private static final JsonCodec CODEC = JsonCodec.shared();
    private static final Instant SENT_AT = Instant.parse("2026-10-15T09:02:00.250Z");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000010");
    private static final UUID OP_ID = UUID.fromString("00000000-0000-4000-8000-000000000070");
    private static final UUID PLAYBACK_ID = UUID.fromString("00000000-0000-4000-8000-000000000080");
    private static final UUID CONTENT_VERSION_ID = UUID.fromString("00000000-0000-4000-8000-000000000090");

    /** 17종 각각의 정상 payload. */
    static Stream<Arguments> validMessages() {
        return Stream.of(
                Arguments.of(new Hello("1.0.0", List.of("pcm16le/24000/1"), null)),
                Arguments.of(new HelloOk(UUID.randomUUID(), SENT_AT, 5000L, HelloOk.AUDIO_PCM16LE_24K_MONO,
                        new HelloOk.SessionInfo(SESSION_ID, View.LISTENING, false))),
                Arguments.of(new Heartbeat(3L, 42L)),
                Arguments.of(new HeartbeatAck(3L, SENT_AT)),
                Arguments.of(new StartSession(List.of(new StartSession.ApprovedAudio(
                        PlayApprovedContent.CLOSING, CONTENT_VERSION_ID, "/media/approved/closing-v1.wav")))),
                Arguments.of(new Ack(OP_ID)),
                Arguments.of(new DeviceReady(DeviceReady.Availability.OK, DeviceReady.Availability.OK,
                        DeviceReady.Prefetch.PARTIAL)),
                Arguments.of(new SessionState(View.THINKING)),
                Arguments.of(new OutputBegin(PLAYBACK_ID, OutputBegin.Source.GPT_LIVE)),
                Arguments.of(new OutputEnd(PLAYBACK_ID, 1800L)),
                Arguments.of(new PlayApprovedContent(PLAYBACK_ID, PlayApprovedContent.WAITING_FILLER, CONTENT_VERSION_ID)),
                Arguments.of(new PlaybackMark(PLAYBACK_ID, PlaybackMark.Status.STOPPED,
                        new PlaybackMark.PlayedRange(0L, 900L), SENT_AT, PlaybackMark.StopCause.STOP_OUTPUT, null)),
                Arguments.of(new StopOutput(StopOutput.Reason.GUARDIAN_STOP)),
                Arguments.of(new CloseTransport(CloseTransport.Reason.ENDED)),
                Arguments.of(new VoiceSessionClosed(SENT_AT, SENT_AT.plusMillis(20))),
                Arguments.of(new DeviceState(DeviceState.Mic.LOST, null, DeviceState.Visibility.HIDDEN, null)),
                Arguments.of(new WsError(WsError.Code.UNKNOWN_TYPE, 9L)));
    }

    @ParameterizedTest
    @MethodSource("validMessages")
    void everyMessageTypeRoundTripsWithItsOpIdAndSessionIdRules(WsPayload payload) {
        WsMessageType type = payload.messageType();
        WsEnvelope sent = WsEnvelope.of(payload, 7, SENT_AT, type.isOpIdRequired() ? OP_ID : null,
                type.isSessionIdRequired() ? SESSION_ID : null);

        String json = CODEC.encodeWs(sent);
        Decoded decoded = CODEC.decodeWs(json);

        assertThat(decoded.outcome()).isEqualTo(Outcome.OK);
        assertThat(decoded.isOk()).isTrue();
        assertThat(decoded.envelope()).isEqualTo(sent);
        assertThat(decoded.envelope().messageType()).isEqualTo(type);
        assertThat(json).contains("\"sentAt\":\"2026-10-15T09:02:00.250Z\"");
        assertThat(json.contains("\"opId\"")).isEqualTo(type.isOpIdRequired());
    }

    @Test
    void opIdIsRequiredOnExactlyTheFourServerCommands() {
        assertThat(Arrays.stream(WsMessageType.values()).filter(WsMessageType::isOpIdRequired)).containsExactlyInAnyOrder(
                WsMessageType.START_SESSION, WsMessageType.PLAY_APPROVED_CONTENT, WsMessageType.STOP_OUTPUT,
                WsMessageType.CLOSE_TRANSPORT);
        assertThat(Arrays.stream(WsMessageType.values())
                        .filter(WsMessageType::isOpIdRequired)
                        .map(WsMessageType::direction))
                .containsOnly(WsMessageType.Direction.SERVER_TO_DEVICE);
        assertThat(WsMessageType.values()).hasSize(18);
    }

    @Test
    void missingOpIdOnACommandAndOpIdOnAnyOtherTypeAreBadPayload() {
        WsEnvelope commandWithoutOpId = new WsEnvelope("STOP_OUTPUT", 1, SENT_AT, null, SESSION_ID,
                new StopOutput(StopOutput.Reason.SILENCE));
        WsEnvelope heartbeatWithOpId = new WsEnvelope("HEARTBEAT", 2, SENT_AT, OP_ID, null, new Heartbeat(1L, null));

        assertThat(commandWithoutOpId.problems()).containsExactly("opId:missing");
        assertThat(heartbeatWithOpId.problems()).containsExactly("opId:notAllowed");
        Decoded decoded = CODEC.decodeWs("""
                {"type":"HEARTBEAT","seq":2,"sentAt":"2026-10-15T09:02:00Z","opId":"%s","payload":{"hbSeq":1}}
                """.formatted(OP_ID));
        assertThat(decoded.outcome()).isEqualTo(Outcome.BAD_PAYLOAD);
        assertThat(decoded.problems()).containsExactly("opId:notAllowed");
        assertThat(decoded.type()).isEqualTo("HEARTBEAT");
        assertThat(decoded.seq()).isEqualTo(2L);
        assertThatThrownBy(() -> CODEC.encodeWs(commandWithoutOpId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opId:missing");
    }

    @Test
    void ackCarriesTheCommandOpIdAsAckOf() {
        Decoded decoded = CODEC.decodeWs("""
                {"type":"ACK","seq":3,"sentAt":"2026-10-15T09:02:00Z","payload":{"ackOf":"%s"}}
                """.formatted(OP_ID));

        assertThat(decoded.isOk()).isTrue();
        assertThat(((Ack) decoded.envelope().payload()).ackOf()).isEqualTo(OP_ID);
        Decoded missing = CODEC.decodeWs("""
                {"type":"ACK","seq":3,"sentAt":"2026-10-15T09:02:00Z","payload":{}}
                """);
        assertThat(missing.outcome()).isEqualTo(Outcome.BAD_PAYLOAD);
        assertThat(missing.problems()).containsExactly("payload.ackOf:missing");
    }

    @Test
    void sessionMessagesWithoutSessionIdAreRejected() {
        WsEnvelope envelope = new WsEnvelope("DEVICE_READY", 4, SENT_AT, null, null,
                new DeviceReady(DeviceReady.Availability.OK, DeviceReady.Availability.UNAVAILABLE, DeviceReady.Prefetch.OK));

        assertThat(envelope.problems()).containsExactly("sessionId:missing");
        assertThat(((DeviceReady) envelope.payload()).isReady()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 4_294_967_296L})
    void seqOutsideOneToUint32MaxIsMalformed(long seq) {
        Decoded decoded = CODEC.decodeWs("""
                {"type":"HEARTBEAT","seq":%d,"sentAt":"2026-10-15T09:02:00Z","payload":{"hbSeq":1}}
                """.formatted(seq));

        assertThat(decoded.outcome()).isEqualTo(Outcome.MALFORMED);
        assertThat(decoded.problems()).containsExactly("seq:invalid");
        assertThat(new WsEnvelope("HEARTBEAT", seq, SENT_AT, null, null, new Heartbeat(1L, null)).problems())
                .containsExactly("seq:range");
    }

    @Test
    void seqBoundaryUint32MaxIsAccepted() {
        Decoded decoded = CODEC.decodeWs("""
                {"type":"HEARTBEAT","seq":4294967295,"sentAt":"2026-10-15T09:02:00Z","payload":{"hbSeq":1}}
                """);

        assertThat(decoded.isOk()).isTrue();
        assertThat(decoded.envelope().seq()).isEqualTo(WsEnvelope.MAX_SEQ);
    }

    @Test
    void sequencerStartsAtOneAndIncrementsByOne() {
        WsEnvelope.Sequencer sequencer = new WsEnvelope.Sequencer();

        assertThat(sequencer.next()).isEqualTo(1);
        assertThat(sequencer.next()).isEqualTo(2);
        assertThat(sequencer.next()).isEqualTo(3);
    }

    @Test
    void malformedFramesAreDistinguishedFromUnknownTypes() {
        assertThat(CODEC.decodeWs("{not json").outcome()).isEqualTo(Outcome.MALFORMED);
        assertThat(CODEC.decodeWs(null).outcome()).isEqualTo(Outcome.MALFORMED);
        assertThat(CODEC.decodeWs("\"text\"").problems()).containsExactly("body:notJsonObject");
        Decoded missingFields = CODEC.decodeWs("{\"seq\":1,\"payload\":[]}");
        assertThat(missingFields.outcome()).isEqualTo(Outcome.MALFORMED);
        assertThat(missingFields.problems()).containsExactly("type:missing", "sentAt:missing", "payload:missing");

        Decoded unknown = CODEC.decodeWs("""
                {"type":"TELEPORT","seq":5,"sentAt":"2026-10-15T09:02:00Z","payload":{"x":1}}
                """);
        assertThat(unknown.outcome()).isEqualTo(Outcome.UNKNOWN_TYPE);
        assertThat(unknown.type()).isEqualTo("TELEPORT");
        assertThat(unknown.seq()).isEqualTo(5L);
        assertThat(unknown.envelope()).isNull();
    }

    @Test
    void badPayloadsAreReportedByFieldNameAndUnknownFieldsAreIgnored() {
        Decoded badEnum = CODEC.decodeWs("""
                {"type":"SESSION_STATE","seq":6,"sentAt":"2026-10-15T09:02:00Z","payload":{"view":"DANCING"}}
                """);
        Decoded badUuid = CODEC.decodeWs("""
                {"type":"START_SESSION","seq":6,"sentAt":"2026-10-15T09:02:00Z","opId":"nope","sessionId":7,
                 "payload":{"approvedAudio":[]}}
                """);
        Decoded wrongShape = CODEC.decodeWs("""
                {"type":"HEARTBEAT","seq":6,"sentAt":"2026-10-15T09:02:00Z","payload":{"hbSeq":{"deep":true}}}
                """);
        Decoded extraField = CODEC.decodeWs("""
                {"type":"SESSION_STATE","seq":6,"sentAt":"2026-10-15T09:02:00Z","future":1,
                 "payload":{"view":"CLOSING","futureField":"x"}}
                """);

        assertThat(badEnum.outcome()).isEqualTo(Outcome.BAD_PAYLOAD);
        assertThat(badEnum.problems()).containsExactly("payload.view:invalid");
        assertThat(badUuid.outcome()).isEqualTo(Outcome.BAD_PAYLOAD);
        assertThat(badUuid.problems()).containsExactly("opId:invalid", "sessionId:invalid");
        assertThat(wrongShape.outcome()).isEqualTo(Outcome.BAD_PAYLOAD);
        assertThat(wrongShape.problems()).containsExactly("payload:invalid");
        assertThat(extraField.isOk()).isTrue();
        assertThat(((SessionState) extraField.envelope().payload()).view()).isEqualTo(View.CLOSING);
    }

    @Test
    void payloadTypeMismatchAndUnknownTypeAreProblemsWhenSending() {
        WsEnvelope mismatch = new WsEnvelope("HEARTBEAT", 1, SENT_AT, null, null, new SessionState(View.STANDBY));
        WsEnvelope unknown = new WsEnvelope("TELEPORT", 1, null, null, null, null);

        assertThat(mismatch.problems()).containsExactly("payload:typeMismatch");
        assertThat(unknown.problems()).containsExactly("type:unknown", "sentAt:missing", "payload:missing");
        assertThatThrownBy(() -> WsEnvelope.of(null, 1, SENT_AT, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Decoded.failure(Outcome.OK, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                Arguments.of(new Hello(" ", List.of(), new Hello.Resume(null)),
                        List.of("appVersion:invalid", "audio:invalid", "resume.sessionId:missing")),
                Arguments.of(new Hello("1.0.0", List.of("PCM 16"), null), List.of("audio:format")),
                Arguments.of(new HelloOk(null, null, 0L, "mp3", new HelloOk.SessionInfo(null, null, null)),
                        List.of("connectionId:missing", "serverTime:missing", "heartbeatIntervalMs:invalid", "audio:invalid",
                                "session:invalid")),
                Arguments.of(new Heartbeat(-1L, -1L), List.of("hbSeq:invalid", "lastRttMs:negative")),
                Arguments.of(new HeartbeatAck(null, null), List.of("hbSeq:invalid", "serverTime:missing")),
                Arguments.of(new StartSession(null), List.of("approvedAudio:missing")),
                Arguments.of(new StartSession(List.of(new StartSession.ApprovedAudio("closing", CONTENT_VERSION_ID,
                        "https://example.invalid/a.wav"))), List.of("approvedAudio:invalid")),
                Arguments.of(new DeviceReady(null, null, null),
                        List.of("mic:missing", "player:missing", "prefetch:missing")),
                Arguments.of(new SessionState(View.UNRECOGNIZED), List.of("view:invalid")),
                Arguments.of(new OutputBegin(null, OutputBegin.Source.UNRECOGNIZED),
                        List.of("playbackId:missing", "source:invalid")),
                Arguments.of(new OutputEnd(null, -1L), List.of("playbackId:missing", "totalMs:negative")),
                Arguments.of(new PlayApprovedContent(null, "closing", null),
                        List.of("playbackId:missing", "contentCode:invalid", "contentVersionId:missing")),
                Arguments.of(new PlaybackMark(null, null, new PlaybackMark.PlayedRange(-1L, null), null, null, null),
                        List.of("playbackId:missing", "status:missing", "observedAt:missing", "playedRange:invalid")),
                Arguments.of(new StopOutput(StopOutput.Reason.UNRECOGNIZED), List.of("reason:invalid")),
                Arguments.of(new CloseTransport(null), List.of("reason:invalid")),
                Arguments.of(new VoiceSessionClosed(null, null), List.of("outputStoppedAt:missing", "micClosedAt:missing")),
                Arguments.of(new DeviceState(null, null, null, null), List.of("payload:empty")),
                Arguments.of(new WsError(WsError.Code.UNRECOGNIZED, 0L), List.of("code:invalid", "refSeq:range")));
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void payloadRulesReportFieldNamesOnly(WsPayload payload, List<String> expected) {
        assertThat(payload.problems()).containsExactlyElementsOf(expected);
    }

    @Test
    void playbackMarkFinalStatusesAreCompletedStoppedAndFailed() {
        assertThat(Arrays.stream(PlaybackMark.Status.values())
                        .filter(status -> new PlaybackMark(PLAYBACK_ID, status, null, SENT_AT, null, null).isFinal()))
                .containsExactly(PlaybackMark.Status.COMPLETED, PlaybackMark.Status.STOPPED, PlaybackMark.Status.FAILED);
    }

    @Test
    void closeCodeTableMatchesTheDeviceActions() {
        assertThat(CloseCode.of(4400)).contains(CloseCode.PROTOCOL_ERROR);
        assertThat(CloseCode.PROTOCOL_ERROR.deviceAction()).isEqualTo(CloseCode.DeviceAction.RECONNECT);
        assertThat(CloseCode.of(4401).orElseThrow().deviceAction()).isEqualTo(CloseCode.DeviceAction.RECHECK_ME);
        assertThat(CloseCode.of(4001).orElseThrow().deviceAction()).isEqualTo(CloseCode.DeviceAction.OFF);
        assertThat(CloseCode.of(1000).orElseThrow().deviceAction()).isEqualTo(CloseCode.DeviceAction.OFF);
        assertThat(CloseCode.ABNORMAL_CLOSURE.isSendable()).isFalse();
        assertThat(CloseCode.HELLO_TIMEOUT.code()).isEqualTo(4408);
        assertThat(CloseCode.of(4999)).isEmpty();
        assertThat(WsMessageType.fromWire("hello")).isEqualTo(WsMessageType.UNRECOGNIZED);
        assertThat(WsMessageType.fromWire("UNRECOGNIZED")).isEqualTo(WsMessageType.UNRECOGNIZED);
    }
}
