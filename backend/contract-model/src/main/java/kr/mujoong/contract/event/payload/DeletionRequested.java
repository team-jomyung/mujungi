package kr.mujoong.contract.event.payload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;

/**
 * DeletionRequested payload(계약 6절). U2 계정 삭제 지휘가 발행하고 U4 SessionLifecycle이 받아 Realtime 쪽 삭제를 지휘한다.
 * 제어 이벤트라 재무장 대상이다(A1). 필수: deletionRequestId · guardianId · requestedAt.
 */
public record DeletionRequested(UUID deletionRequestId, UUID guardianId, UUID childId, Instant requestedAt)
        implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.DELETION_REQUESTED;
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
        if (requestedAt == null) {
            problems.add("requestedAt:missing");
        }
        return problems;
    }
}
