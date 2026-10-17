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
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 아이 종료 의도 · 확인 질문 · 공통 마무리 시험 (L4 · FR3.1 · FR3.2 · BR4.1 ~ BR4.6 · BR2.6 · AC6.4.8).
 *
 * <p>STOP → 곧바로 CLOSING(CHILD_STOP) 마무리 순서, CONFIRM → 확인 질문(말풍선 · 출력 막음 · 재생 완료부터 창) → 확정 · 계속, 종료 확정 뒤
 * 이전 응답이 재생되지 않음을 본다.
 */
@Tag("u4b")
class SessionStopU4bTest {

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
    void anUnclearIntentAsksTheQuestionHoldsTheOutputAndAYesConfirmsTheEnd() {
        h.extraApproved.put(SessionExecutor.STOP_CONFIRM, QUESTION);
        h.activate();
        h.childSays("i-1", "쉬고 싶어");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        PlayApprovedContent question = h.link.sent(PlayApprovedContent.class).getLast();
        assertThat(question.contentCode()).isEqualTo(SessionExecutor.STOP_CONFIRM);
        assertThat(h.store.transcripts).anySatisfy(row -> {
            assertThat(row.text()).isEqualTo(QUESTION);
            assertThat(row.speaker()).isEqualTo(TranscriptEntry.Speaker.AI);
        });
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).as("창은 재생 완료부터").isFalse();
        // 확인 중의 공급자 응답은 취소하고 보내지 않는다(BR2.6)
        emitComplete("r-held", "그래 알았어");
        h.advanceAlive(Duration.ofSeconds(1));
        assertThat(h.link.outputs()).isEmpty();

        mark(question, PlaybackMark.Status.COMPLETED);
        h.childSays("i-2", "응");

        assertThat(h.transitions.find(h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
    }

    @Test
    void anotherReplyLiftsTheHoldAndHandsTheFloorBackOnce() {
        h.activate();
        h.launcher.runAll("provider-create-");
        int creates = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).size();
        h.childSays("i-1", "쉬고 싶어");
        h.childSays("i-2", "티라노가 제일 커");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.settleSend();
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).hasSize(creates + 1);
        emitComplete("r-1", "티라노는 아주 컸어");
        h.advanceAlive(Duration.ofSeconds(1));
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(events(SessionExecutor.EVENT_STOP_CONFIRM_RESOLVED)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).contains("CONTINUE").doesNotContain("티라노"));
    }

    @Test
    void noAnswerWithin20SecondsContinuesAndNineteenStillHolds() {
        h.activate();
        h.childSays("i-1", "쉬고 싶어");

        h.advanceAlive(Duration.ofSeconds(19));
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isTrue();
        h.advanceAlive(Duration.ofSeconds(2));

        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(events(SessionExecutor.EVENT_STOP_CONFIRM_RESOLVED)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).contains("EXPIRED"));
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
    void aStopHeardWhileTheDevicePlaysIsNotJudged() {
        h.activate();
        emitComplete("r-1", "같이 노래 부를까");
        h.advanceAlive(Duration.ofSeconds(1));
        PlaybackMark started = new PlaybackMark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.STARTED,
                null, h.clock.now(), null, null);
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(), started, h.clock.now()));
        h.drain();

        h.childSays("i-1", "무중아 그만할래");

        assertThat(h.executor.status()).as("기기 재생 중 전사는 판정하지 않는다(BR4.1)").isEqualTo(SessionStatus.ACTIVE);
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
