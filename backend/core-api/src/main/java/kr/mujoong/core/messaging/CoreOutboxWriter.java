package kr.mujoong.core.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.messaging.CoreOutboxRepository.NewOutboxEvent;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Core Outbox 기록기 (U1 3.5 Step 20 · BR5.1 · A3 · A4 · RD-11 · OD-7 · ADR-010).
 *
 * <ul>
 *   <li>업무 변경과 같은 트랜잭션에서만 쓴다(전파 MANDATORY와 같은 뜻). 활성 트랜잭션이 없으면
 *       {@link IllegalTransactionStateException}이다 — 업무는 바뀌었는데 이벤트가 따로 커밋되는 일이 없다.
 *   <li>보호자 근거(guardian_id)는 필수 인자다(A4: 기록 지점 한 곳에서 채운다). payload에 보호자가 있으면 같은 값이어야 한다.
 *   <li>traceId는 MDC 값이 형식에 맞을 때만 행 {@code trace_id}에 남긴다(A3 · OD-7). 재시도 발송 때 봉투와 헤더를 이 열로 다시 만든다.
 *   <li>payload는 정규화 JSON으로 저장하고 해시(payload_hash)는 그 SHA-256이다(BR5.5 수신 쪽 중복 · 충돌 판정의 기준).
 *   <li>Core Outbox는 Core → Realtime 방향 이벤트(DeviceReleased · DeletionRequested)만 받는다.
 * </ul>
 */
@Component
public class CoreOutboxWriter {

    private final CoreOutboxRepository outbox;
    private final JsonCodec json;

    public CoreOutboxWriter(CoreOutboxRepository outbox, JsonCodec json) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 이벤트 한 건을 PENDING으로 쓴다.
     *
     * @param guardianId 행 guardian_id(A4, 필수)
     * @param now 업무 변경과 같은 Clock 값(occurred_at · created_at · available_at)
     * @return 새 eventId
     */
    public UUID write(EventPayload payload, UUID guardianId, Instant now) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(guardianId, "guardianId(A4)");
        Objects.requireNonNull(now, "now");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Outbox는 업무 변경과 같은 트랜잭션에서만 쓴다(BR5.1, 전파 MANDATORY)");
        }
        EventType type = payload.eventType();
        if (type.direction() != EventType.Direction.CORE_TO_REALTIME) {
            throw new IllegalArgumentException("Core Outbox는 Core → Realtime 이벤트만 쓴다: " + type);
        }
        if (payload.guardianId() != null && !payload.guardianId().equals(guardianId)) {
            throw new IllegalArgumentException("payload의 guardianId와 행 guardian_id가 다르다(A4)");
        }
        String traceId = TraceIds.parse(MDC.get(TraceIds.MDC_KEY)).orElse(null);
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = json.envelopeFor(eventId, payload, now, traceId);
        outbox.insert(new NewOutboxEvent(
                eventId,
                envelope.eventType(),
                envelope.schemaVersion(),
                envelope.occurredAt(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                guardianId,
                envelope.payloadJson(),
                json.sha256OfCanonical(envelope.payloadJson()),
                envelope.traceId()), now);
        return eventId;
    }
}
