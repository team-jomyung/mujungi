package kr.mujoong.realtime.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.error.ErrorEnvelope;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.session.EndRequestHandler;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Realtime 내부 API 오류 응답 (U1 3.5 Step 33 · BR8.6 · SD-11 · OD-7 · 편차 (n)(o) · J124).
 *
 * <p>모든 오류는 {@code {code, message, traceId}} 봉투이고 message는 code의 고정 문장이다(입력값 · 예외 메시지를 싣지 않는다). traceId는
 * F1이 정한 요청 값이고 응답 헤더 {@code X-Trace-Id}와 같다. 대응:
 *
 * <ul>
 *   <li>본문 형식 · 크기 · 매개변수 오류 → 400(경로별 code, {@link InternalBody.BadRequest}).
 *   <li>맞는 경로 · 메서드가 없음 → 404 NOT_FOUND(체인 밖 기본 거부, security-design 2.3).
 *   <li>DB 장애 · 끝내기 명령 시간 초과 → 503 SERVICE_NOT_READY + Retry-After(보내는 쪽이 재시도 · 번역한다, BR6.7).
 *   <li>그 밖의 예외 → 500 INTERNAL_ERROR + APP_ERROR 한 줄(errorKind만, 스택 없음).
 * </ul>
 *
 * <p>필터(F1b · C-INT)도 {@link #write}로 같은 봉투를 쓴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final JsonCodec json;
    private final ReadinessState readiness;
    private final EventLogger events;

    public ApiExceptionHandler(JsonCodec json, ReadinessState readiness, EventLogger events) {
        this.json = Objects.requireNonNull(json, "json");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.events = Objects.requireNonNull(events, "events");
    }

    @ExceptionHandler(InternalBody.BadRequest.class)
    ResponseEntity<String> badRequest(InternalBody.BadRequest failure, HttpServletRequest request) {
        return envelope(failure.code(), request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        HttpMediaTypeNotSupportedException.class
    })
    ResponseEntity<String> malformed(Exception failure, HttpServletRequest request) {
        return envelope(ErrorCode.VALIDATION_FAILED, request);
    }

    @ExceptionHandler({
        NoResourceFoundException.class,
        NoHandlerFoundException.class,
        HttpRequestMethodNotSupportedException.class
    })
    ResponseEntity<String> notFound(Exception failure, HttpServletRequest request) {
        return envelope(ErrorCode.NOT_FOUND, request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<String> databaseUnavailable(RuntimeException failure, HttpServletRequest request) {
        events.appError(EventLog.Component.REALTIME_MAINTENANCE, failure);
        return serviceNotReady(request);
    }

    @ExceptionHandler(EndRequestHandler.EndTimeoutException.class)
    ResponseEntity<String> endTimeout(EndRequestHandler.EndTimeoutException failure, HttpServletRequest request) {
        return serviceNotReady(request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpected(Exception failure, HttpServletRequest request) {
        events.appError(EventLog.Component.REALTIME_MAINTENANCE, failure);
        return envelope(ErrorCode.INTERNAL_ERROR, request);
    }

    private ResponseEntity<String> serviceNotReady(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(readiness.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(json, ErrorCode.SERVICE_NOT_READY, TraceIdFilter.traceId(request)));
    }

    private ResponseEntity<String> envelope(ErrorCode code, HttpServletRequest request) {
        return errorEntity(json, code, request);
    }

    /** 컨트롤러가 업무 거절(404 · 409 · 423 등)을 오류 봉투로 답한다. */
    public static ResponseEntity<String> errorEntity(JsonCodec json, ErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(code.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(json, code, TraceIdFilter.traceId(request)));
    }

    /** 오류 봉투 본문(고정 message). traceId 형식이 틀리면 새 값을 쓴다. */
    static String body(JsonCodec json, ErrorCode code, String traceId) {
        return json.writeError(ErrorEnvelope.of(code, TraceIds.orNew(traceId)));
    }

    /**
     * 필터가 직접 오류 봉투로 답한다. 이미 응답을 시작했으면 아무것도 하지 않는다.
     *
     * @param retryAfterSeconds 0보다 크면 {@code Retry-After}를 붙인다(503)
     */
    static void write(HttpServletResponse response, JsonCodec json, ErrorCode code, String traceId,
            long retryAfterSeconds) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(code.httpStatus());
        if (retryAfterSeconds > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] bytes = body(json, code, traceId).getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }
}
