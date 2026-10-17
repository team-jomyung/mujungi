package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import kr.mujoong.realtime.gate.SegmentBuffer.Added;
import kr.mujoong.realtime.gate.SegmentBuffer.Sealed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 출력 구간 버퍼 시험 (U1 3.6 수정 라운드 · 리뷰 F1 · SD-17 3단계 · BR7.3 · BR7.4 (가) · PD-4 · J285 ~ J288, U11 BR11.6로 열쇠를 응답 id에서
 * 출력 세대로 바꿨다).
 *
 * <p>한 구간에 한 세대만 담는지, 첫 조각부터 끊김까지를 모두 담아 끊겨서 봉인한 구간만 완결(전사-음성 대응 근거)인지, 상한 초과 조각을 다음
 * 끊김까지 어떻게 버리는지 본다.
 */
@Tag("u1")
class SegmentBufferTest {

    private static final Instant AT = Instant.parse("2026-10-15T00:00:00Z");

    private final SegmentBuffer buffer = new SegmentBuffer();

    @Test
    void aWholeOutputFromItsFirstFragmentToTheQuietSealIsComplete() {
        assertThat(buffer.addAudio(1, true, new byte[4], AT, 10)).isEqualTo(Added.ADDED);
        assertThat(buffer.addTranscript(1, false, "안녕", AT.plusMillis(1), 20)).isEqualTo(Added.ADDED);
        assertThat(buffer.addAudio(1, false, new byte[2], AT.plusMillis(2), 30)).isEqualTo(Added.ADDED);

        Sealed sealed = buffer.sealQuiet(7);

        assertThat(sealed.complete()).isTrue();
        assertThat(sealed.outputGen()).isEqualTo(1);
        assertThat(sealed.transcript()).isEqualTo("안녕");
        assertThat(sealed.chunks()).hasSize(2);
        assertThat(sealed.totalBytes()).isEqualTo(6);
        assertThat(sealed.generation()).isEqualTo(7);
        assertThat(sealed.firstOutNanos()).isEqualTo(10);
        assertThat(sealed.lastOutNanos()).isEqualTo(30);
        assertThat(sealed.lastOutAt()).isEqualTo(AT.plusMillis(2));
        assertThat(sealed.oversize()).isFalse();
        assertThat(buffer.isOpen()).isFalse();
        assertThat(buffer.sealQuiet(8)).isNull();
    }

    @Test
    void aFragmentOfAnotherGenerationIsRefusedUntilTheOpenSegmentIsSealed() {
        buffer.addTranscript(1, true, "안녕", AT, 1);

        assertThat(buffer.holdsOtherThan(2)).isTrue();
        assertThat(buffer.holdsOtherThan(1)).isFalse();
        assertThat(buffer.addAudio(2, true, new byte[2], AT, 2)).isEqualTo(Added.OTHER_GENERATION);
        Sealed first = buffer.sealByGeneration(1);
        assertThat(first.totalBytes()).isZero();
        assertThat(first.complete()).as("세대가 바뀌어 닫은 구간은 불완전").isFalse();

        assertThat(buffer.addAudio(2, true, new byte[2], AT, 3)).isEqualTo(Added.ADDED);
        assertThat(buffer.sealQuiet(1).complete()).isTrue();
    }

    @Test
    void aSegmentThatDidNotStartFromTheFirstFragmentIsNeverComplete() {
        buffer.addTranscript(1, false, "치료가 필요해요", AT, 3);
        buffer.addAudio(1, false, new byte[2], AT, 4);

        assertThat(buffer.sealQuiet(1).complete()).isFalse();
    }

    @Test
    void closingEarlyIsCompleteOnlyWhenTheOutputHadAlreadyGoneQuiet() {
        buffer.addTranscript(1, true, "안녕", AT, 1);
        buffer.addAudio(1, false, new byte[2], AT, 2);
        assertThat(buffer.sealEarly(1, false).complete()).as("응답이 이어지던 중").isFalse();

        buffer.addTranscript(2, true, "반가워", AT, 3);
        buffer.addAudio(2, false, new byte[2], AT, 4);
        assertThat(buffer.sealEarly(1, true).complete()).as("이미 끊겨 있었다").isTrue();
        assertThat(buffer.sealEarly(1, true)).isNull();
    }

    @Test
    void sealingByGenerationClearsTheOpenSegmentAndTheDroppingMark() {
        SegmentBuffer small = new SegmentBuffer(4);
        small.addAudio(1, true, new byte[2], AT, 1);
        assertThat(small.addAudio(1, false, new byte[4], AT, 2)).isEqualTo(Added.OVERSIZE);

        assertThat(small.sealByGeneration(1)).isNull();
        assertThat(small.addTranscript(1, true, "새 응답", AT, 3)).as("세대 변경이 버리던 표시를 지운다").isEqualTo(Added.ADDED);
    }

    @Test
    void anOversizedOutputIsCutOffAndItsRestIsDroppedUntilTheNextQuietSeal() {
        SegmentBuffer small = new SegmentBuffer(4);
        assertThat(small.addAudio(1, true, new byte[2], AT, 1)).isEqualTo(Added.ADDED);

        assertThat(small.addAudio(1, false, new byte[4], AT, 2)).isEqualTo(Added.OVERSIZE);
        Sealed oversized = small.takeOversized();
        assertThat(oversized.oversize()).isTrue();
        assertThat(oversized.complete()).isFalse();
        assertThat(small.takeOversized()).isNull();
        assertThat(small.isOpen()).isFalse();

        assertThat(small.addTranscript(1, false, "남은 말", AT, 3)).isEqualTo(Added.DROPPED);
        assertThat(small.addAudio(2, true, new byte[2], AT, 4)).as("다른 세대는 받는다").isEqualTo(Added.ADDED);
        small.reset();
        // 끊김으로 봉인하면(열린 구간이 없어도) 버리던 표시가 지워져 같은 세대의 다음 조각을 새 구간으로 받는다
        small.addAudio(3, true, new byte[2], AT, 5);
        small.addAudio(3, false, new byte[4], AT, 6);
        assertThat(small.addTranscript(3, false, "더", AT, 7)).isEqualTo(Added.DROPPED);
        assertThat(small.sealQuiet(1)).isNull();
        assertThat(small.addTranscript(3, true, "더", AT, 8)).isEqualTo(Added.ADDED);
        assertThat(small.sealQuiet(1).complete()).isTrue();
    }
}
