package kr.mujoong.realtime.gateway;

import java.util.UUID;

/**
 * 세션 실행기가 기기 판정을 바꾸는 입구 (U1 3.5 Step 24 · BR4.8 ③ · J55 · BR6.12 ② ③).
 *
 * <p>명령 시한(ACK · DEVICE_READY 빠짐 20초, VOICE_SESSION_CLOSED 없음 30초)에 진 연결만 UNRESPONSIVE로 쓴다. 구현은
 * {@link ConnectionStateWriter}이고 시험은 대역을 쓴다. 실패는 호출자에게 던지지 않는다(전이는 이미 커밋됐다).
 */
@FunctionalInterface
public interface DeviceStatusPort {

    /** 그 연결이 아직 원장의 현재 연결이면 CONNECTED · UNSTABLE → UNRESPONSIVE(DeviceStatusChanged 함께). */
    void markUnresponsive(UUID deviceId, UUID connectionId);
}
