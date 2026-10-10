package kr.mujoong.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.Ordered;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JSON 본문만 받기 (U1 3.5 Step 31 · SD-11 '본문이 있는 요청은 application/json만' · BR8.6 · BR5.4).
 *
 * <p>{@code /api/**} · {@code /internal/**} 요청에 본문이 있으면(Content-Length &gt; 0 또는 Transfer-Encoding) Content-Type이
 * {@code application/json}(매개변수 허용)이어야 한다. 아니면 400 VALIDATION_FAILED({@code /internal/events} · 연결 보고는 INVALID_ENVELOPE)다.
 * 보안 체인(인증 · CSRF) 뒤에 둬서 익명 · CSRF 실패 요청은 401 · 403이 먼저 나간다(J75(a)의 '익명 변경 요청은 401').
 */
@Component
public class JsonOnlyFilter extends OncePerRequestFilter implements Ordered {

    /** Spring Security 필터 체인 바로 뒤. */
    public static final int ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = ApiResponses.path(request);
        return !(path.startsWith(ApiResponses.API_PREFIX) || path.startsWith(ApiResponses.INTERNAL_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hasBody(request) && !isJson(request.getContentType())) {
            ApiResponses.writeError(request, response, ApiResponses.invalidBodyCode(ApiResponses.path(request)));
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }

    /** {@code application/json}인가(대소문자 무시, charset 등 매개변수 허용). */
    static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return "application".equalsIgnoreCase(type.getType()) && "json".equalsIgnoreCase(type.getSubtype());
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
