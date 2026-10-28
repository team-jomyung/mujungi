package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 연결 송신기의 대기와 깊이 (U1 3.6 수정 · PD-1 · PD-4 · NFR6.1 · J298 · J306).
 *
 * <p>송신 스레드는 시한 없이 기다린다(예전 1초 재확인 상수 제거): 넣기 · 닫기 요청 · 상대 닫힘 · 끊김이 모두 깨우므로 일을 놓치지 않고 스레드가
 * 끝나야 할 때 끝난다. 레인 깊이({@code mujoong_queue_depth}의 output_control · output_audio)는 기다리는 제어 메시지 수와 기다리는 · 보내는
 * 중인 묶음 수다.
 */
@Tag("skeleton")
class ConnectionSenderTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock();
    private final RecordingTransport transport = new RecordingTransport();
    private final RecordingListener listener = new RecordingListener();
    private final ConnectionSender sender = new ConnectionSender("test", transport, JsonCodec.shared(), clock, listener,
            4, 48_000);

    @Test
    void aWaitingWorkerSendsEveryEnqueuedMessageInOrder() throws Exception {
        Thread worker = startWorker();

        for (int i = 0; i < 3; i++) {
            assertThat(sender.enqueueControl(new SessionState(View.LISTENING), null, null)).isTrue();
            transport.awaitTexts(i + 1);
        }

        assertThat(transport.texts).hasSize(3).allSatisfy(text -> assertThat(text).contains("SESSION_STATE"));
        assertThat(worker.isAlive()).as("일이 없으면 기다린다").isTrue();
        sender.close(CloseCode.DEVICE_OFF);
        worker.join(WAIT.toMillis());
        assertThat(worker.isAlive()).isFalse();
    }

    @Test
    void aCloseRequestWakesTheWorkerWhichClosesTheTransportOnceAndEnds() throws Exception {
        Thread worker = startWorker();

        sender.close(CloseCode.GOING_AWAY);
        sender.close(CloseCode.SERVER_ERROR);

        worker.join(WAIT.toMillis());
        assertThat(worker.isAlive()).isFalse();
        assertThat(transport.closes).containsExactly(CloseCode.GOING_AWAY);
        assertThat(listener.closed.get()).isEqualTo(CloseCode.GOING_AWAY);
        assertThat(sender.enqueueControl(new SessionState(View.LISTENING), null, null)).as("닫힌 뒤에는 받지 않는다")
                .isFalse();
    }

    @Test
    void aPeerCloseEndsAWaitingWorkerWithoutWritingAnything() throws Exception {
        Thread worker = startWorker();

        sender.markClosedByPeer();

        worker.join(WAIT.toMillis());
        assertThat(worker.isAlive()).isFalse();
        assertThat(transport.texts).isEmpty();
        assertThat(transport.closes).isEmpty();
    }

    @Test
    void aBrokenTransportEndsTheWorkerAndReportsTheFailure() throws Exception {
        transport.failNextText = true;
        Thread worker = startWorker();

        sender.enqueueControl(new SessionState(View.LISTENING), null, null);

        worker.join(WAIT.toMillis());
        assertThat(worker.isAlive()).isFalse();
        assertThat(listener.broken.get()).isInstanceOf(IOException.class);
        assertThat(sender.isClosing()).isTrue();
    }

    @Test
    void depthsCountWaitingControlMessagesAndWaitingOrSendingBundles() {
        UUID session = UUID.randomUUID();
        sender.enqueueControl(new SessionState(View.LISTENING), null, session);
        sender.enqueueControl(new SessionState(View.THINKING), null, session);
        sender.enqueueOutput(bundle(session));
        sender.enqueueOutput(bundle(session));

        assertThat(sender.controlDepth()).isEqualTo(2);
        assertThat(sender.audioDepth()).isEqualTo(2);

        sender.drainAvailable();
        assertThat(sender.controlDepth()).isZero();
        assertThat(sender.audioDepth()).isZero();

        sender.enqueueControl(new SessionState(View.LISTENING), null, session);
        sender.enqueueOutput(bundle(session));
        sender.close(CloseCode.DEVICE_OFF);
        assertThat(sender.controlDepth()).as("닫기 요청은 레인을 비운다").isZero();
        assertThat(sender.audioDepth()).isZero();
        assertThat(sender.reservedBytes()).as("비운 묶음의 예약도 푼다").isZero();
    }

    @Test
    void outputBeginCarriesTheBundleSubtitleAndOmitsItWhenAbsent() throws Exception {
        UUID session = UUID.randomUUID();
        DeviceLink.AudioReservation first = sender.reserve(4_800).orElseThrow();
        sender.enqueueOutput(new OutputBundle(session, UUID.randomUUID(), OutputBegin.Source.GPT_LIVE,
                List.of(new byte[4_800]), 100, first, () -> true, at -> { }, frame -> { }, "  같이 놀자고 말해 볼까?  "));
        sender.enqueueOutput(bundle(session));
        Thread worker = startWorker();

        transport.awaitTexts(4);

        List<String> begins = transport.texts.stream().filter(text -> text.contains("OUTPUT_BEGIN")).toList();
        assertThat(begins).hasSize(2);
        assertThat(begins.get(0)).contains("\"text\":\"같이 놀자고 말해 볼까?\"");
        assertThat(begins.get(1)).doesNotContain("\"text\"");
        sender.close(CloseCode.DEVICE_OFF);
        worker.join(WAIT.toMillis());
    }

    private OutputBundle bundle(UUID session) {
        DeviceLink.AudioReservation reservation = sender.reserve(4_800).orElseThrow();
        return new OutputBundle(session, UUID.randomUUID(), OutputBegin.Source.GPT_LIVE, List.of(new byte[4_800]), 100,
                reservation, () -> true, at -> { });
    }

    private Thread startWorker() {
        AtomicReference<Thread> started = new AtomicReference<>();
        sender.start((name, task) -> {
            Thread thread = TaskLauncher.virtualThreads().launch(name, task);
            started.set(thread);
            return thread;
        });
        return started.get();
    }

    /** 쓴 것을 모으는 소켓 대역. */
    private static final class RecordingTransport implements ConnectionSender.Transport {

        private final List<String> texts = new CopyOnWriteArrayList<>();
        private final List<CloseCode> closes = new CopyOnWriteArrayList<>();
        private volatile boolean failNextText;

        @Override
        public void sendText(String text) throws IOException {
            if (failNextText) {
                failNextText = false;
                throw new IOException("끊긴 소켓");
            }
            texts.add(text);
        }

        @Override
        public void sendBinary(byte[] frame) {
            // 이 시험은 프레임 내용을 보지 않는다
        }

        @Override
        public void close(CloseCode code) {
            closes.add(code);
        }

        void awaitTexts(int count) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (texts.size() < count && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(2);
            }
            assertThat(texts).hasSizeGreaterThanOrEqualTo(count);
        }
    }

    /** 송신기 알림 대역. */
    private static final class RecordingListener implements ConnectionSender.Listener {

        private final AtomicReference<CloseCode> closed = new AtomicReference<>();
        private final AtomicReference<IOException> broken = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void controlOverflow() {
            // 이 시험은 넘침을 만들지 않는다
        }

        @Override
        public void broken(IOException failure) {
            broken.set(failure);
            done.countDown();
        }

        @Override
        public void closed(CloseCode code) {
            closed.set(code);
            done.countDown();
        }
    }
}
