package kr.mujoong.realtime.voice;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.voice.ProviderPort.ProviderSession;
import kr.mujoong.realtime.voice.ProviderPort.ProviderUnavailableException;

/**
 * 세션 하나의 입력 중계 (U1 3.5 Step 25 · BR7.1 · PD-2 · PD-4 · R3-8 · NFR12.3 · OD-8 relayIn · J124).
 *
 * <p>WSS 수신 핸들러가 0x01 프레임 본문을 {@link #offer}로 넣고(즉시 반환), 중계 가상 스레드가 순서대로 꺼내 {@code
 * session.input_audio.append}로 보낸다. 규칙:
 *
 * <ul>
 *   <li>수락 조건(R3-8): ACTIVE부터 CLOSE_TRANSPORT 송신(또는 종료 · FAILED 전이)까지만 받는다. 그 밖의 프레임은 버리고 카운터만 센다.
 *   <li>대기열(L1) {@code mujoong.limits.session-input-queue-frames}(50)이 차면 가장 오래된 프레임을 버린다(최신 소리 우선).
 *   <li>순번이 올라가지 않는 프레임은 버린다(오래된 소리를 늦게 보내지 않는다).
 *   <li>원음을 저장하지 않는다(NFR12.3): 대기열은 메모리 뿐이고 보낸 뒤 참조를 놓는다. 멈출 때 비운다. U3: 동의된 세션만 실행기가
 *       {@link AudioTap}(원음 기록기)을 붙이고, 중계는 보내는 순서대로 그 탭에도 넘긴다(U3-R9).
 *   <li>relayIn 표본(수신 직후 → 공급자 전송 호출이 돌아온 직후, 단조 읽기)을 세션별 고정 크기 링(3,000개)에 모은다. 프레임마다 로그를
 *       남기지 않는다(NFR5.1).
 *   <li>LAST_INPUT_FRAME: 평균 절대 진폭이 {@link #VOICE_ENERGY_THRESHOLD} 이상인 프레임만 음성 활동으로 보고 그 수신 시각을 남긴다(무음
 *       프레임은 쓰지 않는다, OD-8 · J124 코드 상수).
 * </ul>
 */
public final class InputRelay {

    /** 음성 활동 판정 임계값(PCM16 평균 절대 진폭, J124 허용 목록 코드 상수). */
    public static final int VOICE_ENERGY_THRESHOLD = 500;
    /** relayIn 표본 링 크기(60초 분량, PD 2.3). */
    public static final int RELAY_IN_RING = 3000;

    /** 버린 까닭(카운터 라벨). */
    public enum DropCause {
        NOT_ACCEPTING,
        QUEUE_FULL,
        OUT_OF_ORDER,
        PROVIDER_SEND
    }

    private record Frame(long sequence, byte[] pcm, long receivedNanos) {
    }

    private final UUID sessionId;
    private final int capacity;
    private final ProviderRequestFactory factory;
    private final MujoongClock clock;
    private final RealtimeMetrics metrics;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final long[] relayInRing = new long[RELAY_IN_RING];

    private int ringSize;
    private int ringNext;
    private boolean accepting;
    private boolean stopped;
    private long lastSequence = -1;
    private ProviderSession session;
    private Instant lastVoiceFrameAt;
    private long dropped;
    private long relayed;
    private Thread worker;
    private boolean launching;
    private volatile AudioTap tap;

