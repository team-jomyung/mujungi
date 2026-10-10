package kr.mujoong.contract.ws.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/** HEARTBEAT_ACK(서버 → 기기). HEARTBEAT를 받자마자 같은 hbSeq로 답한다. */
public record HeartbeatAck(Long hbSeq, Instant serverTime) implements WsPayload {

    @Override
    public WsMessageType messageType() {
        return WsMessageType.HEARTBEAT_ACK;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (hbSeq == null || hbSeq < 0) {
            problems.add("hbSeq:invalid");
        }
        if (serverTime == null) {
            problems.add("serverTime:missing");
        }
        return problems;
    }
}
