package kr.mujoong.core.practice;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
/** Consumer-owned U2 seam. A write lock lasts for the caller's transaction. */
@FunctionalInterface
public interface PracticeAccountPort {
    AccountContext requireActive(UUID guardianId, boolean lock);
    default AccountContext requireChild(UUID childId,boolean lock) { throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY); }
    default void lockForDeletion(UUID guardianId) { throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY); }
    default List<UUID> childrenForDeletion(UUID guardianId) { throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY); }
    record AccountContext(UUID guardianId, UUID childId, Integer ageYears, List<String> interests) {
        public AccountContext { interests = List.copyOf(interests); }
        @Override public String toString() { return "AccountContext[redacted]"; }
    }
}
