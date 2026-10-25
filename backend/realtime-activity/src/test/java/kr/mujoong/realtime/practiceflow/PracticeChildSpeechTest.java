package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_ASK;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 아이가 말하는 중의 무응답 시계 시험 (4회차 R1, docs/practice-direct-ask.md 7.2). 확정 전사는 말이 끝나고 조용 시간이 지나야 오므로, 실행부가 알리는
 * '아이 말 시작'에서 무응답 시계를 멈추고 같은 길이의 안전 시계({@code child-speech})만 건다. 확정 전사가 오면 지금처럼 판정하고, 전사 없이 끝난 말은
 * 안전 시계가 끝날 때 무응답 대기를 처음부터 다시 건다. C안 · 유연 대화 두 경로와 유연 대화의 여는 대화(FT-1023-g)가 같다. 가짜 출력 · 수동 시계
 * 위의 합성 대화이다(테스트 대역 증거).
 */
@Tag("practice-flow")
class PracticeChildSpeechTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String NO_RESPONSE = "no-response";
    private static final String CHILD_SPEECH = PracticeConversation.TIMER_CHILD_SPEECH;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static Harness direct() {
        return new Harness(packageJson(GOAL, directAskScript(SCENARIO)), FIXED_SPEECH);
    }

    private static Harness flexible() {
        return new Harness(packageJson(GOAL, minimalScript(SCENARIO)), Map.of(), PracticeFlowTestKit.flexible());
    }

    @Test
    void aLongAnswerThatStartedBeforeTheWaitRanOutIsNotInterruptedByTheReguide() {
        Harness h = direct();
        h.toOpportunity();
        int slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(8));

        assertThat(h.speechStarted()).isEqualTo(Signal.NONE);
        assertThat(h.out.timers).doesNotContainKey(NO_RESPONSE).containsEntry(CHILD_SPEECH, h.clock.now().plus(WAIT));
        h.advance(Duration.ofSeconds(9));
        assertThat(h.out.slots).as("무응답 대기 10초가 지났지만 말하는 중이라 다시 묻지 않는다").hasSize(slots);

        h.child("손 괜찮아? 그럼 다음에 같이 그리자");

        assertThat(h.out.timers).doesNotContainKeys(CHILD_SPEECH, NO_RESPONSE);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(PracticeFlowTypes.FactKind.TIMEOUT);
    }

    @Test
    void speechWithoutATranscriptRestartsTheWholeWaitWhenTheSafetyClockEnds() {
        Harness h = direct();
        h.toOpportunity();
        int slots = h.out.slots.size();
        h.speechStarted();

        h.advance(WAIT);
        assertThat(h.out.slots).as("안전 시계 만료는 재안내가 아니다").hasSize(slots);
        assertThat(h.out.timers).doesNotContainKey(CHILD_SPEECH).containsEntry(NO_RESPONSE, h.clock.now().plus(WAIT));

        h.advance(WAIT.minusSeconds(1));
        assertThat(h.out.slots).hasSize(slots);
        h.advance(Duration.ofSeconds(1));
        assertThat(h.out.lastSlot().text()).as("그다음 10초가 지나서야 첫 무응답 재안내").isEqualTo(FIXED_SPEECH.get("WAIT_LEAD") + " " + V2_ASK);
    }

    @Test
    void speechThatStartedWhileTheQuestionWasStillPlayingKeepsTheWaitOffAfterPlayback() {
        Harness h = direct();
        h.toIntroduction();
        h.played();
        h.speechStarted();

        h.played();

        assertThat(h.out.timers).as("묻는 말이 끝나도 그 말의 확정 전사를 기다린다").doesNotContainKey(NO_RESPONSE).containsKey(CHILD_SPEECH);
        h.child("손 괜찮아?");
        assertThat(h.out.judgments).singleElement().satisfies(judgment -> assertThat(judgment.point()).isEqualTo(JudgmentPoint.J2));
    }

    @Test
    void speechStartedDuringAJudgmentWaitsForItsTranscriptAfterTheNextQuestion() {
        Harness h = direct();
        h.toOpportunity();
        h.child("음");
        h.speechStarted();
        h.result("J2", "PARTIAL");

        h.played();

        assertThat(h.out.timers).doesNotContainKey(NO_RESPONSE);
        h.child("손 괜찮냐고 물어볼래");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J3);
    }

    @Test
    void theFlexiblePathFollowsTheSameRule() {
        Harness h = flexible();
        h.toOpportunity();
        int slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(4));
        h.speechStarted();

        h.advance(Duration.ofSeconds(9));
        assertThat(h.out.slots).as("생각할 시간을 주는 생성형 재안내가 나가지 않는다").hasSize(slots);
        h.child("무슨 놀이를 하고 싶어?");

        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.timers).doesNotContainKey(CHILD_SPEECH);
    }

    @Test
    void theOpeningLineWaitsForTheChildsSpeechInsteadOfSkippingItMidSentence() {
        // 돌아보기(main FT-1023-g에서 없앰) 대신 유연 대화의 여는 대화가 같은 규칙을 따른다: 말하는 중에는 대답 대기를 끝내지 않는다
        String opening = "안녕, 나 상자 정리하고 있었어.";
        String script = minimalScript(SCENARIO).replace("\"sceneKey\":\"base-1\"",
                "\"openingLine\":\"" + opening + "\",\"sceneKey\":\"base-1\"");
        Harness h = new Harness(packageJson(GOAL, script), Map.of(), PracticeFlowTestKit.flexible());
        h.toIntroduction();
        h.played();
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.OPENING);
        assertThat(h.out.lastSlot().text()).isEqualTo(opening);
        h.played();
        assertThat(h.out.timers).containsKey(NO_RESPONSE);

        h.speechStarted();
        h.advance(WAIT);
        assertThat(h.ext.state().sub()).as("말하는 중에는 여는 대화를 건너뛰지 않는다").isEqualTo(FlowState.Sub.OPENING);
        assertThat(h.out.timers).containsKey(NO_RESPONSE);

        h.advance(WAIT);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.NONE);
        assertThat(h.out.lastSlot().text()).as("전사 없이 끝난 말 뒤 다시 기다린 만큼 지나서야 기회 문장으로 간다").isEqualTo("친구가 말했어. 그거 줘.");
        assertThat(h.out.judgments).as("여는 말에 대한 말은 판정하지 않는다").isEmpty();
    }

    @Test
    void aSpeechStartBeforeActivationOrAfterTheEndingIsIgnored() {
        Harness before = direct();
        assertThat(before.speechStarted()).isEqualTo(Signal.NONE);
        assertThat(before.out.timers).isEmpty();

        Harness ended = direct();
        ended.toOpportunity();
        ended.child("손 괜찮아?");
        ended.result("J2", "FULL");
        assertThat(ended.playedThroughRecap()).isEqualTo(Signal.LAST_GOAL);
        assertThat(ended.speechStarted()).isEqualTo(Signal.NONE);
        assertThat(ended.out.timers).doesNotContainKey(CHILD_SPEECH);

        Harness resting = direct();
        resting.toOpportunity();
        resting.event(ExtensionEvent.Kind.REST_REQUESTED, "stop", null, null);
        resting.speechStarted();
        assertThat(resting.out.timers).doesNotContainKey(CHILD_SPEECH);
    }
}
