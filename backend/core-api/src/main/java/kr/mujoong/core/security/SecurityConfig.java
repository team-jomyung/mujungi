package kr.mujoong.core.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.DevLoginController;
import kr.mujoong.core.internalhttp.InternalAuthInterceptor;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * Core 인증 필터 체인 (U1 3.5 Step 31 · SD-4 · SD-5 · SD-6 · SD-8 · SD-9 · SD-11 · BR8.5 · BR8.8 · BR3.9 · J75(a)(g) · 편차 (e)(t)(u)).
 *
 * <p>인증 수단이 셋(보호자 세션 · 기기 쿠키 · 내부 토큰)이고 서로 권한을 주지 않으므로 <b>경로가 인증 수단을 정한다</b>. 체인은 위에서부터 맞는
 * 첫 것이 적용되고 맞는 체인이 없으면 404다(기본 거부). 서블릿 필터 F1 traceId · F1b 준비 상태 · F2 IP 한도 · 본문 한도는 이 체인들보다 앞에서
 * 이미 돌았다.
 *
 * <table>
 *   <caption>체인(정확한 경로)</caption>
 *   <tr><th>체인</th><th>일치</th><th>인증</th><th>세션</th><th>CSRF</th></tr>
 *   <tr><td>관리</td><td>관리 포트의 Actuator(health · prometheus만 노출)</td><td>없음(컨테이너 루프백 전용)</td><td>없음</td><td>-</td></tr>
 *   <tr><td>C-INT</td><td>{@code /internal/**}</td><td>{@code X-Internal-Token} 정확히 1개</td><td>없음</td><td>면제 5번</td></tr>
 *   <tr><td>C-DEV</td><td>기기 4경로(메서드까지 정확히)</td><td>경로별 기기 쿠키</td><td>없음</td><td>면제 3 · 4번</td></tr>
 *   <tr><td>C-PUB</td><td>{@code GET /api/health} · {@code POST /api/dev/login}</td><td>없음</td><td>시험 로그인만 만든다</td><td>면제 1번</td></tr>
 *   <tr><td>C-GRD</td><td>그 밖의 {@code /api/**}(예: {@code POST /api/device/register})</td><td>{@code MUJOONG_SESSION}</td>
 *       <td>Spring Session JDBC</td><td>필수(예외 없음)</td></tr>
 *   <tr><td>기본</td><td>그 밖 전부</td><td>-</td><td>-</td><td>404(ERROR 디스패치만 통과)</td></tr>
 * </table>
 *
 * <p>C-GRD 순서: F3 세션 인증 → F3b 익명이면 401(CSRF 판정 전, J75(a): 403 CSRF는 늘 '인증은 됐고 토큰이 틀림') → F4 Origin(있으면 허용
 * origin과 같아야 함, J75(g)) → F4 CSRF 토큰({@code X-CSRF-TOKEN}, 세션 토큰, 헤더만 읽음) → F5 소유권(서비스의 (id ∧ guardian_id) 질의).
 * Spring의 CSRF 필터는 기본 위치가 인가보다 앞이라 끄고 같은 필터를 인가 뒤에 직접 둔다. 요청 저장(request cache)은 끈다(익명 401이 세션을
 * 만들지 않게). CORS 헤더는 내지 않는다. StrictHttpFirewall 기본값을 그대로 쓰고 거부는 400 봉투다. 인증이 필요한 응답 · CSRF 응답은
 * {@code Cache-Control: no-store}다. 프레임워크 기본 보안 헤더는 Nginx가 맡으므로 내지 않는다(SD-2).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** CSRF 헤더 이름(계약 csrf-response.schema.json headerName). */
    public static final String CSRF_HEADER = "X-CSRF-TOKEN";
    /** 공개 헬스 경로(편차 (t)). */
    public static final String HEALTH_PATH = "/api/health";

    /** 보호자 세션의 SecurityContext 저장소(시험 로그인 · C-GRD가 같은 것을 쓴다). 세션은 로그인만 만든다. */
    @Bean
    public HttpSessionSecurityContextRepository guardianSecurityContextRepository() {
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();
        repository.setAllowSessionCreation(false);
        return repository;
    }

    /** 세션에 묶인 CSRF 토큰 저장소(SD-6, 쿠키 이중 제출을 쓰지 않는다). */
    @Bean
    public HttpSessionCsrfTokenRepository guardianCsrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName(CSRF_HEADER);
        return repository;
    }

    /** StrictHttpFirewall 거부는 400 VALIDATION_FAILED 봉투(SD-11 'URL 정규화'). 방화벽 설정은 바꾸지 않는다. */
    @Bean
    WebSecurityCustomizer firewallRejectionEnvelope() {
        RequestRejectedHandler handler = (request, response, rejected) ->
                ApiResponses.writeError(request, response, ErrorCode.VALIDATION_FAILED);
        return web -> web.requestRejectedHandler(handler);
    }

    /** 관리 포트 Actuator(health · prometheus). 관리 포트는 컨테이너 루프백에만 묶이고 Nginx는 /actuator/를 403으로 막는다. */
    @Bean
    @Order(1)
    SecurityFilterChain managementChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint());
        stateless(http);
        http.headers(headers -> headers.disable());
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /** C-INT: 내부 토큰(BR3.9 · SD-8). */
    @Bean
    @Order(2)
    SecurityFilterChain internalChain(HttpSecurity http, InternalAuthInterceptor auth, EventLogger events) throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.pathPattern("/internal/**"));
        stateless(http);
        http.headers(headers -> headers.disable());
        http.addFilterBefore(new InternalTokenFilter(auth, events), AuthorizationFilter.class);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /** C-DEV: 기기 4경로(BR1 · BR3.1 · BR3.5). 보호자 세션 쿠키는 여기서 권한이 아니다. */
    @Bean
    @Order(3)
    SecurityFilterChain deviceChain(HttpSecurity http, EventLogger events) throws Exception {
        http.securityMatcher(new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, DeviceCookieAuthFilter.BOOTSTRAP_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, DeviceCookieAuthFilter.STATUS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, DeviceCookieAuthFilter.CLAIM_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, DeviceCookieAuthFilter.ME_PATH)));
        stateless(http);
        noStore(http);
        http.addFilterBefore(new DeviceCookieAuthFilter(events), AuthorizationFilter.class);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /** C-PUB: 공개 헬스와 시험 로그인(dev 한정). 차단 기록 필터는 늘 있다(SD-14). */
    @Bean
    @Order(4)
    SecurityFilterChain publicChain(
            HttpSecurity http, ObjectProvider<DevLoginController> devLogin, Environment environment, EventLogger events)
            throws Exception {
        http.securityMatcher(new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, HEALTH_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, DevLoginBlockedRecorder.PATH)));
        stateless(http);
        noStore(http);
        http.addFilterBefore(new DevLoginBlockedRecorder(() -> devLogin.getIfAvailable() != null, environment, events),
                AuthorizationFilter.class);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /** C-GRD: 보호자 세션 + 익명 401 + Origin + CSRF(SD-4 F3 ~ F4). */
    @Bean
    @Order(5)
    SecurityFilterChain guardianChain(
            HttpSecurity http,
            HttpSessionSecurityContextRepository guardianSecurityContextRepository,
            HttpSessionCsrfTokenRepository guardianCsrfTokenRepository,
            CoreProperties properties,
            EventLogger events)
            throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.pathPattern("/api/**"));
        http.csrf(csrf -> csrf.disable());
        http.securityContext(context -> context.securityContextRepository(guardianSecurityContextRepository));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER));
        http.requestCache(cache -> cache.requestCache(new NullRequestCache()));
        http.logout(logout -> logout.disable());
        noStore(http);
        http.exceptionHandling(handling -> handling
                .authenticationEntryPoint(guardianUnauthenticated(events))
                .accessDeniedHandler((request, response, denied) -> unauthenticated(events, request, response)));
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(GuardianPrincipal.ROLE));

        CsrfFilter csrf = new CsrfFilter(guardianCsrfTokenRepository);
        csrf.setRequestHandler(new HeaderOnlyCsrfTokenRequestHandler());
        csrf.setAccessDeniedHandler(csrfRejected(events));
        http.addFilterAfter(new OriginCheckFilter(properties.security().allowedOrigin(), events), AuthorizationFilter.class);
        http.addFilterAfter(csrf, OriginCheckFilter.class);
        return http.build();
    }

    /** 기본 거부: 맞는 체인이 없으면 404. 오류 디스패치(/error)만 통과시킨다. */
    @Bean
    @Order(6)
    SecurityFilterChain fallbackChain(HttpSecurity http) throws Exception {
        stateless(http);
        http.headers(headers -> headers.disable());
        AuthenticationEntryPoint notFound = (request, response, exception) ->
                ApiResponses.writeError(request, response, ErrorCode.NOT_FOUND);
        http.exceptionHandling(handling -> handling
                .authenticationEntryPoint(notFound)
                .accessDeniedHandler((request, response, denied) -> ApiResponses.writeError(request, response, ErrorCode.NOT_FOUND)));
        http.authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    // ------------------------------------------------------------------ 공통

    /** 세션 · CSRF · 요청 저장 · 로그아웃 없는 체인(SecurityContext는 요청 속성에만). */
    private static void stateless(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()));
        http.requestCache(cache -> cache.requestCache(new NullRequestCache()));
        http.logout(logout -> logout.disable());
    }

    /** 프레임워크 기본 보안 헤더 대신 {@code Cache-Control: no-store} 하나(SD-4 '캐시 금지'). */
    private static void noStore(HttpSecurity http) throws Exception {
        http.headers(headers -> headers.defaultsDisabled()
                .addHeaderWriter(new StaticHeadersWriter(HttpHeaders.CACHE_CONTROL, ApiResponses.NO_STORE)));
    }

    /** F3b: 익명 보호자 요청은 401 UNAUTHENTICATED + SECURITY_AUTH_FAILED(GUARDIAN_SESSION, 401). */
    private static AuthenticationEntryPoint guardianUnauthenticated(EventLogger events) {
        return (request, response, exception) -> unauthenticated(events, request, response);
    }

    private static void unauthenticated(EventLogger events, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.SECURITY, null, entry -> entry
                .attr(AttrKey.KIND, "GUARDIAN_SESSION")
                .attr(AttrKey.STATUS_CODE, ErrorCode.UNAUTHENTICATED.httpStatus()));
        ApiResponses.writeError(request, response, ErrorCode.UNAUTHENTICATED);
    }

    /** F4: 토큰 없음 · 틀림은 403 CSRF_TOKEN_INVALID + SECURITY_AUTH_FAILED(GUARDIAN_SESSION, 403)(편차 (e)). */
    private static AccessDeniedHandler csrfRejected(EventLogger events) {
        return (request, response, denied) -> {
            events.log(EventCode.SECURITY_AUTH_FAILED, EventLog.Component.SECURITY, null, entry -> entry
                    .attr(AttrKey.KIND, "GUARDIAN_SESSION")
                    .attr(AttrKey.STATUS_CODE, ErrorCode.CSRF_TOKEN_INVALID.httpStatus()));
            ApiResponses.writeError(request, response, ErrorCode.CSRF_TOKEN_INVALID);
        };
    }

    /**
     * 토큰 값을 {@code X-CSRF-TOKEN} 헤더에서만 읽는 처리기. 응답 · 비교는 Spring의 마스킹(XOR) 처리기를 그대로 쓴다(받은 값을 그대로 헤더로
     * 보내면 되고 호출마다 값이 달라도 모두 유효하다). 헤더가 없으면 폼 매개변수(본문)를 읽지 않고 토큰 없음으로 본다.
     */
    static final class HeaderOnlyCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

        private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            delegate.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            if (request.getHeader(csrfToken.getHeaderName()) == null) {
                return null;
            }
            return delegate.resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
