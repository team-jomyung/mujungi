package kr.mujoong.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 필터 F4 앞쪽 — 보호자 변경 요청의 Origin 확인 (U1 3.5 Step 31 · SD-4 · SD-6 · J75(g) · BR8.8 · 편차 (e) · OD-13).
 *
 * <p>체인 C-GRD에서 인증(F3 · F3b) 뒤, CSRF 토큰 비교 앞에 둔다. POST · PUT · PATCH · DELETE에 {@code Origin}이 있으면 허용 origin
 * ({@code mujoong.security.allowed-origin}, 환경 변수 {@code MUJOONG_SECURITY_ALLOWED_ORIGIN}) 하나와 문자열이 정확히 같아야 한다. 다르거나
 * {@code null}이거나 여러 개면 403 CSRF_TOKEN_INVALID와 SECURITY_ORIGIN_DENIED(channel REST)다. Origin이 없는 요청은 토큰 비교로 넘긴다
 * (CSRF 토큰에 더한 방어 중복).
 */
public final class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final String allowedOrigin;
    private final EventLogger events;

    public OriginCheckFilter(String allowedOrigin, EventLogger events) {
        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            throw new IllegalArgumentException("허용 origin이 비어 있다");
        }
        this.allowedOrigin = allowedOrigin;
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> origins = request.getHeaders(HttpHeaders.ORIGIN) == null
                ? List.of()
                : Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (origins.isEmpty() || (origins.size() == 1 && allowedOrigin.equals(origins.get(0)))) {
            chain.doFilter(request, response);
            return;
        }
        events.log(EventCode.SECURITY_ORIGIN_DENIED, EventLog.Component.SECURITY, null, entry -> entry
                .attr(AttrKey.CHANNEL, "REST")
                .attr(AttrKey.STATUS_CODE, ErrorCode.CSRF_TOKEN_INVALID.httpStatus()));
        ApiResponses.writeError(request, response, ErrorCode.CSRF_TOKEN_INVALID);
    }
}
