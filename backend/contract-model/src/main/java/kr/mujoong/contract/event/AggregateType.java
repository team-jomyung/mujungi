package kr.mujoong.contract.event;

import java.util.Arrays;

/** 이벤트 봉투의 집합체 종류(계약 6절 Envelope.aggregateType · BR5.4). 선 형식은 PascalCase 문자열이다. */
public enum AggregateType {
    ACTIVITY_SESSION("ActivitySession"),
    DEVICE("Device"),
    DATA_DELETION_REQUEST("DataDeletionRequest"),
    /** 모르는 값('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED(null);

    private final String wire;

    AggregateType(String wire) {
        this.wire = wire;
    }

    /** 선 형식 이름. UNRECOGNIZED는 null. */
    public String wire() {
        return wire;
    }

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static AggregateType fromWire(String value) {
        return Arrays.stream(values())
                .filter(type -> type.wire != null && type.wire.equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
