package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import jakarta.servlet.RequestDispatcher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 오류 대응표 단위 시험 (U1 3.5 Step 34 · BR8.6 · 편차 (o)(r)(i) · OD-3 3번 · OD-7).
 *
 * <p>컨테이너 상태 → code({@code /error} 디스패치) · 노출 대상 번역(보호자 응답에는 내부 전용 code가 나가지 않음) · 본문 오류 code(이벤트 ·
 * 연결 보고는 INVALID_ENVELOPE) · 예상 밖 예외의 500 + APP_ERROR(메시지 없음) · 503의 Retry-After를 HTTP 없이 본다. 실제 서버 경로는
 * {@code ErrorEnvelopeAndLimitsIT}가 본다. 3.6 수정 분량: 없는 경로 · 메서드는 허용 메서드를 드러내지 않는 404이고 앱 오류로 세지 않는다,
 * 본문 한도 초과는 원인 사슬 깊이와 상관없이 400이며 자기 자신을 원인으로 돌려주는 예외에서도 멈추지 않고 500이 된다.
 */
@Tag("skeleton")
class WebErrorMappingTest {

    private final MutableClock clock = new MutableClock();
    private final TestEvents events = new TestEvents(clock);
    private final ReadinessState readiness = new ReadinessState(event -> { }, CoreMetrics.inMemory(), 3, Duration.ofSeconds(7));
    private final ApiExceptionHandler handler = new ApiExceptionHandler(readiness, events.logger());
    private final ApiErrorController errorController = new ApiErrorController();

