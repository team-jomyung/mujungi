package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.realtime.gate.OutputGeneration.AbortAction;
import kr.mujoong.realtime.gate.OutputGeneration.BumpReason;
import kr.mujoong.realtime.gate.OutputGeneration.ChunkResult;
import kr.mujoong.realtime.gate.OutputGeneration.ChunkVerdict;
import kr.mujoong.realtime.gate.OutputGeneration.Phase;
import kr.mujoong.realtime.gate.OutputGeneration.QuietResult;
import kr.mujoong.realtime.gate.OutputGeneration.QuietVerdict;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 출력 세대 협력 객체 시험 (U11 FR12.5 · BR11.5 ~ BR11.7 · BR11.13 · 기능 설계 2.1 표). 시각은 시험이 정하는 값이고 시간 값은 생성자로 준다.
 */
@Tag("u11")
class OutputGenerationTest {

    private static final Instant T0 = Instant.parse("2026-10-17T00:00:00Z");
    private static final Duration QUIET = Duration.ofMillis(800);
    private static final Duration AUTO = Duration.ofMillis(1500);

    private final OutputGeneration gen = new OutputGeneration(QUIET, AUTO);

    private static Instant at(long ms) {
        return T0.plusMillis(ms);
    }

    @Test
    void receivingAcceptsChunksAndSealsCompleteWhenQuiet() {
        ChunkResult first = gen.onChunk(at(0), false);
        ChunkResult second = gen.onChunk(at(100), false);

        assertThat(first.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(first.fromStart()).as("처음 조각").isTrue();
        assertThat(second.fromStart()).isFalse();
        assertThat(gen.onQuiet(at(500)).verdict()).as("아직 조용하지 않다").isEqualTo(QuietVerdict.NOT_YET);
        QuietResult sealed = gen.onQuiet(at(900));
        assertThat(sealed.verdict()).isEqualTo(QuietVerdict.SEAL_RECEIVING);
        assertThat(gen.onChunk(at(1000), false).fromStart()).as("끊김 뒤 첫 조각은 새 구간의 시작").isTrue();
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);
    }

    @Test
    void heldChunksAreDiscardedInEveryPhaseAndOnlyTheLastChunkTimeMoves() {
        ChunkResult held = gen.onChunk(at(0), true);
        assertThat(held.verdict()).isEqualTo(ChunkVerdict.DISCARD_HELD);
        assertThat(gen.lastDeltaAt()).isEqualTo(at(0));
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);

