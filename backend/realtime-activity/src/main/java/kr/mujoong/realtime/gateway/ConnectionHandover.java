package kr.mujoong.realtime.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.message.HelloOk;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.session.SessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HELLO_OK 인계 (U1 3.5 Step 24 · BR3.7 · BR4.8 ① · SCD-2 · R3-7 · 편차 (l) · SD-17).
 *
 * <p>순서는 ① 연결 원장 행을 {@code FOR UPDATE}로 잠그고 새 connection_id · CONNECTED · DeviceStatusChanged를 한 트랜잭션에 커밋 → ② 소켓
 * 레지스트리를 새 연결로 교체 → ③ 옛 소켓이 살아 있으면 4001 REPLACED로 닫고 옛 연결에 묶인 세션에 연결 끊김을 알린다(출력 세대 올림) →
 * ④ READY로 바꾼 뒤 HELLO_OK를 보낸다. ①이 실패하면 새 연결만 1011로 닫고 옛 연결 · 레지스트리는 건드리지 않는다(SCD-2). 새 연결은 진행 중
 * 세션에 자동으로 붙지 않는다(HELLO resume은 U3, HELLO_OK.session은 비움).
 */
@Component
public class ConnectionHandover {

    private final ConnectionStateWriter writer;
    private final SocketRegistry sockets;
    private final SessionRegistry sessions;
    private final EventLogger events;
    private final MujoongClock clock;
    private final Duration heartbeatInterval;

    @Autowired
    public ConnectionHandover(ConnectionStateWriter writer, SocketRegistry sockets, SessionRegistry sessions,
            EventLogger events, MujoongClock clock, RealtimeProperties properties) {
        this(writer, sockets, sessions, events, clock, properties.timing().device().heartbeatInterval());
    }

    public ConnectionHandover(ConnectionStateWriter writer, SocketRegistry sockets, SessionRegistry sessions,
            EventLogger events, MujoongClock clock, Duration heartbeatInterval) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
    }

    /** 인계한다. 성공하면 참(연결은 READY). */
    public boolean complete(DeviceConnection connection) {
        Objects.requireNonNull(connection, "connection");
        UUID deviceId = connection.deviceId();
        if (deviceId == null || connection.phase() != DeviceConnection.Phase.AWAIT_HELLO) {
            return false;
        }
        try {
            writer.handover(connection);
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.DEVICE_GATEWAY, e);
            connection.close(CloseCode.SERVER_ERROR);
            return false;
        }
        Optional<DeviceConnection> previous = sockets.replaceCurrent(deviceId, connection);
        previous.filter(old -> old != connection).ifPresent(this::replaceOld);
        if (!connection.advance(DeviceConnection.Phase.AWAIT_HELLO, DeviceConnection.Phase.READY)) {
            return false;
        }
        connection.sendControl(new HelloOk(connection.connectionId(), clock.now(), heartbeatInterval.toMillis(),
                HelloOk.AUDIO_PCM16LE_24K_MONO, null), null, null);
        return true;
    }

    private void replaceOld(DeviceConnection old) {
        old.close(CloseCode.REPLACED);
        notifyLost(sessions, old);
    }

    /** 연결에 묶인 세션에 끊김을 알린다(SD-17 세대 올림). */
    static void notifyLost(SessionRegistry sessions, DeviceConnection connection) {
        UUID sessionId = connection.boundSessionId();
        if (sessionId != null) {
            sessions.mailbox(sessionId)
                    .ifPresent(mailbox -> mailbox.putSafety(new SessionMailbox.ConnectionLost(connection.connectionId())));
        }
    }
}
