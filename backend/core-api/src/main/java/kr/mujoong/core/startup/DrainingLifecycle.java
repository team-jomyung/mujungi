package kr.mujoong.core.startup;

import java.time.Duration;
import java.util.Objects;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.observability.snapshot.MetricSnapshotJob;
import kr.mujoong.core.web.InFlightRequests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Core 정상 종료 T1 · T4 · T5 · T6 (U1 3.5 Step 21 · 3.6 수정 · RD-13 · J125 · R3-13 · NFR4.10 · J321).
 *
 * <p>가장 높은 phase의 SmartLifecycle이라 SIGTERM 뒤 가장 먼저 멈춘다(웹 서버 graceful 단계 MAX − 1024보다 앞). 순서는
 * reliability-design.md 2.8절 그대로다.
 *
 * <ol>
 *   <li>T1: 준비 상태를 DRAINING으로 내린다. 새 REST · /internal 요청은 필터 F1b가 503 SERVICE_NOT_READY + Retry-After로 답한다.
 *   <li>T4: 스케줄러를 멈춘다(발송 중인 한 건 · 처리 중인 처리기는 마치고, 남은 Outbox 선점은 available_at = now로 되돌린다).
 *   <li>T5: 병합 창에 남은 반복 로그를 쓰고 마지막 METRIC_SNAPSHOT 한 줄을 쓴다(OD-6). 그 줄의 적체 DB 읽기는 마감(아래 30초)의 남은 시간
 *       안에서만 기다리고, 못 읽으면 적체 키를 뺀다(3.6 수정 J369).
 *   <li>T6: F1b가 받아들인 진행 중 요청이 끝날 때까지 기다린다. 이 대기 동안 서버 커넥터는 열려 있다 — 프레임워크 graceful 단계(커넥터
 *       일시 정지 · 수신 소켓 닫기 = 접속 거절)는 이 메서드가 돌아온 뒤에야 시작한다. 그래서 SIGTERM 직후 새 요청은 Nginx 502가 아니라 앱의
 *       503이다(3.6 BT-RD-13). 대기 한도는 {@code spring.lifecycle.timeout-per-shutdown-phase}(30초)에서 T1 ~ T5에 쓴 시간을 뺀 값이다.
 * </ol>
 *
 * <p>진행 중 세션은 닫지 않는다(Core 사본은 다음 이벤트가 덮는다). 한도 안에 끝나지 않은 요청은 APP_ERROR({@code DrainTimeout}) 한 줄을 남기고
 * 프레임워크 graceful 단계와 Compose {@code stop_grace_period}에 맡긴다(모든 일이 멱등이라 안전하다).
 */
@Component
public class DrainingLifecycle implements SmartLifecycle {

    /** 유예 길이 키(표준 프레임워크 키, reliability-design.md 3.5 · J77). */
    public static final String GRACE_KEY = "spring.lifecycle.timeout-per-shutdown-phase";

    private final ReadinessState readiness;
    private final CoreStartupSequence startupSequence;
    private final EventLogger events;
    private final MetricSnapshotJob snapshots;
    private final InFlightRequests inFlight;
    private final MujoongClock clock;
    private final Duration grace;
    private volatile boolean running;

    @Autowired
    public DrainingLifecycle(
            ReadinessState readiness,
            CoreStartupSequence startupSequence,
            EventLogger events,
            MetricSnapshotJob snapshots,
            InFlightRequests inFlight,
            MujoongClock clock,
            Environment environment) {
        this(readiness, startupSequence, events, snapshots, inFlight, clock, Binder.get(environment)
                .bind(GRACE_KEY, Duration.class)
                .orElseThrow(() -> new IllegalStateException("필수 설정 키가 없다: " + GRACE_KEY)));
    }

    public DrainingLifecycle(
            ReadinessState readiness,
            CoreStartupSequence startupSequence,
            EventLogger events,
            MetricSnapshotJob snapshots,
            InFlightRequests inFlight,
            MujoongClock clock,
            Duration grace) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.startupSequence = Objects.requireNonNull(startupSequence, "startupSequence");
        this.events = Objects.requireNonNull(events, "events");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        startupSequence.stopSchedulers();
        events.flushMerged();
        try {
            snapshots.finalSnapshot(remaining(started));
        } catch (RuntimeException e) {
            // 마지막 줄을 못 써도 종료는 이어 간다
            events.appError(EventLog.Component.CORE_MAINTENANCE, e);
        }
        awaitInFlight(remaining(started));
        running = false;
    }

    /** 마감까지 남은 시간(0 이상). */
    private Duration remaining(long startedNanos) {
        Duration left = grace.minus(clock.elapsedSince(startedNanos));
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** T6. 한도가 이미 지났으면 기다리지 않는다. */
    private void awaitInFlight(Duration limit) {
        try {
            if (limit.isNegative() || limit.isZero() || !inFlight.awaitIdle(limit)) {
                if (inFlight.active() > 0) {
                    events.appError(EventLog.Component.CORE_MAINTENANCE, "DrainTimeout");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
