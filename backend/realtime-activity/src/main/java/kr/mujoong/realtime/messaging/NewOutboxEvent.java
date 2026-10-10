package kr.mujoong.realtime.messaging;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.json.JsonCodec;

/**
 * Realtime Outbox에 쓸 이벤트 한 건(BR5.1 · A3 · A4 · entities RealtimeOutboxEvent).
 *
 * <p>Realtime → Core 이벤트(SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted)만 받는다.
 * guardian_id는 보호자 근거 인자로 늘 채운다(A4: U1 이벤트에는 보호자와 무관한 것이 없다). payload_hash는 정규화 payload의
 * SHA-256이고 traceId는 봉투 값(형식이 맞을 때만, A3 · OD-7)이다.
 *
 * @param envelope 보낼 봉투({@code JsonCodec.envelopeFor}가 만든 것)
 * @param guardianId 행 guardian_id(A4, 필수)
 * @param payloadHash 정규화 payload의 SHA-256 소문자 16진수
 */
public record NewOutboxEvent(EventEnvelope envelope, UUID guardianId, String payloadHash) {

    public NewOutboxEvent {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(guardianId, "guardianId(A4)");
        EventType type = envelope.type();
        if (type.direction() != EventType.Direction.REALTIME_TO_CORE) {
            throw new IllegalArgumentException("Realtime Outbox는 Realtime → Core 이벤트만 쓴다: " + envelope.eventType());
        }
        if (payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadHash는 SHA-256 소문자 16진수 64자여야 한다");
        }
    }

    /** 봉투에서 만든다(payload_hash 계산 포함). */
    public static NewOutboxEvent of(EventEnvelope envelope, UUID guardianId) {
        Objects.requireNonNull(envelope, "envelope");
        return new NewOutboxEvent(envelope, guardianId, JsonCodec.shared().sha256OfCanonical(envelope.payloadJson()));
    }

    /** payload는 문자열 표현에 넣지 않는다(BR5.13). */
    @Override
    public String toString() {
        return "NewOutboxEvent[eventId=" + envelope.eventId() + ", eventType=" + envelope.eventType() + "]";
    }
}
