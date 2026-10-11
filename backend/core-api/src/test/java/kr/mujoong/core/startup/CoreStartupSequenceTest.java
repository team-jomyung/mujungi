package kr.mujoong.core.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.activity.PreparingTimeoutChecker;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.config.CorePropertiesConfig;
import kr.mujoong.core.device.DeviceExpiryCleanupJob;
import kr.mujoong.core.health.DbHealthChecker;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.messaging.ControlEventRearm;
import kr.mujoong.core.messaging.CoreInboxDispatcher;
import kr.mujoong.core.messaging.CoreInboxRepoller;
import kr.mujoong.core.messaging.CoreInboxRepository;
import kr.mujoong.core.messaging.CoreOutboxRepository;
import kr.mujoong.core.messaging.CoreOutboxSender;
import kr.mujoong.core.messaging.MessagingCleanupJob;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.observability.snapshot.MetricSnapshotJob;
import kr.mujoong.core.web.InFlightRequests;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

/**
 * Core 기동 · 정지 순서 단위 (RD-12 S2 ~ S6 · RD-13 T1 · T4 · T5 · BR5.12 · A1 · R3-5 · J125). S2 실패면 복구 · 준비 없이 기동이 끝나고,
 * 복구 순서는 S3c → S3d → S3f, 복구 줄(S3e) 실패는 기동을 막지 않으며, 정지는 DRAINING → 스케줄러 정지 → 병합 로그 순이고 두 번 불러도
 * 같다. phase는 정지가 가장 먼저, 기동 순서가 웹 서버(MAX − 2048)보다 먼저다.
 */
@Tag("u1")
class CoreStartupSequenceTest {

    private static final CoreProperties PROPERTIES = testProperties();

    private final MutableClock clock = new MutableClock(Instant.parse("2001-10-01T00:00:00Z"));
    private final SchemaVersionCheck schemaCheck = mock(SchemaVersionCheck.class);
    private final CoreInboxRepository inbox = mock(CoreInboxRepository.class);
    private final CoreOutboxRepository outbox = mock(CoreOutboxRepository.class);
    private final ControlEventRearm rearm = mock(ControlEventRearm.class);
    private final CoreOutboxSender sender = mock(CoreOutboxSender.class);
    private final CoreInboxRepoller repoller = mock(CoreInboxRepoller.class);
    private final CoreInboxDispatcher dispatcher = mock(CoreInboxDispatcher.class);
    private final EventLogger events = mock(EventLogger.class);
    private final List<Object> published = new ArrayList<>();
    private final ReadinessState readiness = new ReadinessState(published::add, CoreMetrics.inMemory(), 3,
            Duration.ofSeconds(5));
    private final MetricSnapshotJob snapshots = mock(MetricSnapshotJob.class);
    private final InFlightRequests inFlight = new InFlightRequests();
    private final CoreStartupSequence sequence = new CoreStartupSequence(schemaCheck, inbox, outbox, rearm, sender,
            repoller, dispatcher, mock(PreparingTimeoutChecker.class), mock(DbHealthChecker.class),
            mock(DeviceExpiryCleanupJob.class), mock(MessagingCleanupJob.class), snapshots, events, readiness, clock,
            PROPERTIES);

    @AfterEach
    void stop() {
        sequence.stopSchedulers();
    }

