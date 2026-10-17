package kr.mujoong.realtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.realtime.observability.RealtimeMetrics.Queue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Realtime 지표 이름 · 종류 · 라벨 (U1 3.6 수정 · OD-5 · OD-12 · NFR4.15 · NFR7.8 · BT-NFR4.15 · BT-NFR7.6 · BT-NFR7.8 · J295 ~ J299).
 *
 * <p>설계 표 2.5의 Realtime 몫 이름이 사건이 없어도 있고(종류 · 라벨 값이 표대로), 각 지표가 원천 값으로 실제로 움직이며, 라벨에 id ·
 * 전사 같은 값이 없는지 본다. Prometheus 이름은 실제 Prometheus 레지스트리로 확인한다.
 */
@Tag("u1")
class RealtimeMetricsTest {

    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();

    @Test
    void theDesignNamesExistBeforeAnyEventWithTheTableLabels() {
        assertThat(metrics.registry().find(RealtimeMetrics.DEVICE_CONNECTIONS_OPEN).gauge()).isNotNull();
        assertThat(metrics.registry().find("mujoong.ws.connections.open").meters()).as("설계와 다른 옛 이름은 없다(J295)")
                .isEmpty();
        assertThat(labels(RealtimeMetrics.DEVICE_CONNECTION_STATE, "state"))
                .containsExactlyInAnyOrder("offline", "connected", "unstable", "disconnected", "unresponsive");
        assertThat(labels(RealtimeMetrics.QUEUE_DEPTH, "queue"))
                .containsExactlyInAnyOrder("session_mailbox", "output_audio", "output_control", "recorder");
        assertThat(labels("mujoong.outbox.pending", "direction")).containsExactly("realtime_to_core");
        assertThat(labels("mujoong.outbox.oldest.pending.seconds", "direction")).containsExactly("realtime_to_core");
        assertThat(labels("mujoong.outbox.failed", "direction")).containsExactly("realtime_to_core");
        assertThat(labels("mujoong.inbox.dead", "direction")).containsExactly("core_to_realtime");
        assertThat(labels(RealtimeMetrics.TIMEOUT_FIRED, "type"))
                .containsExactlyInAnyOrder("device_ack", "closing_confirm", "disconnect");
        assertThat(labels(RealtimeMetrics.VIEW_ITEM_READY, "speaker")).containsExactlyInAnyOrder("child", "ai");
        for (String counter : List.of(RealtimeMetrics.SESSION_ACTIVATED, RealtimeMetrics.DB_POOL_WAIT_EXCEEDED,
                RealtimeMetrics.RECORDER_REJECTED)) {
            assertThat(metrics.registry().find(counter).counter()).as(counter).isNotNull()
                    .satisfies(found -> assertThat(found.count()).isZero());
        }
        for (String histogram : List.of(RealtimeMetrics.SESSION_START_TO_ACTIVE, RealtimeMetrics.RELAY_IN,
                RealtimeMetrics.RELAY_OUT, RealtimeMetrics.TURN_FIRST_AUDIO)) {
            assertThat(metrics.registry().find(histogram).timer()).as(histogram).isNotNull()
                    .satisfies(found -> assertThat(found.count()).isZero());
        }
    }

