package kr.mujoong.realtime.practiceflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;

/**
 * 시험용 가상 기기 연결(실음성 연습 하니스). 운영 기기처럼 명령에 ACK하고, START_SESSION 뒤 DEVICE_READY를 보내고, 입력 프레임을 20ms마다
 * 보내며(아이가 말하지 않을 때는 무음), 받은 출력 묶음 · 승인 음성을 차례로 그 길이만큼 실제 시간으로 재생한 것으로 보고 PLAYBACK_MARK를 보낸다.
 * 기기 → 서버 메시지는 한 스레드로 순서대로 일반 편지함에 넣는다(운영 기기 WSS 처리기와 같은 입구). 기기 반향 · 끼어들기는 흉내 내지 않는다 — 아이는
 * 기기 출력이 조용할 때만 말한다.
 */
final class PracticeVoiceDevice implements DeviceLink {

    /** 24kHz PCM16 모노의 ms당 바이트. */
    static final int BYTES_PER_MS = 48;
    /** 입력 프레임 한 개(20ms) 바이트. */
    static final int FRAME_BYTES = 960;
    /** 기기 음성 레인 예산(20초 분량, 운영 연결 송신기와 같은 상한). */
    static final long AUDIO_BUDGET_BYTES = 960_000L;
    private static final long FRAME_NANOS = 20_000_000L;
    private static final byte[] SILENCE = new byte[FRAME_BYTES];

    /** 아이 말 한 줄의 PCM(20ms 프레임)과 소리가 있는 프레임 표시. */
    record Clip(List<byte[]> frames, boolean[] voiced, int firstVoiced, int lastVoiced) {

        /** 소리가 있다고 보는 프레임 RMS 문턱(PCM16 값, 합성 음성 기준 하니스 값). */
        static final double VOICED_RMS = 300.0;

        static Clip of(byte[] pcm) {
            List<byte[]> frames = new ArrayList<>();
            for (int offset = 0; offset < pcm.length; offset += FRAME_BYTES) {
                byte[] frame = new byte[FRAME_BYTES];
                System.arraycopy(pcm, offset, frame, 0, Math.min(FRAME_BYTES, pcm.length - offset));
                frames.add(frame);
            }
            boolean[] voiced = new boolean[frames.size()];
            int first = -1;
            int last = -1;
            for (int i = 0; i < frames.size(); i++) {
                voiced[i] = rms(frames.get(i)) >= VOICED_RMS;
                if (voiced[i]) {
                    if (first < 0) {
                        first = i;
                    }
                    last = i;
                }
            }
            return new Clip(List.copyOf(frames), voiced, first, last);
        }

        long audioMs() {
            return frames.size() * 20L;
        }

        private static double rms(byte[] frame) {
            double sum = 0;
            int samples = frame.length / 2;
            for (int i = 0; i + 1 < frame.length; i += 2) {
                int sample = (short) ((frame[i] & 0xFF) | (frame[i + 1] << 8));
                sum += (double) sample * sample;
            }
            return samples == 0 ? 0 : Math.sqrt(sum / samples);
        }
    }

    /** 아이 한 줄의 송신 상태(첫 · 마지막 소리 프레임을 보낸 시각). */
    static final class Spoken {
        private final Clip clip;
        private volatile int sent;
        private volatile long firstFrameNanos = -1;
        private volatile long firstVoicedNanos = -1;
        private volatile long lastVoicedNanos = -1;
        private volatile long lastFrameNanos = -1;
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        Spoken(Clip clip) {
            this.clip = clip;
        }

        long firstVoicedNanos() {
            return firstVoicedNanos;
        }

        long firstFrameNanos() {
            return firstFrameNanos;
        }

        /** 마지막 소리 프레임이 끝난 시각(보낸 시각 + 20ms). */
        long voicedEndNanos() {
            return lastVoicedNanos < 0 ? -1 : lastVoicedNanos + FRAME_NANOS;
        }

        long lastFrameNanos() {
            return lastFrameNanos;
        }

        void await() {
            try {
                done.get(clip.audioMs() + 30_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException late) {
                // 기기가 닫혔다(남은 프레임은 보내지 않는다)
            }
        }
    }

    private record LocalItem(UUID playbackId, String code, long durationMs) {
    }

