package kr.mujoong.contract.event;

import java.util.Arrays;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.event.payload.DeletionRequested;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.event.payload.DeviceStatusChanged;
import kr.mujoong.contract.event.payload.SessionRecordCaptured;
import kr.mujoong.contract.event.payload.SessionStateChanged;

/**
 * 영속 이벤트 6종(계약 6절 C11 · ADR-010). 선 형식 이름 · 집합체 · 방향 · 현재 schemaVersion · 제어 이벤트 여부를 한 곳에 둔다.
 *
 * <p>제어 이벤트(DeviceReleased · DeletionRequested · DeletionCompleted · SessionRecordCaptured)는 20회 실패로 FAILED가 되어도 앱
 * 시작 때 재무장한다(A1 · RD-10). 모르는 eventType은 {@link #UNRECOGNIZED}이고 받는 쪽은 IGNORED로 커밋한다(BR5.9).
 */
public enum EventType {
    SESSION_STATE_CHANGED("SessionStateChanged", AggregateType.ACTIVITY_SESSION, Direction.REALTIME_TO_CORE, false,
            SessionStateChanged.class),
    SESSION_RECORD_CAPTURED("SessionRecordCaptured", AggregateType.ACTIVITY_SESSION, Direction.REALTIME_TO_CORE, true,
            SessionRecordCaptured.class),
    DEVICE_STATUS_CHANGED("DeviceStatusChanged", AggregateType.DEVICE, Direction.REALTIME_TO_CORE, false,
            DeviceStatusChanged.class),
    DELETION_COMPLETED("DeletionCompleted", AggregateType.DATA_DELETION_REQUEST, Direction.REALTIME_TO_CORE, true,
            DeletionCompleted.class),
    DEVICE_RELEASED("DeviceReleased", AggregateType.DEVICE, Direction.CORE_TO_REALTIME, true, DeviceReleased.class),
    DELETION_REQUESTED("DeletionRequested", AggregateType.DATA_DELETION_REQUEST, Direction.CORE_TO_REALTIME, true,
            DeletionRequested.class),
    /** 모르는 eventType('알 수 없음'). 처리기가 없으므로 IGNORED(BR5.9). */
    UNRECOGNIZED(null, AggregateType.UNRECOGNIZED, null, false, null);

    /** 전달 방향(어느 앱의 Outbox에서 어느 앱의 Inbox로). */
    public enum Direction {
        /** Realtime Outbox → Core Inbox(core-inbox 채널). */
        REALTIME_TO_CORE,
        /** Core Outbox → Realtime Inbox(realtime-inbox 채널). */
        CORE_TO_REALTIME
    }

    /** U1이 아는 schemaVersion 최대값. 이보다 큰 봉투는 IGNORED(BR5.9). */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final String wire;
    private final AggregateType aggregateType;
    private final Direction direction;
    private final boolean controlEvent;
    private final Class<? extends EventPayload> payloadType;

    EventType(String wire, AggregateType aggregateType, Direction direction, boolean controlEvent,
            Class<? extends EventPayload> payloadType) {
        this.wire = wire;
        this.aggregateType = aggregateType;
        this.direction = direction;
        this.controlEvent = controlEvent;
        this.payloadType = payloadType;
    }

    /** 선 형식 이름(예: SessionStateChanged). UNRECOGNIZED는 null. */
    public String wire() {
        return wire;
    }

    /** 이 이벤트의 집합체 종류. */
    public AggregateType aggregateType() {
        return aggregateType;
    }

    /** 전달 방향. UNRECOGNIZED는 null. */
    public Direction direction() {
        return direction;
    }

    /** 제어 이벤트인가(A1 재무장 대상). */
    public boolean isControlEvent() {
        return controlEvent;
    }

    /** payload 레코드 타입. UNRECOGNIZED는 null. */
    public Class<? extends EventPayload> payloadType() {
        return payloadType;
    }

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static EventType fromWire(String value) {
        return Arrays.stream(values())
                .filter(type -> type.wire != null && type.wire.equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
