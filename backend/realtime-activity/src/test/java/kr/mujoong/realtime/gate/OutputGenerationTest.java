package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.realtime.gate.OutputGeneration.BumpReason;
import kr.mujoong.realtime.gate.OutputGeneration.ChunkResult;
import kr.mujoong.realtime.gate.OutputGeneration.ChunkVerdict;
import kr.mujoong.realtime.gate.OutputGeneration.InstructionTimeout;
import kr.mujoong.realtime.gate.OutputGeneration.Phase;
import kr.mujoong.realtime.gate.OutputGeneration.QuietResult;
import kr.mujoong.realtime.gate.OutputGeneration.QuietVerdict;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 출력 세대 협력 객체 시험 (U11 FR12.5 · BR11.5 ~ BR11.7 · BR11.13, 설계 개정 A2 ~ A5). 시각은 시험이 정하는 값이고 시간 값은 생성자로 준다.
 * 오디오 조각 하나는 960바이트 = 20ms다.
 */
@Tag("judgment-turn")
class OutputGenerationTest {

    private static final Instant T0 = Instant.parse("2026-10-17T00:00:00Z");
    private static final Duration QUIET = Duration.ofMillis(800);
    private static final Duration MARGIN = Duration.ofMillis(600);
    private static final int CHUNK = 960;

    private final OutputGeneration gen = new OutputGeneration(QUIET, MARGIN);

    @Test
    void continuousSilenceAdvancesTheTimelineWithoutPostponingTheSpeechBoundary() {
        audio(0);
        for (int i = 0; i < 250; i++) gen.onSilentAudio(4800);
        assertThat(gen.audioClockMs()).isEqualTo(25020);
        assertThat(gen.lastDeltaAt()).isEqualTo(at(0));
        assertThat(gen.onQuiet(at(800)).verdict()).isEqualTo(QuietVerdict.SEAL_RECEIVING);
        assertThat(audio(900).fromStart()).isTrue();
    }

