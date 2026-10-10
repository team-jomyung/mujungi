package kr.mujoong.contract.ws.message;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.WsPayload;

/**
 * DEVICE_STATE(기기 → 서버). 마이크 · 소리 · 탭 보임 상태가 바뀔 때 바뀐 필드만 실어도 된다. U1 서버는 알려진 type으로 받아
 * 무시한다(ERROR 없음 · 연결 유지, J123). 마이크 문제와 연결 장애를 가르는 근거는 U3가 쓴다.
 */
public record DeviceState(Mic mic, Player player, Visibility visibility, WakeLock wakeLock) implements WsPayload {

    /** 마이크 상태. */
    public enum Mic {
        OK,
        LOST,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** 플레이어 상태. */
    public enum Player {
        OK,
        ERROR,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** 탭 보임 상태. */
    public enum Visibility {
        VISIBLE,
        HIDDEN,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    /** 화면 켜짐 유지 상태. */
    public enum WakeLock {
        HELD,
        RELEASED,
        /** 모르는 값('알 수 없음'). */
        UNRECOGNIZED
    }

    @Override
    public WsMessageType messageType() {
        return WsMessageType.DEVICE_STATE;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (mic == null && player == null && visibility == null && wakeLock == null) {
            problems.add("payload:empty");
        }
        return problems;
    }
}
