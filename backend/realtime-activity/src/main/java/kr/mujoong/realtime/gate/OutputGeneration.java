package kr.mujoong.realtime.gate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 출력 세대 (U11 FR12.5 · BR11.5 ~ BR11.7 · BR11.13 · BR3.4, 기능 설계 2.1). 응답 id를 대신해 공급자 출력 조각을 묶고 버린다.
 *
 * <p>공급자 실호출에는 응답 id도 응답 취소 type도 없다(편차 (U11-a)). 그래서 서버가 세대 번호를 올려 "그 전의 조각"을 버리고, 조각이 한동안
 * 끊기면 앞 응답이 끝난 것으로 본다. 한 세션에 하나이고 번호는 올리기만 한다. <b>실행기 스레드에서만 바꾼다</b>(락 없음). 직렬 송신 작업(다른 스레드)은
 * 송신 직전 대조에 쓰는 {@link #published()}만 읽는다(불변 값을 통째로 바꿔 끼우는 {@code volatile}).
 *
 * <p>단계: {@link Phase#RECEIVING}(조각을 구간으로 받는다) · {@link Phase#DISCARDING}(세대를 올린 뒤 끊김을 기다리며 조각을 버린다) ·
 * {@link Phase#GAP_CONFIRMED}(끊김을 확인했다 — 보낼 지시가 없으면 다음 조각부터 받고, 있으면 송신 처리 뒤 첫 조각부터 받는다).
 *
 * <p>끊김 확인(BR11.7)은 두 조건이 모두 참일 때만이다: ① 마지막 조각 시각과 세대를 올린 시각 중 늦은 쪽에서 {@code segment-quiet}가 지났다.
 * ② 아이 말 끝 이후 조각이 한 번이라도 왔거나(자동 응답이 이미 시작됐다), 아직 한 번도 오지 않았으면 말 끝 시각 +
 * {@code auto-response-start}가 지났다(자동 응답이 아직 시작 전인데 끊김으로 잘못 보는 일을 막는다).
 *
 * <p>시각은 호출자가 {@code MujoongClock}으로 읽어 넘긴다(R-F6). 시간 값 두 개는 생성자로 받는다({@code mujoong.timing.provider.*}, R-F7).
 */
public final class OutputGeneration {

    /** 출력 세대의 단계. */
    public enum Phase {
        RECEIVING,
        DISCARDING,
        GAP_CONFIRMED
    }

    /** 세대를 올리는 까닭(사유 코드만 로그에 남긴다, R-F14). */
    public enum BumpReason {
        /** 판정 턴을 열었다(PR2). */
        JUDGMENT_TURN,
        /** 서버가 지시 · 응답 요청을 보내려 한다(직렬 송신 경로, BR3.3). */
        SERVER_SEND,
        /** 아이가 끼어들었다. */
        BARGE_IN,
        /** 종료 의도로 출력을 멈춘다. */
        STOP_INTENT,
        /** 연습 흐름이 출력을 폐기한다. */
        PRACTICE_DISCARD,
        /** 게이트 폐기 뒤 교정 지시를 보낸다. */
        GATE_CORRECTION,
        /** 출력 보류가 풀렸다(BR11.13). */
        HOLD_RELEASED
    }

    /** 조각을 어떻게 다룰지. */
    public enum ChunkVerdict {
        /** 구간으로 받는다. */
        ACCEPT,
        /** 출력 보류 중이라 버린다(BR11.13, 구간을 열지 않는다). */
        DISCARD_HELD,
        /** 세대를 올린 뒤 아직 끊김 전이라 버린다. */
        DISCARD
    }

    /**
     * 조각 처리 결과.
     *
     * @param fromStart 이 조각이 앞 끊김 · 세대 올림 뒤 첫 조각인가(구간이 첫 조각부터 담겼는지, 완전 봉인의 조건)
     * @param permitInvalidated 송신 대기 중에 조각이 와서 끊김 확인(허가)을 무효로 했는가
     */
    public record ChunkResult(ChunkVerdict verdict, boolean fromStart, boolean permitInvalidated) {
    }

    /** {@link #onQuiet}가 알리는 것. */
    public enum QuietVerdict {
        /** 받는 단계에서 조용했다 — 열린 구간이 있으면 '완전'으로 봉인한다. */
        SEAL_RECEIVING,
        /** 아직 끊김 조건 ①(조용 시간)이 안 찼다 — {@code waitUntil}에 다시 확인한다. */
        NOT_YET,
        /** ①은 찼지만 자동 응답 시작 전일 수 있다 — {@code waitUntil}에 다시 확인한다(자동 응답 시작 대기). */
        WAIT_AUTO_RESPONSE,
        /** 끊김을 확인했다. 송신 대기가 있으면 {@code permitSeq}의 허가를 내준다. */
        GAP_CONFIRMED,
        /** 할 일이 없다(끊김 확인 뒤, 이미 처리됨). */
        NONE
    }

    /**
     * 끊김 확인 결과.
     *
     * @param waitUntil 다시 확인할 시각({@code NOT_YET} · {@code WAIT_AUTO_RESPONSE}일 때)
     * @param permitReady {@code GAP_CONFIRMED}이고 송신 대기가 있어 허가를 내줘야 하는가
     */
    public record QuietResult(QuietVerdict verdict, Instant waitUntil, boolean permitReady, long generation, long permitSeq) {
    }

    /** 송신 작업이 보내지 못하고 끝났을 때 실행기가 할 일. */
    public enum AbortAction {
        /** 세대가 바뀌었다 — 그 송신은 더 의미가 없다(새 송신이 있으면 그 작업이 다음 차례). */
        STALE_GENERATION,
        /** 같은 세대에서 허가만 무효가 됐다 — 다시 끊김을 기다려 새 허가를 낸다. */
        RE_PERMIT
    }

    /** 다른 스레드가 읽는 현재 세대 · 허가 번호(송신 직전 대조용). */
    public record Published(long generation, long permitSeq) {
    }

    private final Duration segmentQuiet;
    private final Duration autoResponseStart;

    private long generation;
    private Phase phase = Phase.RECEIVING;
    private Instant lastDeltaAt;
    private Instant bumpedAt;
    private Instant speechEndAt;
    private boolean deltaSinceSpeechEnd;
    private long permitSeq;
    private boolean pendingSend;
    private boolean sendProcessed;
    private boolean boundary = true;
    private boolean corrected;
    private volatile Published published = new Published(0L, 0L);

    /**
     * @param segmentQuiet 조각이 이만큼 끊기면 끊김의 조건 ①(env {@code mujoong.timing.provider.segment-quiet})
     * @param autoResponseStart 말 끝 뒤 자동 응답 시작을 기다리는 시간(env {@code mujoong.timing.provider.auto-response-start})
     */
    public OutputGeneration(Duration segmentQuiet, Duration autoResponseStart) {
        this.segmentQuiet = Objects.requireNonNull(segmentQuiet, "segmentQuiet");
        this.autoResponseStart = Objects.requireNonNull(autoResponseStart, "autoResponseStart");
        if (segmentQuiet.isNegative() || autoResponseStart.isNegative()) {
            throw new IllegalArgumentException("시간 값은 음수일 수 없다");
        }
    }

    /** 현재 세대 번호(0부터, 올리기만 한다). */
    public long generation() {
        return generation;
    }

    public Phase phase() {
        return phase;
    }

    /** 끊김을 확인할 때마다, 송신 대기 중 조각이 와서 허가를 무효로 할 때마다 오르는 세션 전체 번호. */
    public long permitSeq() {
        return permitSeq;
    }

    /** 현재 세대로 보내려는 지시 · 응답 요청이 끊김을 기다리고 있는가. */
    public boolean pendingSend() {
        return pendingSend;
    }

    /** '보내는 중' 표시({@code InstructionSent})를 처리했는가. */
    public boolean sendProcessed() {
        return sendProcessed;
    }

    /** 마지막 조각 시각(어느 단계 · 출력 범위 밖에서 버린 조각도 포함, 없으면 null). */
    public Instant lastDeltaAt() {
        return lastDeltaAt;
    }

    /** 세대를 올린 시각(올린 적이 없으면 null). */
    public Instant bumpedAt() {
        return bumpedAt;
    }

    /** 다른 스레드가 읽어도 되는 현재 세대 · 허가 번호. */
    public Published published() {
        return published;
    }

    /** 아이 말이 끝났다(말 끝 사건 또는 전사 확정). 이 말에 대한 자동 응답 시작을 기다리는 기준 시각이 된다. */
    public void onSpeechEnd(Instant at) {
        Objects.requireNonNull(at, "at");
        if (speechEndAt == null || deltaSinceSpeechEnd) {
            speechEndAt = at;
            deltaSinceSpeechEnd = false;
        }
    }

    /**
     * 출력 조각(음성 · 전사)이 왔다. 어느 단계 · 보류 여부와 관계없이 마지막 조각 시각을 갱신하고, 단계에 따라 받을지 버릴지 정한다(BR11.13).
     *
     * @param held 지금 출력 보류 중인가(WAITING_CHILD · 종료 확인 진행 중 · 연습 보류)
     */
    public ChunkResult onChunk(Instant at, boolean held) {
        Objects.requireNonNull(at, "at");
        lastDeltaAt = at;
        deltaSinceSpeechEnd = true;
        boolean fromStart = boundary;
        boundary = false;
        if (held) {
            return new ChunkResult(ChunkVerdict.DISCARD_HELD, fromStart, false);
        }
        switch (phase) {
            case RECEIVING:
                return new ChunkResult(ChunkVerdict.ACCEPT, fromStart, false);
            case DISCARDING:
                return new ChunkResult(ChunkVerdict.DISCARD, fromStart, false);
            case GAP_CONFIRMED:
                if (pendingSend && !sendProcessed) {
                    // 허가를 받았지만 송신 표시를 처리하기 전에 조각이 왔다 — 끊김이 아니었다. 허가를 무효로 하고 다시 기다린다
                    permitSeq++;
                    phase = Phase.DISCARDING;
                    publish();
                    return new ChunkResult(ChunkVerdict.DISCARD, fromStart, true);
                }
                phase = Phase.RECEIVING;
                pendingSend = false;
                sendProcessed = false;
                return new ChunkResult(ChunkVerdict.ACCEPT, fromStart, false);
            default:
                throw new IllegalStateException("모르는 단계: " + phase);
        }
    }

    /**
     * 세대를 올린다. 열린 구간을 '불완전'으로 봉인해 버리는 일과 기기 정지 명령은 호출자가 한다.
     *
     * @return 새 세대 번호
     */
    public long bump(Instant now, BumpReason reason) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(reason, "reason");
        generation++;
        phase = Phase.DISCARDING;
        bumpedAt = now;
        sendProcessed = false;
        pendingSend = reason == BumpReason.SERVER_SEND;
        corrected = false;
        boundary = true;
        publish();
        return generation;
    }

    /** 말 끝 · 조각 시각을 바탕으로 끊김을 확인한다. SEGMENT_QUIET · AUTO_RESPONSE_START 시계가 만기될 때 부른다. */
    public QuietResult onQuiet(Instant now) {
        Objects.requireNonNull(now, "now");
        Instant effectiveLast = latest(lastDeltaAt, bumpedAt);
        boolean quiet = effectiveLast == null || !now.isBefore(effectiveLast.plus(segmentQuiet));
        switch (phase) {
            case RECEIVING:
                if (!quiet) {
                    return new QuietResult(QuietVerdict.NOT_YET, effectiveLast.plus(segmentQuiet), false, generation, permitSeq);
                }
                boundary = true;
                return new QuietResult(QuietVerdict.SEAL_RECEIVING, null, false, generation, permitSeq);
            case DISCARDING:
                if (!quiet) {
                    return new QuietResult(QuietVerdict.NOT_YET, effectiveLast.plus(segmentQuiet), false, generation, permitSeq);
                }
                if (speechEndAt != null && !deltaSinceSpeechEnd) {
                    Instant autoStart = speechEndAt.plus(autoResponseStart);
                    if (now.isBefore(autoStart)) {
                        return new QuietResult(QuietVerdict.WAIT_AUTO_RESPONSE, autoStart, false, generation, permitSeq);
                    }
                }
                phase = Phase.GAP_CONFIRMED;
                boundary = true;
                permitSeq++;
                publish();
                return new QuietResult(QuietVerdict.GAP_CONFIRMED, null, pendingSend && !sendProcessed, generation, permitSeq);
            case GAP_CONFIRMED:
                return new QuietResult(QuietVerdict.NONE, null, false, generation, permitSeq);
            default:
                throw new IllegalStateException("모르는 단계: " + phase);
        }
    }

    /**
     * 직렬 송신 작업이 '보내는 중' 표시를 일반 편지함에 넣었고 실행기가 그것을 꺼냈다. 세대가 다르면 받지 않는다. 허가 번호가 낡았어도(표시를
     * 넣은 뒤 같은 세대에서 조각이 와서 허가를 무효로 했더라도) 송신은 이미 일어난 것이라 받는다 — 이미 버린 조각은 되살리지 않고 다음 조각부터
     * 받는다(R-19).
     *
     * @return 이 세대의 표시를 받았는가
     */
    public boolean acceptInstructionSent(long sentGeneration, long sentPermitSeq) {
        if (sentGeneration != generation) {
            return false;
        }
        // 허가가 그대로 유효했든 낡았든 송신은 이미 일어난 것이라 같다: 표시 처리 뒤 첫 조각부터 지시 응답 구간이다(sentPermitSeq는 호출자가
        // 낡은 허가였는지 로그 사유로 구분할 때 쓴다)
        sendProcessed = true;
        phase = Phase.GAP_CONFIRMED;
        return true;
    }

    /** 직렬 송신 작업이 보내지 못하고 끝났다(대조 어긋남). */
    public AbortAction onSendAborted(long abortedGeneration, long abortedPermitSeq) {
        if (abortedGeneration != generation) {
            return AbortAction.STALE_GENERATION;
        }
        return AbortAction.RE_PERMIT;
    }

    /**
     * 끊김 대기 상한 만기 · 세션 종료 · 장애로 송신 대기를 거둔다. 호출자는 그 작업의 허가 칸을 '거부'로 채운다(큐를 영영 쥐지 않게, W9-4).
     *
     * @return 송신 대기가 있었는가
     */
    public boolean cancelPendingSend() {
        boolean had = pendingSend && !sendProcessed;
        pendingSend = false;
        return had;
    }

    /** 이 세대의 교정 지시를 아직 안 보냈으면 보낸 것으로 적고 참(세대당 한 번, BR11.12 W8-6). */
    public boolean claimCorrection() {
        if (corrected) {
            return false;
        }
        corrected = true;
        return true;
    }

    private void publish() {
        published = new Published(generation, permitSeq);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
