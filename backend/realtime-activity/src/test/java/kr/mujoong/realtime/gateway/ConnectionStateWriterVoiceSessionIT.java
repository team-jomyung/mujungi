package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 연결 원장 기록 장치의 U3 불안정 판정 통합 시험(BR4.8 ① · U3-R1 · U3-J2 · U3-J3 · AC2.1.4 · AC2.1.10).
 *
 * <p>실제 DB에서 끊김 사건 두 건이 창 안에 쌓이면 지금 연결이 UNSTABLE이 되고 인계 · 복귀도 UNSTABLE로 가며, 창이 지나 정상 왕복이 오면
 * CONNECTED로 돌아온다. 왕복 초과 UNSTABLE과 정상 왕복 복귀, 무음 끊김의 사건 기록, 창 밖 사건 정리를 본다. 상태가 바뀔 때마다
 * DeviceStatusChanged가 Outbox에 쓰이고 recent_disconnects가 실린다.
 */
@Tag("voice-session")
@Tag("db")
class ConnectionStateWriterVoiceSessionIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Duration DISCONNECT = Duration.ofSeconds(40);

    private static RealtimeDb db;
    private final MutableClock clock = new MutableClock(T0);
    private final LogCapture logs = LogCapture.detached();
    private DeviceConnectionRepository connections;
    private DeviceDisconnectRepository disconnects;
    private DirectRegistry sockets;
    private ConnectionStateWriter writer;
    private DeviceConnection connection;
    private UUID device;
    private UUID guardian;

    @BeforeAll
    static void openDatabase() {
        db = RealtimeDb.open();
    }

    @AfterAll
    static void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        db.wipe();
        connections = new DeviceConnectionRepository(db.jdbc());
        disconnects = new DeviceDisconnectRepository(db.jdbc());
        device = UUID.randomUUID();
        guardian = UUID.randomUUID();
        sockets = new DirectRegistry();
        RealtimeMetrics metrics = RealtimeMetrics.inMemory();
        EventLogger events = TestEventLoggers.strict(clock, logs, metrics);
        writer = new ConnectionStateWriter(connections, disconnects,
                new RealtimeOutboxWriter(new RealtimeOutboxRepository(db.jdbc()), JsonCodec.shared()),
                new DataSourceTransactionManager(db.dataSource()), new DbRetry(2, () -> 0L, duration -> { }), events,
                clock, sockets, WINDOW, 2);
        connection = connect();
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void twoDisconnectsInsideTheWindowMakeTheCurrentConnectionUnstableAndTheWindowPassingRestoresIt() {
        UUID dropped = UUID.randomUUID();
        assertThat(writer.recordDisconnect(device, dropped, DeviceDisconnectRepository.Kind.CLOSED, 1006, clock.now(),
                "trace")).contains(1);
        assertThat(connections.find(device).orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(connections.find(device).orElseThrow().recentDisconnects()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        assertThat(writer.recordDisconnect(device, UUID.randomUUID(), DeviceDisconnectRepository.Kind.CLOSED, 1001,
                clock.now(), "trace")).contains(2);
        DeviceConnectionRow unstable = connections.find(device).orElseThrow();
        assertThat(unstable.state()).isEqualTo(ConnectionState.UNSTABLE);
        assertThat(unstable.recentDisconnects()).isEqualTo(2);
        assertThat(connection.knownState()).as("사본도 같은 트랜잭션에서 맞춘다").isEqualTo(ConnectionState.UNSTABLE);
        assertThat(outboxStates()).isEqualTo(2L);
        assertThat(logs.lines()).anyMatch(line -> line.contains("\"from\":\"CONNECTED\"") && line.contains("\"to\":\"UNSTABLE\""));

        // 아직 창 안: 정상 왕복이 와도 그대로다(횟수만 다시 적는다)
        assertThat(writer.markStableIfCalm(device, connection.connectionId(), "trace")).isEmpty();
        assertThat(connections.find(device).orElseThrow().state()).isEqualTo(ConnectionState.UNSTABLE);

        // 새 연결의 인계도 UNSTABLE로 간다(U3-R1)
        DeviceConnection next = connect();
        assertThat(next.knownState()).isEqualTo(ConnectionState.UNSTABLE);
        assertThat(connections.find(device).orElseThrow().state()).isEqualTo(ConnectionState.UNSTABLE);
        assertThat(connections.find(device).orElseThrow().connectionId()).isEqualTo(next.connectionId());

        // 창(5분)이 지나면 정상 왕복에 CONNECTED
        clock.advance(WINDOW);
        DeviceConnectionRow calm = writer.markStableIfCalm(device, next.connectionId(), "trace").orElseThrow();
        assertThat(calm.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(calm.recentDisconnects()).isZero();
        assertThat(next.knownState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(writer.purgeOldDisconnects()).isEqualTo(2);
        assertThat(disconnects.findByDevice(device)).isEmpty();
    }

    @Test
    void aSlowRoundTripMarksUnstableAndANormalOneRecoversWhenDisconnectsAreRare() {
        DeviceConnectionRow slow = writer.markUnstable(device, connection.connectionId(), "trace").orElseThrow();
        assertThat(slow.state()).isEqualTo(ConnectionState.UNSTABLE);
        assertThat(slow.recentDisconnects()).isZero();
        assertThat(connection.knownState()).isEqualTo(ConnectionState.UNSTABLE);
        // 이미 UNSTABLE이면 다시 쓰지 않는다
        assertThat(writer.markUnstable(device, connection.connectionId(), "trace")).isEmpty();

        DeviceConnectionRow back = writer.markStableIfCalm(device, connection.connectionId(), "trace").orElseThrow();
        assertThat(back.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(connection.knownState()).isEqualTo(ConnectionState.CONNECTED);
        // 다른 연결(교체된 옛 연결)의 왕복은 상태를 바꾸지 못한다
        assertThat(writer.markUnstable(device, UUID.randomUUID(), "trace")).isEmpty();
        assertThat(outboxStates()).isEqualTo(3L);
    }

    @Test
    void aSilentDisconnectIsRecordedWithTheStateChangeAndTheSecondRecoveryInsideTheWindowGoesToUnstable() {
        clock.advance(DISCONNECT.plusSeconds(1));
        DeviceConnectionRow silent = writer.markDisconnected(device, connection.connectionId(), DISCONNECT).orElseThrow();
        // 같은 트랜잭션: 끊김 상태와 함께 사건 · 창 안 횟수가 이미 있다(복귀가 늘 이 건까지 센다, U3-J2)
        assertThat(silent.recentDisconnects()).isEqualTo(1);
        assertThat(disconnects.findByDevice(device)).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(DeviceDisconnectRepository.Kind.SILENT);
            assertThat(event.closeCode()).isNull();
            assertThat(event.occurredAt()).isEqualTo(clock.now());
        });
        DeviceConnectionRow first = writer.markConnectedAgain(device, connection.connectionId(), clock.now(), "trace")
                .orElseThrow();
        assertThat(first.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(first.recentDisconnects()).isEqualTo(1);

        clock.advance(DISCONNECT.plusSeconds(1));
        writer.markDisconnected(device, connection.connectionId(), DISCONNECT).orElseThrow();
        // 무음 끊김은 코드를 적지 않는다(기록 장치로 따로 적을 때 값이 와도)
        writer.recordDisconnect(UUID.randomUUID(), UUID.randomUUID(), DeviceDisconnectRepository.Kind.SILENT, 1006,
                clock.now(), null);
        assertThat(disconnects.findByDevice(device)).hasSize(2).allSatisfy(event -> assertThat(event.closeCode()).isNull());
        DeviceConnectionRow second = writer.markConnectedAgain(device, connection.connectionId(), clock.now(), "trace")
                .orElseThrow();
        assertThat(second.state()).isEqualTo(ConnectionState.UNSTABLE);
        assertThat(second.recentDisconnects()).isEqualTo(2);
        assertThat(second.lastSeenAt()).isEqualTo(clock.now());
        assertThat(connection.knownState()).isEqualTo(ConnectionState.UNSTABLE);
    }

    /** 리뷰 반영 2: 1006 · 전송 오류로 끊긴 연결이 40초 뒤 무음 점검에도 걸리면 끊김은 한 번이다(계약 '5분 안 2회'). */
    @Test
    void aConnectionAlreadyCountedAsClosedIsNotCountedAgainWhenTheSilenceCheckMarksItDisconnected() {
        UUID closedId = connection.connectionId();
        assertThat(writer.recordDisconnect(device, closedId, DeviceDisconnectRepository.Kind.CLOSED, 1006, clock.now(),
                "trace")).contains(1);

        clock.advance(DISCONNECT.plusSeconds(1));
        DeviceConnectionRow silent = writer.markDisconnected(device, closedId, DISCONNECT).orElseThrow();

        assertThat(silent.state()).as("원장은 그대로 끊김으로 간다").isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(silent.recentDisconnects()).isEqualTo(1);
        assertThat(disconnects.findByDevice(device)).singleElement()
                .satisfies(event -> assertThat(event.kind()).isEqualTo(DeviceDisconnectRepository.Kind.CLOSED));

        // 다시 붙자마자 UNSTABLE이 되지 않는다
        DeviceConnection next = connect();
        assertThat(next.knownState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(connections.find(device).orElseThrow().recentDisconnects()).isEqualTo(1);
    }

    @Test
    void aLateCloseOfAConnectionAlreadyCountedAsSilentIsNotCountedAgain() {
        clock.advance(DISCONNECT.plusSeconds(1));
        writer.markDisconnected(device, connection.connectionId(), DISCONNECT).orElseThrow();

        assertThat(writer.recordDisconnect(device, connection.connectionId(), DeviceDisconnectRepository.Kind.CLOSED, 1006,
                clock.now(), "trace")).contains(1);

        assertThat(disconnects.findByDevice(device)).singleElement()
                .satisfies(event -> assertThat(event.kind()).isEqualTo(DeviceDisconnectRepository.Kind.SILENT));
        assertThat(connections.find(device).orElseThrow().recentDisconnects()).isEqualTo(1);
    }

    /** 2차 리뷰 3: 무음으로 센 옛 연결이 기기가 새 연결로 돌아온 뒤 늦게 1006으로 닫혀도 같은 끊김이다. */
    @Test
    void aLateCloseOfASilentConnectionAfterTheDeviceCameBackOnANewConnectionIsNotCountedAgain() {
        DeviceConnection old = connection;
        clock.advance(DISCONNECT.plusSeconds(1));
        writer.markDisconnected(device, old.connectionId(), DISCONNECT).orElseThrow();
        DeviceConnection next = connect();
        assertThat(next.knownState()).isEqualTo(ConnectionState.CONNECTED);

        assertThat(writer.recordDisconnect(device, old.connectionId(), DeviceDisconnectRepository.Kind.CLOSED, 1006,
                clock.now(), old.lastMessageAt(), "trace")).contains(1);

        assertThat(disconnects.findByDevice(device)).singleElement()
                .satisfies(event -> assertThat(event.kind()).isEqualTo(DeviceDisconnectRepository.Kind.SILENT));
        assertThat(connections.find(device).orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
    }

    /** 같은 연결이 무음 뒤 HEARTBEAT로 복귀했다가 다시 끊기면 새 끊김이다(복귀 뒤 들은 시각이 무음 기록보다 늦다). */
    @Test
    void aCloseAfterTheSilentConnectionRecoveredIsANewDisconnect() {
        clock.advance(DISCONNECT.plusSeconds(1));
        writer.markDisconnected(device, connection.connectionId(), DISCONNECT).orElseThrow();
        clock.advance(Duration.ofSeconds(2));
        connection.touch(clock.now());
        writer.markConnectedAgain(device, connection.connectionId(), clock.now(), "trace").orElseThrow();

        clock.advance(Duration.ofSeconds(5));
        assertThat(writer.recordDisconnect(device, connection.connectionId(), DeviceDisconnectRepository.Kind.CLOSED, 1006,
                clock.now(), connection.lastMessageAt(), "trace")).contains(2);
    }

    @Test
    void aSilenceAfterAnotherConnectionsCloseStillCountsAsItsOwnDisconnect() {
        assertThat(writer.recordDisconnect(device, UUID.randomUUID(), DeviceDisconnectRepository.Kind.CLOSED, 1006,
                clock.now(), "trace")).contains(1);

        clock.advance(DISCONNECT.plusSeconds(1));
        DeviceConnectionRow silent = writer.markDisconnected(device, connection.connectionId(), DISCONNECT).orElseThrow();

        assertThat(silent.recentDisconnects()).isEqualTo(2);
        assertThat(disconnects.findByDevice(device)).extracting(DeviceDisconnectRepository.DisconnectEvent::kind)
                .containsExactly(DeviceDisconnectRepository.Kind.CLOSED, DeviceDisconnectRepository.Kind.SILENT);
    }

    @Test
    void aDisconnectOfAnotherDeviceDoesNotCountAndAFailedRecordIsReportedNotThrown() {
        UUID other = UUID.randomUUID();
        writer.recordDisconnect(other, UUID.randomUUID(), DeviceDisconnectRepository.Kind.CLOSED, 1006, clock.now(), null);
        writer.recordDisconnect(other, UUID.randomUUID(), DeviceDisconnectRepository.Kind.CLOSED, 1006, clock.now(), null);

        assertThat(connections.find(device).orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(connections.find(device).orElseThrow().recentDisconnects()).isZero();
        // 범위 밖 코드는 CHECK가 거부한다 — 기록 장치는 던지지 않고 앱 오류 한 줄만 남긴다
        assertThat(writer.recordDisconnect(device, UUID.randomUUID(), DeviceDisconnectRepository.Kind.CLOSED, 99,
                clock.now(), null)).isEmpty();
        assertThat(logs.lines()).anyMatch(line -> line.contains("APP_ERROR"));
        assertThat(disconnects.findByDevice(device)).isEmpty();
    }

    /** 새 연결을 확인 · 인계한다(원장 CONNECTED 또는 UNSTABLE, 레지스트리 현재 연결). */
    private DeviceConnection connect() {
        DeviceConnection next = new DeviceConnection(UUID.randomUUID(), "0123456789abcdef0123456789abcdef", clock.now());
        next.verified(new DeviceConnection.Principal(device, guardian, UUID.randomUUID()));
        sockets.add(next);
        writer.handover(next);
        sockets.replaceCurrent(device, next);
        next.advance(DeviceConnection.Phase.AWAIT_VERIFY, DeviceConnection.Phase.AWAIT_HELLO);
        next.advance(DeviceConnection.Phase.AWAIT_HELLO, DeviceConnection.Phase.READY);
        return next;
    }

    private long outboxStates() {
        return db.single("select count(*) from realtime.realtime_outbox_event where event_type = 'DeviceStatusChanged'",
                new MapSqlParameterSource(), Long.class);
    }

    /** 송신기 없는 시험 연결도 id로 찾는 레지스트리(사본 맞추기 확인용, 이 시험 전용). */
    private static final class DirectRegistry extends SocketRegistry {

        private final Map<UUID, DeviceConnection> byId = new ConcurrentHashMap<>();

        void add(DeviceConnection connection) {
            byId.put(connection.connectionId(), connection);
        }

        @Override
        public Optional<DeviceConnection> byConnectionId(UUID connectionId) {
            return connectionId == null ? Optional.empty() : Optional.ofNullable(byId.get(connectionId));
        }
    }
}
