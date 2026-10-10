package kr.mujoong.contract.ws.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * HELLO_OK(서버 → 기기). 이 시점에 같은 기기의 옛 연결을 4001로 닫는다(편차 (l) · BR3.7). heartbeatIntervalMs는
 * {@code mujoong.timing.device.heartbeat-interval}에서 온다. U1의 session은 null이다(이어가기는 U3).
 */
public record HelloOk(UUID connectionId, Instant serverTime, Long heartbeatIntervalMs, String audio, SessionInfo session)
        implements WsPayload {

    /** 확정 입력 형식(J27). */
    public static final String AUDIO_PCM16LE_24K_MONO = "pcm16le/24000/1";

    /** 이어가기 결과(U3). */
    public record SessionInfo(UUID sessionId, View view, Boolean resumed) {
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.HELLO_OK;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (connectionId == null) {
            problems.add("connectionId:missing");
        }
        if (serverTime == null) {
            problems.add("serverTime:missing");
        }
        if (heartbeatIntervalMs == null || heartbeatIntervalMs < 1) {
            problems.add("heartbeatIntervalMs:invalid");
        }
        if (!AUDIO_PCM16LE_24K_MONO.equals(audio)) {
            problems.add("audio:invalid");
        }
        if (session != null && (session.sessionId() == null || session.view() == null || session.resumed() == null)) {
            problems.add("session:invalid");
        }
        return problems;
    }
}