    @Test
    void quietPracticeKeepsShortAudioAndTranscriptBeforeOrAfterAnAckWithoutTheLegacyMargin() {
        gen.bumpForQuietInstruction(at(1000), BumpReason.SERVER_SEND, "practice-1");
        assertThat(gen.awaitingNewSpeech()).isTrue();
        var first = gen.onAudio(at(1010), CHUNK, false);
        assertThat(first.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(first.fromStart()).isTrue();
        assertThat(gen.onTranscript(at(1020), 0, false).verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(gen.onAppended("older-request", 99999)).isFalse();
        assertThat(gen.onAppended("practice-1", 99999)).isTrue();
        assertThat(gen.onAudio(at(1030), CHUNK, false).verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(gen.onQuiet(at(1830)).verdict()).isEqualTo(QuietVerdict.SEAL_RECEIVING);
        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.NONE);
    }

    @Test
    void quietPracticeStillHonorsHoldCancellationAndTheNoOutputDeadline() {
        gen.bumpForQuietInstruction(at(0), BumpReason.SERVER_SEND, "quiet");
        assertThat(gen.onAudio(at(10), CHUNK, true).verdict()).isEqualTo(ChunkVerdict.DISCARD_HELD);
        assertThat(gen.awaitingNewSpeech()).isTrue();
        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.NEW_SPEECH_NOT_OBSERVED);
        assertThat(gen.onAudio(at(20), CHUNK, false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        gen.bumpForQuietInstruction(at(30), BumpReason.SERVER_SEND, "cancel");
        gen.bump(at(40), BumpReason.STOP_INTENT);
        assertThat(gen.onAppended("cancel", 0)).isFalse();
        assertThat(gen.onTranscript(at(50), 0, false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
    }

    private static Instant at(long ms) {
        return T0.plusMillis(ms);
    }

    private ChunkResult audio(long atMs) {
        return gen.onAudio(at(atMs), CHUNK, false);
    }

    /** 오디오 시계를 {@code ms}까지 낡은 음성으로 채운다(받는 단계가 아니면 버려진다). */
    private void play(long untilClockMs, long atMs) {
        while (gen.audioClockMs() < untilClockMs) {
            gen.onAudio(at(atMs), CHUNK, false);
        }
    }

    // ---- 받는 단계 · 조용함 ----

    @Test
    void receivingAcceptsChunksAndSealsCompleteWhenQuiet() {
        ChunkResult first = audio(0);
        ChunkResult second = audio(100);

        assertThat(first.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(first.fromStart()).as("처음 조각").isTrue();
        assertThat(second.fromStart()).isFalse();
        assertThat(gen.onQuiet(at(500)).verdict()).as("아직 조용하지 않다").isEqualTo(QuietVerdict.NOT_YET);
        assertThat(gen.onQuiet(at(900)).verdict()).isEqualTo(QuietVerdict.SEAL_RECEIVING);
        assertThat(audio(1000).fromStart()).as("조용함 뒤 첫 조각은 새 구간의 시작").isTrue();
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);
    }

    @Test
    void heldChunksAreDiscardedInEveryPhaseButTheClockAndLastChunkTimeMove() {
        ChunkResult held = gen.onAudio(at(0), CHUNK, true);

        assertThat(held.verdict()).isEqualTo(ChunkVerdict.DISCARD_HELD);
        assertThat(gen.lastDeltaAt()).isEqualTo(at(0));
        assertThat(gen.audioClockMs()).as("보류 중 조각도 오디오 시계는 센다").isEqualTo(20L);
        gen.bump(at(10), BumpReason.BARGE_IN);
        assertThat(gen.onTranscript(at(20), 0L, true).verdict()).isEqualTo(ChunkVerdict.DISCARD_HELD);
        assertThat(gen.phase()).isEqualTo(Phase.DISCARDING);
    }

    // ---- 지시 없이 세대를 올린 경우(A5): segment-quiet 끊김 ----

    @Test
    void bumpWithoutAnInstructionDropsChunksUntilTheGapAndThenReceivesTheNextChunk() {
        long before = gen.generation();

        long after = gen.bump(at(0), BumpReason.STOP_INTENT);

        assertThat(after).isEqualTo(before + 1);
        assertThat(gen.phase()).isEqualTo(Phase.DISCARDING);
        assertThat(audio(100).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(audio(200).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.onQuiet(at(500)).verdict()).as("마지막 조각 200ms + 800ms 전").isEqualTo(QuietVerdict.NOT_YET);
        assertThat(gen.onQuiet(at(1000)).verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
        ChunkResult next = audio(1100);
        assertThat(next.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(next.fromStart()).isTrue();
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);
    }

    @Test
    void theGapNeedsQuietFromTheLaterOfTheLastChunkAndTheBump() {
        audio(0);
        gen.bump(at(400), BumpReason.BARGE_IN);

        assertThat(gen.onQuiet(at(1000)).verdict()).as("마지막 조각 0 기준이면 찼겠지만 올림 400 + 800 = 1200").isEqualTo(QuietVerdict.NOT_YET);
        QuietResult not = gen.onQuiet(at(1100));
        assertThat(not.waitUntil()).isEqualTo(at(1200));
        assertThat(gen.onQuiet(at(1200)).verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
    }

    @Test
    void aChunkDuringTheDiscardingWaitPushesTheGapOut() {
        gen.bump(at(0), BumpReason.PRACTICE_DISCARD);
        audio(700);

        assertThat(gen.onQuiet(at(1400)).verdict()).isEqualTo(QuietVerdict.NOT_YET);
        assertThat(gen.onQuiet(at(1500)).verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
    }

    @Test
    void thereIsNoAutoResponseStartCriterionAnymore() {
        gen.onAudio(at(0), CHUNK, false);
        gen.bump(at(100), BumpReason.BARGE_IN);

        // 아이 말 끝 정보가 없다: 조용 시간(800ms)만 차면 끊김이다
        assertThat(gen.onQuiet(at(900)).verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
    }

    @Test
    void correctionIsClaimedOncePerGeneration() {
        assertThat(gen.claimCorrection()).isTrue();
        assertThat(gen.claimCorrection()).isFalse();
        gen.bump(at(0), BumpReason.GATE_CORRECTION);
        assertThat(gen.claimCorrection()).isTrue();
    }

    // ---- 지시를 보낸 경우(A4): 확인 사건 end_ms → 새 말 전사 start_ms → 오디오 시계 + 여유 ----

    @Test
    void anInstructionBumpDiscardsEverythingUntilTheAckAndANewSpeechTranscriptAreSeen() {
        play(1000, 0);
        gen.bumpForInstruction(at(10), BumpReason.SERVER_SEND, "mj-1");

        assertThat(gen.phase()).isEqualTo(Phase.DISCARDING);
        assertThat(gen.awaitingNewSpeech()).isTrue();
        assertThat(audio(20).verdict()).as("낡은 꼬리 음성").isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.onTranscript(at(30), 1100L, false).verdict()).as("확인 사건 전 전사는 새 말로 보지 않는다")
                .isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.newStartMs()).isEqualTo(-1L);
        assertThat(gen.onQuiet(at(5_000)).verdict()).as("지시 경로는 조용함으로 끊김을 확인하지 않는다").isEqualTo(QuietVerdict.NONE);
    }

    @Test
    void aTranscriptBeforeTheAckEndIsStaleAndTheFirstOneAtOrAfterItStartsTheNewSpeech() {
        play(1000, 0);
        gen.bumpForInstruction(at(10), BumpReason.SERVER_SEND, "mj-1");

        assertThat(gen.onAppended("mj-1", 1300L)).isTrue();
        assertThat(gen.ackEndMs()).isEqualTo(1300L);
        assertThat(gen.onTranscript(at(40), 1200L, false).verdict()).as("end_ms 앞 start_ms는 낡은 응답").isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.newStartMs()).isEqualTo(-1L);

        ChunkResult piece = gen.onTranscript(at(50), 1500L, false);

        assertThat(piece.verdict()).as("새 말 전사부터는 음성보다 앞서 도착해도 받는다").isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(piece.fromStart()).isTrue();
        assertThat(gen.newStartMs()).isEqualTo(1500L);
        assertThat(gen.awaitingNewSpeech()).isFalse();
        assertThat(gen.onTranscript(at(60), 1700L, false).fromStart()).isFalse();
        assertThat(gen.onTranscript(at(70), 1400L, false).verdict()).as("새 말 시작 앞의 전사는 버린다").isEqualTo(ChunkVerdict.DISCARD);
    }

    @Test
    void audioIsReceivedFromTheFirstChunkWhoseClockReachesNewStartPlusMargin() {
        play(1000, 0);
        gen.bumpForInstruction(at(10), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 1300L);
        gen.onTranscript(at(40), 1500L, false);
        // 새 말 시작 1500 + 여유 600 = 2100. 오디오 시계는 1000에서 시작해 조각마다 20ms
        play(2080, 100);
        assertThat(gen.phase()).as("2080까지는 낡은 꼬리 · 여유 안이라 버린다").isEqualTo(Phase.DISCARDING);
        assertThat(gen.audioClockMs()).isEqualTo(2080L);

        ChunkResult first = audio(200);

        assertThat(first.verdict()).as("이 조각의 시작 시계가 2080이 아니라 2100에 닿는 첫 조각").isEqualTo(ChunkVerdict.DISCARD);
        ChunkResult accepted = audio(210);
        assertThat(accepted.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(accepted.fromStart()).as("전사가 구간을 이미 열었다").isFalse();
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);
    }

    @Test
    void anAckForAnotherInstructionOrAnUnknownIdIsIgnored() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-2");

        assertThat(gen.onAppended("mj-1", 500L)).as("낡은 지시의 확인").isFalse();
        assertThat(gen.onAppended("x", 500L)).isFalse();
        assertThat(gen.onAppended("mj-2", -1L)).as("end_ms 없음").isFalse();
        assertThat(gen.ackReceived()).isFalse();
        assertThat(gen.onAppended("mj-2", 500L)).isTrue();
        assertThat(gen.onAppended("mj-2", 900L)).as("두 번째 확인은 무시").isFalse();
        assertThat(gen.ackEndMs()).isEqualTo(500L);
    }

    @Test
    void aSecondInstructionRestartsTheWaitWithItsOwnAck() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 500L);
        gen.onTranscript(at(10), 600L, false);
        assertThat(gen.newStartMs()).isEqualTo(600L);

        long generation = gen.bumpForInstruction(at(20), BumpReason.GATE_CORRECTION, "mj-2");

        assertThat(generation).isEqualTo(2L);
        assertThat(gen.newStartMs()).isEqualTo(-1L);
        assertThat(gen.ackEndMs()).isEqualTo(-1L);
        assertThat(gen.onAppended("mj-1", 700L)).as("앞 지시의 늦은 확인").isFalse();
        assertThat(gen.onAppended("mj-2", 900L)).isTrue();
        gen.onTranscript(at(30), 950L, false);
        assertThat(gen.newStartMs()).isEqualTo(950L);
    }

    @Test
    void transcriptWithoutATimeCanNeverStartNewSpeech() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 500L);

        assertThat(gen.onTranscript(at(10), -1L, false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.newStartMs()).isEqualTo(-1L);
    }

    @Test
    void aHeldTranscriptStillTracksTheNewSpeechStartButIsDropped() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 500L);

        ChunkResult held = gen.onTranscript(at(10), 800L, true);

        assertThat(held.verdict()).isEqualTo(ChunkVerdict.DISCARD_HELD);
        assertThat(gen.newStartMs()).isEqualTo(800L);
    }

    @Test
    void anOutputThatEndsBeforeTheAudioReachesTheMarginAbandonsTheTranscriptOnlySegment() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 200L);
        gen.onTranscript(at(10), 300L, false);
        gen.onAudio(at(20), CHUNK, false);

