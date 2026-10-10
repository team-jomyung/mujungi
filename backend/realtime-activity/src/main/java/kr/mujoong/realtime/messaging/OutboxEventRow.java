package kr.mujoong.realtime.messaging;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;

/**
 * {@code realtime.realtime_outbox_event} 한 행(17열, entities RealtimeOutboxEvent + A3 trace_id). payload는 문자열 표현에 넣지
 * 않는다(BR5.13).
 */
public record OutboxEventRow(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateType,
        UUID aggregateId,
        UUID guardianId,
        String payloadJson,
        String payloadHash,
        Status status,
        int retryCount,
        Instant availableAt,
        Instant publishedAt,
        String lastError,
        Instant purgedAt,
        Instant createdAt,
        String traceId) {

    /** Outbox 상태(BR5.3). */
    public enum Status {
        PENDING,
        PUBLISHED,
        FAILED
    }

    /** 행의 열에서 보낼 봉투를 다시 만든다(RD-8: traceId 포함). 비워진 payload({@code {}})는 다시 보낼 수 없다. */
    public EventEnvelope toEnvelope() {
        if (purgedAt != null) {
            throw new IllegalStateException("payload를 비운 행은 다시 보낼 수 없다: " + eventId);
        }
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateType, aggregateId, traceId,
                payloadJson);
    }

    @Override
    public String toString() {
        return "OutboxEventRow[eventId=" + eventId + ", eventType=" + eventType + ", status=" + status + ", retryCount="
                + retryCount + "]";
    }
}
