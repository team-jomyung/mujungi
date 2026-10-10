package kr.mujoong.realtime.gateway;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.realtime.messaging.InboxEventRow;
import kr.mujoong.realtime.messaging.InboxHandler;
import org.springframework.stereotype.Component;

/**
 * DeviceReleased 처리기 (U1 3.5 Step 24 · BR3.8 · BR5.7 · J46).
 *
 * <p>Inbox 처리 트랜잭션 안에서 연결 원장을 {@code device_id} 조건으로 OFFLINE · connection_id 비움으로 바꾸고 바뀌었으면
 * DeviceStatusChanged를 같은 트랜잭션에 쓴다. 커밋 뒤(후속 작업) 그 기기의 살아 있는 소켓을 4401로 닫는다 — 기기 웹은 /device/me로 확인하고
 * 401이면 D0로 간다. 다시 처리돼도 결과가 같다(멱등).
 */
@Component
public class DeviceReleasedHandler implements InboxHandler {

    private final ConnectionStateWriter writer;
    private final SocketRegistry sockets;
    private final JsonCodec json;

    public DeviceReleasedHandler(ConnectionStateWriter writer, SocketRegistry sockets, JsonCodec json) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public EventType eventType() {
        return EventType.DEVICE_RELEASED;
    }

    @Override
    public Result handle(InboxEventRow event, Instant now) {
        DeviceReleased payload = json.read(event.payloadJson(), DeviceReleased.class);
        UUID deviceId = payload == null || payload.deviceId() == null ? event.aggregateId() : payload.deviceId();
        if (deviceId == null) {
            throw new IllegalArgumentException("DeviceReleased에 deviceId가 없다");
        }
        writer.release(deviceId);
        return Result.then(() -> sockets.closeDevice(deviceId, CloseCode.UNAUTHORIZED));
    }
}
