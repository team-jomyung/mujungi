package kr.mujoong.core.startup;

import java.util.Objects;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Core 정상 종료 T1 · T4 · T5 (U1 3.5 Step 21 · RD-13 · J125 · R3-13 · NFR4.10).
 *
 * <p>가장 높은 phase의 SmartLifecycle이라 SIGTERM 뒤 가장 먼저 멈춘다(웹 서버 graceful 단계 MAX − 1024보다 앞). T1 준비 상태를
 * DRAINING으로 내린다(새 REST · /internal 요청은 필터 F1b가 503 SERVICE_NOT_READY + Retry-After, Step 31). T4 스케줄러를 멈춘다(발송
 * 중인 한 건 · 처리 중인 처리기는 마치고, 남은 Outbox 선점은 available_at = now로 되돌린다). T5 병합 창에 남은 반복 로그를 쓴다. 그 뒤
 * 웹 서버 graceful 단계가 진행 중 요청을 {@code spring.lifecycle.timeout-per-shutdown-phase}(30초) 안에서 마친다. 진행 중 세션은 닫지
 * 않는다(Core 사본은 다음 이벤트가 덮는다).
 */
@Component
public class DrainingLifecycle implements SmartLifecycle {

    private final ReadinessState readiness;
    private final CoreStartupSequence startupSequence;
    private final EventLogger events;
    private volatile boolean running;

    public DrainingLifecycle(ReadinessState readiness, CoreStartupSequence startupSequence, EventLogger events) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.startupSequence = Objects.requireNonNull(startupSequence, "startupSequence");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        readiness.markDraining();
        startupSequence.stopSchedulers();
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
