package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimedOutputBufferTest {
    private final TimedOutputBuffer buffer = new TimedOutputBuffer();
    private final Instant at = Instant.parse("2026-10-21T00:00:00Z");

    @Test
    void preservesTheTextOfTheInstructionMarginWithoutReleasingOldAudio() {
        buffer.transcript("잘린 앞말", 0, 100, at, 1);
        buffer.transcript("새 말", 100, 800, at, 2);
        buffer.audio(new byte[4800], 700 * 48);
        var unit = buffer.poll(1, 2, at, 3);
        assertThat(buffer.failed()).isFalse();
        assertThat(unit.transcript()).isEqualTo("잘린 앞말새 말");
        assertThat(unit.totalBytes()).isEqualTo(4800);
    }

    @Test
    void splitsASingleAudioFrameExactlyAtTranscriptBoundaries() {
        buffer.transcript("첫 조각", 0, 10, at, 1);
        buffer.transcript("둘째 조각", 10, 20, at, 2);
        byte[] pcm = new byte[1200];
        java.util.Arrays.fill(pcm, 0, 480, (byte) 1);
        java.util.Arrays.fill(pcm, 480, 960, (byte) 2);
        java.util.Arrays.fill(pcm, 960, 1200, (byte) 3);
        buffer.audio(pcm, 0);
        var first = buffer.poll(3, 4, at, 3);
        var second = buffer.poll(3, 4, at, 4);
        assertThat(first.transcript()).isEqualTo("첫 조각");
        assertThat(first.totalBytes()).isEqualTo(480);
        assertThat(first.chunks().getFirst()).containsOnly((byte) 1);
        assertThat(first.complete()).isTrue();
        assertThat(second.transcript()).isEqualTo("둘째 조각");
        assertThat(second.chunks().getFirst()).containsOnly((byte) 2);
        assertThat(buffer.poll(3, 4, at, 5)).isNull();
    }

    @Test
    void waitsForCompleteAudioAndPreservesNonMillisecondPcmFrames() {
        buffer.transcript("안녕", 100, 110, at, 1);
        buffer.audio(new byte[242], 4800);
        assertThat(buffer.poll(0, 0, at, 2)).isNull();
        buffer.audio(new byte[238], 5042);
        var unit = buffer.poll(0, 0, at, 3);
        assertThat(unit.totalBytes()).isEqualTo(480);
        assertThat(unit.chunks()).hasSize(2);
    }

    @Test
    void aGapOrOverlappingTranscriptInvalidatesTheBufferUntilReset() {
        buffer.transcript("첫 조각", 0, 20, at, 1);
        buffer.transcript("겹침", 10, 30, at, 2);
        assertThat(buffer.failed()).isTrue();
        buffer.audio(new byte[960], 0);
        assertThat(buffer.poll(0, 0, at, 3)).isNull();
        buffer.reset();
        buffer.transcript("새 조각", 0, 20, at, 4);
        buffer.audio(new byte[240], 0);
        buffer.audio(new byte[240], 480);
        assertThat(buffer.failed()).isTrue();
    }

    @Test
    void refusesExcessiveAudioAndNeverReleasesAnUntranscribedTail() {
        buffer.transcript("짧은 말", 0, 10, at, 1);
        buffer.audio(new byte[SegmentBuffer.MAX_BYTES + 2], 0);
        assertThat(buffer.failed()).isTrue();
        assertThat(buffer.poll(0, 0, at, 2)).isNull();
        buffer.reset();
        buffer.audio(new byte[480], 0);
        assertThat(buffer.poll(0, 0, at, 3)).isNull();
    }

    @Test
    void pollTextGivesTheTranscriptBeforeItsAudioHasArrivedOnceTheAudioHasStarted() {
        buffer.transcript("안녕하세요", 0, 100, at, 1);
        assertThat(buffer.pollText(1, 2, at, 2)).as("음성이 시작되기 전에는 내지 않는다").isNull();
        buffer.audio(new byte[480], 0);
        var unit = buffer.pollText(1, 2, at, 3);
        assertThat(unit.sealed().transcript()).isEqualTo("안녕하세요");
        assertThat(unit.sealed().totalBytes()).as("음성은 따로 꺼낸다").isZero();
        assertThat(unit.sealed().complete()).isTrue();
        assertThat(unit.endByte()).isEqualTo(100L * 48);
        assertThat(buffer.pollText(1, 2, at, 4)).isNull();
    }

    @Test
    void pollTextMergesATranscriptThatLiesEntirelyInTheTrimmedMarginIntoTheNextUnit() {
        buffer.transcript("잘린 앞말", 0, 100, at, 1);
        buffer.transcript("새 말", 100, 800, at, 2);
        buffer.audio(new byte[4800], 700 * 48);
        var unit = buffer.pollText(1, 2, at, 3);
        assertThat(unit.sealed().transcript()).isEqualTo("잘린 앞말새 말");
        assertThat(unit.endByte()).isEqualTo(800L * 48);
    }

    @Test
    void drainAudioGivesOnlyWhatHasArrivedUpToTheLimitAndKeepsTheRestInOrder() {
        buffer.transcript("가", 0, 10, at, 1);
        byte[] first = new byte[600];
        java.util.Arrays.fill(first, (byte) 1);
        buffer.audio(first, 0);
        buffer.pollText(1, 2, at, 2);
        assertThat(buffer.drainAudio(480)).singleElement().satisfies(chunk -> {
            assertThat(chunk).hasSize(480);
            assertThat(chunk).containsOnly((byte) 1);
        });
        // 한도 밖 나머지 120바이트는 남아 있고, 한도를 넘기면 이어서 나온다
        assertThat(buffer.drainAudio(480)).isEmpty();
        byte[] second = new byte[360];
        java.util.Arrays.fill(second, (byte) 2);
        buffer.audio(second, 600);
        var rest = buffer.drainAudio(1_200);
        assertThat(rest).hasSize(2);
        assertThat(rest.get(0)).hasSize(120);
        assertThat(rest.get(1)).hasSize(360);
        assertThat(buffer.drainAudio(1_200)).isEmpty();
    }
}
