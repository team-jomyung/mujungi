package kr.mujoong.core.result;

import java.time.Instant;
import java.util.UUID;

/** U6 BR1: 실행기의 영속 작업과 선점 세대. 개인정보 본문은 담지 않는다. */
public record ResultJob(UUID id, String jobType, UUID subjectId, UUID guardianId, String idempotencyKey,
        String status, int attemptCount, Instant nextAttemptAt, Instant leaseUntil, Instant deadlineAt,
        String lastErrorCode) {
}
