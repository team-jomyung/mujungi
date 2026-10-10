package kr.mujoong.realtime.startup;

import java.time.Duration;
import java.util.Objects;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.session.SessionRegistry;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Realtime 정상 종료 T1 ~ T5 (U1 3.5 Step 26 · RD-13 · BR6.13 · R3-13 · NFR4.10).
 *
 * <p>가장 높은 phase의 SmartLifecycle이라 SIGTERM 뒤 가장 먼저 멈춘다(웹 서버 graceful 단계 MAX − 1024보다 앞). T1 준비 상태를
 * DRAINING으로 내린다(새 WSS는 1013, 새 /internal 요청은 503). T2 모든 기기 WSS를 1001로 닫는다(서버가 보낸 1001은 OFFLINE 사유가 아니다,
 * BR4.8 ②). T3 세션 실행기마다 ServerShutdown을 넣어 공급자 연결을 닫고 기록기를 비우게 한다. T4 주기 작업을 멈춘다. T5 기록기에 남은 칸을
 * 지금 스레드에서 쓰고 병합 창에 남은 반복 로그를 쓴다. 진행 중 세션은 닫지 않는다 — 다음 기동의 S3a가 FAILED(SERVER_RESTART)로 닫는다.
 */
@Component
public class DrainingLifecycle implements SmartLifecycle {

    private final ReadinessState readiness;
    private final SocketRegistry sockets;
    private final SessionRegistry sessions;
    private final RealtimeStartupSequence startupSequence;
    private final AsyncRecorder recorder;
    private final EventLogger events;
    private final Duration recorderGrace;
    private volatile boolean running;

    public DrainingLifecycle(ReadinessState readiness, SocketRegistry sockets, SessionRegistry sessions,
            RealtimeStartupSequence startupSequence, AsyncRecorder recorder, EventLogger events,
            RealtimeProperties properties) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.startupSequence = Objects.requireNonNull(startupSequence, "startupSequence");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.events = Objects.requireNonNull(events, "events");
        this.recorderGrace = properties.limits().dbStatementTimeout();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        readiness.markDraining();
        sockets.closeAll(CloseCode.GOING_AWAY);
        for (SessionExecutor executor : sessions.executors()) {
            executor.mailbox().putSafety(new SessionMailbox.ServerShutdown());
        }
        startupSequence.stopJobs();
        recorder.drain(recorderGrace);
        events.flushMerged();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
