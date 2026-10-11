package kr.mujoong.core.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.core.observability.CoreMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * 풀 연결 획득 대기 측정 (U1 3.6 수정 · NFR7.6 · OD-5 · OD-6 · J323). 기준(시험 10ms)을 넘은 빌림만 세고, 획득 시간 초과도 넘은 것으로 센다.
 * Hikari 추적기 자리에 품은 Micrometer 추적기가 {@code hikaricp_*}를 그대로 내는지, 이미 추적기가 있는 DataSource는 건드리지 않는지 본다.
 */
@Tag("u1")
class DbPoolWaitMonitorTest {

    private static final Duration WARN = Duration.ofMillis(10);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CoreMetrics metrics = new CoreMetrics(registry);
    private final AtomicInteger active = new AtomicInteger();
    private final PoolStats stats = new PoolStats(0) {
        @Override
        protected void update() {
            this.activeConnections = active.get();
        }
    };
    private final DbPoolWaitMonitor monitor = new DbPoolWaitMonitor(WARN, provider(registry), provider(metrics));

    @Test
    void waitOverTheThresholdIsCountedOnceAndRaisesTheWindowMaximum() {
        IMetricsTracker tracker = monitor.factory().create("core-api", stats);
        active.set(4);

        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(25).toNanos());
        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(3).toNanos());

        assertThat(monitor.exceededTotal()).isEqualTo(1);
        assertThat(metrics.count(CoreMetrics.DB_POOL_WAIT_EXCEEDED)).isEqualTo(1.0);
        DbPoolWaitMonitor.Window window = monitor.drainWindow();
        assertThat(window.maxWait()).isEqualTo(Duration.ofMillis(25));
        assertThat(window.maxActive()).isEqualTo(4);
    }

    @Test
    void waitExactlyAtTheThresholdIsNotCounted() {
        IMetricsTracker tracker = monitor.factory().create("core-api", stats);

        tracker.recordConnectionAcquiredNanos(WARN.toNanos());
        tracker.recordConnectionAcquiredNanos(-5);

        assertThat(monitor.exceededTotal()).isZero();
        assertThat(metrics.count(CoreMetrics.DB_POOL_WAIT_EXCEEDED)).isZero();
        assertThat(monitor.drainWindow().maxWait()).isEqualTo(WARN);
    }

    @Test
    void acquireTimeoutCountsAsExceededWithTheConnectionTimeoutAsTheMaximum() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setConnectionTimeout(Duration.ofSeconds(2).toMillis());
        monitor.apply(dataSource);
        IMetricsTracker tracker = dataSource.getMetricsTrackerFactory().create("core-api", stats);

        tracker.recordConnectionTimeout();

        assertThat(monitor.exceededTotal()).isEqualTo(1);
        assertThat(monitor.drainWindow().maxWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(registry.find("hikaricp.connections.timeout").counter()).isNotNull()
                .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0));
    }

    @Test
    void trackerForwardsEveryCallToTheMicrometerTrackerSoHikariMetricsStay() {
        IMetricsTracker tracker = monitor.factory().create("core-api", stats);

        tracker.recordConnectionCreatedMillis(7);
        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(1).toNanos());
        tracker.recordConnectionUsageMillis(12);

        assertThat(registry.find("hikaricp.connections.acquire").timer()).isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
        assertThat(registry.find("hikaricp.connections.usage").timer()).isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
        assertThat(registry.find("hikaricp.connections.creation").timer()).isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));

        tracker.close();
        assertThat(registry.find("hikaricp.connections.acquire").timer()).as("닫기도 넘겨 Hikari 지표를 걷는다").isNull();
    }

    @Test
    void drainingTheWindowResetsTheMaximumsButNotTheTotal() {
        IMetricsTracker tracker = monitor.factory().create("core-api", stats);
        active.set(2);
        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(40).toNanos());
        monitor.drainWindow();
        active.set(1);

        assertThat(monitor.sampleActive()).isEqualTo(1);
        DbPoolWaitMonitor.Window next = monitor.drainWindow();
        assertThat(next.maxWait()).isZero();
        assertThat(next.maxActive()).isEqualTo(1);
        assertThat(monitor.exceededTotal()).as("누계는 구간과 무관").isEqualTo(1);
    }

    @Test
    void postProcessorTouchesOnlyHikariWithoutAnExistingTracker() {
        HikariDataSource fresh = new HikariDataSource();
        HikariDataSource preset = new HikariDataSource();
        MetricsTrackerFactory existing = (poolName, poolStats) -> new IMetricsTracker() { };
        preset.setMetricsTrackerFactory(existing);
        Object other = new Object();

        assertThat(monitor.postProcessBeforeInitialization(fresh, "dataSource")).isSameAs(fresh);
        monitor.postProcessBeforeInitialization(preset, "other");

        assertThat(fresh.getMetricsTrackerFactory()).isNotNull();
        assertThat(preset.getMetricsTrackerFactory()).as("숨은 덮어쓰기 없음").isSameAs(existing);
        assertThat(monitor.postProcessBeforeInitialization(other, "x")).isSameAs(other);
        assertThat(monitor.sampleActive()).as("풀이 없으면 0").isZero();
    }

    @Test
    void withoutRegistryOrMetricsItStillMeasuresAndNonPositiveThresholdIsRejected() {
        DbPoolWaitMonitor bare = new DbPoolWaitMonitor(WARN, provider(null), provider(null));
        IMetricsTracker tracker = bare.factory().create("core-api", stats);

        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(50).toNanos());

        assertThat(bare.exceededTotal()).isEqualTo(1);
        assertThatThrownBy(() -> new DbPoolWaitMonitor(Duration.ZERO, provider(registry), provider(metrics)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(DbPoolWaitMonitor.WARN_KEY);
    }

    /** 빈이 있거나 없는 ObjectProvider(값이 null이면 없는 문맥). */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (value == null) {
            return (ObjectProvider<T>) factory.getBeanProvider(MeterRegistry.class);
        }
        factory.registerSingleton("bean", value);
        return (ObjectProvider<T>) factory.getBeanProvider(value.getClass());
    }
}
