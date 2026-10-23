package kr.mujoong.realtime.log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;

/**
 * 세션 하나의 전사 행 조립 (U1 3.5 Step 25 · BR7.5 · BR6.15 · PD-5 2.4 · NFR1.1 · NFR5.3).
 *
 * <p>세션 실행기 한 스레드만 부른다(잠금 없음, PD-1). 번호는 세션 안에서 1부터 오르고 처음 칸에 넣는 순서가 번호 순서다. 아이 전사는
 * 공급자 입력 전사 항목마다 번호 하나를 첫 조각 때 정하고, 조각은 같은 번호로 덮어쓰며 확정(done)되면 FINAL · 승인 true다. AI 말은 응답의
 * 첫 출력 조각 때 번호를 정해 확정 전 칸(approved false, 텍스트 비움)을 넣고, 폐기는 게이트 판정 때 닫고, 승인된 음성은 기기 STARTED 확인 때 재생 시각으로 FINAL 처리한다 —
 * 폐기한 구간도 FINAL · false로 닫아야 12의 nextSeq가 풀린다(BR6.15). 게이트를 통과하기 전의 AI 텍스트는 DB에 쓰지 않는다(NFR1.1).
 */
public final class TranscriptWriter {

    private final UUID sessionId;
    private final AsyncRecorder recorder;
    private final LatencyRecorder latency;
    private final Map<String, ChildRow> childRows = new HashMap<>();
    private int lastSeq;
    private AiRow openAi;
    private final Map<UUID, PendingPlayback> pendingPlayback = new HashMap<>();
    private record PendingPlayback(Held held, String text) {}
    /**
     * 단위별 검사 경로(스트리밍)의 응답 묶음 행. 응답이 여러 출력 단위로 나뉘어도 보호자 화면에는 한 행으로 보이도록, 통과한 단위의 전사를 응답마다
     * 여기에 모았다가 응답이 끝나고 그 응답의 검사 중인 단위가 모두 정리되면 한 번에 닫는다(통과 전 텍스트는 DB에 쓰지 않는다, NFR1.1).
     * 앞 응답의 단위가 아직 검사 중일 때 다음 응답이 시작될 수 있어 응답마다 따로 둔다.
     */
    private StreamRow currentStream;
    private final List<StreamRow> endedStreams = new ArrayList<>();
    /** U13 BR5.5: 제브 검사를 기다리는 보관 행(최대 검사 중 1건 + 대기열 1칸). */
    private final List<Held> heldRows = new ArrayList<>();

