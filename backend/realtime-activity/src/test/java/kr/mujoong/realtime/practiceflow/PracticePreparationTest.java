package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.fullScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.session.PracticeSessionExtension.Utterance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 사전 교육과 역할극 수행을 분리한다. 합성 발화·가짜 판정·수동 시계만 쓰며 실제 공급자·기기를 검증하지 않는다. */
@Tag("practice-flow")
class PracticePreparationTest {

    private Harness fresh(boolean variant) {
        return new Harness(packageJson(UUID.randomUUID(), variant ? fullScript(UUID.randomUUID()) : minimalScript(UUID.randomUUID())),
                Map.of(), new PracticeFlowSettings(Duration.ofSeconds(10), Duration.ofSeconds(90), Duration.ofMinutes(10),
                        Duration.ofSeconds(20), 2, 1, true));
    }

    private Harness preparationPrompt() {
        Harness h = fresh(false);
        h.activate();
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.GOAL_BRIEF);
        h.played();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.PREPARATION);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_PROMPT);
        return h;
    }

    private Harness listening() {
        Harness h = preparationPrompt();
        h.played();
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.NONE);
        return h;
    }

    private void enterScene(Harness h) {
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FINISH);
        h.played();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.SCENE_INTRO);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.NONE);
        assertThat(h.recorder.rows).isEmpty();
        h.played();
        h.played();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
    }

    @Test
    void startsAsCoachAndWaitsForTheChildBeforeStartingAnySceneOrJudgment() {
        Harness h = preparationPrompt();
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.out.lastSlot().role()).isEqualTo("COACH");
        assertThat(h.recorder.rows).isEmpty();
        assertThat(h.out.judgments).isEmpty();
        h.played();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.PREPARATION);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.NONE);
        assertThat(h.out.timers).containsKey("no-response");
        assertThat(h.recorder.rows).isEmpty();
        assertThat(h.out.judgments).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FULL", "COPIED"})
    void aPreparedExpressionDoesNotCompleteTheRoleplayGoal(String classification) {
        Harness h = listening();
        h.child("파란 상자를 줘");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        h.result("J5", classification);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FINISH);
        assertThat(h.recorder.rows).isEmpty();
        assertThat(h.recorder.helps).isEmpty();
        enterScene(h);
        assertThat(h.recorder.only().endedAt()).isNull();
        assertThat(h.recorder.only().liveTiming()).isNull();
        assertThat(h.recorder.factKinds(h.recorder.only()))
                .contains(FactKind.PREPRACTICE_PROVIDED, FactKind.PREPRACTICE_ATTEMPTED);
        h.child("빨간 상자를 줘");
        assertThat(h.out.lastJudgment().point()).as("사전 연습 성공 뒤에도 새 장면의 목표를 별도로 판단한다")
                .isEqualTo(JudgmentPoint.J2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTIAL", "HELP", "NOT_UNDERSTOOD"})
    void oneAdditionalAttemptIsAllowedButThePracticeNeverRepeatsUntilSuccess(String classification) {
        Harness h = listening();
        h.child("어떻게 말하지?");
        h.result("J5", classification);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FEEDBACK);
        assertThat(h.out.lastSlot().role()).isEqualTo("COACH");
        h.played();
        h.child("아직 모르겠어");
        h.result("J5", classification);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FINISH);
        assertThat(h.out.judgments).hasSize(2);
        assertThat(h.recorder.rows).isEmpty();
        enterScene(h);
        assertThat(h.recorder.only().liveTiming()).isNull();
        assertThat(h.recorder.only().endedAt()).isNull();
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.FOCUS_HELP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"응", "알았어"})
    void anInstructionAcknowledgmentClassifiedAsIrrelevantIsNotATargetAttempt(String acknowledgment) {
        Harness h = listening();
        h.child(acknowledgment);
        h.result("J5", "IRRELEVANT");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FEEDBACK);
        h.played();
        h.child(acknowledgment);
        h.result("J5", "IRRELEVANT");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FINISH);
        enterScene(h);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PREPRACTICE_PROVIDED)
                .doesNotContain(FactKind.PREPRACTICE_ATTEMPTED);
        assertThat(h.recorder.only().liveTiming()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"응", "좋아"})
    void aShortAcceptanceCanPerformAnAcceptanceGoalWhenTheContextualJudgeConfirmsIt(String acceptance) {
        String script = minimalScript(UUID.randomUUID())
                .replace("친구가 상자 두 개를 들고 있어.", "친구가 함께 그림을 그리자고 제안해.")
                .replace("친구가 말했어. 그거 줘.", "같이 그림 그릴래?")
                .replace("친구는 빨간 상자와 파란 상자를 들고 있어.", "친구는 너와 함께 그림을 그리고 싶어 해.");
        String pack = packageJson(UUID.randomUUID(), script)
                .replace("부탁하기", "제안에 답하기")
                .replace("\"behaviorKey\":\"REQUEST\"", "\"behaviorKey\":\"ACCEPT_PROPOSAL\"")
                .replace("원하는 것을 말로 요청한다", "상대의 제안에 동의한다");
        Harness h = new Harness(pack, Map.of(),
                new PracticeFlowSettings(Duration.ofSeconds(10), Duration.ofSeconds(90), Duration.ofMinutes(10),
                        Duration.ofSeconds(20), 2, 1, true));
        h.activate();
        h.played();
        h.played();
        h.child(acceptance);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        h.result("J5", "FULL");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FINISH);
        enterScene(h);
        assertThat(h.recorder.factKinds(h.recorder.only()))
                .contains(FactKind.PREPRACTICE_PROVIDED, FactKind.PREPRACTICE_ATTEMPTED);
        assertThat(h.recorder.only().liveTiming()).as("사전 연습 수행은 역할극의 독립 수행이 아니다").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FULL", "COPIED"})
    void askingHowToAnswerCannotBeRecordedAsAPreparationPerformance(String classification) {
        Harness h = listening();
        h.child("뭐라고 대답해야 해?");
        h.result("J5", classification);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FEEDBACK);
        h.played();
        h.child("어떻게 말해야 해?");
        h.result("J5", classification);
        enterScene(h);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.PREPRACTICE_ATTEMPTED);
        assertThat(h.recorder.only().liveTiming()).isNull();
    }

    @Test
    void aPartialExpressionIsRecordedAsAnAttemptWithoutFabricatingASceneOutcome() {
        Harness h = listening();
        h.child("그거 줘");
        h.result("J5", "PARTIAL");
        h.played();
        h.child("파란 상자 줘");
        h.result("J5", "FULL");
        enterScene(h);
        assertThat(h.recorder.factKinds(h.recorder.only()))
                .contains(FactKind.PREPRACTICE_PROVIDED, FactKind.PREPRACTICE_ATTEMPTED);
        assertThat(h.recorder.only().liveTiming()).isNull();
        assertThat(h.recorder.only().retryCount()).as("사전 표현 연습은 장면의 도움 뒤 재시도 횟수가 아니다").isZero();
    }

    @Test
    void silenceMovesOnWithoutInventingAnAttemptOrWeakness() {
        Harness h = listening();
        h.advance(Duration.ofSeconds(10));
        h.played();
        h.advance(Duration.ofSeconds(10));
        enterScene(h);
        assertThat(h.out.judgments).isEmpty();
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PREPRACTICE_PROVIDED)
                .doesNotContain(FactKind.PREPRACTICE_ATTEMPTED, FactKind.FOCUS_HELP);
        assertThat(h.recorder.only().liveTiming()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Playback.class, names = {"FAILED", "INTERRUPTED"})
    void unheardPreparationDoesNotClaimThatTeachingWasProvided(Playback playback) {
        Harness h = preparationPrompt();
        h.end(playback);
        // 재안내·마무리도 실제 재생되지 않은 경우만 진행시켜, 들은 적 없는 교육을 제공했다고 기록하지 않는지 본다.
        for (int i = 0; i < 8 && h.ext.state().phase() == FlowState.Phase.PREPARATION; i++) {
            if (h.ext.state().sub() == FlowState.Sub.NONE) h.advance(Duration.ofSeconds(10));
            else h.end(playback);
        }
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.SCENE_INTRO);
        h.played();
        assertThat(h.recorder.factKinds(h.recorder.only()))
                .doesNotContain(FactKind.PREPRACTICE_PROVIDED, FactKind.PREPRACTICE_ATTEMPTED);
        assertThat(h.recorder.only().liveTiming()).isNull();
        assertThat(h.out.judgments).isEmpty();
    }

    @Test
    void theSamePreparationJudgmentCannotFinishTwiceOrSkipTheSceneIntroduction() {
        Harness h = listening();
        h.child("파란 상자를 줘");
        var judgment = h.out.lastJudgment();
        h.result("J5", "FULL");
        int slots = h.out.slots.size();
        h.result(judgment.correlationId(), "J5", "FULL");
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FINISH);
        assertThat(h.recorder.rows).isEmpty();
    }

    @Test
    void stoppingDuringTheCoachPromptCancelsItAndIgnoresLatePlayback() {
        Harness h = preparationPrompt();
        var prompt = h.out.lastSlot();
        int slots = h.out.slots.size();
        h.event(ExtensionEvent.Kind.REST_REQUESTED, "stop", null, null);
        assertThat(h.out.cancelledSpeech).contains(prompt.correlationId());
        assertThat(h.slotEvent(prompt.correlationId(), Playback.COMPLETED, prompt.speech(), prompt.text()))
                .isEqualTo(Signal.NONE);
        h.child("파란 상자 줘");
        h.advance(Duration.ofMinutes(1));
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.out.judgments).isEmpty();
        assertThat(h.recorder.rows).isEmpty();
    }

    @Test
    void stoppingDuringJudgmentDoesNotApplyTheLateResultOrBeginARoleplay() {
        Harness h = listening();
        h.child("파란 상자 줘");
        var judgment = h.out.lastJudgment();
        int slots = h.out.slots.size();
        h.ext.onStopping();
        assertThat(h.result(judgment.correlationId(), "J5", "FULL")).isEqualTo(Signal.NONE);
        h.advance(Duration.ofMinutes(1));
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.recorder.rows).isEmpty();
        assertThat(h.out.announces).isEmpty();
    }

    @Test
    void replayingACompletedPromptEventCannotRepeatTheCoachOrStartTheScene() {
        Harness h = preparationPrompt();
        var prompt = h.out.lastSlot();
        h.played();
        int slots = h.out.slots.size();
        h.slotEvent(prompt.correlationId(), Playback.COMPLETED, prompt.speech(), prompt.text());
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.PREPARATION);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.NONE);
        assertThat(h.recorder.rows).isEmpty();
    }

    @Test
    void anUncertainTranscriptIsNeverSentForGoalAchievementJudgment() {
        Harness h = listening();
        h.ext.onChildUtterance(new Utterance(++h.seq, h.clock.now(), "파란 상자", true, false), h.out);
        assertThat(h.out.judgments).isEmpty();
        assertThat(h.recorder.rows).isEmpty();
        assertThat(h.recorder.helps).isEmpty();
        assertThat(h.out.announces).isEmpty();
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FINISH);
        enterScene(h);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.PREPRACTICE_ATTEMPTED);
        assertThat(h.recorder.only().liveTiming()).isNull();
    }

    @Test
    void aJudgmentFaultCannotCompleteTheGoalOrProduceAPerformanceRecord() {
        Harness h = listening();
        h.child("파란 상자 줘");
        h.abstained("J5");
        assertThat(h.recorder.rows).isEmpty();
        assertThat(h.recorder.helps).isEmpty();
        assertThat(h.out.announces).isEmpty();
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PREPARATION_FINISH);
        enterScene(h);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.PREPRACTICE_ATTEMPTED);
        assertThat(h.recorder.only().liveTiming()).isNull();
    }

    @Test
    void theExplicitLegacyFlowDoesNotAcquireTheNewPreparationPhase() {
        Harness h = new Harness(packageJson(UUID.randomUUID(), minimalScript(UUID.randomUUID())), Map.of(),
                new PracticeFlowSettings(Duration.ofSeconds(10), Duration.ofSeconds(90), Duration.ofMinutes(10),
                        Duration.ofSeconds(20), 2, 1, false));
        h.activate();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.SCENE_INTRO);
        assertThat(h.out.lastSlot().generated()).isFalse();
        h.played();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(h.out.judgments).isEmpty();
    }
}
