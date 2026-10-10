package kr.mujoong.contract.error;

import kr.mujoong.contract.trace.TraceIds;

/**
 * REST 오류 봉투 {@code {code, message, traceId}}(계약 2절 · BR8.6 · schemas/error-envelope.schema.json).
 *
 * <p>message는 {@link ErrorCode#message()}의 고정 문장만 쓴다. 입력값 · 예외 메시지를 넣는 생성 경로가 없다(SD-11).
 * traceId는 응답 헤더 {@code X-Trace-Id}와 같은 값이어야 한다(OD-7).
 */
public record ErrorEnvelope(String code, String message, String traceId) {

    public ErrorEnvelope {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("오류 봉투에 code가 없다");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("오류 봉투에 message가 없다");
        }
        if (traceId != null && !TraceIds.isValid(traceId)) {
            throw new IllegalArgumentException("오류 봉투의 traceId 형식이 틀렸다");
        }
    }

    /** code의 고정 message와 요청 traceId로 봉투를 만든다. traceId 형식이 틀리면 거절한다. */
    public static ErrorEnvelope of(ErrorCode code, String traceId) {
        if (code == null) {
            throw new IllegalArgumentException("오류 code가 없다");
        }
        if (!TraceIds.isValid(traceId)) {
            throw new IllegalArgumentException("오류 봉투의 traceId 형식이 틀렸다");
        }
        return new ErrorEnvelope(code.name(), code.message(), traceId);
    }

    /** 이 봉투의 code를 enum으로 읽는다(모르는 code면 INTERNAL_ERROR로 본다). */
    public ErrorCode errorCode() {
        return ErrorCode.fromWire(code).orElse(ErrorCode.INTERNAL_ERROR);
    }
}
