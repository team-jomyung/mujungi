package kr.mujoong.contract.status;

import java.util.Arrays;

/**
 * 내부 끝내기 명령의 사유(POST /internal/sessions/{id}/end, 계약 5절 · J54). CORE_ABANDONED는 Core가 포기한 세션의
 * 정리 요청이다(편차 (f) · BR6.7). 모르는 값은 {@link #UNRECOGNIZED}로 읽는다.
 */
public enum EndRequestReason {
    GUARDIAN_STOP,
    CONSENT_WITHDRAWN,
    CORE_ABANDONED,
    /** 모르는 값('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED;

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static EndRequestReason fromWire(String value) {
        return Arrays.stream(values())
                .filter(reason -> reason != UNRECOGNIZED && reason.name().equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
