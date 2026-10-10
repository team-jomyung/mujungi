package kr.mujoong.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Core가 올리는 지표 이름 한 곳 (U1 3.5 Step 21 · OD-5 · OD-9 · OD-10 · OD-13 · NFR4.14 · NFR4.15 · NFR9.14).
 *
 * <p>Micrometer 점 표기 이름이 Prometheus에서 {@code mujoong_..._total} · {@code mujoong_..._seconds}가 된다. 라벨 값은 열거 집합의 소문자
 * 밑줄 표기만 쓴다. id · 전사 · 토큰 · 코드 · ownerHash는 라벨이 될 수 없다(NFR9.8). 레지스트리가 없는 문맥(부분 시험 문맥)에서는 메모리
 * 레지스트리를 쓴다.
 */
@Component
public class CoreMetrics {

    /** 회로 차단기 상태 게이지 값(0 닫힘 · 1 반열림 · 2 열림). */
    public enum BreakerGauge {
        CLOSED(0),
        HALF_OPEN(1),
        OPEN(2);

        private final int value;

        BreakerGauge(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    private final MeterRegistry registry;
    private final AtomicInteger breakerState = new AtomicInteger(BreakerGauge.CLOSED.value());
    private final AtomicInteger readinessUp = new AtomicInteger(0);

    @Autowired
    public CoreMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    public CoreMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        registry.gauge("mujoong.internal.http.breaker.state",
                io.micrometer.core.instrument.Tags.of("group", "core_to_realtime"), breakerState);
        registry.gauge("mujoong.health.up", io.micrometer.core.instrument.Tags.of("group", "readiness"), readinessUp);
        registry.gauge("mujoong.health.up", io.micrometer.core.instrument.Tags.of("group", "liveness"), new AtomicInteger(1));
    }

    /** 시험용 메모리 레지스트리 지표. */
    public static CoreMetrics inMemory() {
        return new CoreMetrics(new SimpleMeterRegistry());
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** {@code mujoong_security_event_total{type}}(OD-13): type은 사건 코드 이름에서 SECURITY_를 뗀 소문자다. */
    public void securityEvent(String eventCodeName) {
        String type = eventCodeName.startsWith("SECURITY_") ? eventCodeName.substring("SECURITY_".length()) : eventCodeName;
        counter("mujoong.security.event", "type", label(type)).increment();
    }

    /** {@code mujoong_timeout_fired_total{type}}(OD-10): 전이를 이긴 시한 · stale 첫 판정만 센다. */
    public void timeoutFired(String type) {
        counter("mujoong.timeout.fired", "type", label(type)).increment();
    }

    /**
     * {@code mujoong_session_ended_total}(OD-9): Core 로컬 FAILED는 origin core_local이고 status failed · end_reason failed ·
     * failure_code none · phase start다.
     */
    public void sessionEnded(String status, String endReason, String failureCode, String phase, String origin) {
        registry.counter("mujoong.session.ended",
                "status", label(status),
                "end_reason", label(endReason),
                "failure_code", failureCode == null ? "none" : label(failureCode),
                "phase", label(phase),
                "origin", label(origin)).increment();
    }

    /** {@code mujoong_internal_http_requests_total{target,outcome}} · {@code mujoong_internal_http_seconds{target}}(OD-5). */
    public void internalHttp(String target, String outcome, Duration duration) {
        counter("mujoong.internal.http.requests", "target", label(target), "outcome", label(outcome)).increment();
        if (duration != null) {
            Timer.builder("mujoong.internal.http").tag("target", label(target)).register(registry).record(duration);
        }
    }

    /** 회로 차단기 상태 게이지(session_start 차단기 하나, R3-2). */
    public void breakerState(BreakerGauge state) {
        breakerState.set(Objects.requireNonNull(state, "state").value());
    }

    /** 현재 회로 차단기 게이지 값. */
    public int breakerStateValue() {
        return breakerState.get();
    }

    /** {@code mujoong_session_view_fallback_total}: 12 조회가 사본으로 답한 횟수. */
    public void sessionViewFallback() {
        registry.counter("mujoong.session.view.fallback").increment();
    }

    /** {@code mujoong_rate_limiter_evictions_total{limiter}}. */
    public void rateLimiterEviction(String limiter) {
        counter("mujoong.rate.limiter.evictions", "limiter", label(limiter)).increment();
    }

    /** {@code mujoong_rate_limiter_errors_total{limiter}}: 한도기 내부 오류로 요청을 통과시킨 수. */
    public void rateLimiterError(String limiter) {
        counter("mujoong.rate.limiter.errors", "limiter", label(limiter)).increment();
    }

    /** {@code mujoong_log_invalid_attr_total{event}}(OD-3 1번). */
    public void invalidLogAttr(String eventCodeName) {
        counter("mujoong.log.invalid.attr", "event", label(eventCodeName)).increment();
    }

    /** {@code mujoong_event_delivery_seconds{event_type}}: 받은 이벤트의 occurredAt → PROCESSED. */
    public void eventDelivered(String eventType, Duration delay) {
        if (delay == null || delay.isNegative()) {
            return;
        }
        Timer.builder("mujoong.event.delivery").tag("event_type", eventType == null ? "unknown" : eventType)
                .register(registry).record(delay);
    }

    /** {@code mujoong_health_up{group="readiness"}}. */
    public void readinessUp(boolean up) {
        readinessUp.set(up ? 1 : 0);
    }

    /** 카운터 현재 값(시험 · 진단). 없으면 0. */
    public double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }

    private static String label(String value) {
        return value == null ? "none" : value.toLowerCase(Locale.ROOT);
    }
}
