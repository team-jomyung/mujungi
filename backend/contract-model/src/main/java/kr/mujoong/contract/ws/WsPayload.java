package kr.mujoong.contract.ws;

import java.util.List;

/**
 * 기기 WSS 제어 메시지 payload(계약 4.2 · schemas/ws-messages.schema.json). {@code ws.message} 패키지의 레코드 17종이 구현하고
 * {@link WsMessageType#payloadType()}이 짝을 정한다.
 */
public interface WsPayload {

    /** 이 payload의 메시지 type. */
    WsMessageType messageType();

    /** 형식 위반(필수 필드 누락 · 범위 밖)의 필드 이름 목록. 비어 있으면 통과. 값은 담지 않는다. */
    List<String> problems();
}
