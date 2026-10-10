package kr.mujoong.realtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Outbox/Inbox 값 타입 단위 시험(U1 3.5 Step 18, DB 없음).
 *
 * <p>증명: BR5.13(last_error는 공백 없는 코드 · 예외 종류 토큰만, 페이로드 · 메시지 없음) · BR5.5(payload_hash는 정규화 payload의
 * SHA-256이라 키 순서가 달라도 같다) · BR5.9(IGNORED 표시) · RD-8(비운 행은 다시 보낼 수 없음).
 */
@Tag("u1")
class MessagingValueTypesTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    @Test
    void lastErrorAcceptsCodesAndExceptionKindsOnly() {
        assertThat(LastError.require("HTTP_503")).isEqualTo("HTTP_503");
        assertThat(LastError.require(LastError.exceptionKind(new IllegalStateException("메시지는 남지 않는다"))))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(LastError.http(401)).isEqualTo("HTTP_401");
        assertThat(LastError.require("kr.mujoong.Outer$Inner")).isEqualTo("kr.mujoong.Outer$Inner");

        assertThatThrownBy(() -> LastError.require("HTTP 503")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LastError.require("{\"text\":\"전사\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LastError.require("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LastError.require(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LastError.require("x".repeat(LastError.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LastError.http(600)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theInboxHashIgnoresKeyOrderAndMarksIgnoredEvents() {
        UUID eventId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        EventEnvelope ordered = new EventEnvelope(eventId, "DeviceReleased", 1, T0, "Device", deviceId, null,
                "{\"deviceId\":\"" + deviceId + "\",\"reason\":\"REREGISTERED\"}");
        EventEnvelope reordered = new EventEnvelope(eventId, "DeviceReleased", 1, T0, "Device", deviceId, null,
                "{\"reason\":\"REREGISTERED\",\"deviceId\":\"" + deviceId + "\"}");

        NewInboxEvent received = NewInboxEvent.received(ordered, null, T0);
        NewInboxEvent ignored = NewInboxEvent.ignored(reordered, null, T0);

        assertThat(received.payloadHash()).isEqualTo(ignored.payloadHash()).hasSize(64);
        assertThat(received.ignored()).isFalse();
        assertThat(ignored.ignored()).isTrue();
        assertThat(ignored.toString()).contains("ignored=true").doesNotContain("REREGISTERED");
        assertThatThrownBy(() -> new NewInboxEvent(ordered, null, "ABC", false, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewInboxEvent(ordered, null, received.payloadHash(), false, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NewInboxEvent.received(null, null, T0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anOutboxRowRebuildsItsEnvelopeUntilThePayloadIsEmptied() {
        UUID guardian = UUID.randomUUID();
        EventEnvelope envelope = JsonCodec.shared().envelopeFor(UUID.randomUUID(),
                new DeviceReleased(UUID.randomUUID(), guardian, DeviceReleased.Reason.REREGISTERED), T0, null);
        OutboxEventRow pending = outboxRow(envelope, null);

        assertThat(pending.toEnvelope()).isEqualTo(envelope);
        assertThat(pending.toString()).doesNotContain("REREGISTERED");
        assertThatThrownBy(() -> outboxRow(envelope, T0).toEnvelope()).isInstanceOf(IllegalStateException.class);
        InboxEventRow inboxRow = new InboxEventRow(envelope.eventId(), "CORE", envelope.eventType(), 1, T0,
                envelope.aggregateId(), guardian, envelope.payloadJson(), "h", InboxEventRow.Status.RECEIVED, 0, T0, T0,
                null, null, null, null);
        assertThat(inboxRow.toString()).contains("RECEIVED").doesNotContain("REREGISTERED");
    }

    private static OutboxEventRow outboxRow(EventEnvelope envelope, Instant purgedAt) {
        return new OutboxEventRow(envelope.eventId(), envelope.eventType(), envelope.schemaVersion(),
                envelope.occurredAt(), envelope.aggregateType(), envelope.aggregateId(), UUID.randomUUID(),
                envelope.payloadJson(), "h", OutboxEventRow.Status.PENDING, 0, T0, null, null, purgedAt, T0,
                envelope.traceId());
    }
}
