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
 *
 * <p>정상 종료(RD-13): 판정 전에 요청을 {@link InFlightRequests}에 세고 끝나면 뺀다. T6은 받아들인 요청이 다 끝날 때까지 커넥터를 열어 둔 채
 * 기다리므로 그동안의 새 요청은 접속 거절이 아니라 이 필터의 503이다(3.6 수정 J304, Core와 같은 방식).
 */
@Component
@Order(ReadinessGateFilter.ORDER)
public class ReadinessGateFilter extends OncePerRequestFilter {

    /** 필터 순서(F1 바로 뒤, 인증 앞). */
    public static final int ORDER = TraceIdFilter.ORDER + 10;

    private final ReadinessState readiness;
    private final JsonCodec json;
    private final InFlightRequests inFlight;

    public ReadinessGateFilter(ReadinessState readiness, JsonCodec json, InFlightRequests inFlight) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.json = Objects.requireNonNull(json, "json");
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return WebSocketConfig.DEVICE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 판정 전에 센다: T6이 0을 본 뒤에 들어온 요청은 DRAINING을 보고 거절된다(받아들인 요청을 T6이 놓치지 않음)
        inFlight.enter();
        try {
            if (!readiness.isReady()) {
                ApiExceptionHandler.write(response, json, ErrorCode.SERVICE_NOT_READY, TraceIdFilter.traceId(request),
                        readiness.retryAfterSeconds());
                return;
            }
            chain.doFilter(request, response);
        } finally {
            inFlight.exit();
        }
    }
}
