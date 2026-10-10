package kr.mujoong.realtime.gate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 출력 구간 버퍼 (U1 3.5 Step 25 · BR7.3 · PD-3 · PD-4 · SD-17).
 *
 * <p>공급자 응답 하나(responseId)의 출력 음성 · 전사 조각을 게이트 전까지 모은다. 세션 실행기만 쓴다(락 없음). 상한은 20초 분량
 * {@value #MAX_BYTES}바이트(48,000B/s × 20초)이고 넘기려는 음성 조각을 만나면 그 구간을 SEGMENT_OVERSIZE로 떼어 내고 같은 응답의
 * 남은 조각은 응답 끝(output.done 또는 다른 응답)까지 버린다. 구간 확정(봉인)은 실행기가 조용 타이머 만기 · 아이 입력 재개 때 출력 세대
 * 번호와 함께 한다. 음성은 각 공급자 조각을 그대로 한 프레임으로 보낸다.
 */
public final class SegmentBuffer {

    /** 구간 상한(20초 × 48,000B/s). */
    public static final int MAX_BYTES = 960_000;

    /** 조각을 넣은 결과. */
    public enum Added {
        /** 넣었다. */
        ADDED,
        /** 상한을 넘겨 이 구간을 떼어 냈다({@link #takeOversized}). */
        OVERSIZE,
        /** 상한을 넘긴 응답의 남은 조각이라 버렸다. */
        DROPPED
    }

    /** 봉인한 구간. */
    public record Sealed(
            String responseId,
            String transcript,
            List<byte[]> chunks,
            int totalBytes,
            long generation,
            Instant firstOutAt,
            long firstOutNanos,
            long lastOutNanos,
            boolean oversize) {

        public Sealed {
            chunks = List.copyOf(chunks);
        }

        /** 음성 길이(밀리초, PCM16 24kHz 모노). */
        public long totalMs() {
            return totalBytes / 48L;
        }
    }

    private final int maxBytes;
    private String responseId;
    private final StringBuilder transcript = new StringBuilder();
    private final List<byte[]> chunks = new ArrayList<>();
    private int bytes;
    private Instant firstOutAt;
    private long firstOutNanos;
    private long lastOutNanos;
    private String droppedResponseId;
    private Sealed oversized;

    public SegmentBuffer() {
        this(MAX_BYTES);
    }

    public SegmentBuffer(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes는 0보다 커야 한다");
        }
        this.maxBytes = maxBytes;
    }

    /** 열린 구간이 있는가. */
    public boolean isOpen() {
        return responseId != null;
    }

    /** 열린 구간의 응답 id. */
    public String responseId() {
        return responseId;
    }

    /** 이 응답의 조각을 받아야 하는가(상한을 넘긴 응답이면 거짓). */
    public boolean accepts(String id) {
        return !Objects.equals(id, droppedResponseId);
    }

    /** 출력 음성 조각. */
    public Added addAudio(String id, byte[] audio, Instant at, long nanos) {
        if (!accepts(id)) {
            return Added.DROPPED;
        }
        open(id, at, nanos);
        if ((long) bytes + audio.length > maxBytes) {
            oversized = snapshot(-1L, true);
            droppedResponseId = id;
            clear();
            return Added.OVERSIZE;
        }
        chunks.add(audio.clone());
        bytes += audio.length;
        lastOutNanos = nanos;
        return Added.ADDED;
    }

    /** 출력 전사 조각. */
    public Added addTranscript(String id, String delta, Instant at, long nanos) {
        if (!accepts(id)) {
            return Added.DROPPED;
        }
        open(id, at, nanos);
        transcript.append(delta);
        lastOutNanos = nanos;
        return Added.ADDED;
    }

    /** 상한을 넘겨 떼어 낸 구간(한 번만 돌려준다). */
    public Sealed takeOversized() {
        Sealed taken = oversized;
        oversized = null;
        return taken;
    }

    /** 응답 끝(output.done): 버리던 응답 표시를 지운다. 열린 구간은 그대로 둔다(확정은 조용 · 입력 재개로만, BR7.3). */
    public void responseDone(String id) {
        if (Objects.equals(id, droppedResponseId)) {
            droppedResponseId = null;
        }
    }

    /** 열린 구간을 출력 세대와 함께 봉인한다. 없으면 null. */
    public Sealed seal(long generation) {
        if (!isOpen()) {
            return null;
        }
        Sealed sealed = snapshot(generation, false);
        clear();
        return sealed;
    }

    /** 모두 버린다(종료 · 취소). */
    public void reset() {
        clear();
        droppedResponseId = null;
        oversized = null;
    }

    private void open(String id, Instant at, long nanos) {
        if (responseId == null) {
            responseId = id;
            firstOutAt = at;
            firstOutNanos = nanos;
            lastOutNanos = nanos;
        }
    }

    private Sealed snapshot(long generation, boolean oversize) {
        return new Sealed(responseId, transcript.toString(), chunks, bytes, generation, firstOutAt, firstOutNanos,
                lastOutNanos, oversize);
    }

    private void clear() {
        responseId = null;
        transcript.setLength(0);
        chunks.clear();
        bytes = 0;
        firstOutAt = null;
        firstOutNanos = 0L;
        lastOutNanos = 0L;
    }
}
