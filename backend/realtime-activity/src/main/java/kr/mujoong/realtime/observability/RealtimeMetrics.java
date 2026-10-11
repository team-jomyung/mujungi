package kr.mujoong.realtime.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kr.mujoong.contract.status.ConnectionState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Realtime이 올리는 지표 이름 한 곳 (U1 3.5 Step 26 · 3.6 수정 · OD-5 · OD-9 · OD-10 · OD-12 · OD-13 · PD-4 · NFR4.14 · NFR4.15 ·
 * NFR7.8 · NFR9.14).
 *
 * <p>Micrometer 점 표기 이름이 Prometheus에서 {@code mujoong_..._total} · {@code mujoong_..._seconds}가 된다. 라벨 값은 열거 집합의 소문자
 * 밑줄 표기만 쓴다. id · 전사 · 토큰 · ownerHash는 라벨이 될 수 없다(NFR9.8). 레지스트리가 없는 문맥(부분 시험 문맥)에서는 메모리
 * 레지스트리를 쓴다. Core에도 같은 이름 규칙의 클래스가 따로 있다(LC-11).
 *
 * <p>3.6 수정(J295 ~ J298): 설계 표 2.5의 Realtime 몫 중 빠졌던 지표를 더한다 — 열린 기기 연결 수는 설계 이름
 * {@code mujoong_device_connections_open}(전 이름 {@code mujoong_ws_connections_open}은 버린다), 기기 연결 상태 분포 · Outbox · Inbox 적체 ·
 * 대기열 깊이 게이지, 풀 대기 초과 · 세션 활성 카운터, 시작 → ACTIVE · relay_out · 첫 소리 · 12 행 준비 히스토그램. 사건이 없을 때도 이름이
 * 보이게 카운터 · 히스토그램을 0으로 미리 등록한다(OD-5 '이름이 모두 있다'). 히스토그램 버킷(설계 표의 초 값)은 코드가 아니라
 * {@code application.yml}의 {@code management.metrics.distribution.slo}에 둔다(시간 숫자를 코드에 두지 않는다, NFR6.1).
 */
@Component
public class RealtimeMetrics {

    /** Realtime Outbox의 방향 라벨(설계 표 2.5 direction). */
    public static final String OUTBOX_DIRECTION = "realtime_to_core";
    /** Realtime Inbox의 방향 라벨(들어오는 방향). */
    public static final String INBOX_DIRECTION = "core_to_realtime";

    /** {@code mujoong_device_connections_open}: 열린 기기 WSS 수(소켓 레지스트리 크기). */
    public static final String DEVICE_CONNECTIONS_OPEN = "mujoong.device.connections.open";
    /** {@code mujoong_device_connection_state{state}}: 연결 원장의 상태별 기기 수. */
    public static final String DEVICE_CONNECTION_STATE = "mujoong.device.connection.state";
    /** {@code mujoong_sessions_active}: 진행 중 세션 실행기 수. */
    public static final String SESSIONS_ACTIVE = "mujoong.sessions.active";
    /** {@code mujoong_queue_depth{queue}}. */
    public static final String QUEUE_DEPTH = "mujoong.queue.depth";
    /** {@code mujoong_db_pool_wait_exceeded_total}. */
    public static final String DB_POOL_WAIT_EXCEEDED = "mujoong.db.pool.wait.exceeded";
    /** {@code mujoong_session_activated_total}: 시작 성공률 분자. */
    public static final String SESSION_ACTIVATED = "mujoong.session.activated";
    /** {@code mujoong_session_start_to_active_seconds}. */
    public static final String SESSION_START_TO_ACTIVE = "mujoong.session.start.to.active";
    /** {@code mujoong_relay_in_seconds}. */
    public static final String RELAY_IN = "mujoong.relay.in";
    /** {@code mujoong_relay_out_seconds}. */
    public static final String RELAY_OUT = "mujoong.relay.out";
    /** {@code mujoong_turn_first_audio_seconds}. */
    public static final String TURN_FIRST_AUDIO = "mujoong.turn.first.audio";
    /** {@code mujoong_view_item_ready_seconds{speaker}}. */
    public static final String VIEW_ITEM_READY = "mujoong.view.item.ready";
    /** {@code mujoong_timeout_fired_total{type}}. */
    public static final String TIMEOUT_FIRED = "mujoong.timeout.fired";
    /** {@code mujoong_recorder_rejected_total}. */
    public static final String RECORDER_REJECTED = "mujoong.recorder.rejected";

