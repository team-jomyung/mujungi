package kr.mujoong.core.activity;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;

/** 단위 시험용 활동 사본(시험 소스 전용). */
final class ActivityCopies {

    private ActivityCopies() {
    }

    static ActivityCopy copy(UUID sessionId, UUID guardianId, SessionStatus status, int statusVersion, Instant requestedAt) {
        return new ActivityCopy(sessionId, guardianId, UUID.randomUUID(), UUID.randomUUID(), ActivityType.FREE_TALK, status,
                statusVersion, null, null, false, false, null, UUID.randomUUID().toString(), "hash", requestedAt, null,
                null, null, requestedAt, requestedAt);
    }

    static ActivityCopy keyed(
            UUID sessionId, UUID guardianId, String key, String requestHash, boolean localFailed, Integer startResultCode,
            Instant requestedAt) {
        return new ActivityCopy(sessionId, guardianId, UUID.randomUUID(), UUID.randomUUID(), ActivityType.FREE_TALK,
                localFailed ? SessionStatus.FAILED : SessionStatus.REQUESTED, 0, localFailed ? "FAILED" : null, null, false,
                localFailed, startResultCode, key, requestHash, requestedAt, null, null, null, requestedAt, requestedAt);
    }
}
