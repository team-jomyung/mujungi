package kr.mujoong.realtime.gateway;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsPayload;

/**
 * 세션 실행기가 기기 연결 하나에 쓰는 입구 (U1 3.5 Step 24 · PD-1 · PD-4 · SD-17 · BR6.12 ④).
 *
 * <p>구현은 {@link DeviceConnection}(연결 송신기의 제어 · 음성 레인)이고 시험은 대역을 쓴다. 모든 송신은 비동기 레인 넣기라 실행기가
 * 소켓 쓰기를 기다리지 않는다. 연결이 닫혔으면 넣기는 거짓이다.
 */
public interface DeviceLink {

    /** WSS 연결 식별자(HELLO_OK.connectionId · connection_id). */
    UUID connectionId();

    /** 기기 id(verify-token 결과). */
    UUID deviceId();

    /** 아직 열려 있는가(닫힘 요청 · 닫힘 뒤에는 거짓). */
    boolean isOpen();

    /**
     * 제어 메시지를 제어 레인에 넣는다. STOP_OUTPUT은 음성 레인을 비운다. 제어 레인이 가득 차면 연결을 1011로 닫고 거짓이다(PD-4).
     *
     * @param opId 서버 명령 4종의 opId(그 밖은 null)
     * @param sessionId 세션 메시지의 sessionId(없으면 null)
     */
    boolean sendControl(WsPayload payload, UUID opId, UUID sessionId);

    /** 출력 묶음의 음성 바이트 자리를 예약한다(20초 분량 상한, 묶음 통째 허용 · 거절, PD-4). */
    Optional<AudioReservation> reserveAudio(int bytes);

    /** 예약을 쥔 묶음을 음성 레인에 넣는다. 닫혔으면 예약을 풀고 거짓이다. */
    boolean sendOutput(OutputBundle bundle);

    /** 음성 레인을 비운다(대기 묶음 · 진행 중 묶음의 남은 프레임). */
    void clearAudio();

    /** 서버가 연결을 닫는다. */
    void close(CloseCode code);

    /** 이 연결의 마지막 기기 메시지 수신 시각(메모리, BR6.12 ④). 없으면 연결이 열린 시각. */
    Instant lastMessageAt();

    /** 연결별 최신 clockOffsetMs(기기 − 서버, OD-8). 계산할 수 없으면 null. */
    Long clockOffsetMs();

    /** 연결별 최신 rttMs(HEARTBEAT lastRttMs). 없으면 null. */
    Long rttMs();

    /** 이 연결로 START_SESSION을 보낸 세션을 묶는다(입력 프레임 · ACK 경로). */
    void bindSession(UUID sessionId);

    /** 출력 묶음 음성 자리 예약. 프레임을 보내며 풀고, 버리면 남은 자리를 한 번에 푼다. */
    interface AudioReservation {

        /** 예약한 바이트 수. */
        int bytes();

        /** 아직 풀지 않은 자리를 모두 푼다. 두 번 불러도 된다. */
        void release();
    }
}
