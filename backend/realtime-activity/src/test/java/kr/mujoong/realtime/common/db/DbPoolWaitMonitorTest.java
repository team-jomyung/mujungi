package kr.mujoong.realtime.common.db;

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
import kr.mujoong.realtime.observability.RealtimeMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Realtime 풀 연결 획득 대기 측정 (U1 3.6 수정 · NFR7.6 · OD-5 · OD-6 · BT-NFR7.6 · J303). 기준(시험 10ms)을 넘은 빌림만 세고, 획득 시간
 * 초과도 넘은 것으로 센다. Hikari 추적기 자리에 품은 Micrometer 추적기가 {@code hikaricp_*}를 그대로 내는지, 이미 추적기가 있는 DataSource는
 * 건드리지 않는지 본다.
 */
@Tag("u1")
class DbPoolWaitMonitorTest {

    private static final Duration WARN = Duration.ofMillis(10);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RealtimeMetrics metrics = new RealtimeMetrics(registry);
    private final AtomicInteger active = new AtomicInteger();
    private final PoolStats stats = new PoolStats(0) {
        @Override
        protected void update() {
            this.activeConnections = active.get();
        }
    };
    private final DbPoolWaitMonitor monitor = new DbPoolWaitMonitor(WARN, provider(registry), provider(metrics));

    @Test
    void aWaitOverTheThresholdIsCountedOnceAndRaisesTheWindowMaximum() {
        IMetricsTracker tracker = monitor.factory().create("realtime", stats);
        active.set(4);

        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(25).toNanos());
        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(3).toNanos());

        assertThat(monitor.exceededTotal()).isEqualTo(1);
        assertThat(metrics.count(RealtimeMetrics.DB_POOL_WAIT_EXCEEDED)).isEqualTo(1.0);
        DbPoolWaitMonitor.Window window = monitor.drainWindow();
        assertThat(window.maxWait()).isEqualTo(Duration.ofMillis(25));
        assertThat(window.maxActive()).isEqualTo(4);
    }

    @Test
    void aWaitExactlyAtTheThresholdOrNegativeIsNotCounted() {
        IMetricsTracker tracker = monitor.factory().create("realtime", stats);

        tracker.recordConnectionAcquiredNanos(WARN.toNanos());
        tracker.recordConnectionAcquiredNanos(-5);

        assertThat(monitor.exceededTotal()).isZero();
        assertThat(metrics.count(RealtimeMetrics.DB_POOL_WAIT_EXCEEDED)).isZero();
        assertThat(monitor.drainWindow().maxWait()).isEqualTo(WARN);
    }

    @Test
    void anAcquireTimeoutCountsAsExceededWithTheConnectionTimeoutAsTheMaximum() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setConnectionTimeout(Duration.ofSeconds(2).toMillis());
        monitor.apply(dataSource);
        IMetricsTracker tracker = dataSource.getMetricsTrackerFactory().create("realtime", stats);

        tracker.recordConnectionTimeout();

        assertThat(monitor.exceededTotal()).isEqualTo(1);
        assertThat(monitor.drainWindow().maxWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(registry.find("hikaricp.connections.timeout").counter()).isNotNull()
                .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0));
    }

    /**
     * 3.6 수정 J364(Core와 같은 규칙): HikariCP 7.0.2는 빌림 시간 초과 한 번에 같은 스레드에서
     * {@code recordConnectionAcquiredNanos(≈ 획득 제한)}(recordBorrowTimeoutStats)를 부른 바로 뒤 {@code recordConnectionTimeout()}
     * (createTimeoutException)을 부른다. 빌림 1회는 초과 1회다.
     */
    @Test
    void aTimedOutBorrowReportedInTheOrderOfHikari702IsOneExceededWait() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setConnectionTimeout(Duration.ofSeconds(2).toMillis());
        monitor.apply(dataSource);
        IMetricsTracker tracker = dataSource.getMetricsTrackerFactory().create("realtime", stats);

        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(2003).toNanos());
        tracker.recordConnectionTimeout();

        assertThat(monitor.exceededTotal()).isEqualTo(1);
        assertThat(metrics.count(RealtimeMetrics.DB_POOL_WAIT_EXCEEDED)).isEqualTo(1.0);
        assertThat(monitor.drainWindow().maxWait()).as("실제로 기다린 시간").isEqualTo(Duration.ofMillis(2003));
        assertThat(registry.find("hikaricp.connections.timeout").counter()).isNotNull()
                .satisfies(counter -> assertThat(counter.count()).as("품은 추적기는 두 기록을 그대로 받는다").isEqualTo(1.0));
        assertThat(registry.find("hikaricp.connections.acquire").timer()).isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
    }

    /**
     * 3.6 수정 J364: 시간 초과를 흡수하는 것은 '같은 스레드의 바로 앞 기록이 이미 센 대기'일 때뿐이다. Hikari 판이 바뀌어 시간 초과에 대기 기록이
     * 없어도 1회로 세고, 반납으로 끝난 앞 빌림 · 다른 스레드의 대기 · 기준 안 대기는 시간 초과를 흡수하지 않는다.
     */
    @Test
    void onlyTheCountedWaitRecordedJustBeforeOnTheSameThreadAbsorbsATimeout() throws Exception {
        IMetricsTracker tracker = monitor.factory().create("realtime", stats);

        tracker.recordConnectionTimeout();
        assertThat(monitor.exceededTotal()).as("대기 기록 없는 시간 초과(다른 판)").isEqualTo(1);

        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(40).toNanos());
        tracker.recordConnectionUsageMillis(5);
        tracker.recordConnectionTimeout();
        assertThat(monitor.exceededTotal()).as("느린 빌림 1 + 반납 뒤 다른 빌림의 시간 초과 1").isEqualTo(3);

        Thread other = new Thread(() -> tracker.recordConnectionAcquiredNanos(Duration.ofMillis(40).toNanos()));
        other.start();
        other.join();
        tracker.recordConnectionTimeout();
        assertThat(monitor.exceededTotal()).as("다른 스레드의 느린 빌림과 이 스레드의 시간 초과는 따로 센다").isEqualTo(5);

        tracker.recordConnectionAcquiredNanos(WARN.toNanos());
        tracker.recordConnectionTimeout();
        assertThat(monitor.exceededTotal()).as("기준 안 대기 뒤 시간 초과(기준이 획득 제한보다 긴 설정)도 1회").isEqualTo(6);
        assertThat(metrics.count(RealtimeMetrics.DB_POOL_WAIT_EXCEEDED)).isEqualTo(6.0);
    }

    @Test
    void theTrackerForwardsEveryCallSoHikariMetricsStay() {
        IMetricsTracker tracker = monitor.factory().create("realtime", stats);

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
        IMetricsTracker tracker = monitor.factory().create("realtime", stats);
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
    void thePostProcessorTouchesOnlyHikariWithoutAnExistingTracker() {
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
    void withoutRegistryOrMetricsItStillMeasuresAndANonPositiveThresholdIsRejected() {
        DbPoolWaitMonitor bare = new DbPoolWaitMonitor(WARN, provider(null), provider(null));
        IMetricsTracker tracker = bare.factory().create("realtime", stats);

        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(50).toNanos());

        assertThat(bare.exceededTotal()).isEqualTo(1);
        assertThatThrownBy(() -> new DbPoolWaitMonitor(Duration.ZERO, provider(registry), provider(metrics)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(DbPoolWaitMonitor.WARN_KEY);
        assertThatThrownBy(() -> new DbPoolWaitMonitor((Duration) null, provider(registry), provider(metrics)))
                .isInstanceOf(IllegalArgumentException.class);
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
