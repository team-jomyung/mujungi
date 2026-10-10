package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.mujoong.core.security.DeviceCookies;
import kr.mujoong.core.security.SessionCookieConfig;
import kr.mujoong.core.web.support.CoreWebApp.SetCookie;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 쿠키 3종의 Set-Cookie 속성 (U1 3.5 Step 34 · SD-5 · BR1.5 · BR3.1 · BR3.5 · NFR9.2 · J75(j) · 계약 3 · 4.1절).
 *
 * <p>속성은 문자열 그대로 비교한다(Expires만 시각이라 형식 · 값 범위로 본다). 지우는 쿠키는 발급 때와 같은 Path · 속성에 Max-Age=0이다. 세션
 * 쿠키 수명은 서버 유휴 만료(30일)를 넘지 않는다. 세션 ID 교체는 실제 로그인 흐름이 필요해 {@code GuardianApiIT}가 본다.
 */
@Tag("u1")
class CookieAttributesTest {

    private static final Duration CODE_TTL = Duration.ofHours(24);
    private static final Duration DEVICE_MAX_AGE = Duration.ofDays(400);
    private static final Duration SESSION_MAX_AGE = Duration.ofDays(30);
    private static final Pattern EXPIRES = Pattern.compile("; Expires=([^;]+)");

    private final DeviceCookies deviceCookies = new DeviceCookies(CODE_TTL, DEVICE_MAX_AGE);

    @Test
    void bootstrapCookieIsStrictHttpOnlySecureOnTheDevicePathForTheCodeLifetime() {
        String secret = TestSecrets.bootstrapSecret();

        String header = deviceCookies.bootstrapCookie(secret);

        assertThat(withoutExpires(header)).isEqualTo(
                "MUJOONG_DEVICE_BOOTSTRAP=" + secret + "; Path=/api/device; Max-Age=86400; Secure; HttpOnly; SameSite=Strict");
        assertExpiresAbout(header, CODE_TTL);
        assertThat(SetCookie.parse(header).has("Domain")).as("Domain 없음(호스트 한정)").isFalse();
    }

    @Test
    void deviceCookieIsStrictHttpOnlySecureOnTheRootPathFor400Days() {
        String token = TestSecrets.deviceToken();

        String header = deviceCookies.deviceCookie(token);

        assertThat(withoutExpires(header)).isEqualTo(
                "MUJOONG_DEVICE=" + token + "; Path=/; Max-Age=34560000; Secure; HttpOnly; SameSite=Strict");
        assertExpiresAbout(header, DEVICE_MAX_AGE);
        assertThat(deviceCookies.deviceMaxAge()).isEqualTo(DEVICE_MAX_AGE);
    }

    @Test
    void clearingTheBootstrapCookieRepeatsItsPathAndAttributesWithMaxAgeZero() {
        String header = deviceCookies.clearBootstrapCookie();

        assertThat(withoutExpires(header)).isEqualTo(
                "MUJOONG_DEVICE_BOOTSTRAP=; Path=/api/device; Max-Age=0; Secure; HttpOnly; SameSite=Strict");
        assertThat(expires(header).toInstant().getEpochSecond()).as("과거 시각").isZero();
    }

    @Test
    void cookieLifetimesMustBePositive() {
        assertThatThrownBy(() -> new DeviceCookies(Duration.ZERO, DEVICE_MAX_AGE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeviceCookies(CODE_TTL, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deviceCookies.deviceCookie(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void sessionCookieIsLaxHttpOnlySecureOnTheRootPathWithBase64SessionId() throws IOException {
        DefaultCookieSerializer serializer = SessionCookieConfig.serializer(SESSION_MAX_AGE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String sessionId = java.util.UUID.randomUUID().toString();

        serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, sessionId));

        List<String> headers = response.getHeaders("Set-Cookie");
        assertThat(headers).hasSize(1);
        String header = headers.get(0);
        String encoded = Base64.getEncoder().encodeToString(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(withoutExpires(header)).isEqualTo(
                "MUJOONG_SESSION=" + encoded + "; Path=/; Max-Age=2592000; Secure; HttpOnly; SameSite=Lax");
        assertExpiresAbout(header, SESSION_MAX_AGE);
        assertThat(serializer.readCookieValues(requestWithCookieHeader("MUJOONG_SESSION=" + encoded)))
                .containsExactly(sessionId);
    }

    @Test
    void deletingTheSessionCookieKeepsItsPathWithMaxAgeZero() {
        DefaultCookieSerializer serializer = SessionCookieConfig.serializer(SESSION_MAX_AGE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        serializer.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, ""));

        String header = response.getHeader("Set-Cookie");
        assertThat(withoutExpires(header)).isEqualTo("MUJOONG_SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax");
    }

    @Test
    void sessionCookieLifetimeIsWithinTheServerIdleTimeoutAndOutOfRangeValuesAreRejected() throws IOException {
        Map<String, String> config = applicationYaml();

        assertThat(config.get("mujoong.security.session-cookie-max-age")).isEqualTo("30d");
        assertThat(config.get("mujoong.timing.login.session-idle")).isEqualTo("30d");
        assertThat(config.get("server.servlet.session.cookie.name")).isEqualTo(SessionCookieConfig.COOKIE_NAME);
        assertThat(config.get("server.servlet.session.cookie.same-site")).isEqualToIgnoringCase(SessionCookieConfig.SAME_SITE);
        assertThat(config.get("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(config.get("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(config.get("mujoong.timing.bootstrap.code-ttl")).isEqualTo("24h");
        assertThat(config.get("mujoong.timing.device.cookie-max-age")).isEqualTo("400d");

        assertThatThrownBy(() -> SessionCookieConfig.serializer(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionCookieConfig.serializer(Duration.ofSeconds((long) Integer.MAX_VALUE + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 도움

    private static String withoutExpires(String header) {
        return EXPIRES.matcher(header).replaceFirst("");
    }

    private static ZonedDateTime expires(String header) {
        Matcher matcher = EXPIRES.matcher(header);
        assertThat(matcher.find()).as("Expires 속성").isTrue();
        return ZonedDateTime.parse(matcher.group(1), DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    private static void assertExpiresAbout(String header, Duration maxAge) {
        long expected = System.currentTimeMillis() / 1000 + maxAge.toSeconds();
        assertThat(expires(header).toEpochSecond()).isBetween(expected - 120, expected + 120);
    }

    private static MockHttpServletRequest requestWithCookieHeader(String cookie) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String[] nameValue = cookie.split("=", 2);
        request.setCookies(new jakarta.servlet.http.Cookie(nameValue[0], nameValue[1]));
        return request;
    }

    private static Map<String, String> applicationYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        assertThat(sources).hasSize(1);
        Map<?, ?> raw = (Map<?, ?>) sources.get(0).getSource();
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        return values;
    }
}
