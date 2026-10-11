package kr.mujoong.realtime.log;

import java.time.Instant;
import java.util.HashMap;
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
 * 첫 출력 조각 때 번호를 정해 확정 전 칸(approved false, 텍스트 비움)을 넣고, 게이트 판정과 함께 FINAL · 통과 여부로 한 번에 닫는다 —
 * 폐기한 구간도 FINAL · false로 닫아야 12의 nextSeq가 풀린다(BR6.15). 게이트를 통과하기 전의 AI 텍스트는 DB에 쓰지 않는다(NFR1.1).
 */
public final class TranscriptWriter {

    private final UUID sessionId;
    private final AsyncRecorder recorder;
    private final LatencyRecorder latency;
    private final Map<String, ChildRow> childRows = new HashMap<>();
    private int lastSeq;
    private AiRow openAi;

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
        return recorder.putTranscript(TranscriptEntry.partial(sessionId, row.seq, Speaker.CHILD, row.text.toString(),
                row.startedAt));
    }

    /** 아이 입력 전사 확정: FINAL · 승인 true, 즉시 플러시(BR7.5). 정한 번호를 돌려준다. */
    public OptionalInt childFinal(String itemId, String transcript, Instant at) {
        ChildRow row = childRows.get(key(itemId));
        if (row == null) {
            row = new ChildRow(++lastSeq, at);
            childRows.put(key(itemId), row);
        }
        if (row.closed) {
            return OptionalInt.of(row.seq);
        }
        row.closed = true;
        String text = transcript != null ? transcript : row.text.toString();
        latency.transcriptFinal(sessionId, row.seq, at);
        boolean accepted = recorder.putTranscript(TranscriptEntry.finalized(sessionId, row.seq, Speaker.CHILD, text,
                true, row.startedAt, at));
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

    /** 열린 AI 행이 있는가. */
    public boolean hasOpenAi() {
        return openAi != null;
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
        recorder.putTranscript(TranscriptEntry.finalized(sessionId, row.seq, Speaker.AI, text == null ? "" : text,
                approved, row.startedAt, endedAt));
        return OptionalInt.of(row.seq);
    }

    /** 종료 · 취소 때 열린 행을 모두 닫는다: 아이 조각은 지금 텍스트로 FINAL · true, AI 행은 FINAL · false(fail-closed). */
    public void closeAll(Instant at) {
        for (ChildRow row : childRows.values()) {
            if (!row.closed) {
                row.closed = true;
                recorder.putTranscript(TranscriptEntry.finalized(sessionId, row.seq, Speaker.CHILD, row.text.toString(),
                        true, row.startedAt, at));
            }
        }
        aiFinal("", false, at);
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
}
