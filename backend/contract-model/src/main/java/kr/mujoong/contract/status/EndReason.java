package kr.mujoong.contract.status;

import java.util.Arrays;

/**
 * 세션 종료 사유(계약 3절 SessionSummary.endReason · BR6.8). U1은 GUARDIAN_STOP · UNCONFIRMED · DISCONNECT · FAILED를 쓴다.
 * 모르는 값은 {@link #UNRECOGNIZED}로 읽는다(계약 10절).
 */
public enum EndReason {
    GUARDIAN_STOP,
    CHILD_STOP,
    SILENCE,
    DISCONNECT,
    CONSENT_WITHDRAWN,
    UNCONFIRMED,
    DECLINED,
    FAILED,
    /** 모르는 값('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED;

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static EndReason fromWire(String value) {
        return Arrays.stream(values())
                .filter(reason -> reason != UNRECOGNIZED && reason.name().equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
