package kr.mujoong.realtime.gateway;

import java.util.EnumSet;
import java.util.Set;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.ws.CloseCode;

/**
 * 기기 연결 원장 상태 규칙 (U1 3.5 Step 24 · BR4.7 · BR4.8 · J55 · SM2).
 *
 * <p>상태 기록 장치({@link ConnectionStateWriter})와 수신 핸들러가 같은 판정을 쓰도록 순수 함수로 모은다.
 *
 * <ul>
 *   <li>닫힘(②): 기기가 닫기 프레임으로 보낸 정상 닫기(1000 「끄기」 · 1001 페이지 이탈)만 즉시 OFFLINE이다. 서버가 보낸 닫기(재시작 ·
 *       배포 1001, 4001 대체, 4400 · 4401 · 4408 · 1011 · 1013)와 닫기 프레임 없는 끊김(1006 등)은 상태를 바꾸지 않고 40초 점검이 정한다.
 *       HELLO_OK 전에 닫힌 연결은 원장을 건드리지 않는다(BR3.7).
 *   <li>UNRESPONSIVE(③, J55): 명령 시한 두 경우에만 쓴다 — START_SESSION 뒤 ACK 또는 DEVICE_READY가 빠진 20초 시한(전송 실패 포함)과
 *       CLOSING 뒤 VOICE_SESSION_CLOSED가 없는 30초 시한. 기기가 답했는데 공급자 쪽으로 실패하거나 DEVICE_READY가 사용 불가를 알린
 *       경우는 쓰지 않는다.
 *   <li>복귀(J55): UNRESPONSIVE · DISCONNECTED인 연결도 같은 connection_id의 다음 HEARTBEAT 또는 새 연결의 HELLO_OK로 CONNECTED가 된다.
 *   <li>BR4.7: 원장 상태의 변화는 세션 전이를 일으키지 않는다. 세션을 움직이는 연결 사건은 세션별 40초 시계(BR6.12 ④)뿐이다.
 * </ul>
 */
public final class DeviceStatusPolicy {

    /** UNRESPONSIVE를 쓰는 명령 시한(J55). */
    public enum CommandTimeout {
        /** START_SESSION 뒤 ACK 또는 DEVICE_READY가 빠짐(20초, 전송 실패 포함). */
        START_NO_ACK,
        /** ACK · DEVICE_READY는 왔는데 공급자 session.started가 없음. */
        START_PROVIDER,
        /** DEVICE_READY가 mic · player 사용 불가를 알림. */
        START_DEVICE_UNAVAILABLE,
        /** CLOSING 뒤 VOICE_SESSION_CLOSED가 없음(30초). */
        CLOSING_NO_CONFIRM
    }

    private static final Set<ConnectionState> RECOVERABLE =
            EnumSet.of(ConnectionState.UNRESPONSIVE, ConnectionState.DISCONNECTED);

    private DeviceStatusPolicy() {
    }

    /**
     * 연결이 닫혔을 때 원장을 즉시 OFFLINE으로 바꾸는가.
     *
     * @param closeCode 닫기 코드(1006은 닫기 프레임 없는 끊김)
     * @param serverInitiated 서버가 닫기를 시작했는가
     * @param reachedReady HELLO_OK를 보낸 연결인가
     */
    public static boolean closesToOffline(int closeCode, boolean serverInitiated, boolean reachedReady) {
        if (!reachedReady || serverInitiated) {
            return false;
        }
        return closeCode == CloseCode.DEVICE_OFF.code() || closeCode == CloseCode.GOING_AWAY.code();
    }

    /** 같은 연결의 HEARTBEAT가 원장을 CONNECTED로 되돌려야 하는가. */
    public static boolean heartbeatRecovers(ConnectionState known) {
        return known != null && RECOVERABLE.contains(known);
    }

    /** 이 명령 시한이 기기 판정을 UNRESPONSIVE로 쓰는가(J55). */
    public static boolean marksUnresponsive(CommandTimeout timeout) {
        return timeout == CommandTimeout.START_NO_ACK || timeout == CommandTimeout.CLOSING_NO_CONFIRM;
    }
}
