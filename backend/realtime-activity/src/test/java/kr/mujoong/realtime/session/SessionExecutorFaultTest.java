package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.testing.InMemoryTransitions;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 실행기 장애 경로 시험 (U1 3.6 수정 라운드 · 리뷰 F2 · F4 · F5 · BR6.8 · BR6.10 · BR6.12 ③ ④ · PD-1 · RD-1 규약 4 · 5 · 8).
 *
 * <p>실행기 스레드의 공급자 닫기가 자기 편지함(가득 참)을 기다리지 않는지(F2), 최상위 예외로 FAILED 전이를 이기면 살아 있는 연결에
 * CLOSE_TRANSPORT(FAILED) · STANDBY를 보내는지(F4), 커밋은 됐는데 응답을 잃은 전이를 다시 읽어 받아들일 때 그 상태의 시한 · 부수 효과를
 * 한 번만 거는지(F5)를 실제 스레드 없이 본다.
 */
@Tag("skeleton")
@org.junit.jupiter.api.Tag("service-guardrail")
class SessionExecutorFaultTest {

    /** 일반 편지함 상한만 4로 줄인 설정(나머지는 운영 기본값). */
    private static final SessionExecutor.Settings SMALL_MAILBOX = new SessionExecutor.Settings(Duration.ofSeconds(20),
            Duration.ofSeconds(30), Duration.ofSeconds(40), Duration.ofMillis(800), Duration.ofSeconds(20),
            Duration.ofSeconds(1), 50, 4, 60, SessionHarness.LIFECYCLE, Duration.ofMillis(1500),
            Duration.ofMillis(600), Duration.ofSeconds(10), Duration.ZERO);
    private static final Duration DEADLOCK_GUARD = Duration.ofSeconds(5);

    private final SessionHarness h = new SessionHarness();

    // ------------------------------------------------------------------ F2 자기 편지함 교착

    @Test
    void theClosingConfirmProviderCloseDoesNotWaitForItsOwnFullMailbox() {
        SessionHarness small = new SessionHarness(SMALL_MAILBOX);
        small.provider.signalClosedOnClose(true);
        small.activate();
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        small.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        small.drain();
        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);
        fillGeneralMailbox(small);
        small.clock.advance(Duration.ofSeconds(30));
        small.safety.runDue();

        // CLOSING_CONFIRM 만기는 안전 편지함이라 가득 찬 일반 편지함보다 먼저 처리되고, 전이 전에 실행기 스레드에서 공급자를 닫는다
        assertTimeoutPreemptively(DEADLOCK_GUARD, () -> {
            small.executor.step();
            small.executor.step();
        });

