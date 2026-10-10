package kr.mujoong.core.security;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * 보호자 세션의 주체 (U1 3.5 Step 31 · SD-5 · BR8.4 · BR8.5 · NFR9.2).
 *
 * <p>Spring Session JDBC 행(core.core_spring_session)에 들어가는 보호자 신원은 보호자 id 하나뿐이다(세션에는 보호자 id · 권한 · CSRF 토큰만
 * 둔다). 이름({@link #getName()})이 보호자 id라 세션 표의 PRINCIPAL_NAME 색인이 보호자 단위가 된다. 자원은 늘 이 id와 함께 찾는다(BR8.5).
 */
public record GuardianPrincipal(UUID guardianId) implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 보호자 권한 이름(Spring 규칙상 ROLE_ 접두). */
    public static final String ROLE = "GUARDIAN";

    public GuardianPrincipal {
        Objects.requireNonNull(guardianId, "guardianId");
    }

    @Override
    public String getName() {
        return guardianId.toString();
    }

    /** 로그인 성공 뒤 세션에 둘 인증(시험 로그인 · U2 구글 교환이 같은 모양을 쓴다). */
    public static Authentication authentication(UUID guardianId) {
        return UsernamePasswordAuthenticationToken.authenticated(new GuardianPrincipal(guardianId), null,
                AuthorityUtils.createAuthorityList("ROLE_" + ROLE));
    }

    /** 인증에서 보호자 주체를 꺼낸다. */
    public static Optional<GuardianPrincipal> from(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof GuardianPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /** 컨트롤러 인자로 받은 주체가 있어야 한다(체인이 막지 못한 경우의 마지막 선, 401). */
    public static UUID require(GuardianPrincipal principal) {
        if (principal == null) {
            throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        return principal.guardianId();
    }

    /**
     * 보호자 행이 살아 있는지 확인한다: 행이 없으면 401(세션만 남은 계정), 계정 삭제가 접수됐으면 403 ACCOUNT_DELETION_IN_PROGRESS(계약 2절).
     *
     * @return 보호자 id
     */
    public static UUID requireActive(GuardianPrincipal principal, GuardianRepository guardians) {
        UUID guardianId = require(principal);
        Guardian guardian = guardians.findById(guardianId)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.UNAUTHENTICATED));
        if (guardian.isDeletionRequested()) {
            throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
        return guardianId;
    }
}
