package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.testing.FakeAudioRecordings;
import kr.mujoong.realtime.testing.FakeDeviceLink;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PR #2 리뷰 반영 시험(docs/units/u3/report.md '리뷰 반영' 절). 건마다 먼저 실패하던 재현 시험이다.
 *
 * <ul>
 *   <li>1 아이 소리 유실: 이어가기가 아닌 새 연결은 입력 순번을 0부터 다시 센다.
 *   <li>3 대화 멈춤: 종료 의도 오판 뒤 아이가 대화를 이어 가면 출력 막힘이 풀린다.
 *   <li>4 재접속 멈춤: HELLO_OK.session 뒤 세션이 먼저 끝나면 새 연결을 대기로 돌려보낸다.
 *   <li>5 끝나 가는 세션: CLOSING에 새로 붙은 연결에는 START_SESSION 대신 CLOSE_TRANSPORT를 보낸다.
 *   <li>6 원음 범위: 기기로 실제 보낸 AI 음성 프레임만 기록한다.
 *   <li>7 자원 누수: 정리 단계 하나가 예외를 던져도 원음 기록은 닫힌다.
 *   <li>9 재생 추적: 다시 보낸 승인 문구 재생을 서버가 추적한다.
 * </ul>
 */
@Tag("u3")
class SessionExecutorReviewU3Test {

    private static final byte[] FRAME = new byte[960];

    private final SessionHarness h = new SessionHarness();

    // ------------------------------------------------------------------ 1 아이 소리 유실

    @Test
    void aFreshLinkThatRestartsItsFrameSequenceIsRelayedFromTheFirstFrame() {
        h.activate();
        for (int seq = 1; seq <= 300; seq++) {
            assertThat(h.executor.offerAudio(h.link.connectionId(), seq, FRAME, 0)).isTrue();
            h.executor.relay().relayAvailable();
        }
        assertThat(h.executor.relay().relayedFrames()).isEqualTo(300);

        FakeDeviceLink fresh = relink(false);

        // 새로 켠 페이지는 frameSeq 1부터 보낸다 — 옛 순번(300)을 넘을 때까지 버리면 그동안 아이 말이 공급자에 가지 않는다
        assertThat(h.executor.offerAudio(fresh.connectionId(), 1, FRAME, 0)).isTrue();
        assertThat(h.executor.offerAudio(fresh.connectionId(), 2, FRAME, 0)).isTrue();
        assertThat(h.executor.offerAudio(fresh.connectionId(), 2, FRAME, 0)).as("같은 연결 안의 순서 규칙은 그대로").isFalse();
        assertThat(h.executor.relay().relayAvailable()).isEqualTo(2);
    }

    @Test
    void aResumedLinkThatContinuesItsSequenceKeepsRelaying() {
        h.activate();
        assertThat(h.executor.offerAudio(h.link.connectionId(), 40, FRAME, 0)).isTrue();

        FakeDeviceLink fresh = relink(true);

        assertThat(h.executor.offerAudio(fresh.connectionId(), 41, FRAME, 0)).isTrue();
        assertThat(h.executor.offerAudio(h.link.connectionId(), 42, FRAME, 0)).as("옛 연결 프레임은 받지 않는다").isFalse();
        assertThat(h.executor.relay().relayAvailable()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 3 오판 뒤 복구

    @Test
    void aChildWhoKeepsTalkingAfterAStopIntentLiftsTheOutputBlockForNewResponsesOnly() {
        h.activate();
        done("i1", "그만할래");
        assertThat(eventTypes()).contains(SessionExecutor.EVENT_CHILD_STOP_INTENT);

        // 막힌 동안의 응답은 첫 조각에서 취소되고 기기로 가지 않는다
        emitComplete("r1", "그래 알았어");
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).isEmpty();

        // 아이가 종료가 아닌 말로 대화를 이어 간다 → 막힘을 푼다(취소한 응답은 그대로 버린다)
        done("i2", "아니야 공룡 얘기 더 해 줘");
        assertThat(eventTypes()).contains(SessionExecutor.EVENT_CHILD_STOP_LIFTED);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r1", new byte[4_800]));
        h.drain();
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).as("취소한 응답의 늦은 조각은 여전히 버린다").isEmpty();

        emitComplete("r2", "티라노는 아주 컸어");
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void anUnclearOrEmptyUtteranceAfterAStopIntentDoesNotLiftIt() {
        h.activate();
        done("i1", "무중아 그만할래");
        done("i2", "그만");
        done("i3", "");
        assertThat(eventTypes()).doesNotContain(SessionExecutor.EVENT_CHILD_STOP_LIFTED);

        emitComplete("r1", "응 그래");
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).isEmpty();
    }

