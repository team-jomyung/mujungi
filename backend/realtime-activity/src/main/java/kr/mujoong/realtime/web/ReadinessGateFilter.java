package kr.mujoong.realtime.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.gateway.WebSocketConfig;
import kr.mujoong.realtime.health.ReadinessState;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * F1b 준비 상태 게이트 (U1 3.5 Step 33 · RD-16 · RD-13 T1 · OD-11 · 편차 (n) · J124 · R3-13).
 *
 * <p>앱이 READY가 아니면(STARTING · NOT_READY · DRAINING) 인증 · 본문 판정 없이 503 SERVICE_NOT_READY와 {@code Retry-After}
 * ({@code mujoong.health.db-check-interval} 초)로 답한다. {@code /internal/events}도 같아서 보내는 쪽 Outbox가 재시도한다. 점검자가 갱신하는
 * 상태 값 하나만 읽고 요청마다 DB를 부르지 않는다. 기기 WSS({@value WebSocketConfig#DEVICE_PATH})는 여기서 막지 않는다 — 업그레이드를
 * 받은 뒤 핸들러가 1013으로 닫는다(브라우저는 실패한 핸드셰이크의 HTTP 상태를 읽지 못한다, BR3.6).
 */
@Component
@Order(ReadinessGateFilter.ORDER)
public class ReadinessGateFilter extends OncePerRequestFilter {

    /** 필터 순서(F1 바로 뒤, 인증 앞). */
    public static final int ORDER = TraceIdFilter.ORDER + 10;

    private final ReadinessState readiness;
    private final JsonCodec json;

    public ReadinessGateFilter(ReadinessState readiness, JsonCodec json) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return WebSocketConfig.DEVICE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!readiness.isReady()) {
            ApiExceptionHandler.write(response, json, ErrorCode.SERVICE_NOT_READY, TraceIdFilter.traceId(request),
                    readiness.retryAfterSeconds());
            return;
        }
        chain.doFilter(request, response);
    }
}
