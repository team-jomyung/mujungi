package kr.mujoong.core.identity.web;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentStatusRule;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import org.springframework.stereotype.Component;

/**
 * 01 · 04 분기용 내 상태 Me (U2 GuardianIdentity · 계약 3절 Me · R3 · me-response.schema.json).
 *
 * <p>개인정보(이름 · 이메일)는 담지 않는다. 계정 삭제가 접수된 보호자는 동의 · 아이를 읽지 않고 NOT_GIVEN · false로 답한다(어떤 데이터도
 * 돌려주지 않음, AC1.6.4).
 */
@Component
public class MeAssembler {

    private final GuardianRepository guardians;
    private final ConsentPolicyService consents;
    private final ChildProfileService children;

    public MeAssembler(GuardianRepository guardians, ConsentPolicyService consents, ChildProfileService children) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.consents = Objects.requireNonNull(consents, "consents");
        this.children = Objects.requireNonNull(children, "children");
    }

    /** 응답 본문(me-response.schema.json). */
    public record MeBody(UUID guardianId, String consentStatus, boolean childRegistered, String deletionStatus) {
    }

    /** 삭제 접수 여부. */
    public enum DeletionStatus {
        NONE,
        IN_PROGRESS
    }

    /** 보호자의 Me(보호자 행이 있어야 한다). */
    public MeBody me(UUID guardianId) {
        boolean deleting = guardians.findById(Objects.requireNonNull(guardianId, "guardianId"))
                .map(Guardian::isDeletionRequested).orElse(false);
        if (deleting) {
            return deletionInProgress(guardianId);
        }
        ConsentStatusRule.Status consent = consents.state(guardianId).status();
        boolean childRegistered = children.find(guardianId).isPresent();
        return new MeBody(guardianId, consent.name(), childRegistered, DeletionStatus.NONE.name());
    }

    /** 삭제 처리 중 보호자의 Me(데이터 없음). */
    public static MeBody deletionInProgress(UUID guardianId) {
        return new MeBody(guardianId, ConsentStatusRule.Status.NOT_GIVEN.name(), false, DeletionStatus.IN_PROGRESS.name());
    }
}
