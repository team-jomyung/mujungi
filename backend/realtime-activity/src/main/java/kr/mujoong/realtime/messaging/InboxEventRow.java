package kr.mujoong.realtime.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code realtime.realtime_inbox_event} 한 행(17열, entities RealtimeInboxEvent + A3 trace_id). payload는 문자열 표현에 넣지
 * 않는다(BR5.13).
 */
public record InboxEventRow(
        UUID eventId,
        String sourceService,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID aggregateId,
        UUID guardianId,
        String payloadJson,
        String payloadHash,
        Status status,
        int retryCount,
        Instant nextAttemptAt,
        Instant receivedAt,
        Instant processedAt,
        String lastError,
        Instant purgedAt,
        String traceId) {

    /** Inbox 상태(BR5.6 ~ BR5.9). */
    public enum Status {
        RECEIVED,
        PROCESSED,
        IGNORED,
        DEAD
    }

    @Override
    public String toString() {
        return "InboxEventRow[eventId=" + eventId + ", eventType=" + eventType + ", status=" + status + ", retryCount="
                + retryCount + "]";
    }
}
