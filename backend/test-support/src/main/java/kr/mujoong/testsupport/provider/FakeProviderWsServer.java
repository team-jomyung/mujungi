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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.annotation.JsonProperty;
import kr.mujoong.contract.json.JsonCodec;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import tools.jackson.databind.JsonNode;

/**
 * 가짜 공급자 WS 서버 (U1 3.5 Step 30 · RD-5 · NFR4.2 · SD-20 · J131, U11 BR11.10 · 설계 개정 "시험 · 가짜 공급자 변경", 실호출 3회 관측 형식).
 *
 * <p>루프백 무작위 포트에서 듣고, 받은 텍스트 메시지를 모두 기록한다(허용 목록 · 무변환 시험). 시나리오: 정상({@code session.start}에
 * {@code session.started}) · started 없음 · 지연 응답 · 연결 거부(듣지 않는 포트) · 연결 단계 무응답(TCP만 받고 업그레이드에 답하지 않음).
 * 시험은 {@link #emitTurn}으로 아이 전사 조각 · AI 출력(전사 + 음성) 한 턴을, {@link #emit}으로 임의 이벤트를 흘린다.
 *
 * <p>선 형식은 실호출로 관측한 것만 쓰고 공급자처럼 <b>엄격</b>하다. 음성은 {@code session.output_audio.delta{delta}}(응답 id · 시간 정보 없음),
 * 전사는 {@code session.output_transcript.delta} · {@code session.input_transcript.delta}{@code {start_ms, end_ms, delta, event_id}}이고
 * 입력 전사 완료 · 말 끝 · 응답 끝 사건은 <b>보내지 않는다</b>. 지원 목록 밖 type은 {@code error{invalid_request_error, invalid_value, param type}},
 * {@code session.instructions.append}에 {@code delegation_id} 키가 없으면 {@code error{missing_required_parameter, param delegation_id}},
 * {@code response.create}는 {@code error{invalid_request_error, "response.create requires a session with Responses delegation"}}로 답한다.
 * 올바른 {@code append}에는 확인 사건 {@code session.instructions.appended{event_id, client_event_id, start_ms, end_ms}}를 보낸다 — 시간은 이 서버가
 * 센 출력 오디오 시계(보낸 음성 바이트 / 48 ms) 위의 값이다.
 *
 * <p>{@link #instructedSpeech}를 켜면 단순한 모델을 흉내 낸다: 올바른 {@code append}를 받으면 확인 사건을 곧바로 보내고, 말하는 중이었다면 낡은 음성을
 * 낡은 꼬리 길이만큼(관측 1.2 ~ 1.8초) 더 보낸 뒤 쉼 없이 새 말을 시작한다. 새 말의 전사는 음성보다 앞서(기본 0.6초) 도착하고
 * {@code start_ms >= 확인 end_ms}이다. 지속형 지시(문장에 "이번 한 번만"이 없는 것)는 {@link #persistentInstructions}에 남고 한 번만 지시는 남지 않는다.
 * 받은 {@code input_audio.append} 음성은 바이트 해시로만 모은다(원음 보관 없음). 비밀 값(공급자 키)은 기록하지 않고 Authorization 헤더가 Bearer
 * 형식인지만 남긴다.
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
    /** 이 서버가 보낸 출력 음성의 누적 ms(오디오 시계). */
    private final AtomicLong audioClockMs = new AtomicLong();
    private final AtomicInteger eventSeq = new AtomicInteger();
    /** 아직 새 말이 쓰지 않은 가장 최근 지시 확인의 end_ms(없으면 -1). 시험이 보내는 다음 응답은 이 시각 뒤에 시작한다(실제 모델처럼). */
    private volatile long pendingAckEndMs = -1L;
    /** 새 말 앞에 채우는 낡은 꼬리 흉내 음성 길이(앱의 new-speech-margin보다 길어야 한다). */
    private static final long FILLER_MS = 700L;
    private final List<String> persistent = new CopyOnWriteArrayList<>();
    private volatile long lastAudioNanos = System.nanoTime() - Duration.ofHours(1).toNanos();
    private volatile Duration staleTail = Duration.ZERO;
    private volatile int newSpeechChunks;
    private volatile Duration transcriptLead = Duration.ofMillis(600);
    private volatile Duration chunkInterval = Duration.ofMillis(2);
    private volatile Duration childConfirmDelay = Duration.ZERO;
    private volatile boolean autoAck = true;

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

    /**
     * 지시를 따라 말하는 단순한 모델을 켠다(설계 개정 시험용). 올바른 {@code append}를 받으면 확인 사건을 보내고, 직전에 음성을 보내는 중이었다면 낡은
     * 꼬리를 {@code staleTail} 더 보낸 뒤 쉼 없이 {@code newSpeechChunks}개(20ms)의 새 말을 보낸다.
     */
    public FakeProviderWsServer instructedSpeech(Duration staleTail, int newSpeechChunks) {
        this.staleTail = Objects.requireNonNull(staleTail, "staleTail");
        this.newSpeechChunks = newSpeechChunks;
        return this;
    }

    /** 새 말 전사가 음성보다 앞서 도착하는 폭(기본 600ms). */
    public FakeProviderWsServer transcriptLead(Duration lead) {
        this.transcriptLead = Objects.requireNonNull(lead, "lead");
        return this;
    }

    /** 흉내 내는 음성 조각을 보내는 실제 간격(시험이 오래 걸리지 않게 20ms 분량을 이 간격으로 보낸다, 기본 2ms). */
    public FakeProviderWsServer chunkInterval(Duration interval) {
        this.chunkInterval = Objects.requireNonNull(interval, "interval");
        return this;
    }

    /**
     * {@link #emitTurn}에서 아이 입력 전사 조각을 보낸 뒤 AI 말을 보내기 전에 실제로 기다리는 시간. 시험이 앱의 {@code input-transcript-quiet}보다
     * 길게 주면 아이 전사가 확정된 뒤에 AI가 말하는 옛 순서를 흉내 낸다(실제 모델은 아이 말이 끝나기 전에 말을 시작할 수 있다). 기본 0.
     */
    public FakeProviderWsServer childConfirmDelay(Duration delay) {
        this.childConfirmDelay = Objects.requireNonNull(delay, "delay");
        return this;
    }

    /** 올바른 지시 추가에 확인 사건을 자동으로 보내는가(기본 켬). 끄면 시험이 {@link #emitAppended}로 시간표를 직접 만든다. */
    public FakeProviderWsServer autoAck(boolean value) {
        this.autoAck = value;
        return this;
    }

    /** 지금까지 보낸 출력 음성의 누적 ms. */
    public long audioClockMs() {
        return audioClockMs.get();
    }

    /** 남아 있는 지속형 지시 문장들(문장에 "이번 한 번만"이 없는 것, 단순 모델). */
    public List<String> persistentInstructions() {
        return List.copyOf(persistent);
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

    /** 출력 음성 조각 하나({@code session.output_audio.delta{delta}}, 응답 id · 시간 정보 없음). 오디오 시계를 센다. */
    public void emitAudio(byte[] pcm) {
        emit(new Event("session.output_audio.delta", null, Base64.getEncoder().encodeToString(pcm), null));
        audioClockMs.addAndGet(pcm.length / 48L);
        lastAudioNanos = System.nanoTime();
    }

    /** 출력 전사 조각 하나({@code session.output_transcript.delta{start_ms, end_ms, delta, event_id}}). */
    public void emitOutputTranscript(String text, long startMs, long endMs) {
        emit(new TimedEvent("session.output_transcript.delta", startMs, endMs, text, "fake-t-" + eventSeq.incrementAndGet()));
    }

    /** 입력 전사 조각 하나({@code session.input_transcript.delta{start_ms, end_ms, delta, event_id}}). 완료 사건은 없다. */
    public void emitInputTranscript(String text, long startMs, long endMs) {
        emit(new TimedEvent("session.input_transcript.delta", startMs, endMs, text, "fake-i-" + eventSeq.incrementAndGet()));
    }

    /** 지시 확인 사건 하나({@code session.instructions.appended}). */
    public void emitAppended(String clientEventId, long startMs, long endMs) {
        emit(new AppendedEvent("session.instructions.appended", "fake-a-" + eventSeq.incrementAndGet(), clientEventId, startMs,
                endMs));
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
     * 한 턴: 아이 입력 전사 조각 → AI 출력 전사 · 음성 조각들. 입력 전사 완료 · 말 끝 · 응답 끝 사건은 없다(실호출에서 관측되지 않음) — 아이 말의
     * 끝은 서버가 입력 전사 조각을 묶어 정하고 응답 끝은 조각 뒤의 무음으로 드러난다. 출력 전사는 음성보다 앞서 도착한다.
     *
     * <p>이 응답이 지시에 대한 것이면(아직 쓰지 않은 지시 확인이 있으면) 실제 모델처럼 확인 end_ms 뒤에서 새 말을 시작한다: 새 말 전사를 먼저 보내고,
     * 낡은 꼬리를 흉내 낸 채움 음성(앱이 {@code new-speech-margin}만큼 버리는 구간)을 보낸 다음 {@code pcmChunks}를 보낸다.
     *
     * @param itemId 사용하지 않는다(공급자는 입력 전사 항목 id를 주지 않는다)
     * @param childText 아이 말(null이면 입력 전사 없이 AI 말만)
     * @param pcmChunks PCM16LE 24kHz 모노 조각들(짝수 바이트)
     */
    public void emitTurn(String itemId, String childText, String aiText, List<byte[]> pcmChunks) {
        if (childText != null) {
            long clock = audioClockMs.get();
            emitInputTranscript(childText, clock, clock);
            if (!childConfirmDelay.isZero()) {
                sleep(childConfirmDelay.toMillis());
            }
        }
        emitSpeech(aiText, pcmChunks);
    }

    /** AI 말 하나(전사 + 음성). 지시에 대한 응답이면 확인 end_ms 뒤에서 시작하고 앞에 채움 음성을 둔다({@link #emitTurn}). */
    public void emitSpeech(String aiText, List<byte[]> pcmChunks) {
        long clock = audioClockMs.get();
        long total = pcmChunks.stream().mapToLong(chunk -> chunk.length / 48L).sum();
        long pending = pendingAckEndMs;
        if (pending >= 0) {
            pendingAckEndMs = -1L;
            long start = Math.max(clock, pending);
            emitOutputTranscript(aiText, start, start + total);
            emitAudio(new byte[(int) ((start - clock + FILLER_MS) * 48L)]);
        } else {
            emitOutputTranscript(aiText, clock, clock + total);
        }
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

    /** 시간이 있는 전사 사건({@code start_ms}, {@code end_ms}, {@code delta}, {@code event_id}). */
    public record TimedEvent(String type, @JsonProperty("start_ms") long startMs, @JsonProperty("end_ms") long endMs, String delta,
            @JsonProperty("event_id") String eventId) {
    }

    /** {@code session.instructions.appended} 사건. */
    public record AppendedEvent(String type, @JsonProperty("event_id") String eventId,
            @JsonProperty("client_event_id") String clientEventId, @JsonProperty("start_ms") long startMs,
            @JsonProperty("end_ms") long endMs) {
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
        } else if ("response.create".equals(type)) {
            socket.send(JSON.write(new ErrorEvent("error", new ErrorBody("invalid_request_error", "invalid_request",
                    "response.create requires a session with Responses delegation", null))));
        } else if ("session.instructions.append".equals(type)) {
            onAppend(socket, node);
        } else if ("session.start".equals(type)) {
            answerStart(socket);
        } else if ("session.input_audio.append".equals(type)) {
            String audio = text(node, "audio");
            audioHashes.add(sha256(audio == null ? new byte[0] : Base64.getDecoder().decode(audio)));
        }
    }

    /** 지시 추가: {@code delegation_id} 키(null 값 허용)와 {@code content}가 있어야 받는다. 받으면 확인 사건을 보내고 단순 모델을 돌린다. */
    private void onAppend(WebSocket socket, JsonNode node) {
        if (node.get("delegation_id") == null) {
            socket.send(JSON.write(new ErrorEvent("error", new ErrorBody("invalid_request_error", "missing_required_parameter",
                    "Missing required parameter", "delegation_id"))));
            return;
        }
        String content = text(node, "content");
        if (content == null || content.isBlank()) {
            socket.send(JSON.write(new ErrorEvent("error", new ErrorBody("invalid_request_error", "missing_required_parameter",
                    "Missing required parameter", "content"))));
            return;
        }
        String eventId = text(node, "event_id");
        if (!content.contains("이번 한 번만")) {
            persistent.add(content);
        }
        if (!autoAck) {
            return;
        }
        long clock = audioClockMs.get();
        pendingAckEndMs = clock + 300L;
        boolean speaking = System.nanoTime() - lastAudioNanos < Duration.ofMillis(300).toNanos();
        Duration tail = speaking ? staleTail : Duration.ZERO;
        long ackEnd = clock + 300L;
        long newStart = Math.max(ackEnd, clock + tail.toMillis());
        // 확인 사건은 곧바로 간다(관측: 낡은 꼬리가 이어지는 동안 도착)
        socket.send(JSON.write(new AppendedEvent("session.instructions.appended", "fake-a-" + eventSeq.incrementAndGet(), eventId, clock,
                ackEnd)));
        if (newSpeechChunks <= 0) {
            return;
        }
        pendingAckEndMs = -1L;
        Thread.ofVirtual().start(() -> speakAfterInstruction(socket, clock, newStart));
    }

    /** 낡은 꼬리를 {@code newStart}까지 보내고(말하는 중이었다면) 전사를 앞서 보낸 뒤 새 말 음성을 쉼 없이 이어 보낸다. */
    private void speakAfterInstruction(WebSocket socket, long fromClock, long newStart) {
        try {
            long lead = transcriptLead.toMillis();
            boolean transcriptSent = false;
            long newEnd = newStart + newSpeechChunks * 20L;
            while (socket.isOpen() && audioClockMs.get() < newEnd) {
                long now = audioClockMs.get();
                if (!transcriptSent && now >= newStart - lead) {
                    emitOutputTranscript("새응답", newStart, newEnd);
                    transcriptSent = true;
                }
                emitAudio(new byte[960]);
                sleep(chunkInterval.toMillis());
            }
        } catch (IllegalStateException e) {
            // 연결이 닫혔다
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
