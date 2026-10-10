package kr.mujoong.realtime.gateway;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.message.Ack;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.contract.ws.message.WsError;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.session.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 기기 WSS 수신 핸들러 (U1 3.5 Step 24 · BR3.6 ~ 3.7 · BR4.8 · SD-7 · J123 · RD-4 · PD-1 표 2.1-A · SD-22).
 *
 * <p>프레임 종류를 가르고 곧바로 대기열 · 편지함에 넣는다(판정 · 동기 DB 접근 없음). 업그레이드 뒤 순서: readiness가 아니면 1013 → 송신기 ·
 * HELLO 5초 시계 → 쿠키가 없으면 즉시 4401 → verify-token(보조 작업, 1초) → HELLO 형식 확인 → HELLO_OK 인계. 기기 메시지는 모두 연결의
 * 마지막 메시지 시각을 갱신한다(BR6.12 ④). HEARTBEAT는 ACK를 먼저 보내고 세션 편지함에 넣지 않는다. ACK · DEVICE_READY · PLAYBACK_MARK ·
 * VOICE_SESSION_CLOSED는 세션 일반 편지함에, 0x01은 세션 입력 대기열에 넣는다. 일반 편지함이 가득 차면 연결을 1011로 닫는다(PD-2). 세션이
 * 없거나 끝났으면 기록 없이 버린다(BR6.11). 기기가 보낸 1000 · 1001(READY 뒤)만 즉시 OFFLINE이다(BR4.8 ②).
 */
@Component
public class DeviceWebSocketHandler extends AbstractWebSocketHandler {

    /** 속성 키: 연결 객체. */
    public static final String ATTR_CONNECTION = "mujoong.deviceConnection";

    private static final long PCM_BYTES_PER_SECOND = 48_000L;

    private final SocketRegistry sockets;
    private final SessionRegistry sessions;
    private final VerifyTokenClient verifier;
    private final ConnectionHandover handover;
    private final HeartbeatHandler heartbeats;
    private final ConnectionStateWriter writer;
    private final ReadinessState readiness;
    private final TaskLauncher launcher;
    private final TimerClocks clocks;
    private final JsonCodec json;
    private final EventLogger events;
    private final RealtimeMetrics metrics;
    private final MujoongClock clock;
    private final WsFrameGuard guard;
    private final Duration helloTimeout;
    private final int controlCapacity;
    private final long audioBudgetBytes;

    public DeviceWebSocketHandler(
            SocketRegistry sockets,
            SessionRegistry sessions,
            VerifyTokenClient verifier,
            ConnectionHandover handover,
            HeartbeatHandler heartbeats,
            ConnectionStateWriter writer,
            ReadinessState readiness,
            TaskLauncher launcher,
            TimerClocks clocks,
            JsonCodec json,
            EventLogger events,
            RealtimeMetrics metrics,
            MujoongClock clock,
            RealtimeProperties properties) {
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.handover = Objects.requireNonNull(handover, "handover");
        this.heartbeats = Objects.requireNonNull(heartbeats, "heartbeats");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.clocks = Objects.requireNonNull(clocks, "clocks");
        this.json = Objects.requireNonNull(json, "json");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guard = new WsFrameGuard(json, (int) properties.limits().wsTextFrameMax().toBytes());
        this.helloTimeout = properties.timing().device().helloTimeout();
        this.controlCapacity = properties.limits().deviceControlQueue();
        this.audioBudgetBytes = properties.timing().device().outputQueueMax().toSeconds() * PCM_BYTES_PER_SECOND;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String traceId = TraceIds.orNew((String) session.getAttributes().get(DeviceHandshakeInterceptor.ATTR_TRACE_ID));
        DeviceConnection connection = new DeviceConnection(UUID.randomUUID(), traceId, clock.now());
        session.getAttributes().put(ATTR_CONNECTION, connection);
        ConnectionSender sender = new ConnectionSender(connection.connectionId().toString(), new SpringTransport(session),
                json, clock, new SenderListener(connection), controlCapacity, audioBudgetBytes);
        connection.attachSender(sender);
        sender.start(launcher);
        sockets.register(connection);
        metrics.connectionOpened();
        if (!readiness.isReady()) {
            connection.close(CloseCode.TRY_AGAIN_LATER);
            return;
        }
        connection.helloTimer(clocks.safety().schedule(helloTimeout, () -> {
            if (connection.phase() == DeviceConnection.Phase.AWAIT_VERIFY
                    || connection.phase() == DeviceConnection.Phase.AWAIT_HELLO) {
                connection.close(CloseCode.HELLO_TIMEOUT);
            }
        }));
        String token = (String) session.getAttributes().remove(DeviceHandshakeInterceptor.ATTR_DEVICE_TOKEN);
        if (token == null || token.isBlank()) {
            authFailed(connection, EventCode.Level.INFO);
            return;
        }
        launcher.launch("device-verify-" + connection.connectionId(), () -> verify(connection, token));
    }

