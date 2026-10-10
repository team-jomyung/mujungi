package kr.mujoong.contract.event.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;

/**
 * DeviceReleased payload(계약 6절 · BR2.5 · BR2.10 · BR3.8). Core가 기기를 해제한 트랜잭션에서 쓰고 Realtime이 받아 연결을 4401로
 * 닫는다. 기기 행이 지워져도 deviceId는 payload가 가진다. 제어 이벤트라 재무장 대상이다(A1). 필수: deviceId · reason.
 */
public record DeviceReleased(UUID deviceId, UUID guardianId, Reason reason) implements EventPayload {

    /** 해제 사유. */
    public enum Reason {
        REREGISTERED,
        ACCOUNT_DELETED,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    @Override
    public EventType eventType() {
        return EventType.DEVICE_RELEASED;
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
        if (reason == null || reason == Reason.UNRECOGNIZED) {
            problems.add("reason:invalid");
        }
        return problems;
    }
}
