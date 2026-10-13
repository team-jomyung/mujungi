package kr.mujoong.core.account;

import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import kr.mujoong.core.messaging.handler.InboxHandler;
import org.springframework.stereotype.Component;

/**
 * DeletionCompleted 처리기 (U2 AccountDeletion · 계약 6절 C11 · AC1.6.7 · R11 ④).
 *
 * <p>Realtime이 자기 쪽 삭제 참가자를 모두 돌린 뒤 보낸 완료를 요청 행의 realtime_completed_at에 남기고 지휘가 곧바로 마무리하게 한다. Realtime에
 * 요청한 적 없는 · 이미 반영된 · 완료된 · 모르는 요청이면 아무것도 바꾸지 않고 PROCESSED다(같은 이벤트를 두 번 받아도 결과가 같다).
 * payload의 guardianId가 요청과 달라도 반영하지 않는다(A4).
 */
@Component
public class DeletionCompletedHandler implements InboxHandler {

    private final DeletionRequestRepository requests;
    private final JsonCodec json;

    public DeletionCompletedHandler(DeletionRequestRepository requests, JsonCodec json) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public EventType eventType() {
        return EventType.DELETION_COMPLETED;
    }

    @Override
    public Result handle(InboxEvent event, Instant now) {
        DeletionCompleted payload = json.read(event.payloadJson(), DeletionCompleted.class);
        if (payload == null || payload.deletionRequestId() == null || payload.guardianId() == null
                || payload.completedAt() == null) {
            return Result.done();
        }
        requests.markRealtimeCompleted(payload.deletionRequestId(), payload.guardianId(), payload.completedAt(), now);
        return Result.done();
    }
}
