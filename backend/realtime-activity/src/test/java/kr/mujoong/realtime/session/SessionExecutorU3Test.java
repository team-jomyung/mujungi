package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.DeviceReady;
import kr.mujoong.contract.ws.message.DeviceState;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.testing.FakeDeviceLink;
import kr.mujoong.realtime.testing.FakeSessionGaps;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 실행기 U3 단위 시험(design.md 4 · 5절 · U3-J4 · J5 · J7 · J9 · J10 · J18 · J23 · AC6.2.4 · AC3.2.10(b)).
 *
 * <p>연결 교체(이어가기 · 새 기기) · 끊김 구간 · 미확인 명령 재전달 · 표시 상태 기억 · DEVICE_STATE 기록 · 끼어들기를 실제 스레드 없이 확인한다.
 * 세션 전이 표는 U1 그대로다(U3-J1).
 */
@Tag("u3")
class SessionExecutorU3Test {

    private final SessionHarness h = new SessionHarness();

    @Test
    void aLostLinkOpensAGapAndTheResumedReplacementClosesItAndReannouncesTheView() {
        h.activate();
        FakeDeviceLink old = h.link;
        long generation = h.executor.outputGeneration();

        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(old.connectionId()));
        h.drain();
        assertThat(h.gaps.opened()).singleElement().satisfies(gap -> {
            assertThat(gap.sessionId()).isEqualTo(h.sessionId);
            assertThat(gap.kind()).isEqualTo(SessionGapRepository.Kind.DISCONNECT);
            assertThat(gap.startedAt()).isEqualTo(h.clock.now());
        });
        assertThat(h.executor.hasOpenGap()).isTrue();
        assertThat(h.executor.outputGeneration()).isGreaterThan(generation);

