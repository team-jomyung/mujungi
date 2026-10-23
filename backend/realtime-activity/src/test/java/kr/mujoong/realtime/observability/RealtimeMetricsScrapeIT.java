package kr.mujoong.realtime.observability;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.common.db.DbPoolWaitMonitor;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.messaging.RealtimeInboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.observability.snapshot.MetricSnapshotJob;
import kr.mujoong.realtime.observability.snapshot.SystemLoad;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Realtime 지표를 실제 앱 · DB · 기기 시뮬레이터 · 가짜 공급자로 움직이고 관리 포트에서 긁는다 (U1 3.6 수정 · OD-5 · OD-6 · OD-12 · NFR4.15 ·
 * NFR5.4 · NFR7.6 · NFR7.8 · BT-NFR4.15 · BT-NFR7.6 · BT-NFR7.8 · BT-OD-06 · J295 ~ J303 · J309).
 *
 * <p>관리 포트(test 프로필은 무작위 포트, 운영은 127.0.0.1:9090)의 {@code /actuator/prometheus}에 설계 표 2.5의 Realtime 이름 · 라벨 ·
 * 버킷이 있고, 본 포트는 지표를 내지 않으며, 한 턴 · 연결 원장 · Outbox · Inbox 행 · 풀 대기가 게이지 · 카운터 · 히스토그램 ·
 * METRIC_SNAPSHOT 값을 실제로 바꾸는지 본다. 시험마다 앱을 새로 띄운다(값이 0에서 시작).
 */
@Tag("skeleton")
@Tag("db")
@org.junit.jupiter.api.Tag("service-guardrail")
class RealtimeMetricsScrapeIT {

    private static final byte[] PCM = kr.mujoong.realtime.testing.RealtimeItEnvironment.audiblePcm(4800);
    private static final byte[] FRAME = new byte[960];
    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private RealtimeItEnvironment env;
    private LogCapture logs;

    @BeforeEach
    void start() {
        // 시험 설정의 segment-quiet 50ms는 가짜 공급자가 음성보다 먼저 보내는 출력 전사와 첫 음성 사이 틈보다 짧다: CI처럼 CPU가 차면 음성 없는
        // 구간이 먼저 봉인돼 GATE_EMPTY로 폐기되고 OUTPUT_BEGIN이 오지 않는다. SessionVoiceU3IT처럼 운영값 800ms로 둔다
        env = RealtimeItEnvironment.start(Map.of("mujoong.gate.segment-quiet", "800ms"), null);
        // 앱 기동이 Logback 설정을 다시 읽으므로 수집기는 기동 뒤에 붙인다
        logs = LogCapture.attach();
    }

    @AfterEach
    void stop() {
        try {
            env.close();
        } finally {
            logs.close();
        }
    }

    @Test
    void theManagementPortServesEveryRealtimeDesignNameAndTheMainPortServesNone() {
        String body = scrape();

        assertThat(body)
                .contains("mujoong_device_connections_open 0.0")
                .contains("mujoong_device_connection_state{state=\"offline\"} 0.0")
                .contains("mujoong_device_connection_state{state=\"connected\"} 0.0")
                .contains("mujoong_device_connection_state{state=\"unstable\"} 0.0")
                .contains("mujoong_device_connection_state{state=\"disconnected\"} 0.0")
                .contains("mujoong_device_connection_state{state=\"unresponsive\"} 0.0")
                .contains("mujoong_outbox_pending{direction=\"realtime_to_core\"}")
                .contains("mujoong_outbox_oldest_pending_seconds{direction=\"realtime_to_core\"}")
                .contains("mujoong_outbox_failed{direction=\"realtime_to_core\"}")
                .contains("mujoong_inbox_dead{direction=\"core_to_realtime\"}")
                .contains("mujoong_db_pool_wait_exceeded_total")
                .contains("mujoong_queue_depth{queue=\"session_mailbox\"} 0.0")
                .contains("mujoong_queue_depth{queue=\"output_audio\"} 0.0")
                .contains("mujoong_queue_depth{queue=\"output_control\"} 0.0")
                .contains("mujoong_queue_depth{queue=\"recorder\"} 0.0")
                .contains("mujoong_sessions_active 0.0")
                .contains("mujoong_session_activated_total 0.0")
                .contains("mujoong_timeout_fired_total{type=\"device_ack\"} 0.0")
                .contains("mujoong_recorder_rejected_total 0.0")
                .contains("mujoong_session_start_to_active_seconds_count 0")
                .contains("mujoong_relay_out_seconds_count 0")
                .contains("mujoong_turn_first_audio_seconds_count 0")
                .contains("mujoong_view_item_ready_seconds_count{speaker=\"ai\"} 0")
                .contains("mujoong_health_up{group=\"readiness\"} 1.0")
                .contains("hikaricp_connections_active{pool=\"realtime\"}")
                .contains("hikaricp_connections_acquire_seconds_count{pool=\"realtime\"}")
                .doesNotContain("mujoong_ws_connections_open");
        // J309: liveness 게이지의 상태 객체를 필드로 붙잡아 GC 뒤에도 NaN이 되지 않는다(3.6 IT-06 긁기에서 NaN이었다)
        System.gc();
        assertThat(scrape()).contains("mujoong_health_up{group=\"liveness\"} 1.0");
        HttpResponse<String> main = get(URI.create("http://127.0.0.1:" + env.app.port() + "/actuator/prometheus"));
        assertThat(main.statusCode()).as("본 포트(입구 뒤)는 지표를 내지 않는다").isNotEqualTo(200);
        assertThat(main.body()).doesNotContain("mujoong_");
    }