        assertThat(gen.onQuiet(at(500)).verdict()).isEqualTo(QuietVerdict.NOT_YET);
        QuietResult abandoned = gen.onQuiet(at(900));
        assertThat(abandoned.verdict()).isEqualTo(QuietVerdict.ABANDON_NO_AUDIO);
        assertThat(gen.awaitingNewSpeech()).isFalse();
        assertThat(audio(950).verdict()).as("같은 응답의 남은 음성은 다시 끊김을 기다린다").isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.onQuiet(at(2000)).verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
    }

    // ---- 시한(ACK_MISSING · NEW_SPEECH_NOT_OBSERVED) ----

    @Test
    void withoutAnAckTheFirstTimeoutIsAckMissingAndTheLatestSendClockPicksUpTheNextTranscript() {
        play(1000, 0);
        gen.bumpForInstruction(at(10), BumpReason.SERVER_SEND, "mj-1");

        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.ACK_MISSING);
        assertThat(gen.awaitingNewSpeech()).as("DISCARDING을 풀지 않는다").isTrue();
        assertThat(gen.onTranscript(at(20), 900L, false).verdict()).as("지시를 보낼 때 시계 1000 앞 전사는 낡은 것").isEqualTo(ChunkVerdict.DISCARD);
        ChunkResult piece = gen.onTranscript(at(30), 1100L, false);
        assertThat(piece.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(gen.newStartMs()).isEqualTo(1100L);
    }

    @Test
    void ifNoNewSpeechAppearsAfterAckMissingTheSecondTimeoutIsNewSpeechNotObserved() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-1");

        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.ACK_MISSING);
        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.NEW_SPEECH_NOT_OBSERVED);
        assertThat(gen.awaitingNewSpeech()).isFalse();
        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.NONE);
        // 이후 아이 말에 대한 자동 응답이 영영 막히지 않도록 조용 시간 뒤 다시 받는다
        assertThat(audio(100).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.onQuiet(at(1000)).verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
    }

    @Test
    void withAnAckButNoNewSpeechTheFirstTimeoutIsNewSpeechNotObserved() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 300L);

        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.NEW_SPEECH_NOT_OBSERVED);
    }

    @Test
    void theTimeoutIsNothingOnceNewSpeechStartedOrForANonInstructionBump() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 300L);
        gen.onTranscript(at(10), 400L, false);
        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.NONE);

        gen.bump(at(20), BumpReason.BARGE_IN);
        assertThat(gen.onInstructionTimeout()).isEqualTo(InstructionTimeout.NONE);
    }

    // ---- 지시 없는 올림이 지시 경로를 끊는다 ----

    @Test
    void aBargeInDuringTheInstructionWaitSwitchesToTheQuietGapRule() {
        gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 300L);

        gen.bump(at(100), BumpReason.BARGE_IN);

        assertThat(gen.awaitingNewSpeech()).isFalse();
        assertThat(gen.onAppended("mj-1", 400L)).as("지시 경로가 끊겼다").isFalse();
        assertThat(gen.onTranscript(at(150), 1000L, false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.onQuiet(at(900)).verdict()).as("마지막 조각 150 + 800 = 950 전").isEqualTo(QuietVerdict.NOT_YET);
        assertThat(gen.onQuiet(at(1000)).verdict()).isEqualTo(QuietVerdict.GAP_CONFIRMED);
    }

    @Test
    void timeValuesCannotBeNegativeAndTimesAreRequired() {
        assertThatThrownBy(() -> new OutputGeneration(Duration.ofMillis(-1), MARGIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutputGeneration(QUIET, Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gen.bump(null, BumpReason.BARGE_IN)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> gen.bumpForInstruction(at(0), BumpReason.SERVER_SEND, null)).isInstanceOf(NullPointerException.class);
    }
}
