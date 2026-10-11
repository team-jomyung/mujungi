package kr.mujoong.realtime.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.config.RealtimePropertiesConfig;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.gateway.ConnectionStateWriter;
import kr.mujoong.realtime.gateway.DisconnectChecker;
import kr.mujoong.realtime.gateway.StatusReporter;
import kr.mujoong.realtime.health.DbHealthChecker;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.messaging.ControlEventRearm;
import kr.mujoong.realtime.messaging.MessagingCleanupJob;
import kr.mujoong.realtime.messaging.RealtimeInboxDispatcher;
import kr.mujoong.realtime.messaging.RealtimeInboxRepoller;
import kr.mujoong.realtime.messaging.RealtimeInboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxSender;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.observability.snapshot.MetricSnapshotJob;
import kr.mujoong.realtime.runtime.PeriodicJobs;
import kr.mujoong.realtime.session.OrphanSessionChecker;
import kr.mujoong.realtime.session.TombstoneMap;
import kr.mujoong.realtime.session.TransitionService;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Realtime 기동 순서와 주기 작업 스레드 배치 (U1 3.6 수정 리뷰 반영 · RD-12 · RD-16 · OD-6 · R3-18 · J368).
 *
 * <p>기동은 S1b 사전 확인 → S2 → S2b → S3a · S3b · S3c · S3d · S3f → S3e 한 줄 → S4 주기 작업 → S6 READY 순서다. METRIC_SNAPSHOT 표본은
 * 전용 스레드({@value #SAMPLE_THREAD})에서 돌아 DB 읽기에 막혀도 maintenance 스레드의 DB 점검자 · 끊김 점검 · 상태 보고가 제 주기로 돈다
 * (리뷰: 같은 스레드면 DB 정지 때 표본마다 약 6초씩 점검이 밀렸다). 실제 스레드로 돌리고 시간 키는 test 프로필 값(DB 점검 200ms · 표본
 * 500ms)이다.
 */
@Tag("u1")
class RealtimeStartupSequenceTest {

    private static final RealtimeProperties PROPERTIES = testProperties();
    /** 막힌 표본이 스스로 풀리기까지(시험이 먼저 푼다). */
    private static final Duration STUCK = Duration.ofSeconds(30);
    /** 표본 전용 스레드 이름(스레드 덤프에서 보이는 운영 이름이라 글자 그대로 고정한다). */
    private static final String SAMPLE_THREAD = "metric-sample";

    private final MutableClock clock = new MutableClock(Instant.parse("2001-10-01T00:00:00Z"));
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final EventLogger events = spy(TestEventLoggers.strict(clock, logs, metrics));
    private final ReadinessState readiness = new ReadinessState(event -> { }, metrics,
            PROPERTIES.health().dbFailureThreshold(), PROPERTIES.health().dbCheckInterval());
    private final RealtimeStartupChecks startupChecks = mock(RealtimeStartupChecks.class);
    private final SchemaVersionCheck schemaCheck = mock(SchemaVersionCheck.class);
    private final DataClassCheck dataClassCheck = mock(DataClassCheck.class);
    private final TransitionService transitions = mock(TransitionService.class);
    private final ConnectionStateWriter connectionWriter = mock(ConnectionStateWriter.class);
    private final RealtimeInboxRepository inbox = mock(RealtimeInboxRepository.class);
    private final RealtimeOutboxRepository outbox = mock(RealtimeOutboxRepository.class);
    private final ControlEventRearm rearm = mock(ControlEventRearm.class);
    private final RealtimeOutboxSender sender = mock(RealtimeOutboxSender.class);
    private final RealtimeInboxDispatcher dispatcher = mock(RealtimeInboxDispatcher.class);
    private final DisconnectChecker disconnectChecker = mock(DisconnectChecker.class);
    private final StatusReporter statusReporter = mock(StatusReporter.class);
    private final MetricSnapshotJob snapshots = mock(MetricSnapshotJob.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<RealtimeStartupSequence> sequences = new ArrayList<>();

    RealtimeStartupSequenceTest() {
        when(startupChecks.dictionary()).thenReturn(SeedDictionary.loadDefault());
    }

    @AfterEach
    void stopJobs() {
        release.countDown();
        sequences.forEach(sequence -> sequence.stopJobs(Duration.ofSeconds(2)));
    }

    @Test
    void aSampleStuckOnTheDatabaseDoesNotHoldBackTheDbCheckerSoNotReadyComesOnTime() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        when(jdbc.queryForObject("select 1", Integer.class)).thenAnswer(call -> {
            checks.incrementAndGet();
            throw new DataAccessResourceFailureException("db down");
        });
        CountDownLatch sampling = stuckSamples();
        Duration expected = PROPERTIES.health().dbCheckInterval().multipliedBy(PROPERTIES.health().dbFailureThreshold());
        long begun = System.nanoTime();

        sequence().start();

        assertThat(sampling.await(10, TimeUnit.SECONDS)).as("표본이 DB 읽기에서 막혔다").isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (readiness.state() != ReadinessState.State.NOT_READY && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - begun);
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.NOT_READY);
        assertThat(release.getCount()).as("NOT_READY가 될 때 표본은 아직 막혀 있다").isEqualTo(1);
        assertThat(checks.get()).isGreaterThanOrEqualTo(PROPERTIES.health().dbFailureThreshold());
        assertThat(took).as("연속 실패 3회 × 점검 주기 + 여유 1초").isLessThan(expected.plusSeconds(1));
    }

    @Test
    void theSnapshotSampleRunsOnItsOwnThreadWhileTheChecksStayOnMaintenance() throws Exception {
        Map<String, Set<String>> threads = new ConcurrentHashMap<>();
        CountDownLatch seen = new CountDownLatch(4);
        doAnswer(call -> record(threads, "sample", seen)).when(snapshots).tick();
        when(jdbc.queryForObject("select 1", Integer.class)).thenAnswer(call -> {
            record(threads, "dbCheck", seen);
            return 1;
        });
        when(disconnectChecker.runOnce()).thenAnswer(call -> {
            record(threads, "disconnect", seen);
            return 0;
        });
        when(statusReporter.runOnce()).thenAnswer(call -> {
            record(threads, "statusReport", seen);
            return null;
        });

        sequence().start();

        assertThat(seen.await(10, TimeUnit.SECONDS)).as("네 작업이 한 번 이상 돌았다").isTrue();
        assertThat(threads.get("sample")).containsExactly(SAMPLE_THREAD);
        assertThat(threads.get("dbCheck")).containsExactly(PeriodicJobs.MAINTENANCE);
        assertThat(threads.get("disconnect")).containsExactly(PeriodicJobs.MAINTENANCE);
        assertThat(threads.get("statusReport")).containsExactly(PeriodicJobs.MAINTENANCE);
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.READY);
    }

    @Test
    void aFailingSampleLeavesAnAppErrorAndTheNextSampleStillRuns() throws Exception {
        CountDownLatch twoSamples = new CountDownLatch(2);
        doAnswer(call -> {
            twoSamples.countDown();
            throw new IllegalStateException("snapshot failed");
        }).when(snapshots).tick();
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);

        sequence().start();

        assertThat(twoSamples.await(10, TimeUnit.SECONDS)).as("실패한 표본 뒤에도 다음 표본이 돈다").isTrue();
        assertThat(logs.events(EventCode.APP_ERROR)).extracting(line -> LogCapture.attrs(line).get("errorKind"))
                .contains("IllegalStateException");
        assertThat(readiness.state()).as("표본 실패는 준비 상태와 무관하다").isEqualTo(ReadinessState.State.READY);
    }

    @Test
    void t4StopsTheSampleThreadTooAndAStuckSampleCannotHoldItPastItsLimit() throws Exception {
        AtomicInteger samples = new AtomicInteger();
        CountDownLatch sampling = new CountDownLatch(1);
        doAnswer(call -> {
            samples.incrementAndGet();
            sampling.countDown();
            release.await(STUCK.toSeconds(), TimeUnit.SECONDS);
            return null;
        }).when(snapshots).tick();
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        RealtimeStartupSequence sequence = sequence();
        sequence.start();
        assertThat(sampling.await(10, TimeUnit.SECONDS)).isTrue();
        Duration limit = Duration.ofMillis(300);
        long begun = System.nanoTime();

        sequence.stopJobs(limit);

        assertThat(Duration.ofNanos(System.nanoTime() - begun)).as("막힌 표본은 T4 한도까지만 기다린다")
                .isLessThan(limit.plusSeconds(1));
        release.countDown();
        Thread.sleep(PROPERTIES.observability().systemSampleInterval().multipliedBy(3).toMillis());
        assertThat(samples).as("정지 뒤 새 표본은 돌지 않는다").hasValue(1);
        assertThat(sequence.isRunning()).isFalse();
        verify(sender).requestStop();
        verify(dispatcher).shutdown();
    }

    @Test
    void startupChecksThenRecoversThenStartsTheJobsAndOnlyThenBecomesReady() {
        when(transitions.failInProgressAfterRestart()).thenReturn(2);
        when(connectionWriter.resetAfterRestart()).thenReturn(3);
        when(inbox.pullForwardReceived(clock.now())).thenReturn(4);
        when(rearm.rearmOnStart(clock.now())).thenReturn(1);
        when(outbox.releaseClaims(clock.now())).thenReturn(5);
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        RealtimeStartupSequence sequence = sequence();

        sequence.start();

        InOrder order = inOrder(startupChecks, schemaCheck, dataClassCheck, transitions, connectionWriter, inbox, rearm,
                outbox);
        order.verify(startupChecks).dictionary();
        order.verify(schemaCheck).verify();
        order.verify(dataClassCheck).verify();
        order.verify(transitions).failInProgressAfterRestart();
        order.verify(connectionWriter).resetAfterRestart();
        order.verify(inbox).pullForwardReceived(clock.now());
        order.verify(rearm).rearmOnStart(clock.now());
        order.verify(outbox).releaseClaims(clock.now());
        assertThat(logs.events(EventCode.STARTUP_RECOVERY)).singleElement().satisfies(line -> {
            Map<String, Object> attrs = LogCapture.attrs(line);
            assertThat(((Number) attrs.get("recoveredSessions")).intValue()).isEqualTo(2);
            assertThat(((Number) attrs.get("resetConnections")).intValue()).isEqualTo(3);
            assertThat(((Number) attrs.get("reopenedInboxRows")).intValue()).isEqualTo(4);
            assertThat(((Number) attrs.get("rearmedControlEvents")).intValue()).isEqualTo(1);
        });
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.READY);
        assertThat(sequence.isRunning()).isTrue();
        assertThat(sequence.getPhase()).as("웹 서버 시작(MAX − 2048)보다 먼저").isLessThan(Integer.MAX_VALUE - 2048);
    }

    @Test
    void aSchemaCheckFailureEndsStartupBeforeAnyRecoveryJobOrReadiness() {
        when(schemaCheck.verify()).thenThrow(new IllegalStateException("flyway.realtime_schema_history 버전 0 < 기대 1"));
        RealtimeStartupSequence sequence = sequence();

        assertThatThrownBy(sequence::start).isInstanceOf(IllegalStateException.class);

        verify(dataClassCheck, never()).verify();
        verify(transitions, never()).failInProgressAfterRestart();
        verify(outbox, never()).releaseClaims(clock.now());
        verify(snapshots, never()).tick();
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.STARTING);
        assertThat(sequence.isRunning()).isFalse();
    }

    /** 협력 객체는 대역이고 DB 점검자만 실제(대역 JdbcTemplate)인 기동 순서. */
    private RealtimeStartupSequence sequence() {
        DbHealthChecker checker = new DbHealthChecker(jdbc, readiness, events);
        RealtimeStartupSequence sequence = new RealtimeStartupSequence(startupChecks, schemaCheck, dataClassCheck,
                transitions, connectionWriter, inbox, outbox, rearm, sender, mock(RealtimeInboxRepoller.class), dispatcher,
                mock(AsyncRecorder.class), mock(LatencyRecorder.class), checker, disconnectChecker, statusReporter,
                mock(OrphanSessionChecker.class), mock(TombstoneMap.class), mock(MessagingCleanupJob.class), snapshots,
                events, readiness, clock, PROPERTIES);
        sequences.add(sequence);
        return sequence;
    }

    /** 표본마다 DB 읽기에 막힌 것처럼 {@link #release}까지 기다린다. 첫 표본이 막히면 돌려준 래치가 열린다. */
    private CountDownLatch stuckSamples() {
        CountDownLatch sampling = new CountDownLatch(1);
        doAnswer(call -> {
            sampling.countDown();
            release.await(STUCK.toSeconds(), TimeUnit.SECONDS);
            return null;
        }).when(snapshots).tick();
        return sampling;
    }

    private static Object record(Map<String, Set<String>> threads, String job, CountDownLatch seen) {
        if (threads.computeIfAbsent(job, key -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread().getName())) {
            seen.countDown();
        }
        return null;
    }

    private static RealtimeProperties testProperties() {
        AtomicReference<RealtimeProperties> properties = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RealtimePropertiesConfig.class)
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> properties.set(context.getBean(RealtimeProperties.class)));
        return properties.get();
    }
}
