package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.log.TranscriptEntry.AssemblyStatus;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 전사 기록 데이터 접근 통합 시험(U1 3.5 Step 18).
 *
 * <p>증명: BR7.5(번호는 세션 안에서 1부터 · 확정 전 조각은 같은 번호로 덮어쓰기 · FINAL 전환과 승인 판정은 한 문장 · FINAL은 닫혀 늦은
 * 조각 · 늦은 승인이 바꾸지 못함) · PD-5(배치는 한 트랜잭션, 되돌리면 하나도 남지 않음, 다시 보내도 같은 결과) · BR6.15(12에는 아이
 * 전사와 승인된 확정 AI 말만, nextSeq용 가장 작은 확정 전 번호) · NFR1.1(확정 전 AI 말은 승인될 수 없음).
 */
@Tag("skeleton")
@Tag("db")
@org.junit.jupiter.api.Tag("service-guardrail")
class TranscriptRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    private static RealtimeDb db;
    private TranscriptRepository transcripts;
    private UUID sessionId;

    @BeforeAll
    static void openDatabase() {
        db = RealtimeDb.open();
    }

    @AfterAll
    static void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        db.wipe();
        transcripts = new TranscriptRepository(db.jdbc());
        sessionId = insertSession();
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void partialFragmentsAreOverwrittenInPlaceAndFinalClosesTheRow() {
        flush(TranscriptEntry.partial(sessionId, 1, Speaker.CHILD, "공룡", T0));
        UUID firstId = transcripts.findAll(sessionId).get(0).id();
        flush(TranscriptEntry.partial(sessionId, 1, Speaker.CHILD, "공룡이", T0.plusSeconds(1)));
        flush(TranscriptEntry.finalized(sessionId, 1, Speaker.CHILD, "공룡이 좋아", true, T0.plusSeconds(1),
                T0.plusSeconds(2)));
        // 확정 뒤 늦게 온 조각은 행을 다시 열지 못한다
        flush(TranscriptEntry.partial(sessionId, 1, Speaker.CHILD, "늦은 조각", T0.plusSeconds(3)));

        List<TranscriptRow> rows = transcripts.findAll(sessionId);
        assertThat(rows).hasSize(1);
        TranscriptRow row = rows.get(0);
        assertThat(row.id()).isEqualTo(firstId);
        assertThat(row.text()).isEqualTo("공룡이 좋아");
        assertThat(row.assemblyStatus()).isEqualTo(AssemblyStatus.FINAL);
        assertThat(row.isFinal()).isTrue();
        assertThat(row.approvedForDisplay()).isTrue();
        // 처음 시작 시각과 확정 끝 시각을 남긴다
        assertThat(row.startedAt()).isEqualTo(T0);
        assertThat(row.endedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.reviewNeeded()).isFalse();
        assertThat(row.deletedAt()).isNull();
        assertThat(row.toString()).doesNotContain("공룡");
    }

    @Test
    void anAiSegmentIsApprovedOnlyTogetherWithItsFinalRowAndADiscardedOneStaysHidden() {
        flush(TranscriptEntry.finalized(sessionId, 1, Speaker.CHILD, "안녕", true, T0, T0.plusSeconds(1)));
        // AI 행 번호는 첫 delta에서 정해지고 확정 전에는 승인되지 않은 채 칸에 있다
        flush(TranscriptEntry.partial(sessionId, 2, Speaker.AI, "반가", T0.plusSeconds(2)),
                TranscriptEntry.partial(sessionId, 3, Speaker.AI, "금지", T0.plusSeconds(4)),
                TranscriptEntry.partial(sessionId, 4, Speaker.CHILD, "나는", T0.plusSeconds(6)));

        assertThat(transcripts.findVisibleAfter(sessionId, 0)).extracting(TranscriptRow::sequenceNo).containsExactly(1, 4);
        assertThat(transcripts.lowestPartialAfter(sessionId, 0)).contains(2);

        // 통과(true)와 폐기(false)는 FINAL 전환과 같은 문장
        flush(TranscriptEntry.finalized(sessionId, 2, Speaker.AI, "반가워", true, null, T0.plusSeconds(3)),
                TranscriptEntry.finalized(sessionId, 3, Speaker.AI, "금지 문장", false, null, T0.plusSeconds(5)));
        // 폐기된 구간은 뒤늦게 승인되지 않는다
        flush(TranscriptEntry.finalized(sessionId, 3, Speaker.AI, "금지 문장", true, null, T0.plusSeconds(6)));

        assertThat(transcripts.findVisibleAfter(sessionId, 0)).extracting(TranscriptRow::sequenceNo)
                .containsExactly(1, 2, 4);
        assertThat(transcripts.findVisibleAfter(sessionId, 2)).extracting(TranscriptRow::sequenceNo).containsExactly(4);
        assertThat(transcripts.lowestPartialAfter(sessionId, 0)).contains(4);
        TranscriptRow discarded = transcripts.findAll(sessionId).get(2);
        assertThat(discarded.speaker()).isEqualTo(Speaker.AI);
        assertThat(discarded.approvedForDisplay()).isFalse();
        assertThat(discarded.isFinal()).isTrue();

        flush(TranscriptEntry.finalized(sessionId, 4, Speaker.CHILD, "나는 하늘", true, null, T0.plusSeconds(7)));
        assertThat(transcripts.lowestPartialAfter(sessionId, 0)).isEmpty();
        assertThat(transcripts.findVisibleAfter(sessionId, 4)).isEmpty();
    }

    @Test
    void aFlushIsOneTransactionAndAReplayGivesTheSameRows() {
        List<TranscriptEntry> batch = List.of(
                TranscriptEntry.finalized(sessionId, 3, Speaker.CHILD, "셋", true, null, null),
                TranscriptEntry.finalized(sessionId, 1, Speaker.CHILD, "하나", true, null, null),
                TranscriptEntry.partial(sessionId, 2, Speaker.AI, "둘", null));

        db.rolledBack(() -> transcripts.upsertAll(batch, T0));
        assertThat(transcripts.findAll(sessionId)).isEmpty();
        assertThatThrownBy(() -> transcripts.upsertAll(batch, T0)).isInstanceOf(IllegalTransactionStateException.class);

        db.inTransaction(() -> transcripts.upsertAll(batch, T0));
        // 커밋 실패 뒤 같은 칸을 다시 보내도(PD-5 4) 행 수 · 값이 같다
        db.inTransaction(() -> transcripts.upsertAll(batch, T0.plusSeconds(1)));
        db.inTransaction(() -> transcripts.upsertAll(List.of(), T0));

        List<TranscriptRow> rows = transcripts.findAll(sessionId);
        assertThat(rows).extracting(TranscriptRow::sequenceNo).containsExactly(1, 2, 3);
        assertThat(rows).extracting(TranscriptRow::text).containsExactly("하나", "둘", "셋");
        assertThat(rows).extracting(TranscriptRow::createdAt).containsOnly(T0);
    }

    @Test
    void sequenceNumbersStartAtOneAndTheNextNumberFollowsTheLargest() {
        assertThat(transcripts.maxSequenceNo(sessionId)).isZero();
        assertThatThrownBy(() -> TranscriptEntry.partial(sessionId, 0, Speaker.CHILD, "영", T0))
                .isInstanceOf(IllegalArgumentException.class);

        flush(TranscriptEntry.partial(sessionId, 1, Speaker.CHILD, "하나", T0),
                TranscriptEntry.partial(sessionId, 2, Speaker.AI, "둘", T0));

        assertThat(transcripts.maxSequenceNo(sessionId)).isEqualTo(2);
        assertThat(transcripts.maxSequenceNo(UUID.randomUUID())).isZero();
        assertThatThrownBy(() -> transcripts.findVisibleAfter(sessionId, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entriesThatWouldLeakOrCorruptTheTranscriptAreRejected() {
        // 확정 전 AI 말은 승인될 수 없다(NFR1.1 · BR7.4)
        assertThatThrownBy(() -> new TranscriptEntry(sessionId, 1, Speaker.AI, "미확정", AssemblyStatus.PARTIAL, true,
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TranscriptEntry(sessionId, 1, Speaker.CHILD, null, AssemblyStatus.FINAL, true, null,
                null)).isInstanceOf(NullPointerException.class);

        // 같은 번호에 다른 화자가 오면(조립 결함) 덮지 않는다
        flush(TranscriptEntry.partial(sessionId, 1, Speaker.CHILD, "아이 말", T0));
        flush(TranscriptEntry.partial(sessionId, 1, Speaker.AI, "AI 말", T0));
        assertThat(transcripts.findAll(sessionId).get(0).speaker()).isEqualTo(Speaker.CHILD);

        // 세션 행이 없으면 외래 키가 막는다
        assertThatThrownBy(() -> db.inTransaction(() -> transcripts.upsertAll(
                List.of(TranscriptEntry.partial(UUID.randomUUID(), 1, Speaker.CHILD, "고아", T0)), T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(TranscriptEntry.partial(sessionId, 1, Speaker.CHILD, "비밀 문장", T0).toString())
                .doesNotContain("비밀 문장");
    }

    private void flush(TranscriptEntry... entries) {
        db.inTransaction(() -> transcripts.upsertAll(List.of(entries), T0));
    }

    private static UUID insertSession() {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("하늘", 8, List.of("공룡")), null,
                null);
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        return session.id();
    }
}
