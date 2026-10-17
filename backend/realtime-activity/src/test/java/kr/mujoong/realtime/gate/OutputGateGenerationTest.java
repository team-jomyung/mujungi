package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import kr.mujoong.realtime.gate.GateDecision.DiscardReason;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 출력 게이트 검사가 세대 열쇠 구간(끊김 봉인 = 완전)을 다루는 방식 (U11 BR11.6). 시드 사전은 운영 파일을 그대로 쓴다. */
@Tag("u11")
class OutputGateGenerationTest {

    private final OutputGate gate = new OutputGate(SeedDictionary.loadDefault());
    private final SegmentBuffer buffer = new SegmentBuffer();

    private void fill(long gen, boolean fromStart, String text, int audioBytes) {
        buffer.addTranscript(gen, fromStart, text, Instant.EPOCH, 0);
        buffer.addAudio(gen, false, new byte[audioBytes], Instant.EPOCH, 1);
    }

    @Test
    void aQuietSealedSegmentFromItsFirstChunkPasses() {
        fill(1, true, "우와, 공룡 그림을 그렸구나!", 4_800);

        assertThat(gate.inspect(buffer.sealQuiet(1)).passed()).isTrue();
    }

    @Test
    void aSegmentClosedByAGenerationChangeIsDiscardedAsEmptyEvenIfTheTextIsFine() {
        fill(1, true, "우와, 공룡 그림을 그렸구나!", 4_800);

        assertThat(gate.inspect(buffer.sealByGeneration(1)).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
    }

    @Test
    void aSegmentWhoseFirstChunkWasLostIsNeverComplete() {
        fill(1, false, "그 다음 이야기", 4_800);

        assertThat(gate.inspect(buffer.sealQuiet(1)).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
    }

    @Test
    void aForbiddenPatternIsCaughtOnlyAfterTheCompletenessCheckPasses() {
        fill(1, true, "친구가 그렇게 웃는 건 비정상이야", 4_800);
        assertThat(gate.inspect(buffer.sealQuiet(1)).reason()).isEqualTo(DiscardReason.GATE_PATTERN);

        fill(2, true, "친구가 그렇게 웃는 건 비정상이야", 4_800);
        assertThat(gate.inspect(buffer.sealByGeneration(1)).reason()).as("불완전이 먼저").isEqualTo(DiscardReason.GATE_EMPTY);
        assertThat(gate.matchedCategory(buffer.sealByGeneration(1))).isEmpty();
    }

    @Test
    void emptyTranscriptOddBytesOrNoAudioAreDiscardedEvenWhenQuietSealed() {
        fill(1, true, "   ", 4_800);
        assertThat(gate.inspect(buffer.sealQuiet(1)).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
        fill(2, true, "안녕", 3);
        assertThat(gate.inspect(buffer.sealQuiet(1)).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
        buffer.addTranscript(3, true, "안녕", Instant.EPOCH, 0);
        assertThat(gate.inspect(buffer.sealQuiet(1)).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
    }

    @Test
    void theSealedSegmentCarriesTheLastChunkTimeNotTheSealTime() {
        buffer.addTranscript(1, true, "안녕", Instant.EPOCH.plusMillis(10), 100);
        buffer.addAudio(1, false, new byte[2], Instant.EPOCH.plusMillis(250), 400);

        SegmentBuffer.Sealed sealed = buffer.sealQuiet(1);

        assertThat(sealed.lastOutAt()).isEqualTo(Instant.EPOCH.plusMillis(250));
        assertThat(sealed.firstOutAt()).isEqualTo(Instant.EPOCH.plusMillis(10));
    }
}
