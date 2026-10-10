package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.Heartbeat;
import kr.mujoong.contract.ws.message.HeartbeatAck;
import kr.mujoong.contract.ws.message.Hello;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.WsError;
import kr.mujoong.realtime.gateway.DeviceConnection.Phase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 수신 프레임 판정 시험 (U1 3.5 Step 29 · 편차 (s) · J123 · NFR9.9 · SD-7).
 *
 * <p>텍스트 8KB · 0x01 965B 경계, 0x02 수신 거부, HELLO 형식 4400, READY 뒤 모르는 type은 ERROR이고 연결 유지, DEVICE_STATE ·
 * PLAYBACK_MARK(PROGRESS)는 ERROR 없이 무시, sentAt만 빠진 봉투는 받되 표시한다.
 */
@Tag("u1")
class WsFrameGuardTest {

    private static final JsonCodec JSON = JsonCodec.shared();
    private static final int TEXT_MAX = 8 * 1024;
    private static final Instant AT = Instant.parse("2026-10-15T09:00:00Z");

    private final WsFrameGuard guard = new WsFrameGuard(JSON, TEXT_MAX);

    @Test
    void aTextFrameOfExactlyEightKilobytesPassesAndOneByteMoreCloses4400() {
        String base = text(new Heartbeat(1L, 20L), 5);
        String exact = pad(base, TEXT_MAX);
        String over = pad(base, TEXT_MAX + 1);

        assertThat(guard.onText(Phase.READY, exact)).isInstanceOf(WsFrameGuard.Accept.class);
        assertThat(guard.onText(Phase.READY, over)).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
        assertThat(guard.onText(Phase.READY, null)).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
    }

    @Test
    void aBinaryInputFrameOf965BytesIsAcceptedAndAnyOtherLengthOrDirectionCloses() {
        byte[] pcm = new byte[AudioFrameCodec.INPUT_PAYLOAD_BYTES];
        byte[] frame = AudioFrameCodec.encodeInput(7, pcm);
        byte[] longer = new byte[frame.length + 1];
        System.arraycopy(frame, 0, longer, 0, frame.length);
        byte[] output = AudioFrameCodec.encodeOutput(1, new byte[4800]);

        assertThat(frame).hasSize(965);
        WsFrameGuard.Decision accepted = guard.onBinary(Phase.READY, frame);
        assertThat(accepted).isInstanceOf(WsFrameGuard.AcceptAudio.class);
        assertThat(((WsFrameGuard.AcceptAudio) accepted).frame().sequence()).isEqualTo(7);
        assertThat(guard.onBinary(Phase.READY, longer)).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
        assertThat(guard.onBinary(Phase.READY, output)).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
        assertThat(guard.onBinary(Phase.AWAIT_HELLO, frame)).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
        assertThat(guard.onBinary(Phase.CLOSED, frame)).isInstanceOf(WsFrameGuard.Ignore.class);
    }

    @Test
    void beforeReadyOnlyAWellFormedHelloIsAcceptedAndItWaitsForTheTokenCheck() {
        String hello = text(new Hello("1.0.0", List.of("pcm16le/24000/1"), null), 1);
        String badHello = text(new Hello("1.0.0", List.of("pcm16le/24000/1"), null), 1).replace("\"1.0.0\"", "\"\"");
        String heartbeat = text(new Heartbeat(1L, null), 1);

        assertThat(guard.onText(Phase.AWAIT_VERIFY, hello)).isInstanceOf(WsFrameGuard.Defer.class);
        assertThat(guard.onText(Phase.AWAIT_HELLO, hello)).isInstanceOf(WsFrameGuard.Accept.class);
        assertThat(guard.onText(Phase.AWAIT_HELLO, badHello)).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
        assertThat(guard.onText(Phase.AWAIT_HELLO, heartbeat)).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
        assertThat(guard.onText(Phase.AWAIT_HELLO, "not json")).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
    }

    @Test
    void afterReadyAnUnknownTypeIsAnErrorReplyAndTheConnectionStays() {
        String unknown = "{\"type\":\"FLY_TO_MOON\",\"seq\":9,\"sentAt\":\"2026-10-15T09:00:00Z\",\"payload\":{}}";

        WsFrameGuard.Decision decision = guard.onText(Phase.READY, unknown);

        assertThat(decision).isEqualTo(new WsFrameGuard.Error(WsError.Code.UNKNOWN_TYPE, 9L));
    }

