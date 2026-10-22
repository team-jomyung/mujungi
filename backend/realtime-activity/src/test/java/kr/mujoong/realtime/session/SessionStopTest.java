package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 아이 종료 의도 · 확인 질문 · 공통 마무리 시험 (L4 · FR3.1 · FR3.2 · BR4.1 ~ BR4.6 · BR2.6 · AC6.4.8).
 *
 * <p>STOP → 곧바로 CLOSING(CHILD_STOP) 마무리 순서(물음꼴 종료 표현 포함), 종료 확정 뒤 이전 응답이 재생되지 않음을 본다. 확인 질문
 * (CONFIRM → 질문 · 출력 막음 · 창)은 2026-10-22 결정으로 없앴으므로 불명확한 신호가 질문 없이 대화를 잇는지 본다.
 */
@Tag("session-runtime")
@org.junit.jupiter.api.Tag("service-guardrail")
class SessionStopTest {

    private static final String QUESTION = "이야기를 그만할까?";

    private final SessionHarness h = new SessionHarness();

    @Test
    void aClearStopClosesWithChildStopThroughTheCommonClosingSequence() {
        h.activate();
        h.childSays("i-1", "무중아 그만할래");

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(row.endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(row.outputStoppedAt()).as("STOP_OUTPUT 송신 시각").isEqualTo(h.clock.now());
        assertThat(h.link.sent(StopOutput.class)).extracting(StopOutput::reason)
                .containsExactly(StopOutput.Reason.CHILD_STOP);
        PlayApprovedContent closing = h.link.sent(PlayApprovedContent.class).getLast();
        assertThat(closing.contentCode()).isEqualTo(PlayApprovedContent.CLOSING);

        mark(closing, PlaybackMark.Status.COMPLETED);

        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.CHILD_STOP);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().transportClosedAt()).isNotNull();
    }

    @Test
    void aQuestionShapedStopPhraseClosesWithChildStopWithoutAQuestion() {
        h.extraApproved.put(SessionExecutor.STOP_CONFIRM, QUESTION);
        h.activate();
        h.childSays("i-1", "그만할래?");

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(row.endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(h.link.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::contentCode)
                .doesNotContain(SessionExecutor.STOP_CONFIRM);
        assertThat(h.store.transcripts).noneMatch(entry -> QUESTION.equals(entry.text()));
    }

    @Test
    void anUnclearSignalAsksNothingAndTheNextAnswerIsHeardAtOnce() {
        h.extraApproved.put(SessionExecutor.STOP_CONFIRM, QUESTION);
        h.activate();
        h.childSays("i-1", "그만");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.link.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::contentCode)
                .doesNotContain(SessionExecutor.STOP_CONFIRM);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
        assertThat(events(SessionExecutor.EVENT_STOP_CONFIRM_ASKED)).isEmpty();
        // 출력을 막지 않으므로 다음 응답은 평소대로 기기에 간다
        emitComplete("r-1", "티라노는 아주 컸어");
        h.advanceAlive(Duration.ofSeconds(1));
        assertThat(h.link.outputs()).hasSize(1);
    }

    @Test
    void afterTheEndIsConfirmedAnEarlierResponseIsNeverPlayed() {
        h.activate();
        h.speak("공룡은 멋져", 4_800);

        h.childSays("i-1", "그만할래");
        h.drain();
        h.advanceAlive(Duration.ofSeconds(1));

        assertThat(h.link.outputs()).isEmpty();
        // U11 BR3.4: 종료 확인 뒤 앞 응답은 공급자 응답 취소 대신 출력 세대를 올려 버린다 — 공급자로 나가는 것은 없다
        assertThat(h.launcher.names()).noneMatch(name -> name.startsWith("provider-"));
    }

    @Test
    void aStopHeardWhileTheDevicePlaysIsStillJudged() {
        h.activate();
        emitComplete("r-1", "같이 노래 부를까");
        h.advanceAlive(Duration.ofSeconds(1));
        PlaybackMark started = new PlaybackMark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.STARTED,
                null, h.clock.now(), null, null);
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(), started, h.clock.now()));
        h.drain();

        h.childSays("i-1", "무중아 그만할래");

        assertThat(h.executor.status()).as("AI 말 위에 겹쳐 한 종료 표현도 판정한다(2026-10-22 결정)").isEqualTo(SessionStatus.CLOSING);
    }

    private void emitComplete(String responseId, String text) {
        h.speak(text, 4_800);
    }

    private void mark(PlayApprovedContent play, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private List<SessionEvent> events(String type) {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> event.getEventType().equals(type)).toList();
    }
}
