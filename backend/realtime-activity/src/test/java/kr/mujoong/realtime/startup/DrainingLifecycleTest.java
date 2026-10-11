package kr.mujoong.realtime.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.observability.snapshot.MetricSnapshotJob;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.session.SessionRegistry;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.web.InFlightRequests;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Realtime 정상 종료 순서 T1 ~ T6 (U1 3.6 수정 · RD-13 · 2.8 · R3-13 · NFR4.10 · BT-RD-13 · J304).
 *
 * <p>설계 순서(readiness DRAINING → 열린 WSS 1001 → 공급자 닫기 확인 → 폴러 정지 → 기록기 · 병합 로그 · 마지막 스냅샷 → 진행 중 요청 대기)대로
 * 부르고, 모든 단계가 한 마감(timeout-per-shutdown-phase) 안에서 남은 시간만 쓰며, 막힌 단계는 사유 한 줄을 남기고 넘어가는지 본다. 실제
 * 앱의 커넥터 동작은 {@code GracefulDrainIT}가 본다.
 */
@Tag("u1")
class DrainingLifecycleTest {

    private static final Duration GRACE = Duration.ofSeconds(2);
    private static final Duration RECORDER_GRACE = Duration.ofSeconds(1);

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final ReadinessState readiness = spy(new ReadinessState(event -> { }, metrics, 3, Duration.ofSeconds(5)));
    private final SocketRegistry sockets = mock(SocketRegistry.class);
    private final SessionRegistry sessions = mock(SessionRegistry.class);
    private final RealtimeStartupSequence startupSequence = mock(RealtimeStartupSequence.class);
    private final AsyncRecorder recorder = mock(AsyncRecorder.class);
    private final EventLogger events = spy(TestEventLoggers.strict(clock, logs, metrics));
    private final MetricSnapshotJob snapshots = mock(MetricSnapshotJob.class);
    private final InFlightRequests inFlight = spy(new InFlightRequests());

    @Test
    void theStepsRunInTheDesignOrderAndTheProviderCloseIsConfirmedBeforeThePollersStop() throws Exception {
        SessionMailbox mailbox = new SessionMailbox(8);
        List<SessionExecutor> executors = List.of(executorWith(mailbox));
        when(sessions.executors()).thenReturn(executors);
        DrainingLifecycle draining = lifecycle(GRACE);
        draining.start();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(draining::stop);
        SessionMailbox.Message message = mailbox.take();
        assertThat(message).isInstanceOf(SessionMailbox.ServerShutdown.class);
        assertThat(stopping).as("공급자 닫기를 확인하기 전에는 T4로 가지 않는다").isNotDone();
        verify(startupSequence, never()).stopJobs(any());
        ((SessionMailbox.ServerShutdown) message).handled().complete(null);
        stopping.get(10, TimeUnit.SECONDS);

        InOrder order = inOrder(readiness, sockets, startupSequence, recorder, events, snapshots, inFlight);
        order.verify(readiness).markDraining();
        order.verify(sockets).closeAll(CloseCode.GOING_AWAY);
        order.verify(startupSequence).stopJobs(any());
        order.verify(recorder).drain(any());
        order.verify(events).flushMerged();
        order.verify(snapshots).finalSnapshot();
        order.verify(inFlight).awaitIdle(any());
        assertThat(readiness.state()).isEqualTo(ReadinessState.State.DRAINING);
        assertThat(draining.isRunning()).isFalse();
        assertThat(logs.events(EventCode.APP_ERROR)).isEmpty();
    }

    @Test
    void aStuckExecutorIsGivenUpAtTheDeadlineAndTheLaterStepsStillRun() {
        SessionMailbox mailbox = new SessionMailbox(8);
        List<SessionExecutor> executors = List.of(executorWith(mailbox));
        when(sessions.executors()).thenReturn(executors);
        Duration shortGrace = Duration.ofMillis(200);

        lifecycle(shortGrace).stop();

        assertThat(appErrorKinds()).containsExactly("ProviderCloseTimeout");
        verify(startupSequence).stopJobs(any());
        verify(snapshots).finalSnapshot();
        assertThat(mailbox.safetySize()).as("처리되지 않은 종료 신호가 편지함에 남아 있다").isEqualTo(1);
    }

