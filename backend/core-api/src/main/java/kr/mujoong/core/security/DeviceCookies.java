package kr.mujoong.core.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kr.mujoong.core.config.CoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 기기 쿠키 두 종의 이름 · 속성 · 읽기 (U1 3.5 Step 31 · SD-5 · SD-7 · BR1.5 · BR3.1 · BR3.5 · 계약 4.1).
 *
 * <table>
 *   <caption>속성 고정(security-design 2.4)</caption>
 *   <tr><th>쿠키</th><th>Path</th><th>Max-Age</th><th>공통</th></tr>
 *   <tr><td>{@value #BOOTSTRAP}</td><td>{@code /api/device}</td><td>{@code mujoong.timing.bootstrap.code-ttl}(24시간)</td>
 *       <td rowspan="2">HttpOnly · Secure(요청 스킴에서 유추하지 않고 고정) · SameSite=Strict · Domain 없음</td></tr>
 *   <tr><td>{@value #DEVICE}</td><td>{@code /}</td><td>{@code mujoong.timing.device.cookie-max-age}(400일, /device/me마다 갱신)</td></tr>
 * </table>
 *
 * <p>지우는 Set-Cookie는 발급 때와 같은 Path · 속성에 Max-Age=0이다. 쿠키 값(비밀 · 토큰 원문)은 이 클래스 밖으로 로그 · 본문에 나가지 않는다.
 * 같은 이름의 쿠키가 둘 이상 오면 어느 것도 믿지 않는다(쿠키 끼워 넣기 방지).
 */
@Component
public class DeviceCookies {

    /** bootstrap 비밀 쿠키(BR1.5). */
    public static final String BOOTSTRAP = "MUJOONG_DEVICE_BOOTSTRAP";
    /** 기기 토큰 쿠키(BR3.1). */
    public static final String DEVICE = "MUJOONG_DEVICE";
    /** bootstrap 쿠키 Path(기기 REST 접두). */
    public static final String BOOTSTRAP_PATH = "/api/device";
    /** 기기 쿠키 Path. */
    public static final String DEVICE_PATH = "/";
    /** SameSite 값(두 쿠키 공통). */
    public static final String SAME_SITE = "Strict";

    private final Duration bootstrapMaxAge;
    private final Duration deviceMaxAge;

    @Autowired
    public DeviceCookies(CoreProperties properties) {
        this(properties.timing().bootstrap().codeTtl(), properties.timing().device().cookieMaxAge());
    }

    public DeviceCookies(Duration bootstrapMaxAge, Duration deviceMaxAge) {
        this.bootstrapMaxAge = positive(bootstrapMaxAge, "bootstrapMaxAge");
        this.deviceMaxAge = positive(deviceMaxAge, "deviceMaxAge");
    }

    /** 새 bootstrap 쿠키(Max-Age = code-ttl). */
    public String bootstrapCookie(String secret) {
        return cookie(BOOTSTRAP, secret, BOOTSTRAP_PATH, bootstrapMaxAge);
    }

    /** bootstrap 쿠키 지우기(claim 성공, BR3.1). */
    public String clearBootstrapCookie() {
        return cookie(BOOTSTRAP, "", BOOTSTRAP_PATH, Duration.ZERO);
    }

    /** 기기 토큰 쿠키(claim · /device/me 갱신, Max-Age 400일). */
    public String deviceCookie(String token) {
        return cookie(DEVICE, token, DEVICE_PATH, deviceMaxAge);
    }

    /** 기기 쿠키 수명. */
    public Duration deviceMaxAge() {
        return deviceMaxAge;
    }

    /** bootstrap 쿠키 수명. */
    public Duration bootstrapMaxAge() {
        return bootstrapMaxAge;
    }

    /** 이름이 {@code name}인 쿠키가 정확히 한 개이고 값이 비어 있지 않을 때 그 값. */
    public static Optional<String> single(HttpServletRequest request, String name) {
        Objects.requireNonNull(name, "name");
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>(1);
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                values.add(cookie.getValue());
            }
        }
        if (values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(values.get(0));
    }

    /** 이름이 {@code name}인 쿠키가 하나라도 있는가(값과 무관). */
    public static boolean present(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, Objects.requireNonNull(value, "value"))
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .path(path)
                .maxAge(maxAge)
                .build()
                .toString();
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "는 0보다 커야 한다");
        }
        return value;
    }
}