    @Test
    void schemaCheckFailureEndsStartupBeforeRecoveryOrReadiness() {
        when(schemaCheck.verify()).thenThrow(new IllegalStateException("flyway.core_schema_history 버전 0 < 기대 1"));

        assertThatThrownBy(sequence::start).isInstanceOf(IllegalStateException.class);

        verify(inbox, never()).pullForwardReceived(any());
        verify(rearm, never()).rearmOnStart(any());
        verify(outbox, never()).releaseAllClaims(any());
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.STARTING);
        assertThat(sequence.isRunning()).isFalse();
    }

    @Test
    void recoveryRunsInboxThenRearmThenClaimsAndThenReady() {
        when(inbox.pullForwardReceived(clock.now())).thenReturn(2);
        when(rearm.rearmOnStart(clock.now())).thenReturn(1);
        when(outbox.releaseAllClaims(clock.now())).thenReturn(3);

        sequence.start();

        InOrder order = inOrder(schemaCheck, inbox, rearm, outbox, events);
        order.verify(schemaCheck).verify();
        order.verify(inbox).pullForwardReceived(clock.now());
        order.verify(rearm).rearmOnStart(clock.now());
        order.verify(outbox).releaseAllClaims(clock.now());
        order.verify(events).log(eq(EventCode.STARTUP_RECOVERY), eq(EventLog.Component.CORE_MAINTENANCE), eq(null), any());
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.READY);
        assertThat(sequence.isRunning()).isTrue();
        assertThat(sequence.recover()).isEqualTo(new CoreStartupSequence.Recovery(2, 1, 3));
    }

    @Test
    void recoveryLogFailureDoesNotBlockStartup() {
        doThrow(new IllegalStateException("log sink down")).when(events)
                .log(eq(EventCode.STARTUP_RECOVERY), any(), any(), any());

        sequence.start();

        assertThat(readiness.isReady()).isTrue();
        verify(events).appError(eq(EventLog.Component.CORE_MAINTENANCE), any(IllegalStateException.class));
    }

    @Test
    void recoveryStatementFailureEndsStartup() {
        when(rearm.rearmOnStart(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(sequence::start).isInstanceOf(IllegalStateException.class);
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.STARTING);
    }

    @Test
    void stoppingSchedulersAsksTheSenderToStopAndIsIdempotent() throws Exception {
        sequence.start();

        sequence.stop();
        sequence.stopSchedulers();

        verify(sender, times(2)).requestStop();
        verify(dispatcher, times(2)).shutdown();
        assertThat(sequence.isRunning()).isFalse();
    }

    @Test
    void drainingStopFirstMarksDrainingThenStopsSchedulersThenFlushesMergedLogs() {
        CoreStartupSequence startup = mock(CoreStartupSequence.class);
        AtomicReference<ReadinessState.State> stateAtSchedulerStop = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(call -> {
            stateAtSchedulerStop.set(readiness.state());
            return null;
        }).when(startup).stopSchedulers();
        DrainingLifecycle draining = new DrainingLifecycle(readiness, startup, events, snapshots, inFlight, clock,
                Duration.ofSeconds(2));
        readiness.markReady();
        draining.start();

        draining.stop();

        InOrder order = inOrder(startup, events, snapshots);
        order.verify(startup).stopSchedulers();
        order.verify(events).flushMerged();
        order.verify(snapshots).finalSnapshot();
        assertThat(stateAtSchedulerStop.get()).isEqualTo(ReadinessState.State.DRAINING);
        assertThat(draining.isRunning()).isFalse();
    }

    /** T6(3.6 수정 J321): 받아들인 요청이 끝날 때까지 돌아오지 않는다 — 그동안 커넥터가 열려 있어 새 요청은 F1b의 503이다. */
    @Test
    void drainingWaitsForAdmittedRequestsBeforeItReturns() throws Exception {
        CoreStartupSequence startup = mock(CoreStartupSequence.class);
        DrainingLifecycle draining = new DrainingLifecycle(readiness, startup, events, snapshots, inFlight, clock,
                Duration.ofSeconds(5));
        inFlight.enter();
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(draining::stop);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (readiness.state() != ReadinessState.State.DRAINING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.DRAINING);
        assertThat(stopping).as("요청이 남아 있으면 기다린다").isNotDone();

        inFlight.exit();

        stopping.get(5, TimeUnit.SECONDS);
        verify(events, never()).appError(any(), eq("DrainTimeout"));
    }

    /** T6 한도: 유예에서 T1 ~ T5에 쓴 시간을 빼고 기다리며, 끝나지 않은 요청이 있으면 DrainTimeout 한 줄. 마지막 스냅샷 실패도 종료를 막지 않는다. */
    @Test
    void drainingGivesUpAfterTheGraceAndAFailedLastSnapshotDoesNotStopIt() {
        CoreStartupSequence startup = mock(CoreStartupSequence.class);
        org.mockito.Mockito.doAnswer(call -> {
            clock.advance(Duration.ofSeconds(5));
            return null;
        }).when(startup).stopSchedulers();
        doThrow(new IllegalStateException("db down")).when(snapshots).finalSnapshot();
        DrainingLifecycle draining = new DrainingLifecycle(readiness, startup, events, snapshots, inFlight, clock,
                Duration.ofSeconds(5));
        inFlight.enter();
        long started = System.nanoTime();

        draining.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("T4가 유예를 다 써 T6은 기다리지 않는다")
                .isLessThan(Duration.ofSeconds(2));
        verify(events).appError(eq(EventLog.Component.CORE_MAINTENANCE), any(IllegalStateException.class));
        verify(events).appError(EventLog.Component.CORE_MAINTENANCE, "DrainTimeout");
        assertThat(draining.isRunning()).isFalse();
        assertThatThrownBy(() -> new DrainingLifecycle(readiness, startup, events, snapshots, inFlight, clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(DrainingLifecycle.GRACE_KEY);
    }

    @Test
    void drainingStopsBeforeEverythingAndStartupRunsBeforeTheWebServer() {
        DrainingLifecycle draining = new DrainingLifecycle(readiness, sequence, events, snapshots, inFlight, clock,
                Duration.ofSeconds(2));

        assertThat(draining.getPhase()).isEqualTo(Integer.MAX_VALUE).isGreaterThan(sequence.getPhase());
        assertThat(sequence.getPhase()).isLessThan(Integer.MAX_VALUE - 2048).isGreaterThan(SmartLifecycle.DEFAULT_PHASE - 8192);
        assertThat(sequence.isAutoStartup()).isTrue();
    }

    private static CoreProperties testProperties() {
        AtomicReference<CoreProperties> properties = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(CorePropertiesConfig.class)
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> properties.set(context.getBean(CoreProperties.class)));
        return properties.get();
    }
}
