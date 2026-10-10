package kr.mujoong.core.common.error;

import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;

/**
 * 업무 서비스가 오류 응답을 정할 때 던지는 예외 (U1 3.5 Step 19 · BR8.6 · SD-11).
 *
 * <p>code 하나만 담는다. 메시지는 code 이름뿐이고 입력값 · 코드 · 토큰 · 전사를 담지 않는다(BR8.7). 웹 계층의 오류 처리기
 * (Step 31 ApiExceptionHandler)가 {@link ErrorCode#httpStatus()}와 고정 message로 오류 봉투 {@code {code, message, traceId}}를 만든다.
 * 내부 전용 code는 보호자 · 기기 응답으로 내보내기 전에 {@link ErrorCode#forGuardian()}으로 번역한다.
 */
public class CoreApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode code;

    public CoreApiException(ErrorCode code) {
        super(Objects.requireNonNull(code, "code").name(), null, false, false);
        this.code = code;
    }

    /** 응답 code. */
    public ErrorCode code() {
        return code;
    }

    /** HTTP 상태 코드(code의 대표 값). */
    public int httpStatus() {
        return code.httpStatus();
    }

    /** {@code code}로 거절한다. */
    public static CoreApiException of(ErrorCode code) {
        return new CoreApiException(code);
    }
}
