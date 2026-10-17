package kr.mujoong.testsupport.provider;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.json.JsonCodec;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 공급자 WS 서버 (U1 3.5 Step 30 · RD-5 · NFR4.2 · SD-20 · J131, U11 BR11.10 실호출 관측 형식).
 *
 * <p>루프백 무작위 포트에서 듣고, 받은 텍스트 메시지를 모두 기록한다(허용 목록 · 무변환 시험). 시나리오: 정상({@code session.start}에
 * {@code session.started}) · started 없음 · 지연 응답 · 연결 거부(듣지 않는 포트) · 연결 단계 무응답(TCP만 받고 업그레이드에 답하지 않음).
 * 시험은 {@link #emitTurn}으로 아이 전사 · AI 출력(전사 + 음성) 한 턴을, {@link #emit}으로 임의 이벤트를 흘린다. 선 형식은 실호출로 관측한
 * 것만 쓴다: 음성은 {@code session.output_audio.delta{delta}}(응답 id · 응답 끝 사건 없음 — 응답 경계는 조각 사이 무음), 오류는
 * {@code error{error{type, code, param}}}, 지원 목록 밖 type을 받으면 {@code error{invalid_request_error, invalid_value, param type}}으로
 * 답한다. 출력 전사 · 입력 전사 · 말 끝 사건의 이름은 관측되지 않아 기존 가정 이름을 임시로 쓴다(미확인). 받은
 * {@code input_audio.append} 음성은 바이트 해시로만 모은다(원음 보관 없음). 비밀 값(공급자 키)은 기록하지 않고 Authorization 헤더가
 * Bearer 형식인지만 남긴다.
 */
public final class FakeProviderWsServer implements AutoCloseable {

    /** 응답 시나리오. */
    public enum Scenario {
        /** {@code session.start}에 곧바로 {@code session.started}. */
        OK,
        /** {@code session.started}를 보내지 않는다. */
        NO_STARTED,
        /** {@link #startedDelay} 뒤에 {@code session.started}. */
        DELAYED
    }

    private static final JsonCodec JSON = JsonCodec.shared();
    /** 실호출 오류 문장에서 확인한 공급자 지원 type 전부(U11-a). 이 밖의 type은 오류로 답한다. */
    public static final java.util.Set<String> SUPPORTED_TYPES = java.util.Set.of("session.start", "session.update",
            "session.close", "session.input_audio.append", "session.input_audio.mute", "session.input_audio.unmute",
            "session.instructions.append", "session.thinking.append", "session.commentary.append",
            "session.output_audio.fixture.play", "response.create", "response.item.create", "decision.create",
            "decision.item.create", "decision.item.delete");

    private final Server server;
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final List<String> rawReceived = new CopyOnWriteArrayList<>();
    private final List<String> audioHashes = new CopyOnWriteArrayList<>();
    private final List<Boolean> bearerHeaders = new CopyOnWriteArrayList<>();
    private volatile Scenario scenario = Scenario.OK;
    private volatile Duration startedDelay = Duration.ZERO;
    private volatile WebSocket current;

    private FakeProviderWsServer() throws InterruptedException {
        this.server = new Server(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.setReuseAddr(true);
        server.setDaemon(true);
        server.start();
        if (!server.started.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("가짜 공급자 서버가 뜨지 않았다");
        }
    }

    /** 서버를 띄운다. */
    public static FakeProviderWsServer start() {
        try {
            return new FakeProviderWsServer();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 연결 주소. */
    public URI uri() {
        return URI.create("ws://127.0.0.1:" + server.getPort() + "/fake-provider");
    }

    /** 아무것도 듣지 않는 루프백 주소(연결 거부 시나리오). */
    public static URI refusingUri() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("ws://127.0.0.1:" + socket.getLocalPort() + "/fake-provider");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** TCP만 받고 업그레이드에 답하지 않는 서버(연결 단계 무응답 시나리오). 닫으면 멈춘다. */
    public static Silent silent() {
        return new Silent();
    }

    public FakeProviderWsServer scenario(Scenario value) {
        this.scenario = Objects.requireNonNull(value, "scenario");
        return this;
    }

    public FakeProviderWsServer startedDelay(Duration value) {
        this.startedDelay = Objects.requireNonNull(value, "startedDelay");
        return this;
    }

    /** 받은 메시지(JSON). */
    public List<JsonNode> received() {
        return List.copyOf(received);
    }

    /** 받은 메시지 원문(키 · 표식 검색용). */
    public List<String> rawReceived() {
        return List.copyOf(rawReceived);
    }

    /** 그 type의 받은 메시지. */
    public List<JsonNode> received(String type) {
        return received.stream().filter(node -> type.equals(text(node, "type"))).toList();
    }

    /** 받은 입력 음성 프레임의 SHA-256(순서대로). */
    public List<String> audioHashes() {
        return List.copyOf(audioHashes);
    }

    /** 연결마다 Authorization이 Bearer 형식이었는가. */
    public List<Boolean> bearerHeaders() {
        return List.copyOf(bearerHeaders);
    }

    /** 열린 연결 수. */
    public int openConnections() {
        return server.getConnections().size();
    }

    /** 그 type의 메시지가 {@code count}개 올 때까지 기다린다. */
    public List<JsonNode> await(String type, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (received(type).size() < count && System.nanoTime() < deadline) {
            sleep(10);
        }
        return received(type);
    }

    /** 지금 연결로 이벤트 하나. */
    public void emit(Object event) {
        WebSocket socket = current;
        if (socket == null || !socket.isOpen()) {
            throw new IllegalStateException("열린 공급자 연결이 없다");
        }
        socket.send(JSON.write(event));
    }

    /** 출력 음성 조각 하나({@code session.output_audio.delta{delta}}, 응답 id 없음). */
    public void emitAudio(byte[] pcm) {
        emit(new Event("session.output_audio.delta", null, Base64.getEncoder().encodeToString(pcm), null));
    }

    /** 오류 사건 하나({@code error{error{type, code, param}}}). message 원문은 담지 않는다. */
    public void emitError(String errorType, String code, String param) {
        emit(new ErrorEvent("error", new ErrorBody(errorType, code, "오류", param)));
    }

    /** 사용량 사건 하나({@code session.usage.updated{usage{seconds}}}). */
    public void emitUsage(double seconds) {
        emit(new UsageEvent("session.usage.updated", new UsageBody(seconds)));
    }

    /**
     * 한 턴: 아이 입력 전사(조각 · 확정) → 말 끝 → AI 출력 전사 · 음성 조각들. 응답 끝 사건은 없다(실호출에서 관측되지 않음) — 응답 경계는 조각 뒤의
     * 무음으로 드러난다.
     *
     * @param pcmChunks PCM16LE 24kHz 모노 조각들(짝수 바이트)
     */
    public void emitTurn(String itemId, String childText, String aiText, List<byte[]> pcmChunks) {
        emit(new Event("session.input_transcript.delta", itemId, childText, null));
        emit(new Event("session.input_audio.speech_stopped", null, null, null));
        emit(new Event("session.input_transcript.done", itemId, null, childText));
        emit(new Event("session.output_transcript.delta", null, aiText, null));
        for (byte[] chunk : pcmChunks) {
            emitAudio(chunk);
        }
    }

    /** 공급자 쪽에서 연결을 닫는다. */
    public void closeCurrent() {
        WebSocket socket = current;
        if (socket != null) {
            socket.close(1000);
        }
    }

    @Override
    public void close() {
        try {
            server.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 선 형식 이벤트(전사 · 음성 조각). 없는 필드는 빼고 쓴다. */
    public record Event(String type, String itemId, String delta, String transcript) {
    }

    /** {@code error} 사건. */
    public record ErrorEvent(String type, ErrorBody error) {
    }

    /** {@code error} 사건의 본문. */
    public record ErrorBody(String type, String code, String message, String param) {
    }

    /** {@code session.usage.updated} 사건. */
    public record UsageEvent(String type, UsageBody usage) {
    }

    /** 사용량 본문. */
    public record UsageBody(double seconds) {
    }

    /** {@code session.started} 사건. */
    public record StartedEvent(String type, SessionRef session) {
    }

    /** 사건의 {@code session} 객체. */
    public record SessionRef(String id) {
    }

    private void onText(WebSocket socket, String message) {
        rawReceived.add(message);
        JsonNode node = JSON.read(message, JsonNode.class);
        received.add(node);
        String type = text(node, "type");
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            socket.send(JSON.write(new ErrorEvent("error",
                    new ErrorBody("invalid_request_error", "invalid_value", "Invalid value", "type"))));
        } else if ("session.start".equals(type)) {
            answerStart(socket);
        } else if ("session.input_audio.append".equals(type)) {
            String audio = text(node, "audio");
            audioHashes.add(sha256(audio == null ? new byte[0] : Base64.getDecoder().decode(audio)));
        }
    }

    private void answerStart(WebSocket socket) {
        Scenario now = scenario;
        if (now == Scenario.NO_STARTED) {
            return;
        }
        StartedEvent started = new StartedEvent("session.started", new SessionRef("fake-provider-session"));
        if (now == Scenario.DELAYED) {
            Thread.ofVirtual().start(() -> {
                sleep(startedDelay.toMillis());
                if (socket.isOpen()) {
                    socket.send(JSON.write(started));
                }
            });
        } else {
            socket.send(JSON.write(started));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Server extends WebSocketServer {

        private final CountDownLatch started = new CountDownLatch(1);

        private Server(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket socket, ClientHandshake handshake) {
            String authorization = handshake.getFieldValue("Authorization");
            bearerHeaders.add(authorization != null && authorization.startsWith("Bearer ") && authorization.length() > 7);
            current = socket;
        }

        @Override
        public void onClose(WebSocket socket, int code, String reason, boolean remote) {
            if (current == socket) {
                current = null;
            }
        }

        @Override
        public void onMessage(WebSocket socket, String message) {
            onText(socket, message);
        }

        @Override
        public void onError(WebSocket socket, Exception error) {
            // 시험 서버: 연결 오류는 시험의 단언이 드러낸다
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }

    /** TCP 연결은 받되 아무 응답도 하지 않는 서버. */
    public static final class Silent implements AutoCloseable {

        private final ServerSocket socket;
        private final List<Socket> accepted = new ArrayList<>();
        private final Thread acceptor;

        private Silent() {
            try {
                socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            acceptor = Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        synchronized (accepted) {
                            accepted.add(client);
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        public URI uri() {
            return URI.create("ws://127.0.0.1:" + socket.getLocalPort() + "/fake-provider");
        }

        public int acceptedCount() {
            synchronized (accepted) {
                return accepted.size();
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
                synchronized (accepted) {
                    for (Socket client : accepted) {
                        client.close();
                    }
                }
            } catch (IOException e) {
                // 정리 경로
            }
            acceptor.interrupt();
        }
    }
}
