package kr.mujoong.realtime.gateway;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.OutputEnd;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.realtime.runtime.TaskLauncher;

/**
 * 기기 연결 하나의 송신기 (U1 3.5 Step 24 · PD-1 표 2.1-A · PD-4 · SD-17 · OD-8 · RD-13).
 *
 * <p>연결의 모든 쓰기(제어 봉투 · 0x02 프레임 · 닫기)는 이 송신기 한 스레드가 한다 — 같은 소켓에 두 스레드가 동시에 쓰지 않는다.
 *
 * <ul>
 *   <li>레인: 제어(상한 {@code mujoong.limits.device-control-queue} 100, 넘치면 1011로 닫음)와 음성(묶음 단위, 바이트 상한
 *       {@code mujoong.timing.device.output-queue-max} 20초 분량, 자리 예약으로 통째 허용 · 거절). 제어를 늘 먼저 보낸다 — 긴 묶음의
 *       프레임 사이에도 제어가 끼어든다.
 *   <li>STOP_OUTPUT을 넣으면 음성 레인을 비운다(대기 묶음과 진행 중 묶음의 남은 프레임). 프레임마다 묶음의 출력 세대를 확인한다(SD-17).
 *   <li>봉투 seq는 실제로 보내는 순서대로 1부터(계약 4.2), sentAt은 보내는 시각, 0x02 순번은 연결마다 0부터 오른다.
 *   <li>닫기 요청은 레인을 비우고 다음 차례에 닫는다. 쓰기 실패는 연결을 끊긴 것으로 보고 닫는다.
 * </ul>
 * 원음 · 전사는 로그에 쓰지 않는다.
 */
public final class ConnectionSender {

    /** 실제 소켓(운영은 Spring WebSocketSession 감싸개). */
    public interface Transport {

        void sendText(String text) throws IOException;

        void sendBinary(byte[] frame) throws IOException;

        void close(CloseCode code) throws IOException;
    }

    /** 송신기에서 생긴 일을 연결에 알린다. */
    public interface Listener {

        /** 제어 레인이 넘쳤다(1011로 닫는다, QUEUE_PRESSURE). */
        void controlOverflow();

        /** 쓰기 실패로 끊겼다. */
        void broken(IOException failure);

        /** 닫기를 마쳤다(코드). */
        void closed(CloseCode code);
    }

    private record ControlItem(WsPayload payload, UUID opId, UUID sessionId) {
    }

    private final String name;
    private final Transport transport;
    private final JsonCodec json;
    private final MujoongClock clock;
    private final Listener listener;
    private final int controlCapacity;
    private final long audioBudgetBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();
    private final ArrayDeque<ControlItem> control = new ArrayDeque<>();
    private final ArrayDeque<OutputBundle> audio = new ArrayDeque<>();
    private final WsEnvelope.Sequencer sequencer = new WsEnvelope.Sequencer();

    private long reservedBytes;
    private CloseCode pendingClose;
    private boolean closed;
    private OutputBundle current;
    private int currentStep;
    private long outputFrameSequence;
    private Thread worker;

    /**
     * @param controlCapacity 제어 레인 상한
     * @param audioBudgetBytes 음성 레인 바이트 상한
     */
    public ConnectionSender(String name, Transport transport, JsonCodec json, MujoongClock clock, Listener listener,
            int controlCapacity, long audioBudgetBytes) {
        this.name = Objects.requireNonNull(name, "name");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
        if (controlCapacity <= 0 || audioBudgetBytes <= 0) {
            throw new IllegalArgumentException("레인 상한은 0보다 커야 한다");
        }
        this.controlCapacity = controlCapacity;
        this.audioBudgetBytes = audioBudgetBytes;
    }

    /** 송신 스레드를 띄운다(시험은 {@link #drainAvailable()}로 지금 스레드에서 보낸다). */
    public void start(TaskLauncher launcher) {
        lock.lock();
        try {
            if (worker != null || closed) {
                return;
            }
        } finally {
            lock.unlock();
        }
        Thread thread = launcher.launch("ws-sender-" + name, this::loop);
        lock.lock();
        try {
            worker = thread;
        } finally {
            lock.unlock();
        }
    }

