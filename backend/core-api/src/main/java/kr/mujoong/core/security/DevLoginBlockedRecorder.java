package kr.mujoong.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.profile.ProfileGuardRules;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 시험 로그인 차단 기록 (U1 3.5 Step 31 · SD-14 '차단 사건' · A2 · BR8.3 · NFR9.11 · OD-13).
 *
 * <p>체인 C-PUB에 늘 있는 작은 필터다(기능 · 비밀 없음). {@code POST /api/dev/login}이 왔는데 시험 로그인 컨트롤러 빈이 없거나(프로필 dev
 * AND {@code mujoong.dev-login.enabled=true}가 아님) 요청 때 이중 확인({@link ProfileGuardRules#isDevLoginAllowedAtRequest})이 거절하면
 * SECURITY_DEV_LOGIN_BLOCKED(profile · statusCode 404)와 {@code mujoong_security_event_total{type=dev_login_blocked}}를 남기고 세션을
 * 만들지 않은 채 404 NOT_FOUND로 답한다.
 */
public final class DevLoginBlockedRecorder extends OncePerRequestFilter {

    /** 시험 로그인 경로(CSRF 면제 1번, BR8.8). */
    public static final String PATH = "/api/dev/login";

    private final BooleanSupplier controllerPresent;
    private final Environment environment;
    private final EventLogger events;

    /**
     * @param controllerPresent 시험 로그인 컨트롤러 빈이 있는가(요청 때 확인한다)
     */
    public DevLoginBlockedRecorder(BooleanSupplier controllerPresent, Environment environment, EventLogger events) {
        this.controllerPresent = Objects.requireNonNull(controllerPresent, "controllerPresent");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && PATH.equals(ApiResponses.path(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (controllerPresent.getAsBoolean() && allowedAtRequest(environment)) {
            chain.doFilter(request, response);
            return;
        }
        record(events, environment);
        ApiResponses.writeError(request, response, ErrorCode.NOT_FOUND);
    }

    /** 요청 때 이중 확인: dev가 켜져 있고 스위치가 true이며 demo · prod가 꺼져 있어야 한다. */
    public static boolean allowedAtRequest(Environment environment) {
        String enabled = environment.getProperty(ProfileGuardRules.DEV_LOGIN_ENABLED_KEY);
        boolean switchOn = enabled != null && "true".equals(enabled.trim().toLowerCase(Locale.ROOT));
        return ProfileGuardRules.isDevLoginAllowedAtRequest(Set.of(environment.getActiveProfiles()), switchOn);
    }

    /** 차단 사건 한 줄(profile은 활성 환경 프로필의 대문자 이름, 없으면 생략). */
    public static void record(EventLogger events, Environment environment) {
        String profile = ProfileGuardRules.environmentProfile(Set.of(environment.getActiveProfiles())).orElse(null);
        events.log(EventCode.SECURITY_DEV_LOGIN_BLOCKED, EventLog.Component.SECURITY, null, entry -> entry
                .attr(AttrKey.PROFILE, profile)
                .attr(AttrKey.STATUS_CODE, ErrorCode.NOT_FOUND.httpStatus()));
    }
}
