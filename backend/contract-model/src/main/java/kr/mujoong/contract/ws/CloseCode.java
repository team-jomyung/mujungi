package kr.mujoong.contract.ws;

import java.util.Arrays;
import java.util.Optional;

/**
 * 기기 WSS 닫기 코드 표(계약 4.2 · BR3.6 · BR3.7 · BR9.7 · 편차 (s)). {@link #deviceAction()}은 기기 웹 동작이다.
 * 1006은 닫기 프레임 없이 끊긴 연결을 가리키는 관측 값이라 보내지 않는다.
 */
public enum CloseCode {
    DEVICE_OFF(1000, DeviceAction.OFF, true),
    GOING_AWAY(1001, DeviceAction.RECONNECT, true),
    ABNORMAL_CLOSURE(1006, DeviceAction.RECONNECT, false),
    SERVER_ERROR(1011, DeviceAction.RECONNECT, true),
    TRY_AGAIN_LATER(1013, DeviceAction.RECONNECT, true),
    REPLACED(4001, DeviceAction.OFF, true),
    PROTOCOL_ERROR(4400, DeviceAction.RECONNECT, true),
    UNAUTHORIZED(4401, DeviceAction.RECHECK_ME, true),
    HELLO_TIMEOUT(4408, DeviceAction.RECONNECT, true);

    /** 기기 웹이 이 닫기 코드를 받았을 때의 동작(BR9.7). */
    public enum DeviceAction {
        /** 꺼짐으로 두고 자동 재접속하지 않는다(1000 「끄기」 · 4001 대체됨). */
        OFF,
        /** 켜짐 상태면 마지막 화면을 두고 재접속한다(U1 5초 고정, 백오프는 U3). */
        RECONNECT,
        /** GET /api/device/me로 확인하고 401이면 D0(4401). */
        RECHECK_ME
    }

    private final int code;
    private final DeviceAction deviceAction;
    private final boolean sendable;

    CloseCode(int code, DeviceAction deviceAction, boolean sendable) {
        this.code = code;
        this.deviceAction = deviceAction;
        this.sendable = sendable;
    }

    /** WebSocket 닫기 코드 숫자. */
    public int code() {
        return code;
    }

    /** 기기 웹 동작. */
    public DeviceAction deviceAction() {
        return deviceAction;
    }

    /** 닫기 프레임으로 보낼 수 있는 코드인가(1006은 아니다). */
    public boolean isSendable() {
        return sendable;
    }

    /** 숫자로 찾는다. 표에 없으면 빈 값. */
    public static Optional<CloseCode> of(int code) {
        return Arrays.stream(values()).filter(value -> value.code == code).findFirst();
    }
}
