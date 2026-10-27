package kr.mujoong.core.admin;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.security.GuardianPrincipal;
import org.springframework.stereotype.Component;

/**
 * 관리자 API 권한 확인 (JIRA-280).
 *
 * <p>인증은 기존 보호자 세션 체인이 한다(세션 없음 · 만료 → 401 UNAUTHENTICATED). 여기서는 살아 있는 보호자인지(삭제 접수 → 403
 * ACCOUNT_DELETION_IN_PROGRESS) 확인한 뒤 활성 관리자가 아니면 403 ADMIN_FORBIDDEN으로 거절한다. 모든 관리자 연산이 처음에 부른다.
 */
@Component
public class AdminAccess {

    private final GuardianRepository guardians;
    private final AdminAccountRepository admins;

    public AdminAccess(GuardianRepository guardians, AdminAccountRepository admins) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.admins = Objects.requireNonNull(admins, "admins");
    }

    /** @return 관리자 보호자 id */
    public UUID requireAdmin(GuardianPrincipal principal) {
        UUID guardianId = GuardianPrincipal.requireActive(principal, guardians);
        if (!admins.isActive(guardianId)) {
            throw CoreApiException.of(ErrorCode.ADMIN_FORBIDDEN);
        }
        return guardianId;
    }
}
