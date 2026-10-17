package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.NextAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 턴 실행기 연결 2 — 종료 의도 J6 (U11 W2 · BR6.x · BR7.x, 첫 판 검토 R-13 · R-14). 공급자와 판정기는 가짜, 값은 합성이다(R-F1 · R-F3).
 */
@Tag("u11")
class StopJudgmentExecutorTest {

    private final JudgmentExecutorSupport t = new JudgmentExecutorSupport();

    /** 출력권을 가진 시험 연습 확장점(연습 시계 · 전사 받기를 기록한다). */
    private static final class OwningExtension implements PracticeSessionExtension {
        final List<Utterance> utterances = new ArrayList<>();
        final List<ExtensionEvent> events = new ArrayList<>();
        PracticeOutput output;

        @Override public boolean controlsOutput() { return true; }

        @Override public Signal onActive(PracticeContext context, PracticeOutput out) {
            this.output = out;
            out.schedule("practice-clock", Duration.ofSeconds(10));
            return Signal.NONE;
        }

        @Override public Signal onChildUtterance(Utterance utterance, PracticeOutput out) {
            utterances.add(utterance);
            return Signal.NONE;
        }

        @Override public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput out) {
            events.add(event);
            return Signal.NONE;
        }

        @Override public void onSettle(EndContext end) { }

