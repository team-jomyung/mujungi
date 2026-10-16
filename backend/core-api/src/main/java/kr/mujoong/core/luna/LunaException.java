package kr.mujoong.core.luna;

import java.util.Objects;

/**
 * Luna 호출 실패 (U9 entities LunaFailure · BR6.2). 메시지에는 사유 코드만 있고 요청 · 응답 본문, 원인 예외 메시지를 담지 않는다.
 */
public final class LunaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final LunaFailureReason reason;
    private final int attemptsUsed;

    public LunaException(LunaFailureReason reason, int attemptsUsed) {
        super("Luna failure: " + Objects.requireNonNull(reason, "reason").name(), null, false, false);
        if (attemptsUsed < 0 || attemptsUsed > 3) {
            throw new IllegalArgumentException("attemptsUsed는 0 ~ 3이다");
        }
        this.reason = reason;
        this.attemptsUsed = attemptsUsed;
    }

    /** 실패 사유. */
    public LunaFailureReason reason() {
        return reason;
    }

    /** 일시 실패인가. */
    public boolean isTransient() {
        return reason.isTransient();
    }

    /** 쓴 공급자 시도 수(호출하지 않았으면 0). */
    public int attemptsUsed() {
        return attemptsUsed;
    }
}