    @Test
    void theHistogramBucketsAreTheDesignTableValues() {
        env.app.bean(RealtimeMetrics.class).eventDelivered("DeviceReleased", Duration.ofMillis(120));

        String body = scrape();

        assertThat(buckets(body, "mujoong_session_start_to_active_seconds", null))
                .containsExactly(seconds("1", "2", "4", "6", "8", "10", "15", "20"));
        for (String relay : List.of("mujoong_relay_in_seconds", "mujoong_relay_out_seconds")) {
            assertThat(buckets(body, relay, null)).as(relay)
                    .containsExactly(seconds("0.01", "0.025", "0.05", "0.1", "0.2", "0.3", "0.5", "1"));
        }
        assertThat(buckets(body, "mujoong_turn_first_audio_seconds", null))
                .containsExactly(seconds("0.5", "1", "1.5", "2", "2.5", "3", "4", "6", "10"));
        for (String speaker : List.of("child", "ai")) {
            assertThat(buckets(body, "mujoong_view_item_ready_seconds", "speaker=\"" + speaker + "\"")).as(speaker)
                    .containsExactly(seconds("0.05", "0.1", "0.25", "0.5", "1", "1.5", "2", "5"));
        }
        assertThat(buckets(body, "mujoong_event_delivery_seconds", "event_type=\"DeviceReleased\""))
                .containsExactly(seconds("0.1", "0.25", "0.5", "1", "2", "5", "10", "30", "60", "300"));
        assertThat(body).contains("mujoong_event_delivery_seconds_bucket{event_type=\"DeviceReleased\",le=\"0.1\"} 0")
                .contains("mujoong_event_delivery_seconds_bucket{event_type=\"DeviceReleased\",le=\"0.25\"} 1");
    }

    @Test
    void aFullTurnMovesTheSessionRelayTurnAndItemReadyMetrics() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        assertThat(env.start(sessionId)).isEqualTo(new StartSessionHandler.Accepted(SessionStatus.PREPARING));
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(command.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitTrue(() -> status(sessionId) == SessionStatus.ACTIVE, "ACTIVE");
        // LISTENING은 입력 수락을 연 뒤에 나간다 — 그 뒤의 프레임은 버려지지 않는다
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING,
                WAIT);
        awaitTrue(() -> value(scrape(), "mujoong_session_activated_total") == 1.0, "활성 카운터");

        // 입력 프레임 → relay_in, 한 턴 → relay_out · turn_first_audio · view_item_ready{child · ai}
        device.heartbeat(30L);
        for (int i = 0; i < 3; i++) {
            device.sendAudio(FRAME);
        }
        env.provider.await(ProviderRequestFactory.TYPE_INPUT_AUDIO_APPEND, 3, WAIT);
        env.provider.emitTurn("item-1", "공룡이 좋아", "나도 공룡 좋아해", List.of(PCM, PCM));
        WsEnvelope begin = device.await(WsMessageType.OUTPUT_BEGIN, WAIT);
        device.await(WsMessageType.OUTPUT_END, WAIT);
        device.playbackMark(sessionId, ((OutputBegin) begin.payload()).playbackId(), PlaybackMark.Status.STARTED);
        awaitTrue(() -> value(scrape(), "mujoong_turn_first_audio_seconds_count") == 1.0, "첫 소리 히스토그램");
        awaitTrue(() -> value(scrape(), "mujoong_view_item_ready_seconds_count{speaker=\"ai\"}") == 1.0, "AI 행 준비");

