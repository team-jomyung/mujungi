package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 참여 확인 흐름 시험 (L3 · FR2.1 ~ FR2.4 · BR2.1 ~ BR2.7 · BR3.1 ~ BR3.3 · BR4.7 · NFR5 · functional-spec WF2 · WF6).
 *
 * <p>가변 시계 실행기 하네스로 WAITING_CHILD 진입 · 시작 안내 · 재생 완료부터 열리는 창(19 · 21초) · 결정표 칸 · 최대 3번 안내 · 빠진 문구 ·
 * 참여 전 공급자 응답 취소 · DECLINED 인사 순서를 본다.
 */
@Tag("u4b")
class SessionJoinU4bTest {

    private static final String INTRO = "안녕, 무중이야. 오늘 나랑 이야기할래?";
    private static final String REPROMPT = "천천히 생각해도 돼. 이야기할래?";
    private static final String ALTERNATIVE = "그럼 다른 이야기를 해 볼까?";

    private final SessionHarness h = new SessionHarness();

    private void withPrompts() {
        h.extraApproved.put(SessionExecutor.INTRO_FREE_TALK, INTRO);
        h.extraApproved.put(SessionExecutor.INTRO_PRACTICE, "연습 시작 안내");
        h.extraApproved.put(SessionExecutor.RE_PROMPT, REPROMPT);
        h.extraApproved.put(SessionExecutor.DECLINE_ALTERNATIVE, ALTERNATIVE);
    }

    private void toWaitingChild() {
        h.boot();
        h.ackAndReady();
    }

