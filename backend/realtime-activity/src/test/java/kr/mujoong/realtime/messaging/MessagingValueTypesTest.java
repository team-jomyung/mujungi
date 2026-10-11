package kr.mujoong.realtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Outbox/Inbox 값 타입 단위 시험(U1 3.5 Step 18, DB 없음).
 *
 * <p>증명: BR5.13(last_error는 공백 없는 코드 · 예외 종류 토큰만, 페이로드 · 메시지 없음) · BR5.5(payload_hash는 정규화 payload의
 * SHA-256이라 키 순서가 달라도 같다) · BR5.9(IGNORED 표시) · RD-8(비운 행은 다시 보낼 수 없음). 3.6 수정(BT-TC-10)에서 경계를 더했다:
 * Outbox 방향 규칙을 모든 이벤트 종류와 모르는 종류에(RealtimeMessagingRepositoryIT가 본 두 종류 밖, J318) · payload_hash 64자 소문자
 * 16진수의 양쪽 경계 · last_error 길이와 HTTP 범위의 포함 경계.
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

    @Test
    void theOutboxTakesEveryRealtimeToCoreTypeAndRefusesCoreToRealtimeAndUnknownTypes() {
        UUID guardian = UUID.randomUUID();
        for (EventType type : EventType.values()) {
            if (type == EventType.UNRECOGNIZED) {
                continue;
            }
            EventEnvelope envelope = envelope(type.wire(), type.aggregateType().wire());
            if (type.direction() == EventType.Direction.REALTIME_TO_CORE) {
                assertThat(NewOutboxEvent.of(envelope, guardian).envelope().type()).as(type.wire()).isEqualTo(type);
            } else {
                assertThatThrownBy(() -> NewOutboxEvent.of(envelope, guardian)).as(type.wire())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        // 모르는 eventType(BR5.9 수신 쪽 IGNORED 대상)은 보내는 쪽 Outbox에 쓸 수 없다
        EventEnvelope unknown = envelope("SessionRenamed", "ActivitySession");
        assertThat(unknown.type()).isEqualTo(EventType.UNRECOGNIZED);
        assertThatThrownBy(() -> NewOutboxEvent.of(unknown, guardian)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SessionRenamed");
    }

    @Test
    void payloadHashMustBeExactly64LowercaseHexOnBothTables() {
        EventEnvelope outbound = envelope(EventType.DEVICE_STATUS_CHANGED.wire(), "Device");
        EventEnvelope inbound = envelope(EventType.DEVICE_RELEASED.wire(), "Device");
        String exact = "0123456789abcdef".repeat(4);
        UUID guardian = UUID.randomUUID();

        assertThat(new NewOutboxEvent(outbound, guardian, exact).payloadHash()).hasSize(64);
        assertThat(new NewInboxEvent(inbound, null, exact, false, T0).payloadHash()).isEqualTo(exact);
        for (String bad : new String[] {exact.substring(1), exact + "0", exact.toUpperCase(), "g" + exact.substring(1),
                null}) {
            assertThatThrownBy(() -> new NewOutboxEvent(outbound, guardian, bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain(exact.substring(0, 16)));
            assertThatThrownBy(() -> new NewInboxEvent(inbound, null, bad, false, T0)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void lastErrorBoundsAreInclusiveAndTheNamedCodesAreValidTokens() {
        assertThat(LastError.require("x")).isEqualTo("x");
        assertThat(LastError.require("E".repeat(LastError.MAX_LENGTH))).hasSize(LastError.MAX_LENGTH);
        assertThat(LastError.http(100)).isEqualTo("HTTP_100");
        assertThat(LastError.http(599)).isEqualTo("HTTP_599");
        assertThat(LastError.require(LastError.EVENT_CONFLICT)).isEqualTo("EVENT_CONFLICT");
        assertThat(LastError.require(LastError.INVALID_ENVELOPE)).isEqualTo("INVALID_ENVELOPE");
        assertThat(LastError.require(LastError.ACCOUNT_DELETED)).isEqualTo("ACCOUNT_DELETED");

        assertThatThrownBy(() -> LastError.http(99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LastError.exceptionKind(null)).isInstanceOf(IllegalArgumentException.class);
        // 줄바꿈 · 탭 · 한글 문장 조각은 토큰이 아니다(값은 오류 메시지에 남지 않는다)
        for (String bad : new String[] {"HTTP_503\n", "a\tb", "전사조각"}) {
            assertThatThrownBy(() -> LastError.require(bad)).isInstanceOf(IllegalArgumentException.class)
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain(bad));
        }
    }

    private static EventEnvelope envelope(String eventType, String aggregateType) {
        return new EventEnvelope(UUID.randomUUID(), eventType, 1, T0, aggregateType, UUID.randomUUID(), null, "{}");
    }

    private static OutboxEventRow outboxRow(EventEnvelope envelope, Instant purgedAt) {
        return new OutboxEventRow(envelope.eventId(), envelope.eventType(), envelope.schemaVersion(),
                envelope.occurredAt(), envelope.aggregateType(), envelope.aggregateId(), UUID.randomUUID(),
                envelope.payloadJson(), "h", OutboxEventRow.Status.PENDING, 0, T0, null, null, purgedAt, T0,
                envelope.traceId());
    }
}
