package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * DEVICE_READY(기기 → 서버). mic 또는 player가 UNAVAILABLE이면 즉시 FAILED(DEVICE_NO_ACK), prefetch는 OK · PARTIAL 모두 수락한다(BR6.8).
 */
public record DeviceReady(Availability mic, Availability player, Prefetch prefetch) implements WsPayload {

    /** 마이크 · 플레이어 준비. */
    public enum Availability {
        OK,
        UNAVAILABLE,
        /** 모르는 값('알 수 없음'). 준비됨으로 보지 않는다. */
        UNRECOGNIZED
    }

    /** 승인 음성 미리 받기 결과. */
    public enum Prefetch {
        OK,
        PARTIAL,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** mic · player가 둘 다 OK인가(ACTIVE 전이 조건의 기기 쪽, BR6.8). */
    public boolean isReady() {
        return mic == Availability.OK && player == Availability.OK;
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.DEVICE_READY;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (mic == null) {
            problems.add("mic:missing");
        }
        if (player == null) {
            problems.add("player:missing");
        }
        if (prefetch == null) {
            problems.add("prefetch:missing");
        }
        return problems;
    }
}
