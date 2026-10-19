package kr.mujoong.realtime.observability.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.PoolStats;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.realtime.common.db.DbPoolWaitMonitor;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.messaging.RealtimeInboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Realtime METRIC_SNAPSHOT (U1 3.6 수정 · OD-6 · OD-12 · NFR7.8 · NFR4.15 · BT-OD-06 · J299 · J302). 가변 Clock으로 60초 경계를 넘기고
 * 실제 시간을 기다리지 않는다. 한 줄은 Realtime 몫 숫자 키만이고(사건 코드의 허용 키 안), 게이지 값은 시점 값 · 카운터는 직전 줄 이후 증가분 ·
 * 최댓값은 구간 최댓값이다.
 */
@Tag("skeleton")
class MetricSnapshotJobTest {

    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Set<String> REALTIME_KEYS = Set.of("outboxPending", "outboxOldestPendingSec", "outboxFailed",
            "inboxDead", "poolActive", "poolWaitMaxMs", "poolWaitExceeded", "openConnections", "sysCpuAvgPct",
            "sysMemMaxPct", "heapUsedPct", "sessionsActive");

    private final MutableClock clock = new MutableClock(Instant.parse("2001-05-01T00:00:00Z"));
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final RealtimeOutboxRepository outbox = mock(RealtimeOutboxRepository.class);
    private final RealtimeInboxRepository inbox = mock(RealtimeInboxRepository.class);
    private final DeviceConnectionRepository connections = mock(DeviceConnectionRepository.class);
    private final DbPoolWaitMonitor pool = new DbPoolWaitMonitor(Duration.ofMillis(100),
            new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class),
            new DefaultListableBeanFactory().getBeanProvider(RealtimeMetrics.class));
    private final FakeLoad load = new FakeLoad();
    private final MetricSnapshotJob job;

    MetricSnapshotJobTest() {
        when(outbox.backlog()).thenReturn(new RealtimeOutboxRepository.Backlog(0, Optional.empty(), 0));
        when(inbox.countDead()).thenReturn(0L);
        when(connections.countByState()).thenReturn(Map.of());
        job = new MetricSnapshotJob(clock, INTERVAL, outbox, inbox, connections, pool, metrics,
                TestEventLoggers.strict(clock, logs, metrics), load);
    }

    @Test
    void oneLineIsWrittenWhenTheIntervalHasPassedOnTheAppClock() {
        clock.advance(INTERVAL.minusMillis(1));
        job.tick();
        assertThat(snapshots()).as("59.999초에는 아직 없다").isEmpty();

        clock.advance(Duration.ofMillis(1));
        job.tick();
        job.tick();
        assertThat(snapshots()).as("60초에 한 줄, 같은 구간에 두 줄은 없다").hasSize(1);

        clock.advance(INTERVAL.multipliedBy(3));
        job.tick();
        job.tick();
        assertThat(snapshots()).as("오래 멈췄다 돌아와도 밀린 줄을 몰아 쓰지 않는다").hasSize(2);
    }

    @Test
    void theLineHoldsOnlyNumericRealtimeKeysFromTheAllowList() {
        load.cpu.add(0.25);
        load.memory.add(0.5);
        clock.advance(INTERVAL);

        job.tick();

        Map<String, Object> attrs = LogCapture.attrs(snapshots().get(0));
        assertThat(attrs.keySet()).isEqualTo(REALTIME_KEYS);
        Set<String> allowed = EventCode.METRIC_SNAPSHOT.allowedKeys().stream().map(AttrKey::key)
                .collect(Collectors.toSet());
        assertThat(allowed).as("사건 코드의 허용 키(contract-model) 안").containsAll(attrs.keySet());
        assertThat(attrs.values()).as("정수만(소수 · 문자열 없음, OD-1)")
                .allSatisfy(value -> assertThat(value).isInstanceOfAny(Integer.class, Long.class));
        assertThat(attrs).as("Core 몫 키는 쓰지 않는다").doesNotContainKeys("restTotal", "viewTotal", "breakerOpenSec");
        assertThat(snapshots().get(0).get("ownerHash")).isNull();
        assertThat(snapshots().get(0).get("level")).isEqualTo("INFO");
    }

    @Test
    void countersAreIncrementsSinceTheLastLineAndMaximaResetPerInterval() {
        HikariDataSource dataSource = new HikariDataSource();
        pool.apply(dataSource);
        IMetricsTracker tracker = dataSource.getMetricsTrackerFactory().create("realtime", stats(3));
        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(150).toNanos());
        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(250).toNanos());
        load.cpu.addAll(List.of(0.2, 0.4));
        load.memory.addAll(List.of(0.3, 0.7));
        job.tick();
        clock.advance(INTERVAL);
        job.tick();

        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(5).toNanos());
        clock.advance(INTERVAL);
        job.tick();

        Map<String, Object> first = attrs(snapshots().get(0));
        assertThat(first).containsEntry("poolWaitExceeded", 2L).containsEntry("poolWaitMaxMs", 250L)
                .containsEntry("poolActive", 3L).containsEntry("sysCpuAvgPct", 30L).containsEntry("sysMemMaxPct", 70L);
        Map<String, Object> second = attrs(snapshots().get(1));
        assertThat(second).as("다음 구간은 증가분 · 새 최댓값").containsEntry("poolWaitExceeded", 0L)
                .containsEntry("poolWaitMaxMs", 5L).doesNotContainKeys("sysCpuAvgPct", "sysMemMaxPct");
    }

    @Test
    void backlogAndConnectionStatesArePointValuesThatAlsoDriveTheGauges() {
        when(outbox.backlog()).thenReturn(new RealtimeOutboxRepository.Backlog(3,
                Optional.of(clock.now().plus(INTERVAL).minusSeconds(90)), 1));
        when(inbox.countDead()).thenReturn(2L);
        when(connections.countByState()).thenReturn(Map.of(ConnectionState.CONNECTED, 4L, ConnectionState.OFFLINE, 6L));
        metrics.connectionOpened();
        metrics.sessionStarted();
        metrics.sessionStarted();
        clock.advance(INTERVAL);

        job.tick();

        assertThat(attrs(snapshots().get(0))).containsEntry("outboxPending", 3L)
                .containsEntry("outboxOldestPendingSec", 90L).containsEntry("outboxFailed", 1L)
                .containsEntry("inboxDead", 2L).containsEntry("openConnections", 1L).containsEntry("sessionsActive", 2L);
        assertThat(metrics.gauge("mujoong.outbox.pending", "direction", RealtimeMetrics.OUTBOX_DIRECTION)).isEqualTo(3.0);
        assertThat(metrics.gauge("mujoong.outbox.oldest.pending.seconds")).isEqualTo(90.0);
        assertThat(metrics.gauge("mujoong.inbox.dead", "direction", RealtimeMetrics.INBOX_DIRECTION)).isEqualTo(2.0);
        assertThat(metrics.gauge(RealtimeMetrics.DEVICE_CONNECTION_STATE, "state", "connected")).isEqualTo(4.0);
        assertThat(metrics.gauge(RealtimeMetrics.DEVICE_CONNECTION_STATE, "state", "offline")).isEqualTo(6.0);
        assertThat(job.latestBacklog()).contains(new MetricSnapshotJob.Backlog(3, 90, 1, 2));
    }

    @Test
    void whenTheDatabaseFailsTheBacklogKeysAreLeftOutAndTheGaugesKeepTheirLastValues() {
        when(connections.countByState()).thenReturn(Map.of(ConnectionState.CONNECTED, 1L));
        job.tick();
        when(outbox.backlog()).thenThrow(new DataAccessResourceFailureException("db down"));
        when(connections.countByState()).thenThrow(new DataAccessResourceFailureException("db down"));
        clock.advance(INTERVAL);

        job.tick();

        Map<String, Object> attrs = LogCapture.attrs(snapshots().get(0));
        assertThat(attrs).doesNotContainKeys("outboxPending", "outboxOldestPendingSec", "outboxFailed", "inboxDead")
                .containsKeys("poolActive", "openConnections");
        assertThat(logs.events(EventCode.APP_ERROR)).isNotEmpty();
        assertThat(metrics.gauge(RealtimeMetrics.DEVICE_CONNECTION_STATE, "state", "connected"))
                .as("읽지 못한 표본은 게이지를 0으로 덮지 않는다").isEqualTo(1.0);
        assertThat(job.latestBacklog()).isEmpty();
    }

    @Test
    void theFinalSnapshotWritesAPartialLineAndStopsPeriodicLines() {
        clock.advance(Duration.ofSeconds(7));

        job.finalSnapshot(Duration.ofSeconds(5));
        clock.advance(INTERVAL.multipliedBy(2));
        job.tick();

        assertThat(snapshots()).as("정상 종료 T5의 마지막 한 줄뿐(부분 구간)").hasSize(1);
        assertThat(LogCapture.attrs(snapshots().get(0))).containsKeys("outboxPending", "openConnections");
    }

    /**
     * 3.6 수정 J369: 마지막 줄의 적체 읽기는 남은 시간(limit)까지만 기다린다. DB가 응답하지 않아도 limit 뒤 줄을 쓰고, 못 읽은 적체 네 키는
     * 빼며(기존 규칙) APP_ERROR({@value MetricSnapshotJob#FINAL_READ_TIMEOUT}) 한 줄을 남긴다. 늦게 끝난 읽기는 줄을 더 쓰지 않는다.
     */
    @Test
    void theLastLineDoesNotWaitForAStuckBacklogReadPastTheTimeLeft() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch lateReadDone = new CountDownLatch(1);
        when(outbox.backlog()).thenAnswer(call -> {
            try {
                release.await(10, TimeUnit.SECONDS);
                return new RealtimeOutboxRepository.Backlog(0, Optional.empty(), 0);
            } finally {
                lateReadDone.countDown();
            }
        });
        Duration limit = Duration.ofMillis(200);
        long begun = System.nanoTime();

        job.finalSnapshot(limit);

        assertThat(Duration.ofNanos(System.nanoTime() - begun)).as("남은 시간 + 여유 안").isLessThan(limit.plusSeconds(2));
        assertThat(LogCapture.attrs(snapshots().get(0)))
                .doesNotContainKeys("outboxPending", "outboxOldestPendingSec", "outboxFailed", "inboxDead")
                .containsKeys("poolActive", "poolWaitExceeded", "openConnections", "sessionsActive");
        assertThat(logs.events(EventCode.APP_ERROR)).extracting(line -> LogCapture.attrs(line).get("errorKind"))
                .containsExactly(MetricSnapshotJob.FINAL_READ_TIMEOUT);
        release.countDown();
        assertThat(lateReadDone.await(10, TimeUnit.SECONDS)).isTrue();
        clock.advance(INTERVAL.multipliedBy(2));
        job.tick();
        assertThat(snapshots()).as("마지막 줄 뒤에는 줄이 없다").hasSize(1);
    }

    /** 3.6 수정 J369 경계: 남은 시간이 없으면 DB를 부르지 않고 메모리 값만으로 마지막 줄을 쓴다. */
    @Test
    void withNoTimeLeftTheLastLineSkipsTheDatabaseAndKeepsTheInMemoryKeys() {
        metrics.connectionOpened();
        clock.advance(Duration.ofSeconds(3));

        job.finalSnapshot(Duration.ZERO);

        verify(outbox, never()).backlog();
        verify(connections, never()).countByState();
        assertThat(attrs(snapshots().get(0))).containsEntry("openConnections", 1L).containsKey("poolWaitExceeded")
                .doesNotContainKeys("outboxPending", "inboxDead");
        assertThat(logs.events(EventCode.APP_ERROR)).extracting(line -> LogCapture.attrs(line).get("errorKind"))
                .containsExactly(MetricSnapshotJob.FINAL_READ_TIMEOUT);
    }

    @Test
    void aNonPositiveIntervalIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MetricSnapshotJob(clock, Duration.ZERO, outbox,
                inbox, connections, pool, metrics, TestEventLoggers.strict(clock, logs, metrics), load))
                .isInstanceOf(IllegalArgumentException.class);
        // U3(BR4.8 ①)가 UNSTABLE 값을 더했다 — 다섯 상태 모두 게이지 라벨이다
        assertThat(EnumSet.copyOf(RealtimeMetrics.CONNECTION_STATES)).isEqualTo(EnumSet.complementOf(
                EnumSet.of(ConnectionState.UNRECOGNIZED)));
    }

    private List<Map<String, Object>> snapshots() {
        return logs.events(EventCode.METRIC_SNAPSHOT);
    }

    /** attrs(숫자는 long으로 맞춘다 — JSON 읽기는 작은 수를 int로 준다, Core 시험과 같은 방식). */
    private static Map<String, Object> attrs(Map<String, Object> line) {
        Map<String, Object> converted = new LinkedHashMap<>();
        LogCapture.attrs(line).forEach((key, value) -> converted.put(key,
                value instanceof Number number ? (Object) number.longValue() : value));
        return converted;
    }

    private static PoolStats stats(int active) {
        return new PoolStats(0) {
            @Override
            protected void update() {
                this.activeConnections = active;
            }
        };
    }

    /** 고정 값 표본(넣은 순서대로 하나씩, 다 쓰면 빈 값). */
    private static final class FakeLoad implements SystemLoad {

        private final Deque<Double> cpu = new ArrayDeque<>();
        private final Deque<Double> memory = new ArrayDeque<>();

        @Override
        public OptionalDouble cpu() {
            return cpu.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(cpu.poll());
        }

        @Override
        public OptionalDouble memory() {
            return memory.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(memory.poll());
        }

        @Override
        public OptionalDouble heap() {
            return OptionalDouble.of(0.42);
        }
    }
}
