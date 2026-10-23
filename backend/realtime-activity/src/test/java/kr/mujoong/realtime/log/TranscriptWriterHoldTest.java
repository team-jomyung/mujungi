package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AI 행 보관 손잡이 시험 (U13 BR5.5): 제브 검사를 기다리는 구간의 행은 열린 행에서 분리되어 PARTIAL로 남고, 결과에 따라 한 번에 닫힌다. 보관 행은
 * 새 구간의 행과 섞이지 않고 종료 때 FINAL · 승인 거짓으로 닫힌다.
 */
@Tag("jev-guardrail")
@org.junit.jupiter.api.Tag("service-guardrail")
class TranscriptWriterHoldTest {

    private final SessionHarness h = new SessionHarness();
    private final UUID sessionId = UUID.randomUUID();
    private final TranscriptWriter writer = new TranscriptWriter(sessionId, 0, h.recorder, h.latency);
    private final Instant t0 = h.clock.now();

    @Test
    void streamedPlaybackSettlesEveryUnitWithoutLosingEarlierPlayedText() {
        int ai = writer.aiStartStreaming(t0);
        UUID first = UUID.randomUUID();
        writer.awaitPlayback(first, writer.aiHold(), "첫 문장.");
        writer.aiStartStreaming(t0.plusSeconds(1));
        UUID second = UUID.randomUUID();
        writer.awaitPlayback(second, writer.aiHold(), "다음 문장.");
        writer.endStream(t0.plusSeconds(2));
        writer.playbackStarted(first, t0.plusSeconds(3));
        assertThat(row(ai).approvedForDisplay()).isFalse();
        writer.playbackStarted(second, t0.plusSeconds(4));
        assertThat(row(ai).text()).contains("첫 문장.", "다음 문장.");
        assertThat(row(ai).approvedForDisplay()).isTrue();
        assertThat(writer.heldCount()).isZero();
    }

    @Test
    void displayWaitsForPlaybackAndKeepsInputSequenceStable() {
        int ai = writer.aiStart(t0);
        UUID playback = UUID.randomUUID();
        writer.awaitPlayback(playback, null, "지난번 이야기를 떠올려 볼까?");
        writer.childFinal("child", "그래 알았어", t0.plusSeconds(3));
        assertThat(row(ai).approvedForDisplay()).isFalse();
        writer.playbackStarted(playback, t0.plusSeconds(5));
        assertThat(row(ai).startedAt()).isEqualTo(t0.plusSeconds(5));
        assertThat(row(ai).approvedForDisplay()).isTrue();
        assertThat(row(ai + 1).startedAt()).isEqualTo(t0.plusSeconds(3));
        writer.playbackStarted(playback, t0.plusSeconds(9));
        assertThat(row(ai).startedAt()).isEqualTo(t0.plusSeconds(5));
    }

    @Test
    void unplayedAndStaleAudioNeverBecomesVisible() {
        int ai = writer.aiStart(t0);
        UUID playback = UUID.randomUUID();
        writer.awaitPlayback(playback, null, "재생되지 않은 말");
        writer.playbackAbandoned(playback, t0.plusSeconds(1));
        writer.playbackStarted(playback, t0.plusSeconds(2));
        assertThat(row(ai).approvedForDisplay()).isFalse();
        assertThat(row(ai).text()).isEmpty();
        int second = writer.aiStart(t0.plusSeconds(3));
        UUID next = UUID.randomUUID();
        writer.awaitPlayback(next, null, "종료 후 말");
        writer.closeAll(t0.plusSeconds(4));
        writer.playbackStarted(next, t0.plusSeconds(5));
        assertThat(row(second).approvedForDisplay()).isFalse();
    }

    private TranscriptEntry row(int seq) {
        h.recorder.tick();
        return h.store.row(seq).orElseThrow();
    }

