package kr.mujoong.contract.status;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 세션 상태 11개(K5, 계약 3절 · 6절). REQUESTED는 Core 사본에만 있다(BR6.8).
 *
 * <p>받는 쪽은 모르는 값을 {@link #UNRECOGNIZED}로 읽고 오류로 끊지 않는다(계약 10절 · BR5.9).
 * {@code UNRECOGNIZED}는 종료 · 비종료 어느 쪽에도 속하지 않는다.
 */
public enum SessionStatus {
    REQUESTED,
    PREPARING,
    DEVICE_ACK,
    WAITING_CHILD,
    ACTIVE,
    CLOSING,
    ENDED,
    ENDED_UNCONFIRMED,
    ABORTED,
    DECLINED,
    FAILED,
    /** 모르는 값('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED;

    private static final Set<SessionStatus> TERMINAL = EnumSet.of(ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED);
    private static final Set<SessionStatus> NON_TERMINAL =
            EnumSet.of(REQUESTED, PREPARING, DEVICE_ACK, WAITING_CHILD, ACTIVE, CLOSING);

    /** 종료 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · DECLINED · FAILED, BR6.1 ②). */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 종료 아닌 상태(REQUESTED ~ CLOSING). 보호자당 하나다(BR6.3). */
    public boolean isNonTerminal() {
        return NON_TERMINAL.contains(this);
    }

    /** 선 형식 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static SessionStatus fromWire(String value) {
        return Arrays.stream(values())
                .filter(status -> status != UNRECOGNIZED && status.name().equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
