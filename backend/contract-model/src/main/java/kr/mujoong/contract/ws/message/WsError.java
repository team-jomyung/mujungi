package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * ERROR(서버 → 기기, type 이름 ERROR). READY 뒤 모르는 type · 틀린 payload에 보내고 연결을 유지한다. 크기 · 규격 · HELLO 형식 ·
 * 방향 위반은 ERROR가 아니라 4400이다(편차 (s) · J123). refSeq는 문제 메시지의 봉투 seq다.
 */
public record WsError(Code code, Long refSeq) implements WsPayload {

    /** 오류 종류. */
    public enum Code {
        UNKNOWN_TYPE,
        BAD_PAYLOAD,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.ERROR;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (code == null || code == Code.UNRECOGNIZED) {
            problems.add("code:invalid");
        }
        if (refSeq != null && refSeq < 1) {
            problems.add("refSeq:range");
        }
        return problems;
    }
}