    @Test
    void containerStatusesMapToEnvelopeCodesAndInternalOnlyCodesAreTranslatedForGuardians() {
        assertThat(ApiErrorController.codeFor(400)).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ApiErrorController.codeFor(413)).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ApiErrorController.codeFor(414)).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ApiErrorController.codeFor(431)).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ApiErrorController.codeFor(401)).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(ApiErrorController.codeFor(405)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ApiErrorController.codeFor(503)).isEqualTo(ErrorCode.SERVICE_NOT_READY);
        assertThat(ApiErrorController.codeFor(418)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ApiErrorController.codeFor(505)).isEqualTo(ErrorCode.INTERNAL_ERROR);

        assertThat(ApiResponses.codeFor("/api/home", ErrorCode.INVALID_ENVELOPE)).as("내부 전용은 보호자에게 500")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ApiResponses.codeFor("/api/activities/free", ErrorCode.DEVICE_NOT_CONNECTED)).isEqualTo(ErrorCode.DEVICE_NOT_STABLE);
        assertThat(ApiResponses.codeFor("/internal/events", ErrorCode.INVALID_ENVELOPE)).isEqualTo(ErrorCode.INVALID_ENVELOPE);
        assertThat(ApiResponses.invalidBodyCode("/internal/events")).isEqualTo(ErrorCode.INVALID_ENVELOPE);
        assertThat(ApiResponses.invalidBodyCode("/internal/devices/x/connection")).isEqualTo(ErrorCode.INVALID_ENVELOPE);
        assertThat(ApiResponses.invalidBodyCode("/internal/devices/verify-token")).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ApiResponses.invalidBodyCode("/api/device/register")).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void errorDispatchAnswersTheEnvelopeForTheOriginalPathWithTheRequestTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 413);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/device/register");
        request.setAttribute(TraceIdFilter.ATTRIBUTE, "0123456789abcdef0123456789abcdef");

        ResponseEntity<String> response = errorController.error(request);

        assertThat(response.getStatusCode().value()).as("413이 아니라 400 (r)").isEqualTo(400);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(envelope(response)).containsEntry("code", "VALIDATION_FAILED")
                .containsEntry("traceId", "0123456789abcdef0123456789abcdef");

        MockHttpServletRequest unknown = new MockHttpServletRequest("GET", "/error");
        ResponseEntity<String> fallback = errorController.error(unknown);
        assertThat(fallback.getStatusCode().value()).as("상태가 없으면 500").isEqualTo(500);
        assertThat(envelope(fallback).get("traceId")).asString().matches("^[0-9a-f]{32}$");
    }

    @Test
    void unexpectedExceptionsBecome500WithOneAppErrorLineAndNoMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/activities/free");
        String secret = TestSecrets.internalServiceToken();

        ResponseEntity<String> response = handler.handleUnexpected(new IllegalStateException("비밀 " + secret + " 포함 메시지"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).doesNotContain(secret, "IllegalState", "메시지");
        LogCapture capture = events.capture();
        assertThat(capture.events(EventCode.APP_ERROR)).singleElement().satisfies(event -> {
            assertThat(LogCapture.attrs(event)).containsEntry("errorKind", "IllegalStateException");
            assertThat(event.get("component")).isEqualTo(EventLog.Component.ACTIVITY_COORDINATOR.value());
        });
        assertThat(capture.lines()).noneMatch(line -> line.contains(secret));

        ResponseEntity<String> tooLarge = handler.handleUnexpected(
                new IllegalStateException(new RestBodyLimitFilter.BodyTooLargeException()), new MockHttpServletRequest("POST", "/internal/events"));
        assertThat(tooLarge.getStatusCode().value()).as("읽다가 한도를 넘으면 400").isEqualTo(400);
        assertThat(envelope(tooLarge)).containsEntry("code", "INVALID_ENVELOPE");
    }

    @Test
    void unreadableBodiesAndNotReadyFollowThePathRules() {
        HttpMessageNotReadableException unreadable =
                new HttpMessageNotReadableException("x", new MockHttpInputMessage(new byte[0]));
        assertThat(envelope(handler.handleInvalidRequest(unreadable, new MockHttpServletRequest("PUT",
                "/internal/devices/00000000-0000-4000-8000-000000000001/connection")))).containsEntry("code", "INVALID_ENVELOPE");
        assertThat(envelope(handler.handleInvalidRequest(unreadable, new MockHttpServletRequest("POST", "/api/device/register"))))
                .containsEntry("code", "VALIDATION_FAILED");

        ResponseEntity<String> notReady = handler.handleApi(CoreApiException.of(ErrorCode.SERVICE_NOT_READY),
                new MockHttpServletRequest("GET", "/api/home"));
        assertThat(notReady.getStatusCode().value()).isEqualTo(503);
        assertThat(notReady.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        ResponseEntity<String> internalOnly = handler.handleApi(CoreApiException.of(ErrorCode.EVENT_CONFLICT),
                new MockHttpServletRequest("GET", "/api/home"));
        assertThat(envelope(internalOnly)).as("보호자 경로에 내부 code가 새지 않는다").containsEntry("code", "INTERNAL_ERROR");

        assertThat(ApiExceptionHandler.componentFor("/api/device/me")).isEqualTo(EventLog.Component.DEVICE_REGISTRY);
        assertThat(ApiExceptionHandler.componentFor("/internal/devices/verify-token")).isEqualTo(EventLog.Component.DEVICE_REGISTRY);
        assertThat(ApiExceptionHandler.componentFor("/api/sessions/x")).isEqualTo(EventLog.Component.ACTIVITY_COORDINATOR);
        assertThat(ApiExceptionHandler.componentFor("/internal/events")).isEqualTo(EventLog.Component.CORE_MESSAGING);
        assertThat(ApiExceptionHandler.componentFor("/api/health")).isEqualTo(EventLog.Component.HEALTH);
        assertThat(ApiExceptionHandler.componentFor("/api/session/csrf")).isEqualTo(EventLog.Component.SECURITY);
    }

    @Test
    void missingRoutesAndMethodsAre404WithoutAnAllowHeaderOrAnAppError() {
        ResponseEntity<String> wrongMethod = handler.handleNotFound(
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET")),
                new MockHttpServletRequest("DELETE", "/api/home"));
        ResponseEntity<String> noRoute = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/internal/nothing", new HttpHeaders()),
                new MockHttpServletRequest("GET", "/internal/nothing"));

        for (ResponseEntity<String> response : List.of(wrongMethod, noRoute)) {
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(envelope(response)).containsEntry("code", "NOT_FOUND").containsOnlyKeys("code", "message", "traceId");
            assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).as("허용 메서드를 드러내지 않는다").isNull();
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        }
        assertThat(events.capture().events(EventCode.APP_ERROR)).as("없는 경로는 앱 오류가 아니다").isEmpty();
    }

    @Test
    void aBodyLimitDeepInTheCauseChainIs400AndASelfCausedErrorStillEndsAs500() {
        Exception nested = new IllegalStateException(new RuntimeException(new RestBodyLimitFilter.BodyTooLargeException()));

        ResponseEntity<String> deep =
                handler.handleUnexpected(nested, new MockHttpServletRequest("POST", "/api/device/register"));

        assertThat(deep.getStatusCode().value()).as("두 단계 아래의 본문 한도 초과").isEqualTo(400);
        assertThat(envelope(deep)).containsEntry("code", "VALIDATION_FAILED");
        assertThat(events.capture().events(EventCode.APP_ERROR)).as("본문 한도 초과는 앱 오류가 아니다").isEmpty();

        ResponseEntity<String> selfCaused = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> handler.handleUnexpected(new SelfCausedException(), new MockHttpServletRequest("GET", "/api/home")),
                "원인 사슬이 자기 자신을 가리켜도 끝난다");
        assertThat(selfCaused.getStatusCode().value()).isEqualTo(500);
        assertThat(envelope(selfCaused)).containsEntry("code", "INTERNAL_ERROR");
        assertThat(events.capture().events(EventCode.APP_ERROR)).singleElement()
                .satisfies(event -> assertThat(LogCapture.attrs(event)).containsEntry("errorKind", "SelfCausedException"));
    }

    /** getCause()가 자기 자신을 돌려주는 예외(원인 사슬 순환). */
    private static final class SelfCausedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(ResponseEntity<String> response) {
        return JsonCodec.shared().read(response.getBody(), Map.class);
    }
}
