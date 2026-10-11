package kr.mujoong.core.observability.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.common.db.DbPoolWaitMonitor;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Core 지표와 METRIC_SNAPSHOT을 실제 앱 · DB로 움직인다 (U1 3.6 수정 · OD-5 · OD-6 · OD-12 · NFR4.15 · NFR7.6 · NFR7.8 · BT-NFR4.15 ·
 * BT-NFR7.6 · BT-NFR7.8 · BT-OD-06 · J322 ~ J326).
 *
 * <p>관리 포트의 {@code /actuator/prometheus}에 설계 표 2.5의 Core 이름이 있고, Outbox · Inbox 행 · REST 요청 · 풀 대기가 게이지 · 카운터 ·
 * 스냅샷 줄 값을 실제로 바꾸는지 본다. 넣는 Outbox 행은 available_at을 먼 미래로 두어 이 앱의 발송기가 집지 않게 한다. 시각은 앱의 가변 Clock
 * 이다(스냅샷 주기 = 시험 값 1초를 앞당긴다).
 */
@Tag("u1")
@Tag("db")
class MetricSnapshotIT {

    private static final Instant ANCIENT = Instant.parse("1990-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    private static DbTestSupport db;
    private static ContractStubServer realtime;
    private static CoreWebApp app;
    private static LogCapture logs;

    @BeforeAll
    static void start() {
        db = DbTestSupport.coreApp();
        String token = TestSecrets.internalServiceToken();
        realtime = WebFixtures.realtime(token);
        app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).start();
        logs = LogCapture.attach();
    }

    @AfterEach
    void cleanUp() {
        db.cleanUp();
    }