    @Test
    void aClosedMailboxIsNotAwaitedAndAClosingMailboxReleasesTheWaiter() throws Exception {
        SessionMailbox closed = new SessionMailbox(8);
        closed.close();
        SessionMailbox released = new SessionMailbox(8);
        List<SessionExecutor> executors = List.of(executorWith(closed), executorWith(released));
        when(sessions.executors()).thenReturn(executors);
        DrainingLifecycle draining = lifecycle(Duration.ofSeconds(30));

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(draining::stop);
        awaitQueued(released);
        // 실행기가 처리하지 못하고 끝나면 편지함 정리가 약속을 끝낸다(30초를 기다리지 않는다)
        released.closeAndRelease();
        stopping.get(10, TimeUnit.SECONDS);

        assertThat(closed.safetySize()).isZero();
        assertThat(appErrorKinds()).isEmpty();
    }

    @Test
    void theDeadlineIsSharedSoLaterStepsOnlyGetWhatIsLeft() throws Exception {
        when(sessions.executors()).thenReturn(List.of());
        // T4가 마감 2초 중 1.5초를 썼다
        doAnswer(invocation -> {
            clock.advance(Duration.ofMillis(1500));
            return null;
        }).when(startupSequence).stopJobs(any());

        lifecycle(GRACE).stop();

        ArgumentCaptor<Duration> jobs = ArgumentCaptor.forClass(Duration.class);
        verify(startupSequence).stopJobs(jobs.capture());
        assertThat(jobs.getValue()).isEqualTo(GRACE);
        ArgumentCaptor<Duration> drain = ArgumentCaptor.forClass(Duration.class);
        verify(recorder).drain(drain.capture());
        assertThat(drain.getValue()).as("기록기 상한 1초보다 남은 0.5초가 짧다").isEqualTo(Duration.ofMillis(500));
        ArgumentCaptor<Duration> idle = ArgumentCaptor.forClass(Duration.class);
        verify(inFlight).awaitIdle(idle.capture());
        assertThat(idle.getValue()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void aRequestStillInFlightAtTheDeadlineLeavesDrainTimeoutButAFailedSnapshotDoesNotStopTheShutdown() {
        when(sessions.executors()).thenReturn(List.of());
        doThrow(new IllegalStateException("db down")).when(snapshots).finalSnapshot();
        inFlight.enter();
        DrainingLifecycle draining = lifecycle(Duration.ofMillis(100));
        draining.start();

        draining.stop();

        assertThat(appErrorKinds()).containsExactly("IllegalStateException", "DrainTimeout");
        assertThat(draining.isRunning()).isFalse();
        assertThat(inFlight.active()).as("포기해도 요청을 끊지는 않는다(뒤 단계 graceful · SIGKILL의 몫)").isEqualTo(1);
    }

    @Test
    void aNonPositiveGraceIsRejectedAndThePhaseIsTheHighest() {
        assertThatThrownBy(() -> lifecycle(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(DrainingLifecycle.GRACE_KEY);
        assertThatThrownBy(() -> lifecycle(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(lifecycle(GRACE).getPhase()).as("웹 서버 graceful 단계(MAX − 1024)보다 먼저 멈춘다")
                .isEqualTo(Integer.MAX_VALUE);
    }

    private DrainingLifecycle lifecycle(Duration grace) {
        return new DrainingLifecycle(readiness, sockets, sessions, startupSequence, recorder, events, snapshots,
                inFlight, clock, RECORDER_GRACE, grace);
    }

    /** 편지함만 쓰는 실행기 대역. 다른 대역의 when(...) 안에서 부르지 않는다(Mockito 미완 스텁). */
    private static SessionExecutor executorWith(SessionMailbox mailbox) {
        SessionExecutor executor = mock(SessionExecutor.class);
        when(executor.mailbox()).thenReturn(mailbox);
        return executor;
    }

    private static void awaitQueued(SessionMailbox mailbox) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (mailbox.safetySize() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(mailbox.safetySize()).isEqualTo(1);
    }

    private List<Object> appErrorKinds() {
        return logs.events(EventCode.APP_ERROR).stream().map(line -> LogCapture.attrs(line).get("errorKind")).toList();
    }
}
