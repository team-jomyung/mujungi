package kr.mujoong.realtime.gate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 출력 세대 (U11 FR12.5 · BR11.5 ~ BR11.7 · BR11.13 · BR3.4, 기능 설계 2.1, <b>설계 개정 A2 ~ A5 · A4-b</b>). 응답 id를 대신해 공급자 출력 조각을 묶고 버린다.
 *
 * <p>공급자 실호출에는 응답 id도 응답 취소 type도 응답 끝 사건도 없다(편차 (U11-a)). 그래서 서버가 세대 번호를 올려 "그 전의 조각"을 버린다. 한 세션에
 * 하나이고 번호는 올리기만 한다. <b>실행기 스레드에서만 바꾼다</b>(락 없음). 시각은 호출자가 {@code MujoongClock}으로 읽어 넘긴다(R-F6). 시간
 * 값은 생성자로 받는다({@code mujoong.gate.segment-quiet}, {@code mujoong.timing.provider.new-speech-margin}, R-F7).
 *
 * <p>세대를 올리는 방법이 둘이다.
 * <ul>
 *   <li><b>지시를 보내며 올린다</b>({@link #bumpForInstruction}, 모든 서버 발화): 낡은 응답은 지시를 받아도 1.2 ~ 1.8초 더 이어지고 새 말이 쉼 없이
 *       뒤따르므로(조각 사이 쉼으로는 경계를 못 가른다) <b>전사 시간선</b>으로 새 말의 시작을 정한다. 오디오 시계 = 이 세션에서 받은 출력 음성의 누적
 *       바이트 / 48(ms). ① {@code session.instructions.appended}(우리가 실은 {@code event_id}와 {@code client_event_id} 일치)의 {@code end_ms}를
 *       기록한다({@code ackEndMs}). ② 그 뒤 처음 오는 <b>출력 전사 조각 중 {@code start_ms >= ackEndMs}</b>인 것이 새 말의 시작이다
 *       ({@code newStartMs}). 전사가 음성보다 앞서 도착하므로 그 조각부터의 전사는 받는다. ③ 오디오 시계(조각이 시작하는 위치)가
 *       {@code newStartMs + new-speech-margin}에 닿는 첫 음성 조각부터 {@link Phase#RECEIVING}이다. 낡은 꼬리가 새는 것보다 첫 낱말이 잘리는 것이
 *       낫다(fail-closed) — 잘려도 괜찮게 지시문이 짧은 맞장구로 시작하라고 시킨다. 확인 사건이 시한 안에 안 오면(ACK_MISSING) 가장 최근 지시를 보낼
 *       때의 오디오 시계 이후 {@code start_ms}인 전사로 ②를 대신하고, 새 말 전사가 시한 안에 없으면(NEW_SPEECH_NOT_OBSERVED) 이 세대는 닫는다(아이는 아무
 *       소리도 듣지 않는다 = 안전 쪽 실패).
 *       <br><b>조용할 때 보낸 지시(설계 개정 A4-b, 3회차 V2)</b>: 보낼 때 출력이 조용했으면(마지막 조각 — 보류 · 버린 조각 포함 — 뒤
 *       {@code segment-quiet}가 지났거나 조각을 받은 적이 없고, 받았지만 아직 처리하지 않은 조각도 없음) 낡은 꼬리가 없다. 공급자는 유휴 중 지시를 받으면
 *       곧바로(+32 ~ +69ms) 말을 시작해 첫 전사 조각의 {@code start_ms}가 확인 사건 {@code end_ms}보다 앞서므로 ①②를 쓰면 첫머리가 잘린다(실음성
 *       실측 12.3: 15줄 중 7줄). 그래서 이 세대는 확인 사건을 기다리지 않고 보낸 뒤 처음 오는 시간 있는 전사 조각(또는 먼저 온 음성 조각)을 새 말의
 *       시작으로 보고, 음성은 여유 없이 첫 음성 조각부터 받는다. 기준을 구간 봉인과 같은 {@code segment-quiet}로 잡은 까닭은 응답 안 조각 사이 쉼이
 *       최대 234ms였고(800ms 이상 0번) 그 값으로 이미 응답 끝을 판단하기 때문이다. 말하는 중 보낸 지시는 ①②③ 그대로다.
 *   <li><b>지시 없이 올린다</b>({@link #bump}: 끼어들기 · 종료 의도 · 연습 폐기 · 게이트 폐기 · 보류 해제): 낡은 응답은 끊기지 않고 자연 종료까지
 *       이어진다. 마지막 조각 시각과 세대를 올린 시각 중 늦은 쪽에서 {@code segment-quiet}가 지나면 {@link Phase#GAP_CONFIRMED}이고 그 뒤 처음 오는
 *       조각부터 {@link Phase#RECEIVING}이다.
 * </ul>
 * 출력 보류 중(참여 확인 · 종료 확인 질문 · 연습 보류)에는 단계와 관계없이 조각을 버린다(BR11.13). 받는 단계에서 조각이 {@code segment-quiet} 동안
 * 오지 않으면 열린 구간을 '완전'으로 봉인한다(공급자 응답 끝 사건이 없다).
 */
public final class OutputGeneration {

    /** 24kHz s16 모노 출력 음성의 ms당 바이트. */
    public static final long PCM_BYTES_PER_MILLISECOND = 48L;

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
        /** 서버가 지시(= 발화)를 보낸다(직렬 송신 경로, 설계 개정 A2). */
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
        HOLD_RELEASED,
        /** 제브가 막은 뒤 같은 다음 행동을 한 번 다시 지시한다(U13 BR6.2). */
        GUARDRAIL_RETRY,
        /** 대본 줄을 미리 합성한 음성으로 낸다(3회차 V1): 지시를 보내지 않으므로 공급자 조각은 끊김까지 버린다. */
        SCRIPT_CLIP
    }

    /** 지시 하나의 새 말 관측이 어떻게 끝났는가(사건 {@code OUTPUT_NEW_SPEECH}). */
    public enum TraceOutcome {
        /** 새 말의 음성을 받기 시작했다. */
        RECEIVING,
        /** 시한 안에 새 말이 없어 세대를 닫았다. */
        NEW_SPEECH_NOT_OBSERVED,
        /** 새 말 전사는 받았는데 음성이 오지 않은 채 조용해졌다. */
        NO_AUDIO,
        /** 새 말을 받기 전에 세대가 다시 올랐다. */
        SUPERSEDED
    }

    /**
     * 지시 하나의 새 말 시작 관측(3회차 V2, 세션 사건 {@code OUTPUT_NEW_SPEECH}). 전사 원문은 담지 않고 시각 · 개수만 담는다(R-F14). 시각 값은 모두 이
     * 세션의 출력 오디오 시계 · 전사 시간선의 ms이고 모르면 -1이다.
     *
     * @param quietAtSend 보낼 때 출력이 조용했는가(조용 시작 규칙을 썼는가)
     * @param sentClockMs 보낼 때의 오디오 시계
     * @param ackStartMs 확인 사건의 start_ms
     * @param ackEndMs 확인 사건의 end_ms
     * @param firstTranscriptStartMs 처음 받은 전사 조각의 start_ms
     * @param firstTranscriptClockMs 그 조각이 왔을 때의 오디오 시계
     * @param audioFromClockMs 음성을 받기 시작한 조각이 시작하는 오디오 시계
     * @param discardedTranscriptChunks 새 말을 받기 전에 낡은 것으로 버린 전사 조각 수(보류로 버린 것은 빼고)
     * @param discardedTranscriptChars 그 조각들의 글자 수(코드 포인트)
     * @param discardedAudioMs 음성을 받기 전에 버린 음성 길이
     * @param heldChunks 출력 보류로 버린 조각 수
     */
    public record InstructionTrace(String eventId, BumpReason reason, boolean quietAtSend, long sentClockMs, long ackStartMs,
            long ackEndMs, long firstTranscriptStartMs, long firstTranscriptClockMs, long audioFromClockMs,
            int discardedTranscriptChunks, int discardedTranscriptChars, long discardedAudioMs, int heldChunks,
            TraceOutcome outcome) {

        public InstructionTrace {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** 조각을 어떻게 다룰지. */
    public enum ChunkVerdict {
        /** 구간으로 받는다. */
        ACCEPT,
        /** 출력 보류 중이라 버린다(BR11.13, 구간을 열지 않는다). */
        DISCARD_HELD,
        /** 세대를 올린 뒤 아직 새 말(또는 끊김) 전이라 버린다. */
        DISCARD
    }

    /**
     * 조각 처리 결과.
     *
     * @param fromStart 이 조각이 구간을 여는 첫 조각인가(구간이 첫 조각부터 담겼는지, 완전 봉인의 조건)
     */
    public record ChunkResult(ChunkVerdict verdict, boolean fromStart) {
    }

    /** {@link #onQuiet}가 알리는 것. */
    public enum QuietVerdict {
        /** 받는 단계에서 조용했다 — 열린 구간이 있으면 '완전'으로 봉인한다. */
        SEAL_RECEIVING,
        /** 아직 조용 시간이 안 찼다 — {@code waitUntil}에 다시 확인한다. */
        NOT_YET,
        /** 끊김을 확인했다(지시 없이 올린 세대). 다음 조각부터 받는다. */
        GAP_CONFIRMED,
        /** 새 말 전사는 받았는데 음성이 여유 시각에 닿지 못한 채 조용해졌다 — 열린 구간(전사뿐)을 불완전으로 버린다. */
        ABANDON_NO_AUDIO,
        /** 할 일이 없다. */
        NONE
    }

    /** 끊김 확인 결과. */
    public record QuietResult(QuietVerdict verdict, Instant waitUntil) {
    }

    /** 지시 응답 시한({@code response-ready})이 지났을 때 알리는 것(사유 코드만 남긴다, R-F14). */
    public enum InstructionTimeout {
        /** 기다리는 지시가 없다. */
        NONE,
        /** 확인 사건이 안 왔다 — 가장 최근 지시 이후 전사로 새 말을 찾아 계속한다. */
        ACK_MISSING,
        /** 새 말 전사가 시한 안에 없다 — 이 세대는 닫는다. */
        NEW_SPEECH_NOT_OBSERVED
    }

    private enum Mode {
        GAP,
        INSTRUCTION
    }

    private final Duration segmentQuiet;
    private final long marginMs;
    private final Consumer<InstructionTrace> traces;

    private long generation;
    private Phase phase = Phase.RECEIVING;
    private Mode mode = Mode.GAP;
    private Instant lastDeltaAt;
    private Instant bumpedAt;
    private boolean boundary = true;
    private boolean corrected;
    private long audioBytes;

    // 지시 경로 상태(지시를 보내며 올린 세대에서만 쓴다)
    private String pendingEventId;
    private long sentClockMs;
    private long ackEndMs = -1L;
    private boolean ackMissing;
    private long newStartMs = -1L;
    private boolean transcriptOpen;
    /** 보낼 때 출력이 조용했다(설계 개정 A4-b): 확인 사건 · 여유 없이 보낸 뒤 첫 조각부터 새 말이다. */
    private boolean quietStart;
    /** 지금 지시 세대의 새 말 관측(닫히면 null). */
    private TraceState trace;

    /**
     * @param segmentQuiet 조각이 이만큼 끊기면 끊김 · 구간 봉인(env {@code mujoong.gate.segment-quiet})
     * @param newSpeechMargin 새 말 시작에 더하는 여유(env {@code mujoong.timing.provider.new-speech-margin})
     */
    public OutputGeneration(Duration segmentQuiet, Duration newSpeechMargin) {
        this(segmentQuiet, newSpeechMargin, trace -> { });
    }

    /**
     * @param traces 지시 하나의 새 말 관측이 끝날 때마다 받는 곳(실행기 스레드에서 곧바로 부른다, 기다리지 않아야 한다)
     */
    public OutputGeneration(Duration segmentQuiet, Duration newSpeechMargin, Consumer<InstructionTrace> traces) {
        this.segmentQuiet = Objects.requireNonNull(segmentQuiet, "segmentQuiet");
        Objects.requireNonNull(newSpeechMargin, "newSpeechMargin");
        if (segmentQuiet.isNegative() || newSpeechMargin.isNegative()) {
            throw new IllegalArgumentException("시간 값은 음수일 수 없다");
        }
        this.marginMs = newSpeechMargin.toMillis();
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    /** 현재 세대 번호(0부터, 올리기만 한다). */
    public long generation() {
        return generation;
    }

    public Phase phase() {
        return phase;
    }

    /** 이 세션에서 받은 출력 음성의 누적 길이(ms, 보류 · 버린 조각도 센다). */
    public long audioClockMs() {
        return audioBytes / PCM_BYTES_PER_MILLISECOND;
    }

    /** Continuous digital silence belongs to the provider timeline but must not postpone a speech boundary. */
    public void onSilentAudio(int bytes) {
        if (bytes <= 0 || bytes % 2 != 0) throw new IllegalArgumentException("Invalid PCM silence length");
        audioBytes += bytes;
    }

    /** 마지막 조각 시각(어느 단계 · 출력 범위 밖에서 버린 조각도 포함, 없으면 null). */
    public Instant lastDeltaAt() {
        return lastDeltaAt;
    }

    /** 세대를 올린 시각(올린 적이 없으면 null). */
    public Instant bumpedAt() {
        return bumpedAt;
    }

    /** 지시를 보내며 올린 세대가 새 말을 기다리는 중인가(확인 사건 또는 새 말 전사 전, 시한 만료로 닫기 전). */
    public boolean awaitingNewSpeech() {
        return mode == Mode.INSTRUCTION && phase == Phase.DISCARDING && newStartMs < 0;
    }

    /** 확인 사건을 받았는가(지시 경로). */
    public boolean ackReceived() {
        return mode == Mode.INSTRUCTION && ackEndMs >= 0;
    }

    /** 새 말의 전사 시작 시각(ms, 아직 모르면 -1). */
    public long newStartMs() {
        return newStartMs;
    }

    /** 확인 사건의 end_ms(ms, 아직 모르면 -1). */
    public long ackEndMs() {
        return ackEndMs;
    }

    /** 지금 지시 세대가 조용할 때 보낸 지시인가(설계 개정 A4-b, 지시 없이 올린 세대면 거짓). */
    public boolean quietStart() {
        return mode == Mode.INSTRUCTION && quietStart;
    }

    /**
     * 지금 출력이 조용한가: 조각(보류 · 버린 조각 포함, 무음은 빼고)을 받은 적이 없거나 마지막 조각 뒤 {@code segment-quiet}가 지났다. 구간 봉인과 같은
     * 기준이다.
     */
    public boolean quietAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return lastDeltaAt == null || !now.isBefore(lastDeltaAt.plus(segmentQuiet));
    }

    /**
     * 출력 음성 조각이 왔다. 어느 단계 · 보류 여부와 관계없이 오디오 시계와 마지막 조각 시각을 갱신하고, 단계에 따라 받을지 버릴지 정한다.
     *
     * @param bytes 이 조각의 PCM 바이트 수
     * @param held 지금 출력 보류 중인가(WAITING_CHILD · 종료 확인 진행 중 · 연습 보류)
     */
    public ChunkResult onAudio(Instant at, int bytes, boolean held) {
        Objects.requireNonNull(at, "at");
        long clockStartMs = audioClockMs();
        audioBytes += Math.max(0, bytes);
        lastDeltaAt = at;
        if (quietStart && mode == Mode.INSTRUCTION && phase == Phase.DISCARDING && newStartMs < 0) {
            // 조용할 때 보낸 지시: 낡은 꼬리가 없으니 전사보다 먼저 온 음성 조각도 새 말의 시작이다
            newStartMs = clockStartMs;
            boundary = true;
        }
        if (held) {
            noteHeld();
            return new ChunkResult(ChunkVerdict.DISCARD_HELD, false);
        }
        switch (phase) {
            case RECEIVING:
                return new ChunkResult(ChunkVerdict.ACCEPT, takeBoundary());
            case DISCARDING:
                if (mode == Mode.INSTRUCTION && newStartMs >= 0 && (quietStart || clockStartMs >= newStartMs + marginMs)) {
                    phase = Phase.RECEIVING;
                    finishTrace(TraceOutcome.RECEIVING, clockStartMs);
                    return new ChunkResult(ChunkVerdict.ACCEPT, takeBoundary());
                }
                if (trace != null) {
                    trace.discardedAudioMs += Math.max(0, bytes) / PCM_BYTES_PER_MILLISECOND;
                }
                return new ChunkResult(ChunkVerdict.DISCARD, false);
            case GAP_CONFIRMED:
                phase = Phase.RECEIVING;
                boundary = true;
                return new ChunkResult(ChunkVerdict.ACCEPT, takeBoundary());
            default:
                throw new IllegalStateException("모르는 단계: " + phase);
        }
    }

    /**
     * 출력 전사 조각이 왔다(글자 수 모름). {@link #onTranscript(Instant, long, int, boolean)}와 같다.
     */
    public ChunkResult onTranscript(Instant at, long startMs, boolean held) {
        return onTranscript(at, startMs, 0, held);
    }

    /**
     * 출력 전사 조각이 왔다. 지시 경로에서는 확인 사건 뒤 처음 오는 {@code start_ms >= ackEndMs} 조각이 새 말의 시작이고, 그 조각부터의 전사는
     * 음성이 아직 낡은 꼬리여도 받는다(전사가 음성보다 앞서 도착한다). 조용할 때 보낸 지시는 확인 사건을 기다리지 않고 보낸 뒤 처음 오는 시간 있는
     * 조각이 새 말의 시작이다(설계 개정 A4-b).
     *
     * @param startMs 전사 시간선의 시작(ms, 없으면 음수)
     * @param chars 조각의 글자 수(관측용 개수, 원문은 받지 않는다)
     */
    public ChunkResult onTranscript(Instant at, long startMs, int chars, boolean held) {
        Objects.requireNonNull(at, "at");
        lastDeltaAt = at;
        if (mode == Mode.INSTRUCTION && phase == Phase.DISCARDING && newStartMs < 0 && startMs >= 0) {
            if (quietStart || (ackEndMs >= 0 && startMs >= ackEndMs) || (ackMissing && startMs >= sentClockMs)) {
                newStartMs = startMs;
                boundary = true;
            }
        }
        if (held) {
            noteHeld();
            return new ChunkResult(ChunkVerdict.DISCARD_HELD, false);
        }
        switch (phase) {
            case RECEIVING:
                return new ChunkResult(ChunkVerdict.ACCEPT, takeBoundary());
            case DISCARDING:
                if (mode == Mode.INSTRUCTION && newStartMs >= 0 && startMs >= newStartMs) {
                    transcriptOpen = true;
                    if (trace != null && trace.firstTranscriptStartMs < 0) {
                        trace.firstTranscriptStartMs = startMs;
                        trace.firstTranscriptClockMs = audioClockMs();
                    }
                    return new ChunkResult(ChunkVerdict.ACCEPT, takeBoundary());
                }
                if (trace != null) {
                    trace.discardedTranscriptChunks++;
                    trace.discardedTranscriptChars += Math.max(0, chars);
                }
                return new ChunkResult(ChunkVerdict.DISCARD, false);
            case GAP_CONFIRMED:
                phase = Phase.RECEIVING;
                boundary = true;
                return new ChunkResult(ChunkVerdict.ACCEPT, takeBoundary());
            default:
                throw new IllegalStateException("모르는 단계: " + phase);
        }
    }

    /**
     * 지시 확인 사건({@code session.instructions.appended})을 받았다(start_ms 모름). {@link #onAppended(String, long, long)}와 같다.
     */
    public boolean onAppended(String clientEventId, long endMs) {
        return onAppended(clientEventId, -1L, endMs);
    }

    /**
     * 지시 확인 사건({@code session.instructions.appended})을 받았다. 가장 최근에 보낸 지시의 {@code event_id}와 맞을 때만 받는다(낡은 지시의
     * 확인 · 모르는 id는 무시). 조용할 때 보낸 지시는 새 말의 시작을 이 사건으로 정하지 않지만 관측 값으로 남긴다.
     *
     * @param startMs 확인 사건의 start_ms(관측용, 없으면 음수)
     * @return 받았는가
     */
    public boolean onAppended(String clientEventId, long startMs, long endMs) {
        if (mode != Mode.INSTRUCTION || pendingEventId == null || !pendingEventId.equals(clientEventId) || endMs < 0
                || ackEndMs >= 0) {
            return false;
        }
        ackEndMs = endMs;
        if (trace != null) {
            trace.ackStartMs = startMs < 0 ? -1L : startMs;
            trace.ackEndMs = endMs;
            if (trace.outcome != null) {
                // 새 말을 이미 받기 시작한 관측(조용 시작은 확인 사건이 늦게 온다)을 확인 사건 값과 함께 남긴다
                finishTrace(trace.outcome, trace.audioFromClockMs);
            }
        }
        return true;
    }

    /**
     * 지시 응답 시한({@code response-ready})이 지났다.
     *
     * @return 확인 사건이 없으면 {@link InstructionTimeout#ACK_MISSING}(전사로 새 말을 찾아 계속, 한 번), 확인은 왔는데 새 말이 없거나 이미 한 번
     *     ACK_MISSING을 알렸으면 {@link InstructionTimeout#NEW_SPEECH_NOT_OBSERVED}(이 세대는 닫는다)
     */
    public InstructionTimeout onInstructionTimeout() {
        if (!awaitingNewSpeech()) {
            return InstructionTimeout.NONE;
        }
        if (ackEndMs < 0 && !ackMissing) {
            ackMissing = true;
            return InstructionTimeout.ACK_MISSING;
        }
        // 이 세대는 닫는다: 아이는 이 지시의 응답을 듣지 않는다. 다만 이후 아이 말에 대한 자동 응답이 영영 막히지 않게 지시 없이 올린 세대처럼
        // 조각이 끊기기를 기다린 뒤 다시 받는다
        finishTrace(TraceOutcome.NEW_SPEECH_NOT_OBSERVED, -1L);
        mode = Mode.GAP;
        quietStart = false;
        pendingEventId = null;
        newStartMs = -1L;
        transcriptOpen = false;
        boundary = true;
        return InstructionTimeout.NEW_SPEECH_NOT_OBSERVED;
    }

    /**
     * 지시 없이 세대를 올린다(끼어들기 · 종료 의도 · 연습 폐기 · 게이트 폐기 · 보류 해제). 열린 구간을 '불완전'으로 봉인해 버리는 일과 기기 정지
     * 명령은 호출자가 한다. 낡은 응답은 끊기지 않으므로 조각이 {@code segment-quiet} 동안 끊기기를 기다린다.
     *
     * @return 새 세대 번호
     */
    public long bump(Instant now, BumpReason reason) {
        return doBump(now, reason, Mode.GAP, null);
    }

    /**
     * 서버가 지시(= 발화)를 보내며 세대를 올린다(설계 개정 A2). 받았지만 아직 처리하지 않은 조각은 없다고 본다.
     * {@link #bumpForInstruction(Instant, BumpReason, String, boolean)}와 같다.
     */
    public long bumpForInstruction(Instant now, BumpReason reason, String eventId) {
        return bumpForInstruction(now, reason, eventId, false);
    }

    /**
     * 서버가 지시(= 발화)를 보내며 세대를 올린다(설계 개정 A2). 이 세대는 전사 시간선으로 새 말의 시작을 찾는다. 보낼 때 출력이 조용했으면
     * ({@link #quietAt} · 처리하지 않은 조각 없음) 조용 시작 규칙(A4-b)을 쓴다.
     *
     * @param eventId 보내는 {@code append}의 {@code event_id}(확인 사건의 {@code client_event_id}와 맞춘다)
     * @param chunksInFlight 공급자 리더가 받았지만 실행기가 아직 처리하지 않은 출력 조각이 있는가(있으면 조용하지 않다)
     * @return 새 세대 번호
     */
    public long bumpForInstruction(Instant now, BumpReason reason, String eventId, boolean chunksInFlight) {
        Objects.requireNonNull(eventId, "eventId");
        boolean quiet = !chunksInFlight && quietAt(now);
        long next = doBump(now, reason, Mode.INSTRUCTION, eventId);
        quietStart = quiet;
        trace = new TraceState(eventId, reason, quiet, sentClockMs);
        return next;
    }

    private long doBump(Instant now, BumpReason reason, Mode newMode, String eventId) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(reason, "reason");
        finishTrace(TraceOutcome.SUPERSEDED, -1L);
        generation++;
        phase = Phase.DISCARDING;
        mode = newMode;
        quietStart = false;
        bumpedAt = now;
        corrected = false;
        boundary = true;
        pendingEventId = eventId;
        sentClockMs = audioClockMs();
        ackEndMs = -1L;
        ackMissing = false;
        newStartMs = -1L;
        transcriptOpen = false;
        return generation;
    }

    /** 조각 시각을 바탕으로 조용함을 확인한다. SEGMENT_QUIET 시계가 만기될 때 부른다. */
    public QuietResult onQuiet(Instant now) {
        Objects.requireNonNull(now, "now");
        Instant effectiveLast = latest(lastDeltaAt, bumpedAt);
        boolean quiet = effectiveLast == null || !now.isBefore(effectiveLast.plus(segmentQuiet));
        switch (phase) {
            case RECEIVING:
                if (!quiet) {
                    return new QuietResult(QuietVerdict.NOT_YET, effectiveLast.plus(segmentQuiet));
                }
                boundary = true;
                return new QuietResult(QuietVerdict.SEAL_RECEIVING, null);
            case DISCARDING:
                if (mode == Mode.INSTRUCTION) {
                    if (transcriptOpen) {
                        if (!quiet) {
                            return new QuietResult(QuietVerdict.NOT_YET, effectiveLast.plus(segmentQuiet));
                        }
                        // 전사는 받았는데 음성이 여유 시각에 닿기 전에 끝났다: 전사만 있는 구간은 완전하지 않다
                        finishTrace(TraceOutcome.NO_AUDIO, -1L);
                        transcriptOpen = false;
                        mode = Mode.GAP;
                        quietStart = false;
                        boundary = true;
                        // 같은 응답의 남은 음성이 뒤늦게 오면 새 구간으로 받지 않도록 지시 없이 올린 세대처럼 다시 끊김을 기다린다
                        return new QuietResult(QuietVerdict.ABANDON_NO_AUDIO, null);
                    }
                    return new QuietResult(QuietVerdict.NONE, null);
                }
                if (!quiet) {
                    return new QuietResult(QuietVerdict.NOT_YET, effectiveLast.plus(segmentQuiet));
                }
                phase = Phase.GAP_CONFIRMED;
                boundary = true;
                return new QuietResult(QuietVerdict.GAP_CONFIRMED, null);
            case GAP_CONFIRMED:
                return new QuietResult(QuietVerdict.NONE, null);
            default:
                throw new IllegalStateException("모르는 단계: " + phase);
        }
    }

    /** 이 세대의 교정 지시를 아직 안 보냈으면 보낸 것으로 적고 참(세대당 한 번, BR11.12 W8-6). */
    public boolean claimCorrection() {
        if (corrected) {
            return false;
        }
        corrected = true;
        return true;
    }

    private boolean takeBoundary() {
        boolean fromStart = boundary;
        boundary = false;
        return fromStart;
    }

    private void noteHeld() {
        if (trace != null && trace.outcome == null) {
            trace.heldChunks++;
        }
    }

    /**
     * 관측의 결과를 정하고 내보낸다(열린 것이 없으면 아무것도 하지 않는다). 새 말을 받기 시작했는데 확인 사건이 아직 없으면(조용 시작은 확인 사건이 새 말보다
     * 늦게 온다) 확인 사건이나 다음 세대 올림까지 기다렸다가 함께 내보낸다. 결과가 이미 정해진 관측은 그 결과로 내보낸다.
     */
    private void finishTrace(TraceOutcome outcome, long audioFromClockMs) {
        TraceState open = trace;
        if (open == null) {
            return;
        }
        if (open.outcome == null) {
            open.outcome = outcome;
            open.audioFromClockMs = audioFromClockMs;
            if (outcome == TraceOutcome.RECEIVING && open.ackEndMs < 0) {
                return;
            }
        }
        trace = null;
        traces.accept(new InstructionTrace(open.eventId, open.reason, open.quietAtSend, open.sentClockMs, open.ackStartMs,
                open.ackEndMs, open.firstTranscriptStartMs, open.firstTranscriptClockMs, open.audioFromClockMs,
                open.discardedTranscriptChunks, open.discardedTranscriptChars, open.discardedAudioMs, open.heldChunks,
                open.outcome));
    }

    /** 지시 하나의 새 말 관측(실행기 스레드 전용). */
    private static final class TraceState {
        final String eventId;
        final BumpReason reason;
        final boolean quietAtSend;
        final long sentClockMs;
        long ackStartMs = -1L;
        long ackEndMs = -1L;
        long firstTranscriptStartMs = -1L;
        long firstTranscriptClockMs = -1L;
        int discardedTranscriptChunks;
        int discardedTranscriptChars;
        long discardedAudioMs;
        int heldChunks;
        long audioFromClockMs = -1L;
        /** 정해진 결과(아직 열려 있으면 null). */
        TraceOutcome outcome;

        TraceState(String eventId, BumpReason reason, boolean quietAtSend, long sentClockMs) {
            this.eventId = eventId;
            this.reason = reason;
            this.quietAtSend = quietAtSend;
            this.sentClockMs = sentClockMs;
        }
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
