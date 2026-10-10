package kr.mujoong.realtime.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.trace.TraceIds;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Realtime Outbox 기록기 (U1 3.5 Step 26 · BR5.1 · A3 · A4 · RD-11 · OD-7 · ADR-010 · LC-11).
 *
 * <ul>
 *   <li>업무 변경과 같은 트랜잭션에서만 쓴다(전파 MANDATORY와 같은 뜻). 활성 트랜잭션이 없으면
 *       {@link IllegalTransactionStateException}이다 — 상태는 바뀌었는데 이벤트가 따로 커밋되는 일이 없다.
 *   <li>보호자 근거(guardian_id)는 필수 인자다(A4: 기록 지점 한 곳에서 채운다). payload에 보호자가 있으면 같은 값이어야 한다.
 *       DeviceStatusChanged는 연결 행의 guardian_id를 payload 선택 필드에도 싣는다(편차 (q), 쓰는 쪽이 넣는다).
 *   <li>traceId는 넘긴 값(세션 · 연결 traceId) 또는 MDC 값이 형식에 맞을 때만 행 {@code trace_id}에 남긴다(A3 · OD-7). 재시도 발송 때
 *       봉투와 헤더를 이 열로 다시 만든다.
 *   <li>Realtime Outbox는 Realtime → Core 방향 이벤트(SessionStateChanged · DeviceStatusChanged · SessionRecordCaptured ·
 *       DeletionCompleted)만 받는다.
 * </ul>
 */
@Component
public class RealtimeOutboxWriter {

    private final RealtimeOutboxRepository outbox;
    private final JsonCodec json;

    public RealtimeOutboxWriter(RealtimeOutboxRepository outbox, JsonCodec json) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** MDC traceId로 쓴다. */
    public UUID write(EventPayload payload, UUID guardianId, Instant now) {
        return write(payload, guardianId, now, null);
    }

    /**
     * 이벤트 한 건을 PENDING으로 쓴다.
     *
     * @param guardianId 행 guardian_id(A4, 필수)
     * @param now 업무 변경과 같은 Clock 값(occurred_at · created_at · available_at)
     * @param traceId 세션 · 연결 traceId(형식이 틀리거나 null이면 MDC 값, 그것도 아니면 비움)
     * @return 새 eventId
     */
    public UUID write(EventPayload payload, UUID guardianId, Instant now, String traceId) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(guardianId, "guardianId(A4)");
        Objects.requireNonNull(now, "now");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Outbox는 업무 변경과 같은 트랜잭션에서만 쓴다(BR5.1, 전파 MANDATORY)");
        }
        EventType type = payload.eventType();
        if (type.direction() != EventType.Direction.REALTIME_TO_CORE) {
            throw new IllegalArgumentException("Realtime Outbox는 Realtime → Core 이벤트만 쓴다: " + type);
        }
        if (payload.guardianId() != null && !payload.guardianId().equals(guardianId)) {
            throw new IllegalArgumentException("payload의 guardianId와 행 guardian_id가 다르다(A4)");
        }
        String effectiveTrace = TraceIds.isValid(traceId) ? traceId : TraceIds.parse(MDC.get(TraceIds.MDC_KEY)).orElse(null);
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = json.envelopeFor(eventId, payload, now, effectiveTrace);
        outbox.insert(NewOutboxEvent.of(envelope, guardianId), now);
        return eventId;
    }
}
