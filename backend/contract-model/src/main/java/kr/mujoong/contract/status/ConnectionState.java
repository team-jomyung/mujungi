package kr.mujoong.contract.status;

import java.util.Arrays;

/**
 * 기기 연결 원장 상태(Realtime realtime_device_connection, 계약 5절 · 6절 · BR4.8). Core는 보고받은 사본만 가진다(BR4.1).
 * U1은 OFFLINE · CONNECTED · DISCONNECTED · UNRESPONSIVE를 쓰고 UNSTABLE 판정은 U3가 완성한다.
 * 모르는 값은 {@link #UNRECOGNIZED}로 읽는다(DeviceView의 네트워크 표시 UNKNOWN과는 다른 뜻이다).
 */
public enum ConnectionState {
    OFFLINE,
    CONNECTED,
    UNSTABLE,
    DISCONNECTED,
    UNRESPONSIVE,
    /** 모르는 값('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED;

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static ConnectionState fromWire(String value) {
        return Arrays.stream(values())
                .filter(state -> state != UNRECOGNIZED && state.name().equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
