package kr.mujoong.realtime.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import kr.mujoong.contract.event.payload.DeviceStatusChanged;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기기 연결 원장 상태 기록 장치 (U1 3.5 Step 24 · BR4.8 · BR3.7 · BR3.8 · BR6.13 S3b · J55 · J125 · 편차 (q) · A4 · OD-2).
 *
 * <p>상태가 실제로 바뀐 경우에만(조건부 UPDATE가 행을 돌려줄 때만) 같은 트랜잭션에 DeviceStatusChanged를 Outbox에 쓴다. 이벤트의
 * occurredAt은 상태 UPDATE 직전에 Clock으로 읽은 시각이다(트랜잭션 시작 시각이 아님, BR4.8). payload에는 연결 행의 guardian_id를
 * 선택 필드 guardianId로도 싣고(편차 (q)) 행 guardian_id(A4)도 같은 값이다. 교체 이후의 DISCONNECTED · UNRESPONSIVE · OFFLINE 기록은
 * {@code device_id AND connection_id} 조건부라 대체된 옛 연결의 기록은 0행으로 버려진다. 해제(BR3.8)만 device_id 조건이다. 커밋 뒤
 * DEVICE_CONNECTION 한 줄(from · to · closeCode)을 남긴다. 트랜잭션 경계는 이 장치 하나다(RD-22, 바깥 트랜잭션이 있으면 거기에 합류).
 */
@Component
public class ConnectionStateWriter implements DeviceStatusPort {

    private final DeviceConnectionRepository connections;
    private final RealtimeOutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final DbRetry dbRetry;
    private final EventLogger events;
    private final MujoongClock clock;
    private final SocketRegistry sockets;

