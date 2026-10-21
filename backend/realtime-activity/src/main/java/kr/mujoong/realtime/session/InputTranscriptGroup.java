package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.Objects;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;

/**
 * 입력 전사 조각을 하나의 발화로 묶는 칸(설계 개정 A7). 공급자는 입력 전사 완료 사건을 주지 않으므로 실행기가 조각을 여기에 모았다가 조용 시계가 울리면
 * 확정 전사 하나로 꺼낸다. 진단 로그(contracts/ai-io.md 10.15)에 쓰려고 묶은 조각 수, 조각 사이 최대 간격, 묶음을 연 서버 시각과 그때 기기가
 * 재생 중이었는지도 함께 쥔다. 실행기 스레드만 쓴다. 전사 원문은 문자열 표현에 나오지 않는다.
 */
final class InputTranscriptGroup {

    /** 간격을 잴 수 없었다(조각 시간선이 없거나 조각이 하나뿐). */
    static final long NO_GAP = -1L;

    /**
     * 확정 전사 하나의 모양(원문 없음).
     *
     * @param deltaCount 묶인 조각 수(시험 대역의 완료 사건만 왔으면 0)
     * @param maxGapMs 조각 사이 최대 간격(다음 조각 {@code start_ms} − 앞 조각 {@code end_ms}, 0 이상). 잴 수 없으면 {@link #NO_GAP}
     * @param openedAt 첫 조각을 서버가 받은 시각(아이가 말을 시작한 때)
     * @param playingAtOpen 묶음을 열 때 기기가 승인 문구 · 응답을 재생 중이었는가
     */
    record Shape(int deltaCount, long maxGapMs, Instant openedAt, boolean playingAtOpen) {

        Shape {
            Objects.requireNonNull(openedAt, "openedAt");
        }

        /** 조각 없이 완료 사건 하나로 온 확정 전사(시험 대역 경로). */
        static Shape single(Instant at, boolean playing) {
            return new Shape(0, NO_GAP, at, playing);
        }
    }

    /** 꺼낸 묶음. */
    record Closed(String id, String text, Shape shape) {

        @Override
        public String toString() {
            return "Closed[id=" + id + ", shape=" + shape + "]";
        }
    }

    private final StringBuilder text = new StringBuilder();
    private String id;
    private long seq;
    private long opens;
    private int deltaCount;
    private long lastEndMs = NO_GAP;
    private long maxGapMs = NO_GAP;
    private Instant openedAt;
    private boolean playingAtOpen;

    boolean isOpen() {
        return id != null;
    }

    /** 열린 묶음의 id(없으면 null). */
    String id() {
        return id;
    }

    /** 묶음을 처음 연 횟수(단조 증가). */
    long opens() {
        return opens;
    }

    /**
     * 조각을 더한다. 열린 묶음이 없으면 연다(공급자 항목 id가 있으면 그것을, 없으면 서버 번호를 id로 쓴다).
     *
     * @param at 서버가 조각을 받은 시각
     * @param playing 지금 기기가 재생 중인가(묶음을 열 때만 쓴다)
     * @return 묶음 id
     */
    String add(ProviderEvent.InputTranscriptDelta delta, Instant at, boolean playing) {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(at, "at");
        if (id == null) {
            opens++;
            id = delta.itemId() != null ? delta.itemId() : "in-" + (++seq);
            text.setLength(0);
            deltaCount = 0;
            lastEndMs = NO_GAP;
            maxGapMs = NO_GAP;
            openedAt = at;
            playingAtOpen = playing;
        }
        if (lastEndMs >= 0 && delta.startMs() >= 0) {
            maxGapMs = Math.max(maxGapMs, Math.max(0L, delta.startMs() - lastEndMs));
        }
        if (delta.endMs() >= 0) {
            lastEndMs = delta.endMs();
        }
        deltaCount++;
        text.append(delta.delta());
        return id;
    }

    /** 열린 묶음의 모양(없으면 null). */
    Shape shape() {
        return id == null ? null : new Shape(deltaCount, maxGapMs, openedAt, playingAtOpen);
    }

    /** 묶음을 꺼내고 비운다(없으면 null). */
    Closed close() {
        if (id == null) {
            return null;
        }
        Closed closed = new Closed(id, text.toString(), shape());
        clear();
        return closed;
    }

    /** 묶음을 버린다. */
    void clear() {
        id = null;
        text.setLength(0);
        deltaCount = 0;
        lastEndMs = NO_GAP;
        maxGapMs = NO_GAP;
        openedAt = null;
        playingAtOpen = false;
    }

    @Override
    public String toString() {
        return "InputTranscriptGroup[open=" + isOpen() + ", deltas=" + deltaCount + "]";
    }
}
