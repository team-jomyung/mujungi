package kr.mujoong.realtime.gateway;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.time.TimerDriver;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;

/**
 * 기기 WSS 연결 하나의 메모리 상태 (U1 3.5 Step 24 · SD-7 핸드셰이크 상태 기계 · BR3.6 · BR3.7 · BR4.8 · RD-4 · OD-8).
 *
 * <p>상태는 AWAIT_VERIFY → AWAIT_HELLO → READY → CLOSED이고 확인이 끝나기 전의 메시지는 처리하지 않는다. 마지막 기기 메시지 시각 ·
 * clockOffsetMs · rttMs는 수신 핸들러가 원자적으로 바꾸는 휘발 값이다(세션 ④ 시계 · LATENCY_TURN이 읽는다). {@link #knownState()}는
 * 연결 원장 행 상태의 메모리 사본이라 HEARTBEAT마다 DB를 보지 않고 복귀(UNRESPONSIVE · DISCONNECTED → CONNECTED)가 필요한지 안다.
 * 기기 토큰은 확인이 끝나면 지운다(BR8.7).
 *
 * <p>U3: HELLO의 이어가기 요청(resume.sessionId)과 마지막 DEVICE_STATE(필드별 최신값, 마이크 · 소리 · 보임 · 켜짐 유지)를 메모리에 둔다
 * (U3-J4 · U3-J9). 둘 다 판단 근거일 뿐 원장에는 쓰지 않는다.
 */
@SingleInstanceAssumption(si = "SI-1",
        reason = "마지막 기기 메시지 시각 · 시계 환산 입력(clockOffsetMs · rttMs) · HELLO 시계가 연결 객체 메모리에만 있어 다른 인스턴스가 받은 메시지를 볼 수 없다")
public final class DeviceConnection implements DeviceLink {

    /** 핸드셰이크 상태(SD-7). */
    public enum Phase {
        AWAIT_VERIFY,
        AWAIT_HELLO,
        READY,
        CLOSED
    }

    /** verify-token이 돌려준 주체(BR3.4). */
    public record Principal(UUID deviceId, UUID guardianId, UUID childId) {

        public Principal {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(guardianId, "guardianId");
        }
    }

