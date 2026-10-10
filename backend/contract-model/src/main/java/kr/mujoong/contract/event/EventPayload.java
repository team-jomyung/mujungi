package kr.mujoong.contract.event;

import java.util.List;
import java.util.UUID;

/**
 * 영속 이벤트 payload(계약 6절 · schemas/event-payloads.schema.json). {@code event.payload} 패키지의 레코드 6종이 구현하고
 * {@link EventType#payloadType()}이 짝을 정한다(클래스패스 모듈이라 sealed로 묶지 않는다).
 *
 * <p>{@link #guardianId()}는 Outbox/Inbox 행 guardian_id의 원천이다(A4). DeviceStatusChanged · DeviceReleased는 선택 필드라
 * null일 수 있고, 그 경우 쓰는 쪽이 자기 행에서 채운다.
 */
public interface EventPayload {

    /** 이 payload의 이벤트 종류. */
    EventType eventType();

    /** 봉투 aggregateId가 될 값(세션 · 기기 · 삭제 요청 id). */
    UUID aggregateId();

    /** 소유 보호자 id(A4). 선택 필드인 이벤트는 null일 수 있다. */
    UUID guardianId();

    /** 보내는 쪽 형식 위반(필수 필드 누락 등)의 필드 이름 목록. 비어 있으면 통과. 값은 담지 않는다. */
    List<String> problems();
}