    private void verify(DeviceConnection connection, String token) {
        VerifyTokenClient.Result result = verifier.verify(token, connection.traceId());
        switch (result) {
            case VerifyTokenClient.Verified verified -> {
                connection.verified(verified.principal());
                if (connection.advance(DeviceConnection.Phase.AWAIT_VERIFY, DeviceConnection.Phase.AWAIT_HELLO)) {
                    connection.takeDeferredHello().ifPresent(hello -> onHello(connection));
                }
            }
            case VerifyTokenClient.Rejected rejected -> authFailed(connection, null);
            case VerifyTokenClient.Unavailable unavailable -> connection.close(CloseCode.TRY_AGAIN_LATER);
        }
    }

    private void authFailed(DeviceConnection connection, EventCode.Level level) {
        events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.DEVICE_GATEWAY, null, connection.traceId(),
                entry -> {
                    entry.attr(AttrKey.KIND, "DEVICE_TOKEN").attr(AttrKey.STATUS_CODE,
                            (long) CloseCode.UNAUTHORIZED.code());
                    if (level != null) {
                        entry.level(level);
                    }
                });
        connection.close(CloseCode.UNAUTHORIZED);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        DeviceConnection connection = connection(session);
        if (connection == null) {
            return;
        }
        Instant receivedAt = clock.now();
        connection.touch(receivedAt);
        WsFrameGuard.Decision decision = guard.onText(connection.phase(), message.getPayload());
        switch (decision) {
            case WsFrameGuard.Close close -> connection.close(close.code());
            case WsFrameGuard.Error error -> connection.sendControl(new WsError(error.code(), error.refSeq()), null, null);
            case WsFrameGuard.Ignore ignored -> {
                // DEVICE_STATE · READY 뒤 HELLO · PLAYBACK_MARK(PROGRESS): 받되 판단에 쓰지 않는다(J123)
            }
            case WsFrameGuard.Defer defer -> {
                connection.deferHello(defer.hello());
                if (connection.phase() == DeviceConnection.Phase.AWAIT_HELLO) {
                    connection.takeDeferredHello().ifPresent(hello -> onHello(connection));
                }
            }
            case WsFrameGuard.AcceptAudio audio -> connection.close(CloseCode.PROTOCOL_ERROR);
            case WsFrameGuard.Accept accept -> onEnvelope(connection, accept, receivedAt);
        }
    }

    private void onEnvelope(DeviceConnection connection, WsFrameGuard.Accept accept, Instant receivedAt) {
        WsEnvelope envelope = accept.envelope();
        switch (envelope.messageType()) {
            case HELLO -> onHello(connection);
            case HEARTBEAT -> heartbeats.onHeartbeat(connection, envelope, accept.sentAtMissing(), receivedAt);
            case ACK -> route(connection, envelope,
                    new SessionMailbox.DeviceAck(connection.connectionId(), ((Ack) envelope.payload()).ackOf(), receivedAt));
            case DEVICE_READY -> route(connection, envelope, new SessionMailbox.DeviceReadyReceived(
                    connection.connectionId(), (DeviceReady) envelope.payload(), receivedAt));
            case PLAYBACK_MARK -> route(connection, envelope, new SessionMailbox.PlaybackMarkReceived(
                    connection.connectionId(), (PlaybackMark) envelope.payload(), receivedAt));
            case VOICE_SESSION_CLOSED -> route(connection, envelope, new SessionMailbox.VoiceClosed(
                    connection.connectionId(), (VoiceSessionClosed) envelope.payload(), receivedAt));
            default -> {
                // 그 밖의 기기 → 서버 type은 WsFrameGuard가 이미 걸렀다
            }
        }
    }

    private void onHello(DeviceConnection connection) {
        if (connection.phase() != DeviceConnection.Phase.AWAIT_HELLO) {
            return;
        }
        connection.cancelHelloTimer();
        handover.complete(connection);
    }

    /** 세션 일반 편지함에 넣는다. 세션이 없거나 닫혔으면 버리고, 가득 찼으면 연결을 1011로 닫는다(PD-2). */
    private void route(DeviceConnection connection, WsEnvelope envelope, SessionMailbox.Message message) {
        UUID sessionId = envelope.sessionId() != null ? envelope.sessionId() : connection.boundSessionId();
        Optional<SessionMailbox> mailbox = sessions.mailbox(sessionId);
        if (mailbox.isEmpty() || mailbox.get().isClosed()) {
            return;
        }
        if (!mailbox.get().offerGeneral(message) && !mailbox.get().isClosed()) {
            events.log(EventCode.QUEUE_PRESSURE, EventLog.Component.DEVICE_GATEWAY, sessionId, connection.traceId(),
                    entry -> entry.attr(AttrKey.QUEUE, "GENERAL_MAILBOX").attr(AttrKey.DROPPED, 1L));
            connection.close(CloseCode.SERVER_ERROR);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        DeviceConnection connection = connection(session);
        if (connection == null) {
            return;
        }
        long receivedNanos = clock.monotonicNanos();
        connection.touch(clock.now());
        byte[] bytes = new byte[message.getPayloadLength()];
        message.getPayload().duplicate().get(bytes);
        WsFrameGuard.Decision decision = guard.onBinary(connection.phase(), bytes);
        if (decision instanceof WsFrameGuard.Close close) {
            connection.close(close.code());
            return;
        }
        if (decision instanceof WsFrameGuard.AcceptAudio audio) {
            sessions.executor(connection.boundSessionId()).ifPresent(executor -> offer(executor, connection, audio,
                    receivedNanos));
        }
    }

    private static void offer(SessionExecutor executor, DeviceConnection connection, WsFrameGuard.AcceptAudio audio,
            long receivedNanos) {
        executor.offerAudio(connection.connectionId(), audio.frame().sequence(), audio.frame().pcm(), receivedNanos);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        DeviceConnection connection = connection(session);
        events.appError(EventLog.Component.DEVICE_GATEWAY, exception);
        if (connection != null) {
            connection.close(CloseCode.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        DeviceConnection connection = connection(session);
        if (connection == null) {
            return;
        }
        DeviceConnection.Phase previous = connection.markClosed();
        if (previous == DeviceConnection.Phase.CLOSED) {
            return;
        }
        connection.cancelHelloTimer();
        connection.senderClosedByPeer();
        sockets.remove(connection);
        metrics.connectionClosed();
        ConnectionHandover.notifyLost(sessions, connection);
        UUID deviceId = connection.deviceId();
        if (deviceId != null && DeviceStatusPolicy.closesToOffline(status.getCode(), connection.isServerClosing(),
                previous == DeviceConnection.Phase.READY)) {
            launcher.launch("device-offline-" + connection.connectionId(), () -> {
                try {
                    writer.markClosed(deviceId, connection.connectionId(), status.getCode(), connection.traceId());
                } catch (RuntimeException e) {
                    events.appError(EventLog.Component.DEVICE_GATEWAY, e);
                }
            });
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private static DeviceConnection connection(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_CONNECTION);
        return value instanceof DeviceConnection connection ? connection : null;
    }

    /** Spring 소켓 감싸개. 쓰기는 연결 송신기 한 스레드만 한다. */
    private static final class SpringTransport implements ConnectionSender.Transport {

        private final WebSocketSession session;

        private SpringTransport(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void sendText(String text) throws IOException {
            session.sendMessage(new TextMessage(text));
        }

        @Override
        public void sendBinary(byte[] frame) throws IOException {
            session.sendMessage(new BinaryMessage(frame));
        }

        @Override
        public void close(CloseCode code) throws IOException {
            if (session.isOpen()) {
                session.close(new CloseStatus(code.code()));
            }
        }
    }

    /** 송신기 사건. */
    private final class SenderListener implements ConnectionSender.Listener {

        private final DeviceConnection connection;

        private SenderListener(DeviceConnection connection) {
            this.connection = connection;
        }

        @Override
        public void controlOverflow() {
            events.log(EventCode.QUEUE_PRESSURE, EventLog.Component.DEVICE_GATEWAY, connection.deviceId(),
                    connection.traceId(), entry -> entry.attr(AttrKey.QUEUE, "OUTPUT_CONTROL").attr(AttrKey.DROPPED, 1L));
        }

        @Override
        public void broken(IOException failure) {
            events.appError(EventLog.Component.DEVICE_GATEWAY, failure);
        }

        @Override
        public void closed(CloseCode code) {
            // 닫힘 처리는 afterConnectionClosed가 한다
        }
    }
}
