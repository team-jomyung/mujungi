package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.log.TranscriptRow;
import org.springframework.stereotype.Component;

/**
 * 내부 진행 조회 (U1 3.5 Step 33 · SQ-10 · PD-6 · OD-8 tViewItemFirstServed).
 *
 * <p>{@code GET /internal/sessions/{id}/view?sinceSeq}의 값을 Realtime 원본(세션 행 · 전사)에서 읽는다:
 *
 * <ul>
 *   <li>세션 요약(상태 · 사유 · 실패 코드 · confirmed · 시각)과 statusVersion · serverTime. requestedAt은 Realtime이 시작 명령을 받아 행을
 *       만든 시각이다. observedAt · stale은 Core가 채운다.
 * <li>items: {@code seq > sinceSeq}인 아이 전사와 확정(FINAL) · 승인된 AI 말만(게이트가 폐기한 구간 · 확정 전 AI 말은 없다).
 *   <li>nextSeq: items의 최대 seq(비면 sinceSeq). 단 확정 전(PARTIAL) 행이 있으면 그중 가장 작은 seq − 1을 넘지 않는다 — 확정 전 조각은
 * 같은 seq로 다시 온다.
 * </ul>
 *
 * <p>확정 행이 처음 응답에 실리면 {@link LatencyRecorder#itemsServed}로 SESSION_ITEMS_SERVED 한 줄을 남긴다(OD-8). 판정값 · 08 원문은
 * 이 응답에 들어갈 자리가 없다. U3: 세션의 끊김 구간({@code gaps}, 시작 순)을 함께 읽는다(AC3.2.10(b)).
 */
@Component
public class SessionViewQuery {

    /** 말풍선 한 개(계약 Bubble). */
    public record Item(int seq, Speaker speaker, String text, boolean isFinal, boolean uncertain, Instant startedAt,
            Instant endedAt) {

        /** 전사 원문은 문자열 표현에 넣지 않는다. */
        @Override
        public String toString() {
            return "Item[seq=" + seq + ", speaker=" + speaker + ", final=" + isFinal + "]";
        }
    }

    /**
     * 조회 결과. {@code gaps}는 끊김 · 미확인 구간(시작 순, U3 · U4b). {@code live}는 살아 있는 실행기의 기술 일시정지 · 공백 경고이고 종료 세션 ·
     * 실행기 없는 세션은 둘 다 null이다.
     */
    public record View(ActivitySessionRow session, Instant serverTime, List<Item> items, int nextSeq,
            List<SessionGapRepository.SessionGapRow> gaps, IdleClock.LiveState live) {

        public View {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(serverTime, "serverTime");
            items = List.copyOf(items);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
            live = live == null || session.status().isTerminal() ? IdleClock.LiveState.NONE : live;
        }

        /** U3 모양(살아 있는 값 없음). */
        public View(ActivitySessionRow session, Instant serverTime, List<Item> items, int nextSeq,
                List<SessionGapRepository.SessionGapRow> gaps) {
            this(session, serverTime, items, nextSeq, gaps, IdleClock.LiveState.NONE);
        }
    }

    private final ActivitySessionRepository sessions;
    private final TranscriptRepository transcripts;
    private final SessionGapRepository gaps;
    private final LatencyRecorder latency;
    private final MujoongClock clock;
    private final SessionRegistry registry;

    public SessionViewQuery(ActivitySessionRepository sessions, TranscriptRepository transcripts,
            SessionGapRepository gaps, LatencyRecorder latency, MujoongClock clock, SessionRegistry registry) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.gaps = Objects.requireNonNull(gaps, "gaps");
        this.latency = Objects.requireNonNull(latency, "latency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 조회한다. Realtime이 모르는 세션이면 빈 값(404).
     *
     * @param sinceSeq 0 이상
     */
    public Optional<View> view(UUID sessionId, int sinceSeq) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sinceSeq < 0) {
            throw new IllegalArgumentException("sinceSeq는 0 이상이어야 한다");
        }
        Optional<ActivitySessionRow> row = sessions.find(sessionId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        List<Item> items = new ArrayList<>();
        for (TranscriptRow transcript : transcripts.findVisibleAfter(sessionId, sinceSeq)) {
            items.add(item(transcript));
        }
        int nextSeq = nextSeq(items, sinceSeq, transcripts.lowestPartialAfter(sessionId, sinceSeq));
        Instant now = clock.now();
        List<LatencyRecorder.ServedItem> served = items.stream().filter(Item::isFinal)
                .map(item -> new LatencyRecorder.ServedItem(item.seq(), item.speaker())).toList();
        if (!served.isEmpty()) {
            latency.itemsServed(sessionId, served, now);
        }
        // 실행기가 바꿔 쓴 불변 값을 읽기만 한다(종료 세션은 View가 null로 둔다)
        IdleClock.LiveState live = registry.executor(sessionId).map(SessionExecutor::liveState)
                .orElse(IdleClock.LiveState.NONE);
        return Optional.of(new View(row.get(), now, items, nextSeq, gaps.findBySession(sessionId), live));
    }

    /** nextSeq 규칙. */
    static int nextSeq(List<Item> items, int sinceSeq, Optional<Integer> lowestPartial) {
        int next = items.isEmpty() ? sinceSeq : items.getLast().seq();
        if (lowestPartial.isPresent()) {
            next = Math.min(next, lowestPartial.get() - 1);
        }
        return Math.max(sinceSeq, next);
    }

    private static Item item(TranscriptRow row) {
        Instant startedAt = row.startedAt() != null ? row.startedAt() : row.createdAt();
        return new Item(row.sequenceNo(), row.speaker(), row.text() == null ? "" : row.text(), row.isFinal(),
                row.reviewNeeded(), startedAt, row.endedAt());
    }
}
