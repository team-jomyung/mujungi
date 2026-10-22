package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 종료 의도와 판정 AI (U11 W2 폐기, 2026-10-22 결정). 종료 의도 판정 AI(J6)와 확인 질문을 없앴다: 판정기가 STOP이면 곧바로 끝내고, 그 밖(불명확한
 * 종료 신호 포함)은 판정 AI · 확인 질문 없이 대화를 잇는다. 출력권을 가진 연습에서도 아이 전사는 보류 없이 곧바로 연습에 넘어간다. 공급자와 판정기는
 * 가짜, 값은 합성이다(R-F1 · R-F3). 증거 종류: 테스트 대역.
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class StopJudgmentExecutorTest {

    private final JudgmentExecutorSupport t = new JudgmentExecutorSupport();

    /** 출력권을 가진 시험 연습 확장점(연습 시계 · 전사 받기를 기록한다). */
    private static final class OwningExtension implements PracticeSessionExtension {
        final List<Utterance> utterances = new ArrayList<>();
        final List<ExtensionEvent> events = new ArrayList<>();

        @Override public boolean controlsOutput() { return true; }

        @Override public Signal onActive(PracticeContext context, PracticeOutput out) {
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

    private void assertNoJudgmentAndNoQuestion() {
        assertThat(t.judge.calls()).as("J6를 열지 않는다").isEmpty();
        assertThat(t.records()).as("건너뛴 판정 기록도 없다").isEmpty();
        assertThat(t.events(SessionExecutor.EVENT_STOP_CONFIRM_ASKED)).as("확인 질문이 없다").isEmpty();
        assertThat(t.h.link.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::contentCode)
                .doesNotContain(SessionExecutor.STOP_CONFIRM);
    }

    @Test
    void aRegisteredStopPhraseNeverCallsTheJudgeAndEndsAtOnce() {
        t.h.activate();

        t.h.childSays("i-1", "무중아 그만할래");

        assertThat(t.judge.calls()).isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(t.h.transitions.find(t.h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(t.records()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"무중아 그만할래?", "그만할래?", "우리 이제 그만할까", "이제 끝낼까"})
    void aQuestionShapedStopPhraseAlsoEndsAtOnceWithoutAQuestion(String text) {
        t.h.activate();

        t.h.childSays("i-1", text);

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(t.events(SessionExecutor.EVENT_CHILD_STOP_INTENT)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).doesNotContain(text));
        assertNoJudgmentAndNoQuestion();
    }

    @ParameterizedTest
    @ValueSource(strings = {"그만", "그만해", "졸려", "힘들어", "쉬고 싶어", "숙제를 안 해가서 혼났어", "어떻게 됐어"})
    void anUnclearSignalOrEverydayTalkKeepsTheConversationWithoutJ6OrAQuestion(String text) {
        t.h.activate();

        t.h.childSays("i-1", text);

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.events(SessionExecutor.EVENT_CHILD_STOP_INTENT)).isEmpty();
        assertNoJudgmentAndNoQuestion();
    }

    @Test
    void withoutAProviderAnUnclearSignalLeavesNoTrace() {
        t.judge.available(false);
        t.h.activate();

        t.h.childSays("i-1", "그만");

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertNoJudgmentAndNoQuestion();
    }

    @Test
    void anOutputOwningPracticeGetsAnUnclearSignalAtOnceAndItsClockKeepsRunning() {
        OwningExtension extension = owningPractice();
        t.h.activate();
        t.advance(Duration.ofSeconds(2));

        t.h.childSays("i-1", "그만");

        assertThat(extension.utterances).as("판정으로 보류하지 않고 곧바로 연습에 넘긴다").singleElement()
                .satisfies(utterance -> assertThat(utterance.text()).isEqualTo("그만"));
        t.advance(Duration.ofSeconds(7));
        assertThat(extension.events).as("남은 8초 중 7초").extracting(PracticeSessionExtension.ExtensionEvent::kind)
                .doesNotContain(PracticeSessionExtension.ExtensionEvent.Kind.TIMER_FIRED);
        t.advance(Duration.ofSeconds(2));
        assertThat(extension.events).as("연습 시계는 멈추지 않는다").extracting(PracticeSessionExtension.ExtensionEvent::kind)
                .contains(PracticeSessionExtension.ExtensionEvent.Kind.TIMER_FIRED);
        assertNoJudgmentAndNoQuestion();
    }

    @Test
    void aStopPhraseInAnOutputOwningPracticeStillEndsAtOnce() {
        owningPractice();
        t.h.activate();

        t.h.childSays("i-1", "무중아 그만할래");

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertNoJudgmentAndNoQuestion();
    }
}
