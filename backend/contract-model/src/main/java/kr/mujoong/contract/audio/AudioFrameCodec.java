package kr.mujoong.contract.audio;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 기기 WSS 바이너리 음성 프레임(계약 4.2 · J27 · BR7.1 · 편차 (s)).
 *
 * <pre>
 * [0]     종류: 0x01 입력(기기 → 서버) · 0x02 출력(서버 → 기기)
 * [1..4]  프레임 순번 uint32 빅엔디언
 * [5..]   PCM16LE · 24kHz · 모노
 * </pre>
 *
 * <p>입력 프레임은 20ms = 480표본 = 960바이트라 전체 965바이트여야 한다. 출력 프레임 본문은 길이 자유이되 2바이트(표본) 배수다.
 * 서버가 받은 0x02와 965바이트가 아닌 0x01은 4400 사유다(J123). 이 클래스는 형식만 다루고 본문(원음)을 저장 · 기록하지 않는다(BR7.1).
 */
public final class AudioFrameCodec {

    /** 머리 길이(종류 1 + 순번 4). */
    public static final int HEADER_BYTES = 5;
    /** 입력 프레임 본문 길이(20ms × 24,000Hz × 16비트 모노). */
    public static final int INPUT_PAYLOAD_BYTES = 960;
    /** 입력 프레임 전체 길이. */
    public static final int INPUT_FRAME_BYTES = HEADER_BYTES + INPUT_PAYLOAD_BYTES;
    /** PCM16 표본 크기. */
    public static final int SAMPLE_BYTES = 2;
    /** 순번 상한(uint32). */
    public static final long MAX_SEQUENCE = 0xFFFF_FFFFL;

    private AudioFrameCodec() {
    }

    /** 프레임 종류. */
    public enum Kind {
        INPUT((byte) 0x01),
        OUTPUT((byte) 0x02);

        private final byte marker;

        Kind(byte marker) {
            this.marker = marker;
        }

        /** 첫 바이트 값. */
        public byte marker() {
            return marker;
        }

        static Kind of(byte marker) {
            for (Kind kind : values()) {
                if (kind.marker == marker) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** 거절 사유(값 · 본문은 담지 않는다). */
    public enum Reason {
        /** 머리보다 짧거나 비었다. */
        TOO_SHORT,
        /** 첫 바이트가 0x01 · 0x02가 아니다. */
        UNKNOWN_KIND,
        /** 이 방향에서 받을 수 없는 종류다(서버가 받은 0x02 등). */
        UNEXPECTED_KIND,
        /** 0x01인데 전체가 965바이트가 아니다. */
        INPUT_LENGTH,
        /** 0x02 본문이 비었거나 2바이트 배수가 아니다. */
        OUTPUT_LENGTH,
        /** 순번이 uint32 범위 밖이다(인코딩 때). */
        SEQUENCE_RANGE
    }

    /** 형식 위반. WSS에서는 4400 PROTOCOL_ERROR 사유다. */
    public static final class InvalidFrameException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final Reason reason;

        InvalidFrameException(Reason reason) {
            super("음성 프레임 형식 위반: " + reason);
            this.reason = reason;
        }

        /** 거절 사유. */
        public Reason reason() {
            return reason;
        }
    }

    /**
     * 해석한 프레임. {@code pcm}은 복사본이다(원음은 메모리에서만 쓴다).
     *
     * @param kind 종류
     * @param sequence 순번(0 ~ 2^32 − 1)
     * @param pcm PCM16LE 본문
     */
    public record AudioFrame(Kind kind, long sequence, byte[] pcm) {

        public AudioFrame {
            pcm = pcm.clone();
        }

        @Override
        public byte[] pcm() {
            return pcm.clone();
        }

        /** 본문 길이(바이트). */
        public int pcmLength() {
            return pcm.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AudioFrame that
                    && kind == that.kind
                    && sequence == that.sequence
                    && Arrays.equals(pcm, that.pcm);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * kind.hashCode() + Long.hashCode(sequence)) + Arrays.hashCode(pcm);
        }

        @Override
        public String toString() {
            return "AudioFrame[kind=" + kind + ", sequence=" + sequence + ", pcmBytes=" + pcm.length + "]";
        }
    }

