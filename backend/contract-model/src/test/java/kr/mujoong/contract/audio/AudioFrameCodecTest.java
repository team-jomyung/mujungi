package kr.mujoong.contract.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import kr.mujoong.contract.audio.AudioFrameCodec.AudioFrame;
import kr.mujoong.contract.audio.AudioFrameCodec.InvalidFrameException;
import kr.mujoong.contract.audio.AudioFrameCodec.Kind;
import kr.mujoong.contract.audio.AudioFrameCodec.Reason;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 기기 WSS 바이너리 음성 프레임(J27 · BR7.1 · 편차 (s)): 1바이트 종류 + uint32 빅엔디언 순번 + PCM16LE. */
@Tag("u1")
class AudioFrameCodecTest {

    @Test
    void inputFrameIs965BytesWithKindAndBigEndianSequenceHeader() {
        byte[] pcm = pcm(AudioFrameCodec.INPUT_PAYLOAD_BYTES);

        byte[] frame = AudioFrameCodec.encodeInput(0x01020304L, pcm);

        assertThat(frame).hasSize(965);
        assertThat(frame[0]).isEqualTo((byte) 0x01);
        assertThat(new byte[] {frame[1], frame[2], frame[3], frame[4]}).containsExactly(0x01, 0x02, 0x03, 0x04);
        AudioFrame decoded = AudioFrameCodec.decodeFromDevice(frame);
        assertThat(decoded.kind()).isEqualTo(Kind.INPUT);
        assertThat(decoded.sequence()).isEqualTo(0x01020304L);
        assertThat(decoded.pcm()).isEqualTo(pcm);
        assertThat(decoded.pcmLength()).isEqualTo(960);
    }

