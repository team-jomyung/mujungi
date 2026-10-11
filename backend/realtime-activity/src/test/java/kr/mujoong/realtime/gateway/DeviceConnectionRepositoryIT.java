package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository.Handover;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository.LastSeen;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 기기 연결 원장 데이터 접근 통합 시험(U1 3.5 Step 18).
 *
 * <p>증명: BR3.7(HELLO_OK 때 FOR UPDATE 교체 · 첫 접속 행 생성 · 잠금을 나중에 얻은 연결이 이김 · 옛 connection_id 기록은 0행) ·
 * BR4.8(정상 닫기 즉시 OFFLINE · 마지막 기기 메시지 뒤 40초에 DISCONNECTED, 경계 직전 0행 · 직후 1행 · 점검이 읽은 뒤 온 HEARTBEAT는
 * 덮지 않음 · UNRESPONSIVE는 명령 시한에만) · J55(같은 연결의 HEARTBEAT로 CONNECTED 복귀) · J125(last_seen_at은 HEARTBEAT 때) ·
 * BR3.8(해제는 device_id만 조건, 멱등) · BR6.13(재시작 때 CONNECTED · UNSTABLE → DISCONNECTED, cid 비움) · PD-5(last_seen 배치).
 */
@Tag("u1")
@Tag("db")
class DeviceConnectionRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    /** {@code mujoong.timing.device.disconnect} 운영값. */
    private static final Duration DISCONNECT = Duration.ofSeconds(40);
    private static final Duration TICK = Duration.ofMillis(1);

    private static RealtimeDb db;
    private DeviceConnectionRepository connections;
    private UUID device;
    private UUID guardian;
    private UUID child;

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
        guardian = UUID.randomUUID();
        child = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    // ------------------------------------------------------------------ 교체 (BR3.7)

    @Test
    void theFirstHelloOkCreatesAConnectedRow() {
        UUID cid = UUID.randomUUID();
        assertThat(connections.find(device)).isEmpty();

        Handover handover = handover(cid, T0);

        assertThat(handover.firstConnection()).isTrue();
        assertThat(handover.replacedConnectionId()).isNull();
        DeviceConnectionRow row = connections.find(device).orElseThrow();
        assertThat(row).isEqualTo(handover.current());
        assertThat(row.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(row.connectionId()).isEqualTo(cid);
        assertThat(row.guardianId()).isEqualTo(guardian);
        assertThat(row.childId()).isEqualTo(child);
        assertThat(row.connectionSince()).isEqualTo(T0);
        assertThat(row.lastSeenAt()).isEqualTo(T0);
        assertThat(row.updatedAt()).isEqualTo(T0);
        assertThat(row.recentDisconnects()).isZero();
        assertThat(row.lastRttMs()).isNull();
    }

    @Test
    void aLaterHelloOkReplacesTheOldConnectionWhoseLateWritesAreDropped() {
        UUID oldCid = UUID.randomUUID();
        UUID newCid = UUID.randomUUID();
        handover(oldCid, T0);

        Handover replaced = handover(newCid, T0.plusSeconds(10));

        assertThat(replaced.firstConnection()).isFalse();
        assertThat(replaced.replacedConnectionId()).isEqualTo(oldCid);
        assertThat(replaced.previous().connectionSince()).isEqualTo(T0);
        // 4001로 닫힌 옛 연결의 닫힘 · HEARTBEAT · 시한 기록은 새 연결 상태를 덮지 않는다(0행)
        assertThat(connections.markClosed(device, oldCid, T0.plusSeconds(11))).isEmpty();
        assertThat(connections.markSeen(device, oldCid, T0.plusSeconds(11), 30)).isFalse();
        assertThat(connections.markUnresponsive(device, oldCid, T0.plusSeconds(11))).isEmpty();
        assertThat(connections.markDisconnected(device, oldCid, T0.plusSeconds(60), DISCONNECT)).isEmpty();

        DeviceConnectionRow row = connections.find(device).orElseThrow();
        assertThat(row.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(row.connectionId()).isEqualTo(newCid);
        assertThat(row.connectionSince()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void racingFirstConnectionsSerializeOnTheRowAndTheLaterLockWins() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Handover> a = pool.submit(() -> db.inTransaction(() -> {
                Handover result = connections.handover(device, first, guardian, child, T0);
                firstLocked.countDown();
                await(release);
                return result;
            }));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            // B의 첫 접속 INSERT는 A의 커밋을 기다렸다가 아무것도 하지 않고, A가 쓴 행을 잠가 덮는다
            Future<Handover> b = pool.submit(() -> db.inTransaction(
                    () -> connections.handover(device, second, guardian, child, T0.plusMillis(5))));
            release.countDown();
            Handover winnerOfFirst = a.get(10, TimeUnit.SECONDS);
            Handover later = b.get(10, TimeUnit.SECONDS);

            assertThat(winnerOfFirst.firstConnection()).isTrue();
            assertThat(later.firstConnection()).isFalse();
            assertThat(later.replacedConnectionId()).isEqualTo(first);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(connections.find(device).orElseThrow().connectionId()).isEqualTo(second);
        assertThat(connections.findAll()).hasSize(1);
    }

    @Test
    void handoverOnlyRunsInsideTheHelloOkTransaction() {
        assertThatThrownBy(() -> connections.handover(device, UUID.randomUUID(), guardian, child, T0))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(connections.find(device)).isEmpty();
        // 아이 없는 기기도 접속할 수 있다
        Handover handover = db.inTransaction(() -> connections.handover(device, UUID.randomUUID(), guardian, null, T0));
        assertThat(handover.current().childId()).isNull();
    }

    // ------------------------------------------------------------------ HEARTBEAT · 끊김 (BR4.8 · J55 · J125)

    @Test
    void heartbeatsMoveLastSeenForwardForTheCurrentConnectionOnly() {
        UUID cid = UUID.randomUUID();
        handover(cid, T0);

        assertThat(connections.markSeen(device, cid, T0.plusSeconds(5), 42)).isTrue();
        // 늦게 도착한 더 이른 시각은 되돌리지 않는다
        assertThat(connections.markSeen(device, cid, T0.plusSeconds(3), 10)).isFalse();
        assertThat(connections.markSeen(device, cid, T0.plusSeconds(10), null)).isTrue();

        DeviceConnectionRow row = connections.find(device).orElseThrow();
        assertThat(row.lastSeenAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(row.lastRttMs()).isEqualTo(42);
        assertThat(row.state()).isEqualTo(ConnectionState.CONNECTED);

        // 기록기 배치(PD-5): 현재 연결 한 건만 쓰이고 옛 연결 · 모르는 기기는 0행
        UUID other = UUID.randomUUID();
        int written = connections.markSeenAll(List.of(
                new LastSeen(device, cid, T0.plusSeconds(15), 38),
                new LastSeen(device, UUID.randomUUID(), T0.plusSeconds(16), null),
                new LastSeen(other, UUID.randomUUID(), T0.plusSeconds(16), null)));
        assertThat(written).isEqualTo(1);
        assertThat(connections.markSeenAll(List.of())).isZero();
        assertThat(connections.find(device).orElseThrow().lastSeenAt()).isEqualTo(T0.plusSeconds(15));
        assertThatThrownBy(() -> new LastSeen(device, cid, T0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSilentConnectionBecomesDisconnectedExactlyFortySecondsAfterItsLastMessage() {
        UUID cid = UUID.randomUUID();
        handover(cid, T0);
        Instant lastSeen = T0.plusSeconds(2);
        connections.markSeen(device, cid, lastSeen, 25);

        Instant justBefore = lastSeen.plus(DISCONNECT).minus(TICK);
        assertThat(connections.findSilent(justBefore, DISCONNECT)).isEmpty();
        assertThat(connections.markDisconnected(device, cid, justBefore, DISCONNECT)).isEmpty();

        Instant boundary = lastSeen.plus(DISCONNECT);
        assertThat(connections.findSilent(boundary, DISCONNECT)).extracting(DeviceConnectionRow::deviceId)
                .containsExactly(device);
        DeviceConnectionRow disconnected = connections.markDisconnected(device, cid, boundary, DISCONNECT).orElseThrow();
        assertThat(disconnected.state()).isEqualTo(ConnectionState.DISCONNECTED);
        // 소켓이 열린 채 무음인 경우도 있으므로 connection_id는 남는다
        assertThat(disconnected.connectionId()).isEqualTo(cid);
        assertThat(disconnected.updatedAt()).isEqualTo(boundary);
        assertThat(connections.findSilent(boundary.plusSeconds(5), DISCONNECT)).isEmpty();
        assertThat(connections.markDisconnected(device, cid, boundary.plusSeconds(5), DISCONNECT)).isEmpty();

        // J55: 같은 연결의 다음 HEARTBEAT가 CONNECTED로 되돌린다
        connections.markSeen(device, cid, boundary.plusSeconds(6), 30);
        DeviceConnectionRow back = connections.markConnectedAgain(device, cid, boundary.plusSeconds(6),
                boundary.plusSeconds(6)).orElseThrow();
        assertThat(back.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(connections.markConnectedAgain(device, cid, boundary.plusSeconds(7), boundary.plusSeconds(7)))
                .isEmpty();
        assertThatThrownBy(() -> connections.markDisconnected(device, cid, boundary, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoveryRaisesLastSeenAtToTheHeartbeatTimeButNeverLowersIt() {
        UUID cid = UUID.randomUUID();
        handover(cid, T0);
        Instant boundary = T0.plus(DISCONNECT);
        connections.markDisconnected(device, cid, boundary, DISCONNECT).orElseThrow();

        // 복귀 UPDATE 자신이 HEARTBEAT 수신 시각을 last_seen_at에 쓴다 — 기록기 배치가 아직 쓰지 않았어도 끊김 후보가 아니다(3.6 수정 J390)
        Instant beat = boundary.plusSeconds(1);
        DeviceConnectionRow back = connections.markConnectedAgain(device, cid, beat, beat.plusMillis(5)).orElseThrow();
        assertThat(back.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(back.lastSeenAt()).isEqualTo(beat);
        assertThat(back.updatedAt()).isEqualTo(beat.plusMillis(5));
        assertThat(connections.findSilent(beat.plusMillis(5), DISCONNECT)).isEmpty();
        assertThat(connections.markDisconnected(device, cid, beat.plusMillis(5), DISCONNECT)).isEmpty();

        // 기록기 배치가 더 늦은 HEARTBEAT를 먼저 썼으면 복귀는 그 값을 내리지 않는다(greatest)
        connections.markDisconnected(device, cid, beat.plus(DISCONNECT), DISCONNECT).orElseThrow();
        Instant later = beat.plus(DISCONNECT).plusSeconds(2);
        assertThat(connections.markSeen(device, cid, later, 20)).isTrue();
        DeviceConnectionRow kept = connections.markConnectedAgain(device, cid, later.minusSeconds(1), later).orElseThrow();
        assertThat(kept.lastSeenAt()).isEqualTo(later);
        // 옛 연결의 복귀는 0행이고 last_seen_at도 건드리지 않는다
        assertThat(connections.markConnectedAgain(device, UUID.randomUUID(), later.plusSeconds(9), later.plusSeconds(9)))
                .isEmpty();
        assertThat(connections.find(device).orElseThrow().lastSeenAt()).isEqualTo(later);
    }

    @Test
    void aHeartbeatThatArrivesAfterTheCheckReadTheRowIsNotOverwritten() {
        UUID cid = UUID.randomUUID();
        handover(cid, T0);
        Instant checkedAt = T0.plus(DISCONNECT).plusSeconds(1);

        // 점검이 후보로 읽은 직후 HEARTBEAT가 도착한다
        assertThat(connections.findSilent(checkedAt, DISCONNECT)).hasSize(1);
        assertThat(connections.markSeen(device, cid, checkedAt, 33)).isTrue();

        assertThat(connections.markDisconnected(device, cid, checkedAt, DISCONNECT)).isEmpty();
        assertThat(connections.find(device).orElseThrow().state()).isEqualTo(ConnectionState.CONNECTED);
    }

    @Test
    void unresponsiveIsOnlyForLiveConnectionsAndAHeartbeatClearsIt() {
        UUID cid = UUID.randomUUID();
        handover(cid, T0);

        DeviceConnectionRow unresponsive = connections.markUnresponsive(device, cid, T0.plusSeconds(20)).orElseThrow();
        assertThat(unresponsive.state()).isEqualTo(ConnectionState.UNRESPONSIVE);
        assertThat(connections.markUnresponsive(device, cid, T0.plusSeconds(21))).isEmpty();
        // 상태 기록 뒤에 처리된 더 이른 HEARTBEAT는 last_seen_at만 올리고 updated_at을 되돌리지 않는다
        assertThat(connections.markSeen(device, cid, T0.plusSeconds(19), null)).isTrue();
        DeviceConnectionRow afterLateBeat = connections.find(device).orElseThrow();
        assertThat(afterLateBeat.lastSeenAt()).isEqualTo(T0.plusSeconds(19));
        assertThat(afterLateBeat.updatedAt()).isEqualTo(T0.plusSeconds(20));
        // UNRESPONSIVE도 40초 무음이면 끊김 후보다
        assertThat(connections.findSilent(T0.plusSeconds(19).plus(DISCONNECT), DISCONNECT)).hasSize(1);

        assertThat(connections.markConnectedAgain(device, cid, T0.plusSeconds(22), T0.plusSeconds(22)))
                .map(DeviceConnectionRow::state).contains(ConnectionState.CONNECTED);
        // 이미 끊김이면 UNRESPONSIVE로 바꾸지 않는다. 무음 40초는 복귀를 만든 HEARTBEAT(T0+22초)부터 센다(J390)
        Instant silentAt = T0.plusSeconds(22).plus(DISCONNECT);
        connections.markDisconnected(device, cid, silentAt, DISCONNECT).orElseThrow();
        assertThat(connections.markUnresponsive(device, cid, silentAt.plusSeconds(1))).isEmpty();
    }

    @Test
    void aNormalCloseGoesOfflineAtOnceAndClearsTheConnectionId() {
        UUID cid = UUID.randomUUID();
        handover(cid, T0);

        DeviceConnectionRow offline = connections.markClosed(device, cid, T0.plusSeconds(5)).orElseThrow();

        assertThat(offline.state()).isEqualTo(ConnectionState.OFFLINE);
        assertThat(offline.connectionId()).isNull();
        assertThat(offline.updatedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(connections.markClosed(device, cid, T0.plusSeconds(6))).isEmpty();
        // OFFLINE은 끊김 후보가 아니다
        assertThat(connections.findSilent(T0.plusSeconds(120), DISCONNECT)).isEmpty();
    }

    // ------------------------------------------------------------------ 해제 · 재시작 (BR3.8 · BR6.13)

    @Test
    void releaseUsesOnlyTheDeviceIdAndIsIdempotent() {
        UUID cid = UUID.randomUUID();
        handover(cid, T0);
        connections.resetAfterRestart(T0.plusSeconds(1));
        assertThat(connections.find(device).orElseThrow().connectionId()).isNull();

        // 재시작 뒤 DISCONNECTED(cid 없음) 행도 OFFLINE으로
        DeviceConnectionRow released = connections.release(device, T0.plusSeconds(2)).orElseThrow();
        assertThat(released.state()).isEqualTo(ConnectionState.OFFLINE);
        assertThat(released.connectionId()).isNull();
        assertThat(connections.release(device, T0.plusSeconds(3))).isEmpty();
        assertThat(connections.release(UUID.randomUUID(), T0.plusSeconds(3))).isEmpty();
    }

    @Test
    void restartResetsOnlyLiveConnectionsAndTheReportReadsEveryRow() {
        UUID offlineDevice = UUID.randomUUID();
        UUID unresponsiveDevice = UUID.randomUUID();
        UUID liveCid = UUID.randomUUID();
        UUID offlineCid = UUID.randomUUID();
        UUID unresponsiveCid = UUID.randomUUID();
        handover(liveCid, T0);
        db.inTransaction(() -> connections.handover(offlineDevice, offlineCid, guardian, null, T0));
        db.inTransaction(() -> connections.handover(unresponsiveDevice, unresponsiveCid, guardian, null, T0));
        connections.markClosed(offlineDevice, offlineCid, T0.plusSeconds(1)).orElseThrow();
        connections.markUnresponsive(unresponsiveDevice, unresponsiveCid, T0.plusSeconds(1)).orElseThrow();

        List<DeviceConnectionRow> reset = connections.resetAfterRestart(T0.plusSeconds(60));

        assertThat(reset).extracting(DeviceConnectionRow::deviceId).containsExactly(device);
        assertThat(reset.get(0).state()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(reset.get(0).connectionId()).isNull();
        assertThat(connections.find(unresponsiveDevice).orElseThrow().state()).isEqualTo(ConnectionState.UNRESPONSIVE);
        assertThat(connections.resetAfterRestart(T0.plusSeconds(61))).isEmpty();
        // 10초 보고는 OFFLINE까지 모든 행을 기기 순으로 읽는다
        assertThat(connections.findAll()).extracting(DeviceConnectionRow::deviceId)
                .containsExactlyInAnyOrder(device, offlineDevice, unresponsiveDevice)
                .hasSize(3);
        assertThat(connections.findAll()).extracting(DeviceConnectionRow::state)
                .contains(ConnectionState.OFFLINE, ConnectionState.DISCONNECTED, ConnectionState.UNRESPONSIVE);
    }

    // ------------------------------------------------------------------ 도우미

    private Handover handover(UUID cid, Instant now) {
        return db.inTransaction(() -> connections.handover(device, cid, guardian, child, now));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시험 래치 대기 시간 초과");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