    @Test
    void gaugesFollowTheirSourcesAndNeverGoNegative() {
        metrics.connectionOpened();
        metrics.connectionOpened();
        metrics.connectionClosed();
        metrics.connectionClosed();
        metrics.connectionClosed();
        metrics.sessionStarted();
        metrics.messagingBacklog(3, 90, 1, -2);
        Map<ConnectionState, Long> states = new EnumMap<>(ConnectionState.class);
        states.put(ConnectionState.CONNECTED, 2L);
        states.put(ConnectionState.OFFLINE, 5L);
        states.put(ConnectionState.UNSTABLE, 7L);
        metrics.connectionStates(states);

        assertThat(metrics.gauge(RealtimeMetrics.DEVICE_CONNECTIONS_OPEN)).as("닫기가 열기보다 많아도 0 아래로 가지 않는다")
                .isZero();
        assertThat(metrics.openConnections()).isZero();
        assertThat(metrics.sessionsActive()).isEqualTo(1);
        assertThat(metrics.gauge("mujoong.outbox.pending", "direction", RealtimeMetrics.OUTBOX_DIRECTION)).isEqualTo(3.0);
        assertThat(metrics.gauge("mujoong.outbox.oldest.pending.seconds")).isEqualTo(90.0);
        assertThat(metrics.gauge("mujoong.outbox.failed")).isEqualTo(1.0);
        assertThat(metrics.gauge("mujoong.inbox.dead", "direction", RealtimeMetrics.INBOX_DIRECTION)).isZero();
        assertThat(metrics.gauge(RealtimeMetrics.DEVICE_CONNECTION_STATE, "state", "connected")).isEqualTo(2.0);
        assertThat(metrics.gauge(RealtimeMetrics.DEVICE_CONNECTION_STATE, "state", "offline")).isEqualTo(5.0);
        assertThat(metrics.gauge(RealtimeMetrics.DEVICE_CONNECTION_STATE, "state", "disconnected")).as("표에 없으면 0")
                .isZero();
        // U3(BR4.8 ①)가 UNSTABLE 라벨을 더했다: 원천 값을 그대로 따른다(U1에서는 라벨이 없었다)
        assertThat(metrics.gauge(RealtimeMetrics.DEVICE_CONNECTION_STATE, "state", "unstable")).isEqualTo(7.0);
    }

    @Test
    void queueDepthGaugesReadTheBoundSourceAtScrapeTime() {
        Map<Queue, AtomicLong> depths = new EnumMap<>(Queue.class);
        for (Queue queue : Queue.values()) {
            depths.put(queue, new AtomicLong());
        }
        assertThat(metrics.gauge(RealtimeMetrics.QUEUE_DEPTH, "queue", "recorder")).as("묶기 전에는 0").isZero();

        metrics.queueDepthSource(queue -> depths.get(queue).get());
        depths.get(Queue.SESSION_MAILBOX).set(4);
        depths.get(Queue.RECORDER).set(200);

        assertThat(metrics.gauge(RealtimeMetrics.QUEUE_DEPTH, "queue", "session_mailbox")).isEqualTo(4.0);
        assertThat(metrics.gauge(RealtimeMetrics.QUEUE_DEPTH, "queue", "recorder")).isEqualTo(200.0);
        depths.get(Queue.RECORDER).set(0);
        assertThat(metrics.gauge(RealtimeMetrics.QUEUE_DEPTH, "queue", "recorder")).as("표본 주기 없이 바로 읽는다")
                .isZero();
        assertThat(metrics.gauge(RealtimeMetrics.QUEUE_DEPTH, "queue", "output_audio")).isZero();
    }

