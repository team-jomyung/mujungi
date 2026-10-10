package kr.mujoong.contract.status;

import java.util.Arrays;

/**
 * FAILED 세션의 실패 코드(계약 3절 · BR6.8 · BR6.12 · BR6.13). SERVER_RESTART는 재시작 복구 · 고아 세션 점검 · 실행기 예외에도
 * 쓴다(J126). Core 로컬 FAILED는 실패 코드를 비운다(BR6.7). 모르는 값은 {@link #UNRECOGNIZED}로 읽는다.
 */
public enum FailureCode {
    DEVICE_NO_ACK,
    SCENARIO_GEN_FAILED,
    PROVIDER_UNAVAILABLE,
    SERVER_RESTART,
    /** 모르는 값('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED;

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static FailureCode fromWire(String value) {
        return Arrays.stream(values())
                .filter(code -> code != UNRECOGNIZED && code.name().equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
