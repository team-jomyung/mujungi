package kr.mujoong.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Core 지표 이름 · 라벨 (U1 3.6 수정 · OD-5 · OD-12 · NFR7.6 · NFR7.8 · NFR4.15 · J322 · J323 · J324). Prometheus 형식으로 긁어 설계 표 2.5의
 * 이름 · 라벨 그대로 나오는지(호스트 수집기 {@code infra/bin/host-collect.sh}가 읽는 이름), 사건이 없어도 0으로 보이는지, 값이 실제로 움직이는지
 * 본다.
 */
@Tag("skeleton")
class CoreMetricsTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final CoreMetrics metrics = new CoreMetrics(registry);

    @Test
    void backlogGaugesUseTheDesignNamesAndDirectionLabels() {
        metrics.messagingBacklog(3, 75, 1, 2);

        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("mujoong_outbox_pending{direction=\"core_to_realtime\"} 3.0")
                .contains("mujoong_outbox_oldest_pending_seconds{direction=\"core_to_realtime\"} 75.0")
                .contains("mujoong_outbox_failed{direction=\"core_to_realtime\"} 1.0")
                .contains("mujoong_inbox_dead{direction=\"realtime_to_core\"} 2.0");
    }

    @Test
    void countersWithoutAnyEventAreAlreadyExportedAtZero() {
        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("mujoong_db_pool_wait_exceeded_total 0.0")
                .contains("mujoong_session_view_fallback_total 0.0")
                .contains("mujoong_timeout_fired_total{type=\"preparing\"} 0.0")
                .contains("mujoong_timeout_fired_total{type=\"device_status_stale\"} 0.0")
                .contains("mujoong_outbox_pending{direction=\"core_to_realtime\"} 0.0")
                .contains("mujoong_health_up{group=\"liveness\"} 1.0");
    }

    @Test
    void poolWaitExceededAndFallbackCountersMove() {
        metrics.dbPoolWaitExceeded();
        metrics.dbPoolWaitExceeded();
        metrics.sessionViewFallback();
        metrics.timeoutFired("PREPARING");

        assertThat(metrics.count(CoreMetrics.DB_POOL_WAIT_EXCEEDED)).isEqualTo(2.0);
        assertThat(metrics.count(CoreMetrics.SESSION_VIEW_FALLBACK)).isEqualTo(1.0);
        assertThat(registry.scrape()).contains("mujoong_db_pool_wait_exceeded_total 2.0")
                .contains("mujoong_timeout_fired_total{type=\"preparing\"} 1.0");
    }

    @Test
    void negativeBacklogValuesAreClampedToZero() {
        metrics.messagingBacklog(-1, -30, -2, -3);

        assertThat(metrics.gauge("mujoong.outbox.pending", "direction", CoreMetrics.OUTBOX_DIRECTION)).isZero();
        assertThat(metrics.gauge("mujoong.outbox.oldest.pending.seconds", "direction", CoreMetrics.OUTBOX_DIRECTION)).isZero();
        assertThat(metrics.gauge("mujoong.outbox.failed", "direction", CoreMetrics.OUTBOX_DIRECTION)).isZero();
        assertThat(metrics.gauge("mujoong.inbox.dead", "direction", CoreMetrics.INBOX_DIRECTION)).isZero();
        assertThat(metrics.gauge("mujoong.no.such.gauge")).isNaN();
    }

    @Test
    void requestTotalsCountFastRequestsUpToTheBoundaryInclusive() {
        metrics.restRequest(Duration.ofMillis(300));
        metrics.restRequest(Duration.ofMillis(301));
        metrics.restRequest(Duration.ZERO);
        metrics.viewRequest(Duration.ofMillis(500));
        metrics.viewRequest(Duration.ofMillis(501));

        assertThat(metrics.requestTotals()).isEqualTo(new CoreMetrics.RequestTotals(3, 2, 2, 1));
        assertThat(registry.scrape()).as("메모리 누계라 Prometheus 이름을 늘리지 않는다").doesNotContain("rest_total")
                .doesNotContain("view_total");
    }

    @Test
    void negativeDurationsAreCountedButNeverAsFast() {
        metrics.restRequest(Duration.ofMillis(-1));
        metrics.viewRequest(Duration.ofMillis(-1));

        assertThat(metrics.requestTotals()).isEqualTo(new CoreMetrics.RequestTotals(1, 0, 1, 0));
    }
}