    @Test
    void afterReadyDeviceStateHelloAndProgressMarksAreIgnoredWithoutError() {
        String state = text(new DeviceState(DeviceState.Mic.OK, DeviceState.Player.OK, DeviceState.Visibility.VISIBLE,
                DeviceState.WakeLock.HELD), 3);
        String progress = text(new PlaybackMark(UUID.randomUUID(), PlaybackMark.Status.PROGRESS, null, AT, null, null),
                4, null, UUID.randomUUID());
        String hello = text(new Hello("1.0.0", List.of("pcm16le/24000/1"), null), 5);

        assertThat(guard.onText(Phase.READY, state)).isInstanceOf(WsFrameGuard.Ignore.class);
        assertThat(guard.onText(Phase.READY, progress)).isInstanceOf(WsFrameGuard.Ignore.class);
        assertThat(guard.onText(Phase.READY, hello)).isInstanceOf(WsFrameGuard.Ignore.class);
    }

    @Test
    void aServerToDeviceTypeFromTheDeviceOrBrokenJsonCloses4400AndABadDevicePayloadIsAnError() {
        String serverType = text(new HeartbeatAck(1L, AT), 2);
        String badPayload = "{\"type\":\"HEARTBEAT\",\"seq\":6,\"sentAt\":\"2026-10-15T09:00:00Z\",\"payload\":{}}";

        assertThat(guard.onText(Phase.READY, serverType)).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
        assertThat(guard.onText(Phase.READY, "{")).isEqualTo(new WsFrameGuard.Close(CloseCode.PROTOCOL_ERROR));
        assertThat(guard.onText(Phase.READY, badPayload)).isEqualTo(new WsFrameGuard.Error(WsError.Code.BAD_PAYLOAD, 6L));
    }

    @Test
    void aHeartbeatWithoutSentAtIsAcceptedAndMarkedSoTheOffsetIsSkipped() {
        String missing = "{\"type\":\"HEARTBEAT\",\"seq\":8,\"payload\":{\"hbSeq\":3,\"lastRttMs\":40}}";
        String wrong = "{\"type\":\"HEARTBEAT\",\"seq\":8,\"sentAt\":\"어제\",\"payload\":{\"hbSeq\":3}}";

        WsFrameGuard.Decision decision = guard.onText(Phase.READY, missing);
        WsFrameGuard.Decision wrongDecision = guard.onText(Phase.READY, wrong);

        assertThat(decision).isInstanceOfSatisfying(WsFrameGuard.Accept.class, accept -> {
            assertThat(accept.sentAtMissing()).isTrue();
            assertThat(accept.envelope().sentAt()).isNull();
            assertThat(accept.envelope().messageType()).isEqualTo(WsMessageType.HEARTBEAT);
        });
        assertThat(wrongDecision).isInstanceOfSatisfying(WsFrameGuard.Accept.class,
                accept -> assertThat(accept.sentAtMissing()).isTrue());
        assertThat(guard.onText(Phase.READY, text(new Heartbeat(3L, 40L), 8)))
                .isInstanceOfSatisfying(WsFrameGuard.Accept.class, accept -> assertThat(accept.sentAtMissing()).isFalse());
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"CLOSED"})
    void framesOnAClosedConnectionAreIgnored(Phase phase) {
        assertThat(guard.onText(phase, text(new Heartbeat(1L, null), 1))).isInstanceOf(WsFrameGuard.Ignore.class);
    }

    private static String text(WsPayload payload, long seq) {
        return text(payload, seq, null, null);
    }

    private static String text(WsPayload payload, long seq, UUID opId, UUID sessionId) {
        return JSON.encodeWs(WsEnvelope.of(payload, seq, AT, opId, sessionId));
    }

    /** JSON 뒤에 공백을 붙여 정확히 {@code bytes} 바이트로 만든다. */
    private static String pad(String json, int bytes) {
        int length = json.getBytes(StandardCharsets.UTF_8).length;
        return json + " ".repeat(bytes - length);
    }
}
