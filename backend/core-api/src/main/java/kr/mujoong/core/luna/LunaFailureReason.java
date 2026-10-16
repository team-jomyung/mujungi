package kr.mujoong.core.luna;

/**
 * Luna 호출 실패 사유 (U9 entities LunaFailure · BR4.1 · BR4.4 · BR2.4). 일시 실패({@link #isTransient()})는 연결 · 429 · 5xx · 빈 출력뿐이다.
 * 사유 코드는 감사 로그 {@code lunaOutcome} 값과 같다(contracts/ai-io.md 10.8).
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

    /** 기술 재시도 대상인가(BR3.2). */
    public boolean isTransient() {
        return transientFailure;
    }
}
