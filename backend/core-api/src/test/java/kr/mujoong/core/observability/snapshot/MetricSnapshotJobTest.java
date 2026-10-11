package kr.mujoong.core.observability.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.PoolStats;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.activity.SessionStartBreaker;
import kr.mujoong.core.common.db.DbPoolWaitMonitor;
import kr.mujoong.core.messaging.CoreInboxRepository;
import kr.mujoong.core.messaging.CoreOutboxRepository;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Core METRIC_SNAPSHOT (U1 3.6 수정 · OD-6 · OD-12 · NFR7.8 · J322 · J325 · J326). 가변 Clock으로 60초 경계를 넘기고 실제 시간을 기다리지
 * 않는다. 한 줄은 Core 몫 숫자 키만이고, 게이지 값은 시점 값 · 카운터는 직전 줄 이후 증가분 · 최댓값은 구간 최댓값이다.
 */
@Tag("u1")
class MetricSnapshotJobTest {

    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Set<String> CORE_KEYS = Set.of("outboxPending", "outboxOldestPendingSec", "outboxFailed",
            "inboxDead", "poolActive", "poolWaitMaxMs", "poolWaitExceeded", "restTotal", "restLe300Ms", "viewTotal",
            "viewLe500Ms", "viewFallback", "sysCpuAvgPct", "sysMemMaxPct", "heapUsedPct", "breakerOpenSec");

    private final MutableClock clock = new MutableClock(Instant.parse("2001-05-01T00:00:00Z"));
    private final TestEvents events = new TestEvents(clock);
    private final CoreMetrics metrics = events.metrics();
    private final CoreOutboxRepository outbox = mock(CoreOutboxRepository.class);
    private final CoreInboxRepository inbox = mock(CoreInboxRepository.class);
    private final DbPoolWaitMonitor pool = new DbPoolWaitMonitor(Duration.ofMillis(100),
            new DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
            new DefaultListableBeanFactory().getBeanProvider(CoreMetrics.class));
    private final SessionStartBreaker breaker = new SessionStartBreaker(clock, metrics, 1, Duration.ofSeconds(5));
    private final FakeLoad load = new FakeLoad();
    private final MetricSnapshotJob job;

    MetricSnapshotJobTest() {
        when(outbox.backlog()).thenReturn(new CoreOutboxRepository.Backlog(0, Optional.empty(), 0));
        when(inbox.countDead()).thenReturn(0L);
        job = new MetricSnapshotJob(clock, INTERVAL, outbox, inbox, pool, breaker, metrics, events.logger(), load);
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

        clock.advance(INTERVAL);
        job.tick();
        assertThat(snapshots()).hasSize(2);
    }

    @Test
    void theLineHoldsOnlyNumericCoreKeys() {
        load.cpu.add(0.25);
        load.memory.add(0.5);
        clock.advance(INTERVAL);

        job.tick();

        Map<String, Object> attrs = LogCapture.attrs(snapshots().get(0));
        assertThat(attrs.keySet()).isEqualTo(CORE_KEYS);
        assertThat(attrs.values()).as("정수만(소수 · 문자열 없음, OD-1)")
                .allSatisfy(value -> assertThat(value).isInstanceOfAny(Integer.class, Long.class));
        assertThat(snapshots().get(0).get("ownerHash")).isNull();
        assertThat(attrs).as("Realtime 몫 키는 쓰지 않는다").doesNotContainKeys("openConnections", "sessionsActive");
    }

    @Test
    void countersAreIncrementsSinceTheLastLineAndMaximaResetPerInterval() {
        HikariDataSource dataSource = new HikariDataSource();
        pool.apply(dataSource);
        IMetricsTracker tracker = dataSource.getMetricsTrackerFactory().create("core-api", stats(3));
        metrics.restRequest(Duration.ofMillis(100));
        metrics.restRequest(Duration.ofMillis(900));
        metrics.viewRequest(Duration.ofMillis(200));
        metrics.sessionViewFallback();
        tracker.recordConnectionAcquiredNanos(Duration.ofMillis(150).toNanos());
        clock.advance(INTERVAL);
        job.tick();

        metrics.restRequest(Duration.ofMillis(10));
        clock.advance(INTERVAL);
        job.tick();

        Map<String, Object> first = attrs(snapshots().get(0));
        assertThat(first).containsEntry("restTotal", 2L).containsEntry("restLe300Ms", 1L).containsEntry("viewTotal", 1L)
                .containsEntry("viewLe500Ms", 1L).containsEntry("viewFallback", 1L).containsEntry("poolWaitExceeded", 1L)
                .containsEntry("poolWaitMaxMs", 150L).containsEntry("poolActive", 3L);
        Map<String, Object> second = attrs(snapshots().get(1));
        assertThat(second).containsEntry("restTotal", 1L).containsEntry("restLe300Ms", 1L).containsEntry("viewTotal", 0L)
                .containsEntry("viewFallback", 0L).containsEntry("poolWaitExceeded", 0L).containsEntry("poolWaitMaxMs", 0L);
    }

