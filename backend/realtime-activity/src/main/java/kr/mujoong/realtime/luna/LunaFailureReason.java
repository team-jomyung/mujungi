package kr.mujoong.realtime.luna;

/**
 * Luna 호출 실패 사유 — realtime (U9 entities LunaFailure · BR4.1 · BR4.4 · BR2.4). core에 같은 모양이 따로 있다(R-F5). 사유 코드는 감사
 * 로그 {@code lunaOutcome} 값과 같다(contracts/ai-io.md 10.8). 판정(NONE)은 재시도하지 않으므로 일시 여부는 기록 · U11 판단 재료다.
 */
public enum LunaFailureReason {
    AUTH_FAILED(false),
    RATE_LIMITED(true),
    SERVER_ERROR(true),
    REQUEST_REJECTED(false),
    TRANSPORT_ERROR(true),
    TIMEOUT(false),
    EMPTY_OUTPUT(true),
    LENGTH_EXCEEDED(false),
    REFUSED(false),
    SCHEMA_INVALID(false),
    FORBIDDEN_INPUT(false),
    MODEL_MISSING(false);

    private final boolean transientFailure;

    LunaFailureReason(boolean transientFailure) {
        this.transientFailure = transientFailure;
    }

    /** 일시 실패인가. */
    public boolean isTransient() {
        return transientFailure;
    }
}