        @Override public Object practiceRecord() { return null; }
    }

    private OwningExtension owningPractice() {
        OwningExtension extension = new OwningExtension();
        t.h.activityType = ActivityType.PRACTICE;
        t.h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        t.h.practice = sessionId -> extension;
        return extension;
    }

    @Test
    void endClearClosesTheSessionWithTheLunaBasisAndNoConfirmationQuestion() {
        t.openStopJudgment();

        t.judge.confirm("END_CLEAR", 0.95, "NOT_END", 0.02);
        t.h.drain();

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(t.h.transitions.find(t.h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(t.events(SessionExecutor.EVENT_CHILD_STOP_INTENT)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).contains("LUNA_J6").doesNotContain(JudgmentExecutorSupport.UNCLEAR_STOP));
        assertThat(t.events(SessionExecutor.EVENT_STOP_CONFIRM_ASKED)).isEmpty();
        assertThat(t.h.link.sent(StopOutput.class)).isNotEmpty();
        assertThat(t.onlyRecord().nextAction()).isEqualTo(NextAction.CLOSE);
    }

    @Test
    void notEndGoesBackToTheConversationWithTodaysContinueInstruction() {
        t.openStopJudgment();
        t.h.settleSend();
        int before = t.appendedContents().size();

        t.judge.confirm("NOT_END", 0.9, "UNCLEAR", 0.05);
        t.h.drain();
        t.h.settleSend();

        assertThat(t.appendedContents()).hasSize(before + 1);
        assertThat(t.appendedContents().getLast()).isEqualTo(InstructionTemplates.ResponseStart.CONTINUE.content());
        assertThat(t.events(SessionExecutor.EVENT_STOP_CONFIRM_ASKED)).isEmpty();
        assertThat(t.onlyRecord().nextAction()).isEqualTo(NextAction.RESUME);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void unclearAndEveryAbstainFallBackToTodaysConfirmationQuestionAndItsEvents() {
        t.openStopJudgment();

        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();

        PlayApprovedContent question = t.h.link.sent(PlayApprovedContent.class).getLast();
        assertThat(question.contentCode()).isEqualTo(SessionExecutor.STOP_CONFIRM);
        assertThat(t.events(SessionExecutor.EVENT_STOP_CONFIRM_ASKED)).hasSize(1);
        assertThat(t.onlyRecord().nextAction()).isEqualTo(NextAction.ASK_STOP_CONFIRM);

        t.mark(question, kr.mujoong.contract.ws.message.PlaybackMark.Status.COMPLETED);
        t.h.childSays("i-2", "응");

        assertThat(t.events(SessionExecutor.EVENT_STOP_CONFIRM_RESOLVED)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).contains("STOP"));
        assertThat(t.h.transitions.find(t.h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.CHILD_STOP);
    }

    @Test
    void restInFreeTalkSendsTheAcknowledgeAndReturnInstructionOnce() {
        t.openStopJudgment();
        t.h.settleSend();
        int before = t.appendedContents().size();

        t.judge.confirm("REST", 0.9, "NOT_END", 0.05);
        t.h.drain();
        t.h.settleSend();
        t.h.speak("그래, 잠깐 쉬었다가 하자", 4_800);
        t.advance(Duration.ofSeconds(1));

        assertThat(t.appendedContents()).hasSize(before + 1);
        assertThat(t.appendedContents().getLast()).isEqualTo(InstructionTemplates.NextAction.REST_ACK_AND_RETURN.content());
        assertThat(t.onlyRecord().nextAction()).isEqualTo(NextAction.REST_ACK_AND_RETURN);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aRegisteredStopPhraseNeverCallsTheJudgeAndEndsAtOnce() {
        t.h.activate();

        t.h.childSays("i-1", "무중아 그만할래");

        assertThat(t.judge.calls()).isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(t.records()).isEmpty();
    }

    @Test
    void aStopPhraseWhileJ6IsOpenClosesItAsStoppedAndEndsTheSession() {
        t.openStopJudgment();

        t.h.childSays("i-2", "무중아 그만할래");

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(t.onlyRecord().outcome()).isEqualTo(JudgmentOutcomeKind.STOPPED);
        assertThat(t.judge.calls()).hasSize(1);
    }

    @Test
    void aNewOrdinaryReplySupersedesJ6AndAnotherUnclearOneOpensAFreshTurn() {
        t.openStopJudgment();

        t.h.childSays("i-2", "티라노가 제일 커");
        assertThat(t.records()).singleElement().extracting(JudgmentLiveRecord::outcome).isEqualTo(JudgmentOutcomeKind.SUPERSEDED);
        assertThat(t.judge.calls()).hasSize(1);

        t.h.childSays("i-3", JudgmentExecutorSupport.UNCLEAR_STOP);
        t.h.childSays("i-4", JudgmentExecutorSupport.UNCLEAR_STOP);

        assertThat(t.judge.calls()).hasSize(3);
        assertThat(t.records()).extracting(JudgmentLiveRecord::outcome)
                .containsExactly(JudgmentOutcomeKind.SUPERSEDED, JudgmentOutcomeKind.SUPERSEDED);
    }

    @Test
    void noProviderKeepsTodaysConfirmationFlowAndOnlyOneRecord() {
        t.judge.available(false);
        t.h.activate();

        t.h.childSays("i-1", JudgmentExecutorSupport.UNCLEAR_STOP);

        assertThat(t.judge.calls()).isEmpty();
        assertThat(t.events(SessionExecutor.EVENT_STOP_CONFIRM_ASKED)).hasSize(1);
        assertThat(t.onlyRecord().abstainReason()).isEqualTo(AbstainReason.NO_PROVIDER);
    }

    @Test
    void r13NotEndHandsTheHeldTranscriptToAnOutputOwningPracticeAndResumesItsClock() {
        OwningExtension extension = owningPractice();
        t.h.activate();
        t.h.childSays("i-1", JudgmentExecutorSupport.UNCLEAR_STOP);
        assertThat(extension.utterances).as("판정 중에는 연습에 넘기지 않는다").isEmpty();

        t.judge.confirm("NOT_END", 0.9, "UNCLEAR", 0.05);
        t.h.drain();

        assertThat(extension.utterances).singleElement().satisfies(utterance -> assertThat(utterance.text())
                .isEqualTo(JudgmentExecutorSupport.UNCLEAR_STOP));
        t.advance(Duration.ofSeconds(11));
        assertThat(extension.events).extracting(PracticeSessionExtension.ExtensionEvent::kind)
                .contains(PracticeSessionExtension.ExtensionEvent.Kind.TIMER_FIRED);
    }

    @Test
    void r14SupersedeByAnOrdinaryReplyResumesThePracticeClockButAnotherUnclearOneKeepsItPaused() {
        OwningExtension resumed = owningPractice();
        t.h.activate();
        t.advance(Duration.ofSeconds(2));
        t.h.childSays("i-1", JudgmentExecutorSupport.UNCLEAR_STOP);
        t.h.childSays("i-2", "티라노가 제일 커");
        t.advance(Duration.ofSeconds(7));
        assertThat(resumed.events).as("남은 8초 중 7초").extracting(PracticeSessionExtension.ExtensionEvent::kind)
                .doesNotContain(PracticeSessionExtension.ExtensionEvent.Kind.TIMER_FIRED);
        t.advance(Duration.ofSeconds(2));
        assertThat(resumed.events).extracting(PracticeSessionExtension.ExtensionEvent::kind)
                .contains(PracticeSessionExtension.ExtensionEvent.Kind.TIMER_FIRED);

        JudgmentExecutorSupport second = new JudgmentExecutorSupport();
        OwningExtension paused = new OwningExtension();
        second.h.activityType = ActivityType.PRACTICE;
        second.h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        second.h.practice = sessionId -> paused;
        second.h.activate();
        second.advance(Duration.ofSeconds(2));
        second.h.childSays("i-1", JudgmentExecutorSupport.UNCLEAR_STOP);
        second.h.childSays("i-2", JudgmentExecutorSupport.UNCLEAR_STOP);
        second.advance(Duration.ofSeconds(3));
        assertThat(paused.events).as("다시 CONFIRM이면 멈춤을 유지한다").extracting(PracticeSessionExtension.ExtensionEvent::kind)
                .doesNotContain(PracticeSessionExtension.ExtensionEvent.Kind.TIMER_FIRED);
    }

    @Test
    void restInAnOutputOwningPracticeAcknowledgesOnlyAndTellsTheExtension() {
        OwningExtension extension = owningPractice();
        t.h.activate();
        t.h.childSays("i-1", JudgmentExecutorSupport.UNCLEAR_STOP);
        t.h.settleSend();
        int before = t.appendedContents().size();

        t.judge.confirm("REST", 0.9, "NOT_END", 0.05);
        t.h.drain();
        t.h.settleSend();

        assertThat(t.appendedContents()).hasSize(before + 1);
        assertThat(t.appendedContents().getLast()).isEqualTo(InstructionTemplates.NextAction.REST_ACK_ONLY.content());
        t.h.speak("그래, 편할 때 말해 줘", 4_800);
        t.h.drain();
        assertThat(extension.events).extracting(PracticeSessionExtension.ExtensionEvent::kind)
                .contains(PracticeSessionExtension.ExtensionEvent.Kind.REST_REQUESTED);
        assertThat(t.onlyRecord().nextAction()).isEqualTo(NextAction.REST_ACK_ONLY);
    }
}