    /** {@code mujoong_device_connection_state}의 state 값(U1이 쓰는 4종. UNSTABLE은 U3가 값을 더한다). */
    public static final List<ConnectionState> CONNECTION_STATES = List.of(ConnectionState.OFFLINE,
            ConnectionState.CONNECTED, ConnectionState.DISCONNECTED, ConnectionState.UNRESPONSIVE);

    /** Realtime이 이기는 시한 종류({@code mujoong_timeout_fired_total{type}}, 나머지 둘은 Core 몫). */
    static final List<String> REALTIME_TIMEOUT_TYPES = List.of("device_ack", "closing_confirm", "disconnect");

    /** {@code mujoong_view_item_ready_seconds}의 speaker 값. */
    static final List<String> SPEAKERS = List.of("child", "ai");

    /** {@code mujoong_queue_depth}의 queue 값(설계 표 2.5). */
    public enum Queue {
        SESSION_MAILBOX,
        OUTPUT_AUDIO,
        OUTPUT_CONTROL,
        RECORDER
    }

    /** 대기열 깊이 원천. 긁을 때마다 읽는다(메모리 값이라 표본 주기를 기다리지 않는다). */
    @FunctionalInterface
    public interface QueueDepthSource {

        /** 앱 전체(모든 세션 · 연결의 합) 깊이. */
        long depth(Queue queue);
    }

    private final MeterRegistry registry;
    // 게이지는 상태 객체를 약하게 참조하므로 필드로 붙잡아 둔다(지역 객체면 GC 뒤 NaN이 된다 — 예전 liveness가 그래서 3.6 긁기에서 NaN, J309)
    private final AtomicInteger readinessUp = new AtomicInteger(0);
    private final AtomicInteger livenessUp = new AtomicInteger(1);
    private final AtomicInteger openConnections = new AtomicInteger(0);
    private final AtomicInteger activeSessions = new AtomicInteger(0);
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestPendingSeconds = new AtomicLong();
    private final AtomicLong outboxFailed = new AtomicLong();
    private final AtomicLong inboxDead = new AtomicLong();
    private final Map<ConnectionState, AtomicLong> connectionStates = new EnumMap<>(ConnectionState.class);
    private volatile QueueDepthSource queueDepths = queue -> 0L;

    @Autowired
    public RealtimeMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    public RealtimeMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        registry.gauge("mujoong.health.up", Tags.of("group", "readiness"), readinessUp);
        registry.gauge("mujoong.health.up", Tags.of("group", "liveness"), livenessUp);
        registry.gauge(DEVICE_CONNECTIONS_OPEN, openConnections);
        registry.gauge(SESSIONS_ACTIVE, activeSessions);
        for (ConnectionState state : CONNECTION_STATES) {
            AtomicLong count = new AtomicLong();
            connectionStates.put(state, count);
            registry.gauge(DEVICE_CONNECTION_STATE, Tags.of("state", label(state.name())), count);
        }
        // OD-12 · NFR7.8 · NFR4.14: 값은 maintenance 표본이 갱신한다(MetricSnapshotJob)
        registry.gauge("mujoong.outbox.pending", Tags.of("direction", OUTBOX_DIRECTION), outboxPending);
        registry.gauge("mujoong.outbox.oldest.pending.seconds", Tags.of("direction", OUTBOX_DIRECTION),
                outboxOldestPendingSeconds);
        registry.gauge("mujoong.outbox.failed", Tags.of("direction", OUTBOX_DIRECTION), outboxFailed);
        registry.gauge("mujoong.inbox.dead", Tags.of("direction", INBOX_DIRECTION), inboxDead);
        for (Queue queue : Queue.values()) {
            Gauge.builder(QUEUE_DEPTH, this, metrics -> metrics.queueDepths.depth(queue))
                    .tag("queue", label(queue.name())).strongReference(true).register(registry);
        }
        // 사건이 없을 때도 이름이 보이게 0으로 미리 둔다(OD-5)
        registry.counter(SESSION_ACTIVATED);
        registry.counter(DB_POOL_WAIT_EXCEEDED);
        registry.counter(RECORDER_REJECTED);
        for (String type : REALTIME_TIMEOUT_TYPES) {
            counter(TIMEOUT_FIRED, "type", type);
        }
        timer(SESSION_START_TO_ACTIVE);
        timer(RELAY_IN);
        timer(RELAY_OUT);
        timer(TURN_FIRST_AUDIO);
        for (String speaker : SPEAKERS) {
            timer(VIEW_ITEM_READY, "speaker", speaker);
        }
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
        counter(TIMEOUT_FIRED, "type", label(type)).increment();
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

