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
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gateway.DeviceDisconnectRepository.DisconnectEvent;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>연결 객체의 원장 사본(knownState, HEARTBEAT 복귀 판정 J55)은 상태 UPDATE 직후 같은 트랜잭션 안에서 맞춘다(3.6 수정 J391): UPDATE가 행
 * 잠금을 쥔 채라 커밋된 새 상태를 DB에서 본 어떤 관찰자도 뒤처진 사본을 볼 수 없다. 커밋 뒤에 맞추면 커밋 ~ 사본 갱신 사이에 온 HEARTBEAT가
 * 복귀 작업을 띄우지 않아 원장이 다음 HEARTBEAT까지 끊김으로 남는다. 되돌아간 트랜잭션은 사본만 앞서가게 하는데, 그때는 다음 HEARTBEAT의 복귀
 * UPDATE가 0행이라 원장을 다시 읽어 맞출 뿐이다({@link #resyncKnownState}).
 *
 * <p>U3(BR4.8 ① · U3-R1 · U3-J2 · U3-J3): 끊김 사건 표({@link DeviceDisconnectRepository})를 쓰고 창
 * {@code mujoong.timing.device.unstable-disconnect-window} 안 횟수가 {@code mujoong.device.unstable-disconnect-count} 이상이면 인계 · 복귀
 * 상태를 UNSTABLE로 둔다. HEARTBEAT 왕복 초과의 UNSTABLE({@link #markUnstable})과 정상 왕복의 복귀({@link #markStableIfCalm})도 여기서
 * 쓴다. 횟수는 원장 행 {@code recent_disconnects}에 함께 적어 상태 보고 · DeviceStatusChanged에 실린다.
 */
@Component
public class ConnectionStateWriter implements DeviceStatusPort {

    private final DeviceConnectionRepository connections;
    private final DeviceDisconnectRepository disconnects;
    private final RealtimeOutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final DbRetry dbRetry;
    private final EventLogger events;
    private final MujoongClock clock;
    private final SocketRegistry sockets;
    private final Duration unstableWindow;
    private final int unstableCount;

    @Autowired
    public ConnectionStateWriter(
            DeviceConnectionRepository connections,
            DeviceDisconnectRepository disconnects,
            RealtimeOutboxWriter outbox,
            PlatformTransactionManager transactionManager,
            DbRetry dbRetry,
            EventLogger events,
            MujoongClock clock,
            SocketRegistry sockets,
            RealtimeProperties properties) {
        this(connections, disconnects, outbox, transactionManager, dbRetry, events, clock, sockets,
                properties.timing().device().unstableDisconnectWindow(), properties.device().unstableDisconnectCount());
    }

    /**
     * @param unstableWindow 끊김 횟수를 세는 창({@code mujoong.timing.device.unstable-disconnect-window})
     * @param unstableCount 창 안 이 횟수 이상이면 UNSTABLE({@code mujoong.device.unstable-disconnect-count})
     */
    public ConnectionStateWriter(
            DeviceConnectionRepository connections,
            DeviceDisconnectRepository disconnects,
            RealtimeOutboxWriter outbox,
            PlatformTransactionManager transactionManager,
            DbRetry dbRetry,
            EventLogger events,
            MujoongClock clock,
            SocketRegistry sockets,
            Duration unstableWindow,
            int unstableCount) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.disconnects = Objects.requireNonNull(disconnects, "disconnects");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.unstableWindow = Objects.requireNonNull(unstableWindow, "unstableWindow");
        if (unstableWindow.isZero() || unstableWindow.isNegative() || unstableCount < 1) {
            throw new IllegalArgumentException("불안정 창은 0보다 크고 횟수는 1 이상이어야 한다");
        }
        this.unstableCount = unstableCount;
    }

    /**
     * HELLO_OK 교체(BR3.7): 행 FOR UPDATE · 새 연결로 CONNECTED · 이벤트를 한 트랜잭션에. 옛 값을 돌려준다. U3: 창 안 끊김 횟수를 함께 적고 기준
     * 이상이면 같은 트랜잭션에서 UNSTABLE로 둔다(이벤트는 최종 상태 하나).
     */
    public DeviceConnectionRepository.Handover handover(DeviceConnection connection) {
        DeviceConnection.Principal principal = connection.principal()
                .orElseThrow(() -> new IllegalStateException("확인되지 않은 연결은 교체할 수 없다"));
        DeviceConnectionRepository.Handover handover = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> {
                    Instant now = clock.now();
                    DeviceConnectionRepository.Handover result = connections.handover(principal.deviceId(),
                            connection.connectionId(), principal.guardianId(), principal.childId(), now);
                    int recent = disconnects.countRecent(principal.deviceId(), now, unstableWindow);
                    DeviceConnectionRow current = connections.setRecentDisconnects(principal.deviceId(), recent, now)
                            .orElse(result.current());
                    if (DeviceStatusPolicy.recoveredState(recent, unstableCount) == ConnectionState.UNSTABLE) {
                        current = connections.markUnstable(principal.deviceId(), connection.connectionId(), recent, now)
                                .orElse(current);
                    }
                    writeEvent(current, now, connection.traceId());
                    return new DeviceConnectionRepository.Handover(result.previous(), current);
                }));
        Objects.requireNonNull(handover, "handover");
        connection.knownState(handover.current().state());
        ConnectionState from = handover.previous() == null ? ConnectionState.OFFLINE : handover.previous().state();
        logChange(principal.deviceId(), from, handover.current().state(), null, connection.traceId());
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

    /**
     * 같은 연결의 HEARTBEAT(J55): UNRESPONSIVE · DISCONNECTED → CONNECTED. 그 HEARTBEAT의 수신 시각으로 last_seen_at도 올린다(J390). U3: 창 안
     * 끊김이 기준 이상이면 CONNECTED 대신 UNSTABLE로 복귀한다(U3-R1).
     *
     * @param seenAt 복귀를 만든 HEARTBEAT의 수신 시각
     */
    public Optional<DeviceConnectionRow> markConnectedAgain(UUID deviceId, UUID connectionId, Instant seenAt,
            String traceId) {
        Objects.requireNonNull(seenAt, "seenAt");
        return change(deviceId, traceId, null, () -> {
            Instant now = clock.now();
            int recent = disconnects.countRecent(deviceId, now, unstableWindow);
            return connections.markRecovered(deviceId, connectionId, seenAt, now,
                    DeviceStatusPolicy.recoveredState(recent, unstableCount), recent);
        });
    }

    /** U3: HEARTBEAT 왕복이 기준을 넘었다 — 같은 연결의 CONNECTED → UNSTABLE(BR4.8 ①). 바뀌었으면 바뀐 행. */
    public Optional<DeviceConnectionRow> markUnstable(UUID deviceId, UUID connectionId, String traceId) {
        return change(deviceId, traceId, null, () -> {
            Instant now = clock.now();
            int recent = disconnects.countRecent(deviceId, now, unstableWindow);
            return connections.markUnstable(deviceId, connectionId, recent, now);
        });
    }

    /**
     * U3: UNSTABLE인 연결의 정상 왕복 HEARTBEAT — 창 안 끊김이 기준 아래면 CONNECTED로 되돌린다. 아직 기준 이상이면(횟수가 바뀌었을 때만 횟수를
     * 다시 적고) 빈 값이다.
     */
    public Optional<DeviceConnectionRow> markStableIfCalm(UUID deviceId, UUID connectionId, String traceId) {
        return change(deviceId, traceId, null, () -> {
            Instant now = clock.now();
            int recent = disconnects.countRecent(deviceId, now, unstableWindow);
            if (DeviceStatusPolicy.recoveredState(recent, unstableCount) == ConnectionState.UNSTABLE) {
                boolean changed = connections.find(deviceId).map(row -> row.recentDisconnects() != recent).orElse(false);
                if (changed) {
                    connections.setRecentDisconnects(deviceId, recent, now);
                }
                return Optional.empty();
            }
            return connections.markStable(deviceId, connectionId, recent, now);
        });
    }

    /**
     * U3-J2: 끊김 사건 한 건을 적고(닫힘은 코드와 함께, 무음은 코드 없이) 창 안 횟수를 원장에 쓴다. 횟수가 기준 이상인데 지금 연결이 CONNECTED면
     * (늦게 처리된 옛 연결의 닫힘 등) 같은 트랜잭션에서 UNSTABLE로 두고 이벤트를 쓴다. 실패는 앱 오류 한 줄로 남기고 던지지 않는다(끊김 기록은
     * 대화를 멈추게 하지 않는다).
     *
     * @param occurredAt 끊김이 난 시각(닫힘 처리 시각 · 무음 점검 시각)
     * @return 창 안 끊김 횟수(이 건 포함). 실패면 빈 값
     */
    public Optional<Integer> recordDisconnect(UUID deviceId, UUID connectionId, DeviceDisconnectRepository.Kind kind,
            Integer closeCode, Instant occurredAt, String traceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        try {
            Recorded result = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
                // 행을 먼저 잠근다: 같은 기기의 복귀 · 인계가 이 기록을 본 뒤에 횟수를 센다
                connections.findForUpdate(deviceId);
                Instant now = clock.now();
                disconnects.record(new DisconnectEvent(UUID.randomUUID(), deviceId, connectionId, kind,
                        kind == DeviceDisconnectRepository.Kind.SILENT ? null : closeCode, occurredAt), now);
                int recent = disconnects.countRecent(deviceId, now, unstableWindow);
                ConnectionState from = connections.setRecentDisconnects(deviceId, recent, now)
                        .map(DeviceConnectionRow::state).orElse(null);
                Optional<DeviceConnectionRow> changed = Optional.empty();
                if (DeviceStatusPolicy.recoveredState(recent, unstableCount) == ConnectionState.UNSTABLE) {
                    changed = connections.markUnstableByDevice(deviceId, recent, now);
                    changed.ifPresent(row -> {
                        writeEvent(row, now, traceId);
                        mirror(row);
                    });
                }
                return new Recorded(recent, from, changed);
            }));
            if (result != null && result.changed().isPresent()) {
                logChange(deviceId, result.from(), result.changed().get().state(), null, traceId);
            }
            return result == null ? Optional.empty() : Optional.of(result.recent());
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.DEVICE_GATEWAY, e);
            return Optional.empty();
        }
    }

    /** U3: 창보다 오래된 끊김 사건을 지운다(끊김 점검 주기 작업). 지운 행 수(실패면 0, 앱 오류 한 줄). */
    public int purgeOldDisconnects() {
        try {
            Integer deleted = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                    () -> disconnects.deleteOlderThan(clock.now().minus(unstableWindow)));
            return deleted == null ? 0 : deleted;
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.DEVICE_GATEWAY, e);
            return 0;
        }
    }

    /**
     * 복귀 UPDATE가 0행이었을 때(J391): 원장을 다시 읽어 그 행이 아직 이 연결의 것이면 사본을 원장 상태로 맞춘다. 사본이 원장보다 앞서간 경우
     * (되돌아간 상태 트랜잭션 · 먼저 복귀시킨 다른 HEARTBEAT)를 바로잡는다. 다른 연결로 교체된 행이면 사본은 뜻이 없어 건드리지 않는다.
     *
     * @return 맞춘 상태(이 연결의 행이 아니면 빈 값)
     */
    public Optional<ConnectionState> resyncKnownState(DeviceConnection connection) {
        Objects.requireNonNull(connection, "connection");
        Optional<DeviceConnectionRow> row = connection.principal()
                .flatMap(principal -> dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                        () -> connections.find(principal.deviceId())))
                .filter(found -> connection.connectionId().equals(found.connectionId()));
        row.ifPresent(found -> connection.knownState(found.state()));
        return row.map(DeviceConnectionRow::state);
    }

    /**
     * 40초 무음(BR4.8 ②): {@code last_seen_at <= checkedAt − after}일 때만 DISCONNECTED. U3-J2: 바뀌었으면 같은 트랜잭션에 무음 끊김 한 건을
     * 적고 창 안 횟수를 원장에 쓴다 — 끊김 상태를 본 HEARTBEAT 복귀는 늘 이 건까지 센다.
     */
    public Optional<DeviceConnectionRow> markDisconnected(UUID deviceId, UUID connectionId, Duration after) {
        return change(deviceId, null, null, () -> {
            Instant now = clock.now();
            Optional<DeviceConnectionRow> row = connections.markDisconnected(deviceId, connectionId, now, after);
            if (row.isEmpty()) {
                return row;
            }
            disconnects.record(new DisconnectEvent(UUID.randomUUID(), deviceId, connectionId,
                    DeviceDisconnectRepository.Kind.SILENT, null, now), now);
            int recent = disconnects.countRecent(deviceId, now, unstableWindow);
            return connections.setRecentDisconnects(deviceId, recent, now).or(() -> row);
        });
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
            // U3: 행을 먼저 잠그고 읽는다 — 끊김 횟수 · 이전 상태가 그 사이 커밋된 기록을 놓치지 않는다(UPDATE도 같은 행을 잠그므로 잠금 순서는
            // 그대로다)
            ConnectionState from = connections.findForUpdate(deviceId).map(DeviceConnectionRow::state).orElse(null);
            Instant now = clock.now();
            Optional<DeviceConnectionRow> row = update.get();
            row.ifPresent(changed -> {
                writeEvent(changed, now, traceId);
                // 사본은 커밋 전, UPDATE가 행 잠금을 쥔 동안 맞춘다(J391). 커밋이 실패해 되돌아가면 사본만 앞서가는데, 그때는 다음 HEARTBEAT가
                // 0행 복귀를 한 번 더 시도하고 원장을 다시 읽어 맞출 뿐이다(HeartbeatHandler.recover)
                mirror(changed);
            });
            return new Change(from, row);
        }));
        if (result == null || result.row().isEmpty()) {
            return Optional.empty();
        }
        DeviceConnectionRow row = result.row().get();
        logChange(deviceId, result.from(), row.state(), closeCode, traceId);
        return result.row();
    }

    /** 원장 행 상태를 그 연결 객체의 사본에 반영한다(HEARTBEAT 복귀 판정, J55). 상태 UPDATE와 같은 트랜잭션 안에서 부른다(J391). */
    private void mirror(DeviceConnectionRow row) {
        if (row.connectionId() != null) {
            sockets.byConnectionId(row.connectionId()).ifPresent(connection -> connection.knownState(row.state()));
        }
    }

    private void writeEvent(DeviceConnectionRow row, Instant now, String traceId) {
        Instant since = (row.state() == ConnectionState.CONNECTED || row.state() == ConnectionState.UNSTABLE)
                && row.connectionSince() != null ? row.connectionSince() : now;
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

    private record Recorded(int recent, ConnectionState from, Optional<DeviceConnectionRow> changed) {
    }

}
