package kr.mujoong.realtime.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.realtime.gate.SegmentBuffer;
import kr.mujoong.realtime.testing.FakeSpeechClipSynthesizer;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 3회차 V1: 세션들이 함께 쓰는 대본 줄 음성 장치(contracts/ai-io.md 10.16). 같은 말투 · 원문은 한 번만 만들고(만드는 중이면 같은 약속, 만든 것은 캐시),
 * 캐시는 바이트 상한 안에서 오래 안 쓴 것부터 버리며, 동시 합성 · 대기열 상한을 넘는 줄은 만들지 않고, 실패한 줄은 캐시에 남지 않는지 본다. 4회차 R6:
 * 일시 실패는 같은 차례에서 곧바로 한 번 더 맡기고, 다시 해도 같은 실패는 다시 하지 않는다. 합성은 대역이다(R-F1).
 */
@Tag("practice-flow")
class SpeechClipSupportTest {

    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final String LINE = "자, 여기 초록색!";
    private static final String OTHER = "알았어, 잠깐만!";

    private final MutableClock clock = new MutableClock();
    private final FakeSpeechClipSynthesizer synth = new FakeSpeechClipSynthesizer();

    private SpeechClipSupport support(int maxConcurrency, int maxPending, long cacheBytes) {
        return new SpeechClipSupport(synth, clock, WAIT, maxConcurrency, maxPending, cacheBytes, 48);
    }

    @Test
    void theSameVoiceAndTextIsSynthesizedOnceWhileInFlightAndServedFromTheCacheAfterwards() {
        synth.immediate = false;
        SpeechClipSupport support = support(4, 10, 1_000_000);

        CompletableFuture<SpeechClipSupport.Clip> first = support.clip(SpeechClipVoice.PEER, LINE);
        CompletableFuture<SpeechClipSupport.Clip> again = support.clip(SpeechClipVoice.PEER, LINE);
        assertThat(again).as("만드는 중이면 같은 약속").isSameAs(first);
        clock.advance(Duration.ofMillis(700));
        synth.complete(LINE);

        assertThat(first.join().pcm()).hasSize(FakeSpeechClipSynthesizer.pcmFor(LINE).length);
        assertThat(first.join().audioMs()).isEqualTo(FakeSpeechClipSynthesizer.msFor(LINE));
        assertThat(first.join().synthesisLatency()).as("맡긴 때부터 만든 때까지").isEqualTo(Duration.ofMillis(700));
        assertThat(support.clip(SpeechClipVoice.PEER, LINE).join()).as("다른 세션도 캐시에서 받는다").isSameAs(first.join());
        assertThat(support.clip(SpeechClipVoice.NARRATOR, LINE).isDone()).as("말투가 다르면 다른 줄").isFalse();
        assertThat(synth.callsFor(LINE)).isEqualTo(2);
        assertThat(support.cachedBytes()).isEqualTo(FakeSpeechClipSynthesizer.pcmFor(LINE).length);
    }

    @Test
    void theCacheDropsTheLeastRecentlyUsedClipOnceItsByteLimitIsExceeded() {
        int lineBytes = FakeSpeechClipSynthesizer.pcmFor(LINE).length;
        int otherBytes = FakeSpeechClipSynthesizer.pcmFor(OTHER).length;
        SpeechClipSupport support = support(4, 10, lineBytes + otherBytes);
        support.clip(SpeechClipVoice.PEER, LINE).join();
        support.clip(SpeechClipVoice.PEER, OTHER).join();
        support.clip(SpeechClipVoice.PEER, LINE).join();

        support.clip(SpeechClipVoice.NARRATOR, OTHER).join();

        assertThat(support.cachedBytes()).isLessThanOrEqualTo(lineBytes + otherBytes);
        support.clip(SpeechClipVoice.PEER, LINE).join();
        assertThat(synth.callsFor(LINE)).as("최근에 쓴 줄은 남았다").isEqualTo(1);
        support.clip(SpeechClipVoice.PEER, OTHER).join();
        assertThat(synth.callsFor(OTHER)).as("오래 안 쓴 줄은 버려져 다시 만든다").isEqualTo(3);
    }

    @Test
    void requestsBeyondTheConcurrencyQueueAndPendingLimitAreRefusedAsOverloaded() {
        synth.immediate = false;
        SpeechClipSupport support = support(1, 1, 1_000_000);

        CompletableFuture<SpeechClipSupport.Clip> running = support.clip(SpeechClipVoice.PEER, "하나");
        CompletableFuture<SpeechClipSupport.Clip> queued = support.clip(SpeechClipVoice.PEER, "둘");
        CompletableFuture<SpeechClipSupport.Clip> refused = support.clip(SpeechClipVoice.PEER, "셋");

        assertThat(synth.texts()).as("동시 1개만 보낸다").containsExactly("하나");
        assertThat(failure(refused)).isEqualTo(SpeechClipFailure.OVERLOADED);
        synth.complete("하나");
        assertThat(running).isCompleted();
        assertThat(synth.texts()).as("하나가 끝나면 기다리던 줄을 보낸다").containsExactly("하나", "둘");
        synth.complete("둘");
        assertThat(queued.join().pcm()).hasSize(FakeSpeechClipSynthesizer.pcmFor("둘").length);
    }

