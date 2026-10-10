package kr.mujoong.realtime.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.realtime.internalhttp.InternalAuthInterceptor;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * C-INT 내부 토큰 체인 (U1 3.5 Step 33 · BR3.9 · SD-8 · security-design 2.3 · 2.7 · J124).
 *
 * <p>{@value #PREFIX} 접두 요청만 본다. {@code X-Internal-Token} 헤더가 정확히 한 개이고 SHA-256 상수 시간 비교로 같아야 한다
 * ({@link InternalAuthInterceptor#matches}). 없거나 틀리거나 여러 개면 같은 경로로 401 UNAUTHENTICATED와
 * SECURITY_INTERNAL_TOKEN_FAILED(direction CORE_TO_REALTIME) 한 줄이다. 이 체인은 세션 · 쿠키 · CSRF를 보지 않고 사용자 권한을 만들지
 * 않는다 — 본문의 id는 컨트롤러가 자기 DB로 다시 대조한다. Realtime의 체인은 이것과 기기 WSS 둘뿐이고 그 밖의 경로는 404다.
 */
@Component
@Order(InternalTokenFilter.ORDER)
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 필터 순서(F1b 뒤). */
    public static final int ORDER = ReadinessGateFilter.ORDER + 10;
    /** 내부 경로 접두. */
    public static final String PREFIX = "/internal/";

    private static final String EVENTS_PATH = "/internal/events";

    private final InternalAuthInterceptor auth;
    private final EventLogger events;
    private final JsonCodec json;

    public InternalTokenFilter(InternalAuthInterceptor auth, EventLogger events, JsonCodec json) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.events = Objects.requireNonNull(events, "events");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> presented = Collections.list(request.getHeaders(InternalAuthInterceptor.TOKEN_HEADER));
        if (!auth.matches(presented)) {
            EventLog.Component component = EVENTS_PATH.equals(request.getRequestURI())
                    ? EventLog.Component.REALTIME_MESSAGING : EventLog.Component.SESSION_LIFECYCLE;
            events.log(EventCode.SECURITY_INTERNAL_TOKEN_FAILED, component, null, TraceIdFilter.traceId(request),
                    entry -> entry.attr(AttrKey.DIRECTION, "CORE_TO_REALTIME")
                            .attr(AttrKey.STATUS_CODE, (long) ErrorCode.UNAUTHENTICATED.httpStatus()));
            ApiExceptionHandler.write(response, json, ErrorCode.UNAUTHENTICATED, TraceIdFilter.traceId(request), 0);
            return;
        }
        chain.doFilter(request, response);
    }
}
