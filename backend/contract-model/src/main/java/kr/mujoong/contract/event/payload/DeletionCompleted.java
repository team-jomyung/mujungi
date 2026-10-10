package kr.mujoong.contract.event.payload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;

/**
 * DeletionCompleted payload(계약 6절). Realtime이 Realtime 쪽 삭제 참가자를 모두 돌린 뒤 발행하고(U4) U2가 구독한다.
 * 제어 이벤트라 재무장 대상이다(A1). 필수: deletionRequestId · guardianId · completedAt.
 */
public record DeletionCompleted(UUID deletionRequestId, UUID guardianId, Instant completedAt, Counts counts)
        implements EventPayload {

    /** 지운 건수(선택). */
    public record Counts(Integer sessions, Integer transcripts, Integer audioFiles, Integer practiceRecords) {
    }

    @Override
    public EventType eventType() {
        return EventType.DELETION_COMPLETED;
    }

    @Override
    public UUID aggregateId() {
        return deletionRequestId;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (deletionRequestId == null) {
            problems.add("deletionRequestId:missing");
        }
        if (guardianId == null) {
            problems.add("guardianId:missing");
        }
        if (completedAt == null) {
            problems.add("completedAt:missing");
        }
        return problems;
    }
}