        assertThat(small.transitions.status(small.sessionId)).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
        assertThat(small.executor.isTerminated()).isTrue();
        assertThat(small.provider.last().isOpen()).isFalse();
        assertThat(small.link.sent(SessionState.class)).extracting(SessionState::view).last().isEqualTo(View.STANDBY);
    }

    @Test
    void theTeardownProviderCloseDoesNotWaitForItsOwnFullMailboxEither() {
        SessionHarness small = new SessionHarness(SMALL_MAILBOX);
        small.provider.signalClosedOnClose(true);
        small.activate();
        fillGeneralMailbox(small);
        CompletableFuture<EndOutcome> cancel = new CompletableFuture<>();
        small.mailbox.putSafety(new SessionMailbox.EndRequested(small.sessionId, EndRequestReason.CORE_ABANDONED, cancel));

        // CORE_ABANDONED → FAILED → 정리(teardown)의 공급자 닫기도 실행기 스레드다
        assertTimeoutPreemptively(DEADLOCK_GUARD, () -> {
            small.executor.step();
        });

        assertThat(cancel).isCompletedWithValue(EndOutcome.ACCEPTED_EMPTY);
        assertThat(small.transitions.status(small.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(small.executor.isTerminated()).isTrue();
        assertThat(small.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.FAILED);
    }

    @Test
    void aProviderCloseSignalledFromAnotherThreadIsStillQueuedForTheExecutor() {
        h.provider.signalClosedOnClose(true);
        h.activate();

        // 공급자 리더(실행기 밖 스레드)의 끊김 알림은 평소대로 일반 편지함에 들어가 실행기가 처리한다
        h.provider.last().close();
        assertThat(h.mailbox.generalSize()).isEqualTo(1);
        h.drain();

        // U3-R8 · U3-J13(AC6.6.3 · B18): U1은 ACTIVE를 그대로 두었다. U3부터 공급자 끊김은 ABORTED(DISCONNECT, PROVIDER_LOST)로 끝낸다
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ABORTED);
        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.endReason()).isEqualTo(EndReason.DISCONNECT);
        assertThat(row.endDetail()).isEqualTo("PROVIDER_LOST");
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.DISCONNECT);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).last().isEqualTo(View.STANDBY);
        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(LogCapture.attrs(h.logs.last(EventCode.APP_ERROR).orElseThrow()))
                .containsEntry("errorKind", "ProviderDisconnected");
    }

    // ------------------------------------------------------------------ F4 최상위 예외의 기기 명령

    @Test
    void anUnexpectedExceptionWhileActiveClosesTheLiveTransportAndReturnsTheDeviceToStandby() {
        h.activate();
        h.transitions.crashNext(1);
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP,
                new CompletableFuture<>()));
        h.drain();

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(row.failureCode()).isEqualTo(FailureCode.SERVER_RESTART);
        // BR6.8: FAILED로 전이할 때 연결이 살아 있고 START_SESSION이 나갔으면 CLOSE_TRANSPORT(FAILED) + SESSION_STATE{STANDBY}
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.FAILED);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view)
                .containsExactly(View.LISTENING, View.STANDBY);
        assertThat(h.executor.relay().isAccepting()).isFalse();
        assertThat(h.link.boundSession()).isNull();
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void anUnexpectedExceptionWithoutALiveConnectionSendsNothing() {
        h.activate();
        int sentBefore = h.link.sent().size();
        h.link.setOpen(false);
        h.transitions.crashNext(1);
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP,
                new CompletableFuture<>()));
        h.drain();

        // 보낼 연결이 없으면(재시작 정리와 같은 경우) 전이만 한다
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.link.sent()).hasSize(sentBefore);
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void anUnexpectedExceptionThatCannotWinTheFailedTransitionSendsNoDeviceCommand() {
        h.activate();
        // 최상위 처리의 FAILED 전이마저 실패하면 승자가 없다 — 기기 명령 없이 정리하고 고아 세션 점검에 맡긴다
        h.transitions.crashNext(2);
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP,
                new CompletableFuture<>()));
        h.drain();

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.link.sent(CloseTransport.class)).isEmpty();
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.LISTENING);
        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.logs.events(EventCode.APP_ERROR)).isNotEmpty();
    }

    // ------------------------------------------------------------------ F5 커밋 응답 유실 뒤 받아들인 상태

    @Test
    void aClosingCommitWhoseAnswerWasLostStillRunsTheClosingSequenceAndItsConfirmClock() {
        h.activate();
        Instant committedAt = h.clock.now();
        UUID requestId = UUID.randomUUID();
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.transitions.loseCommitNext(1);
        h.mailbox.putSafety(new SessionMailbox.EndRequested(requestId, EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        // DB는 CLOSING으로 커밋됐지만 실행기는 응답을 잃어 ACTIVE로 알고 timer-tick 뒤 다시 처리한다
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(stop).isNotDone();

        h.advance(Duration.ofSeconds(1));

        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.link.sent(StopOutput.class)).extracting(StopOutput::reason)
                .containsExactly(StopOutput.Reason.GUARDIAN_STOP);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view)
                .containsExactly(View.LISTENING, View.CLOSING);
        assertThat(h.link.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::contentCode)
                .containsExactly(PlayApprovedContent.CLOSING);
        // BR6.12 ③: 30초는 CLOSING을 이긴 커밋 시각부터, 끊김 시계는 CLOSING에 쓰지 않는다
        assertThat(h.executor.timers().deadline(SessionTimers.Kind.CLOSING_CONFIRM))
                .isEqualTo(committedAt.plus(Duration.ofSeconds(30)));
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.DISCONNECT)).isFalse();
        assertThat(h.executor.relay().isAccepting()).isTrue();
        assertThat(h.transitions.applied()).filteredOn(a -> a.to() == SessionStatus.CLOSING).hasSize(1);

        h.advance(Duration.ofSeconds(29));

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).last().isEqualTo(View.STANDBY);
        assertThat(h.unresponsive).hasSize(1);
        assertThat(h.link.sent(StopOutput.class)).hasSize(1);
        assertThat(h.store.sessionEvents).filteredOn(event -> event.getEventType().equals("END_REQUESTED"))
                .singleElement().satisfies(event -> assertThat(event.getOpId()).isEqualTo(requestId.toString()));
    }

    @Test
    void aDisconnectCheckThatFindsALostClosingCommitFollowsTheClosingRulesInstead() {
        h.activate();
        // activate가 끊김 확인 몫으로 시계를 앞당겼으니 기기의 마지막 메시지 시각을 지금으로 다시 잡는다(이 시험의 40초 경계는 그 시각부터다)
        h.link.touch(h.clock.now());
        h.advance(Duration.ofMillis(39_500));
        Instant committedAt = h.clock.now();
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.transitions.loseCommitNext(1);
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();

        // 다시 처리(1초 뒤)보다 끊김 40초 만기가 먼저 온다: ACTIVE → ABORTED는 0행이고 DB는 CLOSING이다
        h.advance(Duration.ofMillis(500));

        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.applied()).noneMatch(a -> a.to() == SessionStatus.ABORTED);
        // CLOSING이 끊김보다 우선한다(BR6.12 ③): 공급자는 VOICE_SESSION_CLOSED · 30초 시한까지 열어 둔다(BR6.10 ④)
        assertThat(h.provider.last().isOpen()).isTrue();
        assertThat(h.logs.events(EventCode.TIMEOUT_FIRED)).isEmpty();
        assertThat(h.link.sent(StopOutput.class)).hasSize(1);
        assertThat(h.executor.timers().deadline(SessionTimers.Kind.CLOSING_CONFIRM))
                .isEqualTo(committedAt.plus(Duration.ofSeconds(30)));

        h.advance(Duration.ofMillis(500));
        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);
        assertThat(h.link.sent(StopOutput.class)).hasSize(1);

        h.advance(Duration.ofSeconds(29));
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
        assertThat(h.logs.events(EventCode.TIMEOUT_FIRED)).singleElement()
                .satisfies(line -> assertThat(LogCapture.attrs(line)).containsEntry("type", "CLOSING_CONFIRM"));
    }

    @Test
    void anActiveCommitWhoseAnswerWasLostStillStartsTheRelayAndTheDisconnectClock() {
        h.boot();
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), h.startOpId(), h.clock.now()));
        h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DEVICE_ACK);
        h.transitions.loseCommitNext(1);
        h.mailbox.offerGeneral(new SessionMailbox.DeviceReadyReceived(h.link.connectionId(), ready(), h.clock.now()));
        h.drain();
        // U4b(FR11.3 활성화 경로): 준비가 끝나면 참여 확인(WAITING_CHILD)으로 간다
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DEVICE_ACK);

        h.advance(Duration.ofSeconds(1));

        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.LISTENING);
        assertThat(h.executor.relay().isAccepting()).isTrue();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.DEVICE_ACK)).isFalse();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.DISCONNECT)).isTrue();
        assertThat(h.transitions.applied()).filteredOn(a -> a.to() == SessionStatus.WAITING_CHILD).hasSize(1);
    }

    @Test
    void aDeviceAckCommitWhoseAnswerWasLostStillActivatesWhenTheReadyCameMeanwhile() {
        h.boot();
        h.transitions.loseCommitNext(1);
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), h.startOpId(), h.clock.now()));
        h.mailbox.offerGeneral(new SessionMailbox.DeviceReadyReceived(h.link.connectionId(), ready(), h.clock.now()));
        h.drain();
        // ACK 전이는 커밋됐지만 응답을 잃었고, READY는 PREPARING으로 알고 처리됐다
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DEVICE_ACK);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);

        h.advance(Duration.ofSeconds(1));

        // U4b(FR11.3 활성화 경로): 준비가 끝나면 참여 확인(WAITING_CHILD)으로 간다
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.LISTENING);

        // 20초 시계가 와도 ACK를 받은 세션을 DEVICE_NO_ACK로 닫지 않는다
        h.completeNotice();
        h.advance(Duration.ofSeconds(20));
        assertThat(h.transitions.applied()).extracting(InMemoryTransitions.Applied::to)
                .containsExactly(SessionStatus.DEVICE_ACK, SessionStatus.WAITING_CHILD);
        assertThat(h.unresponsive).isEmpty();
    }

    @Test
    void aDeviceAckCommitWhoseAnswerWasLostStillFailsAtOnceWhenTheReadyReportedUnavailable() {
        h.boot();
        h.transitions.loseCommitNext(1);
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), h.startOpId(), h.clock.now()));
        h.mailbox.offerGeneral(new SessionMailbox.DeviceReadyReceived(h.link.connectionId(),
                new DeviceReady(DeviceReady.Availability.UNAVAILABLE, DeviceReady.Availability.OK, DeviceReady.Prefetch.OK),
                h.clock.now()));
        h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);

        h.advance(Duration.ofSeconds(1));

        // BR6.8: DEVICE_ACK에서 READY가 사용 불가면 20초를 기다리지 않고 FAILED(DEVICE_NO_ACK), UNRESPONSIVE는 쓰지 않는다(J55)
        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(row.failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(h.unresponsive).isEmpty();
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.FAILED);
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void aCoreAbandonThatFindsALostClosingCommitAnswersEmptyAndRunsTheClosingSequence() {
        h.activate();
        h.transitions.loseCommitNext(1);
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP,
                new CompletableFuture<>()));
        h.drain();
        CompletableFuture<EndOutcome> cancel = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(h.sessionId, EndRequestReason.CORE_ABANDONED, cancel));
        h.drain();

        // BR6.7: CLOSING이면 그대로 202 {} — 행은 CLOSING이고 마무리 순서는 한 번 돈다
        assertThat(cancel).isCompletedWithValue(EndOutcome.ACCEPTED_EMPTY);
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.link.sent(StopOutput.class)).hasSize(1);
        assertThat(h.link.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::contentCode)
                .containsExactly(PlayApprovedContent.CLOSING);
        assertThat(h.link.sent(CloseTransport.class)).isEmpty();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.CLOSING_CONFIRM)).isTrue();

        // 끝내기 요청의 다시 처리는 같은 답이고 마무리 순서를 되풀이하지 않는다
        h.advance(Duration.ofSeconds(1));
        assertThat(h.link.sent(StopOutput.class)).hasSize(1);
        assertThat(h.link.sent(PlayApprovedContent.class)).hasSize(1);
    }

    private static DeviceReady ready() {
        return new DeviceReady(DeviceReady.Availability.OK, DeviceReady.Availability.OK, DeviceReady.Prefetch.OK);
    }

    /** 공급자 폭주 · 실행기 지연으로 일반 편지함이 상한에 닿은 배압 상태를 만든다. */
    private static void fillGeneralMailbox(SessionHarness harness) {
        int capacity = harness.settings.mailboxCapacity();
        for (int i = harness.mailbox.generalSize(); i < capacity; i++) {
            assertThat(harness.mailbox.offerGeneral(new SessionMailbox.ProviderEventReceived(
                    new ProviderEvent.UsageUpdated(0.0), harness.clock.now(), harness.clock.monotonicNanos()))).isTrue();
        }
        assertThat(harness.mailbox.generalSize()).isEqualTo(capacity);
    }
}
