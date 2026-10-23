package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import kr.mujoong.realtime.gate.OutputGeneration.BumpReason;
import kr.mujoong.realtime.gate.OutputGeneration.ChunkVerdict;
import kr.mujoong.realtime.gate.OutputGeneration.Phase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * JIRA-261: 끊김이 확인된 채 조각 없이 보낸 지시 세대의 첫 조각을 새 응답으로 받는 예외(참여 직후 스스로 시작 대기가 빈손으로 끝났을 때). 일반 지시
 * 세대는 그대로 확인 사건 · 전사 시간선으로 낡은 꼬리를 버리는지도 함께 본다. 시각은 시험이 정하는 값이다.
 */
@Tag("realtime-live-fix")
class OutputGenerationOpenStartTest {

    private static final Instant T0 = Instant.parse("2026-10-23T03:46:00Z");
    private static final int CHUNK = 960;

    private final OutputGeneration gen = new OutputGeneration(Duration.ofMillis(800), Duration.ofMillis(700));

    @Test
    void theFirstChunkAfterAQuietGapInstructionIsAcceptedEvenBeforeTheAckEnd() {
        quietGap();
        gen.bumpForInstructionAfterQuietGap(at(1_000), BumpReason.SERVER_SEND, "mj-1");
        assertThat(gen.awaitingOpenStart()).isTrue();
        assertThat(gen.onAppended("mj-1", 500L)).isTrue();

        // 모델이 지시 확인 전에 스스로 시작한 답: 전사 시간선(100)은 확인 끝(500)보다 앞이다
        var first = gen.onTranscript(at(1_100), 100L, false);
        assertThat(first.verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(first.fromStart()).as("새 응답의 첫 조각").isTrue();
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);
        assertThat(gen.takeOpenStartAccepted()).isTrue();
        assertThat(gen.takeOpenStartAccepted()).as("한 번만 알린다").isFalse();
        assertThat(gen.onAudio(at(1_120), CHUNK, false).verdict()).isEqualTo(ChunkVerdict.ACCEPT);
        assertThat(gen.awaitingNewSpeech()).isFalse();
    }

    @Test
    void anOrdinaryInstructionStillDropsChunksWhoseTranscriptPrecedesTheAck() {
        quietGap();
        gen.bumpForInstruction(at(1_000), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 500L);

        assertThat(gen.onTranscript(at(1_100), 100L, false).verdict()).as("낡은 말").isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.onAudio(at(1_120), CHUNK, false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
        assertThat(gen.takeOpenStartAccepted()).isFalse();
    }

    @Test
    void theExceptionIsRefusedUnlessTheGapWasConfirmedWithoutChunks() {
        // 세대를 올린 직후(끊김 미확인): 앞 응답의 꼬리가 아직 올 수 있다
        gen.bump(at(0), BumpReason.HOLD_RELEASED);
        assertThatThrownBy(() -> gen.bumpForInstructionAfterQuietGap(at(10), BumpReason.SERVER_SEND, "mj-1"))
                .isInstanceOf(IllegalStateException.class);
        // 끊김 확인 뒤 조각을 이미 받았다(RECEIVING): 그 응답이 이어지는 중이다
        gen.confirmGap();
        gen.onAudio(at(20), CHUNK, false);
        assertThat(gen.phase()).isEqualTo(Phase.RECEIVING);
        assertThatThrownBy(() -> gen.bumpForInstructionAfterQuietGap(at(30), BumpReason.SERVER_SEND, "mj-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aHeldFirstChunkOrTheNextInstructionEndsTheException() {
        quietGap();
        gen.bumpForInstructionAfterQuietGap(at(1_000), BumpReason.SERVER_SEND, "mj-1");
        gen.onAppended("mj-1", 500L);
        assertThat(gen.onTranscript(at(1_050), 100L, true).verdict()).isEqualTo(ChunkVerdict.DISCARD_HELD);
        assertThat(gen.awaitingOpenStart()).isFalse();
        assertThat(gen.onTranscript(at(1_100), 120L, false).verdict()).as("보류가 풀린 뒤에는 일반 규칙").isEqualTo(ChunkVerdict.DISCARD);

        // 예외 세대 뒤 다음 지시는 예외 없이 일반 지시 경로다
        OutputGeneration other = new OutputGeneration(Duration.ofMillis(800), Duration.ofMillis(700));
        other.bump(at(0), BumpReason.HOLD_RELEASED);
        other.confirmGap();
        other.bumpForInstructionAfterQuietGap(at(1_000), BumpReason.SERVER_SEND, "mj-1");
        other.bumpForInstruction(at(1_010), BumpReason.SERVER_SEND, "mj-2");
        assertThat(other.awaitingOpenStart()).isFalse();
        other.onAppended("mj-2", 500L);
        assertThat(other.onTranscript(at(1_100), 100L, false).verdict()).isEqualTo(ChunkVerdict.DISCARD);
    }

    private void quietGap() {
        gen.bump(at(0), BumpReason.HOLD_RELEASED);
        gen.confirmGap();
    }

    private static Instant at(long ms) {
        return T0.plusMillis(ms);
    }
}
