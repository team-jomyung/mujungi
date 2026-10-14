package kr.mujoong.realtime.messaging;

/**
 * Outbox 행이 PUBLISHED가 된 뒤 부르는 뒷일 (U4b L6 · BR9.5).
 *
 * <p>송신기가 PUBLISHED 표시(조건부 UPDATE 1행)에 성공한 뒤 송신 스레드에서 부른다. 예외는 송신기가 앱 오류 한 줄로 남기고 넘어간다(발행 결과는 바뀌지
 * 않는다). 예: DeletionCompleted가 발행되면 메시징 삭제 참가자를 한 번 더 부른다.
 */
@FunctionalInterface
public interface OutboxPublishedListener {

    /** 그 행이 발행됐다(payload는 이미 비웠다 — 행의 열만 쓴다). */
    void published(OutboxEventRow row);
}
