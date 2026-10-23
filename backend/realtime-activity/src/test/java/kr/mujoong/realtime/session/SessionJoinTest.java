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
@Tag("session-runtime")
@org.junit.jupiter.api.Tag("service-guardrail")
class SessionJoinTest {

    private static final String INTRO = ServiceGuardrailFixtures.TEXT;
    private static final String REPROMPT = ServiceGuardrailFixtures.TEXT;
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
        assertThat(events(JoinFlow.EVENT_JOIN_PROMPTED)).hasSize(1);
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
        // 응답 요청은 출력 세대를 올리고 끊김을 확인한 뒤에 한 번 나간다(U11 W9)
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).isEmpty();
        h.settleSend();
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).hasSize(1);
        assertThat(h.recordings.opened()).as("원음 기록은 ACTIVE부터").hasSize(1);
        // 참여 확인 중에는 저장하지 않지만, 참여를 수락한 그 대답은 수락 때 첫 아이 말 한 행으로 남는다(BR2.5 개정)
        assertThat(h.store.transcripts).as("수락한 참여 대답만 아이 행으로 남는다")
                .filteredOn(entry -> entry.speaker() == TranscriptEntry.Speaker.CHILD).singleElement()
                .satisfies(entry -> assertThat(entry.text()).isEqualTo("응"));
        assertThat(events(JoinFlow.EVENT_JOIN_ACCEPTED)).singleElement()
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
        assertThat(h.transitions.extrasApplied()).filteredOn(applied -> applied.extras().eraseTranscripts()).singleElement()
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
    void aRefusalDeclinesImmediatelyWithoutAnAlternativeAndASecondReplyCannotReopenIt() {
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.childSays("i-1", "싫어");
        assertThat(plays()).extracting(PlayApprovedContent::contentCode).doesNotContain(SessionExecutor.DECLINE_ALTERNATIVE);
        assertThat(events(JoinFlow.EVENT_JOIN_ALTERNATIVE_OFFERED)).isEmpty();
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).participation()).isEqualTo(SessionGuardrailFacts.Participation.REFUSED);

        mark(plays().getLast(), PlaybackMark.Status.COMPLETED);
        h.childSays("i-2", "안 할래");

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
    }

    @Test
    void aTopicRefusalIsNotAnEndAndStartsTheConversationAtOnce() {
        // 자유대화의 시작 의사는 '거절인가'만 본다(contracts/deviations.md FT-1022-b): 화제 거절도 거절이 아니므로 재안내 없이 시작한다
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.childSays("i-1", "이거 말고 다른 거");

        assertThat(plays()).extracting(PlayApprovedContent::contentCode).containsExactly(SessionExecutor.INTRO_FREE_TALK);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void anUnclearReplyStartsTheConversationAtOnceWithoutAReprompt() {
        // 불명확한 대답을 판정 AI(J1)에 넘기거나 재안내로 되묻던 단계는 없앴다(contracts/deviations.md FT-1022-b)
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        h.childSays("i-1", "음");

        assertThat(plays()).extracting(PlayApprovedContent::contentCode).containsExactly(SessionExecutor.INTRO_FREE_TALK);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).isEmpty();
        assertThat(events(JoinFlow.EVENT_JOIN_ACCEPTED)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).isEqualTo("{\"prompts\":1}"));
    }

    @Test
    void promptsNeverExceedTwoAndARefusalNeverAddsAnAlternative() {
        withPrompts();
        toWaitingChild();
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);

        // 재안내는 무응답 때만 나온다
        h.advanceAlive(Duration.ofSeconds(21));
        mark(plays().getLast(), PlaybackMark.Status.COMPLETED);
        h.childSays("i-1", "싫어");
        h.advanceAlive(Duration.ofSeconds(20));

        assertThat(plays()).filteredOn(play -> !PlayApprovedContent.CLOSING.equals(play.contentCode()))
                .extracting(PlayApprovedContent::contentCode)
                .containsExactly(SessionExecutor.INTRO_FREE_TALK, SessionExecutor.RE_PROMPT);
        assertThat(plays()).extracting(PlayApprovedContent::contentCode).doesNotContain(SessionExecutor.DECLINE_ALTERNATIVE);
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
    }

    @Test
    void aReplyWhileTheIntroIsStillPlayingCannotAcceptParticipation() {
        withPrompts();
        toWaitingChild();

        h.childSays("i-1", "응");

        assertThat(h.executor.status()).as("FR2: 실제 안내 완료 전 수락은 재사용하지 않는다").isEqualTo(SessionStatus.WAITING_CHILD);
        mark(plays().getFirst(), PlaybackMark.Status.COMPLETED);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        h.childSays("i-2", "응");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void withoutThePromptsTheSessionFailsTechnicallyRatherThanInventingDeliveryOrSilence() {
        h.extraApproved.remove(SessionExecutor.INTRO_FREE_TALK);
        toWaitingChild();
        assertThat(plays()).isEmpty();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).isFalse();

        h.advanceAlive(Duration.ofSeconds(20));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.FAILED);
        h.advanceAlive(Duration.ofSeconds(20));

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).notice()).isEqualTo(SessionGuardrailFacts.Notice.FAILED);
        assertThat(h.transitions.guardrailFacts.get(h.sessionId).participation()).isEqualTo(SessionGuardrailFacts.Participation.UNKNOWN);
        assertThat(plays()).isEmpty();
    }

    @Test
    void providerResponsesBeforeJoiningAreDroppedAndNeverReachTheDevice() {
        withPrompts();
        toWaitingChild();

        h.speak("안녕!", 480);
        h.advanceAlive(Duration.ofSeconds(1));

        // U11 BR11.13: 참여 전 시작된 자동 응답은 공급자 응답 취소 대신 출력 보류가 조각마다 버린다(구간도 행도 열지 않는다)
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).isEmpty();
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.store.transcripts).noneMatch(entry -> "안녕!".equals(entry.text()));
    }

    @Test
    void theFarewellIsCappedByTheClosingConfirmWhenNoMarkComes() {
        declineByActualSilence();
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
        assertThat(h.link.sent(CloseTransport.class)).isEmpty();

        h.advanceAlive(h.settings.closingConfirm());

        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.DECLINED);
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void aNewSessionOnTheSameDeviceDropsTheRemainingFarewellWithoutDeviceCommands() {
        declineByActualSilence();
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
    void anUnreportedIntroPlaybackFailsAtTheOutputLimitWithoutOpeningAWindow() {
        withPrompts();
        toWaitingChild();
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).isFalse();

        h.advanceAlive(h.settings.playbackExpiry());

        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).isFalse();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.FAILED);
    }

    private void declineByActualSilence() {
        toWaitingChild();
        h.completeNotice(); h.advanceAlive(Duration.ofSeconds(20));
        h.completeNotice(); h.advanceAlive(Duration.ofSeconds(20));
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
