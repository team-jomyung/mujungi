package kr.mujoong.realtime.gateway;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import org.springframework.stereotype.Component;

/**
 * 살아 있는 기기 WSS 레지스트리 (U1 3.5 Step 24 · BR3.7 · BR6.6 ② · RD-4 · RD-13 T2).
 *
 * <p>두 지도를 둔다: 열린 연결 전부(핸드셰이크 중 포함, 정상 종료 때 1001을 보낼 대상)와 기기마다 HELLO_OK를 받은 현재 연결 하나.
 * 시작 명령은 연결 원장 행의 connection_id가 여기 '현재 연결'로 살아 있을 때만 받는다(BR6.6 ②). 닫힌 소켓은 즉시 빠지므로 그 사이 시작
 * 명령은 423이다(RD-4). 교체는 연결 행 교체 트랜잭션을 커밋한 뒤에만 한다(R3-7).
 */
@Component
@SingleInstanceAssumption(si = "SI-1",
        reason = "기기 → 현재 연결 · 연결 id → 연결 지도가 이 프로세스 메모리에만 있어 시작 명령 · 연결 대체(4001) · DeviceReleased(4401)가 소켓 없는 인스턴스로 가면 닿지 않는다")
public class SocketRegistry {

    private final ConcurrentHashMap<UUID, DeviceConnection> open = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, DeviceConnection> currentByDevice = new ConcurrentHashMap<>();

    /** 열린 연결로 등록한다(업그레이드 직후). */
    public void register(DeviceConnection connection) {
        open.put(connection.connectionId(), Objects.requireNonNull(connection, "connection"));
    }

    /**
     * 기기의 현재 연결을 바꾼다(HELLO_OK, 커밋 뒤). 옛 현재 연결을 돌려준다(없거나 같은 연결이면 빈 값).
     */
    public Optional<DeviceConnection> replaceCurrent(UUID deviceId, DeviceConnection connection) {
        Objects.requireNonNull(deviceId, "deviceId");
        DeviceConnection previous = currentByDevice.put(deviceId, Objects.requireNonNull(connection, "connection"));
        return previous == null || previous == connection ? Optional.empty() : Optional.of(previous);
    }

    /** 닫힌 연결을 뺀다(현재 연결이면 현재 자리에서도). */
    public void remove(DeviceConnection connection) {
        open.remove(connection.connectionId(), connection);
        UUID deviceId = connection.deviceId();
        if (deviceId != null) {
            currentByDevice.remove(deviceId, connection);
        }
    }

    /** 기기의 현재 연결. */
    public Optional<DeviceConnection> current(UUID deviceId) {
        DeviceConnection connection = currentByDevice.get(Objects.requireNonNull(deviceId, "deviceId"));
        return connection != null && connection.isOpen() ? Optional.of(connection) : Optional.empty();
    }

    /** 연결 id로 찾는다(열린 연결만). */
    public Optional<DeviceConnection> byConnectionId(UUID connectionId) {
        if (connectionId == null) {
            return Optional.empty();
        }
        DeviceConnection connection = open.get(connectionId);
        return connection != null && connection.isOpen() ? Optional.of(connection) : Optional.empty();
    }

    /** 그 기기의 현재 연결이 이 connection_id로 살아 있는가(BR6.6 ②). */
    public boolean isLive(UUID deviceId, UUID connectionId) {
        return connectionId != null && current(deviceId).map(c -> c.connectionId().equals(connectionId)).orElse(false);
    }

    /** 기기의 열린 연결을 모두 닫는다(DeviceReleased 4401, BR3.8). 닫은 수. */
    public int closeDevice(UUID deviceId, CloseCode code) {
        int closed = 0;
        for (DeviceConnection connection : List.copyOf(open.values())) {
            if (deviceId.equals(connection.deviceId()) && connection.isOpen()) {
                connection.close(code);
                closed++;
            }
        }
        return closed;
    }

    /** 열린 연결을 모두 닫는다(정상 종료 T2 1001). 닫은 수. */
    public int closeAll(CloseCode code) {
        int closed = 0;
        for (DeviceConnection connection : List.copyOf(open.values())) {
            if (connection.isOpen()) {
                connection.close(code);
                closed++;
            }
        }
        return closed;
    }

    /** 열린 연결 수. */
    public int openCount() {
        return open.size();
    }

    /** 열린 연결들의 제어 레인 깊이 합({@code mujoong_queue_depth{queue=output_control}}, J298). */
    public long queuedControl() {
        long queued = 0;
        for (DeviceConnection connection : open.values()) {
            queued += connection.controlQueueDepth();
        }
        return queued;
    }

    /** 열린 연결들의 음성 레인 묶음 수 합({@code mujoong_queue_depth{queue=output_audio}}, J298). */
    public long queuedAudio() {
        long queued = 0;
        for (DeviceConnection connection : open.values()) {
            queued += connection.audioQueueDepth();
        }
        return queued;
    }
}
