package kr.mujoong.realtime.gate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 출력 구간 버퍼 (U1 3.5 Step 25 · BR7.3 · PD-3 · PD-4 · SD-17 · 3.6 수정 리뷰 F1 · J285 ~ J288, U11 BR11.6 · 편차 (U11-a)로 구간 열쇠를 바꿨다).
 *
 * <p>출력 세대({@link OutputGeneration}) 하나에 속한 출력 음성 · 전사 조각을 게이트 전까지 모은다. 공급자 실호출에는 응답 id도 응답 끝 사건도
 * 없어서, 구간은 응답이 아니라 <b>세대</b>를 열쇠로 하고 응답 끝은 조각이 한동안 끊기는 것(끊김)으로 본다. 세션 실행기만 쓴다(락 없음). 상한은
 * 20초 분량 {@value #MAX_BYTES}바이트(48,000B/s x 20초)이고 넘기려는 음성 조각을 만나면 그 구간을 SEGMENT_OVERSIZE로 떼어 내고 같은
 * 세대의 남은 조각은 다음 끊김까지 버린다.
 *
 * <p>전사-음성 대응(SD-17 3단계): 구간이 <b>첫 조각부터 끊김까지</b> 모두 담겼고({@code fromStart}) 끊겨서 봉인됐으면
 * ({@link #sealQuiet}) 완전({@link Sealed#complete()})이다(J285). 세대가 바뀌어 봉인한 구간({@link #sealByGeneration})이나 상한을 넘겨 떼어
 * 낸 구간, 첫 조각이 앞 구간 · 버린 조각으로 떨어져 나간 구간은 불완전이라 게이트 3단계가 GATE_EMPTY로 폐기한다(fail-closed). 구간을 열 때
 * 호출자가 {@code fromStart}를 알려 준다({@link OutputGeneration.ChunkResult#fromStart()}).
 */
public final class SegmentBuffer {

    /** 구간 상한(20초 x 48,000B/s). */
    public static final int MAX_BYTES = 960_000;

    /** 조각을 넣은 결과. */
    public enum Added {
        /** 넣었다. */
        ADDED,
        /** 상한을 넘겨 이 구간을 떼어 냈다({@link #takeOversized}). */
        OVERSIZE,
        /** 상한을 넘긴 세대의 남은 조각이라 버렸다(다음 끊김까지). */
        DROPPED,
        /** 열린 구간이 다른 세대의 것이라 받지 않았다(먼저 봉인해야 한다). */
        OTHER_GENERATION
    }

    /**
     * 봉인한 구간.
     *
     * @param outputGen 구간이 속한 출력 세대 번호
     * @param generation 봉인 때의 기기 출력 세대(송신 직전 재확인용 — 출력 세대와 다른 번호다)
     * @param lastOutAt 마지막 조각을 받은 시각(지연 기록의 '공급자 출력 끝', 봉인 시각이 아니다, BR11.6)
     * @param complete 첫 조각부터 끊김까지를 모두 담았고 끊겨서 봉인했는가(전사-음성 1:1 대응의 근거, SD-17 3단계)
     */
    public record Sealed(
            long outputGen,
            String transcript,
            List<byte[]> chunks,
            int totalBytes,
            long generation,
            Instant firstOutAt,
            long firstOutNanos,
            Instant lastOutAt,
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
    private boolean open;
    private long outputGen;
    private final StringBuilder transcript = new StringBuilder();
    private final List<byte[]> chunks = new ArrayList<>();
    private int bytes;
    private int pendingSilenceBytes;
    private Instant firstOutAt;
    private long firstOutNanos;
    private Instant lastOutAt;
    private long lastOutNanos;
    private boolean fromStart;
    private boolean dropping;
    private long droppedOutputGen;
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
        return open;
    }

    /** 열린 구간의 출력 세대 번호(없으면 -1). */
    public long outputGen() {
        return open ? outputGen : -1L;
    }

    /** 마지막 조각을 받은 시각(열린 구간이 없으면 null). */
    public Instant lastOutAt() {
        return open ? lastOutAt : null;
    }

    /** 열린 구간이 다른 세대의 것이라 이 세대의 조각을 넣기 전에 봉인해야 하는가. */
    public boolean holdsOtherThan(long gen) {
        return open && outputGen != gen;
    }

    /**
     * 출력 음성 조각.
     *
     * @param fromStart 이 조각이 구간을 여는 조각이면, 앞 끊김 · 세대 올림 뒤 첫 조각인가
     */
    public Added addAudio(long gen, boolean fromStart, byte[] audio, Instant at, long nanos) {
        Added refused = refusal(gen);
        if (refused != null) {
            return refused;
        }
        join(gen, fromStart, at, nanos);
        if ((long) bytes + pendingSilenceBytes + audio.length > maxBytes) {
            oversized = snapshot(-1L, true, false);
            droppedOutputGen = gen;
            dropping = true;
            clear();
            return Added.OVERSIZE;
        }
        if (pendingSilenceBytes > 0) {
            chunks.add(new byte[pendingSilenceBytes]);
            bytes += pendingSilenceBytes;
            pendingSilenceBytes = 0;
        }
        chunks.add(audio.clone());
        bytes += audio.length;
        lastOutAt = at;
        lastOutNanos = nanos;
        return Added.ADDED;
    }

    /** Preserve internal pauses only if speech resumes; trailing transport silence is never a new segment. */
    public void addSilence(int length) {
        if (length <= 0 || length % 2 != 0) throw new IllegalArgumentException("Invalid PCM silence length");
        if (open) pendingSilenceBytes = (int) Math.min((long) maxBytes + 1, (long) pendingSilenceBytes + length);
    }

    /** 출력 전사 조각. */
    public Added addTranscript(long gen, boolean fromStart, String delta, Instant at, long nanos) {
        Added refused = refusal(gen);
        if (refused != null) {
            return refused;
        }
        join(gen, fromStart, at, nanos);
        transcript.append(delta);
        lastOutAt = at;
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
     * 조각이 끊겨 봉인한다(끊김 시계 만기). 첫 조각부터 담겼으면 '완전'이다. 상한을 넘겨 버리던 세대의 표시도 여기서 지운다(다음 응답은 새로
     * 받는다). 열린 구간이 없으면 null.
     *
     * @param deviceGeneration 봉인 때의 기기 출력 세대
     */
    public Sealed sealQuiet(long deviceGeneration) {
        dropping = false;
        if (!open) {
            return null;
        }
        Sealed sealed = snapshot(deviceGeneration, false, fromStart);
        clear();
        return sealed;
    }

    /**
     * 끊김 전에 닫는다(아이 입력 재개). 조각이 {@code quietEnough}만큼 이미 끊겨 있었으면 끊김으로 봉인한 것과 같아 '완전'이고, 아니면
     * 응답이 아직 이어지던 중이라 '불완전'이다.
     */
    public Sealed sealEarly(long deviceGeneration, boolean quietEnough) {
        if (!open) {
            return null;
        }
        Sealed sealed = snapshot(deviceGeneration, false, fromStart && quietEnough);
        clear();
        return sealed;
    }

    /** 세대가 바뀌어 열린 구간을 '불완전'으로 봉인해 버린다. 없으면 null. */
    public Sealed sealByGeneration(long deviceGeneration) {
        dropping = false;
        if (!open) {
            return null;
        }
        Sealed sealed = snapshot(deviceGeneration, false, false);
        clear();
        return sealed;
    }

    /** 모두 버린다(종료 · 취소). */
    public void reset() {
        clear();
        dropping = false;
        oversized = null;
    }

    /** 받지 않는 조각이면 그 결과, 받으면 null. */
    private Added refusal(long gen) {
        if (dropping && gen == droppedOutputGen) {
            return Added.DROPPED;
        }
        if (open && outputGen != gen) {
            return Added.OTHER_GENERATION;
        }
        return null;
    }

    /** 조각을 열린 구간에 붙인다(없으면 연다). */
    private void join(long gen, boolean startsFromStart, Instant at, long nanos) {
        if (!open) {
            open = true;
            outputGen = gen;
            firstOutAt = at;
            firstOutNanos = nanos;
            lastOutAt = at;
            lastOutNanos = nanos;
            fromStart = startsFromStart;
        }
    }

    private Sealed snapshot(long deviceGeneration, boolean oversize, boolean complete) {
        return new Sealed(outputGen, transcript.toString(), chunks, bytes, deviceGeneration, firstOutAt, firstOutNanos, lastOutAt,
                lastOutNanos, oversize, complete);
    }

    private void clear() {
        open = false;
        transcript.setLength(0);
        chunks.clear();
        bytes = 0;
        pendingSilenceBytes = 0;
        firstOutAt = null;
        firstOutNanos = 0L;
        lastOutAt = null;
        lastOutNanos = 0L;
        fromStart = false;
    }
}
