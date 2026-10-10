package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/** STOP_OUTPUT(서버 → 기기, opId 필수). 출력 취소. 실제로 멈춘 재생은 PLAYBACK_MARK(STOPPED)로만 확인한다. */
public record StopOutput(Reason reason) implements WsPayload {

    /** 취소 사유. */
    public enum Reason {
        GATE_CANCEL,
        GUARDIAN_STOP,
        CHILD_STOP,
        SILENCE,
        BEFORE_CLOSING,
        SERVER_BARGE_IN,
        TECHNICAL,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.STOP_OUTPUT;
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
