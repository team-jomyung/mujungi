package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/** OUTPUT_BEGIN(서버 → 기기). 게이트를 통과한 AI 말 묶음 시작. 뒤따르는 0x02가 이 묶음이다(BR7.4 · BR7.6). */
public record OutputBegin(UUID playbackId, Source source) implements WsPayload {

    /** 출력 원천. */
    public enum Source {
        GPT_LIVE,
        SCENARIO_SCRIPT,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.OUTPUT_BEGIN;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (playbackId == null) {
            problems.add("playbackId:missing");
        }
        if (source == null || source == Source.UNRECOGNIZED) {
            problems.add("source:invalid");
        }
        return problems;
    }
}