    @Test
    void backlogIsAPointValueThatAlsoDrivesTheGauges() {
        when(outbox.backlog()).thenReturn(new CoreOutboxRepository.Backlog(3,
                Optional.of(clock.now().plus(INTERVAL).minusSeconds(90)), 1));
        when(inbox.countDead()).thenReturn(2L);
        clock.advance(INTERVAL);

        job.tick();

        assertThat(attrs(snapshots().get(0))).containsEntry("outboxPending", 3L).containsEntry("outboxOldestPendingSec", 90L)
                .containsEntry("outboxFailed", 1L).containsEntry("inboxDead", 2L);
        assertThat(metrics.gauge("mujoong.outbox.pending", "direction", CoreMetrics.OUTBOX_DIRECTION)).isEqualTo(3.0);
        assertThat(metrics.gauge("mujoong.outbox.oldest.pending.seconds", "direction", CoreMetrics.OUTBOX_DIRECTION))
                .isEqualTo(90.0);
        assertThat(metrics.gauge("mujoong.inbox.dead", "direction", CoreMetrics.INBOX_DIRECTION)).isEqualTo(2.0);
        assertThat(job.latestBacklog()).contains(new MetricSnapshotJob.Backlog(3, 90, 1, 2));
    }

    @Test
    void whenTheDatabaseFailsTheFourBacklogKeysAreLeftOutAndAnAppErrorIsLogged() {
        when(outbox.backlog()).thenThrow(new DataAccessResourceFailureException("db down"));
        clock.advance(INTERVAL);

        job.tick();

        Map<String, Object> attrs = attrs(snapshots().get(0));
        assertThat(attrs).doesNotContainKeys("outboxPending", "outboxOldestPendingSec", "outboxFailed", "inboxDead")
                .containsKey("poolActive");
        assertThat(events.capture().events(EventCode.APP_ERROR)).isNotEmpty();
        assertThat(job.latestBacklog()).isEmpty();
    }

    @Test
    void cpuIsTheAverageAndMemoryTheMaximumOfTheSamplesInTheInterval() {
        load.cpu.addAll(List.of(0.10, 0.30, 0.20));
        load.memory.addAll(List.of(0.40, 0.70, 0.50));
        load.heap = OptionalDouble.of(0.333);

        job.tick();
        job.tick();
        clock.advance(INTERVAL);
        job.tick();

        assertThat(attrs(snapshots().get(0))).containsEntry("sysCpuAvgPct", 20L).containsEntry("sysMemMaxPct", 70L)
                .containsEntry("heapUsedPct", 33L);
    }

    @Test
    void missingSystemValuesAreLeftOutInsteadOfWritten() {
        load.heap = OptionalDouble.empty();
        clock.advance(INTERVAL);

        job.tick();

        assertThat(attrs(snapshots().get(0))).doesNotContainKeys("sysCpuAvgPct", "sysMemMaxPct", "heapUsedPct");
    }

    @Test
    void breakerOpenSecondsCountOnlyTheOpenTimeInsideEachInterval() {
        clock.advance(Duration.ofSeconds(57));
        breaker.onFailure(breaker.acquire());
        clock.advance(Duration.ofSeconds(3));
        job.tick();
        clock.advance(INTERVAL);
        job.tick();

        assertThat(attrs(snapshots().get(0))).containsEntry("breakerOpenSec", 3L);
        assertThat(attrs(snapshots().get(1))).as("열림 5초 중 나머지 2초만, 그 뒤 반열림은 세지 않는다")
                .containsEntry("breakerOpenSec", 2L);
    }

    @Test
    void finalSnapshotWritesAPartialLineAndNoMoreLinesFollow() {
        clock.advance(Duration.ofSeconds(10));
        metrics.restRequest(Duration.ofMillis(5));

        job.finalSnapshot(Duration.ofSeconds(5));
        clock.advance(INTERVAL.multipliedBy(3));
        job.tick();

        assertThat(snapshots()).hasSize(1);
        assertThat(attrs(snapshots().get(0))).containsEntry("restTotal", 1L);
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
                return new CoreOutboxRepository.Backlog(0, Optional.empty(), 0);
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
                .containsKeys("poolActive", "poolWaitExceeded", "restTotal", "breakerOpenSec");
        assertThat(events.capture().events(EventCode.APP_ERROR)).extracting(line -> LogCapture.attrs(line).get("errorKind"))
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
        metrics.restRequest(Duration.ofMillis(5));
        clock.advance(Duration.ofSeconds(3));

        job.finalSnapshot(Duration.ZERO);

        verify(outbox, never()).backlog();
        verify(inbox, never()).countDead();
        assertThat(attrs(snapshots().get(0))).containsEntry("restTotal", 1L).containsKey("poolWaitExceeded")
                .doesNotContainKeys("outboxPending", "inboxDead");
        assertThat(events.capture().events(EventCode.APP_ERROR)).extracting(line -> LogCapture.attrs(line).get("errorKind"))
                .containsExactly(MetricSnapshotJob.FINAL_READ_TIMEOUT);
    }

    @Test
    void aLongPauseDoesNotBurstTheMissedLines() {
        clock.advance(INTERVAL.multipliedBy(5));
        job.tick();
        job.tick();
        assertThat(snapshots()).hasSize(1);

        clock.advance(INTERVAL.minusSeconds(1));
        job.tick();
        assertThat(snapshots()).as("다음 줄은 밀린 경계가 아니라 지금부터 한 주기 뒤").hasSize(1);
        clock.advance(Duration.ofSeconds(1));
        job.tick();
        assertThat(snapshots()).hasSize(2);
    }

    private List<Map<String, Object>> snapshots() {
        return events.capture().events(EventCode.METRIC_SNAPSHOT);
    }

    /** attrs(숫자는 long으로 맞춘다 — JSON 읽기는 작은 수를 int로 준다). */
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

    /** 표본을 차례로 내주는 대역(다 쓰면 빈 값). */
    private static final class FakeLoad implements SystemLoad {

        private final Deque<Double> cpu = new ArrayDeque<>();
        private final Deque<Double> memory = new ArrayDeque<>();
        private OptionalDouble heap = OptionalDouble.of(0.5);

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
            return heap;
        }
    }
}