    /** 재생 대기 항목(출력 묶음 또는 승인 음성)과 넣을 때의 정지 세대. 그 뒤 STOP_OUTPUT · CLOSE_TRANSPORT가 오면 재생하지 않는다. */
    private record Queued(Object item, long stopEpoch) {

        UUID playbackId() {
            return item instanceof OutputBundle bundle ? bundle.playbackId() : ((LocalItem) item).playbackId();
        }
    }

    private final UUID connectionId = UUID.randomUUID();
    private final UUID deviceId;
    private final MujoongClock clock;
    private final PracticeVoiceFacts facts;
    private final Map<String, Long> approvedAudioMs;
    private final Map<String, String> approvedTexts;
    private final ExecutorService inbound = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("voice-device-inbound").factory());
    private final LinkedBlockingDeque<Queued> queue = new LinkedBlockingDeque<>();
    private final ArrayDeque<Integer> childFrames = new ArrayDeque<>();
    private volatile SessionExecutor executor;
    private volatile boolean open = true;
    private volatile boolean micOn;
    private volatile boolean busy;
    private volatile long quietSinceNanos = System.nanoTime();
    /** STOP_OUTPUT · CLOSE_TRANSPORT마다 올린다. 재생 중 · 대기 중 항목은 자기 세대가 지나면 멈춘다. */
    private volatile long stopEpoch;
    private volatile Spoken speaking;
    private UUID boundSession;
    private long reserved;

    /**
     * @param approvedAudioMs 승인 음성 코드 → 실제 길이(ms, 승인 음성 기록의 길이)
     * @param approvedTexts 승인 음성 코드 → 문구(재생 사실의 '들린 말')
     */
    PracticeVoiceDevice(UUID deviceId, MujoongClock clock, PracticeVoiceFacts facts, Map<String, Long> approvedAudioMs,
            Map<String, String> approvedTexts) {
        this.deviceId = deviceId;
        this.clock = clock;
        this.facts = facts;
        this.approvedAudioMs = Map.copyOf(approvedAudioMs);
        this.approvedTexts = Map.copyOf(approvedTexts);
    }

    void attach(SessionExecutor value) {
        this.executor = value;
        Thread.ofVirtual().name("voice-device-speaker").start(this::speakerLoop);
    }

    /** 출력이 이만큼 조용했나(재생 · 대기 묶음 없음). */
    boolean quietFor(Duration gap) {
        return !busy && queue.isEmpty() && System.nanoTime() - quietSinceNanos >= gap.toNanos();
    }

    boolean childSpeaking() {
        return speaking != null;
    }

    /** 아이 한 줄을 마이크에 넣는다(다음 20ms 프레임부터 나간다). */
    Spoken say(Clip clip) {
        Spoken spoken = new Spoken(clip);
        synchronized (childFrames) {
            for (int i = 0; i < clip.frames().size(); i++) {
                childFrames.add(i);
            }
            speaking = spoken;
        }
        if (clip.frames().isEmpty() || !micOn) {
            speaking = null;
            spoken.done.complete(null);
        }
        return spoken;
    }

    @Override
    public UUID connectionId() {
        return connectionId;
    }

