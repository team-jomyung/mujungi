package kr.mujoong.core.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.profile.ProfileGuardRules;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.security.DevLoginBlockedRecorder;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * U1 시험 로그인 {@code POST /api/dev/login} (U1 3.5 Step 31 · BR8.3 · BR8.4 · A2 · SD-14 · SD-5 · J119 · AC1.1.7).
 *
 * <p>빈은 프로필 dev AND {@code mujoong.dev-login.enabled=true}일 때만 생긴다(프로필 파일에는 true를 쓰지 않는다). 그 밖에서는 빈이 없어
 * 차단 기록 필터가 404 + SECURITY_DEV_LOGIN_BLOCKED로 답한다. 요청 때도 demo · prod가 켜져 있으면 404를 내고 세션을 만들지 않는다(가드가
 * 우회된 경우의 마지막 선).
 *
 * <p>본문 없이 부르면 시드 보호자(고정 UUID, DevSeedRunner)의 보호자 세션을 연다: 들고 온 세션이 있으면 무효로 하고 새 세션을 만들어(세션 ID
 * 교체, 세션 고정 방지) 인증을 저장한다. 쿠키는 운영과 같은 Spring Session JDBC의 MUJOONG_SESSION이다. 응답은 200 {@code {guardianId}}.
 * 구글 외 로그인 · 회원가입 · 아이디 · 비밀번호 경로는 없다(BR8.4).
 */
@RestController
@Profile(ProfileGuardRules.DEV)
@ConditionalOnProperty(name = ProfileGuardRules.DEV_LOGIN_ENABLED_KEY, havingValue = "true")
public class DevLoginController {

    private final GuardianRepository guardians;
    private final HttpSessionSecurityContextRepository securityContexts;
    private final EventLogger events;
    private final Environment environment;

    public DevLoginController(
            GuardianRepository guardians,
            HttpSessionSecurityContextRepository guardianSecurityContextRepository,
            EventLogger events,
            Environment environment) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.securityContexts = Objects.requireNonNull(guardianSecurityContextRepository, "securityContexts");
        this.events = Objects.requireNonNull(events, "events");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @PostMapping(DevLoginBlockedRecorder.PATH)
    ResponseEntity<DevLoginBody> login(HttpServletRequest request, HttpServletResponse response) {
        if (!DevLoginBlockedRecorder.allowedAtRequest(environment)) {
            DevLoginBlockedRecorder.record(events, environment);
            throw CoreApiException.of(ErrorCode.NOT_FOUND);
        }
        UUID guardianId = SeedIds.GUARDIAN;
        if (guardians.findById(guardianId).isEmpty()) {
            // 시드 빈과 같은 조건이라 정상이면 늘 있다. 없으면 설정 오류(500, 세션을 만들지 않는다)
            throw new IllegalStateException("시드 보호자가 없다(DevSeedRunner 확인)");
        }
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(GuardianPrincipal.authentication(guardianId));
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        return ApiResponses.json(200, new DevLoginBody(guardianId));
    }

    /** 200 본문(dev-login-response.schema.json). */
    public record DevLoginBody(UUID guardianId) {
    }
}
