package kr.mujoong.testsupport.device;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.Ack;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.Heartbeat;
import kr.mujoong.contract.ws.message.Hello;
import kr.mujoong.contract.ws.message.HelloOk;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;

/**
 * 기기 WSS 시뮬레이터 (U1 3.5 Step 30 · BR3.6 · BR3.7 · BR4.8 · BR6.6 ~ 6.12 · OD-8 · J143).
 *
 * <p>JDK WebSocket으로 Origin · 기기 쿠키를 실어 연결하고, 받은 텍스트 봉투와 0x02 출력 프레임 바이트를 기록한다. HELLO · HEARTBEAT(송신
 * 직전 제어 봉투 sentAt을 채우고 시계 오프셋을 주입할 수 있음) · ACK · DEVICE_READY · PLAYBACK_MARK · VOICE_SESSION_CLOSED ·
 * DEVICE_STATE · 원문 텍스트 · 0x01 입력 프레임을 보낼 수 있다. HEARTBEAT를 멈추거나 열린 채 무음으로 두는 것은 시험이 보내지 않는 것으로
 * 흉내 낸다. 기기 토큰 값은 문자열 표현 · 예외 메시지에 쓰지 않는다.
 */
public final class DeviceSimulatorClient implements AutoCloseable {

    private static final JsonCodec JSON = JsonCodec.shared();

    private final WebSocket socket;
    private final Recorder recorder;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong inputSeq = new AtomicLong();
    private final AtomicLong hbSeq = new AtomicLong();
    private volatile Duration clockSkew = Duration.ZERO;

    private DeviceSimulatorClient(WebSocket socket, Recorder recorder) {
        this.socket = socket;
        this.recorder = recorder;
    }

    /** 연결 결과: 열림 또는 업그레이드 실패(HTTP 상태). */
    public sealed interface Connection {
    }

    /** 열렸다. */
    public record Opened(DeviceSimulatorClient client) implements Connection {
    }

    /** 업그레이드가 거절됐다(예: Origin 403). */
    public record Refused(Throwable cause) implements Connection {
    }

    /**
     * 연결한다.
     *
     * @param origin Origin 헤더(null이면 보내지 않는다)
     * @param deviceToken 기기 쿠키 값(null이면 쿠키 없음)
     */
    public static Connection connect(URI uri, String origin, String deviceToken, Duration timeout) {
        Recorder recorder = new Recorder();
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(timeout);
        if (origin != null) {
            builder.header("Origin", origin);
        }
        if (deviceToken != null) {
            builder.header("Cookie", "MUJOONG_DEVICE=" + deviceToken);
        }
        CompletableFuture<WebSocket> future = builder.buildAsync(uri, recorder);
        try {
            return new Opened(new DeviceSimulatorClient(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS), recorder));
        } catch (ExecutionException e) {
            return new Refused(e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Refused(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Refused(e);
        }
    }

    /** 연결이 열린다고 단정하고 연다. */
    public static DeviceSimulatorClient open(URI uri, String origin, String deviceToken, Duration timeout) {
        Connection connection = connect(uri, origin, deviceToken, timeout);
        if (connection instanceof Opened opened) {
            return opened.client();
        }
        throw new IllegalStateException("기기 WSS 연결이 열리지 않았다: " + ((Refused) connection).cause());
    }

    /** 이후 보내는 봉투 sentAt에 더할 시계 오프셋(기기 시계 − 서버 시계). */
    public DeviceSimulatorClient clockSkew(Duration value) {
        this.clockSkew = Objects.requireNonNull(value, "clockSkew");
        return this;
    }

    /** HELLO를 보내고 HELLO_OK를 기다린다. */
    public HelloOk hello(Duration timeout) {
        return hello(timeout, null);
    }

    /** HELLO(이어가기 요청 포함, U3)를 보내고 HELLO_OK를 기다린다. */
    public HelloOk hello(Duration timeout, UUID resumeSessionId) {
        sendHello(resumeSessionId);
        return (HelloOk) await(WsMessageType.HELLO_OK, timeout).payload();
    }

    /** HELLO만 보낸다. */
    public void sendHello() {
        sendHello(null);
    }

    /** HELLO만 보낸다(이어가기 요청은 null이면 없음). */
    public void sendHello(UUID resumeSessionId) {
        send(new Hello("u1-sim-1.0", List.of(HelloOk.AUDIO_PCM16LE_24K_MONO),
                resumeSessionId == null ? null : new Hello.Resume(resumeSessionId)), null, null);
    }

    /** HEARTBEAT(봉투 sentAt = 지금 + 시계 오프셋). 보낸 hbSeq. */
    public long heartbeat(Long lastRttMs) {
        long value = hbSeq.incrementAndGet();
        send(new Heartbeat(value, lastRttMs), null, null);
        return value;
    }

    /** sentAt 없는 HEARTBEAT(원문). */
    public void heartbeatWithoutSentAt(Long lastRttMs) {
        long value = hbSeq.incrementAndGet();
        sendRaw("{\"type\":\"HEARTBEAT\",\"seq\":" + seq.incrementAndGet() + ",\"payload\":{\"hbSeq\":" + value
                + (lastRttMs == null ? "" : ",\"lastRttMs\":" + lastRttMs) + "}}");
    }

    /** ACK(ackOf = 서버 명령 opId). */
    public void ack(UUID ackOf, UUID sessionId) {
        send(new Ack(ackOf), null, sessionId);
    }

    /** DEVICE_READY. */
    public void deviceReady(UUID sessionId, boolean micOk, boolean playerOk) {
        send(new DeviceReady(micOk ? DeviceReady.Availability.OK : DeviceReady.Availability.UNAVAILABLE,
                playerOk ? DeviceReady.Availability.OK : DeviceReady.Availability.UNAVAILABLE, DeviceReady.Prefetch.OK),
                null, sessionId);
    }

    /** PLAYBACK_MARK(observedAt = 지금 + 시계 오프셋). */
    public void playbackMark(UUID sessionId, UUID playbackId, PlaybackMark.Status status) {
        PlaybackMark.StopCause cause = status == PlaybackMark.Status.STOPPED ? PlaybackMark.StopCause.STOP_OUTPUT : null;
        playbackMark(sessionId, playbackId, status, null, cause);
    }

    /** PLAYBACK_MARK(재생 범위 · 멈춤 원인 지정, U3 끼어들기 · 진행 표식). */
    public void playbackMark(UUID sessionId, UUID playbackId, PlaybackMark.Status status, PlaybackMark.PlayedRange range,
            PlaybackMark.StopCause cause) {
        send(new PlaybackMark(playbackId, status, range, deviceNow(), cause, null), null, sessionId);
    }

    /** VOICE_SESSION_CLOSED. */
    public void voiceSessionClosed(UUID sessionId) {
        Instant now = deviceNow();
        send(new VoiceSessionClosed(now, now), null, sessionId);
    }

    /** DEVICE_STATE(모두 정상). */
    public void deviceState() {
        deviceState(new DeviceState(DeviceState.Mic.OK, DeviceState.Player.OK, DeviceState.Visibility.VISIBLE,
                DeviceState.WakeLock.HELD));
    }

    /** DEVICE_STATE(바뀐 필드만 실어도 된다, U3). */
    public void deviceState(DeviceState state) {
        send(state, null, null);
    }

    /** 0x01 입력 프레임 하나(960B PCM). */
    public void sendAudio(byte[] pcm) {
        sendBinary(AudioFrameCodec.encodeInput(inputSeq.incrementAndGet(), pcm));
    }

    /** 원문 텍스트. */
    public void sendRaw(String text) {
        join(socket.sendText(text, true));
    }

    /** 원문 바이너리. */
    public void sendBinary(byte[] bytes) {
        join(socket.sendBinary(ByteBuffer.wrap(bytes), true));
    }

    /** 받은 봉투 중 조건에 맞는 첫 것을 기다린다. */
    public WsEnvelope await(Predicate<WsEnvelope> condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<WsEnvelope> found = recorder.envelopes.stream().filter(condition).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleep(5);
        }
        throw new AssertionError("기기가 기대한 메시지를 받지 못했다(제한 " + timeout + ")");
    }