    @ParameterizedTest
    @ValueSource(ints = {964, 966, 5, 1925})
    void inputFrameOfAnyOtherLengthIsRejected(int length) {
        byte[] frame = new byte[length];
        frame[0] = 0x01;

        assertThatThrownBy(() -> AudioFrameCodec.decodeFromDevice(frame))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INPUT_LENGTH));
    }

    @Test
    void sequenceUsesTheFullUnsignedThirtyTwoBitRange() {
        byte[] frame = AudioFrameCodec.encodeInput(AudioFrameCodec.MAX_SEQUENCE, pcm(960));

        assertThat(AudioFrameCodec.decode(frame).sequence()).isEqualTo(4_294_967_295L);
        assertThat(new byte[] {frame[1], frame[2], frame[3], frame[4]}).containsOnly((byte) 0xFF);
        assertThat(AudioFrameCodec.decode(AudioFrameCodec.encodeInput(0, pcm(960))).sequence()).isZero();
        assertThatThrownBy(() -> AudioFrameCodec.encodeInput(AudioFrameCodec.MAX_SEQUENCE + 1, pcm(960)))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.SEQUENCE_RANGE));
        assertThatThrownBy(() -> AudioFrameCodec.encodeOutput(-1, pcm(2)))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.SEQUENCE_RANGE));
    }

    @Test
    void outputFrameBodyIsAnyEvenLengthAndDecodesOnTheDeviceSide() {
        byte[] pcm = pcm(4800);

        byte[] frame = AudioFrameCodec.encodeOutput(7, pcm);

        assertThat(frame[0]).isEqualTo((byte) 0x02);
        AudioFrame decoded = AudioFrameCodec.decodeFromServer(frame);
        assertThat(decoded.kind()).isEqualTo(Kind.OUTPUT);
        assertThat(decoded.sequence()).isEqualTo(7);
        assertThat(decoded.pcm()).isEqualTo(pcm);
        assertThat(AudioFrameCodec.decodeFromServer(AudioFrameCodec.encodeOutput(8, pcm(2))).pcmLength()).isEqualTo(2);
    }

    @Test
    void outputFrameWithOddOrEmptyBodyIsRejected() {
        assertThatThrownBy(() -> AudioFrameCodec.encodeOutput(1, pcm(3)))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.OUTPUT_LENGTH));
        assertThatThrownBy(() -> AudioFrameCodec.encodeOutput(1, new byte[0]))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.OUTPUT_LENGTH));
        byte[] odd = new byte[AudioFrameCodec.HEADER_BYTES + 3];
        odd[0] = 0x02;
        assertThatThrownBy(() -> AudioFrameCodec.decode(odd))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.OUTPUT_LENGTH));
        byte[] headerOnly = {0x02, 0, 0, 0, 1};
        assertThatThrownBy(() -> AudioFrameCodec.decode(headerOnly))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.OUTPUT_LENGTH));
    }

    @Test
    void serverRejectsOutputKindFromDeviceAndDeviceRejectsInputKindFromServer() {
        byte[] output = AudioFrameCodec.encodeOutput(1, pcm(960));
        byte[] input = AudioFrameCodec.encodeInput(1, pcm(960));

        assertThatThrownBy(() -> AudioFrameCodec.decodeFromDevice(output))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNEXPECTED_KIND));
        assertThatThrownBy(() -> AudioFrameCodec.decodeFromServer(input))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNEXPECTED_KIND));
    }

    @Test
    void unknownKindShortOrNullFramesAreRejectedWithoutEchoingBytes() {
        byte[] unknown = new byte[965];
        unknown[0] = 0x03;

        assertThatThrownBy(() -> AudioFrameCodec.decode(unknown))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNKNOWN_KIND))
                .hasMessage("음성 프레임 형식 위반: UNKNOWN_KIND");
        assertThatThrownBy(() -> AudioFrameCodec.decode(new byte[] {0x01, 0, 0}))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_SHORT));
        assertThatThrownBy(() -> AudioFrameCodec.decode(null))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_SHORT));
        assertThatThrownBy(() -> AudioFrameCodec.decodeFromDevice((ByteBuffer) null))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_SHORT));
        assertThatThrownBy(() -> AudioFrameCodec.encodeInput(1, pcm(958)))
                .isInstanceOfSatisfying(InvalidFrameException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INPUT_LENGTH));
        assertThatThrownBy(() -> AudioFrameCodec.encodeInput(1, null)).isInstanceOf(InvalidFrameException.class);
    }

    @Test
    void byteBufferDecodingReadsRemainingBytesWithoutMovingThePosition() {
        byte[] frame = AudioFrameCodec.encodeInput(42, pcm(960));
        ByteBuffer buffer = ByteBuffer.allocate(frame.length + 3);
        buffer.put(new byte[] {9, 9, 9}).put(frame).flip().position(3);

        AudioFrame decoded = AudioFrameCodec.decodeFromDevice(buffer);

        assertThat(decoded.sequence()).isEqualTo(42);
        assertThat(buffer.position()).isEqualTo(3);
    }

    @Test
    void frameRecordCopiesPcmAndHidesBodyInToString() {
        byte[] pcm = pcm(4);
        AudioFrame frame = new AudioFrame(Kind.OUTPUT, 1, pcm);
        pcm[0] = 99;
        frame.pcm()[1] = 99;

        assertThat(frame.pcm()).containsExactly(0, 1, 2, 3);
        assertThat(frame).isEqualTo(new AudioFrame(Kind.OUTPUT, 1, new byte[] {0, 1, 2, 3}));
        assertThat(frame).hasSameHashCodeAs(new AudioFrame(Kind.OUTPUT, 1, new byte[] {0, 1, 2, 3}));
        assertThat(frame).isNotEqualTo(new AudioFrame(Kind.OUTPUT, 2, new byte[] {0, 1, 2, 3}));
        assertThat(frame.toString()).isEqualTo("AudioFrame[kind=OUTPUT, sequence=1, pcmBytes=4]");
        assertThat(Kind.INPUT.marker()).isEqualTo((byte) 0x01);
    }

    private static byte[] pcm(int length) {
        byte[] pcm = new byte[length];
        for (int i = 0; i < length; i++) {
            pcm[i] = (byte) i;
        }
        return pcm;
    }
}