    @Test
    void aFailedLineIsNotCachedSoTheNextSessionMayAskAgain() {
        synth.failures.put(LINE, SpeechClipFailure.RATE_LIMITED);
        SpeechClipSupport support = support(4, 10, 1_000_000);

        assertThat(failure(support.clip(SpeechClipVoice.PEER, LINE))).isEqualTo(SpeechClipFailure.RATE_LIMITED);
        // 4회차 R6: 일시 실패(요청 많음)는 곧바로 한 번 더 맡긴 뒤 실패로 끝난다
        assertThat(synth.callsFor(LINE)).isEqualTo(2);
        synth.failures.clear();
        assertThat(support.clip(SpeechClipVoice.PEER, LINE).join().pcm()).isNotEmpty();
        assertThat(synth.callsFor(LINE)).isEqualTo(3);
        assertThat(support.cachedBytes()).isEqualTo(FakeSpeechClipSynthesizer.pcmFor(LINE).length);
    }

    /** 4회차 R6(실측 B5): 시간 초과는 같은 차례에서 곧바로 한 번 더 맡기고, 그 음성을 캐시에 둔다. 만든 시간은 처음 맡긴 때부터다. */
    @Test
    void aTimeoutIsTriedOnceMoreAndTheSecondSuccessBecomesTheCachedClip() {
        synth.immediate = false;
        SpeechClipSupport support = support(4, 10, 1_000_000);
        CompletableFuture<SpeechClipSupport.Clip> clip = support.clip(SpeechClipVoice.NARRATOR, LINE);
        clock.advance(Duration.ofSeconds(20));

        synth.fail(LINE, SpeechClipFailure.TIMEOUT);

        assertThat(clip).as("한 번 더 맡겨 아직 기다린다").isNotDone();
        assertThat(synth.callsFor(LINE)).isEqualTo(2);
        clock.advance(Duration.ofSeconds(3));
        synth.complete(LINE);
        assertThat(clip.join().synthesisLatency()).isEqualTo(Duration.ofSeconds(23));
        assertThat(support.clip(SpeechClipVoice.NARRATOR, LINE).join()).as("다시 만든 음성은 캐시에서 준다").isSameAs(clip.join());
        assertThat(synth.callsFor(LINE)).isEqualTo(2);
    }

    @Test
    void twoTransientFailuresInARowEndAsAFailureAndTheRetryKeepsTheConcurrencySlotUntilThen() {
        synth.immediate = false;
        SpeechClipSupport support = support(1, 10, 1_000_000);
        CompletableFuture<SpeechClipSupport.Clip> first = support.clip(SpeechClipVoice.PEER, LINE);
        CompletableFuture<SpeechClipSupport.Clip> queued = support.clip(SpeechClipVoice.PEER, OTHER);

        synth.fail(LINE, SpeechClipFailure.PROVIDER_ERROR);
        assertThat(synth.texts()).as("다시 맡기는 동안 동시 자리를 쥐고 있어 기다리던 줄은 아직이다").containsExactly(LINE, LINE);
        synth.fail(LINE, SpeechClipFailure.TRANSPORT_ERROR);

        assertThat(failure(first)).isEqualTo(SpeechClipFailure.TRANSPORT_ERROR);
        assertThat(synth.texts()).as("두 번째 실패 뒤 자리를 돌려준다").containsExactly(LINE, LINE, OTHER);
        synth.complete(OTHER);
        assertThat(queued.join().pcm()).isNotEmpty();
        assertThat(support.cachedBytes()).isEqualTo(FakeSpeechClipSynthesizer.pcmFor(OTHER).length);
    }

    @Test
    void authInputAndAudioFailuresAreNotTriedAgain() {
        synth.failures.put(LINE, SpeechClipFailure.AUTH_FAILED);
        synth.failures.put(OTHER, SpeechClipFailure.REQUEST_REJECTED);
        SpeechClipSupport support = support(4, 10, 1_000_000);
        AtomicInteger oddCalls = new AtomicInteger();
        SpeechClipSupport odd = new SpeechClipSupport((voice, text) -> {
            oddCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new byte[3]);
        }, clock, WAIT, 4, 10, 1_000_000, 48);