    /**
     * ACTIVE 전이를 이긴 쪽이 부른다: {@code mujoong_session_activated_total} + 1, {@code mujoong_session_start_to_active_seconds}에 시작
     * 명령을 받은 시각(PREPARING) → ACTIVE 길이를 넣는다(OD-5 · NFR4.14). 길이를 모르면(null · 음수) 카운터만 센다.
     */
    public void sessionActivated(Duration startToActive) {
        registry.counter(SESSION_ACTIVATED).increment();
        record(timer(SESSION_START_TO_ACTIVE), startToActive);
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
        registry.counter(RECORDER_REJECTED).increment();
    }

    /** {@code mujoong_db_pool_wait_exceeded_total}: 풀 연결 획득이 {@code mujoong.metrics.db-pool-wait-warn}을 넘었다(NFR7.6). */
    public void dbPoolWaitExceeded() {
        registry.counter(DB_POOL_WAIT_EXCEEDED).increment();
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
        record(timer(RELAY_IN), delay);
    }

    /** {@code mujoong_relay_out_seconds}: 턴의 구간 확정 → 기기 첫 0x02 프레임 송신(NFR5.1 · OD-8 relayOutMs). */
    public void relayOut(Duration delay) {
        record(timer(RELAY_OUT), delay);
    }

    /** {@code mujoong_turn_first_audio_seconds}: 말 끝(t0) → 기기 첫 소리(t3, 서버 시계로 환산)(NFR5.2 · OD-8). */
    public void turnFirstAudio(Duration delay) {
        record(timer(TURN_FIRST_AUDIO), delay);
    }

    /** {@code mujoong_view_item_ready_seconds{speaker}}: 행 확정(tTranscriptFinal) → 12에 실릴 수 있게 커밋(tViewItemReady)(NFR5.3). */
    public void viewItemReady(String speaker, Duration delay) {
        record(timer(VIEW_ITEM_READY, "speaker", label(speaker)), delay);
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

    /** 지금 열린 기기 WSS 수(METRIC_SNAPSHOT openConnections). */
    public int openConnections() {
        return openConnections.get();
    }

    /** 진행 중 세션 실행기 수(게이지). */
    public void sessionStarted() {
        activeSessions.incrementAndGet();
    }

    /** 진행 중 세션 실행기 수(게이지). */
    public void sessionFinished() {
        activeSessions.updateAndGet(value -> Math.max(0, value - 1));
    }

    /** 지금 진행 중 세션 수(METRIC_SNAPSHOT sessionsActive). */
    public int sessionsActive() {
        return activeSessions.get();
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

    /** 연결 원장의 상태별 기기 수를 바꾼다(시점 값). 표에 없는 상태는 0이다. */
    public void connectionStates(Map<ConnectionState, Long> counts) {
        Objects.requireNonNull(counts, "counts");
        connectionStates.forEach((state, gauge) -> gauge.set(nonNegative(counts.getOrDefault(state, 0L))));
    }

    /** 대기열 깊이 원천을 묶는다(앱 문맥에서 한 번). */
    public void queueDepthSource(QueueDepthSource source) {
        this.queueDepths = Objects.requireNonNull(source, "source");
    }

    /** 카운터 현재 값(시험 · 진단). 없으면 0. */
    public double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 게이지 현재 값(시험 · 진단). 없으면 NaN. */
    public double gauge(String name, String... tags) {
        Gauge gauge = registry.find(name).tags(tags).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    /** 히스토그램(타이머)에 넣은 값 수(시험 · 진단). 없으면 0. */
    public long recorded(String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0L : timer.count();
    }

    private Counter counter(String name, String... tags) {
        return registry.counter(name, tags);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(registry);
    }

    private static void record(Timer timer, Duration value) {
        if (value != null && !value.isNegative()) {
            timer.record(value);
        }
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static String label(String value) {
        return value == null ? "none" : value.toLowerCase(Locale.ROOT);
    }
}