    @Override
    public UUID deviceId() {
        return deviceId;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean sendControl(WsPayload payload, UUID opId, UUID sessionId) {
        if (!open) {
            return false;
        }
        if (opId != null) {
            post(new SessionMailbox.DeviceAck(connectionId, opId, clock.now()));
        }
        switch (payload) {
            case StartSession ignored -> {
                post(new SessionMailbox.DeviceReadyReceived(connectionId,
                        new DeviceReady(DeviceReady.Availability.OK, DeviceReady.Availability.OK, DeviceReady.Prefetch.OK), clock.now()));
                startMic();
            }
            case PlayApprovedContent play -> {
                long duration = approvedAudioMs.getOrDefault(play.contentCode(), 2_000L);
                facts.localQueued(play.playbackId(), play.contentCode(), approvedTexts.get(play.contentCode()), duration);
                queue.add(new Queued(new LocalItem(play.playbackId(), play.contentCode(), duration), stopEpoch));
            }
            case StopOutput ignored -> {
                stopEpoch++;
                dropQueued("STOP_OUTPUT");
            }
            case CloseTransport ignored -> {
                micOn = false;
                stopEpoch++;
                dropQueued("CLOSE_TRANSPORT");
                post(new SessionMailbox.VoiceClosed(connectionId, new VoiceSessionClosed(clock.now(), clock.now()), clock.now()));
            }
            case SessionState state -> facts.deviceView(state.view() == null ? "null" : state.view().name());
            default -> {
                // 그 밖의 제어 메시지(VIEW 등)는 재생에 영향이 없다
            }
        }
        return true;
    }

    @Override
    public synchronized Optional<AudioReservation> reserveAudio(int bytes) {
        if (!open || reserved + bytes > AUDIO_BUDGET_BYTES) {
            return Optional.empty();
        }
        reserved += bytes;
        return Optional.of(new AudioReservation() {
            private boolean released;

            @Override
            public int bytes() {
                return bytes;
            }

            @Override
            public void release() {
                synchronized (PracticeVoiceDevice.this) {
                    if (!released) {
                        released = true;
                        reserved -= bytes;
                    }
                }
            }
        });
    }

    @Override
    public boolean sendOutput(OutputBundle bundle) {
        if (!open) {
            bundle.reservation().release();
            return false;
        }
        facts.bundleQueued(bundle.playbackId(), bundle.totalBytes());
        queue.add(new Queued(bundle, stopEpoch));
        return true;
    }

    @Override
    public void clearAudio() {
        List<Queued> kept = new ArrayList<>();
        Queued queued;
        while ((queued = queue.poll()) != null) {
            if (queued.item() instanceof OutputBundle bundle) {
                bundle.reservation().release();
                facts.playEnded(bundle.playbackId(), "CLEARED_BEFORE_PLAY", 0);
            } else {
                kept.add(queued);
            }
        }
        queue.addAll(kept);
    }

    @Override
    public void close(CloseCode code) {
        open = false;
        micOn = false;
        stopEpoch++;
        Spoken current = speaking;
        if (current != null) {
            current.done.complete(null);
        }
        inbound.shutdown();
    }

    @Override
    public Instant lastMessageAt() {
        // 가상 기기는 하트비트처럼 늘 살아 있다
        return clock.now();
    }

    @Override
    public Long clockOffsetMs() {
        return 0L;
    }

    @Override
    public Long rttMs() {
        return 20L;
    }

    @Override
    public synchronized void bindSession(UUID sessionId) {
        this.boundSession = sessionId;
    }

    @Override
    public synchronized UUID boundSessionId() {
        return boundSession;
    }

    @Override
    public synchronized boolean unbindSessionIf(UUID expected) {
        if (expected == null || !expected.equals(boundSession)) {
            return false;
        }
        boundSession = null;
        return true;
    }

    private void dropQueued(String why) {
        Queued queued;
        while ((queued = queue.poll()) != null) {
            drop(queued, "DROPPED_" + why);
        }
    }

    private void drop(Queued queued, String end) {
        if (queued.item() instanceof OutputBundle bundle) {
            bundle.reservation().release();
        }
        facts.playEnded(queued.playbackId(), end, 0);
    }

    private void post(SessionMailbox.Message message) {
        if (inbound.isShutdown()) {
            return;
        }
        try {
            inbound.execute(() -> {
                SessionExecutor target = executor;
                if (target != null && !target.isTerminated() && !target.mailbox().offerGeneral(message)) {
                    facts.notice("세션 편지함이 가득 차 기기 메시지 하나를 버렸다(" + message.getClass().getSimpleName() + ")");
                }
            });
        } catch (RejectedExecutionException closed) {
            // 기기가 닫혔다
        }
    }

    private void mark(UUID playbackId, PlaybackMark.Status status, PlaybackMark.StopCause cause) {
        Instant at = clock.now();
        if (status == PlaybackMark.Status.STARTED) {
            // 이 시각이 승인 음성 확정 행의 시작 · 끝이 된다(재생 묶음과 들린 글을 잇는 열쇠)
            at = facts.markInstant(at);
            facts.playStarted(playbackId, at);
        }
        post(new SessionMailbox.PlaybackMarkReceived(connectionId, new PlaybackMark(playbackId, status, null, at, cause, null), at));
    }

    private void speakerLoop() {
        while (open) {
            Queued queued;
            try {
                queued = queue.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (queued == null) {
                continue;
            }
            busy = true;
            try {
                if (queued.stopEpoch() != stopEpoch) {
                    // 꺼낸 사이에 정지 명령이 왔다(대기열 비우기를 지나쳤다): 재생하지 않는다
                    drop(queued, "DROPPED_STOPPED_BEFORE_PLAY");
                } else if (queued.item() instanceof OutputBundle bundle) {
                    playBundle(bundle, queued.stopEpoch());
                } else if (queued.item() instanceof LocalItem local) {
                    play(local.playbackId(), local.durationMs(), queued.stopEpoch());
                }
            } finally {
                quietSinceNanos = System.nanoTime();
                busy = false;
            }
        }
    }

    /** 운영 송신기처럼 프레임마다 세대를 확인해 보낸 뒤(보낸 프레임만 재생), 그 길이만큼 재생한다. */
    private void playBundle(OutputBundle bundle, long epoch) {
        int delivered = 0;
        long bytes = 0;
        try {
            for (byte[] frame : bundle.frames()) {
                if (!bundle.stillCurrent().getAsBoolean()) {
                    break;
                }
                bundle.onFrameSent().accept(frame);
                if (delivered == 0) {
                    bundle.onFirstFrameSent().accept(clock.now());
                }
                delivered++;
                bytes += frame.length;
            }
        } finally {
            bundle.reservation().release();
        }
        if (delivered == 0) {
            facts.playEnded(bundle.playbackId(), "NOT_DELIVERED_GENERATION_CHANGED", 0);
            return;
        }
        play(bundle.playbackId(), bytes / BYTES_PER_MS, epoch);
    }

    /** 재생 시간만큼 기다린다. 그사이 정지 세대가 바뀌면(STOP_OUTPUT · CLOSE_TRANSPORT) 멈춘 것으로 보고한다. */
    private void play(UUID playbackId, long durationMs, long epoch) {
        mark(playbackId, PlaybackMark.Status.STARTED, null);
        long started = System.nanoTime();
        long end = started + durationMs * 1_000_000L;
        while (open && stopEpoch == epoch && System.nanoTime() < end) {
            LockSupport.parkNanos(Math.min(10_000_000L, Math.max(1L, end - System.nanoTime())));
        }
        long played = (System.nanoTime() - started) / 1_000_000L;
        if (!open) {
            facts.playEnded(playbackId, "DEVICE_CLOSED", played);
        } else if (stopEpoch != epoch) {
            mark(playbackId, PlaybackMark.Status.STOPPED, micOn ? PlaybackMark.StopCause.STOP_OUTPUT : PlaybackMark.StopCause.CLOSE_TRANSPORT);
            facts.playEnded(playbackId, "STOPPED", played);
        } else {
            mark(playbackId, PlaybackMark.Status.COMPLETED, null);
            facts.playEnded(playbackId, "COMPLETED", durationMs);
        }
    }

    private void startMic() {
        if (micOn) {
            return;
        }
        micOn = true;
        Thread.ofVirtual().name("voice-device-mic").start(this::micLoop);
    }

    /** 20ms마다 프레임 하나(아이 말 또는 무음). 운영 기기처럼 DEVICE_READY 뒤부터 CLOSE_TRANSPORT까지 계속 보낸다. */
    private void micLoop() {
        long next = System.nanoTime();
        long seq = 0;
        while (micOn && open) {
            Integer index;
            Spoken current;
            boolean last;
            synchronized (childFrames) {
                index = childFrames.poll();
                current = speaking;
                last = index != null && childFrames.isEmpty();
            }
            byte[] frame = index == null || current == null ? SILENCE : current.clip.frames().get(index);
            boolean voiced = index != null && current != null && current.clip.voiced()[index];
            long now = System.nanoTime();
            SessionExecutor target = executor;
            if (target != null) {
                target.offerAudio(connectionId, ++seq, frame, now);
            }
            facts.frameOffered(voiced);
            if (index != null && current != null) {
                if (current.sent == 0) {
                    current.firstFrameNanos = now;
                }
                current.sent++;
                current.lastFrameNanos = now;
                if (voiced) {
                    if (current.firstVoicedNanos < 0) {
                        current.firstVoicedNanos = now;
                    }
                    current.lastVoicedNanos = now;
                }
                if (last) {
                    synchronized (childFrames) {
                        if (speaking == current) {
                            speaking = null;
                        }
                    }
                    current.done.complete(null);
                }
            }
            next += FRAME_NANOS;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            } else if (wait < -200_000_000L) {
                next = System.nanoTime();
            }
        }
        Spoken current = speaking;
        if (current != null) {
            current.done.complete(null);
        }
    }
}
