package kr.mujoong.core.practice;

import java.util.List;
import java.util.UUID;

/**
 * 연습(U5a)이 보호자 · 아이 계정을 읽는 소비자 포트. 구현은 U2 부품을 쓰는 {@link PracticeAccountAdapter} 하나다(U5a 리뷰 A · 3).
 *
 * <p>잠그는 호출(lock = true · {@link #lockForDeletion})은 부르는 쪽 트랜잭션 안에서만 쓰고, 잠금은 그 트랜잭션이 끝날 때 풀린다. 보호자 행 잠금은
 * 늘 트랜잭션의 첫 잠금이다(U2 락 순서 원칙).
 */
public interface PracticeAccountPort {

    /**
     * 보호자가 ACTIVE인지만 확인한다. 아이가 없어도 된다(18 빈 목록, U5a 리뷰 4).
     *
     * @throws kr.mujoong.core.common.error.CoreApiException 보호자 행이 없으면 UNAUTHENTICATED, 삭제 접수면 ACCOUNT_DELETION_IN_PROGRESS
     */
    void requireGuardian(UUID guardianId);

    /**
     * 보호자와 그 아이. lock이면 보호자 행을 FOR UPDATE로 잡는다(트랜잭션 필수).
     *
     * @throws kr.mujoong.core.common.error.CoreApiException {@link #requireGuardian}의 오류 + 아이가 없으면 NOT_FOUND
     */
    AccountContext requireActive(UUID guardianId, boolean lock);

    /**
     * 아이 id로 보호자를 찾아 {@link #requireActive}와 같이 확인한다.
     *
     * @throws kr.mujoong.core.common.error.CoreApiException null이면 VALIDATION_FAILED, 없는 아이면 NOT_FOUND
     */
    AccountContext requireChild(UUID childId, boolean lock);

    /** 계정 삭제 참가자가 보호자 행을 상태와 관계없이 FOR UPDATE로 잡는다(트랜잭션 필수). */
    void lockForDeletion(UUID guardianId);

    /** 계정 삭제 때 지울 아이 id(상태와 관계없이). */
    List<UUID> childrenForDeletion(UUID guardianId);

    /** 연습이 쓰는 최소 계정 문맥. 나이를 모르면 ageYears는 null이다. */
    record AccountContext(UUID guardianId, UUID childId, Integer ageYears, List<String> interests) {
        public AccountContext {
            interests = List.copyOf(interests);
        }

        @Override
        public String toString() {
            return "AccountContext[redacted]";
        }
    }
}
