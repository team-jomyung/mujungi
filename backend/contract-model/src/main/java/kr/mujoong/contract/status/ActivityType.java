package kr.mujoong.contract.status;

import java.util.Arrays;

/** 활동 종류(계약 3절 · 5절 · 6절). U1은 FREE_TALK만 만든다(PRACTICE는 U5). 모르는 값은 {@link #UNRECOGNIZED}. */
public enum ActivityType {
    FREE_TALK,
    PRACTICE,
    /** 모르는 값('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED;

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static ActivityType fromWire(String value) {
        return Arrays.stream(values())
                .filter(type -> type != UNRECOGNIZED && type.name().equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
