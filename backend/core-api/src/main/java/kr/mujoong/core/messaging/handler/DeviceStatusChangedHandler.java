package kr.mujoong.core.messaging.handler;

import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.event.payload.DeviceStatusChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.device.ConnectionCopyWriter;
import kr.mujoong.core.device.DeviceJdbcRepository.ConnectionReport;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import org.springframework.stereotype.Component;

/**
 * DeviceStatusChanged 처리기 (U1 3.5 Step 20 · BR4.1 · BR4.2 · BR5.10 · 편차 (g)).
 *
 * <p>Realtime의 상태 변화 이벤트를 연결 상태 사본에 반영한다. 비교 시각은 봉투 occurredAt(Inbox 행 occurred_at)이고 주기 보고와 같은
 * 조건부 UPDATE 한 문장({@link ConnectionCopyWriter})을 쓴다: 사본보다 늦은 시각 · REGISTERED 행에만 덮는다. 늦거나 같은 시각 · 해제된
 * 기기의 이벤트는 버리고 PROCESSED다(두 경로 · 역순 도착에도 최종 값이 같다). 모르는 state('알 수 없음')는 사본을 바꾸지 않는다.
 */
@Component
public class DeviceStatusChangedHandler implements InboxHandler {

    private final ConnectionCopyWriter writer;
    private final JsonCodec json;

    public DeviceStatusChangedHandler(ConnectionCopyWriter writer, JsonCodec json) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public EventType eventType() {
        return EventType.DEVICE_STATUS_CHANGED;
    }

    @Override
    public Result handle(InboxEvent event, Instant now) {
        DeviceStatusChanged payload = json.read(event.payloadJson(), DeviceStatusChanged.class);
        if (payload.deviceId() == null || payload.state() == null || payload.state() == ConnectionState.UNRECOGNIZED) {
            return Result.done();
        }
        writer.apply(payload.deviceId(), new ConnectionReport(payload.state(), event.occurredAt(), payload.rttMs(),
                payload.recentDisconnects(), payload.since(), payload.lastSeenAt()));
        return Result.done();
    }
}