    @Test
    void readinessEntersWaitingChildPlaysTheIntroAndOpensTheRelayWithoutRecording() {
        withPrompts();
        h.rawAudioConsent = true;
        toWaitingChild();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(h.executor.relay().isAccepting()).isTrue();
        assertThat(h.recordings.opened()).as("참여 전 원음은 쓰지 않는다(U4b-J2)").isEmpty();
        assertThat(plays()).extracting(PlayApprovedContent::contentCode).containsExactly(SessionExecutor.INTRO_FREE_TALK);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).containsExactly(View.LISTENING);
        // 보낸 순간 말풍선 한 행(BR3.1)
        assertThat(h.store.transcripts).singleElement().satisfies(row -> {
            assertThat(row.speaker()).isEqualTo(TranscriptEntry.Speaker.AI);
            assertThat(row.text()).isEqualTo(INTRO);
            assertThat(row.approvedForDisplay()).isTrue();
            assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        });
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).as("창은 재생 완료부터").isFalse();
        assertThat(events(SessionExecutor.EVENT_JOIN_PROMPTED)).hasSize(1);
    }

    @Test
    void aReplyAt19SecondsAfterThePlaybackMarkStartsAndSendsOneResponseCreate() {
        withPrompts();
        h.rawAudioConsent = true;
        toWaitingChild();
        h.advanceAlive(Duration.ofSeconds(5));
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.advanceAlive(Duration.ofSeconds(19));
        h.childSays("i-1", "응");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.startedAt()).isEqualTo(h.clock.now());
        assertThat(row.statusVersion()).as("PREPARING 1 → DEVICE_ACK → WAITING_CHILD → ACTIVE").isEqualTo(4);
        h.launcher.runAll("provider-create-");
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).hasSize(1);
        assertThat(h.recordings.opened()).as("원음 기록은 ACTIVE부터").hasSize(1);
        assertThat(h.store.transcripts).as("참여 대답은 저장하지 않는다(BR2.5)")
                .noneMatch(entry -> entry.speaker() == TranscriptEntry.Speaker.CHILD);
        assertThat(events(SessionExecutor.EVENT_JOIN_ACCEPTED)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).isEqualTo("{\"prompts\":1}"));
    }

    @Test
    void silenceUntil21SecondsRepromptsAndASecondSilenceDeclinesWithTheFarewell() {
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.advanceAlive(Duration.ofSeconds(19));
        assertThat(plays()).hasSize(1);
        h.advanceAlive(Duration.ofSeconds(2));
        assertThat(plays()).extracting(PlayApprovedContent::contentCode)
                .containsExactly(SessionExecutor.INTRO_FREE_TALK, SessionExecutor.RE_PROMPT);

        mark(plays().getLast(), PlaybackMark.Status.COMPLETED);
        h.advanceAlive(Duration.ofSeconds(21));

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.DECLINED);
        assertThat(row.endReason()).isEqualTo(EndReason.DECLINED);
        assertThat(h.transitions.extrasApplied()).singleElement()
                .satisfies(applied -> assertThat(applied.extras().eraseTranscripts()).as("전사 삭제(BR3.2)").isTrue());
        // 인사: STOP_OUTPUT 없이 마무리 문구 1회, 말풍선 없음
        assertThat(h.link.sent(StopOutput.class)).isEmpty();
        PlayApprovedContent farewell = plays().getLast();
        assertThat(farewell.contentCode()).isEqualTo(PlayApprovedContent.CLOSING);
        assertThat(h.store.transcripts).noneMatch(entry -> "CLOSING".equals(entry.text()));
        assertThat(h.link.sent(CloseTransport.class)).isEmpty();

        markFarewell(farewell, PlaybackMark.Status.COMPLETED);

        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.DECLINED);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).last().isEqualTo(View.STANDBY);
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void aRefusalGetsTheAlternativeAndASecondRefusalDeclines() {
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.childSays("i-1", "싫어");
        assertThat(plays()).extracting(PlayApprovedContent::contentCode).last()
                .isEqualTo(SessionExecutor.DECLINE_ALTERNATIVE);
        assertThat(events(SessionExecutor.EVENT_JOIN_ALTERNATIVE_OFFERED)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).contains("REFUSE").doesNotContain("싫어"));

        mark(plays().getLast(), PlaybackMark.Status.COMPLETED);
        h.childSays("i-2", "안 할래");

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
    }

    @Test
    void aTopicRefusalIsNotAnEndAndTheChildCanStillJoin() {
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.childSays("i-1", "이거 말고 다른 거");
        mark(plays().getLast(), PlaybackMark.Status.COMPLETED);
        h.childSays("i-2", "좋아");

        assertThat(plays()).extracting(PlayApprovedContent::contentCode)
                .containsExactly(SessionExecutor.INTRO_FREE_TALK, SessionExecutor.DECLINE_ALTERNATIVE);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void anUnclearReplyUsesTheRepromptOnceThenTheSameWindowKeepsGoing() {
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.childSays("i-1", "음");
        mark(plays().getLast(), PlaybackMark.Status.COMPLETED);
        h.childSays("i-2", "어디");

        assertThat(plays()).extracting(PlayApprovedContent::contentCode)
                .containsExactly(SessionExecutor.INTRO_FREE_TALK, SessionExecutor.RE_PROMPT);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        h.advanceAlive(Duration.ofSeconds(20));
        assertThat(h.transitions.status(h.sessionId)).as("재안내를 쓴 뒤 무응답은 DECLINED").isEqualTo(SessionStatus.DECLINED);
    }

    @Test
    void promptsNeverExceedThreeAcrossRepromptAndAlternative() {
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.childSays("i-1", "음");
        mark(plays().getLast(), PlaybackMark.Status.COMPLETED);
        h.childSays("i-2", "싫어");
        mark(plays().getLast(), PlaybackMark.Status.COMPLETED);
        h.childSays("i-3", "모르겠는데");
        h.advanceAlive(Duration.ofSeconds(20));

        assertThat(plays()).filteredOn(play -> !PlayApprovedContent.CLOSING.equals(play.contentCode())).hasSize(3);
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
    }

    @Test
    void aReplyWhileTheIntroIsStillPlayingIsHandledAtOnce() {
        withPrompts();
        toWaitingChild();

        h.childSays("i-1", "응");

        assertThat(h.executor.status()).as("창 시작을 기다리지 않는다(R-11)").isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void withoutThePromptsInThePackageTheClockStillRunsToDeclinedAndTheMissingCodesAreReportedOnce() {
        toWaitingChild();
        assertThat(plays()).isEmpty();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).as("곧바로 재생 완료로 본다").isTrue();

        h.advanceAlive(Duration.ofSeconds(20));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        h.advanceAlive(Duration.ofSeconds(20));

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
        assertThat(events(SessionExecutor.EVENT_APPROVED_CONTENT_MISSING)).extracting(SessionEvent::getPayloadJson)
                .containsExactly("{\"code\":\"INTRO_FREE_TALK\"}", "{\"code\":\"RE_PROMPT\"}");
        // 마무리 문구(필수)는 있다 → 인사 1회
        assertThat(plays()).extracting(PlayApprovedContent::contentCode).containsExactly(PlayApprovedContent.CLOSING);
    }

    @Test
    void providerResponsesBeforeJoiningAreCancelledAndNeverReachTheDevice() {
        withPrompts();
        toWaitingChild();

        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("resp-early", "안녕!"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("resp-early", new byte[480]));
        h.provider.last().emit(new ProviderEvent.OutputDone("resp-early"));
        h.drain();
        h.advanceAlive(Duration.ofSeconds(1));
        h.launcher.runAll("provider-cancel-");

        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCancel.class))
                .extracting(ProviderRequestFactory.ResponseCancel::responseId).containsExactly("resp-early");
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.store.transcripts).noneMatch(entry -> "안녕!".equals(entry.text()));
    }

    @Test
    void theFarewellIsCappedByTheClosingConfirmWhenNoMarkComes() {
        toWaitingChild();
        h.advanceAlive(Duration.ofSeconds(40));
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
        assertThat(h.link.sent(CloseTransport.class)).isEmpty();

        h.advanceAlive(h.settings.closingConfirm());

        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.DECLINED);
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void aNewSessionOnTheSameDeviceDropsTheRemainingFarewellWithoutDeviceCommands() {
        toWaitingChild();
        h.advanceAlive(Duration.ofSeconds(40));
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);

        h.link.bindSession(UUID.randomUUID());
        h.mailbox.putSafety(new SessionMailbox.DeviceTakenOver(UUID.randomUUID()));
        h.drain();

        assertThat(h.link.sent(CloseTransport.class)).isEmpty();
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void guardianStopWhileWaitingClosesWithItsReasonAndStopsTheJoinWindow() {
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();

        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.GUARDIAN_STOP);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).isFalse();
        h.childSays("i-1", "응");
        assertThat(h.executor.status()).as("CLOSING에서 참여 대답은 전이를 일으키지 않는다").isEqualTo(SessionStatus.CLOSING);
    }

    @Test
    void aPracticeSessionPlaysThePracticeIntro() {
        withPrompts();
        h.activityType = ActivityType.PRACTICE;
        toWaitingChild();

        assertThat(plays()).extracting(PlayApprovedContent::contentCode).containsExactly(SessionExecutor.INTRO_PRACTICE);
    }

    @Test
    void aLostProviderWhileWaitingAbortsTheSession() {
        toWaitingChild();
        h.provider.last().emit(new ProviderEvent.Closed());
        h.drain();

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.ABORTED);
        assertThat(row.endReason()).isEqualTo(EndReason.DISCONNECT);
    }

    @Test
    void anUnreportedIntroPlaybackStillOpensTheWindowAtTheOutputLimit() {
        withPrompts();
        toWaitingChild();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).isFalse();

        h.advanceAlive(h.settings.playbackExpiry());

        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).isTrue();
    }

    private List<PlayApprovedContent> plays() {
        return h.link.sent(PlayApprovedContent.class);
    }

    private void mark(PlayApprovedContent play, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private void markFarewell(PlayApprovedContent play, PlaybackMark.Status status) {
        mark(play, status);
    }

    private List<SessionEvent> events(String type) {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> event.getEventType().equals(type)).toList();
    }
}
