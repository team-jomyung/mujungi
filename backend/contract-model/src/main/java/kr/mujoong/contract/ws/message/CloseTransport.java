package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/** CLOSE_TRANSPORT(서버 → 기기, opId 필수). 세션 끝. 기기는 ACK 뒤 정리하고 VOICE_SESSION_CLOSED를 보낸다(BR6.10 ③). */
public record CloseTransport(Reason reason) implements WsPayload {

    /** 닫는 사유. */
    public enum Reason {
        ENDED,
        GUARDIAN_STOP,
        CHILD_STOP,
        SILENCE,
        DISCONNECT,
        CONSENT_WITHDRAWN,
        DECLINED,
        FAILED,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.CLOSE_TRANSPORT;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (reason == null || reason == Reason.UNRECOGNIZED) {
            problems.add("reason:invalid");
        }
        return problems;
    }
}
