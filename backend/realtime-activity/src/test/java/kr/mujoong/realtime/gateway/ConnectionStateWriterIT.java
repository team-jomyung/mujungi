package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 연결 원장 기록 장치와 연결 객체 사본의 순서 시험(3.6 수정 J391 · BR4.8 · J55 · RD-4).
 *
 * <p>경주 (B)의 앞부분을 결정적으로 재현한다: 끊김 UPDATE가 커밋된 뒤에야 사본(knownState)을 맞추면, 커밋 ~ 사본 갱신 사이에 원장에서
 * DISCONNECTED를 본 HEARTBEAT 수신 스레드는 사본이 아직 CONNECTED라 복귀 작업을 띄우지 않고 원장은 다음 HEARTBEAT까지 끊김으로 남는다.
 * 그 창을 넓히려고 사본 갱신이 레지스트리에서 연결을 찾는 순간을 걸어 두고(탐침 레지스트리), 그 순간 다른 연결로 읽은 원장과 사본을 나란히
 * 본다. 원장이 새 상태를 보여 주는 어떤 순간에도 사본이 뒤처져 있으면 안 된다.
 */
@Tag("u1")
@Tag("db")
class ConnectionStateWriterIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final Duration DISCONNECT = Duration.ofSeconds(40);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static RealtimeDb db;
    private final MutableClock clock = new MutableClock(T0);
    private final LogCapture logs = LogCapture.detached();
    private DeviceConnectionRepository connections;
    private ProbeRegistry sockets;
    private ConnectionStateWriter writer;
    private DeviceConnection connection;
    private UUID device;

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
        device = UUID.randomUUID();
        UUID guardian = UUID.randomUUID();
        connection = new DeviceConnection(UUID.randomUUID(), "0123456789abcdef0123456789abcdef", T0);
        connection.verified(new DeviceConnection.Principal(device, guardian, UUID.randomUUID()));
        sockets = new ProbeRegistry(connection);
        RealtimeMetrics metrics = RealtimeMetrics.inMemory();
        EventLogger events = TestEventLoggers.strict(clock, logs, metrics);
        writer = new ConnectionStateWriter(connections, new DeviceDisconnectRepository(db.jdbc()),
                new RealtimeOutboxWriter(new RealtimeOutboxRepository(db.jdbc()), JsonCodec.shared()),
                new DataSourceTransactionManager(db.dataSource()), new DbRetry(2, () -> 0L, duration -> { }), events,
                clock, sockets, Duration.ofMinutes(5), 2);
        db.inTransaction(() -> connections.handover(device, connection.connectionId(), guardian, null, T0));
        connection.knownState(ConnectionState.CONNECTED);
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void theCopyIsNeverBehindTheLedgerWhileAnObserverCanSeeTheNewState() throws InterruptedException {
        clock.advance(DISCONNECT.plusSeconds(1));
        Thread checker = Thread.ofVirtual().name("it-disconnect-check")
                .start(() -> writer.markDisconnected(device, connection.connectionId(), DISCONNECT));

        // 사본 갱신이 연결을 찾는 순간: HEARTBEAT 수신 스레드가 이때 원장을 봤다고 치자
        assertThat(sockets.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("사본 갱신 진입").isTrue();
        ConnectionState ledgerSeen = connections.find(device).map(DeviceConnectionRow::state).orElseThrow();
        ConnectionState copySeen = connection.knownState();
        boolean heartbeatWouldRecover = DeviceStatusPolicy.heartbeatRecovers(copySeen);
        sockets.proceed.countDown();
        checker.join(WAIT.toMillis());
        assertThat(checker.isAlive()).isFalse();

        // 끝나면 둘 다 끊김이다
        assertThat(connections.find(device).orElseThrow().state()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(connection.knownState()).isEqualTo(ConnectionState.DISCONNECTED);
        // 핵심: 관찰자가 원장에서 DISCONNECTED를 볼 수 있었던 순간이라면 사본도 이미 DISCONNECTED라 HEARTBEAT가 복귀를 띄운다(J391)
        if (ledgerSeen == ConnectionState.DISCONNECTED) {
            assertThat(copySeen).as("원장이 끊김을 보여 주는데 사본은 %s", copySeen).isEqualTo(ConnectionState.DISCONNECTED);
            assertThat(heartbeatWouldRecover).isTrue();
        } else {
            // 사본을 커밋 전에 맞추는 구현: 그 순간 원장은 아직 CONNECTED(미커밋)이고 사본이 먼저 끊김이 된다 — 앞서가는 사본은 무해하다
            assertThat(ledgerSeen).isEqualTo(ConnectionState.CONNECTED);
        }
    }

    @Test
    void aStateChangeThatFindsNoLiveConnectionStillCommitsAndTheNextLookupSeesIt() {
        sockets.absent = true;
        clock.advance(DISCONNECT.plusSeconds(1));

        Optional<DeviceConnectionRow> changed = writer.markDisconnected(device, connection.connectionId(), DISCONNECT);

        assertThat(changed).map(DeviceConnectionRow::state).contains(ConnectionState.DISCONNECTED);
        assertThat(connections.find(device).orElseThrow().state()).isEqualTo(ConnectionState.DISCONNECTED);
        // 레지스트리에 없는(이미 닫힌) 연결의 사본은 건드리지 않는다
        assertThat(connection.knownState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(sockets.entered.getCount()).isZero();
    }

    @Test
    void recoveryWritesTheHeartbeatTimeIntoLastSeenAtAndMirrorsConnected() {
        sockets.proceed.countDown();
        clock.advance(DISCONNECT.plusSeconds(1));
        writer.markDisconnected(device, connection.connectionId(), DISCONNECT).orElseThrow();
        assertThat(connection.knownState()).isEqualTo(ConnectionState.DISCONNECTED);

        // 복귀 HEARTBEAT는 Clock보다 조금 전에 받았다(수신 → 보조 작업 사이)
        Instant receivedAt = clock.now();
        clock.advance(Duration.ofMillis(7));
        DeviceConnectionRow back = writer.markConnectedAgain(device, connection.connectionId(), receivedAt, "trace")
                .orElseThrow();

        assertThat(back.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(back.lastSeenAt()).isEqualTo(receivedAt);
        assertThat(back.updatedAt()).isEqualTo(clock.now());
        assertThat(connection.knownState()).isEqualTo(ConnectionState.CONNECTED);
        // 복귀 직후 끊김 후보가 아니다(J390)
        assertThat(connections.findSilent(clock.now(), DISCONNECT)).isEmpty();
        assertThat(logs.lines()).filteredOn(line -> line.contains("\"to\":\"CONNECTED\"")).hasSize(1);
    }

    @Test
    void aRecoveryThatChangesNothingReturnsEmptyAndLeavesTheCopyAndLogAlone() {
        sockets.proceed.countDown();

        assertThat(writer.markConnectedAgain(device, connection.connectionId(), clock.now(), "trace")).isEmpty();

        assertThat(connection.knownState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(connections.find(device).orElseThrow().lastSeenAt()).isEqualTo(T0);
        assertThat(logs.lines()).noneMatch(line -> line.contains("DEVICE_CONNECTION"));
        assertThat(sockets.entered.getCount()).isEqualTo(1);
    }

    @Test
    void resyncPullsTheCopyBackToTheLedgerOnlyForTheConnectionThatOwnsTheRow() {
        // 사본만 앞서간 상황(되돌아간 끊김 트랜잭션 뒤)
        connection.knownState(ConnectionState.DISCONNECTED);
        assertThat(writer.resyncKnownState(connection)).contains(ConnectionState.CONNECTED);
        assertThat(connection.knownState()).isEqualTo(ConnectionState.CONNECTED);

        // 다른 연결로 교체된 뒤의 옛 연결은 맞추지 않는다(사본은 뜻이 없다)
        db.inTransaction(() -> connections.handover(device, UUID.randomUUID(), UUID.randomUUID(), null, clock.now()));
        connection.knownState(ConnectionState.DISCONNECTED);
        assertThat(writer.resyncKnownState(connection)).isEmpty();
        assertThat(connection.knownState()).isEqualTo(ConnectionState.DISCONNECTED);

        // 확인되지 않은 연결은 기기를 몰라 맞출 수 없다
        DeviceConnection unverified = new DeviceConnection(UUID.randomUUID(), "0123456789abcdef0123456789abcdef", T0);
        assertThat(writer.resyncKnownState(unverified)).isEmpty();
    }

    /** 사본 갱신이 연결을 찾는 순간을 붙잡아 두는 레지스트리(이 시험 전용). 열린 소켓 지도 대신 연결 하나를 직접 돌려준다. */
    private static final class ProbeRegistry extends SocketRegistry {

        private final DeviceConnection connection;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);
        private volatile boolean absent;

        private ProbeRegistry(DeviceConnection connection) {
            this.connection = connection;
        }

        @Override
        public Optional<DeviceConnection> byConnectionId(UUID connectionId) {
            entered.countDown();
            if (absent) {
                return Optional.empty();
            }
            try {
                if (!proceed.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("탐침 레지스트리를 풀어 주지 않았다");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return connection.connectionId().equals(connectionId) ? Optional.of(connection) : Optional.empty();
        }
    }
}