    public ConnectionStateWriter(
            DeviceConnectionRepository connections,
            RealtimeOutboxWriter outbox,
            PlatformTransactionManager transactionManager,
            DbRetry dbRetry,
            EventLogger events,
            MujoongClock clock,
            SocketRegistry sockets) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
    }

    /** HELLO_OK 교체(BR3.7): 행 FOR UPDATE · 새 연결로 CONNECTED · 이벤트를 한 트랜잭션에. 옛 값을 돌려준다. */
    public DeviceConnectionRepository.Handover handover(DeviceConnection connection) {
        DeviceConnection.Principal principal = connection.principal()
                .orElseThrow(() -> new IllegalStateException("확인되지 않은 연결은 교체할 수 없다"));
        DeviceConnectionRepository.Handover handover = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> {
                    Instant now = clock.now();
                    DeviceConnectionRepository.Handover result = connections.handover(principal.deviceId(),
                            connection.connectionId(), principal.guardianId(), principal.childId(), now);
                    writeEvent(result.current(), now, connection.traceId());
                    return result;
                }));
        Objects.requireNonNull(handover, "handover");
        connection.knownState(ConnectionState.CONNECTED);
        ConnectionState from = handover.previous() == null ? ConnectionState.OFFLINE : handover.previous().state();
        logChange(principal.deviceId(), from, ConnectionState.CONNECTED, null, connection.traceId());
        return handover;
    }

    /** 기기의 정상 닫기(1000 · 1001, BR4.8 ②): OFFLINE. 대체된 옛 연결이면 빈 값. */
    public Optional<DeviceConnectionRow> markClosed(UUID deviceId, UUID connectionId, int closeCode, String traceId) {
        return change(deviceId, traceId, closeCode,
                () -> connections.markClosed(deviceId, connectionId, clock.now()));
    }

    /** 명령 시한(J55): UNRESPONSIVE. 실패는 앱 오류 한 줄로 남기고 던지지 않는다. */
    @Override
    public void markUnresponsive(UUID deviceId, UUID connectionId) {
        try {
            change(deviceId, null, null, () -> connections.markUnresponsive(deviceId, connectionId, clock.now()));
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.DEVICE_GATEWAY, e);
        }
    }

    /** 같은 연결의 HEARTBEAT(J55): UNRESPONSIVE · DISCONNECTED → CONNECTED. */
    public Optional<DeviceConnectionRow> markConnectedAgain(UUID deviceId, UUID connectionId, String traceId) {
        return change(deviceId, traceId, null, () -> connections.markConnectedAgain(deviceId, connectionId, clock.now()));
    }

    /** 40초 무음(BR4.8 ②): {@code last_seen_at <= checkedAt − after}일 때만 DISCONNECTED. */
    public Optional<DeviceConnectionRow> markDisconnected(UUID deviceId, UUID connectionId, Duration after) {
        return change(deviceId, null, null,
                () -> connections.markDisconnected(deviceId, connectionId, clock.now(), after));
    }

    /** 기기 해제(BR3.8): device_id 조건으로 OFFLINE · connection_id 비움. 바깥(Inbox 처리기) 트랜잭션에 합류한다. */
    public Optional<DeviceConnectionRow> release(UUID deviceId) {
        return change(deviceId, null, CloseCode.UNAUTHORIZED.code(), () -> connections.release(deviceId, clock.now()));
    }

    /** 재시작 정리(BR6.13 S3b): CONNECTED · UNSTABLE → DISCONNECTED(connection_id 비움). 바뀐 행 수. */
    public int resetAfterRestart() {
        List<DeviceConnectionRow> changed = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(
                status -> {
                    Instant now = clock.now();
                    List<DeviceConnectionRow> rows = connections.resetAfterRestart(now);
                    for (DeviceConnectionRow row : rows) {
                        writeEvent(row, now, null);
                    }
                    return rows;
                }));
        List<DeviceConnectionRow> rows = changed == null ? List.of() : changed;
        for (DeviceConnectionRow row : rows) {
            logChange(row.deviceId(), null, ConnectionState.DISCONNECTED, null, null);
        }
        return rows.size();
    }

    private Optional<DeviceConnectionRow> change(UUID deviceId, String traceId, Integer closeCode,
            Supplier<Optional<DeviceConnectionRow>> update) {
        Objects.requireNonNull(deviceId, "deviceId");
        Change result = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            ConnectionState from = connections.find(deviceId).map(DeviceConnectionRow::state).orElse(null);
            Instant now = clock.now();
            Optional<DeviceConnectionRow> row = update.get();
            row.ifPresent(changed -> writeEvent(changed, now, traceId));
            return new Change(from, row);
        }));
        if (result == null || result.row().isEmpty()) {
            return Optional.empty();
        }
        DeviceConnectionRow row = result.row().get();
        mirror(row);
        logChange(deviceId, result.from(), row.state(), closeCode, traceId);
        return result.row();
    }

    /** 원장 행 상태를 그 연결 객체의 사본에 반영한다(HEARTBEAT 복귀 판정, J55). */
    private void mirror(DeviceConnectionRow row) {
        if (row.connectionId() != null) {
            sockets.byConnectionId(row.connectionId()).ifPresent(connection -> connection.knownState(row.state()));
        }
    }

    private void writeEvent(DeviceConnectionRow row, Instant now, String traceId) {
        Instant since = row.state() == ConnectionState.CONNECTED && row.connectionSince() != null ? row.connectionSince() : now;
        DeviceStatusChanged payload = new DeviceStatusChanged(row.deviceId(), row.state(), row.lastRttMs(),
                row.recentDisconnects(), since, row.lastSeenAt(), row.guardianId());
        outbox.write(payload, row.guardianId(), now, traceId);
    }

    private void logChange(UUID deviceId, ConnectionState from, ConnectionState to, Integer closeCode, String traceId) {
        events.log(EventCode.DEVICE_CONNECTION, EventLog.Component.DEVICE_GATEWAY, deviceId, traceId, entry -> {
            entry.attr(AttrKey.FROM, from).attr(AttrKey.TO, to);
            if (closeCode != null) {
                entry.attr(AttrKey.CLOSE_CODE, (long) closeCode);
            }
        });
    }

    private record Change(ConnectionState from, Optional<DeviceConnectionRow> row) {
    }

}
