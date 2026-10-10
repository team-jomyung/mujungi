package kr.mujoong.contract.status;

import java.util.Arrays;

/**
 * 기기 등록 축(core_device.registration_status, entities.md Device · SM1). 전이는 UNREGISTERED → REGISTERED · EXPIRED,
 * REGISTERED → RELEASED만 허용한다. 모르는 값은 {@link #UNRECOGNIZED}로 읽는다.
 */
public enum RegistrationStatus {
    UNREGISTERED,
    REGISTERED,
    RELEASED,
    EXPIRED,
    /** 모르는 값('알 수 없음'). 저장하지 않는다. */
    UNRECOGNIZED;

    /** 이 상태에서 {@code target}으로 가는 전이가 허용되는가(SM1). */
    public boolean canTransitionTo(RegistrationStatus target) {
        return switch (this) {
            case UNREGISTERED -> target == REGISTERED || target == EXPIRED;
            case REGISTERED -> target == RELEASED;
            case RELEASED, EXPIRED, UNRECOGNIZED -> false;
        };
    }

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static RegistrationStatus fromWire(String value) {
        return Arrays.stream(values())
                .filter(status -> status != UNRECOGNIZED && status.name().equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
