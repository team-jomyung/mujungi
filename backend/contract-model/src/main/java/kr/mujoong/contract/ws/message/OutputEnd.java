package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/** OUTPUT_END(서버 → 기기). 그 묶음 마지막 0x02 프레임 뒤. */
public record OutputEnd(UUID playbackId, Long totalMs) implements WsPayload {

    @Override
    public WsMessageType messageType() {
        return WsMessageType.OUTPUT_END;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (playbackId == null) {
            problems.add("playbackId:missing");
        }
        if (totalMs != null && totalMs < 0) {
            problems.add("totalMs:negative");
        }
        return problems;
    }
}
