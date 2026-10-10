package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/** ACK(기기 → 서버). opId가 있는 명령을 받자마자 ackOf로 돌려준다. 받았다는 뜻만이다(재생 · 중단의 증거가 아니다). */
public record Ack(UUID ackOf) implements WsPayload {

    @Override
    public WsMessageType messageType() {
        return WsMessageType.ACK;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (ackOf == null) {
            problems.add("ackOf:missing");
        }
        return problems;
    }
}