    private final UUID connectionId;
    private final String traceId;
    private final Instant openedAt;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.AWAIT_VERIFY);
    private final AtomicBoolean serverClosing = new AtomicBoolean();
    private final AtomicBoolean transportFailed = new AtomicBoolean();
    private volatile ConnectionSender sender;
    private volatile Principal principal;
    private volatile Instant lastMessageAt;
    private volatile Long rttMs;
    private volatile Long clockOffsetMs;
    private volatile ConnectionState knownState = ConnectionState.OFFLINE;
    /** 묶인 세션(비교 후 풀기를 한 번에 하려고 참조 원자값으로 둔다, 3차 리뷰 4). */
    private final AtomicReference<UUID> boundSessionId = new AtomicReference<>();
    private volatile TimerDriver.TimerHandle helloTimer;
    private final AtomicReference<WsEnvelope> deferredHello = new AtomicReference<>();
    private volatile UUID resumeSessionId;
    private volatile DeviceState knownDeviceState;
    private long lastInboundSeq;

    public DeviceConnection(UUID connectionId, String traceId, Instant openedAt) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.lastMessageAt = openedAt;
    }

    /** 송신기를 붙인다(한 번). */
    public void attachSender(ConnectionSender value) {
        this.sender = Objects.requireNonNull(value, "sender");
    }

    @Override
    public UUID connectionId() {
        return connectionId;
    }

    /** 연결 traceId(Nginx X-Trace-Id가 형식에 맞으면 그 값, J123). */
    public String traceId() {
        return traceId;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Phase phase() {
        return phase.get();
    }

    /** 상태를 {@code expected}에서 {@code next}로 바꾼다. */
    public boolean advance(Phase expected, Phase next) {
        return phase.compareAndSet(expected, next);
    }

    /** CLOSED로 바꾸고 직전 상태를 돌려준다. */
    public Phase markClosed() {
        return phase.getAndSet(Phase.CLOSED);
    }

    /** verify-token 성공(BR3.4). */
    public void verified(Principal value) {
        this.principal = Objects.requireNonNull(value, "principal");
    }

    public Optional<Principal> principal() {
        return Optional.ofNullable(principal);
    }

    @Override
    public UUID deviceId() {
        Principal current = principal;
        return current == null ? null : current.deviceId();
    }

    @Override
    public boolean isOpen() {
        ConnectionSender current = sender;
        return phase.get() != Phase.CLOSED && current != null && !current.isClosing();
    }

    @Override
    public boolean sendControl(WsPayload payload, UUID opId, UUID sessionId) {
        ConnectionSender current = sender;
        return phase.get() != Phase.CLOSED && current != null && current.enqueueControl(payload, opId, sessionId);
    }

    @Override
    public Optional<AudioReservation> reserveAudio(int bytes) {
        ConnectionSender current = sender;
        if (phase.get() != Phase.READY || current == null) {
            return Optional.empty();
        }
        return current.reserve(bytes);
    }

    @Override
    public boolean sendOutput(OutputBundle bundle) {
        ConnectionSender current = sender;
        if (phase.get() != Phase.READY || current == null) {
            bundle.reservation().release();
            return false;
        }
        return current.enqueueOutput(bundle);
    }

    @Override
    public void clearAudio() {
        ConnectionSender current = sender;
        if (current != null) {
            current.clearAudio();
        }
    }

    /** 서버가 닫는다(서버가 보낸 닫기는 OFFLINE 사유가 아니다, BR4.8 ②). */
    @Override
    public void close(CloseCode code) {
        serverClosing.set(true);
        ConnectionSender current = sender;
        if (current != null) {
            current.close(code);
        }
    }

    /** 서버가 닫기를 시작했는가. */
    public boolean isServerClosing() {
        return serverClosing.get();
    }

    /** 전송 오류(TCP 끊김 · EOF)를 받았다(U3-J2: 서버가 1011로 닫아도 네트워크 끊김으로 센다). */
    public void markTransportFailed() {
        transportFailed.set(true);
    }

    /** 전송 오류로 끊겼는가. */
    public boolean isTransportFailed() {
        return transportFailed.get();
    }

    /** 제어 레인 깊이(송신기가 없으면 0, J298). */
    public int controlQueueDepth() {
        ConnectionSender current = sender;
        return current == null ? 0 : current.controlDepth();
    }

    /** 음성 레인 묶음 수(송신기가 없으면 0, J298). */
    public int audioQueueDepth() {
        ConnectionSender current = sender;
        return current == null ? 0 : current.audioDepth();
    }

    /** 기기 메시지를 받았다(모든 텍스트 · 바이너리 프레임, BR6.12 ④ · RD-4). */
    public void touch(Instant receivedAt) {
        this.lastMessageAt = receivedAt;
    }

    @Override
    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    @Override
    public Long clockOffsetMs() {
        return clockOffsetMs;
    }

    @Override
    public Long rttMs() {
        return rttMs;
    }

    /** HEARTBEAT로 계산한 값(OD-8 · R3-4). 계산할 수 없으면 offset은 null로 둔다. */
    public void timing(Long rtt, Long offset) {
        if (rtt != null) {
            this.rttMs = rtt;
        }
        this.clockOffsetMs = offset;
    }

    /** 연결 원장 행 상태의 메모리 사본. */
    public ConnectionState knownState() {
        return knownState;
    }

    public void knownState(ConnectionState value) {
        this.knownState = Objects.requireNonNull(value, "knownState");
    }

    @Override
    public void bindSession(UUID sessionId) {
        boundSessionId.set(sessionId);
    }

    /** 이 연결로 START_SESSION을 받은 세션(없으면 null). */
    @Override
    public UUID boundSessionId() {
        return boundSessionId.get();
    }

    @Override
    public boolean unbindSessionIf(UUID expected) {
        if (expected == null) {
            return false;
        }
        while (true) {
            // compareAndSet은 참조로 비교하므로 값이 같은 지금 참조를 읽어 그것으로 바꾼다
            UUID current = boundSessionId.get();
            if (!expected.equals(current)) {
                return false;
            }
            if (boundSessionId.compareAndSet(current, null)) {
                return true;
            }
        }
    }

    /** HELLO 타이머(시계 1, 4408). */
    public void helloTimer(TimerDriver.TimerHandle handle) {
        this.helloTimer = handle;
    }

    /** HELLO 타이머를 끈다. */
    public void cancelHelloTimer() {
        TimerDriver.TimerHandle handle = helloTimer;
        if (handle != null) {
            handle.cancel();
        }
    }

    /** 확인 전에 도착한 HELLO(확인 뒤 처리, SD-7 5단계). */
    public void deferHello(WsEnvelope hello) {
        deferredHello.set(hello);
    }

    /** 미뤄 둔 HELLO를 꺼낸다. */
    public Optional<WsEnvelope> takeDeferredHello() {
        return Optional.ofNullable(deferredHello.getAndSet(null));
    }

    /** HELLO가 이어가기를 요청한 세션(U3-J4). 없으면 null. */
    public UUID resumeSessionId() {
        return resumeSessionId;
    }

    public void resumeSessionId(UUID value) {
        this.resumeSessionId = value;
    }

    /** 마지막으로 알려진 기기 상태(필드별 최신값, U3-J9). 없으면 null. */
    public DeviceState knownDeviceState() {
        return knownDeviceState;
    }

    /** DEVICE_STATE를 받아 바뀐 필드만 덮는다. 합친 뒤 상태를 돌려준다. */
    public synchronized DeviceState mergeDeviceState(DeviceState update) {
        Objects.requireNonNull(update, "update");
        DeviceState current = knownDeviceState;
        DeviceState merged = current == null ? update : new DeviceState(
                update.mic() != null ? update.mic() : current.mic(),
                update.player() != null ? update.player() : current.player(),
                update.visibility() != null ? update.visibility() : current.visibility(),
                update.wakeLock() != null ? update.wakeLock() : current.wakeLock());
        knownDeviceState = merged;
        return merged;
    }

    /** 기기가 먼저 닫았다(송신기를 멈춘다). */
    public void senderClosedByPeer() {
        ConnectionSender current = sender;
        if (current != null) {
            current.markClosedByPeer();
        }
    }

    /**
     * 받은 봉투 seq가 처음인가(같은 seq는 한 번만 처리, 계약 4.2). 수신 핸들러는 연결마다 차례로 불리므로 동기화하지 않는다.
     */
    public synchronized boolean acceptInboundSeq(long seq) {
        if (seq <= lastInboundSeq) {
            return false;
        }
        lastInboundSeq = seq;
        return true;
    }

    @Override
    public String toString() {
        return "DeviceConnection[connectionId=" + connectionId + ", phase=" + phase.get() + "]";
    }
}
