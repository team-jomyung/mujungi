package kr.mujoong.realtime.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.json.JsonCodec;

/**
 * Realtime Inbox에 수락 기록으로 쓸 이벤트 한 건(BR5.5 · BR5.6 · BR5.9 · A3 · A4 · entities RealtimeInboxEvent).
 *
 * <p>처리기가 있는 이벤트는 RECEIVED(next_attempt_at = received_at)로, 처리기가 없거나 상위 schemaVersion이면 처음부터
 * IGNORED(payload 비움)로 들어간다. payload_hash는 원래 payload의 정규화 SHA-256이라 같은 eventId 중복 · 충돌 판정에 쓰인다.
 *
 * @param envelope 받은 봉투
 * @param guardianId 행 guardian_id(A4: payload의 guardianId, 없으면 null)
 * @param payloadHash 정규화 payload의 SHA-256 소문자 16진수
 * @param ignored 처음부터 IGNORED로 쓸지(BR5.9)
 * @param receivedAt 수신 시각(Clock)
 */
public record NewInboxEvent(EventEnvelope envelope, UUID guardianId, String payloadHash, boolean ignored,
        Instant receivedAt) {

    public NewInboxEvent {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadHash는 SHA-256 소문자 16진수 64자여야 한다");
        }
    }

    /** 처리기가 있는 이벤트(RECEIVED). */
    public static NewInboxEvent received(EventEnvelope envelope, UUID guardianId, Instant receivedAt) {
        return new NewInboxEvent(envelope, guardianId, hash(envelope), false, receivedAt);
    }

    /** 처리기가 없거나 상위 schemaVersion인 이벤트(IGNORED, BR5.9). */
    public static NewInboxEvent ignored(EventEnvelope envelope, UUID guardianId, Instant receivedAt) {
        return new NewInboxEvent(envelope, guardianId, hash(envelope), true, receivedAt);
    }

    private static String hash(EventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return JsonCodec.shared().sha256OfCanonical(envelope.payloadJson());
    }

    /** payload는 문자열 표현에 넣지 않는다(BR5.13). */
    @Override
    public String toString() {
        return "NewInboxEvent[eventId=" + envelope.eventId() + ", eventType=" + envelope.eventType() + ", ignored="
                + ignored + "]";
    }
}