        h.clock.advance(Duration.ofSeconds(3));
        FakeDeviceLink fresh = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fresh, true, h.clock.now()));
        h.drain();

        FakeSessionGaps.Opened opened = h.gaps.opened().get(0);
        assertThat(h.gaps.closed()).containsExactly(new FakeSessionGaps.Closed(opened.id(), h.clock.now()));
        assertThat(h.executor.hasOpenGap()).isFalse();
        assertThat(h.executor.boundConnectionId()).isEqualTo(fresh.connectionId());
        assertThat(fresh.boundSession()).isEqualTo(h.sessionId);
        assertThat(old.boundSession()).isNull();
        // 이어가기: START_SESSION 없이 표시 상태만 다시 알린다(U3-J4)
        assertThat(fresh.sent(StartSession.class)).isEmpty();
        assertThat(fresh.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.LISTENING);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.DISCONNECT)).isTrue();
        // 입력 프레임은 새 연결의 것만 받는다
        assertThat(h.executor.offerAudio(fresh.connectionId(), 1, new byte[960], 0)).isTrue();
        assertThat(h.executor.offerAudio(old.connectionId(), 2, new byte[960], 0)).isFalse();
        assertThat(events()).filteredOn(event -> event.getEventType().equals(EndingFlow.EVENT_LINK_REPLACED))
                .singleElement().satisfies(event -> assertThat(event.getPayloadJson()).isEqualTo("{\"resumed\":true}"));
    }

    @Test
    void aFreshDeviceWithoutResumeGetsStartSessionAgainWithTheSameOpIdAndItsLateReadyIsIgnored() {
        h.activate();
        UUID startOpId = h.startOpId();
        int transitions = h.transitions.applied().size();

        FakeDeviceLink fresh = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fresh, false, h.clock.now()));
        h.drain();

        // U3-J5: 세션을 모르는 기기에는 같은 opId · 같은 승인 음성의 START_SESSION부터, 그 뒤 표시 상태
        assertThat(fresh.sent()).extracting(sent -> sent.payload().getClass().getSimpleName())
                .containsExactly("StartSession", "SessionState");
        assertThat(fresh.sent().get(0).opId()).isEqualTo(startOpId);
        assertThat(((StartSession) fresh.sent().get(0).payload()).approvedAudio()).hasSize(2);
        assertThat(fresh.sent().get(0).sessionId()).isEqualTo(h.sessionId);

        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(fresh.connectionId(), startOpId, h.clock.now()));
        h.mailbox.offerGeneral(new SessionMailbox.DeviceReadyReceived(fresh.connectionId(),
                new DeviceReady(DeviceReady.Availability.OK, DeviceReady.Availability.OK, DeviceReady.Prefetch.OK),
                h.clock.now()));
        h.drain();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.transitions.applied()).hasSize(transitions);
        assertThat(events()).filteredOn(event -> event.getEventType().equals(EndingFlow.EVENT_LINK_REPLACED))
                .singleElement().satisfies(event -> assertThat(event.getPayloadJson()).isEqualTo("{\"resumed\":false}"));
    }

    @Test
    void unackedCommandsAreResentOnTheNewLinkWithTheirOpIdsButAStaleStopOutputIsNot() {
        h.activate();
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);
        FakeDeviceLink.Sent play = h.link.sent().stream().filter(sent -> sent.payload() instanceof PlayApprovedContent)
                .findFirst().orElseThrow();
        assertThat(play.opId()).isNotNull();
        // STOP_OUTPUT · PLAY_APPROVED_CONTENT 둘 다 ACK 전이다(START_SESSION은 ACK를 받았다)
        assertThat(h.executor.pendingCommandCount()).isEqualTo(2);

        FakeDeviceLink fresh = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fresh, true, h.clock.now()));
        h.drain();

        // U3-J7: 마무리 문구는 같은 opId로 다시, 낡은 STOP_OUTPUT은 다시 보내지 않는다
        assertThat(fresh.sent(StopOutput.class)).isEmpty();
        assertThat(fresh.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.CLOSING);
        List<FakeDeviceLink.Sent> replayed = fresh.sent().stream().filter(sent -> sent.payload() instanceof PlayApprovedContent)
                .toList();
        assertThat(replayed).hasSize(1);
        assertThat(replayed.get(0).opId()).isEqualTo(play.opId());
        assertThat(((PlayApprovedContent) replayed.get(0).payload()).playbackId())
                .isEqualTo(((PlayApprovedContent) play.payload()).playbackId());

        // 새 연결의 ACK가 미확인 명령을 지우고, 같은 playbackId의 끝 표식으로 마무리가 이어진다
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(fresh.connectionId(), play.opId(), h.clock.now()));
        h.drain();
        assertThat(h.executor.pendingCommandCount()).isEqualTo(1);
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(fresh.connectionId(),
                new PlaybackMark(((PlayApprovedContent) play.payload()).playbackId(), PlaybackMark.Status.COMPLETED, null,
                        h.clock.now(), null, null), h.clock.now()));
        h.drain();
        assertThat(fresh.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.GUARDIAN_STOP);
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(fresh.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED);
        assertThat(h.executor.view()).isEqualTo(View.STANDBY);
        assertThat(h.gaps.opened()).hasSize(1);
        assertThat(h.gaps.closed()).hasSize(1);
    }

    @Test
    void inTheStartPhaseStartSessionIsResentOnlyWhileUnackedOrForADeviceThatDoesNotResume() {
        h.boot();
        UUID startOpId = h.startOpId();
        assertThat(h.executor.pendingCommandCount()).isEqualTo(1);

        FakeDeviceLink second = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(second, true, h.clock.now()));
        h.drain();
        assertThat(second.sent(StartSession.class)).hasSize(1);
        assertThat(second.sent().get(0).opId()).isEqualTo(startOpId);
        assertThat(h.gaps.opened()).as("시작 단계에는 끊김 구간이 없다").isEmpty();

        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(second.connectionId(), startOpId, h.clock.now()));
        h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DEVICE_ACK);
        assertThat(h.executor.pendingCommandCount()).isZero();

        FakeDeviceLink third = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(third, true, h.clock.now()));
        h.drain();
        assertThat(third.sent()).as("ACK를 받은 START_SESSION은 이어가는 기기에 다시 보내지 않는다").isEmpty();

        FakeDeviceLink fourth = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fourth, false, h.clock.now()));
        h.drain();
        assertThat(fourth.sent(StartSession.class)).as("세션을 모르는 기기에는 다시 보낸다").hasSize(1);
        assertThat(fourth.sent().get(0).opId()).isEqualTo(startOpId);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DEVICE_ACK);
    }

    @Test
    void deviceStateChangesAreRecordedOncePerChangeAndOnlyFromTheBoundConnection() {
        h.activate();

        state(h.link.connectionId(), DeviceState.Mic.LOST, null);
        state(h.link.connectionId(), DeviceState.Mic.LOST, null);
        state(UUID.randomUUID(), DeviceState.Mic.OK, DeviceState.Player.ERROR);
        state(h.link.connectionId(), DeviceState.Mic.OK, null);
        state(h.link.connectionId(), null, DeviceState.Player.ERROR);
        state(h.link.connectionId(), null, DeviceState.Player.OK);
        state(h.link.connectionId(), DeviceState.Mic.OK, null);

        assertThat(events()).filteredOn(event -> event.getEventType().startsWith("DEVICE_"))
                .extracting(SessionEvent::getEventType)
                .containsExactly(EndingFlow.EVENT_MIC_LOST, EndingFlow.EVENT_MIC_OK,
                        EndingFlow.EVENT_PLAYER_ERROR);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aLocalVoiceStopMarkRecordsBargeInWithThePlayedPositionAndStopsTheRemainingFrames() {
        h.activate();
        h.speak("공룡은 아주 오래전에 살았어", 48_000);
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).hasSize(1);
        OutputBundle bundle = h.link.outputs().get(0);
        long generation = h.executor.outputGeneration();
        int cleared = h.link.clearAudioCalls();

        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(bundle.playbackId(), PlaybackMark.Status.PROGRESS, new PlaybackMark.PlayedRange(0L, 1_200L),
                        h.clock.now(), null, null), h.clock.now()));
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(bundle.playbackId(), PlaybackMark.Status.STOPPED, null, h.clock.now(),
                        PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY, null), h.clock.now()));
        h.drain();

        // SD-17: 세대가 올라 송신기는 남은 프레임을 보내지 않는다
        assertThat(h.executor.outputGeneration()).isGreaterThan(generation);
        assertThat(bundle.stillCurrent().getAsBoolean()).isFalse();
        assertThat(h.link.clearAudioCalls()).isGreaterThan(cleared);
        assertThat(events()).filteredOn(event -> event.getEventType().equals(EndingFlow.EVENT_BARGE_IN))
                .singleElement().satisfies(event -> assertThat(event.getPayloadJson())
                        .isEqualTo("{\"playbackId\":\"" + bundle.playbackId() + "\",\"playedToMs\":1200}"));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        // 멈춘 재생의 전사 원문은 이벤트 payload에 없다
        assertThat(events()).noneMatch(event -> event.getPayloadJson() != null
                && event.getPayloadJson().contains("공룡"));
    }

    @Test
    void aStopMarkWithItsOwnRangeUsesThatRangeAndOtherStopCausesAreNotBargeIns() {
        h.activate();
        UUID playbackId = UUID.randomUUID();
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(playbackId, PlaybackMark.Status.STOPPED, new PlaybackMark.PlayedRange(0L, 350L),
                        h.clock.now(), PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY, null), h.clock.now()));
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(UUID.randomUUID(), PlaybackMark.Status.STOPPED, null, h.clock.now(),
                        PlaybackMark.StopCause.STOP_OUTPUT, null), h.clock.now()));
        h.drain();

        assertThat(events()).filteredOn(event -> event.getEventType().equals(EndingFlow.EVENT_BARGE_IN))
                .singleElement().satisfies(event -> assertThat(event.getPayloadJson())
                        .isEqualTo("{\"playbackId\":\"" + playbackId + "\",\"playedToMs\":350}"));
    }

    @Test
    void theGapStaysOpenWhenTheSessionAbortsWhileDisconnectedAndIsNeverOpenedBeforeActive() {
        h.boot();
        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.drain();
        assertThat(h.gaps.opened()).as("시작 단계의 끊김은 구간이 아니다").isEmpty();

        h.ackAndReady();
        // U4b(FR11.3 활성화 경로): 참여 응답 한 번으로 ACTIVE
        h.join();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.drain();
        assertThat(h.gaps.opened()).as("같은 끊김에 구간은 하나다").hasSize(1);

        h.advance(Duration.ofSeconds(41));

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ABORTED);
        assertThat(h.gaps.closed()).as("복구하지 못한 구간은 열린 채 남는다(U3-J18)").isEmpty();
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void aFailedGapWriteIsToleratedAndTheReplacementStillRebinds() {
        h.gaps.failOpen(true);
        h.activate();
        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.drain();
        assertThat(h.executor.hasOpenGap()).isFalse();

        FakeDeviceLink fresh = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fresh, true, h.clock.now()));
        h.drain();

        assertThat(h.gaps.closed()).isEmpty();
        assertThat(h.executor.boundConnectionId()).isEqualTo(fresh.connectionId());
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void theViewFollowsEverySessionStateSentAndASameConnectionReplacementIsANoOp() {
        h.create();
        assertThat(h.executor.view()).isEqualTo(View.STANDBY);
        h.boot();
        h.ackAndReady();
        assertThat(h.executor.view()).isEqualTo(View.LISTENING);
        int sent = h.link.sent().size();

        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(h.link, true, h.clock.now()));
        h.drain();
        assertThat(h.link.sent()).as("같은 연결은 다시 묶지 않는다").hasSize(sent);

        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        assertThat(h.executor.view()).isEqualTo(View.CLOSING);
    }

    /** 기록기에 쌓인 세션 이벤트를 쓰게 하고(틱 = 플러시) 저장소에 쓴 것을 돌려준다. */
    private List<SessionEvent> events() {
        h.recorder.tick();
        return h.store.sessionEvents;
    }

    private void state(UUID connectionId, DeviceState.Mic mic, DeviceState.Player player) {
        h.mailbox.offerGeneral(new SessionMailbox.DeviceStateReceived(connectionId, new DeviceState(mic, player, null, null),
                h.clock.now()));
        h.drain();
    }
}
