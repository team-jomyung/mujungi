package kr.mujoong.core.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.core.config.SessionJdbcConfig;
import kr.mujoong.core.security.GuardianPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
 *   <li>{@link #closeAll}: 그 보호자의 모든 세션 행을 지운다(계정 삭제 접수 · 지휘 초반 바퀴). Spring Session 표의 PRINCIPAL_NAME = 보호자 id다.
 *   <li>{@link #deleteAllInCurrentTransaction}: 같은 일을 호출자 트랜잭션 안에서 한다(계정 삭제 마지막 단계, 보호자 행 삭제와 원자적으로).
 * </ul>
 */
@Component
public class GuardianSessions {

    private final HttpSessionSecurityContextRepository securityContexts;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final NamedParameterJdbcTemplate jdbc;

    public GuardianSessions(
            HttpSessionSecurityContextRepository guardianSecurityContextRepository,
            FindByIndexNameSessionRepository<? extends Session> sessions,
            NamedParameterJdbcTemplate jdbc) {
        this.securityContexts = Objects.requireNonNull(guardianSecurityContextRepository, "securityContexts");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
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
     * 보호자의 모든 세션 행을 지운다(Spring Session 저장소가 자기 트랜잭션에서 지운다).
     *
     * @return 지운 세션 수
     */
    public int closeAll(UUID guardianId) {
        Set<String> ids = sessions.findByPrincipalName(Objects.requireNonNull(guardianId, "guardianId").toString()).keySet();
        ids.forEach(sessions::deleteById);
        return ids.size();
    }

    /**
     * 호출자 트랜잭션 안에서 보호자의 세션 행을 지운다(속성 행은 외래 키 CASCADE). 계정 삭제 마지막 단계가 보호자 행 삭제와 같은 트랜잭션에서
     * 부른다 — 둘이 함께 커밋돼 같은 id로 다시 생긴 보호자(dev 시드)에 세션이 되살아나지 않는다(2차 리뷰 2). Spring Session 저장소는
     * 자기 트랜잭션(REQUIRES_NEW)을 쓰므로 여기서는 같은 표를 직접 지운다(표 이름은 {@link SessionJdbcConfig#TABLE_NAME} 하나).
     *
     * @return 지운 세션 수
     */
    public int deleteAllInCurrentTransaction(UUID guardianId) {
        return jdbc.update("delete from " + SessionJdbcConfig.TABLE_NAME + " where principal_name = :name",
                new MapSqlParameterSource("name", Objects.requireNonNull(guardianId, "guardianId").toString()));
    }
}
