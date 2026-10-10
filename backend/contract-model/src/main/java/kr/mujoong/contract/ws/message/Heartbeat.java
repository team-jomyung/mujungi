package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * HEARTBEAT(기기 → 서버, 5초마다). lastRttMs는 앞 왕복 지연이다. 기기는 송신 직전 봉투 sentAt을 채우고 서버는
 * {@code sentAt − (수신 시각 − rttMs ÷ 2)}로 clockOffsetMs를 계산한다(OD-8 · R3-4).
 */
public record Heartbeat(Long hbSeq, Long lastRttMs) implements WsPayload {

    @Override
    public WsMessageType messageType() {
        return WsMessageType.HEARTBEAT;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (hbSeq == null || hbSeq < 0) {
            problems.add("hbSeq:invalid");
        }
        if (lastRttMs != null && lastRttMs < 0) {
            problems.add("lastRttMs:negative");
        }
        return problems;
    }
}