    @AfterAll
    static void stop() {
        if (logs != null) {
            logs.close();
        }
        if (app != null) {
            app.close();
        }
        if (realtime != null) {
            realtime.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void prometheusShowsTheCoreMetricNamesWhileHikariMetricsStillReport() {
        Result scrape = app.management("/actuator/prometheus");

        assertThat(scrape.status()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("mujoong_outbox_pending{direction=\"core_to_realtime\"}")
                .contains("mujoong_outbox_oldest_pending_seconds{direction=\"core_to_realtime\"}")
                .contains("mujoong_outbox_failed{direction=\"core_to_realtime\"}")
                .contains("mujoong_inbox_dead{direction=\"realtime_to_core\"}")
                .contains("mujoong_db_pool_wait_exceeded_total")
                .contains("mujoong_session_view_fallback_total")
                .contains("mujoong_timeout_fired_total{type=\"preparing\"}")
                .contains("mujoong_health_up{group=\"readiness\"} 1.0")
                .contains("hikaricp_connections_active{pool=\"core-api\"}")
                .contains("hikaricp_connections_acquire_seconds_count{pool=\"core-api\"}");
    }

    @Test
    void outboxAndInboxRowsMoveTheGaugesAndTheSnapshotLine() {
        MetricSnapshotJob job = app.bean(MetricSnapshotJob.class);
        job.tick();
        MetricSnapshotJob.Backlog before = job.latestBacklog().orElseThrow();

        outboxRow("PENDING");
        outboxRow("PENDING");
        outboxRow("FAILED");
        deadInboxRow();
        flushSnapshot(job);

        MetricSnapshotJob.Backlog after = job.latestBacklog().orElseThrow();
        assertThat(after.pending() - before.pending()).isEqualTo(2);
        assertThat(after.failed() - before.failed()).isEqualTo(1);
        assertThat(after.dead() - before.dead()).isEqualTo(1);
        assertThat(after.oldestPendingSeconds()).as("가장 오래된 PENDING(1990년 행)의 나이")
                .isEqualTo(Duration.between(ANCIENT, app.clock().now()).toSeconds());
        Map<String, Object> line = LogCapture.attrs(logs.last(EventCode.METRIC_SNAPSHOT).orElseThrow());
        assertThat(((Number) line.get("outboxPending")).longValue()).isEqualTo(after.pending());
        assertThat(((Number) line.get("inboxDead")).longValue()).isEqualTo(after.dead());
        CoreMetrics metrics = app.bean(CoreMetrics.class);
        assertThat(metrics.gauge("mujoong.outbox.pending", "direction", CoreMetrics.OUTBOX_DIRECTION))
                .isEqualTo((double) after.pending());
        assertThat(app.management("/actuator/prometheus").body())
                .contains("mujoong_outbox_failed{direction=\"core_to_realtime\"} " + (double) after.failed());
    }

    @Test
    void restAndViewRequestsMoveTheSnapshotCountersButHealthDoesNot() {
        MetricSnapshotJob job = app.bean(MetricSnapshotJob.class);
        flushSnapshot(job);

        assertThat(app.get("/api/home").send().status()).as("세션 없음").isEqualTo(401);
        assertThat(app.get("/api/sessions/" + UUID.randomUUID()).send().status()).isEqualTo(401);
        assertThat(app.get("/api/health").send().status()).isEqualTo(200);
        flushSnapshot(job);

        Map<String, Object> line = LogCapture.attrs(logs.last(EventCode.METRIC_SNAPSHOT).orElseThrow());
        assertThat(((Number) line.get("restTotal")).longValue()).isEqualTo(1);
        assertThat(((Number) line.get("viewTotal")).longValue()).isEqualTo(1);
        assertThat(((Number) line.get("restLe300Ms")).longValue()).isBetween(0L, 1L);
        assertThat(line.keySet()).doesNotContain("openConnections", "sessionsActive");
    }

    @Test
    void eventDeliveryHistogramPublishesTheDesignBuckets() {
        app.bean(CoreMetrics.class).eventDelivered("SessionStateChanged", Duration.ofMillis(120));

        String body = app.management("/actuator/prometheus").body();

        assertThat(body)
                .contains("mujoong_event_delivery_seconds_bucket{event_type=\"SessionStateChanged\",le=\"0.1\"} 0")
                .contains("mujoong_event_delivery_seconds_bucket{event_type=\"SessionStateChanged\",le=\"0.25\"} 1")
                .contains("mujoong_event_delivery_seconds_bucket{event_type=\"SessionStateChanged\",le=\"300.0\"} 1");
    }

    @Test
    void aWaitLongerThanTheThresholdOnARealPoolIsCounted() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CoreMetrics metrics = new CoreMetrics(registry);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("registry", registry);
        beans.registerSingleton("metrics", metrics);
        DbPoolWaitMonitor monitor = new DbPoolWaitMonitor(Duration.ofMillis(10),
                beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                beans.getBeanProvider(CoreMetrics.class));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.postgres().jdbcUrl());
        config.setUsername(db.postgres().username(Role.CORE_APP));
        config.setPassword(db.postgres().password(Role.CORE_APP));
        config.setMaximumPoolSize(1);
        config.setPoolName("wait-probe");
        try (HikariDataSource pool = new HikariDataSource()) {
            config.copyStateTo(pool);
            monitor.apply(pool);
            CountDownLatch holding = new CountDownLatch(1);
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
                try (Connection held = pool.getConnection()) {
                    holding.countDown();
                    Thread.sleep(Duration.ofMillis(300));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

            try (Connection waited = pool.getConnection()) {
                assertThat(waited.isValid(1)).isTrue();
            }
            holder.get(10, TimeUnit.SECONDS);
            assertThat(registry.find("hikaricp.connections.acquire").tag("pool", "wait-probe").timer())
                    .as("품은 Micrometer 추적기가 빌림 두 번을 그대로 잰다(풀을 닫으면 걷힌다)").isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(2));
        }

        // 기다린 빌림은 반드시 센다. 첫 빌림은 바로 받지만 기계가 바쁘면 10ms를 넘을 수 있어 하나 더 셀 수 있다(그것도 실제 초과다)
        assertThat(monitor.exceededTotal()).isBetween(1L, 2L);
        assertThat(metrics.count(CoreMetrics.DB_POOL_WAIT_EXCEEDED)).isEqualTo((double) monitor.exceededTotal());
        assertThat(monitor.drainWindow().maxWait()).as("붙잡힌 300ms 가운데 대부분을 기다렸다")
                .isGreaterThan(Duration.ofMillis(100));
    }

    /** 앱 Clock을 스냅샷 주기만큼 앞당기고 표본을 돌려 지금까지의 구간을 한 줄로 닫는다. */
    private static void flushSnapshot(MetricSnapshotJob job) {
        app.clock().advance(app.bean(kr.mujoong.core.config.CoreProperties.class).observability().metricSnapshotInterval());
        job.tick();
    }

    private static void outboxRow(String status) {
        UUID id = db.trackOutbox(UUID.randomUUID());
        db.jdbc().update("""
                insert into core.core_outbox_event (event_id, event_type, schema_version, occurred_at, aggregate_type,
                    aggregate_id, payload_json, payload_hash, status, retry_count, available_at, created_at)
                values (:id, 'DeletionRequested', 1, :created, 'Guardian', :aggregate, '{}'::jsonb, :hash, :status, 0,
                    :available, :created)
                """, new MapSqlParameterSource("id", id)
                .addValue("created", java.sql.Timestamp.from(ANCIENT))
                .addValue("aggregate", UUID.randomUUID())
                .addValue("hash", DbTestSupport.randomHash())
                .addValue("status", status)
                .addValue("available", java.sql.Timestamp.from(FAR_FUTURE)));
    }

    private static void deadInboxRow() {
        UUID id = db.trackInbox(UUID.randomUUID());
        db.jdbc().update("""
                insert into core.core_inbox_event (event_id, source_service, event_type, schema_version, occurred_at,
                    aggregate_id, payload_json, payload_hash, status, retry_count, received_at)
                values (:id, 'REALTIME', 'SessionStateChanged', 1, :at, :aggregate, '{}'::jsonb, :hash, 'DEAD', 5, :at)
                """, new MapSqlParameterSource("id", id)
                .addValue("at", java.sql.Timestamp.from(ANCIENT))
                .addValue("aggregate", UUID.randomUUID())
                .addValue("hash", DbTestSupport.randomHash()));
    }
}
