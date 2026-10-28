package kr.mujoong.realtime.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import kr.mujoong.contract.ws.message.OutputBegin;

/**
 * 게이트를 통과한 출력 묶음 하나(U1 3.5 Step 24 · BR7.4 · BR7.6 · SD-17 · PD-3 · PD-6).
 *
 * <p>연결 송신기가 OUTPUT_BEGIN → 0x02 프레임들 → OUTPUT_END로 보낸다. 프레임을 보내기 전마다 {@link #stillCurrent()}(출력 세대가 묶음을
 * 만들 때와 같은가)를 확인해 STOP_OUTPUT · 종료 · 연결 교체 뒤에는 남은 프레임을 보내지 않는다(SD-17). 음성은 봉인한 구간의 바이트를 순서대로
 * 이어 {@value #FRAME_PAYLOAD_BYTES}바이트(100ms) 프레임으로 나눈다 — 바이트는 바꾸지 않는다(J131 무변환). 첫 프레임을 소켓에 쓴 직후
 * {@code onFirstFrameSent}로 tFirstFrameSent를 알린다(OD-8). 프레임을 소켓에 쓸 때마다 그 본문을 {@code onFrameSent}에 넘긴다 — 원음 기록은
 * 게이트 통과가 아니라 실제로 기기로 보낸 프레임만 남긴다(U3-R9, 리뷰 반영 6).
 *
 * @param stillCurrent 출력 세대 확인(송신기 스레드에서 불린다)
 * @param onFirstFrameSent 첫 프레임 직후 시각 수신자(송신기 스레드)
 * @param onFrameSent 프레임 하나를 소켓에 쓴 직후 그 본문 수신자(송신기 스레드, 기다리지 않아야 한다)
 * @param subtitle 게이트를 통과한 이 묶음의 글(OUTPUT_BEGIN.text, 기기 자막). 없으면 null. 로그 · 문자열 표현에 넣지 않는다(R-F14)
 */
public record OutputBundle(
        UUID sessionId,
        UUID playbackId,
        OutputBegin.Source source,
        List<byte[]> frames,
        long totalMs,
        DeviceLink.AudioReservation reservation,
        BooleanSupplier stillCurrent,
        Consumer<Instant> onFirstFrameSent,
        Consumer<byte[]> onFrameSent,
        String subtitle) {

    /** 0x02 프레임 본문 크기(100ms × PCM16 24kHz 모노). 마지막 프레임은 짧을 수 있다. */
    public static final int FRAME_PAYLOAD_BYTES = 4_800;

    public OutputBundle {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(playbackId, "playbackId");
        Objects.requireNonNull(source, "source");
        frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        Objects.requireNonNull(onFirstFrameSent, "onFirstFrameSent");
        Objects.requireNonNull(onFrameSent, "onFrameSent");
        subtitle = OutputBegin.subtitle(subtitle);
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("출력 묶음에 프레임이 없다");
        }
    }

    /** 자막 없는 묶음. */
    public OutputBundle(UUID sessionId, UUID playbackId, OutputBegin.Source source, List<byte[]> frames, long totalMs,
            DeviceLink.AudioReservation reservation, BooleanSupplier stillCurrent, Consumer<Instant> onFirstFrameSent,
            Consumer<byte[]> onFrameSent) {
        this(sessionId, playbackId, source, frames, totalMs, reservation, stillCurrent, onFirstFrameSent, onFrameSent, null);
    }

    /** 보낸 프레임을 따로 받지 않는 묶음(원음 기록이 없는 세션). */
    public OutputBundle(UUID sessionId, UUID playbackId, OutputBegin.Source source, List<byte[]> frames, long totalMs,
            DeviceLink.AudioReservation reservation, BooleanSupplier stillCurrent, Consumer<Instant> onFirstFrameSent) {
        this(sessionId, playbackId, source, frames, totalMs, reservation, stillCurrent, onFirstFrameSent, frame -> { }, null);
    }

    /**
     * 구간 조각들을 순서대로 이어 프레임으로 나눈다. 전체 길이는 2바이트(표본) 배수여야 한다(게이트가 먼저 본다).
     */
    public static List<byte[]> frame(List<byte[]> chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        if (total == 0 || total % 2 != 0) {
            throw new IllegalArgumentException("출력 음성 길이가 PCM16 표본 배수가 아니다");
        }
        byte[] all = new byte[total];
        int position = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, all, position, chunk.length);
            position += chunk.length;
        }
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < total; offset += FRAME_PAYLOAD_BYTES) {
            int length = Math.min(FRAME_PAYLOAD_BYTES, total - offset);
            byte[] frame = new byte[length];
            System.arraycopy(all, offset, frame, 0, length);
            frames.add(frame);
        }
        return frames;
    }

    /** 음성 바이트 합. */
    public int totalBytes() {
        int total = 0;
        for (byte[] frame : frames) {
            total += frame.length;
        }
        return total;
    }

    /** 음성 바이트는 문자열 표현에 넣지 않는다. */
    @Override
    public String toString() {
        return "OutputBundle[sessionId=" + sessionId + ", playbackId=" + playbackId + ", frames=" + frames.size() + "]";
    }
}
