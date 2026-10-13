package kr.mujoong.core.practice;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.child.ChildProfileService.ChildView;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link PracticeAccountPort}를 U2 부품으로 채운다(U5a 리뷰 A).
 *
 * <ul>
 *   <li>보호자 행 잠금은 {@link GuardianWriteLock}에만 맡긴다(lockActive · statusForUpdate — 보호자 행 잠금 SQL은 그 클래스 한 곳).
 *   <li>잠그지 않는 확인은 보호자 엔터티의 상태를 읽는다({@code GuardianPrincipal.requireActive}와 같은 규칙).
 *   <li>아이 정보는 {@link ChildProfileService#find}로 읽는다. 나이 0(모름)은 null로 넘긴다.
 * </ul>
 */
@Component
public class U2PracticeAccountAdapter implements PracticeAccountPort {

    private final GuardianWriteLock guardianLock;
    private final GuardianRepository guardians;
    private final ChildRepository children;
    private final ChildProfileService childProfiles;

    public U2PracticeAccountAdapter(GuardianWriteLock guardianLock, GuardianRepository guardians, ChildRepository children,
            ChildProfileService childProfiles) {
        this.guardianLock = Objects.requireNonNull(guardianLock, "guardianLock");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.children = Objects.requireNonNull(children, "children");
        this.childProfiles = Objects.requireNonNull(childProfiles, "childProfiles");
    }

    @Override
    public void requireGuardian(UUID guardianId) {
        checkActive(guardianId, false);
    }

    @Override
    public AccountContext requireActive(UUID guardianId, boolean lock) {
        checkActive(guardianId, lock);
        ChildView child = childProfiles.find(guardianId).orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        Integer age = child.age() > 0 ? child.age() : null;
        return new AccountContext(guardianId, child.childId(), age, child.interests());
    }

    @Override
    public AccountContext requireChild(UUID childId, boolean lock) {
        if (childId == null) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID owner = children.findById(childId).map(Child::getGuardianId)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        AccountContext context = requireActive(owner, lock);
        if (!context.childId().equals(childId)) {
            throw CoreApiException.of(ErrorCode.NOT_FOUND);
        }
        return context;
    }

    @Override
    public void lockForDeletion(UUID guardianId) {
        requireTransaction();
        guardianLock.statusForUpdate(Objects.requireNonNull(guardianId, "guardianId"));
    }

    @Override
    public List<UUID> childrenForDeletion(UUID guardianId) {
        return children.findByGuardianId(Objects.requireNonNull(guardianId, "guardianId")).map(Child::getId).stream().toList();
    }

    private void checkActive(UUID guardianId, boolean lock) {
        if (guardianId == null) {
            throw CoreApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        if (lock) {
            requireTransaction();
            guardianLock.lockActive(guardianId);
            return;
        }
        String status = guardians.findById(guardianId).map(Guardian::getStatus)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.UNAUTHENTICATED));
        if (!Guardian.STATUS_ACTIVE.equals(status)) {
            throw CoreApiException.of(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("보호자 행 잠금은 트랜잭션 안에서만 쓴다");
        }
    }
}
