package kr.mujoong.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.security.DeviceCookies;
import kr.mujoong.core.security.ratelimit.RateLimiterConfig.RateLimiters;
import kr.mujoong.core.security.ratelimit.SlidingWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 필터 F2 bootstrap IP 한도 확인 (U1 3.5 Step 31 · BR1.6 · BR8.9 · SD-10 · 편차 (k) · SD-4 순서 2).
 *
 * <p>{@code POST /api/device/bootstrap}이고 bootstrap 쿠키가 없을 때만 본다(쿠키가 있으면 같은 코드 반환일 수 있어 서비스가 판정한다, (k)).
 * 클라이언트 IP는 {@code request.getRemoteAddr()}만 쓰고 헤더를 직접 읽지 않는다 — 신뢰 프록시(Nginx 고정 IP,
 * {@code server.tomcat.remoteip.internal-proxies})에서 온 요청만 Tomcat이 {@code X-Forwarded-For} 값으로 바꿔 준다(인프라 명세 3.3).
 *
 * <p>한도 소비는 새 코드 발급 때 {@code BootstrapService} 한 곳이 한다(같은 요청을 두 번 세지 않는다). 이 필터는 이미 한도에 닿은 IP를
 * 기록하지 않고 확인만 해서({@link SlidingWindowRateLimiter#isLimited}) DB 조회 · 인증 전에 429 RATE_LIMITED로 끊는다. 한도기 내부 오류는
 * 통과(fail-open, SD-10)다.
 */
@Component
public class BootstrapIpLimitFilter extends OncePerRequestFilter implements Ordered {

    /** 준비 상태 게이트 다음. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    /** 대상 경로. */
    public static final String PATH = "/api/device/bootstrap";

    private final SlidingWindowRateLimiter limiter;
    private final EventLogger events;

    @Autowired
    public BootstrapIpLimitFilter(RateLimiters limiters, EventLogger events) {
        this(limiters.bootstrapIp(), events);
    }

    public BootstrapIpLimitFilter(SlidingWindowRateLimiter limiter, EventLogger events) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && PATH.equals(ApiResponses.path(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (DeviceCookies.present(request, DeviceCookies.BOOTSTRAP)
                || !limiter.isLimited(SlidingWindowRateLimiter.ipKey(request.getRemoteAddr()))) {
            chain.doFilter(request, response);
            return;
        }
        events.log(EventCode.SECURITY_RATE_LIMITED, EventLog.Component.DEVICE_REGISTRY, null, entry -> entry
                .attr(AttrKey.LIMITER, SlidingWindowRateLimiter.Limiter.BOOTSTRAP_IP.name())
                .attr(AttrKey.STATUS_CODE, ErrorCode.RATE_LIMITED.httpStatus()));
        ApiResponses.writeError(request, response, ErrorCode.RATE_LIMITED);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
