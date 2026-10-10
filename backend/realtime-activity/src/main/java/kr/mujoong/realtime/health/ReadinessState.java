package kr.mujoong.realtime.health;

import java.time.Duration;
import java.util.Objects;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Realtime 준비 상태 (U1 3.5 Step 26 · RD-16 · OD-11 · 편차 (n) · J124).
 *
 * <p>STARTING(S1 ~ S5) → READY(S6) ↔ NOT_READY(DB 점검 연속 {@code mujoong.health.db-failure-threshold}회 실패, 성공 1회로 복귀) →
 * DRAINING(SIGTERM, 되돌아오지 않음). 요청마다 DB를 부르지 않고 점검자가 갱신하는 값 하나만 읽는다. 상태가 바뀌면 Spring
 * {@link org.springframework.boot.availability.ReadinessState}(actuator readiness 프로브)와 {@code mujoong_health_up{group=readiness}}를
 * 함께 바꾼다. 거절 응답(F1b 필터 · 새 WSS 1013, Step 33)의 {@code Retry-After}는 {@link #retryAfterSeconds()}다.
 */
@Component
public class ReadinessState {

    /** 준비 상태 값. */
    public enum State {
        STARTING,
        READY,
        NOT_READY,
        DRAINING
    }

    private final ApplicationEventPublisher publisher;
    private final RealtimeMetrics metrics;
    private final int failureThreshold;
    private final long retryAfterSeconds;

    private State state = State.STARTING;
    private int consecutiveFailures;

    @Autowired
    public ReadinessState(ApplicationEventPublisher publisher, RealtimeMetrics metrics, RealtimeProperties properties) {
        this(publisher, metrics, properties.health().dbFailureThreshold(), properties.health().dbCheckInterval());
    }

    public ReadinessState(
            ApplicationEventPublisher publisher, RealtimeMetrics metrics, int failureThreshold, Duration dbCheckInterval) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold는 0보다 커야 한다");
        }
        this.failureThreshold = failureThreshold;
        // Retry-After는 초 단위 정수이고 1 이상(J124: db-check-interval, 시험의 200ms도 1초로 올림)
        this.retryAfterSeconds = Math.max(1L, (Objects.requireNonNull(dbCheckInterval, "dbCheckInterval").toMillis() + 999) / 1000);
        metrics.readinessUp(false);
    }

    /** 지금 상태. */
    public synchronized State state() {
        return state;
    }

    /** 요청을 받을 수 있는가(READY일 때만). */
    public synchronized boolean isReady() {
        return state == State.READY;
    }

    /** 503 응답의 Retry-After 초(NOT_READY · DRAINING 공통, J124). */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** S6: 시작 복구가 끝났다. DRAINING이면 무시한다. */
    public void markReady() {
        transition(State.READY, State.STARTING, State.NOT_READY, State.READY);
    }

    /** T1: 정상 종료에 들어간다(되돌아오지 않는다). */
    public void markDraining() {
        State previous;
        synchronized (this) {
            previous = state;
            state = State.DRAINING;
        }
        if (previous != State.DRAINING) {
            publish(State.DRAINING);
        }
    }

    /**
     * DB 점검 결과를 반영한다. 연속 실패가 한도에 닿으면 READY → NOT_READY, NOT_READY에서 성공 1회면 READY. STARTING · DRAINING은
     * 점검으로 바뀌지 않는다.
     *
     * @return 반영 뒤 상태
     */
    public State recordDbCheck(boolean success) {
        State changedTo = null;
        State current;
        synchronized (this) {
            if (success) {
                consecutiveFailures = 0;
                if (state == State.NOT_READY) {
                    state = State.READY;
                    changedTo = State.READY;
                }
            } else {
                consecutiveFailures++;
                if (state == State.READY && consecutiveFailures >= failureThreshold) {
                    state = State.NOT_READY;
                    changedTo = State.NOT_READY;
                }
            }
            current = state;
        }
        if (changedTo != null) {
            publish(changedTo);
        }
        return current;
    }

    /** 지금 연속 실패 수(시험 · 진단). */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    private void transition(State target, State... allowedFrom) {
        boolean changed = false;
        synchronized (this) {
            for (State from : allowedFrom) {
                if (state == from) {
                    changed = state != target;
                    state = target;
                    consecutiveFailures = 0;
                    break;
                }
            }
        }
        if (changed) {
            publish(target);
        }
    }

    private void publish(State next) {
        boolean up = next == State.READY;
        metrics.readinessUp(up);
        AvailabilityChangeEvent.publish(publisher, this, up
                ? org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC
                : org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC);
    }
}
