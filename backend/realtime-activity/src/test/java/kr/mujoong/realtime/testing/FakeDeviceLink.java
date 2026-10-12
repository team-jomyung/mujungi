package kr.mujoong.realtime.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.WsPayload;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.gateway.OutputBundle;

/**
 * 시험용 기기 연결(세션 실행기 단위 시험). 보낸 제어 메시지 · 출력 묶음을 기록하고, 음성 레인 예산 · 열림 · 마지막 메시지 시각을 시험이
 * 정한다. 출력 묶음은 {@link #deliver}로 송신기처럼 한 프레임씩 내보내며 세대 확인(stillCurrent)을 흉내 낸다.
 */
public final class FakeDeviceLink implements DeviceLink {

    /** 보낸 제어 메시지 한 건. */
    public record Sent(WsPayload payload, UUID opId, UUID sessionId) {
    }

    private final UUID connectionId = UUID.randomUUID();
    private final UUID deviceId;
    private final List<Sent> sent = new ArrayList<>();
    private final List<OutputBundle> outputs = new ArrayList<>();
    private final List<byte[]> deliveredFrames = new ArrayList<>();
    private boolean open = true;
    private boolean acceptControl = true;
    private long audioBudget = 960_000L;
    private long reserved;
    private Instant lastMessageAt;
    private Long clockOffsetMs;
    private Long rttMs;
    private UUID boundSession;
    private CloseCode closedWith;
    private int clearAudioCalls;

    public FakeDeviceLink(UUID deviceId, Instant openedAt) {
        this.deviceId = deviceId;
        this.lastMessageAt = openedAt;
    }

    @Override
    public UUID connectionId() {
        return connectionId;
    }

    @Override
    public UUID deviceId() {
        return deviceId;
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized boolean sendControl(WsPayload payload, UUID opId, UUID sessionId) {
        if (!open || !acceptControl) {
            return false;
        }
        sent.add(new Sent(payload, opId, sessionId));
        return true;
    }

    @Override
    public synchronized Optional<AudioReservation> reserveAudio(int bytes) {
        if (!open || reserved + bytes > audioBudget) {
            return Optional.empty();
        }
        reserved += bytes;
        return Optional.of(new AudioReservation() {
            private boolean released;

            @Override
            public int bytes() {
                return bytes;
            }

            @Override
            public void release() {
                synchronized (FakeDeviceLink.this) {
                    if (!released) {
                        released = true;
                        reserved -= bytes;
                    }
                }
            }
        });
    }

    @Override
    public synchronized boolean sendOutput(OutputBundle bundle) {
        if (!open) {
            bundle.reservation().release();
            return false;
        }
        outputs.add(bundle);
        return true;
    }

    @Override
    public synchronized void clearAudio() {
        clearAudioCalls++;
    }

    @Override
    public synchronized void close(CloseCode code) {
        open = false;
        closedWith = code;
    }

    @Override
    public synchronized Instant lastMessageAt() {
        return lastMessageAt;
    }

    @Override
    public synchronized Long clockOffsetMs() {
        return clockOffsetMs;
    }

    @Override
    public synchronized Long rttMs() {
        return rttMs;
    }

    @Override
    public synchronized void bindSession(UUID sessionId) {
        this.boundSession = sessionId;
    }

    /**
     * 송신기처럼 묶음 하나를 내보낸다: 프레임마다 세대를 확인해 다르면 남은 프레임을 버린다. 보낸 프레임마다 송신 콜백을, 첫 프레임 뒤 시각
     * 콜백을 부른다.
     *
     * @return 보낸 프레임 수
     */
    public int deliver(OutputBundle bundle, Instant at) {
        int count = 0;
        try {
            for (byte[] frame : bundle.frames()) {
                if (!bundle.stillCurrent().getAsBoolean()) {
                    break;
                }
                synchronized (this) {
                    deliveredFrames.add(frame.clone());
                }
                bundle.onFrameSent().accept(frame);
                if (count == 0) {
                    bundle.onFirstFrameSent().accept(at);
                }
                count++;
            }
        } finally {
            bundle.reservation().release();
        }
        return count;
    }

    public synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** 보낸 제어 메시지 중 그 종류만. */
    public synchronized <T extends WsPayload> List<T> sent(Class<T> type) {
        return sent.stream().map(Sent::payload).filter(type::isInstance).map(type::cast).toList();
    }

    public synchronized List<OutputBundle> outputs() {
        return List.copyOf(outputs);
    }

    public synchronized List<byte[]> deliveredFrames() {
        return List.copyOf(deliveredFrames);
    }

    public synchronized void setOpen(boolean value) {
        this.open = value;
    }

    public synchronized void setAcceptControl(boolean value) {
        this.acceptControl = value;
    }

    public synchronized void setAudioBudget(long bytes) {
        this.audioBudget = bytes;
    }

    public synchronized void touch(Instant at) {
        this.lastMessageAt = at;
    }

    public synchronized void timing(Long offset, Long rtt) {
        this.clockOffsetMs = offset;
        this.rttMs = rtt;
    }

    public synchronized UUID boundSession() {
        return boundSession;
    }

    @Override
    public synchronized UUID boundSessionId() {
        return boundSession;
    }

    public synchronized CloseCode closedWith() {
        return closedWith;
    }

    public synchronized long reservedBytes() {
        return reserved;
    }

    public synchronized int clearAudioCalls() {
        return clearAudioCalls;
    }
}