    @Test
    void holdingTheOpenRowFreesTheWriterToOpenANewRowWithANewNumber() {
        int first = writer.aiStart(t0);

        TranscriptWriter.Held held = writer.aiHold();

        assertThat(writer.hasOpenAi()).isFalse();
        assertThat(writer.heldCount()).isEqualTo(1);
        int second = writer.aiStart(t0.plusSeconds(1));
        assertThat(second).isEqualTo(first + 1);
        assertThat(held.toString()).contains("seq=" + first);
    }

    @Test
    void closingTheHeldRowApprovedWritesThatRowOnlyAndLeavesTheNewOpenRowPartial() {
        int first = writer.aiStart(t0);
        TranscriptWriter.Held held = writer.aiHold();
        int second = writer.aiStart(t0.plusSeconds(1));

        var closed = writer.aiFinal(held, "공룡을 좋아하는구나", true, t0.plusSeconds(2));

        assertThat(closed).hasValue(first);
        TranscriptEntry firstRow = row(first);
        assertThat(firstRow.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(firstRow.approvedForDisplay()).isTrue();
        assertThat(firstRow.text()).isEqualTo("공룡을 좋아하는구나");
        assertThat(row(second).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.PARTIAL);
        assertThat(writer.hasOpenAi()).isTrue();
        assertThat(writer.heldCount()).isZero();
    }

    @Test
    void closingTheHeldRowNotApprovedKeepsItOutOfTheGuardianView() {
        int first = writer.aiStart(t0);
        TranscriptWriter.Held held = writer.aiHold();

        writer.aiFinal(held, "막힌 문장", false, t0.plusSeconds(1));

        TranscriptEntry firstRow = row(first);
        assertThat(firstRow.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(firstRow.approvedForDisplay()).isFalse();
    }

    @Test
    void aHeldHandleClosesOnlyOnceAndALaterCloseCannotApproveAClosedRow() {
        int first = writer.aiStart(t0);
        TranscriptWriter.Held held = writer.aiHold();
        writer.aiFinal(held, "막힌 문장", false, t0.plusSeconds(1));

        var again = writer.aiFinal(held, "다시 승인", true, t0.plusSeconds(2));

        assertThat(again).isEmpty();
        assertThat(row(first).approvedForDisplay()).isFalse();
        assertThat(row(first).text()).isEqualTo("막힌 문장");
    }

    @Test
    void closeAllClosesBothTheOpenRowAndEveryHeldRowAsNotApproved() {
        int first = writer.aiStart(t0);
        TranscriptWriter.Held held1 = writer.aiHold();
        int second = writer.aiStart(t0.plusSeconds(1));
        TranscriptWriter.Held held2 = writer.aiHold();
        int third = writer.aiStart(t0.plusSeconds(2));
        assertThat(writer.heldCount()).isEqualTo(2);

        writer.closeAll(t0.plusSeconds(3));

        for (int seq : new int[] {first, second, third}) {
            assertThat(row(seq).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
            assertThat(row(seq).approvedForDisplay()).isFalse();
        }
        assertThat(writer.heldCount()).isZero();
        assertThat(writer.aiFinal(held1, "x", true, t0.plus(Duration.ofSeconds(4)))).isEmpty();
        assertThat(writer.aiFinal(held2, "x", true, t0.plus(Duration.ofSeconds(4)))).isEmpty();
    }

    @Test
    void holdingWithoutAnOpenRowGivesAnInertHandle() {
        TranscriptWriter.Held held = writer.aiHold();

        assertThat(held).isNotNull();
        assertThat(writer.heldCount()).isZero();
        assertThat(writer.aiFinal(held, "x", true, t0)).isEmpty();
    }

    @Test
    void theOrdinaryCloseNeverTouchesAHeldRow() {
        int first = writer.aiStart(t0);
        writer.aiHold();
        int second = writer.aiStart(t0.plusSeconds(1));

        writer.aiFinal("두 번째 문장", true, t0.plusSeconds(2));

        assertThat(row(second).approvedForDisplay()).isTrue();
        assertThat(row(first).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.PARTIAL);
    }
}
