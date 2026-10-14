package kr.mujoong.core.web.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.CoreApiApplication;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.startup.RequiredSecretsCheck;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * Step 34 API 시험의 Core 앱 하나(시험 소스 전용). 실제 Tomcat(무작위 포트) · 실제 보안 체인 · Spring Session JDBC · 공용 PostgreSQL(core_app) ·
 * 계약 대역 Realtime으로 운영 jar와 같은 앱을 띄운다. 시각은 시험이 준 가변 Clock이다(앱의 Clock 빈을 대신한다). 비밀(내부 토큰)은 실행마다
 * 만든 값을 받는다. 닫으면 스케줄러까지 멈춘다 — 시험 클래스가 끝날 때 반드시 닫는다(다른 시험의 행을 건드리지 않게).
 *
 * <p>HTTP는 JDK 클라이언트로 프록시 없이 루프백에 보낸다. 쿠키는 {@link CookieJar}가 손으로 다룬다(앱 쿠키는 Secure라 평문 시험에서 클라이언트
 * 기본 저장소가 다시 보내지 않는다).
 */
public final class CoreWebApp implements AutoCloseable {

    /** 시험 허용 origin(예약 TLD .invalid, 실제 도메인 아님). dev 프로필이 test 값을 덮지 않게 명령행으로 고정한다. */
    public static final String ALLOWED_ORIGIN = "https://mujoong.invalid";

    private final ConfigurableApplicationContext context;
    private final MutableClock clock;
    private final String internalToken;
    private final HttpClient http;
    private final URI baseUrl;
    private final URI managementUrl;

    private CoreWebApp(ConfigurableApplicationContext context, MutableClock clock, String internalToken) {
        this.context = context;
        this.clock = clock;
        this.internalToken = internalToken;
        this.http = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        Integer managementPort = context.getEnvironment().getProperty("local.management.port", Integer.class);
        this.baseUrl = URI.create("http://127.0.0.1:" + Objects.requireNonNull(port, "local.server.port"));
        this.managementUrl = managementPort == null ? null : URI.create("http://127.0.0.1:" + managementPort);
    }

