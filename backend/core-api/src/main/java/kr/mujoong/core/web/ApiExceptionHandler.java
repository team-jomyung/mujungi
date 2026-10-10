package kr.mujoong.core.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러 오류를 오류 봉투로 (U1 3.5 Step 31 · BR8.6 · SD-11 · SD-15 3번 · 편차 (o)(r)(d)).
 *
 * <ul>
 *   <li>{@link CoreApiException}: code 그대로(보호자 · 기기 응답은 내부 전용 code를 번역, BR8.6). SERVICE_NOT_READY는 Retry-After를 단다.
 *   <li>본문 · 매개변수 형식 오류, JSON 아님, 본문 한도 초과(chunked): 400 VALIDATION_FAILED({@code /internal/events} · 연결 보고는
 *       INVALID_ENVELOPE).
 *   <li>없는 경로 · 메서드: 404 NOT_FOUND(허용 메서드를 드러내지 않는다).
 *   <li>그 밖의 예외: 500 INTERNAL_ERROR(스택 · 예외 메시지 · 입력값 없음, 편차 (o)). 로그는 APP_ERROR 한 줄에 errorKind(예외 클래스 단순
 *       이름)만 남긴다(메시지 · 원인 사슬은 DB 오류 DETAIL에 키 값이 들 수 있어 남기지 않는다).
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final ReadinessState readiness;
    private final EventLogger events;

    public ApiExceptionHandler(ReadinessState readiness, EventLogger events) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.events = Objects.requireNonNull(events, "events");
    }

    @ExceptionHandler(CoreApiException.class)
    ResponseEntity<String> handleApi(CoreApiException exception, HttpServletRequest request) {
        ResponseEntity<String> response = ApiResponses.errorEntity(request, exception.code());
        if (exception.code() == ErrorCode.SERVICE_NOT_READY) {
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .header(ApiResponses.RETRY_AFTER, Long.toString(readiness.retryAfterSeconds()))
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        HttpMediaTypeNotSupportedException.class,
        HttpMediaTypeNotAcceptableException.class,
        MissingServletRequestParameterException.class,
        ServletRequestBindingException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<String> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        return ApiResponses.errorEntity(request, ApiResponses.invalidBodyCode(ApiResponses.path(request)));
    }

    @ExceptionHandler({
        NoResourceFoundException.class,
        NoHandlerFoundException.class,
        HttpRequestMethodNotSupportedException.class
    })
    ResponseEntity<String> handleNotFound(Exception exception, HttpServletRequest request) {
        return ApiResponses.errorEntity(request, ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (hasCause(exception, RestBodyLimitFilter.BodyTooLargeException.class)) {
            return ApiResponses.errorEntity(request, ApiResponses.invalidBodyCode(ApiResponses.path(request)));
        }
        events.appError(componentFor(ApiResponses.path(request)), exception);
        return ApiResponses.errorEntity(request, ErrorCode.INTERNAL_ERROR);
    }

    /** 경로로 고른 로그 component(웹 계층 전용 component는 없다). */
    static EventLog.Component componentFor(String path) {
        if (path.startsWith("/api/device") || path.startsWith("/internal/devices") || path.startsWith("/api/home")) {
            return EventLog.Component.DEVICE_REGISTRY;
        }
        if (path.startsWith("/api/activities") || path.startsWith("/api/sessions")) {
            return EventLog.Component.ACTIVITY_COORDINATOR;
        }
        if (path.startsWith(ApiResponses.EVENTS_PATH)) {
            return EventLog.Component.CORE_MESSAGING;
        }
        if (path.startsWith("/api/health")) {
            return EventLog.Component.HEALTH;
        }
        return EventLog.Component.SECURITY;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
        }
        return false;
    }
}