    /** 그 type의 첫 봉투를 기다린다. */
    public WsEnvelope await(WsMessageType type, Duration timeout) {
        return await(envelope -> envelope.messageType() == type, timeout);
    }

    /** 그 type의 봉투가 {@code count}개 올 때까지 기다린다. */
    public List<WsEnvelope> awaitCount(WsMessageType type, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (received(type).size() < count && System.nanoTime() < deadline) {
            sleep(5);
        }
        return received(type);
    }

    /** 받은 봉투들. */
    public List<WsEnvelope> received() {
        return List.copyOf(recorder.envelopes);
    }

    /** 그 type의 받은 봉투들. */
    public List<WsEnvelope> received(WsMessageType type) {
        return recorder.envelopes.stream().filter(envelope -> envelope.messageType() == type).toList();
    }

    /** 받은 0x02 출력 프레임의 PCM 바이트(순서대로 이어 붙임). */
    public byte[] outputPcm() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] frame : recorder.binary) {
            out.writeBytes(AudioFrameCodec.decodeFromServer(frame).pcm());
        }
        return out.toByteArray();
    }

    /** 받은 0x02 프레임 수. */
    public int outputFrames() {
        return recorder.binary.size();
    }

    /** 서버가 닫은 코드를 기다린다. 제한 안에 닫히지 않으면 빈 값. */
    public Optional<Integer> awaitClose(Duration timeout) {
        try {
            return Optional.of(recorder.closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** 닫혔는가. */
    public boolean isClosed() {
        return recorder.closed.isDone() || socket.isInputClosed();
    }

    /** 기기가 닫는다(1000 「끄기」 · 1001 페이지 이탈 등). */
    public void closeWith(int code) {
        join(socket.sendClose(code, ""));
    }

    @Override
    public void close() {
        socket.abort();
    }

    @Override
    public String toString() {
        return "DeviceSimulatorClient[received=" + recorder.envelopes.size() + "]";
    }

    private void send(WsPayload payload, UUID opId, UUID sessionId) {
        sendRaw(JSON.encodeWs(WsEnvelope.of(payload, seq.incrementAndGet(), deviceNow(), opId, sessionId)));
    }

    private Instant deviceNow() {
        return Instant.now().plus(clockSkew);
    }

    private static void join(CompletableFuture<WebSocket> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("기기 시뮬레이터 송신 실패", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 수신 기록. */
    private static final class Recorder implements WebSocket.Listener {

        private final List<WsEnvelope> envelopes = new CopyOnWriteArrayList<>();
        private final List<byte[]> binary = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        private final List<byte[]> binaryParts = new ArrayList<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                WsEnvelope.Decoded decoded = JSON.decodeWs(text.toString());
                text.setLength(0);
                if (decoded.isOk()) {
                    envelopes.add(decoded.envelope());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] part = new byte[data.remaining()];
            data.get(part);
            binaryParts.add(part);
            if (last) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                binaryParts.forEach(out::writeBytes);
                binaryParts.clear();
                binary.add(out.toByteArray());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(1006);
        }
    }
}
