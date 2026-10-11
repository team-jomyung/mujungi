package kr.mujoong.realtime.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 기기 연결 상태 주기 보고 (U1 3.5 Step 24 · BR4.1 · BR4.8 · 계약 5절 C9 · 편차 (i) · RD-14 · NFR4.1).
 *
 * <p>maintenance 스레드가 {@code mujoong.timing.device.status-report-interval}(10초)마다 부른다. 원장의 모든 행(OFFLINE 포함)을 Core
 * {@code PUT /internal/devices/{deviceId}/connection}으로 보낸다(제한 {@code mujoong.internal-http.device-status-timeout} 1초, 재시도
 * 없음 — 다음 주기가 다시 보낸다). reportedAt은 그 행을 읽은 Realtime 시각이다. 한 라운드에서 연속
 * {@code mujoong.internal-http.status-round-skip-threshold}(3)건이 실패하면 그 라운드의 남은 행은 건너뛴다(Core 장애 때 maintenance가
 * 묶이지 않게).
 */
@Component
@SingleInstanceAssumption(si = "SI-7",
        reason = "인스턴스마다 10초 상태 보고가 돌아 같은 원장 행을 Core에 겹쳐 보낸다(덮어쓰기라 결과는 같다)")
public class StatusReporter {

    /** 보고 본문(계약 internal-device-connection-report). */
    public record ConnectionReport(ConnectionState state, Integer rttMs, Integer recentDisconnects, Instant since,
            Instant lastSeenAt, Instant reportedAt) {
    }

    /** 한 라운드 결과. */
    public record Round(int sent, int failed, int skipped) {
    }

    private final DeviceConnectionRepository connections;
    private final InternalHttpClient http;
    private final JsonCodec json;
    private final EventLogger events;
    private final MujoongClock clock;
    private final Duration timeout;
    private final int skipThreshold;

    @Autowired
    public StatusReporter(DeviceConnectionRepository connections, InternalHttpClient http, JsonCodec json,
            EventLogger events, MujoongClock clock, RealtimeProperties properties) {
        this(connections, http, json, events, clock, properties.internalHttp().deviceStatusTimeout(),
                properties.internalHttp().statusRoundSkipThreshold());
    }

    public StatusReporter(DeviceConnectionRepository connections, InternalHttpClient http, JsonCodec json,
            EventLogger events, MujoongClock clock, Duration timeout, int skipThreshold) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (skipThreshold < 1) {
            throw new IllegalArgumentException("status-round-skip-threshold는 1 이상이다");
        }
        this.skipThreshold = skipThreshold;
    }

    /** 한 라운드를 보낸다. */
    public Round runOnce() {
        int sent = 0;
        int failed = 0;
        int skipped = 0;
        int consecutiveFailures = 0;
        for (DeviceConnectionRow row : connections.findAll()) {
            if (consecutiveFailures >= skipThreshold) {
                skipped++;
                continue;
            }
            if (report(row)) {
                sent++;
                consecutiveFailures = 0;
            } else {
                failed++;
                consecutiveFailures++;
            }
        }
        return new Round(sent, failed, skipped);
    }

    private boolean report(DeviceConnectionRow row) {
        Instant reportedAt = clock.now();
        Instant since = row.state() == ConnectionState.CONNECTED && row.connectionSince() != null
                ? row.connectionSince() : row.updatedAt();
        ConnectionReport body = new ConnectionReport(row.state(), row.lastRttMs(), row.recentDisconnects(), since,
                row.lastSeenAt(), reportedAt);
        try {
            InternalHttpClient.Response response = http.send(InternalHttpClient.Target.DEVICE_STATUS,
                    new InternalHttpClient.Call("PUT", "/internal/devices/" + row.deviceId() + "/connection",
                            json.write(body), timeout, 1, row.deviceId(), null));
            return response.status() >= 200 && response.status() < 300;
        } catch (InternalHttpClient.CallException e) {
            return false;
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.DEVICE_GATEWAY, e);
            return false;
        }
    }
}
