package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.testing.FakeProviderPort;
import kr.mujoong.realtime.testing.InMemoryTransitions;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 실행기 단위 시험 (U1 3.5 Step 29 · PD-1 · R3-1 · R3-6 · SCD-1 · BR6.6 ~ 6.11 · J54 · J126 · RD-3).
 *
 * <p>생산자 여럿의 처리 순서 · 안전 편지함 우선 · 세대가 다른 타이머 무동작 · 실행기 예외 → FAILED(SERVER_RESTART) · INIT 중 명령 순서 ·
 * 종료 뒤 늦은 메시지 폐기와 시작 · 끝내기 경로를 실제 스레드 없이 확인한다. 3.6 수정: 앱 종료 T3 처리 알림(J304)과 AI 행 준비 시간의
 * 시작점(J300).
 */
@Tag("u1")
class SessionExecutorTest {

    private final SessionHarness h = new SessionHarness();

    @Test
    void bootstrapSendsStartSessionFirstThenArmsTheDeviceAckClockThenLaunchesTheProviderConnect() {
        h.create();
        h.executor.bootstrapOnly();
        h.drain();

        assertThat(h.link.sent()).hasSize(1);
        assertThat(h.link.sent().get(0).payload()).isInstanceOf(StartSession.class);
        assertThat(h.link.sent().get(0).opId()).isNotNull();
        assertThat(h.link.boundSession()).isEqualTo(h.sessionId);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.DEVICE_ACK)).isTrue();
        assertThat(h.executor.timers().deadline(SessionTimers.Kind.DEVICE_ACK))
                .isEqualTo(h.clock.now().plus(Duration.ofSeconds(20)));
        assertThat(h.launcher.names()).anyMatch(name -> name.startsWith("provider-connect-"));
        assertThat(h.provider.openedCount()).isZero();
    }

    @Test
    void ackReadyAndProviderStartedActivateTheSessionAndOpenTheRelay() {
        h.activate();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.transitions.applied()).extracting(InMemoryTransitions.Applied::to)
                .containsExactly(SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.LISTENING);
        assertThat(h.executor.relay().isAccepting()).isTrue();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.DEVICE_ACK)).isFalse();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.DISCONNECT)).isTrue();
        assertThat(h.transitions.find(h.sessionId).orElseThrow().providerSessionId()).isEqualTo("prov-session-1");
    }

    @Test
    void readyBeforeTheProviderStartedWaitsForTheLaterOfTheTwo() {
        h.provider.mode(FakeProviderPort.Mode.NO_STARTED);
        h.boot();
        h.ackAndReady();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DEVICE_ACK);

        h.provider.last().emit(new ProviderEvent.Started("late"));
        h.drain();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void withoutALiveSocketTheStartFailsAtOnceWithDeviceNoAckAndNoCloseTransport() {
        h.link = null;
        h.create();
        h.executor.bootstrapOnly();
        h.drain();

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.removed).isTrue();
        assertThat(h.launcher.names()).noneMatch(name -> name.startsWith("provider-connect-"));
    }

    @Test
    void aStartSessionSendFailureFailsTheSessionAndMarksTheConnectionUnresponsive() {
        h.link.setAcceptControl(false);
        h.create();
        h.executor.bootstrapOnly();
        h.drain();

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.unresponsive).hasSize(1);
        assertThat(h.unresponsive.get(0)[1]).isEqualTo(h.link.connectionId());
    }

    @Test
    void theSafetyMailboxIsAlwaysDrainedBeforeTheGeneralOne() {
        SessionMailbox mailbox = new SessionMailbox(10);
        SessionMailbox.Message general = new SessionMailbox.ServerShutdown();
        SessionMailbox.Message first = new SessionMailbox.TimerFired(SessionTimers.Kind.DEVICE_ACK, 1);
        SessionMailbox.Message second = new SessionMailbox.TimerFired(SessionTimers.Kind.DISCONNECT, 1);
        assertThat(mailbox.offerGeneral(general)).isTrue();
        mailbox.putSafety(first);
        mailbox.putSafety(second);

        assertThat(mailbox.poll()).isSameAs(first);
        assertThat(mailbox.poll()).isSameAs(second);
        assertThat(mailbox.poll()).isSameAs(general);
        assertThat(mailbox.poll()).isNull();
    }

    @Test
    void theGeneralMailboxRejectsDeviceProducersWhenFullAndKeepsFifoOrder() {
        SessionMailbox mailbox = new SessionMailbox(2);
        SessionMailbox.Message a = new SessionMailbox.ConnectionLost(UUID.randomUUID());
        SessionMailbox.Message b = new SessionMailbox.ConnectionLost(UUID.randomUUID());
        assertThat(mailbox.offerGeneral(a)).isTrue();
        assertThat(mailbox.offerGeneral(b)).isTrue();
        assertThat(mailbox.offerGeneral(new SessionMailbox.ServerShutdown())).isFalse();

        assertThat(mailbox.poll()).isSameAs(a);
        assertThat(mailbox.poll()).isSameAs(b);
        mailbox.close();
        assertThat(mailbox.putSafety(new SessionMailbox.ServerShutdown())).isFalse();
        assertThat(mailbox.isClosed()).isTrue();
    }

    @Test
    void aStaleTimerGenerationDoesNothing() {
        h.boot();
        h.mailbox.putSafety(new SessionMailbox.TimerFired(SessionTimers.Kind.DEVICE_ACK, 999));
        h.clock.advance(Duration.ofSeconds(25));
        h.drain();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(h.transitions.applied()).isEmpty();
    }

    @Test
    void anUnexpectedExceptionFailsTheSessionWithServerRestartAndCleansUp() {
        h.boot();
        h.transitions.crashNext(1);
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), h.startOpId(), h.clock.now()));
        h.drain();

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode()).isEqualTo(FailureCode.SERVER_RESTART);
        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.logs.events(EventCode.APP_ERROR)).isNotEmpty();
        assertThat(h.provider.last().isOpen()).isFalse();
    }

    @Test
    void aDatabaseFailureIsRedeliveredAfterTheTimerTick() {
        h.boot();
        h.transitions.failNext(1);
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), h.startOpId(), h.clock.now()));
        h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);

        h.advance(Duration.ofSeconds(1));

        assertThat(h.executor.status()).isEqualTo(SessionStatus.DEVICE_ACK);
    }

    @Test
    void commandsQueuedDuringInitAreHandledInArrivalOrderBeforeTheStart() {
        h.create();
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        CompletableFuture<EndOutcome> cancel = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.mailbox.putSafety(new SessionMailbox.EndRequested(h.sessionId, EndRequestReason.CORE_ABANDONED, cancel));
        h.executor.bootstrapOnly();
        h.drain();

        assertThat(stop).isCompletedWithValue(EndOutcome.NOT_ACTIVE);
        assertThat(cancel).isCompletedWithValue(EndOutcome.ACCEPTED_EMPTY);
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode()).isNull();
        // 취소가 먼저 이겼으므로 START_SESSION도 CLOSE_TRANSPORT도 나가지 않는다
        assertThat(h.link.sent()).isEmpty();
    }

    @Test
    void lateMessagesAfterATerminalStateAreDroppedWithoutRecords() {
        h.activate();
        CompletableFuture<EndOutcome> cancel = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(h.sessionId, EndRequestReason.CORE_ABANDONED, cancel));
        h.drain();
        int sentBefore = h.link.sent().size();

        boolean accepted = h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(UUID.randomUUID(), PlaybackMark.Status.COMPLETED, null, h.clock.now(), null, null),
                h.clock.now()));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r1", "늦은 말"));

        assertThat(accepted).isFalse();
        assertThat(h.drain()).isZero();
        assertThat(h.link.sent()).hasSize(sentBefore);
        assertThat(h.executor.offerAudio(h.link.connectionId(), 1, new byte[960], 0)).isFalse();
    }

    @Test
    void aProviderFailureFailsAtOnceAndSendsCloseTransportFailedAndStandby() {
        h.provider.mode(FakeProviderPort.Mode.REFUSE);
        h.boot();

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode())
                .isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.FAILED);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.STANDBY);
        assertThat(h.unresponsive).isEmpty();
    }

    @Test
    void anUnavailableDeviceReadyFailsWithoutWaitingAndWithoutUnresponsive() {
        h.boot();
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), h.startOpId(), h.clock.now()));
        h.mailbox.offerGeneral(new SessionMailbox.DeviceReadyReceived(h.link.connectionId(),
                new DeviceReady(DeviceReady.Availability.UNAVAILABLE, DeviceReady.Availability.OK,
                        DeviceReady.Prefetch.OK),
                h.clock.now()));
        h.drain();

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(h.unresponsive).isEmpty();
        assertThat(h.link.sent(CloseTransport.class)).hasSize(1);
    }

    @Test
    void anAckFromAnotherConnectionOrForAnotherOpIsIgnored() {
        h.boot();
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(UUID.randomUUID(), h.startOpId(), h.clock.now()));
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), UUID.randomUUID(), h.clock.now()));
        h.drain();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);
    }

    @Test
    void coreAbandonedWhileActiveFailsWithoutFailureCodeAndClosesTheTransport() {
        h.activate();
        CompletableFuture<EndOutcome> cancel = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(h.sessionId, EndRequestReason.CORE_ABANDONED, cancel));
        h.drain();

        assertThat(cancel).isCompletedWithValue(EndOutcome.ACCEPTED_EMPTY);
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode()).isNull();
        assertThat(h.transitions.applied().get(h.transitions.applied().size() - 1).origin())
                .isEqualTo(SessionTransitions.Origin.CORE_ABANDONED);
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.FAILED);
        assertThat(h.executor.relay().isAccepting()).isFalse();
    }

    @Test
    void guardianStopRunsTheClosingSequenceOnceAndEndsConfirmed() {
        h.activate();
        CompletableFuture<EndOutcome> first = new CompletableFuture<>();
        CompletableFuture<EndOutcome> again = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, first));
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, again));
        h.drain();

        assertThat(first).isCompletedWithValue(EndOutcome.CLOSING);
        assertThat(again).isCompletedWithValue(EndOutcome.CLOSING);
        assertThat(h.transitions.applied()).filteredOn(a -> a.to() == SessionStatus.CLOSING).hasSize(1);
        assertThat(h.link.sent(StopOutput.class)).extracting(StopOutput::reason)
                .containsExactly(StopOutput.Reason.GUARDIAN_STOP);
        List<PlayApprovedContent> plays = h.link.sent(PlayApprovedContent.class);
        assertThat(plays).hasSize(1);
        assertThat(plays.get(0).contentCode()).isEqualTo(PlayApprovedContent.CLOSING);
        assertThat(plays.get(0).contentVersionId()).isEqualTo(h.closingVersion);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view)
                .containsExactly(View.LISTENING, View.CLOSING);

        // 다른 playbackId의 마크는 근거가 아니다
        mark(UUID.randomUUID(), PlaybackMark.Status.COMPLETED);
        assertThat(h.link.sent(CloseTransport.class)).isEmpty();
        mark(plays.get(0).playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.GUARDIAN_STOP);
        assertThat(h.executor.relay().isAccepting()).isFalse();

        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(row.confirmed()).isTrue();
        assertThat(row.outputStoppedAt()).isNotNull();
        assertThat(row.providerClosedAt()).isNotNull();
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).last().isEqualTo(View.STANDBY);
        assertThat(h.provider.last().requests()).last().isInstanceOf(
                kr.mujoong.realtime.voice.ProviderRequestFactory.SessionClose.class);
        assertThat(h.removed).isTrue();
    }

    @Test
    void guardianStopBeforeActiveIsNotActiveAndUnsupportedReasonsAreRejected() {
        h.boot();
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        CompletableFuture<EndOutcome> consent = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        // U4b(FR1.4): CONSENT_WITHDRAWN은 응답표에 들어왔다. 응답표 밖 사유로 거절을 본다
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.UNRECOGNIZED,
                consent));
        h.drain();

        assertThat(stop).isCompletedWithValue(EndOutcome.NOT_ACTIVE);
        assertThat(consent).isCompletedWithValue(EndOutcome.UNSUPPORTED);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);
    }

    @Test
    void endRequestsLeftInTheMailboxAtTeardownAreReleasedForTheDatabaseAnswer() {
        h.link = null;
        h.create();
        h.executor.bootstrapOnly();
        CompletableFuture<EndOutcome> pending = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, pending));
        h.executor.step();

        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(pending).isCompletedExceptionally();
        assertThat(pending.exceptionNow()).isInstanceOf(SessionMailbox.SessionGone.class);
        assertThat(h.mailbox.putSafety(new SessionMailbox.ServerShutdown())).isFalse();
    }

    // ------------------------------------------------------------------ 3.6 수정: 정상 종료 T3 알림(J304) · 행 준비 시간(J300)

    @Test
    void aServerShutdownClosesTheProviderAndFlushesTheRecorderThenAcknowledgesWithoutEndingTheSession() {
        h.activate();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", "공룡"));
        h.drain();
        assertThat(h.store.transcripts).as("확정 전 칸은 아직 쓰지 않았다").isEmpty();
        SessionMailbox.ServerShutdown shutdown = new SessionMailbox.ServerShutdown();
        assertThat(h.mailbox.putSafety(shutdown)).isTrue();
        assertThat(shutdown.handled()).isNotDone();

        h.drain();

        assertThat(shutdown.handled()).as("정상 종료 T4는 이 알림 뒤에 폴러를 멈춘다").isCompleted();
        assertThat(h.provider.last().isOpen()).isFalse();
        assertThat(h.provider.last().requests()).last().isInstanceOf(ProviderRequestFactory.SessionClose.class);
        assertThat(h.store.transcripts).extracting(TranscriptEntry::assemblyStatus)
                .containsExactly(TranscriptEntry.AssemblyStatus.PARTIAL);
        assertThat(h.transitions.status(h.sessionId)).as("세션 행은 다음 기동 S3a가 닫는다").isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.isTerminated()).isFalse();
    }

    @Test
    void aServerShutdownLeftInTheMailboxAtTeardownIsStillAcknowledgedSoTheDrainNeverWaitsForIt() {
        h.link = null;
        h.create();
        h.executor.bootstrapOnly();
        SessionMailbox.ServerShutdown shutdown = new SessionMailbox.ServerShutdown();
        h.mailbox.putSafety(shutdown);

        h.executor.step();

        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(shutdown.handled()).isCompleted();
        assertThat(h.provider.openedCount()).isZero();
    }

    @Test
    void onlyTheOutputDoneOfTheOpenResponseStartsTheAiRowsViewItemReadyTime() {
        h.activate();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", "공룡"));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r1", "공룡 좋아"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r1", new byte[4800]));
        h.provider.last().emit(new ProviderEvent.OutputDone("r0"));
        h.drain();
        h.advance(Duration.ofMillis(300));

        h.provider.last().emit(new ProviderEvent.OutputDone("r1"));
        h.drain();
        h.advance(Duration.ofMillis(500));

        Timer ai = h.metrics.registry().find(RealtimeMetrics.VIEW_ITEM_READY).tag("speaker", "ai").timer();
        assertThat(ai.count()).isEqualTo(1);
        assertThat(ai.totalTime(TimeUnit.MILLISECONDS)).as("r1의 끝(300ms) → 게이트 통과 커밋(800ms), 다른 응답의 끝은 시작점이 아니다")
                .isEqualTo(500.0);
        assertThat(h.store.transcripts).filteredOn(row -> row.speaker() == TranscriptEntry.Speaker.AI)
                .singleElement().satisfies(row -> assertThat(row.approvedForDisplay()).isTrue());
    }

    private void mark(UUID playbackId, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(playbackId, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

}
