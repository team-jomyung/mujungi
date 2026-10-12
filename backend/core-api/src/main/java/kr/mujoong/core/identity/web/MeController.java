package kr.mujoong.core.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.account.AccountDeletionService;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.identity.GuardianSessions;
import kr.mujoong.core.identity.web.MeAssembler.MeBody;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 상태 {@code GET /api/me} · 계정 삭제 접수 {@code DELETE /api/me} (U2 GuardianIdentity · AccountDeletion · 계약 2 · 3절 · AC1.1.5 ·
 * AC1.6.1 · AC1.6.7 · R3 · R10 · 편차 (U2-h)).
 *
 * <p>둘 다 계정 삭제가 접수된 보호자도 부를 수 있다(계약 2절 '/api/me 외 403'). 보호자 행이 없으면(세션만 남음) 401이다. 삭제 접수는 지금
 * 세션을 끝내고(응답에서 쿠키 Max-Age=0) 다른 기기 세션도 모두 지운다.
 */
@RestController
public class MeController {

    private final GuardianRepository guardians;
    private final MeAssembler me;
    private final AccountDeletionService deletion;
    private final GuardianSessions sessions;

    public MeController(
            GuardianRepository guardians, MeAssembler me, AccountDeletionService deletion, GuardianSessions sessions) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.me = Objects.requireNonNull(me, "me");
        this.deletion = Objects.requireNonNull(deletion, "deletion");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @GetMapping("/api/me")
    ResponseEntity<MeBody> get(@AuthenticationPrincipal GuardianPrincipal guardian) {
        return ApiResponses.json(200, me.me(existing(guardian)));
    }

    @DeleteMapping("/api/me")
    ResponseEntity<DeletionAcceptedBody> delete(
            @AuthenticationPrincipal GuardianPrincipal guardian, HttpServletRequest request) {
        UUID guardianId = existing(guardian);
        UUID requestId = deletion.request(guardianId);
        sessions.close(request);
        return ApiResponses.json(202, new DeletionAcceptedBody(requestId));
    }

    private UUID existing(GuardianPrincipal guardian) {
        UUID guardianId = GuardianPrincipal.require(guardian);
        if (guardians.findById(guardianId).isEmpty()) {
            throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        return guardianId;
    }

    /** 202 본문(deletion-accepted-response.schema.json). */
    public record DeletionAcceptedBody(UUID deletionRequestId) {
    }
}