        gen.bump(at(10), BumpReason.BARGE_IN);
        assertThat(gen.onChunk(at(20), true).verdict()).isEqualTo(ChunkVerdict.DISCARD_HELD);
        assertThat(gen.phase()).isEqualTo(Phase.DISCARDING);
        assertThat(gen.lastDeltaAt()).isEqualTo(at(20));
    }

    @Test
    void bumpingEntersDiscardingAndEveryChunkIsDroppedUntilAGapIsConfirmed() {
        long before = gen.generation();

        long after = gen.bump(at(0), BumpReason.STOP_INTENT);

        assertThat(after).isEqualTo(before + 1);
        assertThat(gen.phase()).isEqualTo(Phase.DISCARDING);
        assertThat(gen.onChunk(at(100), false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.onChunk(at(200), false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.published().generation()).isEqualTo(after);
    }

    @Test
    void theGapNeedsQuietFromTheLaterOfTheLastChunkAndTheBump() {
        gen.onChunk(at(0), false);
        gen.bump(at(400), BumpReason.BARGE_IN);
        gen.onChunk(at(500), false);

        QuietResult tooEarly = gen.onQuiet(at(1200));
        QuietResult enough = gen.onQuiet(at(1300));

        assertThat(tooEarly.verdict()).isEqualTo(QuietVerdict.NOT_YET);
        assertThat(tooEarly.waitUntil()).as("마지막 조각 500 + 800").isEqualTo(at(1300));
        assertThat(enough.verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
        assertThat(enough.permitReady()).as("송신 대기가 없다").isFalse();
        assertThat(gen.phase()).isEqualTo(Phase.GAP_CONFIRMED);
    }

    @Test
    void withoutAChunkSinceTheChildsSpeechEndTheAutoResponseStartWaitApplies() {
        gen.onChunk(at(0), false);
        gen.onSpeechEnd(at(1000));
        gen.bumpForSend(at(1010), BumpReason.SERVER_SEND);

        QuietResult waiting = gen.onQuiet(at(1900));
        QuietResult confirmed = gen.onQuiet(at(2500));

        assertThat(waiting.verdict()).isEqualTo(QuietVerdict.WAIT_AUTO_RESPONSE);
        assertThat(waiting.waitUntil()).as("말 끝 1000 + 자동 응답 시작 대기 1500").isEqualTo(at(2500));
        assertThat(confirmed.verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
        assertThat(confirmed.permitReady()).isTrue();
    }

    @Test
    void aChunkAfterTheSpeechEndMeansTheAutoResponseAlreadyStartedSoQuietIsEnough() {
        gen.onSpeechEnd(at(1000));
        gen.onChunk(at(1200), false);
        gen.bumpForSend(at(1300), BumpReason.SERVER_SEND);

        QuietResult confirmed = gen.onQuiet(at(2100));

        assertThat(confirmed.verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
        assertThat(confirmed.permitReady()).isTrue();
    }

    @Test
    void afterAGapWithNothingToSendTheNextChunkStartsAFreshSegment() {
        gen.bump(at(0), BumpReason.BARGE_IN);
        gen.onQuiet(at(800));

        ChunkResult next = gen.onChunk(at(900), false);

        assertThat(next.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(next.fromStart()).isTrue();
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);
    }

    @Test
    void aChunkBetweenThePermitAndTheSentMarkVoidsThePermitAndAWaitStartsOver() {
        gen.bumpForSend(at(0), BumpReason.SERVER_SEND);
        QuietResult first = gen.onQuiet(at(800));
        assertThat(first.permitReady()).isTrue();
        long firstPermit = first.permitSeq();

        ChunkResult late = gen.onChunk(at(850), false);

        assertThat(late.verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(late.permitInvalidated()).isTrue();
        assertThat(gen.permitSeq()).isGreaterThan(firstPermit);
        assertThat(gen.published().permitSeq()).isEqualTo(gen.permitSeq());
        assertThat(gen.phase()).isEqualTo(Phase.DISCARDING);
        assertThat(gen.onQuiet(at(1000)).verdict()).as("새 조각으로부터 800ms가 안 찼다").isEqualTo(QuietVerdict.NOT_YET);
        QuietResult second = gen.onQuiet(at(1650));
        assertThat(second.verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
        assertThat(second.permitReady()).isTrue();
        assertThat(second.permitSeq()).isGreaterThan(gen.permitSeq() - 1);
    }

    @Test
    void theSentMarkLetsTheNextChunkIntoTheInstructedResponseSegment() {
        gen.bumpForSend(at(0), BumpReason.SERVER_SEND);
        QuietResult permit = gen.onQuiet(at(800));

        assertThat(gen.acceptInstructionSent(permit.generation(), permit.permitSeq())).isTrue();
        assertThat(gen.sendProcessed()).isTrue();
        ChunkResult response = gen.onChunk(at(900), false);

        assertThat(response.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(response.fromStart()).isTrue();
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);
        assertThat(gen.pendingSend()).isFalse();
    }

    @Test
    void aStalePermitSentMarkIsStillAcceptedButDoesNotReviveDiscardedChunks() {
        gen.bumpForSend(at(0), BumpReason.SERVER_SEND);
        QuietResult permit = gen.onQuiet(at(800));
        gen.onChunk(at(850), false);

        boolean accepted = gen.acceptInstructionSent(permit.generation(), permit.permitSeq());

        assertThat(accepted).as("송신은 이미 일어났다").isTrue();
        assertThat(gen.onChunk(at(900), false).verdict()).as("다음 조각부터 받는다").isEqualTo(ChunkVerdict.ACCEPT);
    }

    @Test
    void aSentMarkOfAnOlderGenerationIsIgnored() {
        gen.bumpForSend(at(0), BumpReason.SERVER_SEND);
        QuietResult permit = gen.onQuiet(at(800));
        gen.bump(at(850), BumpReason.BARGE_IN);

        assertThat(gen.acceptInstructionSent(permit.generation(), permit.permitSeq())).isFalse();
        assertThat(gen.phase()).isEqualTo(Phase.DISCARDING);
        assertThat(gen.onSendAborted(permit.generation(), permit.permitSeq())).isEqualTo(AbortAction.STALE_GENERATION);
    }

    @Test
    void aSameGenerationAbortAsksForANewPermit() {
        long generation = gen.bumpForSend(at(0), BumpReason.SERVER_SEND);
        gen.onQuiet(at(800));
        gen.onChunk(at(850), false);

        assertThat(gen.onSendAborted(generation, 1)).isEqualTo(AbortAction.RE_PERMIT);
        assertThat(gen.pendingSend()).isTrue();
    }

    @Test
    void cancellingThePendingSendReportsWhetherOneWasWaiting() {
        gen.bumpForSend(at(0), BumpReason.SERVER_SEND);
        assertThat(gen.pendingSend()).isTrue();

        assertThat(gen.cancelPendingSend()).isTrue();
        assertThat(gen.pendingSend()).isFalse();
        assertThat(gen.cancelPendingSend()).isFalse();
    }

    @Test
    void aBumpForAnotherReasonDropsTheWaitingFlagAndTheCorrectionIsOncePerGeneration() {
        gen.bumpForSend(at(0), BumpReason.SERVER_SEND);
        gen.bump(at(10), BumpReason.BARGE_IN);
        assertThat(gen.pendingSend()).isFalse();

        assertThat(gen.claimCorrection()).isTrue();
        assertThat(gen.claimCorrection()).as("세대당 한 번").isFalse();
        gen.bump(at(20), BumpReason.GATE_CORRECTION);
        assertThat(gen.claimCorrection()).as("새 세대는 다시 한 번").isTrue();
    }

    @Test
    void releasingAHoldBumpsTheGenerationSoTheTailOfAnAutoResponseStartedWhileHeldIsDropped() {
        gen.onChunk(at(0), true);
        long before = gen.generation();

        gen.bump(at(50), BumpReason.HOLD_RELEASED);

        assertThat(gen.generation()).isEqualTo(before + 1);
        assertThat(gen.onChunk(at(100), false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.onQuiet(at(900)).verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
    }

    @Test
    void generationNeverGoesDownAndNegativeTimesAreRefused() {
        long last = gen.generation();
        for (int i = 0; i < 5; i++) {
            long next = gen.bump(at(i), BumpReason.BARGE_IN);
            assertThat(next).isGreaterThan(last);
            last = next;
        }
        assertThatThrownBy(() -> new OutputGeneration(Duration.ofMillis(-1), AUTO)).isInstanceOf(IllegalArgumentException.class);
    }
}
