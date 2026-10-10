package kr.mujoong.realtime.gateway;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.status.ConnectionState;

/**
 * {@code realtime.realtime_device_connection} 한 행(10열, entities DeviceConnection · E2). 기기 연결 상태 원장의 읽은 값이다.
 *
 * @param connectionId 현재 WSS 연결 식별자(OFFLINE이면 null, 재시작 정리 뒤 DISCONNECTED도 null)
 * @param lastSeenAt 마지막 기기 메시지 시각(DISCONNECTED 판정용, BR4.8)
 */
public record DeviceConnectionRow(
        UUID deviceId,
        UUID guardianId,
        UUID childId,
        ConnectionState state,
        UUID connectionId,
        Instant connectionSince,
        Instant lastSeenAt,
        Integer lastRttMs,
        int recentDisconnects,
        Instant updatedAt) {
}
