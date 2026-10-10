package kr.mujoong.core.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.security.SessionCookieConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSRF 토큰 취득 {@code GET /api/session/csrf} (U1 3.5 Step 31 · 편차 (u) · SD-6 · J75 · J75(j) · csrf-response.schema.json).
 *
 * <p>체인 C-GRD 안이라 익명은 401이다(취득은 인증 · 권한을 주지 않는다). 인증된 세션이면 세션에 묶인 토큰을
 * {@code {token, headerName: "X-CSRF-TOKEN"}}으로 {@code Cache-Control: no-store}와 함께 돌려주고, 세션 쿠키를 같은 속성으로 다시 내려
 * Max-Age를 갱신한다(유휴 30일 갱신형). 토큰은 마스킹 값이라 호출마다 달라도 모두 유효하다. 값은 로그에 쓰지 않는다.
 */
@RestController
public class CsrfController {

    private final CookieSerializer sessionCookies;

    public CsrfController(CookieSerializer sessionCookies) {
        this.sessionCookies = Objects.requireNonNull(sessionCookies, "sessionCookies");
    }

    @GetMapping("/api/session/csrf")
    ResponseEntity<CsrfBody> csrf(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        if (!(request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token)) {
            throw new IllegalStateException("CSRF 토큰 처리기가 체인에 없다");
        }
        CsrfBody body = new CsrfBody(token.getToken(), token.getHeaderName());
        SessionCookieConfig.refresh(sessionCookies, request, response, session.getId());
        return ApiResponses.json(200, body);
    }

    /** 응답 본문. 토큰 값은 문자열 표현에 내지 않는다. */
    public record CsrfBody(String token, String headerName) {

        @Override
        public String toString() {
            return "CsrfBody[token=***, headerName=" + headerName + "]";
        }
    }
}
