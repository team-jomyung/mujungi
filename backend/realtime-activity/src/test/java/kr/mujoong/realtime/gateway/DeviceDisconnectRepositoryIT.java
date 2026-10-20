package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.realtime.gateway.DeviceDisconnectRepository.DisconnectEvent;
import kr.mujoong.realtime.gateway.DeviceDisconnectRepository.Kind;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 기기 끊김 사건 데이터 접근 통합 시험(U3 L1 · design.md 3절 · 7절).
 *
 * <p>증명: U3-R1(창 안 끊김 횟수가 불안정 판정의 원천 — 창 경계는 세지 않음) · U3-J2(닫힘은 코드와 함께, 무음은 코드 없이) · V2 CHECK
 * (닫기 코드 범위) · 삭제 참가자 · 정리 작업의 삭제 범위.
 */
@Tag("voice-session")
@Tag("db")
class DeviceDisconnectRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private static RealtimeDb db;
    private DeviceDisconnectRepository disconnects;

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
        disconnects = new DeviceDisconnectRepository(db.jdbc());
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void closedEventsKeepTheirCodeAndSilentEventsHaveNoneAndTheyListInTimeOrder() {
        UUID device = UUID.randomUUID();
        UUID firstConnection = UUID.randomUUID();
        UUID secondConnection = UUID.randomUUID();
        UUID silentId = UUID.randomUUID();
        UUID closedId = UUID.randomUUID();
        // 늦은 사건을 먼저 적어도 조회는 시각 순이다
        disconnects.record(new DisconnectEvent(silentId, device, secondConnection, Kind.SILENT, null, T0.plusSeconds(90)),
                T0.plusSeconds(91));
        disconnects.record(new DisconnectEvent(closedId, device, firstConnection, Kind.CLOSED, 1006, T0), T0);
        disconnects.record(new DisconnectEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Kind.CLOSED, 1001,
                T0), T0);

        assertThat(disconnects.findByDevice(device)).containsExactly(
                new DisconnectEvent(closedId, device, firstConnection, Kind.CLOSED, 1006, T0),
                new DisconnectEvent(silentId, device, secondConnection, Kind.SILENT, null, T0.plusSeconds(90)));
        assertThat(disconnects.findByDevice(UUID.randomUUID())).isEmpty();
    }

    @Test
    void countRecentCountsOnlyEventsStrictlyInsideTheWindow() {
        UUID device = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        record(device, T0);
        record(device, T0.plusSeconds(60));
        record(device, T0.plus(WINDOW));
        record(other, T0.plus(WINDOW));

        // now − 5분 = T0: 정각에 난 사건은 창 밖, 그 뒤 둘은 창 안(U3-R1 경계)
        assertThat(disconnects.countRecent(device, T0.plus(WINDOW), WINDOW)).isEqualTo(2);
        // 1분 뒤에는 60초 사건도 창 밖으로 빠진다
        assertThat(disconnects.countRecent(device, T0.plus(WINDOW).plusSeconds(60), WINDOW)).isEqualTo(1);
        // 창 전체가 사건보다 뒤면 0
        assertThat(disconnects.countRecent(device, T0.plus(WINDOW).plus(WINDOW).plusSeconds(1), WINDOW)).isZero();
        assertThat(disconnects.countRecent(UUID.randomUUID(), T0.plus(WINDOW), WINDOW)).isZero();

        assertThatThrownBy(() -> disconnects.countRecent(device, T0, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> disconnects.countRecent(device, T0, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSilentEventRejectsACodeAndCodesOutsideTheWebSocketRangeAreRejectedByTheSchema() {
        UUID device = UUID.randomUUID();
        assertThatThrownBy(() -> new DisconnectEvent(UUID.randomUUID(), device, UUID.randomUUID(), Kind.SILENT, 1006, T0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> disconnects.record(
                new DisconnectEvent(UUID.randomUUID(), device, UUID.randomUUID(), Kind.CLOSED, 999, T0), T0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> disconnects.record(
                new DisconnectEvent(UUID.randomUUID(), device, UUID.randomUUID(), Kind.CLOSED, 5000, T0), T0))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 거부된 행은 남지 않는다
        assertThat(disconnects.findByDevice(device)).isEmpty();
        // 4xxx 앱 코드(예: 4408 HELLO 시한)는 적는다
        disconnects.record(new DisconnectEvent(UUID.randomUUID(), device, UUID.randomUUID(), Kind.CLOSED, 4408, T0), T0);
        assertThat(disconnects.findByDevice(device)).singleElement().extracting(DisconnectEvent::closeCode).isEqualTo(4408);
    }

    @Test
    void deleteByDeviceAndDeleteOlderThanRemoveOnlyTheMatchingRows() {
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        record(deviceA, T0);
        record(deviceA, T0.plusSeconds(600));
        record(deviceB, T0);

        // 기준 시각 이전(같은 시각 포함)만 지운다
        assertThat(disconnects.deleteOlderThan(T0)).isEqualTo(2);
        assertThat(disconnects.findByDevice(deviceA)).singleElement().extracting(DisconnectEvent::occurredAt)
                .isEqualTo(T0.plusSeconds(600));
        assertThat(disconnects.findByDevice(deviceB)).isEmpty();

        assertThat(disconnects.deleteByDevice(deviceA)).isEqualTo(1);
        assertThat(disconnects.deleteByDevice(deviceA)).isZero();
        assertThat(disconnects.findByDevice(deviceA)).isEmpty();
    }

    private void record(UUID device, Instant occurredAt) {
        disconnects.record(new DisconnectEvent(UUID.randomUUID(), device, UUID.randomUUID(), Kind.CLOSED, 1006, occurredAt),
                occurredAt);
    }
}
