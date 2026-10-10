package kr.mujoong.realtime.gateway;

import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.message.Heartbeat;
import kr.mujoong.contract.ws.message.HeartbeatAck;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.runtime.TaskLauncher;
import org.springframework.stereotype.Component;

/**
 * HEARTBEAT 처리 (U1 3.5 Step 24 · BR4.8 · J55 · J125 · OD-8 · R3-4 · PD-1 표 2.1-C 아래 문단).
 *
 * <p>HEARTBEAT는 세션 편지함에 넣지 않는다. 수신 핸들러 스레드에서 ① HEARTBEAT_ACK(hbSeq)를 먼저 넣고 ② 연결의 rttMs(기기가 보낸 직전
 * 왕복)와 clockOffsetMs = sentAt − (수신 − rttMs ÷ 2)를 바꾸고(봉투 sentAt이 없거나 틀리면 offset을 비운다 → LATENCY_TURN
 * CLOCK_OFFSET_MISSING) ③ last_seen_at은 기록기의 기기 칸에 넘긴다(기기당 마지막 값, J125 — HEARTBEAT 때만). ④ 연결 원장 사본이
 * UNRESPONSIVE · DISCONNECTED면 같은 connection_id의 HEARTBEAT로 CONNECTED 복귀를 보조 작업으로 쓴다(J55). 수신 스레드에서 DB를 기다리지
 * 않는다(SD-22).
 */
@Component
public class HeartbeatHandler {

    private final AsyncRecorder recorder;
    private final ConnectionStateWriter writer;
    private final TaskLauncher launcher;
    private final EventLogger events;
    private final MujoongClock clock;

    public HeartbeatHandler(AsyncRecorder recorder, ConnectionStateWriter writer, TaskLauncher launcher,
            EventLogger events, MujoongClock clock) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param sentAtMissing 봉투 sentAt이 없거나 틀렸는가(WsFrameGuard 판정)
     * @param receivedAt 수신 직후 시각
     */
    public void onHeartbeat(DeviceConnection connection, WsEnvelope envelope, boolean sentAtMissing, Instant receivedAt) {
        Objects.requireNonNull(connection, "connection");
        Heartbeat heartbeat = (Heartbeat) envelope.payload();
        connection.sendControl(new HeartbeatAck(heartbeat.hbSeq(), clock.now()), null, null);
        Long rtt = heartbeat.lastRttMs() != null && heartbeat.lastRttMs() >= 0 ? heartbeat.lastRttMs() : null;
        Long rttForOffset = rtt != null ? rtt : connection.rttMs();
        Long offset = sentAtMissing ? null : LatencyRecorder.clockOffsetMs(envelope.sentAt(), receivedAt, rttForOffset);
        connection.timing(rtt, offset);
        connection.principal().ifPresent(principal -> {
            recorder.deviceSeen(new DeviceConnectionRepository.LastSeen(principal.deviceId(),
                    connection.connectionId(), receivedAt, rtt == null ? null : (int) Math.min(rtt, Integer.MAX_VALUE)));
            if (DeviceStatusPolicy.heartbeatRecovers(connection.knownState())) {
                launcher.launch("device-recover-" + connection.connectionId(), () -> recover(connection, principal));
            }
        });
    }

    private void recover(DeviceConnection connection, DeviceConnection.Principal principal) {
        try {
            writer.markConnectedAgain(principal.deviceId(), connection.connectionId(), connection.traceId())
                    .ifPresent(row -> connection.knownState(row.state()));
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.DEVICE_GATEWAY, e);
        }
    }
}