    /** 제어 메시지를 넣는다. 닫혔거나 닫는 중이면 거짓, 넘치면 1011 닫기를 요청하고 거짓. */
    public boolean enqueueControl(WsPayload payload, UUID opId, UUID sessionId) {
        Objects.requireNonNull(payload, "payload");
        boolean overflow = false;
        lock.lock();
        try {
            if (closed || pendingClose != null) {
                return false;
            }
            if (control.size() >= controlCapacity) {
                overflow = true;
                requestCloseLocked(CloseCode.SERVER_ERROR);
            } else {
                if (payload instanceof StopOutput) {
                    clearAudioLocked();
                }
                // 계약: opId는 필수인 type에만 싣는다(없으면 만든다), 그 밖에는 싣지 않는다(opId:notAllowed)
                UUID effectiveOpId = payload.messageType().isOpIdRequired()
                        ? (opId != null ? opId : UUID.randomUUID()) : null;
                control.addLast(new ControlItem(payload, effectiveOpId, sessionId));
                workAvailable.signalAll();
                return true;
            }
        } finally {
            lock.unlock();
        }
        if (overflow) {
            listener.controlOverflow();
        }
        return false;
    }

    /** 음성 자리를 예약한다(상한을 넘으면 빈 값). */
    public Optional<DeviceLink.AudioReservation> reserve(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("예약 바이트는 0보다 커야 한다");
        }
        lock.lock();
        try {
            if (closed || pendingClose != null || reservedBytes + bytes > audioBudgetBytes) {
                return Optional.empty();
            }
            reservedBytes += bytes;
            return Optional.of(new Reservation(bytes));
        } finally {
            lock.unlock();
        }
    }

    /** 예약을 쥔 묶음을 넣는다. 닫혔으면 예약을 풀고 거짓. */
    public boolean enqueueOutput(OutputBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        lock.lock();
        try {
            if (closed || pendingClose != null) {
                bundle.reservation().release();
                return false;
            }
            audio.addLast(bundle);
            workAvailable.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 음성 레인을 비운다. */
    public void clearAudio() {
        lock.lock();
        try {
            clearAudioLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 닫기를 요청한다(먼저 온 코드가 이긴다). 레인을 비운다. */
    public void close(CloseCode code) {
        Objects.requireNonNull(code, "code");
        lock.lock();
        try {
            if (!closed) {
                requestCloseLocked(code);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 닫혔거나 닫는 중인가. */
    public boolean isClosing() {
        lock.lock();
        try {
            return closed || pendingClose != null;
        } finally {
            lock.unlock();
        }
    }

    /** 닫힘을 바깥에서 알게 됐다(상대가 닫음). 레인을 비우고 스레드를 끝낸다. */
    public void markClosedByPeer() {
        lock.lock();
        try {
            closed = true;
            control.clear();
            clearAudioLocked();
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 대기 중인 음성 예약 바이트(시험 · 진단). */
    public long reservedBytes() {
        lock.lock();
        try {
            return reservedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** 제어 레인에 기다리는 메시지 수({@code mujoong_queue_depth{queue=output_control}}, J298). */
    public int controlDepth() {
        lock.lock();
        try {
            return control.size();
        } finally {
            lock.unlock();
        }
    }

    /** 음성 레인의 묶음 수: 기다리는 묶음 + 보내는 중인 묶음({@code mujoong_queue_depth{queue=output_audio}}, J298). */
    public int audioDepth() {
        lock.lock();
        try {
            return audio.size() + (current == null ? 0 : 1);
        } finally {
            lock.unlock();
        }
    }

    /** 지금 할 수 있는 일을 모두 지금 스레드에서 한다(시험). 한 일의 수. */
    public int drainAvailable() {
        int done = 0;
        while (true) {
            Runnable work = nextWork(false);
            if (work == null) {
                return done;
            }
            work.run();
            done++;
        }
    }

    private void loop() {
        while (true) {
            Runnable work = nextWork(true);
            if (work == null) {
                return;
            }
            work.run();
        }
    }

    /** 다음 일(쓰기 하나). 끝났으면 null. {@code wait}면 일이 생길 때까지 기다린다. */
    private Runnable nextWork(boolean wait) {
        lock.lock();
        try {
            while (true) {
                if (closed) {
                    return null;
                }
                if (pendingClose != null) {
                    CloseCode code = pendingClose;
                    closed = true;
                    return () -> doClose(code);
                }
                ControlItem item = control.pollFirst();
                if (item != null) {
                    return () -> writeControl(item);
                }
                Runnable audioWork = nextAudioLocked();
                if (audioWork != null) {
                    return audioWork;
                }
                if (!wait) {
                    return null;
                }
                try {
                    // 시한 없이 기다린다(3.6 수정 J306): 일을 만드는 모든 변경(넣기 · 닫기 요청 · 상대 닫힘 · 끊김)이 잠금 안에서
                    // signalAll을 부르므로 놓치는 깨움이 없다. 예전의 1초 재확인은 시간 숫자 상수였다(NFR6.1)
                    workAvailable.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private Runnable nextAudioLocked() {
        if (current == null) {
            current = audio.pollFirst();
            currentStep = 0;
            if (current == null) {
                return null;
            }
        }
        OutputBundle bundle = current;
        int step = currentStep++;
        if (step == 0) {
            return () -> writeControl(new ControlItem(new OutputBegin(bundle.playbackId(), bundle.source()), null,
                    bundle.sessionId()));
        }
        int frameIndex = step - 1;
        if (frameIndex < bundle.frames().size()) {
            byte[] frame = bundle.frames().get(frameIndex);
            long sequence = outputFrameSequence++;
            return () -> writeFrame(bundle, frameIndex, sequence, frame);
        }
        current = null;
        bundle.reservation().release();
        return () -> writeControl(new ControlItem(new OutputEnd(bundle.playbackId(), bundle.totalMs()), null,
                bundle.sessionId()));
    }

    private void writeControl(ControlItem item) {
        try {
            WsEnvelope envelope = WsEnvelope.of(item.payload(), sequencer.next(), clock.now(), item.opId(), item.sessionId());
            transport.sendText(json.encodeWs(envelope));
        } catch (IOException e) {
            broken(e);
        }
    }

    private void writeFrame(OutputBundle bundle, int frameIndex, long sequence, byte[] frame) {
        if (!bundle.stillCurrent().getAsBoolean()) {
            abortCurrent(bundle);
            return;
        }
        try {
            transport.sendBinary(AudioFrameCodec.encodeOutput(sequence & AudioFrameCodec.MAX_SEQUENCE, frame));
        } catch (IOException e) {
            broken(e);
            return;
        }
        if (frameIndex == 0) {
            bundle.onFirstFrameSent().accept(clock.now());
        }
    }

    /** 세대가 바뀐 묶음: 남은 프레임과 OUTPUT_END를 보내지 않는다(SD-17). */
    private void abortCurrent(OutputBundle bundle) {
        lock.lock();
        try {
            if (current == bundle) {
                current = null;
            }
        } finally {
            lock.unlock();
        }
        bundle.reservation().release();
    }

    private void doClose(CloseCode code) {
        try {
            transport.close(code);
        } catch (IOException e) {
            // 이미 끊긴 소켓: 닫힌 것으로 본다
        }
        listener.closed(code);
    }

    private void broken(IOException failure) {
        lock.lock();
        try {
            closed = true;
            control.clear();
            clearAudioLocked();
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        listener.broken(failure);
    }

    private void requestCloseLocked(CloseCode code) {
        if (pendingClose == null) {
            pendingClose = code;
        }
        control.clear();
        clearAudioLocked();
        workAvailable.signalAll();
    }

    private void clearAudioLocked() {
        for (OutputBundle bundle : audio) {
            bundle.reservation().release();
        }
        audio.clear();
        if (current != null) {
            current.reservation().release();
            current = null;
        }
    }

    /** 음성 자리 예약. */
    private final class Reservation implements DeviceLink.AudioReservation {

        private final int bytes;
        private boolean released;

        Reservation(int bytes) {
            this.bytes = bytes;
        }

        @Override
        public int bytes() {
            return bytes;
        }

        @Override
        public void release() {
            lock.lock();
            try {
                if (!released) {
                    released = true;
                    reservedBytes -= bytes;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public String toString() {
        return "ConnectionSender[" + name + "]";
    }
}
