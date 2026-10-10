package kr.mujoong.core.device;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.device.DeviceJdbcRepository.ConnectionReport;
import org.springframework.stereotype.Component;

/**
 * 연결 상태 사본 쓰기 (U1 3.5 Step 19 · BR4.1 · BR4.2 · 편차 (g)(i) · AC2.1.10 · J47).
 *
 * <p>두 경로가 같은 문장을 쓴다: Realtime의 주기 보고(PUT /internal/devices/{id}/connection, 비교 시각 = 본문 reportedAt)와
 * DeviceStatusChanged 처리기(비교 시각 = Inbox 행 occurred_at). 사본보다 늦은 시각일 때만, REGISTERED 행에만 한 문장 조건부 UPDATE로 덮는다.
 * 늦거나 같은 시각의 보고 · 등록되지 않은(해제된) 기기의 보고는 버린다 — 두 경로가 겹쳐도 reported_at이 줄지 않고 해제된 기기를 되살리지 않는다.
 * rtt · 끊김 수 · 마지막 수신이 없으면 기존 값을 둔다. 보고는 유실돼도 된다(다음 보고가 덮는다).
 */
@Component
public class ConnectionCopyWriter {

    private final DeviceJdbcRepository devices;
    private final MujoongClock clock;

    public ConnectionCopyWriter(DeviceJdbcRepository devices, MujoongClock clock) {
        this.devices = Objects.requireNonNull(devices, "devices");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 보고를 사본에 반영한다.
     *
     * @return 반영했으면 true, 늦은 보고 · 등록되지 않은 기기라 버렸으면 false(어느 쪽이든 내부 응답은 204)
     */
    public boolean apply(UUID deviceId, ConnectionReport report) {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(report, "report");
        return devices.applyConnectionReport(deviceId, report, clock.now()) == 1;
    }
}