    // ------------------------------------------------------------------ 4 재접속 멈춤

    @Test
    void aRelinkQueuedBehindTheSessionsEndSendsTheResumedDeviceBackToStandby() {
        h.activate();
        h.link.setOpen(false);
        FakeDeviceLink fresh = new FakeDeviceLink(h.deviceId, h.clock.now());
        // HELLO_OK.session(resumed)이 나간 직후, LinkReplaced보다 먼저 세션을 끝내는 명령이 처리된다
        CompletableFuture<EndOutcome> abandoned = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.CORE_ABANDONED, abandoned));
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fresh, true, h.clock.now()));
        h.drain();

        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(fresh.sent()).extracting(sent -> sent.payload().getClass().getSimpleName())
                .containsExactly("CloseTransport", "SessionState");
        assertThat(fresh.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.FAILED);
        assertThat(fresh.sent().get(0).opId()).isNotNull();
        assertThat(fresh.sent().get(0).sessionId()).isEqualTo(h.sessionId);
        assertThat(fresh.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.STANDBY);
        assertThat(fresh.boundSession()).isNull();
    }

    @Test
    void aNonResumedRelinkQueuedBehindTheEndOnlyGetsStandby() {
        h.activate();
        h.link.setOpen(false);
        FakeDeviceLink fresh = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.CORE_ABANDONED,
                new CompletableFuture<>()));
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fresh, false, h.clock.now()));
        h.drain();

        assertThat(fresh.sent()).extracting(sent -> sent.payload().getClass().getSimpleName())
                .containsExactly("SessionState");
        assertThat(fresh.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.STANDBY);
    }

    @Test
    void theStandDownHelperSendsCloseTransportOnlyToAResumedDeviceAndMapsTheEndingToAReason() {
        FakeDeviceLink resumed = new FakeDeviceLink(h.deviceId, h.clock.now());
        SessionExecutor.standDown(resumed, h.sessionId, SessionStatus.ABORTED, true);
        assertThat(resumed.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.DISCONNECT);

        FakeDeviceLink ended = new FakeDeviceLink(h.deviceId, h.clock.now());
        SessionExecutor.standDown(ended, h.sessionId, SessionStatus.ENDED_UNCONFIRMED, true);
        assertThat(ended.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.ENDED);

        FakeDeviceLink closed = new FakeDeviceLink(h.deviceId, h.clock.now());
        closed.setOpen(false);
        SessionExecutor.standDown(closed, h.sessionId, SessionStatus.FAILED, true);
        assertThat(closed.sent()).isEmpty();
    }

    // ------------------------------------------------------------------ 5 끝나 가는 세션

    @Test
    void aFreshDeviceThatJoinsAfterCloseTransportWasAckedGetsCloseTransportNotStartSession() {
        h.activate();
        guardianStop();
        PlayApprovedContent closing = closingPhrase(h.link);
        mark(h.link, closing.playbackId(), PlaybackMark.Status.COMPLETED);
        FakeDeviceLink.Sent close = h.link.sent().stream().filter(sent -> sent.payload() instanceof CloseTransport)
                .findFirst().orElseThrow();
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), close.opId(), h.clock.now()));
        h.drain();
        assertThat(h.executor.relay().isAccepting()).isFalse();

        // 기기가 CLOSE_TRANSPORT를 처리해 세션을 잊은 뒤 끊겼다 → resume 없이 다시 붙는다
        FakeDeviceLink fresh = relink(false);

        assertThat(fresh.sent(StartSession.class)).as("입력 창을 다시 열게 하지 않는다").isEmpty();
        assertThat(fresh.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.GUARDIAN_STOP);
        assertThat(h.executor.relay().isAccepting()).isFalse();

        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(fresh.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
        assertThat(h.transitions.status(h.sessionId)).as("30초 시한 전에 확인으로 끝난다").isEqualTo(SessionStatus.ENDED);
    }

    @Test
    void aFreshDeviceThatJoinsWhileTheClosingPhraseIsPendingSkipsItAndClosesTheTransportAtOnce() {
        h.activate();
        guardianStop();
        assertThat(h.executor.relay().isAccepting()).isTrue();

        FakeDeviceLink fresh = relink(false);

        assertThat(fresh.sent(StartSession.class)).isEmpty();
        assertThat(fresh.sent(PlayApprovedContent.class)).as("세션을 모르는 기기에 마무리 문구를 다시 보내지 않는다").isEmpty();
        assertThat(fresh.sent()).extracting(sent -> sent.payload().getClass().getSimpleName())
                .containsExactly("SessionState", "CloseTransport");
        assertThat(fresh.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.CLOSING);
        assertThat(h.executor.relay().isAccepting()).as("CLOSE_TRANSPORT 송신까지만 입력을 받는다(R3-8)").isFalse();
        assertThat(h.transitions.find(h.sessionId).orElseThrow().transportClosedAt()).isEqualTo(h.clock.now());

        // 옛 마무리 문구의 늦은 끝 표식은 CLOSE_TRANSPORT를 또 보내지 않는다
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(fresh.connectionId(),
                new PlaybackMark(closingPhrase(h.link).playbackId(), PlaybackMark.Status.STOPPED, null, h.clock.now(),
                        PlaybackMark.StopCause.DISCONNECTED, null), h.clock.now()));
        h.drain();
        assertThat(fresh.sent(CloseTransport.class)).hasSize(1);
    }

    @Test
    void aResumedDeviceInClosingStillGetsTheUnackedClosingPhraseAndNoExtraClose() {
        h.activate();
        guardianStop();
        PlayApprovedContent closing = closingPhrase(h.link);

        FakeDeviceLink fresh = relink(true);

        assertThat(fresh.sent(StartSession.class)).isEmpty();
        assertThat(fresh.sent(CloseTransport.class)).isEmpty();
        assertThat(fresh.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::playbackId)
                .containsExactly(closing.playbackId());
    }

    // ------------------------------------------------------------------ 6 원음 범위

    @Test
    void onlyAiFramesActuallyWrittenToTheDeviceAreRecorded() {
        h.rawAudioConsent = true;
        h.activate();
        OutputBundle bundle = respond("r1", "공룡은 멋져", 9_600);
        FakeAudioRecordings.Recorded recorded = h.recordings.opened().get(0);
        assertThat(recorded.aiBytes()).as("게이트 통과만으로는 기록하지 않는다").isEmpty();

        assertThat(h.link.deliver(bundle, h.clock.now())).isEqualTo(2);
        assertThat(recorded.aiBytes()).hasSize(9_600);
    }

    @Test
    void aiAudioCutByAGenerationChangeOrNeverWrittenIsNotRecorded() {
        h.rawAudioConsent = true;
        h.activate();
        OutputBundle cut = respond("r1", "공룡은 멋져", 9_600);
        // 끼어들기로 세대가 올라 송신기는 남은 프레임을 보내지 않는다
        mark(h.link, cut.playbackId(), PlaybackMark.Status.STARTED);
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(cut.playbackId(), PlaybackMark.Status.STOPPED, null, h.clock.now(),
                        PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY, null), h.clock.now()));
        h.drain();
        assertThat(h.link.deliver(cut, h.clock.now())).isZero();

        // 연결이 닫혀 음성 레인에 들어가지 못한 묶음(OUTBOUND_FULL)
        OutputBundle refused = respond("r2", "티라노는 컸어", 4_800);
        h.link.setOpen(false);
        assertThat(h.link.sendOutput(refused)).isFalse();

        assertThat(h.recordings.opened().get(0).aiBytes()).isEmpty();
    }

    // ------------------------------------------------------------------ 7 자원 누수

    @Test
    void theRecordingIsClosedEvenWhenAnEarlierTeardownStepThrows() {
        h.rawAudioConsent = true;
        h.activate();
        h.provider.last().failOnClose(true);
        Instant endedAt = h.clock.now();

        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.CORE_ABANDONED,
                new CompletableFuture<>()));
        h.drain();

        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.recordings.opened()).singleElement()
                .satisfies(recorded -> assertThat(recorded.closedAt()).isEqualTo(endedAt));
        assertThat(h.executor.relay().isAccepting()).isFalse();
        assertThat(h.removed).isTrue();
        assertThat(h.mailbox.isClosed()).isTrue();
    }

    // ------------------------------------------------------------------ 9 재생 추적

    @Test
    void aResentWaitingFillerIsTrackedSoASecondFillerDoesNotOverlapIt() {
        h.activate();
        emitComplete("r1", "진단해 보면 이래");
        h.advance(Duration.ofMillis(800));
        PlayApprovedContent filler = h.link.sent(PlayApprovedContent.class).get(0);
        assertThat(filler.contentCode()).isEqualTo(PlayApprovedContent.WAITING_FILLER);

        FakeDeviceLink fresh = relink(true);
        assertThat(fresh.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::playbackId)
                .containsExactly(filler.playbackId());
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.PLAYBACK_EXPIRY)).isTrue();

        // 다시 보낸 대기 문구가 도는 동안 또 폐기돼도 대기 문구를 겹쳐 보내지 않는다(R3-19)
        emitComplete("r2", "진단 결과야");
        h.advance(Duration.ofMillis(800));
        assertThat(fresh.sent(PlayApprovedContent.class)).hasSize(1);

        // 그 재생의 끝 표식이 추적을 지운다
        mark(fresh, filler.playbackId(), PlaybackMark.Status.COMPLETED);
        emitComplete("r3", "진단 결과야");
        h.advance(Duration.ofMillis(800));
        assertThat(fresh.sent(PlayApprovedContent.class)).hasSize(2);
    }

    // ------------------------------------------------------------------ 보조

    private FakeDeviceLink relink(boolean resumed) {
        FakeDeviceLink fresh = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fresh, resumed, h.clock.now()));
        h.drain();
        return fresh;
    }

    private void guardianStop() {
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);
    }

    private static PlayApprovedContent closingPhrase(FakeDeviceLink link) {
        return link.sent(PlayApprovedContent.class).stream()
                .filter(play -> PlayApprovedContent.CLOSING.equals(play.contentCode())).findFirst().orElseThrow();
    }

    private void mark(FakeDeviceLink link, UUID playbackId, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(link.connectionId(),
                new PlaybackMark(playbackId, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private OutputBundle respond(String responseId, String text, int bytes) {
        int before = h.link.outputs().size();
        emitComplete(responseId, text, bytes);
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).hasSize(before + 1);
        return h.link.outputs().get(before);
    }

    private void emitComplete(String responseId, String text) {
        emitComplete(responseId, text, 4_800);
    }

    private void emitComplete(String responseId, String text, int bytes) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(responseId, text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(responseId, new byte[bytes]));
        h.provider.last().emit(new ProviderEvent.OutputDone(responseId));
        h.drain();
    }

    private void done(String itemId, String transcript) {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone(itemId, transcript, 0.95));
        h.drain();
    }

    private List<String> eventTypes() {
        h.recorder.tick();
        return h.store.sessionEvents.stream().map(SessionEvent::getEventType).toList();
    }
}
