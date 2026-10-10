package kr.mujoong.contract.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.trace.TraceIds;

/**
 * 영속 이벤트 봉투(계약 6절 Envelope · BR5.4 · schemas/event-envelope.schema.json).
 *
 * <p>필수 7필드(eventId · eventType · schemaVersion ≥ 1 · occurredAt · aggregateType · aggregateId · payload)와 선택 필드
 * traceId(편차 (p) · OD-7)를 가진다. eventType · aggregateType은 받은 문자열 그대로 두고 {@link #type()} · {@link #aggregate()}로
 * 해석한다 — 모르는 값도 봉투로는 유효하다(BR5.9). payload는 정규화 JSON 문자열로 들고 해시 · 저장에 쓴다.
 * 봉투 디코딩 · 인코딩은 {@code kr.mujoong.contract.json.JsonCodec}이 한다.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateType,
        UUID aggregateId,
        String traceId,
        String payloadJson) {

    public EventEnvelope {
        if (eventId == null) {
            throw new IllegalArgumentException("봉투에 eventId가 없다");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("봉투에 eventType이 없다");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("봉투 schemaVersion은 1 이상이어야 한다");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("봉투에 occurredAt이 없다");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("봉투에 aggregateType이 없다");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("봉투에 aggregateId가 없다");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("봉투에 payload가 없다");
        }
        if (traceId != null && !TraceIds.isValid(traceId)) {
            throw new IllegalArgumentException("봉투 traceId 형식이 틀렸다");
        }
    }

    /**
     * 보내는 쪽 봉투를 만든다. eventType · aggregateType · aggregateId는 payload에서 정해진다. traceId가 형식에 맞지 않으면 null로 둔다.
     *
     * @param payloadJson {@code JsonCodec}이 payload를 정규화해 쓴 JSON
     */
    public static EventEnvelope create(
            UUID eventId, EventPayload payload, String payloadJson, Instant occurredAt, String traceId) {
        if (payload == null) {
            throw new IllegalArgumentException("payload가 없다");
        }
        List<String> problems = payload.problems();
        if (!problems.isEmpty()) {
            throw new InvalidEnvelopeException(problems);
        }
        EventType type = payload.eventType();
        return new EventEnvelope(
                eventId,
                type.wire(),
                EventType.CURRENT_SCHEMA_VERSION,
                occurredAt,
                type.aggregateType().wire(),
                payload.aggregateId(),
                TraceIds.isValid(traceId) ? traceId : null,
                payloadJson);
    }

    /** eventType 해석(모르는 값은 UNRECOGNIZED). */
    public EventType type() {
        return EventType.fromWire(eventType);
    }

    /** aggregateType 해석(모르는 값은 UNRECOGNIZED). */
    public AggregateType aggregate() {
        return AggregateType.fromWire(aggregateType);
    }

    /**
     * 받는 쪽이 처리기를 부를 수 있는가: 아는 eventType이고 schemaVersion이 앱이 아는 최대값 이하. 아니면 IGNORED(BR5.9).
     */
    public boolean isHandleable() {
        return type() != EventType.UNRECOGNIZED && schemaVersion <= EventType.CURRENT_SCHEMA_VERSION;
    }

    /**
     * 봉투 검사 실패(400 INVALID_ENVELOPE, BR5.4). 문제 목록은 필드 이름과 위반 종류만 담고 값은 담지 않는다(BR5.13).
     */
    public static final class InvalidEnvelopeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final List<String> problems;

        public InvalidEnvelopeException(List<String> problems) {
            super("이벤트 봉투 형식 오류: " + String.join(", ", problems));
            this.problems = List.copyOf(problems);
        }

        /** 문제 목록(예: {@code eventId:missing}). */
        public List<String> problems() {
            return problems;
        }
    }
}
