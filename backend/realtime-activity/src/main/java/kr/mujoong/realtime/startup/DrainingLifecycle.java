package kr.mujoong.realtime.startup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.snapshot.MetricSnapshotJob;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.session.SessionRegistry;
import kr.mujoong.realtime.web.InFlightRequests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Realtime 정상 종료 T1 ~ T6 (U1 3.5 Step 26 · 3.6 수정 · RD-13 · BR6.13 · R3-13 · NFR4.10 · J304, Core와 같은 방식).
 *
 * <p>가장 높은 phase의 SmartLifecycle이라 SIGTERM 뒤 가장 먼저 멈춘다(웹 서버 graceful 단계 MAX − 1024보다 앞). 순서는
 * reliability-design.md 2.8절 그대로이고, 모든 단계가 한 마감({@code spring.lifecycle.timeout-per-shutdown-phase}, 30초) 안에서 남은 시간만
 * 쓴다.
 *
 * <ol>
 *   <li>T1: 준비 상태를 DRAINING으로 내린다. 새 /internal 요청은 필터 F1b가 503 SERVICE_NOT_READY + Retry-After, 새 WSS는 업그레이드 뒤
 *       1013이다.
 *   <li>T2: 모든 기기 WSS를 1001로 닫는다(서버가 보낸 1001은 OFFLINE 사유가 아니다, BR4.8 ②).
 *   <li>T3: 세션 실행기마다 ServerShutdown을 넣어 공급자 연결을 닫고 기록기를 비우게 하고, 실행기들이 처리를 마칠 때까지 기다린다 — 폴러를
 *       멈추기 전에 유료 연결이 닫힌다.
 *   <li>T4: 주기 작업(outbox-sender · inbox-repoll · recorder-tick · maintenance)과 처리기 풀을 멈춘다(진행 중 한 건은 마치고 남은 선점은
 *       되돌린다).
 *   <li>T5: 기록기에 남은 칸을 지금 스레드에서 쓰고 병합 창에 남은 반복 로그와 마지막 METRIC_SNAPSHOT 한 줄을 쓴다(OD-6).
 *   <li>T6: F1b가 받아들인 진행 중 요청이 끝날 때까지 기다린다. 이 대기 동안 서버 커넥터는 열려 있다 — 프레임워크 graceful 단계(커넥터 일시
 *       정지 · 수신 소켓 닫기 = 접속 거절)는 이 메서드가 돌아온 뒤에야 시작한다. 그래서 SIGTERM 직후 새 요청은 입구 502가 아니라 앱의
 *       503이다(3.6 BT-RD-13).
 * </ol>
 *
 * <p>진행 중 세션은 닫지 않는다 — 다음 기동의 S3a가 FAILED(SERVER_RESTART)로 닫는다. 남은 시간 안에 끝나지 않은 단계는 APP_ERROR
 * ({@code ProviderCloseTimeout} · {@code DrainTimeout}) 한 줄을 남기고 다음 단계로 넘어간다(모든 일이 영속 표 위의 멱등 일이라 안전하다).
 */
@Component
public class DrainingLifecycle implements SmartLifecycle {

    /** 유예 길이 키(표준 프레임워크 키, reliability-design.md 3.5 · J77). */
    public static final String GRACE_KEY = "spring.lifecycle.timeout-per-shutdown-phase";

    private final ReadinessState readiness;
    private final SocketRegistry sockets;
    private final SessionRegistry sessions;
    private final RealtimeStartupSequence startupSequence;
    private final AsyncRecorder recorder;
    private final EventLogger events;
    private final MetricSnapshotJob snapshots;
    private final InFlightRequests inFlight;
    private final MujoongClock clock;
    private final Duration recorderGrace;
    private final Duration grace;
    private volatile boolean running;

    @Autowired
    public DrainingLifecycle(ReadinessState readiness, SocketRegistry sockets, SessionRegistry sessions,
            RealtimeStartupSequence startupSequence, AsyncRecorder recorder, EventLogger events,
            MetricSnapshotJob snapshots, InFlightRequests inFlight, MujoongClock clock, RealtimeProperties properties,
            Environment environment) {
        this(readiness, sockets, sessions, startupSequence, recorder, events, snapshots, inFlight, clock,
                properties.limits().dbStatementTimeout(), Binder.get(environment).bind(GRACE_KEY, Duration.class)
                        .orElseThrow(() -> new IllegalStateException("필수 설정 키가 없다: " + GRACE_KEY)));
    }

    /**
     * @param recorderGrace T5 기록기 비우기의 상한(진행 중 플러시 한 트랜잭션 = 문장 제한)
     * @param grace T1 ~ T6 전체의 마감
     */
    public DrainingLifecycle(ReadinessState readiness, SocketRegistry sockets, SessionRegistry sessions,
            RealtimeStartupSequence startupSequence, AsyncRecorder recorder, EventLogger events,
            MetricSnapshotJob snapshots, InFlightRequests inFlight, MujoongClock clock, Duration recorderGrace,
            Duration grace) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.startupSequence = Objects.requireNonNull(startupSequence, "startupSequence");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.events = Objects.requireNonNull(events, "events");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recorderGrace = Objects.requireNonNull(recorderGrace, "recorderGrace");
        this.grace = Objects.requireNonNull(grace, "grace");
        if (grace.isZero() || grace.isNegative()) {
            throw new IllegalArgumentException("설정 키는 0보다 커야 한다: " + GRACE_KEY);
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        long started = clock.monotonicNanos();
        readiness.markDraining();
        sockets.closeAll(CloseCode.GOING_AWAY);
        closeProviders(remaining(started));
        startupSequence.stopJobs(remaining(started));
        Duration left = remaining(started);
        recorder.drain(recorderGrace.compareTo(left) <= 0 ? recorderGrace : left);
        events.flushMerged();
        try {
            snapshots.finalSnapshot();
        } catch (RuntimeException e) {
            // 마지막 줄을 못 써도 종료는 이어 간다
            events.appError(EventLog.Component.REALTIME_MAINTENANCE, e);
        }
        awaitInFlight(remaining(started));
        running = false;
    }

    /** T3. 실행기가 이미 끝나 편지함이 닫혔으면 기다리지 않는다. */
    private void closeProviders(Duration limit) {
        List<CompletableFuture<Void>> handled = new ArrayList<>();
        for (SessionExecutor executor : sessions.executors()) {
            SessionMailbox.ServerShutdown shutdown = new SessionMailbox.ServerShutdown();
            if (executor.mailbox().putSafety(shutdown)) {
                handled.add(shutdown.handled());
            }
        }
        if (handled.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(handled.toArray(new CompletableFuture<?>[0]))
                    .get(Math.max(0L, limit.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            events.appError(EventLog.Component.REALTIME_MAINTENANCE, "ProviderCloseTimeout");
        } catch (ExecutionException e) {
            events.appError(EventLog.Component.REALTIME_MAINTENANCE, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** T6. 한도가 이미 지났으면 기다리지 않는다. */
    private void awaitInFlight(Duration limit) {
        try {
            if (limit.isZero() || !inFlight.awaitIdle(limit)) {
                if (inFlight.active() > 0) {
                    events.appError(EventLog.Component.REALTIME_MAINTENANCE, "DrainTimeout");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 마감까지 남은 시간(0 이상). */
    private Duration remaining(long startedNanos) {
        Duration left = grace.minus(clock.elapsedSince(startedNanos));
        return left.isNegative() ? Duration.ZERO : left;
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