    public InputRelay(UUID sessionId, int capacity, ProviderRequestFactory factory, MujoongClock clock,
            RealtimeMetrics metrics) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity는 0보다 커야 한다");
        }
        this.capacity = capacity;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** U3: 보내는 아이 프레임을 함께 받을 탭(동의된 세션의 원음 기록기, U3-R9). null이면 뗀다. */
    public void tap(AudioTap value) {
        this.tap = value;
    }

    /** 공급자 세션을 붙인다(그 전에 받은 프레임은 수락 전이라 없다). */
    public void attach(ProviderSession providerSession) {
        lock.lock();
        try {
            this.session = providerSession;
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 수락 여부(R3-8). 끄면 대기열도 비운다. */
    public void setAccepting(boolean value) {
        lock.lock();
        try {
            accepting = value && !stopped;
            if (!accepting) {
                countDropped(DropCause.NOT_ACCEPTING, queue.size());
                queue.clear();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isAccepting() {
        lock.lock();
        try {
            return accepting;
        } finally {
            lock.unlock();
        }
    }

    /**
     * WSS 수신 핸들러가 부른다(기다리지 않는다).
     *
     * @return 대기열에 넣었으면 true
     */
    public boolean offer(long sequence, byte[] pcm, long receivedNanos) {
        Objects.requireNonNull(pcm, "pcm");
        if (pcm.length != AudioFrameCodec.INPUT_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("입력 프레임 본문 길이가 틀렸다");
        }
        boolean voice = isVoice(pcm);
        Instant now = voice ? clock.now() : null;
        lock.lock();
        try {
            if (!accepting) {
                countDropped(DropCause.NOT_ACCEPTING, 1);
                return false;
            }
            if (sequence <= lastSequence) {
                countDropped(DropCause.OUT_OF_ORDER, 1);
                return false;
            }
            lastSequence = sequence;
            if (queue.size() >= capacity) {
                queue.pollFirst();
                countDropped(DropCause.QUEUE_FULL, 1);
            }
            queue.addLast(new Frame(sequence, pcm, receivedNanos));
            if (voice) {
                lastVoiceFrameAt = now;
            }
            available.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 중계 가상 스레드를 띄운다. */
    public void start() {
        start(TaskLauncher.virtualThreads());
    }

    /** 중계 작업을 띄운다(시험은 실행하지 않는 장치를 넣고 {@link #relayAvailable()}로 보낸다). */
    public void start(TaskLauncher launcher) {
        Objects.requireNonNull(launcher, "launcher");
        lock.lock();
        try {
            if (launching || worker != null || stopped) {
                return;
            }
            launching = true;
        } finally {
            lock.unlock();
        }
        Thread thread = launcher.launch("input-relay-" + sessionId, this::loop);
        lock.lock();
        try {
            worker = thread;
        } finally {
            lock.unlock();
        }
    }

    /** 멈추고 대기열을 비운다. 두 번 불러도 된다. */
    public void stop() {
        Thread running;
        lock.lock();
        try {
            stopped = true;
            accepting = false;
            queue.clear();
            session = null;
            running = worker;
            available.signalAll();
        } finally {
            lock.unlock();
        }
        if (running != null && running != Thread.currentThread()) {
            running.interrupt();
        }
    }

    /**
     * 대기열에 있는 프레임을 지금 스레드에서 보낸다(시험 · 결정적 실행). 공급자 세션이 없으면 보내지 않는다.
     *
     * @return 보낸 프레임 수
     */
    public int relayAvailable() {
        int sent = 0;
        while (true) {
            Frame frame;
            ProviderSession target;
            lock.lock();
            try {
                if (stopped || session == null || queue.isEmpty()) {
                    return sent;
                }
                frame = queue.pollFirst();
                target = session;
            } finally {
                lock.unlock();
            }
            if (send(target, frame)) {
                sent++;
            }
        }
    }

    private void loop() {
        while (true) {
            Frame frame;
            ProviderSession target;
            lock.lock();
            try {
                while (!stopped && (session == null || queue.isEmpty())) {
                    // 시한 없이 기다린다(3.6 수정 J306): 조건을 바꾸는 붙이기 · 넣기 · 멈추기가 잠금 안에서 signalAll을 부르고 멈추기는
                    // 스레드도 끊는다. 예전의 1초 재확인은 시간 숫자 상수였다(NFR6.1)
                    available.await();
                }
                if (stopped) {
                    return;
                }
                frame = queue.pollFirst();
                target = session;
            } catch (InterruptedException e) {
                return;
            } finally {
                lock.unlock();
            }
            send(target, frame);
        }
    }

    private boolean send(ProviderSession target, Frame frame) {
        AudioTap currentTap = tap;
        if (currentTap != null) {
            try {
                currentTap.childFrame(frame.pcm());
            } catch (RuntimeException e) {
                // 기록 실패가 중계를 멈추지 않는다(기록기가 스스로 오류를 남긴다)
            }
        }
        try {
            target.sendInputAudio(factory.inputAudio(frame.pcm()));
        } catch (ProviderUnavailableException e) {
            countDropped(DropCause.PROVIDER_SEND, 1);
            return false;
        }
        long elapsed = Math.max(0L, clock.monotonicNanos() - frame.receivedNanos());
        lock.lock();
        try {
            relayed++;
            relayInRing[ringNext] = elapsed;
            ringNext = (ringNext + 1) % RELAY_IN_RING;
            ringSize = Math.min(RELAY_IN_RING, ringSize + 1);
        } finally {
            lock.unlock();
        }
        metrics.relayIn(Duration.ofNanos(elapsed));
        return true;
    }

    /** 마지막 음성 활동 프레임 수신 시각(LAST_INPUT_FRAME). 없으면 null. */
    public Instant lastVoiceFrameAt() {
        lock.lock();
        try {
            return lastVoiceFrameAt;
        } finally {
            lock.unlock();
        }
    }

    /** relayIn 표본의 p95(밀리초)를 계산하고 링을 비운다. 표본이 없으면 null. */
    public Long drainRelayInP95Millis() {
        long[] samples;
        lock.lock();
        try {
            if (ringSize == 0) {
                return null;
            }
            samples = Arrays.copyOf(relayInRing, ringSize);
            ringSize = 0;
            ringNext = 0;
        } finally {
            lock.unlock();
        }
        Arrays.sort(samples);
        int index = (int) Math.ceil(samples.length * 0.95) - 1;
        return TimeUnit.NANOSECONDS.toMillis(samples[Math.max(0, index)]);
    }

    /** 버린 프레임 수(누적). */
    public long droppedFrames() {
        lock.lock();
        try {
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    /** 보낸 프레임 수(누적). */
    public long relayedFrames() {
        lock.lock();
        try {
            return relayed;
        } finally {
            lock.unlock();
        }
    }

    /** 대기열 길이. */
    public int queued() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    private void countDropped(DropCause cause, long count) {
        if (count <= 0) {
            return;
        }
        dropped += count;
        metrics.inputFramesDropped(cause.name(), count);
    }

    /** PCM16LE 평균 절대 진폭이 임계값 이상인가. */
    static boolean isVoice(byte[] pcm) {
        long sum = 0;
        int samples = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += Math.abs(sample);
        }
        return samples > 0 && sum / samples >= VOICE_ENERGY_THRESHOLD;
    }
}
