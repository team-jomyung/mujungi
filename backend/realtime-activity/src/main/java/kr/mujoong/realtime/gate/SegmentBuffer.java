package kr.mujoong.realtime.gate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 출력 구간 버퍼 (U1 3.5 Step 25 · BR7.3 · PD-3 · PD-4 · SD-17 · 3.6 수정 리뷰 F1 · J285 ~ J288).
 *
 * <p>공급자 응답 하나(responseId)의 출력 음성 · 전사 조각을 게이트 전까지 모은다. 세션 실행기만 쓴다(락 없음). 상한은 20초 분량
 * {@value #MAX_BYTES}바이트(48,000B/s × 20초)이고 넘기려는 음성 조각을 만나면 그 구간을 SEGMENT_OVERSIZE로 떼어 내고 같은 응답의
 * 남은 조각은 응답 끝(output.done)까지 버린다. 구간 확정(봉인)은 실행기가 조용 타이머 만기 · 아이 입력 재개 · 다른 응답의 조각 도착 때
 * 출력 세대 번호와 함께 한다. 음성은 각 공급자 조각을 그대로 한 프레임으로 보낸다.
 *
 * <p>전사-음성 대응(SD-17 3단계): 한 구간에는 한 응답의 조각만 담고 다른 응답의 조각은 받지 않는다({@link Added#OTHER_RESPONSE}, J286).
 * 봉인한 구간은 그 응답의 첫 조각부터 응답 끝 신호(output.done)까지를 모두 담았고 끝 뒤에 붙은 조각이 없을 때만
 * {@link Sealed#complete()}다(J285). 이 세션에서 이미 조각을 받은 응답 id는 세션 동안 기억해, 앞부분이 다른 구간으로 떨어져 나간 응답의
 * 나머지 구간은 완결로 보지 않는다(재설정에도 지우지 않는다, J288). responseId가 없는 조각은 대응 근거가 없어 버린다.
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
        /** 상한을 넘긴 응답의 남은 조각이거나 응답 id가 없어 버렸다. */
        DROPPED,
        /** 열린 구간이 다른 응답의 것이라 받지 않았다(먼저 봉인해야 한다). */
        OTHER_RESPONSE
    }

    /**
     * 봉인한 구간.
     *
     * @param complete 그 응답의 첫 조각부터 응답 끝 신호까지를 모두 담았는가(전사-음성 1:1 대응의 근거, SD-17 3단계)
     */
    public record Sealed(
            String responseId,
            String transcript,
            List<byte[]> chunks,
            int totalBytes,
            long generation,
            Instant firstOutAt,
            long firstOutNanos,
            long lastOutNanos,
            boolean oversize,
            boolean complete) {

        public Sealed {
            chunks = List.copyOf(chunks);
        }

        /** 음성 길이(밀리초, PCM16 24kHz 모노). */
        public long totalMs() {
            return totalBytes / 48L;
        }
    }

    private final int maxBytes;
    private final Set<String> seenResponses = new HashSet<>();
    private String responseId;
    private final StringBuilder transcript = new StringBuilder();
    private final List<byte[]> chunks = new ArrayList<>();
    private int bytes;
    private Instant firstOutAt;
    private long firstOutNanos;
    private long lastOutNanos;
    private boolean fromStart;
    private boolean done;
    private boolean afterDone;
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

    /** 이 응답의 조각을 받아야 하는가(응답 id가 없거나 상한을 넘긴 응답이면 거짓). */
    public boolean accepts(String id) {
        return id != null && !id.isBlank() && !Objects.equals(id, droppedResponseId);
    }

    /** 열린 구간이 다른 응답의 것이라 이 응답의 조각을 넣기 전에 봉인해야 하는가(J286). */
    public boolean holdsOtherThan(String id) {
        return isOpen() && accepts(id) && !responseId.equals(id);
    }

    /** 출력 음성 조각. */
    public Added addAudio(String id, byte[] audio, Instant at, long nanos) {
        Added refused = refusal(id);
        if (refused != null) {
            return refused;
        }
        join(id, at, nanos);
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
        Added refused = refusal(id);
        if (refused != null) {
            return refused;
        }
        join(id, at, nanos);
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

    /**
     * 응답 끝(output.done): 그 응답의 전사 완료 신호다. 열린 구간이 그 응답이면 완결 근거로 표시하고, 버리던 응답이면 표시를 지운다. 열린
     * 구간은 그대로 둔다(확정은 조용 · 입력 재개 · 다른 응답 도착으로만, BR7.3).
     */
    public void responseDone(String id) {
        if (isOpen() && responseId.equals(id)) {
            done = true;
        }
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

    /** 모두 버린다(종료 · 취소). 이미 본 응답 id는 지우지 않는다(J288). */
    public void reset() {
        clear();
        droppedResponseId = null;
        oversized = null;
    }

    /** 받지 않는 조각이면 그 결과, 받으면 null. */
    private Added refusal(String id) {
        if (!accepts(id)) {
            return Added.DROPPED;
        }
        if (isOpen() && !responseId.equals(id)) {
            // 섞지 않는다. 이 조각은 그 응답의 첫 조각일 수 있으므로 본 것으로 적어 뒤 구간이 완결로 보이지 않게 한다(fail-closed)
            seenResponses.add(id);
            return Added.OTHER_RESPONSE;
        }
        return null;
    }

    /** 조각을 열린 구간에 붙인다(없으면 연다). 처음 보는 응답으로 연 구간만 그 응답의 첫 조각부터 담는다. */
    private void join(String id, Instant at, long nanos) {
        if (responseId == null) {
            responseId = id;
            firstOutAt = at;
            firstOutNanos = nanos;
            lastOutNanos = nanos;
            fromStart = seenResponses.add(id);
        } else if (done) {
            // 응답 끝 뒤에 온 조각: 끝 신호가 덮지 못한 조각을 담게 되므로 이 구간은 완결이 아니다
            afterDone = true;
        }
    }

    private Sealed snapshot(long generation, boolean oversize) {
        return new Sealed(responseId, transcript.toString(), chunks, bytes, generation, firstOutAt, firstOutNanos,
                lastOutNanos, oversize, fromStart && done && !afterDone);
    }

    private void clear() {
        responseId = null;
        transcript.setLength(0);
        chunks.clear();
        bytes = 0;
        firstOutAt = null;
        firstOutNanos = 0L;
        lastOutNanos = 0L;
        fromStart = false;
        done = false;
        afterDone = false;
    }
}
