package kr.mujoong.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Core가 올리는 지표 이름 한 곳 (U1 3.5 Step 21 · OD-5 · OD-9 · OD-10 · OD-13 · NFR4.14 · NFR4.15 · NFR9.14).
 *
 * <p>Micrometer 점 표기 이름이 Prometheus에서 {@code mujoong_..._total} · {@code mujoong_..._seconds}가 된다. 라벨 값은 열거 집합의 소문자
 * 밑줄 표기만 쓴다. id · 전사 · 토큰 · 코드 · ownerHash는 라벨이 될 수 없다(NFR9.8). 레지스트리가 없는 문맥(부분 시험 문맥)에서는 메모리
 * 레지스트리를 쓴다.
 *
 * <p>3.6 수정(J322 · J323): 설계 표 2.5의 Core 몫 중 빠졌던 Outbox · Inbox 게이지 4종(OD-12 · NFR7.8 · NFR4.14)과
 * {@code mujoong_db_pool_wait_exceeded_total}(NFR7.6)을 더하고, 사건이 없으면 이름이 아예 없던 카운터(사본 폴백 · Core 시한 2종 · 풀 대기
 * 초과)를 0으로 미리 등록한다(OD-5 시험 '이름이 모두 있다'). METRIC_SNAPSHOT(OD-6)의 REST · 12 조회 처리 시간 계수는 Prometheus 이름을
 * 늘리지 않는 메모리 누계로 둔다(Prometheus 쪽은 {@code http_server_requests_seconds}가 같은 값을 SLO 버킷으로 보인다).
 *
 * <p>설계 표 2.5에서 Core가 내는 히스토그램은 {@code mujoong_event_delivery_seconds}(버킷은 application.yml SLO) ·
 * {@code mujoong_internal_http_seconds} · {@code http_server_requests_seconds}다. 세션 시작 → ACTIVE · view_item_ready · relay ·
 * turn_first_audio는 표의 발행 앱이 realtime이라 여기에 없다(J328).
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

    /** Core Outbox의 방향 라벨(설계 표 2.5 direction). */
    public static final String OUTBOX_DIRECTION = "core_to_realtime";
    /** Core Inbox의 방향 라벨(들어오는 방향). */
    public static final String INBOX_DIRECTION = "realtime_to_core";
    /** {@code mujoong_db_pool_wait_exceeded_total}의 Micrometer 이름. */
    public static final String DB_POOL_WAIT_EXCEEDED = "mujoong.db.pool.wait.exceeded";
    /** {@code mujoong_session_view_fallback_total}의 Micrometer 이름. */
    public static final String SESSION_VIEW_FALLBACK = "mujoong.session.view.fallback";
    /**
     * METRIC_SNAPSHOT {@code restLe300Ms}의 경계(OD-6 · NFR5.5). 값은 키 이름이 고정한 계약 상수라 설정 키로 두지 않는다(J324, 시간 상수 검사의
     * 허용 목록).
     */
    public static final Duration REST_FAST = Duration.ofMillis(300);
    /** METRIC_SNAPSHOT {@code viewLe500Ms}의 경계(OD-6 · NFR5.5, J324 허용 목록). */
    public static final Duration VIEW_FAST = Duration.ofMillis(500);

    private final MeterRegistry registry;
    private final AtomicInteger breakerState = new AtomicInteger(BreakerGauge.CLOSED.value());
    private final AtomicInteger readinessUp = new AtomicInteger(0);
    // 게이지는 상태 객체를 약하게 참조하므로 필드로 붙잡아 둔다(지역 객체면 GC 뒤 NaN이 된다)
    private final AtomicInteger livenessUp = new AtomicInteger(1);
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestPendingSeconds = new AtomicLong();
    private final AtomicLong outboxFailed = new AtomicLong();
    private final AtomicLong inboxDead = new AtomicLong();
    private final LongAdder restTotal = new LongAdder();
    private final LongAdder restFast = new LongAdder();
    private final LongAdder viewTotal = new LongAdder();
    private final LongAdder viewFast = new LongAdder();

    @Autowired
    public CoreMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    public CoreMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        registry.gauge("mujoong.internal.http.breaker.state", Tags.of("group", "core_to_realtime"), breakerState);
        registry.gauge("mujoong.health.up", Tags.of("group", "readiness"), readinessUp);
        registry.gauge("mujoong.health.up", Tags.of("group", "liveness"), livenessUp);
        // OD-12 · NFR7.8 · NFR4.14: 값은 maintenance 표본이 갱신한다(MetricSnapshotJob)
        registry.gauge("mujoong.outbox.pending", Tags.of("direction", OUTBOX_DIRECTION), outboxPending);
        registry.gauge("mujoong.outbox.oldest.pending.seconds", Tags.of("direction", OUTBOX_DIRECTION),
                outboxOldestPendingSeconds);
        registry.gauge("mujoong.outbox.failed", Tags.of("direction", OUTBOX_DIRECTION), outboxFailed);
        registry.gauge("mujoong.inbox.dead", Tags.of("direction", INBOX_DIRECTION), inboxDead);
        // 사건이 없을 때도 이름이 보이게 0으로 미리 둔다(OD-5)
        registry.counter(DB_POOL_WAIT_EXCEEDED);
        registry.counter(SESSION_VIEW_FALLBACK);
        counter("mujoong.timeout.fired", "type", "preparing");
        counter("mujoong.timeout.fired", "type", "device_status_stale");
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
        registry.counter(SESSION_VIEW_FALLBACK).increment();
    }

    /** {@code mujoong_db_pool_wait_exceeded_total}: 풀 연결 획득이 {@code mujoong.metrics.db-pool-wait-warn}을 넘었다(NFR7.6). */
    public void dbPoolWaitExceeded() {
        registry.counter(DB_POOL_WAIT_EXCEEDED).increment();
    }

    /**
     * Outbox · Inbox 적체 게이지를 바꾼다(OD-12): PENDING 수, 가장 오래된 PENDING의 나이(초, 없으면 0), FAILED 수, Inbox DEAD 수. 모두 시점
     * 값이다.
     */
    public void messagingBacklog(long pending, long oldestPendingSeconds, long failed, long dead) {
        outboxPending.set(nonNegative(pending));
        outboxOldestPendingSeconds.set(nonNegative(oldestPendingSeconds));
        outboxFailed.set(nonNegative(failed));
        inboxDead.set(nonNegative(dead));
    }

    /** 보호자 · 기기 REST 요청 하나의 서버 처리 시간(12 조회 · 헬스 · 내부 호출 제외, OD-6 restTotal · restLe300Ms). */
    public void restRequest(Duration took) {
        restTotal.increment();
        if (!took.isNegative() && took.compareTo(REST_FAST) <= 0) {
            restFast.increment();
        }
    }

    /** 12 진행 조회 요청 하나의 서버 처리 시간(Realtime 중계 포함, OD-6 viewTotal · viewLe500Ms). */
    public void viewRequest(Duration took) {
        viewTotal.increment();
        if (!took.isNegative() && took.compareTo(VIEW_FAST) <= 0) {
            viewFast.increment();
        }
    }

    /** METRIC_SNAPSHOT이 구간 증가분을 셈하는 누계(앱 시작 뒤 단조 증가). */
    public RequestTotals requestTotals() {
        return new RequestTotals(restTotal.sum(), restFast.sum(), viewTotal.sum(), viewFast.sum());
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

    /** 게이지 현재 값(시험 · 진단). 없으면 NaN. */
    public double gauge(String name, String... tags) {
        io.micrometer.core.instrument.Gauge gauge = registry.find(name).tags(tags).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }

    private static String label(String value) {
        return value == null ? "none" : value.toLowerCase(Locale.ROOT);
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    /** REST · 12 조회 처리 시간 누계(빠른 것 = 경계 이하). */
    public record RequestTotals(long rest, long restFast, long view, long viewFast) {
    }
}
