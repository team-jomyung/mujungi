package kr.mujoong.core.common.db;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import kr.mujoong.core.observability.CoreMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 풀 연결 획득 대기 측정 (U1 3.6 수정 · NFR7.6 · OD-5 · OD-6 · SCD-5 · J323).
 *
 * <p>앱 DataSource(Hikari)의 지표 추적기에 끼어들어 연결을 빌릴 때마다 대기 시간을 본다. {@code mujoong.metrics.db-pool-wait-warn}(100ms)을
 * 넘으면 {@code mujoong_db_pool_wait_exceeded_total}을 올리고, 획득 시간 초과(풀 고갈)도 넘은 것으로 센다. METRIC_SNAPSHOT의 구간 값
 * (poolWaitMaxMs · poolWaitExceeded · poolActive)도 여기서 모은다.
 *
 * <p>설계(observability-design.md 2.5)는 'DataSource 감싸개'라 적었지만 DataSource 빈 자체를 감싸면 빈 타입이 Hikari가 아니게 되어 연결 상한
 * (DbSessionLimits) · Boot의 Hikari 지표 연결이 감싸개를 벗겨야 한다. 그래서 같은 값(Hikari가 재는 빌림 대기, {@code
 * hikaricp_connections_acquire_seconds}와 같은 측정점)을 Hikari 지표 추적기 자리에서 읽는다(J323). 추적기 자리는 하나뿐이라 Boot가 걸던
 * Micrometer 추적기를 안에 품어 {@code hikaricp_*} 지표를 그대로 낸다. 빈 후처리기라 다른 빈을 미리 만들지 않게 레지스트리 · 지표는 쓸 때
 * 찾는다.
 *
 * <p>빌림 1회 = 판정 1회(3.6 수정 J364). HikariCP 7.0.2는 빌림 시간 초과 한 번에 빌리던 스레드에서 대기 기록
 * ({@code recordConnectionAcquiredNanos}, 값 ≈ 획득 제한)을 부른 바로 뒤 시간 초과 기록({@code recordConnectionTimeout})을 부른다
 * (HikariPool.getConnection의 recordBorrowTimeoutStats → createTimeoutException, 둘 사이에 다른 추적기 호출 없음). 그래서 시간 초과는 같은
 * 스레드의 바로 앞 기록이 기준을 넘겨 이미 센 대기일 때 그 빌림의 것으로 보고 다시 세지 않는다. 그 밖(대기 기록 없이 시간 초과만 부르는 판 ·
 * 앞 기록이 기준 안 대기 · 반납 · 다른 스레드)은 시간 초과를 센다. 한 스레드의 앞뒤 두 빌림 사이에는 보통 반납 기록이 끼므로 앞 빌림의 대기가
 * 뒤 빌림의 시간 초과를 흡수하지 않는다. 예외는 연결을 쥔 채 같은 스레드에서 또 빌리는 중첩 빌림이 대기 기록 없는 판에서 시간 초과로 끝날
 * 때뿐이다(7.0.2는 대기 기록을 함께 부르므로 해당 없고, U1 운영 코드에는 REQUIRES_NEW · 직접 getConnection이 없다).
 */
@Component
public class DbPoolWaitMonitor implements BeanPostProcessor {

    /** 경고 기준 키(NFR7.6). */
    public static final String WARN_KEY = "mujoong.metrics.db-pool-wait-warn";

    private final long warnNanos;
    private final ObjectProvider<MeterRegistry> registries;
    private final ObjectProvider<CoreMetrics> metrics;
    private final AtomicLong exceededTotal = new AtomicLong();
    private final AtomicLong windowMaxWaitNanos = new AtomicLong();
    private final AtomicLong windowMaxActive = new AtomicLong();
    /** 이 스레드의 바로 앞 추적기 기록이 기준을 넘겨 이미 센 대기였는가(시간 초과와 짝 맞추기, J364). */
    private final ThreadLocal<Boolean> countedWaitJustBefore = new ThreadLocal<>();
    private volatile PoolStats stats;
    private volatile long connectionTimeoutNanos;

    /** 운영: 기준은 설정 키에서 읽는다. 키가 없거나 0 이하면 기동이 실패한다. */
    @Autowired
    public DbPoolWaitMonitor(
            Environment environment, ObjectProvider<MeterRegistry> registries, ObjectProvider<CoreMetrics> metrics) {
        this(Binder.get(environment).bind(WARN_KEY, Duration.class)
                .orElseThrow(() -> new IllegalStateException("필수 설정 키가 없다: " + WARN_KEY)), registries, metrics);
    }

