package kr.mujoong.realtime.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.realtime.observability.EventLogger;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * F1 traceId 필터 (U1 3.5 Step 33 · OD-7 · SD-4 F1 · BR8.6).
 *
 * <p>들어온 {@code X-Trace-Id}(Nginx가 덮어쓴 값 또는 Core 내부 호출이 붙인 값)가 32자 소문자 16진수면 이어 쓰고, 없거나 틀리면 새로
 * 만든다(로그 주입 방지). 그 값을 MDC · 요청 속성 · 응답 헤더 {@code X-Trace-Id}에 두어 사건 로그와 오류 봉투가 같은 값을 쓴다. 뒤 단계에서
 * 빠져나온 예외는 응답이 시작되기 전이면 500 INTERNAL_ERROR 봉투로 닫고 APP_ERROR 한 줄을 남긴다(최후 방어선).
 */
@Component
@Order(TraceIdFilter.ORDER)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 필터 순서(F1, 가장 앞). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    /** 요청 속성 키. */
    public static final String ATTRIBUTE = "mujoong.traceId";

    private final JsonCodec json;
    private final EventLogger events;

    public TraceIdFilter(JsonCodec json, EventLogger events) {
        this.json = Objects.requireNonNull(json, "json");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 이 요청의 traceId(F1이 정한 값, 없으면 MDC, 그것도 아니면 새 값). */
    public static String traceId(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ATTRIBUTE);
        if (value instanceof String traceId && TraceIds.isValid(traceId)) {
            return traceId;
        }
        return TraceIds.orNew(MDC.get(TraceIds.MDC_KEY));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIds.orNew(request.getHeader(TraceIds.HEADER));
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        MDC.put(TraceIds.MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            events.appError(EventLog.Component.REALTIME_MAINTENANCE, failure);
            if (response.isCommitted()) {
                throw failure;
            }
            ApiExceptionHandler.write(response, json, ErrorCode.INTERNAL_ERROR, traceId, 0);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
