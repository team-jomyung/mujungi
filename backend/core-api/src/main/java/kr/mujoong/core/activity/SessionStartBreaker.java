package kr.mujoong.core.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.CoreMetrics.BreakerGauge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * session_start 호출 하나에만 거는 회로 차단기 (U1 3.5 Step 22 · RD-14 · R3-2 · J74 · NFR4.12 · OD-5).
 *
 * <p>CLOSED: 정상 호출, 성공하면 연속 실패 0. 연속 {@code breaker-failure-threshold}(3)회 실패(시간 초과 · 연결 오류 · 5xx 등 Realtime이
 * 업무 응답을 주지 못한 경우, 409 · 423은 성공으로 센다)면 OPEN. OPEN: {@code breaker-open-duration}(5초, 앱 Clock) 동안 호출 없이 거절.
 * 시간이 지나면 HALF_OPEN이 되어 시험 호출 1건만 통과시키고 나머지는 거절한다. 시험 성공 → CLOSED, 실패 → OPEN(다시 5초). 실패 한 건은
 * 논리적 시작 호출 하나(시간 초과 1회 재시도 포함)다. 끝내기 · 취소 · 조회는 이 차단기를 거치지 않는다. 상태는
 * {@code mujoong_internal_http_breaker_state{group=core_to_realtime}}(0 · 1 · 2)로 보인다.
 */
@Component
public class SessionStartBreaker {

    /** 차단기 상태. */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /** 호출 허가. */
    public enum Permit {
        /** 평소 호출. */
        ALLOWED,
        /** 반열림 시험 호출(결과를 반드시 알려야 한다). */
        TRIAL,
        /** 거절(호출하지 않는다). */
        REJECTED
    }

    private final MujoongClock clock;
    private final CoreMetrics metrics;
    private final int failureThreshold;
    private final Duration openDuration;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean trialInFlight;

    @Autowired
    public SessionStartBreaker(MujoongClock clock, CoreMetrics metrics, CoreProperties properties) {
        this(clock, metrics, properties.internalHttp().breakerFailureThreshold(),
                properties.internalHttp().breakerOpenDuration());
    }

    public SessionStartBreaker(MujoongClock clock, CoreMetrics metrics, int failureThreshold, Duration openDuration) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold는 0보다 커야 한다");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = Objects.requireNonNull(openDuration, "openDuration");
        metrics.breakerState(BreakerGauge.CLOSED);
    }

    /** 호출 전에 허가를 받는다. */
    public synchronized Permit acquire() {
        if (state == State.OPEN && !clock.now().isBefore(openedAt.plus(openDuration))) {
            moveTo(State.HALF_OPEN);
        }
        return switch (state) {
            case CLOSED -> Permit.ALLOWED;
            case OPEN -> Permit.REJECTED;
            case HALF_OPEN -> {
                if (trialInFlight) {
                    yield Permit.REJECTED;
                }
                trialInFlight = true;
                yield Permit.TRIAL;
            }
        };
    }

    /** 업무 응답(202 · 409 · 423 등)을 받았다. */
    public synchronized void onSuccess(Permit permit) {
        if (permit == Permit.REJECTED) {
            return;
        }
        consecutiveFailures = 0;
        if (permit == Permit.TRIAL) {
            trialInFlight = false;
            moveTo(State.CLOSED);
        }
    }

    /** 응답을 받지 못했거나 5xx 등 Realtime이 처리하지 못했다. */
    public synchronized void onFailure(Permit permit) {
        if (permit == Permit.REJECTED) {
            return;
        }
        if (permit == Permit.TRIAL) {
            trialInFlight = false;
            open();
            return;
        }
        consecutiveFailures++;
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    /** 지금 상태(열림 시간이 지났으면 반열림으로 본다). */
    public synchronized State state() {
        if (state == State.OPEN && !clock.now().isBefore(openedAt.plus(openDuration))) {
            moveTo(State.HALF_OPEN);
        }
        return state;
    }

    private void open() {
        openedAt = clock.now();
        consecutiveFailures = 0;
        moveTo(State.OPEN);
    }

    private void moveTo(State next) {
        state = next;
        metrics.breakerState(switch (next) {
            case CLOSED -> BreakerGauge.CLOSED;
            case HALF_OPEN -> BreakerGauge.HALF_OPEN;
            case OPEN -> BreakerGauge.OPEN;
        });
    }
}
