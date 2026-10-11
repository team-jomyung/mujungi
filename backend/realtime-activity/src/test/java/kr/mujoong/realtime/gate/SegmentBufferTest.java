package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import kr.mujoong.realtime.gate.SegmentBuffer.Added;
import kr.mujoong.realtime.gate.SegmentBuffer.Sealed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 출력 구간 버퍼 시험 (U1 3.6 수정 라운드 · 리뷰 F1 · SD-17 3단계 · BR7.3 · BR7.4 (가) · PD-4 · J285 ~ J288).
 *
 * <p>한 구간에 한 응답만 담는지, 그 응답의 첫 조각부터 응답 끝 신호까지를 모두 담은 구간만 완결(전사-음성 대응 근거)인지, 상한 초과 · 응답
 * id 없는 조각을 어떻게 버리는지 본다.
 */
@Tag("u1")
class SegmentBufferTest {

    private static final Instant AT = Instant.parse("2026-10-15T00:00:00Z");

    private final SegmentBuffer buffer = new SegmentBuffer();

    @Test
    void aWholeResponseFromItsFirstFragmentToItsDoneSignalIsComplete() {
        assertThat(buffer.addAudio("r1", new byte[4], AT, 10)).isEqualTo(Added.ADDED);
        assertThat(buffer.addTranscript("r1", "안녕", AT, 20)).isEqualTo(Added.ADDED);
        assertThat(buffer.addAudio("r1", new byte[2], AT, 30)).isEqualTo(Added.ADDED);
        buffer.responseDone("r1");

        Sealed sealed = buffer.seal(7);

        assertThat(sealed.complete()).isTrue();
        assertThat(sealed.responseId()).isEqualTo("r1");
        assertThat(sealed.transcript()).isEqualTo("안녕");
        assertThat(sealed.chunks()).hasSize(2);
        assertThat(sealed.totalBytes()).isEqualTo(6);
        assertThat(sealed.generation()).isEqualTo(7);
        assertThat(sealed.firstOutNanos()).isEqualTo(10);
        assertThat(sealed.lastOutNanos()).isEqualTo(30);
        assertThat(sealed.oversize()).isFalse();
        assertThat(buffer.isOpen()).isFalse();
        assertThat(buffer.seal(8)).isNull();
    }

    @Test
    void aFragmentOfAnotherResponseIsRefusedAndItsLaterSegmentIsNeverComplete() {
        buffer.addTranscript("rA", "안녕", AT, 1);

        assertThat(buffer.holdsOtherThan("rB")).isTrue();
        assertThat(buffer.holdsOtherThan("rA")).isFalse();
        assertThat(buffer.addAudio("rB", new byte[2], AT, 2)).isEqualTo(Added.OTHER_RESPONSE);
        // 다른 응답의 끝 신호는 열린 구간의 완결 근거가 아니다
        buffer.responseDone("rB");
        Sealed first = buffer.seal(1);
        assertThat(first.totalBytes()).isZero();
        assertThat(first.complete()).isFalse();

        // 받지 못한 rB의 첫 조각 때문에 rB의 뒤 구간은 끝 신호가 와도 완결이 아니다(fail-closed)
        buffer.addTranscript("rB", "반가워", AT, 3);
        buffer.addAudio("rB", new byte[2], AT, 4);
        buffer.responseDone("rB");
        assertThat(buffer.seal(1).complete()).isFalse();
    }

    @Test
    void theRestOfAResponseWhoseStartWentToAnEarlierSegmentIsNeverComplete() {
        buffer.addTranscript("r1", "그건 아마 ", AT, 1);
        buffer.addAudio("r1", new byte[2], AT, 2);
        assertThat(buffer.seal(1).complete()).isFalse();

        buffer.addTranscript("r1", "치료가 필요해요", AT, 3);
        buffer.addAudio("r1", new byte[2], AT, 4);
        buffer.responseDone("r1");
        assertThat(buffer.seal(1).complete()).isFalse();

        // 재설정(끝내기 · 취소)도 이미 본 응답을 잊지 않는다
        buffer.addTranscript("r2", "하나", AT, 5);
        buffer.reset();
        buffer.addTranscript("r2", "둘", AT, 6);
        buffer.addAudio("r2", new byte[2], AT, 7);
        buffer.responseDone("r2");
        assertThat(buffer.seal(2).complete()).isFalse();
    }

    @Test
    void aFragmentAfterTheDoneSignalMakesTheSegmentIncomplete() {
        buffer.addTranscript("r1", "안녕", AT, 1);
        buffer.addAudio("r1", new byte[2], AT, 2);
        buffer.responseDone("r1");
        assertThat(buffer.addAudio("r1", new byte[2], AT, 3)).isEqualTo(Added.ADDED);

        Sealed sealed = buffer.seal(1);

        assertThat(sealed.totalBytes()).isEqualTo(4);
        assertThat(sealed.complete()).isFalse();
    }

    @Test
    void fragmentsWithoutAResponseIdAreDroppedAndNeverOpenASegment() {
        assertThat(buffer.accepts(null)).isFalse();
        assertThat(buffer.accepts(" ")).isFalse();
        assertThat(buffer.addAudio(null, new byte[2], AT, 1)).isEqualTo(Added.DROPPED);
        assertThat(buffer.addTranscript("", "말", AT, 2)).isEqualTo(Added.DROPPED);
        assertThat(buffer.isOpen()).isFalse();
        assertThat(buffer.holdsOtherThan(null)).isFalse();

        buffer.addTranscript("r1", "안녕", AT, 3);
        assertThat(buffer.holdsOtherThan(null)).isFalse();
        assertThat(buffer.addAudio(null, new byte[2], AT, 4)).isEqualTo(Added.DROPPED);
        assertThat(buffer.seal(1).totalBytes()).isZero();
    }

    @Test
    void anOversizedResponseIsCutOffAndItsRestIsDroppedUntilItsDoneSignal() {
        SegmentBuffer small = new SegmentBuffer(4);
        assertThat(small.addAudio("r1", new byte[2], AT, 1)).isEqualTo(Added.ADDED);

        assertThat(small.addAudio("r1", new byte[4], AT, 2)).isEqualTo(Added.OVERSIZE);
        Sealed oversized = small.takeOversized();
        assertThat(oversized.oversize()).isTrue();
        assertThat(oversized.complete()).isFalse();
        assertThat(small.takeOversized()).isNull();
        assertThat(small.isOpen()).isFalse();

        assertThat(small.addTranscript("r1", "남은 말", AT, 3)).isEqualTo(Added.DROPPED);
        assertThat(small.accepts("r2")).isTrue();
        small.responseDone("r1");
        assertThat(small.accepts("r1")).isTrue();
        // 끝 신호 뒤 같은 응답의 조각은 새 구간이지만 첫 조각이 아니라 완결이 아니다
        small.addTranscript("r1", "더", AT, 4);
        small.addAudio("r1", new byte[2], AT, 5);
        small.responseDone("r1");
        assertThat(small.seal(1).complete()).isFalse();
    }
}
