package kr.mujoong.core.record;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 앱 내부 사건 ActivityRecorded (U4a ActivityRecord · 계약 8절 in-app-events · U4a-J18).
 *
 * <p>활동 기록을 처음 저장한 트랜잭션 안에서 Spring 앱 이벤트로 낸다. 구독자(U5 PracticeGoal · U6 ResultPipeline)는
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받는다 — 기록이 커밋된 뒤에만 닿고, 같은 기록을 다시 받아도(멱등) 다시 내지
 * 않는다. 대화 원문은 담지 않는다.
 */
public record ActivityRecorded(
        UUID sessionId, UUID guardianId, UUID childId, String activityType, String finalStatus, Instant recordedAt) {

    public ActivityRecorded {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(activityType, "activityType");
        Objects.requireNonNull(finalStatus, "finalStatus");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
