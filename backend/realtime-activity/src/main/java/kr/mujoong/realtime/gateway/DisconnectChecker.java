package kr.mujoong.realtime.gateway;

import java.time.Duration;
import java.util.Objects;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 연결 원장 40초 무음 점검 (U1 3.5 Step 24 · BR4.8 ② · J55 · AC6.2.10 · AC6.2.11).
 *
 * <p>maintenance 스레드가 {@code mujoong.device.disconnect-check-interval}(5초)마다 부른다. CONNECTED · UNSTABLE 행 중
 * {@code last_seen_at <= 지금 − mujoong.timing.device.disconnect}(40초)인 것을 {@code device_id AND connection_id} 조건부로
 * DISCONNECTED로 바꾼다(소켓이 열린 채 무음이어도 같다). 바뀐 행만 DeviceStatusChanged를 쓴다. 원장 상태 변화는 세션 전이를 일으키지 않는다(BR4.7).
 */
@Component
@SingleInstanceAssumption(si = "SI-7",
        reason = "인스턴스마다 5초 연결 점검이 돌아 같은 무음 행을 두고 경합한다(조건부 갱신이라 결과는 같다)")
public class DisconnectChecker {

    private final DeviceConnectionRepository connections;
    private final ConnectionStateWriter writer;
    private final EventLogger events;
    private final MujoongClock clock;
    private final Duration disconnectAfter;

    @Autowired
    public DisconnectChecker(DeviceConnectionRepository connections, ConnectionStateWriter writer, EventLogger events,
            MujoongClock clock, RealtimeProperties properties) {
        this(connections, writer, events, clock, properties.timing().device().disconnect());
    }

    public DisconnectChecker(DeviceConnectionRepository connections, ConnectionStateWriter writer, EventLogger events,
            MujoongClock clock, Duration disconnectAfter) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.disconnectAfter = Objects.requireNonNull(disconnectAfter, "disconnectAfter");
    }

    /** 한 번 점검한다. DISCONNECTED로 바꾼 행 수. */
    public int runOnce() {
        int changed = 0;
        for (DeviceConnectionRow row : connections.findSilent(clock.now(), disconnectAfter)) {
            try {
                if (row.connectionId() != null
                        && writer.markDisconnected(row.deviceId(), row.connectionId(), disconnectAfter).isPresent()) {
                    changed++;
                }
            } catch (RuntimeException e) {
                events.appError(EventLog.Component.DEVICE_GATEWAY, e);
            }
        }
        return changed;
    }
}
