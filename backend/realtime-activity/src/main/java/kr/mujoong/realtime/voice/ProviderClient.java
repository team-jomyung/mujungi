package kr.mujoong.realtime.voice;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.config.RealtimeProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 공급자 WSS 클라이언트 (U1 3.5 Step 25 · J110 · BR7.2 · RD-5 · SD-15 · SD-20 · contracts/ai-io.md 2 · 3절).
 *
 * <p>SDK 없이 JDK {@link WebSocket}으로 선 형식(텍스트 프레임 JSON 한 개 = 메시지 한 개, 공통 필드 type)을 주고받는다. 키는 환경 변수
 * {@code PROVIDER_API_KEY}이고 {@code Authorization: Bearer} 헤더로만 보낸다(값을 로그 · 예외 메시지에 쓰지 않는다). 요청 본문은
 * {@link ProviderRequestFactory}의 레코드만 직렬화하고, 요청 · 이벤트 본문 · 전사를 로그에 쓰지 않는다. 프록시는 JVM 기본 선택기를
 * 따른다(루프백은 기본 nonProxyHosts로 직결). 공급자 클라이언트에 의존하는 패키지는 voice 하나다(ArchUnit ⑥).
 */
@Component
@SingleInstanceAssumption(si = "SI-9",
        reason = "세션마다 공급자 WSS 연결을 이 프로세스에서 열어 쥐므로 세션을 다른 인스턴스로 옮길 수 없다")
public class ProviderClient implements ProviderPort, DisposableBean {

    /** 공급자 키 환경 변수 이름. */
    public static final String API_KEY = "PROVIDER_API_KEY";

    private final URI url;
    private final String apiKey;
    private final JsonCodec json;
    private final Duration sendTimeout;
    private final ExecutorService executor;
    private final HttpClient http;

    @Autowired
    public ProviderClient(RealtimeProperties properties, Environment environment, JsonCodec json) {
        this(properties.provider().url(), environment.getProperty(API_KEY), json, properties.provider().connectTimeout());
    }

    public ProviderClient(URI url, String apiKey, JsonCodec json, Duration sendTimeout) {
        this.url = url;
        this.apiKey = apiKey;
        this.json = Objects.requireNonNull(json, "json");
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpClient.Builder builder = HttpClient.newBuilder().executor(executor);
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        this.http = builder.build();
    }

    @Override
    public URI endpoint() {
        return url;
    }

