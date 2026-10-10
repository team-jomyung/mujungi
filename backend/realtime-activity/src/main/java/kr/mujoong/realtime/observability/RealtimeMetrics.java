package kr.mujoong.realtime.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
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
 * Realtime이 올리는 지표 이름 한 곳 (U1 3.5 Step 26 · OD-5 · OD-9 · OD-10 · OD-13 · PD-4 · NFR4.14 · NFR4.15 · NFR9.14).
 *
 * <p>Micrometer 점 표기 이름이 Prometheus에서 {@code mujoong_..._total} · {@code mujoong_..._seconds}가 된다. 라벨 값은 열거 집합의 소문자
 * 밑줄 표기만 쓴다. id · 전사 · 토큰 · ownerHash는 라벨이 될 수 없다(NFR9.8). 레지스트리가 없는 문맥(부분 시험 문맥)에서는 메모리
 * 레지스트리를 쓴다. Core에도 같은 이름 규칙의 클래스가 따로 있다(LC-11).
 */
@Component
public class RealtimeMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger readinessUp = new AtomicInteger(0);
    private final AtomicInteger openConnections = new AtomicInteger(0);
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    @Autowired
    public RealtimeMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    public RealtimeMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        registry.gauge("mujoong.health.up", Tags.of("group", "readiness"), readinessUp);
        registry.gauge("mujoong.health.up", Tags.of("group", "liveness"), new AtomicInteger(1));
        registry.gauge("mujoong.ws.connections.open", openConnections);
        registry.gauge("mujoong.sessions.active", activeSessions);
    }

    /** 시험용 메모리 레지스트리 지표. */
    public static RealtimeMetrics inMemory() {
        return new RealtimeMetrics(new SimpleMeterRegistry());
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** {@code mujoong_security_event_total{type}}(OD-13): type은 사건 코드 이름에서 SECURITY_를 뗀 소문자다. */
    public void securityEvent(String eventCodeName) {
        String type = eventCodeName.startsWith("SECURITY_") ? eventCodeName.substring("SECURITY_".length()) : eventCodeName;
        counter("mujoong.security.event", "type", label(type)).increment();
    }

    /** {@code mujoong_timeout_fired_total{type}}(OD-10): 전이를 이긴 시한만 센다. */
    public void timeoutFired(String type) {
        counter("mujoong.timeout.fired", "type", label(type)).increment();
    }

    /** {@code mujoong_session_ended_total}(OD-9): 종료 상태 전이를 이긴 쪽만 센다. */
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

    /** {@code mujoong_gate_discard_total{reason}}(SD-17 · PD-3): 폐기 사유 열거값만. 전사 · 일치 문구는 쓰지 않는다. */
    public void gateDiscard(String reason) {
        counter("mujoong.gate.discard", "reason", label(reason)).increment();
    }

    /** {@code mujoong_input_frames_dropped_total{cause}}(PD-4): 대기열 넘침 · ACTIVE 밖 프레임. */
    public void inputFramesDropped(String cause, long count) {
        if (count > 0) {
            counter("mujoong.input.frames.dropped", "cause", label(cause)).increment(count);
        }
    }

    /** {@code mujoong_recorder_rejected_total}(PD-5): 기록기 상한에서 받지 못한 새 칸. */
    public void recorderRejected() {
        registry.counter("mujoong.recorder.rejected").increment();
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

    /** {@code mujoong_relay_in_seconds}: 입력 프레임 수신 → 공급자 전송 완료(NFR5.1, 표본 분포). */
    public void relayIn(Duration delay) {
        if (delay != null && !delay.isNegative()) {
            Timer.builder("mujoong.relay.in").register(registry).record(delay);
        }
    }

    /** {@code mujoong_health_up{group="readiness"}}. */
    public void readinessUp(boolean up) {
        readinessUp.set(up ? 1 : 0);
    }

    /** 열린 기기 WSS 수(게이지). */
    public void connectionOpened() {
        openConnections.incrementAndGet();
    }

    /** 열린 기기 WSS 수(게이지). */
    public void connectionClosed() {
        openConnections.updateAndGet(value -> Math.max(0, value - 1));
    }

    /** 진행 중 세션 실행기 수(게이지). */
    public void sessionStarted() {
        activeSessions.incrementAndGet();
    }

    /** 진행 중 세션 실행기 수(게이지). */
    public void sessionFinished() {
        activeSessions.updateAndGet(value -> Math.max(0, value - 1));
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