    /**
     * @param lastSequenceNo 이미 쓴 가장 큰 번호(새 세션은 0)
     */
    public TranscriptWriter(UUID sessionId, int lastSequenceNo, AsyncRecorder recorder, LatencyRecorder latency) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (lastSequenceNo < 0) {
            throw new IllegalArgumentException("lastSequenceNo는 0 이상이다");
        }
        this.lastSeq = lastSequenceNo;
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.latency = Objects.requireNonNull(latency, "latency");
    }

    /** 아이 입력 전사 조각. 처음 보는 항목이면 번호를 정한다. 칸을 받지 못했으면 거짓. */
    public boolean childDelta(String itemId, String delta, Instant at) {
        ChildRow row = childRows.get(key(itemId));
        if (row == null) {
            row = new ChildRow(++lastSeq, at);
            childRows.put(key(itemId), row);
        } else if (row.closed) {
            return true;
        }
        row.text.append(delta == null ? "" : delta);
        return recorder.putTranscript(TranscriptEntry.partial(sessionId, row.seq, Speaker.CHILD,
                TranscriptMarkers.cleanChild(row.text.toString()), row.startedAt));
    }

    /** 아이 입력 전사 확정: FINAL · 승인 true, 즉시 플러시(BR7.5). 정한 번호를 돌려준다. */
    public OptionalInt childFinal(String itemId, String transcript, Instant at) {
        return childFinal(itemId, transcript, at, false);
    }

    /**
     * U3: 아이 입력 전사 확정과 불확실 표시(U3-R10 · AC6.4.7). 전사는 고치지 않고 표시만 단다.
     *
     * @param reviewNeeded 공급자 신뢰도가 기준 미만이거나 확정 전사가 비었는가
     */
    public OptionalInt childFinal(String itemId, String transcript, Instant at, boolean reviewNeeded) {
        ChildRow row = childRows.get(key(itemId));
        if (row == null) {
            row = new ChildRow(++lastSeq, at);
            childRows.put(key(itemId), row);
        }
        if (row.closed) {
            return OptionalInt.of(row.seq);
        }
        row.closed = true;
        // JIRA-262: 비언어 표지 · 앞머리 잡음은 저장 전에 지운다(보호자 표시 · 결과 작업 인용 대조가 같은 글을 본다)
        String text = TranscriptMarkers.cleanChild(transcript != null ? transcript : row.text.toString());
        latency.transcriptFinal(sessionId, row.seq, at);
        boolean accepted = recorder.putTranscript(TranscriptEntry.childFinal(sessionId, row.seq, text, row.startedAt, at,
                reviewNeeded));
        return accepted ? OptionalInt.of(row.seq) : OptionalInt.empty();
    }

    /** 응답의 첫 출력 조각: AI 번호를 정하고 확정 전 칸(approved false)을 넣는다. 이미 열려 있으면 그 번호. */
    public int aiStart(Instant at) {
        if (openAi == null) {
            openAi = new AiRow(++lastSeq, at);
            latency.aiItem(sessionId, openAi.seq);
            recorder.putTranscript(TranscriptEntry.partial(sessionId, openAi.seq, Speaker.AI, "", at));
        }
        return openAi.seq;
    }

    /** Stable transcript row identifier for diagnostics; zero means no row has been allocated. */
    public int diagnosticSequence(Held held) {
        return held != null ? (held.row == null ? 0 : held.row.seq) : (openAi == null ? 0 : openAi.seq);
    }

    /** 열린 AI 행이 있는가. */
    public boolean hasOpenAi() {
        return openAi != null;
    }

    /**
     * 단위별 검사 경로의 출력 단위 시작: 이 응답의 묶음 행이 없으면 번호를 정해 확정 전 칸을 넣고, 있으면 그 행을 다시 연다. 단위가 몇 개로
     * 나뉘어도 응답 하나는 번호 하나다.
     */
    public int aiStartStreaming(Instant at) {
        if (currentStream == null) {
            AiRow row = new AiRow(++lastSeq, at);
            currentStream = new StreamRow(row);
            latency.aiItem(sessionId, row.seq);
            recorder.putTranscript(TranscriptEntry.partial(sessionId, row.seq, Speaker.AI, "", at));
        }
        if (openAi == null) {
            openAi = currentStream.row;
        }
        return currentStream.row.seq;
    }

    /** 모으는 중인 응답 묶음 행이 있는가. */
    public boolean hasStream() {
        return currentStream != null;
    }

    /**
     * 지금 모으는 응답이 끝났다(응답 끝 · 아이 입력 시작 · 응답을 버림). 그 응답의 검사 중인 단위가 없으면 모은 전사를 한 번에 FINAL로 닫고
     * (하나라도 통과했으면 승인 true), 있으면 마지막 단위가 정리될 때 닫는다. 모으는 응답이 없으면 아무것도 하지 않는다.
     *
     * @return 지금 닫았으면 닫은 번호, 아니면 빈 값
     */
    public OptionalInt endStream(Instant endedAt) {
        StreamRow stream = currentStream;
        if (stream == null) {
            return OptionalInt.empty();
        }
        currentStream = null;
        if (openAi == stream.row) {
            openAi = null;
        }
        stream.ended = true;
        stream.endedAt = endedAt;
        endedStreams.add(stream);
        return settle(stream);
    }

    /** 끝난 묶음 행의 검사 중인 단위가 모두 정리됐으면 닫는다. */
    private OptionalInt settle(StreamRow stream) {
        if (!stream.ended || stream.pending > 0 || stream.written) {
            return OptionalInt.empty();
        }
        stream.written = true;
        endedStreams.remove(stream);
        recorder.putTranscript(TranscriptEntry.finalized(sessionId, stream.row.seq, Speaker.AI,
                TranscriptMarkers.cleanAi(stream.text.toString()), stream.approved, stream.row.startedAt, stream.endedAt));
        return OptionalInt.of(stream.row.seq);
    }

    /** 통과한 전사를 묶음 행에 더한다(비었거나 통과가 아니면 아무것도 하지 않는다). */
    private static void collect(StreamRow stream, String text, boolean approved) {
        if (approved && text != null && !text.isEmpty()) {
            stream.text.append(text);
            stream.approved = true;
        }
    }

    /**
     * 열린 AI 행의 응답 끝(output.done)을 처리했다: 그 시각이 행의 tTranscriptFinal이다(OD-8 · 3.6 수정 J300). 열린 행이 없으면 아무것도 하지
     * 않는다. 실행기는 열린 구간이 그 응답일 때만 부른다.
     */
    public void aiDone(Instant at) {
        if (openAi != null) {
            latency.aiTranscriptFinal(sessionId, openAi.seq, at);
        }
    }

    /**
     * 게이트 판정과 함께 AI 행을 닫는다(통과 true · 폐기 false, 한 칸 한 문장, BR7.5).
     *
     * @return 닫은 번호. 열린 행이 없으면 빈 값
     */
    public OptionalInt aiFinal(String text, boolean approved, Instant endedAt) {
        if (openAi == null) {
            return OptionalInt.empty();
        }
        AiRow row = openAi;
        openAi = null;
        if (currentStream != null && row == currentStream.row) {
            // 응답 묶음 행: 통과한 전사는 모으기만 하고, 버리는 닫기는 응답을 끝내는 것이라 모은 것으로 닫는다
            if (approved && text != null && !text.isEmpty()) {
                collect(currentStream, text, true);
                return OptionalInt.of(row.seq);
            }
            return endStream(endedAt);
        }
        recorder.putTranscript(TranscriptEntry.finalized(sessionId, row.seq, Speaker.AI,
                text == null ? "" : TranscriptMarkers.cleanAi(text), approved, row.startedAt, endedAt));
        return OptionalInt.of(row.seq);
    }

    /**
     * U13 BR5.5: 열린 AI 행을 보관 손잡이로 분리한다 — 구간이 제브 검사를 기다리는 동안 행은 DB에서 PARTIAL로 남고, 그 뒤에 도착하는 조각은
     * {@link #aiStart}가 새 행 번호를 만든다. 결과가 나오면 {@link #aiFinal(Held, String, boolean, Instant)}로 한 번에 닫는다. 열린 행이 없으면
     * 닫을 것이 없는 손잡이를 돌려준다(null이 아니다).
     */
    public Held aiHold() {
        StreamRow stream = openAi != null && currentStream != null && openAi == currentStream.row ? currentStream : null;
        Held held = new Held(openAi, stream);
        openAi = null;
        if (stream != null) {
            stream.pending++;
        }
        if (held.row != null) {
            heldRows.add(held);
        }
        return held;
    }

    /**
     * U13 BR5.5: 보관한 AI 행을 닫는다(통과 true · 폐기 false). 열린 AI 행({@link #openAi})은 건드리지 않는다. 이미 닫은 손잡이면 아무것도 하지
     * 않는다.
     *
     * @return 닫은 번호. 닫을 행이 없으면 빈 값
     */
    public OptionalInt aiFinal(Held held, String text, boolean approved, Instant endedAt) {
        Objects.requireNonNull(held, "held");
        if (held.row == null || held.closed) {
            return OptionalInt.empty();
        }
        held.closed = true;
        heldRows.remove(held);
        if (held.stream != null) {
            // 응답 묶음 행의 단위: 통과한 전사만 모으고, 행은 그 응답이 끝나고 검사 중인 단위가 모두 정리되면 닫는다
            StreamRow stream = held.stream;
            stream.pending--;
            collect(stream, text, approved);
            settle(stream);
            return OptionalInt.of(held.row.seq);
        }
        AiRow row = held.row;
        recorder.putTranscript(TranscriptEntry.finalized(sessionId, row.seq, Speaker.AI,
                text == null ? "" : TranscriptMarkers.cleanAi(text), approved, row.startedAt, endedAt));
        return OptionalInt.of(row.seq);
    }

    public void awaitApprovedPlayback(UUID playbackId, String text, Instant at) {
        if (pendingPlayback.containsKey(playbackId)) return;
        AiRow row = new AiRow(++lastSeq, at);
        Held held = new Held(row, null);
        heldRows.add(held);
        recorder.putTranscript(TranscriptEntry.partial(sessionId, row.seq, Speaker.AI, "", at));
        awaitPlayback(playbackId, held, text);
    }

    /** Gate-approved speech remains hidden until the bound device confirms playback. */
    public void awaitPlayback(UUID playbackId, Held held, String text) {
        Held handle = held == null ? aiHold() : held;
        pendingPlayback.put(playbackId, new PendingPlayback(handle, text));
    }

    public void playbackStarted(UUID playbackId, Instant at) {
        PendingPlayback pending = pendingPlayback.remove(playbackId);
        if (pending == null || pending.held.closed || pending.held.row == null) return;
        Held held = pending.held;
        if (held.stream != null) {
            // Streaming units share one response row; settle the unit through its owner.
            aiFinal(held, pending.text, true, at);
            return;
        }
        held.closed = true;
        heldRows.remove(held);
        recorder.putTranscript(TranscriptEntry.finalized(sessionId, held.row.seq, Speaker.AI,
                pending.text, true, at, at));
    }

    /** Never display unplayed text, including send failure or a missing STARTED mark. */
    public void playbackAbandoned(UUID playbackId, Instant at) {
        PendingPlayback pending = pendingPlayback.remove(playbackId);
        if (pending != null) aiFinal(pending.held, "", false, at);
    }

    /** 보관 중인(아직 닫지 않은) AI 행 수. */
    public int heldCount() {
        return heldRows.size();
    }

    /** 종료 · 취소 때 열린 행을 모두 닫는다: 아이 조각은 지금 텍스트로 FINAL · true, AI 행(보관 행 포함)은 FINAL · false(fail-closed). */
    public void closeAll(Instant at) {
        pendingPlayback.clear();
        for (ChildRow row : childRows.values()) {
            if (!row.closed) {
                row.closed = true;
                recorder.putTranscript(TranscriptEntry.finalized(sessionId, row.seq, Speaker.CHILD,
                        TranscriptMarkers.cleanChild(row.text.toString()), true, row.startedAt, at));
            }
        }
        aiFinal("", false, at);
        for (Held held : List.copyOf(heldRows)) {
            aiFinal(held, "", false, at);
        }
        endStream(at);
        for (StreamRow stream : List.copyOf(endedStreams)) {
            stream.pending = 0;
            settle(stream);
        }
    }

    /**
     * U4b BR3.1 · U4b-R14: 기기에 보낸 승인 문구를 보낸 순간 AI 말풍선 한 행으로 남긴다(FINAL · 승인 true · 문구 원문). 대기 문구 · DECLINED 인사는
     * 부르는 쪽이 부르지 않는다.
     *
     * @return 정한 번호(기록기가 칸을 받지 못했으면 빈 값)
     */
    public OptionalInt approvedLine(String text, Instant at) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(at, "at");
        int seq = ++lastSeq;
        boolean accepted = recorder.putTranscript(TranscriptEntry.finalized(sessionId, seq, Speaker.AI, text, true, at,
                at));
        return accepted ? OptionalInt.of(seq) : OptionalInt.empty();
    }

    /**
     * U4b BR1.7: 동의 철회 — 열린(확정 전) 아이 행을 철회 전에 받은 조각 그대로 FINAL로 닫고 이후 같은 항목의 조각 · 확정은 받지 않는다. 철회 뒤
     * 도착한 전사는 실행기가 아예 넘기지 않는다.
     *
     * @return 닫은 아이 행 수
     */
    public int discardOpenChildren(Instant at) {
        int closed = 0;
        for (ChildRow row : childRows.values()) {
            if (!row.closed) {
                row.closed = true;
                closed++;
                recorder.putTranscript(TranscriptEntry.finalized(sessionId, row.seq, Speaker.CHILD,
                        TranscriptMarkers.cleanChild(row.text.toString()), true, row.startedAt, at));
            }
        }
        return closed;
    }

    /** U4b BR8.6: 그 공급자 항목의 아이 발화 시작 시각(첫 조각을 받은 시각, 처음 보는 항목이면 null). */
    public Instant childStartedAt(String itemId) {
        ChildRow row = childRows.get(key(itemId));
        return row == null ? null : row.startedAt;
    }

    /** 마지막으로 정한 번호. */
    public int lastSequenceNo() {
        return lastSeq;
    }

    private static String key(String itemId) {
        return itemId == null ? "" : itemId;
    }

    private static final class ChildRow {
        private final int seq;
        private final Instant startedAt;
        private final StringBuilder text = new StringBuilder();
        private boolean closed;

        private ChildRow(int seq, Instant startedAt) {
            this.seq = seq;
            this.startedAt = startedAt;
        }
    }

    private record AiRow(int seq, Instant startedAt) {
    }

    /** 응답 하나의 묶음 행: 통과한 전사를 모으고, 끝난 뒤 검사 중인 단위({@link #pending})가 없어지면 한 번에 닫는다. */
    private static final class StreamRow {
        private final AiRow row;
        private final StringBuilder text = new StringBuilder();
        private boolean approved;
        private int pending;
        private boolean ended;
        private boolean written;
        private Instant endedAt;

        private StreamRow(AiRow row) {
            this.row = row;
        }
    }

    /** U13 BR5.5: 제브 검사를 기다리는 AI 행의 보관 손잡이(실행기 스레드 전용). 문자열 표현에 번호만 낸다. */
    public static final class Held {
        private final AiRow row;
        /** 응답 묶음 행의 단위면 그 묶음(닫아도 행을 닫지 않고 전사만 모은다), 아니면 null. */
        private final StreamRow stream;
        private boolean closed;

        private Held(AiRow row, StreamRow stream) {
            this.row = row;
            this.stream = stream;
        }

        @Override
        public String toString() {
            return "Held[seq=" + (row == null ? "none" : String.valueOf(row.seq)) + ", closed=" + closed + "]";
        }
    }
}