        String body = scrape();
        assertThat(value(body, "mujoong_session_start_to_active_seconds_count")).isEqualTo(1.0);
        assertThat(value(body, "mujoong_relay_in_seconds_count")).isEqualTo(3.0);
        assertThat(value(body, "mujoong_relay_out_seconds_count")).isEqualTo(1.0);
        // 아이 행은 수락한 참여 대답 하나와 이어진 아이 말 하나다
        assertThat(value(body, "mujoong_view_item_ready_seconds_count{speaker=\"child\"}")).isEqualTo(2.0);
        assertThat(value(body, "mujoong_sessions_active")).isEqualTo(1.0);
        assertThat(value(body, "mujoong_device_connections_open")).isEqualTo(1.0);
        assertThat(value(body, "mujoong_relay_out_seconds_sum")).as("구간 확정 → 첫 프레임은 음수가 아니다")
                .isGreaterThanOrEqualTo(0.0);
        assertThat(body).as("라벨에 id · 전사가 없다").doesNotContain(sessionId.toString()).doesNotContain("공룡");
    }

    @Test
    void theConnectionStateGaugeFollowsTheLedgerOnTheSampleInterval() {
        DeviceSimulatorClient device = env.connectDevice();

        awaitTrue(() -> value(scrape(), "mujoong_device_connection_state{state=\"connected\"}") == 1.0,
                "표본 주기 안에 CONNECTED 1");
        assertThat(value(scrape(), "mujoong_device_connections_open")).isEqualTo(1.0);

        device.closeWith(1000);
        awaitTrue(() -> value(scrape(), "mujoong_device_connection_state{state=\"connected\"}") == 0.0,
                "닫힌 뒤 CONNECTED 0");
        String body = scrape();
        double others = value(body, "mujoong_device_connection_state{state=\"offline\"}")
                + value(body, "mujoong_device_connection_state{state=\"disconnected\"}")
                + value(body, "mujoong_device_connection_state{state=\"unresponsive\"}");
        assertThat(others).as("원장 행 하나가 다른 상태로 옮겼다").isEqualTo(1.0);
        awaitTrue(() -> value(scrape(), "mujoong_device_connections_open") == 0.0, "열린 WSS 0");
    }

    @Test
    void outboxAndInboxRowsMoveTheBacklogGaugesAndTheSnapshotLine() {
        Instant now = Instant.now();
        Instant future = now.plus(Duration.ofDays(365));
        outboxRow("PENDING", now.minusSeconds(120), future);
        outboxRow("PENDING", now.minusSeconds(30), future);
        outboxRow("FAILED", now.minusSeconds(300), future);
        deadInboxRow(now.minusSeconds(60));

        awaitTrue(() -> value(scrape(), "mujoong_outbox_pending{direction=\"realtime_to_core\"}") == 2.0,
                "PENDING 게이지");
        String body = scrape();
        assertThat(value(body, "mujoong_outbox_failed{direction=\"realtime_to_core\"}")).isEqualTo(1.0);
        assertThat(value(body, "mujoong_inbox_dead{direction=\"core_to_realtime\"}")).isEqualTo(1.0);
        assertThat(value(body, "mujoong_outbox_oldest_pending_seconds{direction=\"realtime_to_core\"}"))
                .as("가장 오래된 PENDING의 created_at 나이(available_at과 무관)").isBetween(120.0, 120.0 + WAIT.toSeconds());

        awaitTrue(() -> logs.events(EventCode.METRIC_SNAPSHOT).stream()
                .anyMatch(line -> number(line, "outboxPending") == 2), "METRIC_SNAPSHOT 줄");
        Map<String, Object> line = logs.events(EventCode.METRIC_SNAPSHOT).stream()
                .filter(event -> number(event, "outboxPending") == 2).reduce((first, second) -> second).orElseThrow();
        assertThat(number(line, "outboxFailed")).isEqualTo(1);
        assertThat(number(line, "inboxDead")).isEqualTo(1);
        assertThat(number(line, "outboxOldestPendingSec")).isBetween(120L, 120L + WAIT.toSeconds());
        assertThat(LogCapture.attrs(line)).containsKeys("poolActive", "poolWaitMaxMs", "poolWaitExceeded",
                "openConnections", "sessionsActive").doesNotContainKeys("restTotal", "viewTotal", "breakerOpenSec");
    }

    /**
     * 실제 풀(연결 1개)에서 다른 빌림이 연결을 기다렸다가 해제 뒤 받으면, 대기 측정기가 Hikari의 Micrometer 추적기 앞에 걸려
     * 빌림 두 번이 그대로 재지고 우리 초과 지표가 모니터 합계와 같다. 시간 의존 없이 실제 대기 스레드를 확인한 뒤 해제한다.
     * 대기 시간 · 임계값 판정(초과 횟수 · 구간 최댓값)은 DbPoolWaitMonitorTest가 본다.
     */
    @Test
    void aRealPoolWaitIsMeasuredAndWiredToTheMetricsWhileHikariStillReports() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(registry);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("registry", registry);
        beans.registerSingleton("metrics", metrics);
        DbPoolWaitMonitor monitor = new DbPoolWaitMonitor(Duration.ofMillis(10),
                beans.getBeanProvider(MeterRegistry.class), beans.getBeanProvider(RealtimeMetrics.class));

        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setPoolName("wait-probe");
            pool.setJdbcUrl(env.postgres.jdbcUrl());
            pool.setUsername(env.postgres.username(Role.REALTIME_APP));
            pool.setPassword(env.postgres.password(Role.REALTIME_APP));
            pool.setMaximumPoolSize(1);
            monitor.apply(pool);

            CompletableFuture<Boolean> waiter;
            try (Connection held = pool.getConnection()) {
                assertThat(held.isValid(1)).isTrue();
                waiter = CompletableFuture.supplyAsync(() -> {
                    try (Connection waited = pool.getConnection()) {
                        return waited.isValid(1);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                // 연결 하나를 쥔 채로 두 번째 빌림이 실제로 기다리기 시작할 때까지 본 뒤 해제한다
                awaitTrue(() -> pool.getHikariPoolMXBean().getThreadsAwaitingConnection() >= 1, "대기 스레드");
            }
            assertThat(waiter.get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("해제 뒤 기다린 빌림이 끝난다").isTrue();
            assertThat(registry.find("hikaricp.connections.acquire").tag("pool", "wait-probe").timer())
                    .as("품은 Micrometer 추적기가 빌림 두 번을 그대로 잰다").isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(2));
        }

        assertThat(metrics.count(RealtimeMetrics.DB_POOL_WAIT_EXCEEDED)).as("초과 지표는 모니터 합계와 같다")
                .isEqualTo((double) monitor.exceededTotal());
        assertThat(value(scrape(), "hikaricp_connections_acquire_seconds_count{pool=\"realtime\"}"))
                .as("앱 풀에도 같은 추적기가 걸려 Boot의 hikaricp 지표가 그대로 나온다").isPositive();
    }

    /**
     * 3.6 수정 J364: 실제 풀(연결 1개 · 획득 제한 250ms)에서 빌림 시간 초과 1회는 {@code mujoong_db_pool_wait_exceeded_total} +1이고
     * METRIC_SNAPSHOT의 poolWaitExceeded도 1이다. HikariCP는 같은 빌림에 대기 기록(≈ 250ms)과 시간 초과 기록을 함께 부른다(리뷰 재현은 2).
     */
    @Test
    void aTimedOutBorrowOnARealPoolIsOneExceededWaitInTheCounterAndTheSnapshotLine() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(registry);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("registry", registry);
        beans.registerSingleton("metrics", metrics);
        DbPoolWaitMonitor monitor = new DbPoolWaitMonitor(Duration.ofMillis(100),
                beans.getBeanProvider(MeterRegistry.class), beans.getBeanProvider(RealtimeMetrics.class));
        MutableClock clock = new MutableClock(Instant.parse("2001-05-01T00:00:00Z"));
        LogCapture lines = LogCapture.detached();
        Duration interval = Duration.ofSeconds(60);

        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setPoolName("timeout-probe");
            pool.setJdbcUrl(env.postgres.jdbcUrl());
            pool.setUsername(env.postgres.username(Role.REALTIME_APP));
            pool.setPassword(env.postgres.password(Role.REALTIME_APP));
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(Duration.ofMillis(250).toMillis());
            monitor.apply(pool);
            try (Connection held = pool.getConnection()) {
                MetricSnapshotJob job = new MetricSnapshotJob(clock, interval, env.app.bean(RealtimeOutboxRepository.class),
                        env.app.bean(RealtimeInboxRepository.class), env.app.bean(DeviceConnectionRepository.class), monitor,
                        metrics, TestEventLoggers.strict(clock, lines, metrics), SystemLoad.platform());
                long exceededBefore = monitor.exceededTotal();
                double counterBefore = metrics.count(RealtimeMetrics.DB_POOL_WAIT_EXCEEDED);

                assertThatThrownBy(pool::getConnection).isInstanceOf(SQLTransientConnectionException.class);

                assertThat(monitor.exceededTotal() - exceededBefore).as("빌림 시간 초과 1회").isEqualTo(1);
                assertThat(metrics.count(RealtimeMetrics.DB_POOL_WAIT_EXCEEDED) - counterBefore).isEqualTo(1.0);
                clock.advance(interval);
                job.tick();
                Map<String, Object> line = LogCapture.attrs(lines.last(EventCode.METRIC_SNAPSHOT).orElseThrow());
                assertThat(((Number) line.get("poolWaitExceeded")).longValue()).isEqualTo(1);
                assertThat(((Number) line.get("poolWaitMaxMs")).longValue()).as("획득 제한만큼 기다렸다")
                        .isGreaterThanOrEqualTo(250);
                assertThat(held.isValid(1)).isTrue();
            }
            assertThat(registry.find("hikaricp.connections.timeout").tag("pool", "timeout-probe").counter())
                    .as("품은 Micrometer 추적기는 시간 초과를 그대로 센다").isNotNull()
                    .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0));
        }
    }

    // ------------------------------------------------------------------ 보조

    private SessionStatus status(UUID sessionId) {
        return env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow().status();
    }

    private void outboxRow(String status, Instant createdAt, Instant availableAt) {
        env.db.jdbc().update("insert into realtime.realtime_outbox_event (event_id, event_type, schema_version,"
                + " occurred_at, aggregate_type, aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count,"
                + " available_at, created_at) values (:id, 'DeviceStatusChanged', 1, :createdAt, 'Device', :aggregate,"
                + " :guardian, '{}'::jsonb, :hash, :status, 0, :availableAt, :createdAt)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                        .addValue("createdAt", java.sql.Timestamp.from(createdAt))
                        .addValue("aggregate", UUID.randomUUID()).addValue("guardian", UUID.randomUUID())
                        .addValue("hash", "0".repeat(64)).addValue("status", status)
                        .addValue("availableAt", java.sql.Timestamp.from(availableAt)));
    }

    private void deadInboxRow(Instant receivedAt) {
        env.db.jdbc().update("insert into realtime.realtime_inbox_event (event_id, source_service, event_type,"
                + " schema_version, occurred_at, aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count,"
                + " received_at) values (:id, 'CORE', 'DeviceReleased', 1, :at, :aggregate, :guardian, '{}'::jsonb, :hash,"
                + " 'DEAD', 5, :at)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                        .addValue("at", java.sql.Timestamp.from(receivedAt))
                        .addValue("aggregate", UUID.randomUUID()).addValue("guardian", UUID.randomUUID())
                        .addValue("hash", "0".repeat(64)));
    }

    private String scrape() {
        int port = Integer.parseInt(env.app.context().getEnvironment().getRequiredProperty("local.management.port"));
        HttpResponse<String> response = get(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static HttpResponse<String> get(URI uri) {
        try {
            return HTTP.send(HttpRequest.newBuilder(uri).timeout(WAIT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 이름(라벨 포함) 그대로인 한 줄의 값. 없으면 NaN. */
    private static double value(String body, String series) {
        for (String line : body.split("\n")) {
            if (line.startsWith(series + " ")) {
                return Double.parseDouble(line.substring(series.length() + 1).trim());
            }
        }
        return Double.NaN;
    }

    /** 히스토그램 버킷 경계(+Inf 제외, 나온 순서). {@code labelFilter}가 있으면 그 라벨을 가진 줄만. */
    private static List<BigDecimal> buckets(String body, String name, String labelFilter) {
        Pattern bucket = Pattern.compile("^" + Pattern.quote(name) + "_bucket\\{([^}]*)\\} \\S+$");
        Pattern le = Pattern.compile("le=\"([^\"]+)\"");
        List<BigDecimal> bounds = new ArrayList<>();
        for (String line : body.split("\n")) {
            Matcher matcher = bucket.matcher(line);
            if (!matcher.matches() || (labelFilter != null && !matcher.group(1).contains(labelFilter))) {
                continue;
            }
            Matcher bound = le.matcher(matcher.group(1));
            if (bound.find() && !"+Inf".equals(bound.group(1))) {
                bounds.add(new BigDecimal(bound.group(1)).stripTrailingZeros());
            }
        }
        return bounds;
    }

    private static BigDecimal[] seconds(String... values) {
        BigDecimal[] bounds = new BigDecimal[values.length];
        for (int i = 0; i < values.length; i++) {
            bounds[i] = new BigDecimal(values[i]).stripTrailingZeros();
        }
        return bounds;
    }

    private static long number(Map<String, Object> line, String key) {
        Object value = LogCapture.attrs(line).get(key);
        return value instanceof Number number ? number.longValue() : -1L;
    }
}
