package kr.mujoong.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.health.ReadinessState;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 필터 F1b 준비 상태 게이트 (U1 3.5 Step 31 · RD-16 · RD-13 T1 · R3-13 · OD-11 · 편차 (n) · J124 · SD-4 순서 1b).
 *
 * <p>traceId 바로 뒤, 한도 · 인증보다 앞이다. 앱이 READY가 아니면(NOT_READY = DB 점검 연속 실패, DRAINING = 정상 종료 중) 인증 · 한도 판정
 * 없이 503 {@code SERVICE_NOT_READY} 오류 봉투와 {@code Retry-After}(= {@code mujoong.health.db-check-interval} 초)로 답한다.
 * {@code /api/health} · {@code /internal/events}도 같다(보내는 쪽 Outbox가 재시도한다). 상태 값 하나를 읽을 뿐 요청마다 DB를 부르지 않는다.
 * 관리 포트의 Actuator 경로는 자체 readiness 응답을 쓰므로 건너뛴다.
 */
@Component
public class ReadinessGateFilter extends OncePerRequestFilter implements Ordered {

    /** traceId 다음. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final String ACTUATOR_PREFIX = "/actuator/";

    private final ReadinessState readiness;

    public ReadinessGateFilter(ReadinessState readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ApiResponses.path(request).startsWith(ACTUATOR_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (readiness.isReady()) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(ApiResponses.RETRY_AFTER, Long.toString(readiness.retryAfterSeconds()));
        ApiResponses.writeError(request, response, ErrorCode.SERVICE_NOT_READY);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
