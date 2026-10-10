package kr.mujoong.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.internalhttp.InternalAuthInterceptor;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 체인 C-INT 인증 (U1 3.5 Step 31 · BR3.9 · SD-8 · NFR9.6 · J124 · OD-13).
 *
 * <p>{@code /internal/**}는 {@code X-Internal-Token}이 정확히 한 개이고 기대 값과 같을 때만 통과한다(SHA-256 상수 시간 비교,
 * {@link InternalAuthInterceptor#matches}). 없음 · 틀림 · 여러 개는 같은 401 UNAUTHENTICATED와 같은 처리 경로이고
 * SECURITY_INTERNAL_TOKEN_FAILED(direction REALTIME_TO_CORE)를 남긴다. 통과해도 보호자 · 기기 권한은 생기지 않는다(세션 · 쿠키를 보지
 * 않고, 본문의 id는 서비스가 자기 DB로 다시 대조한다). 토큰 값은 어디에도 쓰지 않는다.
 *
 * <p>보안 체인 안에서만 쓰도록 빈으로 등록하지 않는다(서블릿 필터로 두 번 돌지 않게).
 */
public final class InternalTokenFilter extends OncePerRequestFilter {

    private final InternalAuthInterceptor auth;
    private final EventLogger events;

    public InternalTokenFilter(InternalAuthInterceptor auth, EventLogger events) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> presented = request.getHeaders(InternalAuthInterceptor.TOKEN_HEADER) == null
                ? List.of()
                : Collections.list(request.getHeaders(InternalAuthInterceptor.TOKEN_HEADER));
        if (auth.matches(presented)) {
            chain.doFilter(request, response);
            return;
        }
        events.log(EventCode.SECURITY_INTERNAL_TOKEN_FAILED, EventLog.Component.SECURITY, null, entry -> entry
                .attr(AttrKey.DIRECTION, "REALTIME_TO_CORE")
                .attr(AttrKey.STATUS_CODE, ErrorCode.UNAUTHENTICATED.httpStatus()));
        ApiResponses.writeError(request, response, ErrorCode.UNAUTHENTICATED);
    }
}