        assertThat(failure(support.clip(SpeechClipVoice.PEER, LINE))).isEqualTo(SpeechClipFailure.AUTH_FAILED);
        assertThat(failure(support.clip(SpeechClipVoice.PEER, OTHER))).isEqualTo(SpeechClipFailure.REQUEST_REJECTED);
        assertThat(failure(odd.clip(SpeechClipVoice.PEER, LINE))).isEqualTo(SpeechClipFailure.INVALID_AUDIO);

        assertThat(synth.callsFor(LINE)).isEqualTo(1);
        assertThat(synth.callsFor(OTHER)).isEqualTo(1);
        assertThat(oddCalls).hasValue(1);
        assertThat(SpeechClipSupport.transientFailure(SpeechClipFailure.TIMEOUT)).isTrue();
        assertThat(SpeechClipSupport.transientFailure(SpeechClipFailure.OVERLOADED)).as("장치 과부하는 장치 안에서 다시 하지 않는다").isFalse();
    }

    @Test
    void unusableAudioAndInputAreRefusedWithTheirOwnReasonCodes() {
        SpeechClipSupport support = new SpeechClipSupport((voice, text) -> CompletableFuture.completedFuture(switch (text) {
            case "빈 음성" -> new byte[0];
            case "홀수" -> new byte[3];
            default -> new byte[SegmentBuffer.MAX_BYTES + 2];
        }), clock, WAIT, 4, 10, 10_000_000, 48);

        assertThat(failure(support.clip(SpeechClipVoice.PEER, "빈 음성"))).isEqualTo(SpeechClipFailure.EMPTY_AUDIO);
        assertThat(failure(support.clip(SpeechClipVoice.PEER, "홀수"))).isEqualTo(SpeechClipFailure.INVALID_AUDIO);
        assertThat(failure(support.clip(SpeechClipVoice.PEER, "너무 긴 음성"))).as("출력 구간 20초 분량을 넘는다")
                .isEqualTo(SpeechClipFailure.AUDIO_TOO_LONG);
        assertThat(failure(support.clip(SpeechClipVoice.PEER, " "))).isEqualTo(SpeechClipFailure.INVALID_INPUT);
        assertThat(failure(support.clip(SpeechClipVoice.PEER, "가".repeat(SpeechClipSupport.MAX_INPUT_LENGTH + 1))))
                .isEqualTo(SpeechClipFailure.INVALID_INPUT);
        assertThat(support.cachedBytes()).isZero();
    }

    @Test
    void aSynthesizerThatThrowsBecomesATransportFailureAndTheLineFallsBack() {
        SpeechClipSupport support = new SpeechClipSupport((voice, text) -> {
            throw new IllegalStateException("시험용 합성 장애");
        }, clock, WAIT, 1, 1, 1_000_000, 48);

        assertThat(failure(support.clip(SpeechClipVoice.NARRATOR, LINE))).isEqualTo(SpeechClipFailure.TRANSPORT_ERROR);
        assertThat(failure(support.clip(SpeechClipVoice.NARRATOR, OTHER))).as("실패한 합성은 동시 자리를 돌려준다")
                .isEqualTo(SpeechClipFailure.TRANSPORT_ERROR);
    }

    @Test
    void theDisabledDeviceNeverSynthesizesAndReportsWhyItIsOff() {
        SpeechClipSupport off = SpeechClipSupport.disabled(SpeechClipSupport.State.DISABLED_NO_KEY);

        assertThat(off.enabled()).isFalse();
        assertThat(off.state()).isEqualTo(SpeechClipSupport.State.DISABLED_NO_KEY);
        assertThat(off.readyWait()).isZero();
        assertThat(off.sessionMaxLines()).isZero();
        assertThat(failure(off.clip(SpeechClipVoice.PEER, LINE))).isEqualTo(SpeechClipFailure.DISABLED);
        assertThat(SpeechClipSupport.DISABLED.state()).isEqualTo(SpeechClipSupport.State.DISABLED_SWITCH_OFF);
        assertThatThrownBy(() -> SpeechClipSupport.disabled(SpeechClipSupport.State.ENABLED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeechClipSupport(synth, clock, WAIT, 0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(off.toString()).doesNotContain(LINE);
        off.close();
        support(1, 1, 1).close();
        assertThat(synth.closed).as("운영 장치는 합성기를 닫는다").isEqualTo(1);
    }

    private static SpeechClipFailure failure(CompletableFuture<SpeechClipSupport.Clip> clip) {
        assertThat(clip).isCompletedExceptionally();
        try {
            clip.join();
            throw new AssertionError("실패해야 한다");
        } catch (CompletionException e) {
            return SpeechClipException.failureOf(e);
        }
    }
}