    /** 기기 입력 프레임(0x01, 본문 960바이트)을 만든다. */
    public static byte[] encodeInput(long sequence, byte[] pcm) {
        if (pcm == null || pcm.length != INPUT_PAYLOAD_BYTES) {
            throw new InvalidFrameException(Reason.INPUT_LENGTH);
        }
        return encode(Kind.INPUT, sequence, pcm);
    }

    /** 서버 출력 프레임(0x02, 본문은 2바이트 배수 · 비지 않음)을 만든다. */
    public static byte[] encodeOutput(long sequence, byte[] pcm) {
        if (pcm == null || pcm.length == 0 || pcm.length % SAMPLE_BYTES != 0) {
            throw new InvalidFrameException(Reason.OUTPUT_LENGTH);
        }
        return encode(Kind.OUTPUT, sequence, pcm);
    }

    /** 종류에 맞는 규칙으로 해석한다(방향 검사 없음). */
    public static AudioFrame decode(byte[] frame) {
        if (frame == null || frame.length < HEADER_BYTES) {
            throw new InvalidFrameException(Reason.TOO_SHORT);
        }
        Kind kind = Kind.of(frame[0]);
        if (kind == null) {
            throw new InvalidFrameException(Reason.UNKNOWN_KIND);
        }
        if (kind == Kind.INPUT && frame.length != INPUT_FRAME_BYTES) {
            throw new InvalidFrameException(Reason.INPUT_LENGTH);
        }
        int pcmLength = frame.length - HEADER_BYTES;
        if (kind == Kind.OUTPUT && (pcmLength == 0 || pcmLength % SAMPLE_BYTES != 0)) {
            throw new InvalidFrameException(Reason.OUTPUT_LENGTH);
        }
        long sequence = Integer.toUnsignedLong(ByteBuffer.wrap(frame, 1, Integer.BYTES).getInt());
        return new AudioFrame(kind, sequence, Arrays.copyOfRange(frame, HEADER_BYTES, frame.length));
    }

    /** 서버가 기기에게서 받은 프레임을 해석한다. 0x01 965바이트만 받는다(서버가 받은 0x02는 4400, 편차 (s)). */
    public static AudioFrame decodeFromDevice(byte[] frame) {
        AudioFrame decoded = decode(frame);
        if (decoded.kind() != Kind.INPUT) {
            throw new InvalidFrameException(Reason.UNEXPECTED_KIND);
        }
        return decoded;
    }

    /** {@link #decodeFromDevice(byte[])}의 ByteBuffer 판(남은 바이트를 읽고 버퍼 위치는 바꾸지 않는다). */
    public static AudioFrame decodeFromDevice(ByteBuffer frame) {
        if (frame == null) {
            throw new InvalidFrameException(Reason.TOO_SHORT);
        }
        ByteBuffer view = frame.duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return decodeFromDevice(bytes);
    }

    /** 기기(시뮬레이터)가 서버에게서 받은 프레임을 해석한다. 0x02만 받는다. */
    public static AudioFrame decodeFromServer(byte[] frame) {
        AudioFrame decoded = decode(frame);
        if (decoded.kind() != Kind.OUTPUT) {
            throw new InvalidFrameException(Reason.UNEXPECTED_KIND);
        }
        return decoded;
    }

    private static byte[] encode(Kind kind, long sequence, byte[] pcm) {
        if (sequence < 0 || sequence > MAX_SEQUENCE) {
            throw new InvalidFrameException(Reason.SEQUENCE_RANGE);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + pcm.length);
        buffer.put(kind.marker());
        buffer.putInt((int) sequence);
        buffer.put(pcm);
        return buffer.array();
    }
}
