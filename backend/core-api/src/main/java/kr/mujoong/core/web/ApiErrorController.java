package kr.mujoong.core.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.trace.TraceIds;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서블릿 오류 디스패치({@code /error})의 오류 봉투 (U1 3.5 Step 31 · BR8.6 · SD-11 · 편차 (o)).
 *
 * <p>컨트롤러 예외는 {@link ApiExceptionHandler}가, 필터 거절은 각 필터가 직접 봉투를 쓴다. 여기에는 그 밖에서 컨테이너가 오류 페이지로 넘긴
 * 경우(필터 밖 예외 등)만 온다. Boot의 기본 오류 응답(타임스탬프 · 경로 · 예외를 담을 수 있는 모양)을 대신해 상태 코드에 맞는 code의 고정 봉투만
 * 낸다. 원래 요청 경로로 노출 대상 번역을 정한다. 오류 디스패치는 기본 거부 체인에서 ERROR 디스패치로만 통과한다.
 */
@RestController
public class ApiErrorController implements ErrorController {

    /** 오류 디스패치 경로(Boot 기본값). */
    public static final String PATH = "/error";

    @RequestMapping(PATH)
    ResponseEntity<String> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        ErrorCode code = codeFor(status instanceof Integer value ? value : 500);
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String originalPath = original instanceof String value ? value : ApiResponses.path(request);
        ErrorCode exposed = ApiResponses.codeFor(originalPath, code);
        String traceId = ApiResponses.traceId(request);
        return ResponseEntity.status(exposed.httpStatus())
                .header(TraceIds.HEADER, traceId)
                .header(HttpHeaders.CACHE_CONTROL, ApiResponses.NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponses.errorBody(exposed, traceId));
    }

    /** 컨테이너 상태 코드 → 오류 code(모르는 상태는 500). */
    static ErrorCode codeFor(int status) {
        return switch (status) {
            case 400, 413, 414, 431 -> ErrorCode.VALIDATION_FAILED;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 404, 405 -> ErrorCode.NOT_FOUND;
            case 503 -> ErrorCode.SERVICE_NOT_READY;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
