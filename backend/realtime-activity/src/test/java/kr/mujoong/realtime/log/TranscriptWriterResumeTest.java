package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.time.Instant;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 조용함으로 끝난 응답의 늦은 조각 시험: 공급자는 응답 끝 사건이 없어 조용 시간이 지나면 끝으로 보지만, 같은 응답의 마지막 조각이 늦게 오면 새
 * 행이 아니라 앞 행에 이어 붙는다. 이어 붙일 수 있는 동안 행은 확정 전이고, 만기 · 아이 입력 · 다른 AI 행 · 종료 때 닫힌다.
 */
@Tag("service-guardrail")
class TranscriptWriterResumeTest {

    private final SessionHarness h = new SessionHarness();
    private final UUID sessionId = UUID.randomUUID();
    private final TranscriptWriter writer = new TranscriptWriter(sessionId, 0, h.recorder, h.latency);
    private final Instant t0 = h.clock.now();

    private void passUnit(String text) {
        writer.aiFinal(writer.aiHold(), text, true, t0);
    }

    @Test
    void lateUnitAfterQuietEndJoinsTheSameRow() {
        int ai = writer.aiStartStreaming(t0);
        passUnit("자라서 엄마 아빠가 된 거");
        writer.pauseStream(t0.plusSeconds(6));
        assertThat(row(ai).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.PARTIAL);

        assertThat(writer.aiStartStreaming(t0.plusSeconds(7))).isEqualTo(ai);
        passUnit("야");
        writer.pauseStream(t0.plusSeconds(8));
        writer.closeResumable();

        assertThat(row(ai).text()).isEqualTo("자라서 엄마 아빠가 된 거야");
        assertThat(row(ai).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(row(ai).approvedForDisplay()).isTrue();
        assertThat(row(ai).startedAt()).isEqualTo(t0);
        assertThat(row(ai).endedAt()).isEqualTo(t0.plusSeconds(8));
        assertThat(writer.lastSequenceNo()).isEqualTo(ai);
    }

    @Test
    void expiryClosesThePausedRowAndTheNextResponseGetsANewRow() {
        int ai = writer.aiStartStreaming(t0);
        passUnit("첫 응답이야.");
        writer.pauseStream(t0.plusSeconds(1));

        assertThat(writer.closeResumable()).hasValue(ai);
        assertThat(row(ai).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(writer.aiStartStreaming(t0.plusSeconds(2))).isEqualTo(ai + 1);
    }

    @Test
    void childInputClosesThePausedRowBeforeTakingItsNumber() {
        int ai = writer.aiStartStreaming(t0);
        passUnit("무엇을 먹었어?");
        writer.pauseStream(t0.plusSeconds(1));

        writer.childDelta("child", "돼지고기", t0.plusSeconds(2));

        assertThat(row(ai).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(writer.hasResumable()).isFalse();
        assertThat(writer.aiStartStreaming(t0.plusSeconds(3))).isEqualTo(ai + 2);
    }

    @Test
    void anotherKindOfAiRowClosesThePausedRow() {
        int ai = writer.aiStartStreaming(t0);
        passUnit("좋아.");
        writer.pauseStream(t0.plusSeconds(1));

        writer.approvedLine("오늘 이야기 즐거웠어.", t0.plusSeconds(2));

        assertThat(row(ai).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(row(ai).text()).isEqualTo("좋아.");
    }

    @Test
    void unitStillUnderCheckKeepsTheRowOpenUntilItSettles() {
        int ai = writer.aiStartStreaming(t0);
        TranscriptWriter.Held checking = writer.aiHold();
        writer.pauseStream(t0.plusSeconds(1));
        writer.closeResumable();
        assertThat(row(ai).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.PARTIAL);

        writer.aiFinal(checking, "검사를 통과한 말.", true, t0.plusSeconds(2));

        assertThat(row(ai).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(row(ai).text()).isEqualTo("검사를 통과한 말.");
    }

    @Test
    void closeAllClosesThePausedRow() {
        int ai = writer.aiStartStreaming(t0);
        passUnit("그만할까?");
        writer.pauseStream(t0.plusSeconds(1));

        writer.closeAll(t0.plusSeconds(2));

        assertThat(row(ai).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(row(ai).text()).isEqualTo("그만할까?");
        assertThat(row(ai).approvedForDisplay()).isTrue();
    }

    private TranscriptEntry row(int seq) {
        h.recorder.tick();
        return h.store.row(seq).orElseThrow();
    }
}
