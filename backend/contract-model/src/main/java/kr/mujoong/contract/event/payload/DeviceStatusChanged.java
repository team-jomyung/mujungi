package kr.mujoong.contract.event.payload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.status.ConnectionState;

/**
 * DeviceStatusChanged payload(계약 6절 · BR4.8). 상태가 바뀔 때만 쓴다. Core는 봉투 occurredAt으로 최신만 반영한다(편차 (g) · BR4.1).
 * 선택 필드 guardianId(편차 (q))는 Inbox 행 guardian_id의 원천이다(A4). 필수: deviceId · state · since.
 */
public record DeviceStatusChanged(
        UUID deviceId,
        ConnectionState state,
        Integer rttMs,
        Integer recentDisconnects,
        Instant since,
        Instant lastSeenAt,
        UUID guardianId)
        implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.DEVICE_STATUS_CHANGED;
    }

    @Override
    public UUID aggregateId() {
        return deviceId;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (deviceId == null) {
            problems.add("deviceId:missing");
        }
        if (state == null || state == ConnectionState.UNRECOGNIZED) {
            problems.add("state:invalid");
        }
        if (since == null) {
            problems.add("since:missing");
        }
        if (rttMs != null && rttMs < 0) {
            problems.add("rttMs:negative");
        }
        if (recentDisconnects != null && recentDisconnects < 0) {
            problems.add("recentDisconnects:negative");
        }
        return problems;
    }
}