    @Override
    public ProviderSession open(Listener listener, Duration timeout)
            throws ProviderUnavailableException, InterruptedException {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(timeout, "timeout");
        if (url == null || apiKey == null || apiKey.isBlank()) {
            throw new ProviderUnavailableException(ProviderUnavailableException.Reason.CONNECT_FAILED);
        }
        Reader reader = new Reader(listener, json);
        CompletableFuture<WebSocket> future = http.newWebSocketBuilder()
                .connectTimeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .buildAsync(url, reader);
        try {
            WebSocket socket = future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return new Session(socket, reader, json, sendTimeout);
        } catch (TimeoutException e) {
            abandon(future);
            throw new ProviderUnavailableException(ProviderUnavailableException.Reason.TIMEOUT);
        } catch (ExecutionException e) {
            throw new ProviderUnavailableException(ProviderUnavailableException.Reason.CONNECT_FAILED, e.getCause());
        } catch (InterruptedException e) {
            abandon(future);
            throw e;
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    /** 늦게 열린 연결은 바로 끊는다. */
    private static void abandon(CompletableFuture<WebSocket> future) {
        future.cancel(true);
        future.thenAccept(WebSocket::abort);
    }

    /** 수신 콜백. 텍스트 조각을 모아 메시지 하나를 해석하고 다음 한 건을 요청한다(리스너가 기다리면 배압이 TCP로 전해진다). */
    static final class Reader implements WebSocket.Listener {

        private final Listener listener;
        private final JsonCodec json;
        private final StringBuilder text = new StringBuilder();
        private final AtomicBoolean closed = new AtomicBoolean();

        Reader(Listener listener, JsonCodec json) {
            this.listener = listener;
            this.json = json;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                ProviderEvent event = parse(json, message);
                if (event instanceof ProviderEvent.Closed) {
                    signalClosed();
                } else if (event != null) {
                    listener.onEvent(event);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            signalClosed();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            signalClosed();
        }

        void signalClosed() {
            if (closed.compareAndSet(false, true)) {
                listener.onEvent(new ProviderEvent.Closed());
            }
        }
    }

    /**
     * 받은 메시지 모양(필드는 type별로 일부만 쓴다). U3: 확정 전사의 선택 필드 confidence(편차 (aa)) — 숫자가 아닌 값이 와도 전사를 잃지
     * 않게 타입을 고정하지 않고 받아 {@link #confidence(Object)}가 거른다.
     */
    record Inbound(String type, String providerSessionId, String itemId, String delta, String transcript,
            String responseId, String audio, String code, Object confidence) {
    }

    /** 선 형식 → 앱 이벤트. 모르는 type · 깨진 본문은 무시(null)한다. */
    static ProviderEvent parse(JsonCodec json, String message) {
        Inbound inbound;
        try {
            inbound = json.read(message, Inbound.class);
        } catch (RuntimeException e) {
            return null;
        }
        if (inbound == null || inbound.type() == null) {
            return null;
        }
        try {
            return switch (inbound.type()) {
                case "session.started" -> new ProviderEvent.Started(blankToNull(inbound.providerSessionId()));
                case "session.input_audio.speech_stopped" -> new ProviderEvent.SpeechStopped();
                case "session.input_transcript.delta" ->
                    new ProviderEvent.InputTranscriptDelta(inbound.itemId(), nullToEmpty(inbound.delta()));
                case "session.input_transcript.done" -> new ProviderEvent.InputTranscriptDone(inbound.itemId(),
                        nullToEmpty(inbound.transcript()), confidence(inbound.confidence()));
                case "session.output_transcript.delta" ->
                    new ProviderEvent.OutputTranscriptDelta(inbound.responseId(), nullToEmpty(inbound.delta()));
                case "session.output_audio.delta" -> new ProviderEvent.OutputAudioDelta(inbound.responseId(),
                        Base64.getDecoder().decode(nullToEmpty(inbound.audio())));
                case "session.output.done" -> new ProviderEvent.OutputDone(inbound.responseId());
                case "session.error" -> new ProviderEvent.Error(nullToEmpty(inbound.code()));
                case "session.closed" -> new ProviderEvent.Closed();
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 숫자가 아니거나 0 ~ 1 밖이면 없는 것으로 본다(U3-J17: 값이 없으면 불확실로 보지 않는다). */
    static Double confidence(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double confidence = number.doubleValue();
        return Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0 ? null : confidence;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 열린 공급자 세션. 송신은 잠금으로 한 번에 하나(JDK WebSocket은 앞 송신이 끝나야 다음을 받는다). */
    static final class Session implements ProviderSession {

        private final WebSocket socket;
        private final Reader reader;
        private final JsonCodec json;
        private final Duration sendTimeout;
        private final ReentrantLock sendLock = new ReentrantLock();
        private final AtomicBoolean closed = new AtomicBoolean();

        Session(WebSocket socket, Reader reader, JsonCodec json, Duration sendTimeout) {
            this.socket = socket;
            this.reader = reader;
            this.json = json;
            this.sendTimeout = sendTimeout;
        }

        @Override
        public void start(ProviderRequestFactory.SessionStart request) throws ProviderUnavailableException {
            send(json.write(Objects.requireNonNull(request, "request")));
        }

        @Override
        public void sendInputAudio(ProviderRequestFactory.InputAudioAppend request)
                throws ProviderUnavailableException {
            send(json.write(Objects.requireNonNull(request, "request")));
        }

        @Override
        public void cancelResponse(ProviderRequestFactory.ResponseCancel request) throws ProviderUnavailableException {
            send(json.write(Objects.requireNonNull(request, "request")));
        }

        @Override
        public void correctInstructions(ProviderRequestFactory.InstructionsCorrect request)
                throws ProviderUnavailableException {
            send(json.write(Objects.requireNonNull(request, "request")));
        }

        @Override
        public void createResponse(ProviderRequestFactory.ResponseCreate request) throws ProviderUnavailableException {
            send(json.write(Objects.requireNonNull(request, "request")));
        }

        @Override
        public void close(ProviderRequestFactory.SessionClose request) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                sendRaw(json.write(request));
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "")
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ProviderUnavailableException | ExecutionException | TimeoutException | RuntimeException e) {
                // 정리 경로: 닫기 실패는 끊기로 대신한다
            } finally {
                socket.abort();
                reader.signalClosed();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                socket.abort();
                reader.signalClosed();
            }
        }

        @Override
        public boolean isOpen() {
            return !closed.get() && !socket.isOutputClosed() && !socket.isInputClosed();
        }

        private void send(String text) throws ProviderUnavailableException {
            if (!isOpen()) {
                throw new ProviderUnavailableException(ProviderUnavailableException.Reason.CLOSED);
            }
            sendRaw(text);
        }

        private void sendRaw(String text) throws ProviderUnavailableException {
            sendLock.lock();
            try {
                socket.sendText(text, true).get(Math.max(1L, sendTimeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderUnavailableException(ProviderUnavailableException.Reason.SEND_FAILED, e);
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                throw new ProviderUnavailableException(ProviderUnavailableException.Reason.SEND_FAILED, e);
            } finally {
                sendLock.unlock();
            }
        }
    }
}
