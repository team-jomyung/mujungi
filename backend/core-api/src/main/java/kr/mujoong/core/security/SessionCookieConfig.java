package kr.mujoong.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Objects;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 보호자 세션 쿠키 {@value #COOKIE_NAME} (U1 3.5 Step 31 · SD-5 · NFR9.2 · J75(j) · 계약 3절).
 *
 * <p>Spring Session이 쓰는 쿠키 직렬화기를 직접 정의해 속성을 고정한다(Boot의 기본 직렬화기는 물러난다): HttpOnly · Secure(요청 스킴에서
 * 유추하지 않음) · SameSite=Lax · Path=/ · Domain 없음 · Max-Age = {@code mujoong.security.session-cookie-max-age}(30일, 서버 유휴 만료
 * {@code mujoong.timing.login.session-idle} 이하 — 넘으면 설정 검증이 기동을 거부한다). 쿠키 값은 세션 id의 base64다(세션 행이 상태의 정본이고
 * 쿠키에는 상태가 없다). 갱신형 수명은 {@code GET /api/session/csrf}가 {@link #refresh}로 쿠키를 다시 내려 만든다.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {

    /** 보호자 세션 쿠키 이름(계약 3절). */
    public static final String COOKIE_NAME = "MUJOONG_SESSION";
    /** SameSite(U2 구글 로그인 복귀의 최상위 GET 이동 때문에 Lax, CSRF는 토큰으로 막는다). */
    public static final String SAME_SITE = "Lax";

    @Bean
    CookieSerializer mujoongSessionCookieSerializer(CoreProperties properties) {
        return serializer(properties.security().sessionCookieMaxAge());
    }

    /** 고정 속성의 직렬화기(시험이 직접 부른다). */
    public static DefaultCookieSerializer serializer(Duration maxAge) {
        Objects.requireNonNull(maxAge, "maxAge");
        long seconds = maxAge.toSeconds();
        if (seconds <= 0 || seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("세션 쿠키 수명이 범위를 벗어났다");
        }
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(COOKIE_NAME);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        serializer.setSameSite(SAME_SITE);
        serializer.setCookiePath("/");
        serializer.setCookieMaxAge((int) seconds);
        return serializer;
    }

    /** 지금 세션의 쿠키를 같은 속성으로 다시 내려 Max-Age를 갱신한다(J75(j)). */
    public static void refresh(
            CookieSerializer serializer, HttpServletRequest request, HttpServletResponse response, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, sessionId));
    }
}
