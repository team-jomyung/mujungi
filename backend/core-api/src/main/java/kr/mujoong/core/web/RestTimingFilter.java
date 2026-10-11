package kr.mujoong.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.observability.CoreMetrics;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 보호자 · 기기 REST의 서버 처리 시간 계수 (U1 3.6 수정 · OD-6 restTotal · restLe300Ms · viewTotal · viewLe500Ms · NFR5.5 · J324).
 *
 * <p>traceId(F1) 바로 뒤 · 준비 상태 게이트(F1b) 앞이라 503 거절까지 포함한 서버 처리 시간을 앱 Clock의 단조 읽기로 잰다. 12 진행 조회
 * {@code GET /api/sessions/{id}}(Realtime 중계 포함, 목표 500ms)는 view로, 그 밖의 {@code /api/**}는 REST(목표 300ms)로 센다. 헬스
 * {@code /api/health} · 내부 호출 {@code /internal/**} · 관리 포트 지표는 세지 않는다(OD-6 '헬스 · 지표 · 내부 호출 제외'). 12 조회를 REST
 * 누계에서 뺀 것은 두 목표가 달라 한 비율에 섞으면 REST p95 판정이 중계 시간에 끌려가기 때문이다(J324).
 */
@Component
public class RestTimingFilter extends OncePerRequestFilter implements Ordered {

    /** traceId(F1) 다음, 준비 상태 게이트(F1b) 앞. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    /** 12 진행 조회(BR6.15). */
    static final Pattern VIEW_PATH = Pattern.compile("^/api/sessions/[^/]+$");

    private static final String HEALTH_PATH = "/api/health";

    private final MujoongClock clock;
    private final CoreMetrics metrics;

    public RestTimingFilter(MujoongClock clock, CoreMetrics metrics) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = ApiResponses.path(request);
        return !path.startsWith(ApiResponses.API_PREFIX) || path.equals(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean view = "GET".equals(request.getMethod()) && VIEW_PATH.matcher(ApiResponses.path(request)).matches();
        long started = clock.monotonicNanos();
        try {
            chain.doFilter(request, response);
        } finally {
            if (view) {
                metrics.viewRequest(clock.elapsedSince(started));
            } else {
                metrics.restRequest(clock.elapsedSince(started));
            }
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