    @Test
    void countersAndHistogramsMoveAndRejectUnknownOrNegativeDurations() {
        metrics.sessionActivated(Duration.ofMillis(1500));
        metrics.sessionActivated(null);
        metrics.sessionActivated(Duration.ofMillis(-1));
        metrics.dbPoolWaitExceeded();
        metrics.timeoutFired("DEVICE_ACK");
        metrics.relayOut(Duration.ofMillis(40));
        metrics.turnFirstAudio(Duration.ofMillis(1800));
        metrics.viewItemReady("CHILD", Duration.ofMillis(120));
        metrics.viewItemReady("AI", Duration.ofMillis(1300));
        metrics.relayIn(null);

        assertThat(metrics.count(RealtimeMetrics.SESSION_ACTIVATED)).as("길이를 몰라도 활성은 센다").isEqualTo(3.0);
        assertThat(metrics.recorded(RealtimeMetrics.SESSION_START_TO_ACTIVE)).isEqualTo(1);
        assertThat(timer(RealtimeMetrics.SESSION_START_TO_ACTIVE).totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500.0);
        assertThat(metrics.count(RealtimeMetrics.DB_POOL_WAIT_EXCEEDED)).isEqualTo(1.0);
        assertThat(metrics.count(RealtimeMetrics.TIMEOUT_FIRED, "type", "device_ack")).isEqualTo(1.0);
        assertThat(metrics.recorded(RealtimeMetrics.RELAY_OUT)).isEqualTo(1);
        assertThat(metrics.recorded(RealtimeMetrics.TURN_FIRST_AUDIO)).isEqualTo(1);
        assertThat(metrics.recorded(RealtimeMetrics.VIEW_ITEM_READY, "speaker", "child")).isEqualTo(1);
        assertThat(metrics.recorded(RealtimeMetrics.VIEW_ITEM_READY, "speaker", "ai")).isEqualTo(1);
        assertThat(metrics.recorded(RealtimeMetrics.RELAY_IN)).as("null은 넣지 않는다").isZero();
    }

    @Test
    void prometheusExposesTheDesignNamesWithoutIdentifierLabels() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        RealtimeMetrics prometheus = new RealtimeMetrics(registry);
        prometheus.connectionOpened();
        prometheus.sessionActivated(Duration.ofSeconds(2));

        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("mujoong_device_connections_open 1.0")
                .contains("mujoong_device_connection_state{state=\"offline\"} 0.0")
                .contains("mujoong_outbox_pending{direction=\"realtime_to_core\"} 0.0")
                .contains("mujoong_outbox_oldest_pending_seconds{direction=\"realtime_to_core\"}")
                .contains("mujoong_outbox_failed{direction=\"realtime_to_core\"}")
                .contains("mujoong_inbox_dead{direction=\"core_to_realtime\"}")
                .contains("mujoong_queue_depth{queue=\"session_mailbox\"}")
                .contains("mujoong_db_pool_wait_exceeded_total 0.0")
                .contains("mujoong_session_activated_total 1.0")
                .contains("mujoong_session_start_to_active_seconds_count 1")
                .contains("mujoong_relay_out_seconds_count 0")
                .contains("mujoong_turn_first_audio_seconds_count 0")
                .contains("mujoong_view_item_ready_seconds_count{speaker=\"child\"} 0")
                .contains("mujoong_timeout_fired_total{type=\"closing_confirm\"} 0.0")
                .doesNotContain("mujoong_ws_connections_open");
        Set<String> tagKeys = registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("mujoong."))
                .flatMap(meter -> meter.getId().getTags().stream().map(tag -> tag.getKey()))
                .collect(Collectors.toSet());
        // U3: mujoong_device_state_total의 field · value도 닫힌 열거값이다(U3-J9)
        // U13(BR8.5): mujoong_guardrail_*의 mode(real · stub) · source · decision도 닫힌 열거값이다
        assertThat(tagKeys).as("라벨은 열거값 키만(id · 전사 · 토큰 · ownerHash 없음, NFR9.8)")
                .isSubsetOf("group", "state", "direction", "queue", "type", "speaker", "field", "value", "mode", "source",
                        "decision");
        assertThat(registry.getMeters().stream().filter(meter -> meter.getId().getName().equals(RealtimeMetrics.DEVICE_STATE))
                .map(meter -> meter.getId().getTag("field") + "=" + meter.getId().getTag("value"))
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("mic=ok", "mic=lost", "player=ok", "player=error", "visibility=visible",
                        "visibility=hidden", "wake_lock=held", "wake_lock=released");
    }

    private Set<String> labels(String name, String key) {
        return metrics.registry().find(name).meters().stream().map(Meter::getId).map(id -> id.getTag(key))
                .collect(Collectors.toSet());
    }

    private Timer timer(String name) {
        return metrics.registry().find(name).timer();
    }
}