    public DbPoolWaitMonitor(
            Duration warn, ObjectProvider<MeterRegistry> registries, ObjectProvider<CoreMetrics> metrics) {
        if (warn == null || warn.isZero() || warn.isNegative()) {
            throw new IllegalArgumentException("설정 키는 0보다 커야 한다: " + WARN_KEY);
        }
        this.warnNanos = warn.toNanos();
        this.registries = Objects.requireNonNull(registries, "registries");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** 앱 Hikari DataSource에 추적기를 건다. 다른 추적기 · 지표 저장소가 이미 있으면 덮지 않는다(숨은 덮어쓰기 방지). */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource dataSource) {
            apply(dataSource);
        }
        return bean;
    }

    /** {@code dataSource}에 대기 측정 추적기를 건다. 풀이 연결을 열기 전에 불러야 한다. */
    public void apply(HikariDataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        if (dataSource.getMetricsTrackerFactory() != null || dataSource.getMetricRegistry() != null) {
            return;
        }
        connectionTimeoutNanos = Duration.ofMillis(dataSource.getConnectionTimeout()).toNanos();
        dataSource.setMetricsTrackerFactory(factory());
    }

    /** Micrometer 추적기를 품은 추적기 공장(레지스트리가 없는 부분 문맥이면 대기 측정만 한다). */
    MetricsTrackerFactory factory() {
        return (poolName, poolStats) -> {
            this.stats = poolStats;
            MeterRegistry registry = registries.getIfAvailable();
            IMetricsTracker delegate = registry == null
                    ? new IMetricsTracker() { }
                    : new MicrometerMetricsTrackerFactory(registry).create(poolName, poolStats);
            return new Tracker(delegate);
        };
    }

    /** 빌림 한 번의 대기(나노초)를 반영한다. */
    void recordWait(long waitNanos) {
        long wait = Math.max(0L, waitNanos);
        windowMaxWaitNanos.accumulateAndGet(wait, Math::max);
        PoolStats current = stats;
        if (current != null) {
            windowMaxActive.accumulateAndGet(current.getActiveConnections(), Math::max);
        }
        if (wait > warnNanos) {
            exceeded();
            countedWaitJustBefore.set(Boolean.TRUE);
        } else {
            countedWaitJustBefore.remove();
        }
    }

    /**
     * 획득 시간 초과(풀 고갈): 기준을 넘은 것이고 대기 최댓값은 획득 제한(connection-timeout)이다. 같은 스레드의 바로 앞 기록이 이미 센 대기면
     * 같은 빌림이라 다시 세지 않는다(J364).
     */
    void recordTimeout() {
        windowMaxWaitNanos.accumulateAndGet(connectionTimeoutNanos, Math::max);
        boolean sameBorrowAlreadyCounted = Boolean.TRUE.equals(countedWaitJustBefore.get());
        countedWaitJustBefore.remove();
        if (!sameBorrowAlreadyCounted) {
            exceeded();
        }
    }

    /** 대기 · 시간 초과가 아닌 추적기 기록(생성 · 반납): 이 스레드의 앞 빌림과 뒤 시간 초과 사이를 끊는다(J364). */
    void recordOther() {
        countedWaitJustBefore.remove();
    }

    private void exceeded() {
        exceededTotal.incrementAndGet();
        metrics.ifAvailable(CoreMetrics::dbPoolWaitExceeded);
    }

    /** 앱 시작 뒤 기준 초과 누계. */
    public long exceededTotal() {
        return exceededTotal.get();
    }

    /** 지금 풀이 빌려준 연결 수(풀이 아직 없으면 0). 표본 때도 구간 최댓값에 넣는다. */
    public int sampleActive() {
        PoolStats current = stats;
        int active = current == null ? 0 : current.getActiveConnections();
        windowMaxActive.accumulateAndGet(active, Math::max);
        return active;
    }

    /** 구간 값(직전 호출 뒤 대기 최댓값 · 사용 연결 최댓값)을 읽고 비운다(METRIC_SNAPSHOT). */
    public Window drainWindow() {
        long maxWait = windowMaxWaitNanos.getAndSet(0L);
        long maxActive = windowMaxActive.getAndSet(0L);
        return new Window(Duration.ofNanos(maxWait), maxActive);
    }

    /** 구간 값. */
    public record Window(Duration maxWait, long maxActive) {
    }

    /** Micrometer 추적기에 모두 넘기고 빌림 대기만 따로 본다. */
    private final class Tracker implements IMetricsTracker {

        private final IMetricsTracker delegate;

        Tracker(IMetricsTracker delegate) {
            this.delegate = delegate;
        }

        @Override
        public void recordConnectionCreatedMillis(long connectionCreatedMillis) {
            delegate.recordConnectionCreatedMillis(connectionCreatedMillis);
            recordOther();
        }

        @Override
        public void recordConnectionAcquiredNanos(long elapsedAcquiredNanos) {
            delegate.recordConnectionAcquiredNanos(elapsedAcquiredNanos);
            recordWait(elapsedAcquiredNanos);
        }

        @Override
        public void recordConnectionUsageMillis(long elapsedBorrowedMillis) {
            delegate.recordConnectionUsageMillis(elapsedBorrowedMillis);
            recordOther();
        }

        @Override
        public void recordConnectionTimeout() {
            delegate.recordConnectionTimeout();
            recordTimeout();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
