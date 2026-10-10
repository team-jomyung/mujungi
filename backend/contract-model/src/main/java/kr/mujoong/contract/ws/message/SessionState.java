package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/** SESSION_STATE(서버 → 기기). T19 표시가 바뀔 때(BR9.6 · BR6.10). 대기 복귀는 종료 전이 뒤 STANDBY. */
public record SessionState(View view) implements WsPayload {

    @Override
    public WsMessageType messageType() {
        return WsMessageType.SESSION_STATE;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (view == null || view == View.UNRECOGNIZED) {
            problems.add("view:invalid");
        }
        return problems;
    }
}
