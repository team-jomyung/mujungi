package kr.mujoong.contract.ws.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/** VOICE_SESSION_CLOSED(기기 → 서버). 기기 멈춤 확인(ENDED confirmed 근거, BR6.10 ④). WSS는 대기용으로 남는다. */
public record VoiceSessionClosed(Instant outputStoppedAt, Instant micClosedAt) implements WsPayload {

    @Override
    public WsMessageType messageType() {
        return WsMessageType.VOICE_SESSION_CLOSED;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (outputStoppedAt == null) {
            problems.add("outputStoppedAt:missing");
        }
        if (micClosedAt == null) {
            problems.add("micClosedAt:missing");
        }
        return problems;
    }
}
