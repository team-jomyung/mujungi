package kr.mujoong.core.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.error.ErrorEnvelope;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.common.error.CoreApiException;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Core 웹 계층 공통 도우미 (U1 3.5 Step 31 · BR8.6 · BR8.10 · OD-7 · SD-4 · SD-11 · 편차 (o)).
 *
 * <ul>
 *   <li>오류 봉투 {@code {code, message, traceId}}: message는 code의 고정 문장뿐이고 traceId는 응답 헤더 {@code X-Trace-Id}와 같다. 필터 ·
 *       오류 처리기 · 오류 컨트롤러가 모두 이 한 곳으로 쓴다(입력값 · 예외 메시지를 싣는 경로가 없다).
 *   <li>노출 대상 번역: {@code /internal/} 응답은 code 그대로, 보호자 · 기기 응답은 내부 전용 code를 번역한다({@link ErrorCode#forGuardian()},
 *       BR8.6 · BR6.6).
 *   <li>시각: ISO 8601 UTC, 밀리초까지(BR8.10, {@link JsonCodec}의 쓰기 규칙과 같다).
 *   <li>인증이 필요한 응답 · CSRF 응답은 {@code Cache-Control: no-store}다(SD-4).
 * </ul>
 */
public final class ApiResponses {

    /** 내부 API 경로 접두(C-INT). */
    public static final String INTERNAL_PREFIX = "/internal/";
    /** 보호자 · 기기 REST 경로 접두. */
    public static final String API_PREFIX = "/api/";
    /** 이벤트 수신 경로(BR5.4: 본문 오류는 INVALID_ENVELOPE). */
    public static final String EVENTS_PATH = "/internal/events";
    /** 캐시 금지 헤더 값(SD-4 · 계약 Cache-Control enum). */
    public static final String NO_STORE = "no-store";
    /** 503의 Retry-After 헤더 이름(편차 (n)). */
    public static final String RETRY_AFTER = "Retry-After";

    private static final String CONNECTION_REPORT_SUFFIX = "/connection";
    private static final String DEVICES_PREFIX = "/internal/devices/";

    private ApiResponses() {
    }

    /** 앱 안 경로(컨텍스트 경로를 뺀 요청 URI, 디코딩하지 않음). */
    public static String path(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String context = request.getContextPath();
        return context == null || context.isEmpty() || !uri.startsWith(context) ? uri : uri.substring(context.length());
    }

    /** 내부 API 경로인가. */
    public static boolean isInternal(String path) {
        return path != null && path.startsWith(INTERNAL_PREFIX);
    }

    /** 응답에 실을 code(노출 대상 번역, BR8.6). */
    public static ErrorCode codeFor(String path, ErrorCode code) {
        Objects.requireNonNull(code, "code");
        return isInternal(path) ? code : code.forGuardian();
    }

    /**
     * 본문 형식 오류의 code: 영속 이벤트 수신과 연결 보고는 INVALID_ENVELOPE(BR5.4 · 편차 (i)), 그 밖은 VALIDATION_FAILED(편차 (d)(r)).
     */
    public static ErrorCode invalidBodyCode(String path) {
        if (EVENTS_PATH.equals(path)
                || (path != null && path.startsWith(DEVICES_PREFIX) && path.endsWith(CONNECTION_REPORT_SUFFIX))) {
            return ErrorCode.INVALID_ENVELOPE;
        }
        return ErrorCode.VALIDATION_FAILED;
    }

    /** 이 요청의 traceId(F1 필터가 정한 값 → MDC → 새 값). */
    public static String traceId(HttpServletRequest request) {
        Object attribute = request == null ? null : request.getAttribute(TraceIdFilter.ATTRIBUTE);
        if (attribute instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        return TraceIds.orNew(MDC.get(TraceIds.MDC_KEY));
    }

    /** 오류 봉투 JSON(고정 message). */
    public static String errorBody(ErrorCode code, String traceId) {
        return JsonCodec.shared().writeError(ErrorEnvelope.of(code, traceId));
    }

    /**
     * 필터 · 진입점에서 오류 봉투를 직접 쓴다. 응답이 이미 나갔으면 아무것도 하지 않는다. code는 노출 대상에 맞게 번역한다.
     */
    public static void writeError(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String traceId = traceId(request);
        ErrorCode exposed = codeFor(path(request), code);
        byte[] body = errorBody(exposed, traceId).getBytes(StandardCharsets.UTF_8);
        response.resetBuffer();
        response.setStatus(exposed.httpStatus());
        response.setHeader(TraceIds.HEADER, traceId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /** 컨트롤러 · 오류 처리기의 오류 응답(Content-Type을 미리 정해 Accept 협상을 건너뛴다). */
    public static ResponseEntity<String> errorEntity(HttpServletRequest request, ErrorCode code) {
        String traceId = traceId(request);
        ErrorCode exposed = codeFor(path(request), code);
        return ResponseEntity.status(exposed.httpStatus())
                .header(TraceIds.HEADER, traceId)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorBody(exposed, traceId));
    }

    /** 성공 응답(JSON, Accept 협상 없이 application/json). */
    public static <T> ResponseEntity<T> json(int status, T body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** 시각 문자열(ISO 8601 UTC, 밀리초까지, BR8.10). null이면 null. */
    public static String time(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    /** 경로의 UUID 조각(36자 정규 형식만). 형식이 아니면 빈 값. */
    public static Optional<UUID> uuid(String value) {
        if (value == null || value.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
    /**
     * 요청 본문(JSON 객체)을 타입으로 읽는다. 모르는 필드는 무시하고 모르는 enum은 '알 수 없음'으로 읽는다(계약 10절). 본문이 없거나 JSON 객체가
     * 아니면 {@code invalid} code로 거절한다(오류 메시지에 본문을 넣지 않는다, SD-11).
     */
    public static <T> T readJson(JsonCodec json, String body, Class<T> type, ErrorCode invalid) {
        if (body == null || body.isBlank() || body.stripLeading().charAt(0) != '{') {
            throw CoreApiException.of(invalid);
        }
        T value;
        try {
            value = json.read(body, type);
        } catch (JsonCodec.JsonCodecException e) {
            throw CoreApiException.of(invalid);
        }
        if (value == null) {
            throw CoreApiException.of(invalid);
        }
        return value;
    }
}
