package kr.mujoong.core.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.core.security.GuardianPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * 보호자 로그인 세션 열기 · 닫기 (U2 GuardianIdentity · SD-5 · R2 · R5 · R10 · AC1.5.2 · AC1.6.1).
 *
 * <ul>
 *   <li>{@link #open}: 들고 온 세션을 무효로 하고 새 세션을 만들어(세션 ID 교체, 세션 고정 방지) 보호자 인증만 저장한다. 시험 로그인(U1)과
 *       같은 저장소 · 같은 SecurityContext 모양이다.
 *   <li>{@link #close}: 지금 세션을 끝낸다(응답에서 Spring Session이 쿠키를 지운다, Max-Age=0). 세션 종료 요청이 아니다(B2).
 *   <li>{@link #closeAll}: 그 보호자의 모든 세션 행을 지운다(계정 삭제 접수 · 마지막 단계). Spring Session 표의 PRINCIPAL_NAME = 보호자 id다.
 * </ul>
 */
@Component
public class GuardianSessions {

    private final HttpSessionSecurityContextRepository securityContexts;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public GuardianSessions(
            HttpSessionSecurityContextRepository guardianSecurityContextRepository,
            FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.securityContexts = Objects.requireNonNull(guardianSecurityContextRepository, "securityContexts");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /** 새 보호자 세션을 연다(Set-Cookie MUJOONG_SESSION). */
    public void open(HttpServletRequest request, HttpServletResponse response, UUID guardianId) {
        Objects.requireNonNull(guardianId, "guardianId");
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(GuardianPrincipal.authentication(guardianId));
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
    }

    /**
     * 지금 세션을 끝낸다. 세션 행을 지우고 응답 끝에 Spring Session이 쿠키를 지운다(Max-Age=0). 다른 기기의 세션은 그대로다.
     */
    public void close(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /**
     * 보호자의 모든 세션 행을 지운다.
     *
     * @return 지운 세션 수
     */
    public int closeAll(UUID guardianId) {
        Set<String> ids = sessions.findByPrincipalName(Objects.requireNonNull(guardianId, "guardianId").toString()).keySet();
        ids.forEach(sessions::deleteById);
        return ids.size();
    }
}
