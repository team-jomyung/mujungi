package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.testing.FakeProviderPort;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 입력 음성 중계 시험 (U1 3.5 Step 29 · BR7.1 · PD-2 L1 · R3-8 · J131 · NFR12.3).
 *
 * <p>수락 구간 밖 프레임은 버리고, 순번 순서대로 무변환으로 공급자에 보내며, 50프레임이 넘으면 가장 오래된 것을 버린다. 프레임은 대기열에만
 * 잠깐 있고 보낸 뒤 남지 않는다(원음 미저장).
 */
@Tag("skeleton")
class InputRelayTest {

    private final MutableClock clock = new MutableClock();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final FakeProviderPort provider = new FakeProviderPort();
    private final InputRelay relay = new InputRelay(UUID.randomUUID(), 50, new ProviderRequestFactory(), clock, metrics);

    @Test
    void framesBeforeAcceptingAreDroppedAndNeverBuffered() throws Exception {
        relay.attach(provider.open(event -> { }, Duration.ofSeconds(1)));

        assertThat(relay.offer(1, frame(1), clock.monotonicNanos())).isFalse();
        relay.setAccepting(true);
        assertThat(relay.offer(2, frame(2), clock.monotonicNanos())).isTrue();

        assertThat(relay.relayAvailable()).isEqualTo(1);
        assertThat(relay.droppedFrames()).isEqualTo(1);
        assertThat(metrics.count("mujoong.input.frames.dropped", "cause", "not_accepting")).isEqualTo(1.0);
    }

    @Test
    void framesAreRelayedInSequenceOrderWithoutAnyConversion() throws Exception {
        relay.attach(provider.open(event -> { }, Duration.ofSeconds(1)));
        relay.setAccepting(true);
        byte[] first = frame(3);
        byte[] second = frame(4);
        relay.offer(10, first, clock.monotonicNanos());
        relay.offer(11, second, clock.monotonicNanos());
        assertThat(relay.offer(11, frame(5), clock.monotonicNanos())).isFalse();
        assertThat(relay.offer(9, frame(5), clock.monotonicNanos())).isFalse();
        clock.advanceMillis(4);

        relay.relayAvailable();

        List<ProviderRequestFactory.InputAudioAppend> sent =
                provider.last().requests(ProviderRequestFactory.InputAudioAppend.class);
        assertThat(sent).extracting(ProviderRequestFactory.InputAudioAppend::type)
                .containsOnly(ProviderRequestFactory.TYPE_INPUT_AUDIO_APPEND);
        assertThat(Base64.getDecoder().decode(sent.get(0).audio())).isEqualTo(first);
        assertThat(Base64.getDecoder().decode(sent.get(1).audio())).isEqualTo(second);
        assertThat(relay.queued()).isZero();
        assertThat(relay.relayedFrames()).isEqualTo(2);
        assertThat(relay.drainRelayInP95Millis()).isEqualTo(4L);
        assertThat(relay.drainRelayInP95Millis()).isNull();
    }

    @Test
    void theFiftyFrameQueueDropsTheOldestFrameWhenFull() throws Exception {
        relay.setAccepting(true);
        for (int seq = 1; seq <= 51; seq++) {
            relay.offer(seq, frame(seq), clock.monotonicNanos());
        }
        assertThat(relay.queued()).isEqualTo(50);
        relay.attach(provider.open(event -> { }, Duration.ofSeconds(1)));

        relay.relayAvailable();

        byte[] firstSent = Base64.getDecoder()
                .decode(provider.last().requests(ProviderRequestFactory.InputAudioAppend.class).get(0).audio());
        assertThat(firstSent).isEqualTo(frame(2));
        assertThat(metrics.count("mujoong.input.frames.dropped", "cause", "queue_full")).isEqualTo(1.0);
    }

    @Test
    void closingAcceptanceOrStoppingClearsTheQueueAndAProviderSendFailureIsCounted() throws Exception {
        ProviderPort.ProviderSession session = provider.open(event -> { }, Duration.ofSeconds(1));
        relay.attach(session);
        relay.setAccepting(true);
        relay.offer(1, frame(1), 0);
        relay.setAccepting(false);
        assertThat(relay.queued()).isZero();

        relay.setAccepting(true);
        relay.offer(2, frame(2), 0);
        session.close();
        assertThat(relay.relayAvailable()).isZero();
        assertThat(metrics.count("mujoong.input.frames.dropped", "cause", "provider_send")).isEqualTo(1.0);

        relay.stop();
        assertThat(relay.isAccepting()).isFalse();
        relay.setAccepting(true);
        assertThat(relay.offer(3, frame(3), 0)).isFalse();
        assertThatThrownBy(() -> relay.offer(4, new byte[10], 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyVoiceFramesMoveTheLastVoiceFrameTime() {
        relay.setAccepting(true);
        relay.offer(1, new byte[960], 0);
        assertThat(relay.lastVoiceFrameAt()).isNull();

        clock.advanceMillis(20);
        relay.offer(2, loud(), 0);

        assertThat(relay.lastVoiceFrameAt()).isEqualTo(clock.now());
        assertThat(InputRelay.isVoice(loud())).isTrue();
        assertThat(InputRelay.isVoice(new byte[960])).isFalse();
    }

    @Test
    void theRelayThreadSendsQueuedFramesUntilStopped() throws Exception {
        FakeProviderPort.FakeSession session = (FakeProviderPort.FakeSession) provider.open(event -> { },
                Duration.ofSeconds(1));
        relay.attach(session);
        relay.setAccepting(true);
        relay.start(TaskLauncher.virtualThreads());
        relay.offer(1, frame(1), clock.monotonicNanos());

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (session.requests(ProviderRequestFactory.InputAudioAppend.class).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        relay.stop();

        assertThat(session.requests(ProviderRequestFactory.InputAudioAppend.class)).hasSize(1);
    }

    @Test
    void aWaitingRelayThreadWakesOnAttachAndEndsOnStopWithoutAPollingInterval() throws Exception {
        // 3.6 수정 J306: 1초 재확인 없이 붙이기 · 넣기 · 멈추기의 신호로만 깨어난다
        java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
        relay.setAccepting(true);
        relay.start((name, task) -> {
            Thread thread = TaskLauncher.virtualThreads().launch(name, task);
            worker.set(thread);
            return thread;
        });
        relay.offer(1, frame(1), clock.monotonicNanos());
        relay.offer(2, frame(2), clock.monotonicNanos());
        assertThat(relay.queued()).as("공급자가 붙기 전에는 보내지 않고 기다린다").isEqualTo(2);

        FakeProviderPort.FakeSession session = (FakeProviderPort.FakeSession) provider.open(event -> { },
                Duration.ofSeconds(1));
        relay.attach(session);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (session.requests(ProviderRequestFactory.InputAudioAppend.class).size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertThat(session.requests(ProviderRequestFactory.InputAudioAppend.class)).hasSize(2);

        relay.stop();
        worker.get().join(Duration.ofSeconds(5).toMillis());
        assertThat(worker.get().isAlive()).as("멈추면 기다리던 스레드가 끝난다").isFalse();
    }

    private static byte[] frame(int marker) {
        byte[] pcm = new byte[960];
        pcm[0] = (byte) marker;
        pcm[959] = (byte) (marker >> 8);
        return pcm;
    }

    private static byte[] loud() {
        byte[] pcm = new byte[960];
        for (int i = 0; i < pcm.length; i += 2) {
            pcm[i] = (byte) 0x10;
            pcm[i + 1] = (byte) 0x27;
        }
        return pcm;
    }
}