    /** 작성기. */
    public static Builder builder(MujoongPostgres postgres) {
        return new Builder(postgres);
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public MutableClock clock() {
        return clock;
    }

    /** 이 앱의 내부 토큰(실행마다 생성). */
    public String internalToken() {
        return internalToken;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    /** 관리 포트 주소(관리 서버가 없으면 빈 값). */
    public Optional<URI> managementUrl() {
        return Optional.ofNullable(managementUrl);
    }

    // ------------------------------------------------------------------ 요청

    /** 요청 작성 시작. */
    public Call call(String method, String path) {
        return new Call(this, method, baseUrl.resolve(path));
    }

    public Call get(String path) {
        return call("GET", path);
    }

    public Call post(String path) {
        return call("POST", path);
    }

    public Call put(String path) {
        return call("PUT", path);
    }

    /** 관리 포트로 GET. */
    public Result management(String path) {
        if (managementUrl == null) {
            throw new IllegalStateException("관리 서버가 없다");
        }
        return new Call(this, "GET", managementUrl.resolve(path)).send();
    }

    /**
     * 보호자 세션 쿠키 값(로그인 화면 없이 시험 보호자 세션을 만든다). 운영 로그인과 같은 저장소 · 같은 SecurityContext 모양을 쓴다.
     */
    @SuppressWarnings("unchecked")
    public String sessionCookieFor(UUID guardianId) {
        SessionRepository<Session> sessions = context.getBean(SessionRepository.class);
        Session session = sessions.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(GuardianPrincipal.authentication(guardianId)));
        sessions.save(session);
        return Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
    }

    /** 세션 쿠키 값에서 세션 id(DefaultCookieSerializer의 base64). */
    public static String sessionId(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        context.close();
    }

    // ------------------------------------------------------------------ 작성기

    /** 앱 작성기. */
    public static final class Builder {

        private final MujoongPostgres postgres;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final List<Class<?>> extraSources = new ArrayList<>();
        private MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
        private String internalToken;
        private URI realtimeBaseUrl = URI.create("http://127.0.0.1:9");
        private boolean devLogin;

        private Builder(MujoongPostgres postgres) {
            this.postgres = Objects.requireNonNull(postgres, "postgres");
        }

        public Builder clock(MutableClock value) {
            this.clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        public Builder internalToken(String value) {
            this.internalToken = Objects.requireNonNull(value, "internalToken");
            return this;
        }

        public Builder realtime(URI value) {
            this.realtimeBaseUrl = Objects.requireNonNull(value, "realtimeBaseUrl");
            return this;
        }

        /** dev 프로필 + 시험 로그인 켬(SD-14 조합 13: dev,test + enabled). */
        public Builder devLogin(boolean value) {
            this.devLogin = value;
            return this;
        }

        /** 신뢰 프록시 정규식(Tomcat RemoteIp, 예: {@code 127\\.0\\.0\\.1}). */
        public Builder trustedProxy(String regex) {
            return property("server.tomcat.remoteip.internal-proxies", regex);
        }

        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        /** 시험 전용 설정 클래스(예: @Primary 대역 빈). */
        public Builder source(Class<?> configuration) {
            extraSources.add(configuration);
            return this;
        }

        public CoreWebApp start() {
            String token = internalToken == null ? TestSecrets.internalServiceToken() : internalToken;
            Map<String, String> args = new LinkedHashMap<>();
            args.put("server.port", "0");
            args.put("management.server.port", "0");
            args.put("spring.datasource.url", postgres.jdbcUrl());
            args.put("spring.datasource.username", postgres.username(Role.CORE_APP));
            args.put("spring.datasource.password", postgres.password(Role.CORE_APP));
            // Inbox 처리기 둘이 커밋 후 REQUIRES_NEW 결과 접수를 마칠 여유 연결이 필요하다.
            args.put("spring.datasource.hikari.maximum-pool-size", "3");
            args.put(RequiredSecretsCheck.INTERNAL_TOKEN, token);
            args.put("mujoong.internal-http.realtime-base-url", realtimeBaseUrl.toString());
            args.put("mujoong.dev-login.enabled", Boolean.toString(devLogin));
            args.put("mujoong.security.allowed-origin", ALLOWED_ORIGIN);
            args.putAll(properties);
            MujoongClock injectedClock = clock;
            List<Class<?>> sources = new ArrayList<>();
            sources.add(CoreApiApplication.class);
            sources.addAll(extraSources);
            ConfigurableApplicationContext context = new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                    .profiles(devLogin ? new String[] {"test", "dev"} : new String[] {"test"})
                    .logStartupInfo(false)
                    .initializers(ctx -> {
                        ctx.getBeanFactory().registerSingleton("testMujoongClock", injectedClock);
                        ctx.getBeanFactory().registerSingleton(TestSourcesExcludeFilter.class.getName(), new TestSourcesExcludeFilter());
                    })
                    .run(args.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
            return new CoreWebApp(context, clock, token);
        }
    }

    /**
     * 앱 패키지 스캔이 시험 소스의 {@code @TestConfiguration} · {@code @TestComponent}(예: 업무 문맥의 스캔 설정)를 집어 가지 않게 한다.
     * {@code @SpringBootTest}가 자동으로 거는 거름과 같은 일이다(SpringApplicationBuilder로 띄우므로 직접 건다).
     */
    static final class TestSourcesExcludeFilter extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().isAnnotated(TestComponent.class.getName());
        }

        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    // ------------------------------------------------------------------ 요청 · 응답

    /** 요청 하나. */
    public static final class Call {

        private final CoreWebApp app;
        private final String method;
        private final URI uri;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private byte[] body;
        private boolean chunked;

        private Call(CoreWebApp app, String method, URI uri) {
            this.app = app;
            this.method = method;
            this.uri = uri;
        }

        /** 헤더(같은 이름을 여러 번 부르면 여러 줄). */
        public Call header(String name, String value) {
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            return this;
        }

        public Call cookie(String name, String value) {
            cookies.put(name, value);
            return this;
        }

        /** 쿠키 상자의 쿠키를 모두 싣는다. */
        public Call cookies(CookieJar jar) {
            cookies.putAll(jar.values());
            return this;
        }

        /** 보호자 세션 쿠키. */
        public Call session(String sessionCookie) {
            return cookie("MUJOONG_SESSION", sessionCookie);
        }

        /** CSRF 헤더. */
        public Call csrf(String token) {
            return header("X-CSRF-TOKEN", token);
        }

        /** 내부 토큰 헤더(이 앱의 값). */
        public Call internalToken() {
            return header("X-Internal-Token", app.internalToken);
        }

        /** JSON 본문(Content-Type application/json, 길이 선언). */
        public Call json(String json) {
            header("Content-Type", "application/json");
            this.body = json.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        /** 원시 본문(Content-Type은 따로). */
        public Call body(byte[] bytes, String contentType) {
            if (contentType != null) {
                header("Content-Type", contentType);
            }
            this.body = bytes.clone();
            return this;
        }

        /** 길이를 선언하지 않고(chunked) 보낸다. */
        public Call chunked() {
            this.chunked = true;
            return this;
        }

        public Result send() {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
            headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            if (!cookies.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                cookies.forEach((name, value) -> {
                    if (!cookieHeader.isEmpty()) {
                        cookieHeader.append("; ");
                    }
                    cookieHeader.append(name).append('=').append(value);
                });
                builder.header("Cookie", cookieHeader.toString());
            }
            HttpRequest.BodyPublisher publisher;
            if (body == null) {
                publisher = HttpRequest.BodyPublishers.noBody();
            } else if (chunked) {
                byte[] bytes = body;
                publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
            } else {
                publisher = HttpRequest.BodyPublishers.ofByteArray(body);
            }
            builder.method(method, publisher);
            try {
                HttpResponse<String> response = app.http.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Result(response.statusCode(), response.headers().map(), response.body());
            } catch (IOException e) {
                throw new IllegalStateException("요청 실패: " + method + " " + uri.getPath(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("요청 중단", e);
            }
        }
    }

    /** 응답. 헤더 이름은 소문자. */
    public record Result(int status, Map<String, List<String>> headers, String body) {

        public Result {
            Map<String, List<String>> lower = new LinkedHashMap<>();
            headers.forEach((name, values) -> lower.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .addAll(values));
            headers = lower;
        }

        /** 첫 헤더 값(없으면 null). */
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        /** 같은 이름 헤더 전부. */
        public List<String> headerValues(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }

        /** 이름이 {@code name}인 Set-Cookie 줄(없으면 빈 값). */
        public Optional<SetCookie> setCookie(String name) {
            return headerValues("set-cookie").stream().map(SetCookie::parse).filter(c -> c.name().equals(name)).findFirst();
        }

        /** 본문 JSON 객체. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            return JsonCodec.shared().read(body, Map.class);
        }

        /** 오류 봉투 code. */
        public String code() {
            return String.valueOf(json().get("code"));
        }
    }

    /** Set-Cookie 한 줄의 이름 · 값 · 속성(이름은 원래 표기, 비교는 대소문자 무시). */
    public record SetCookie(String name, String value, Map<String, String> attributes) {

        public static SetCookie parse(String line) {
            String[] parts = line.split(";");
            String[] nameValue = parts[0].trim().split("=", 2);
            Map<String, String> attributes = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.isEmpty()) {
                    continue;
                }
                String[] kv = part.split("=", 2);
                attributes.put(kv[0].trim().toLowerCase(Locale.ROOT), kv.length > 1 ? kv[1].trim() : "");
            }
            return new SetCookie(nameValue[0], nameValue.length > 1 ? nameValue[1] : "", attributes);
        }

        public boolean has(String attribute) {
            return attributes.containsKey(attribute.toLowerCase(Locale.ROOT));
        }

        public String attribute(String attribute) {
            return attributes.get(attribute.toLowerCase(Locale.ROOT));
        }
    }

    /** 손으로 다루는 쿠키 상자(기기 웹 하나 · 보호자 브라우저 하나를 흉내 낸다). */
    public static final class CookieJar {

        private final Map<String, String> values = new LinkedHashMap<>();

        /** 응답의 Set-Cookie를 반영한다(Max-Age=0은 지운다). */
        public Result accept(Result result) {
            for (String line : result.headerValues("set-cookie")) {
                SetCookie cookie = SetCookie.parse(line);
                if ("0".equals(cookie.attribute("max-age")) || cookie.value().isEmpty()) {
                    values.remove(cookie.name());
                } else {
                    values.put(cookie.name(), cookie.value());
                }
            }
            return result;
        }

        public Optional<String> get(String name) {
            return Optional.ofNullable(values.get(name));
        }

        public void put(String name, String value) {
            values.put(name, value);
        }

        public void remove(String name) {
            values.remove(name);
        }

        public Map<String, String> values() {
            return Map.copyOf(values);
        }
    }
}
