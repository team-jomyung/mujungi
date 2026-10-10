package kr.mujoong.contract.ws;

import java.util.Arrays;
import kr.mujoong.contract.ws.message.Ack;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.Heartbeat;
import kr.mujoong.contract.ws.message.HeartbeatAck;
import kr.mujoong.contract.ws.message.Hello;
import kr.mujoong.contract.ws.message.HelloOk;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.OutputEnd;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.contract.ws.message.WsError;

/**
 * 기기 WSS 제어 메시지 17종(계약 4.2 표). 방향 · payload 타입 · opId 필요 여부 · 봉투 sessionId 필요 여부를 한 곳에 둔다.
 *
 * <p>opId는 확인이 필요한 서버 명령 4종(START_SESSION · PLAY_APPROVED_CONTENT · STOP_OUTPUT · CLOSE_TRANSPORT)에만 붙는다.
 * 모르는 type은 {@link #UNRECOGNIZED}이고 READY 뒤에는 ERROR(UNKNOWN_TYPE)로 답한다(J123).
 */
public enum WsMessageType {
    HELLO(Direction.DEVICE_TO_SERVER, Hello.class, false, false),
    HELLO_OK(Direction.SERVER_TO_DEVICE, HelloOk.class, false, false),
    HEARTBEAT(Direction.DEVICE_TO_SERVER, Heartbeat.class, false, false),
    HEARTBEAT_ACK(Direction.SERVER_TO_DEVICE, HeartbeatAck.class, false, false),
    START_SESSION(Direction.SERVER_TO_DEVICE, StartSession.class, true, true),
    ACK(Direction.DEVICE_TO_SERVER, Ack.class, false, false),
    DEVICE_READY(Direction.DEVICE_TO_SERVER, DeviceReady.class, false, true),
    SESSION_STATE(Direction.SERVER_TO_DEVICE, SessionState.class, false, false),
    OUTPUT_BEGIN(Direction.SERVER_TO_DEVICE, OutputBegin.class, false, true),
    OUTPUT_END(Direction.SERVER_TO_DEVICE, OutputEnd.class, false, false),
    PLAY_APPROVED_CONTENT(Direction.SERVER_TO_DEVICE, PlayApprovedContent.class, true, true),
    PLAYBACK_MARK(Direction.DEVICE_TO_SERVER, PlaybackMark.class, false, true),
    STOP_OUTPUT(Direction.SERVER_TO_DEVICE, StopOutput.class, true, true),
    CLOSE_TRANSPORT(Direction.SERVER_TO_DEVICE, CloseTransport.class, true, true),
    VOICE_SESSION_CLOSED(Direction.DEVICE_TO_SERVER, VoiceSessionClosed.class, false, true),
    DEVICE_STATE(Direction.DEVICE_TO_SERVER, DeviceState.class, false, false),
    ERROR(Direction.SERVER_TO_DEVICE, WsError.class, false, false),
    /** 모르는 type('알 수 없음'). 보내지 않는다. */
    UNRECOGNIZED(null, null, false, false);

    /** 메시지 방향. */
    public enum Direction {
        DEVICE_TO_SERVER,
        SERVER_TO_DEVICE
    }

    private final Direction direction;
    private final Class<? extends WsPayload> payloadType;
    private final boolean opIdRequired;
    private final boolean sessionIdRequired;

    WsMessageType(Direction direction, Class<? extends WsPayload> payloadType, boolean opIdRequired,
            boolean sessionIdRequired) {
        this.direction = direction;
        this.payloadType = payloadType;
        this.opIdRequired = opIdRequired;
        this.sessionIdRequired = sessionIdRequired;
    }

    /** 방향. UNRECOGNIZED는 null. */
    public Direction direction() {
        return direction;
    }

    /** payload 레코드 타입. UNRECOGNIZED는 null. */
    public Class<? extends WsPayload> payloadType() {
        return payloadType;
    }

    /** 봉투에 opId가 꼭 있어야 하는가(서버 명령 4종). 그 밖의 type에는 opId가 없어야 한다. */
    public boolean isOpIdRequired() {
        return opIdRequired;
    }

    /** 봉투에 sessionId가 꼭 있어야 하는가. */
    public boolean isSessionIdRequired() {
        return sessionIdRequired;
    }

    /** 선 형식 type 문자열을 읽는다. 모르는 값 · null은 {@link #UNRECOGNIZED}. */
    public static WsMessageType fromWire(String value) {
        return Arrays.stream(values())
                .filter(type -> type != UNRECOGNIZED && type.name().equals(value))
                .findFirst()
                .orElse(UNRECOGNIZED);
    }
}
