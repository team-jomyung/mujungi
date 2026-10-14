package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practice.PracticeTypes.*;
import static kr.mujoong.realtime.practice.PracticePorts.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
@Tag("db")
class PracticeProtocolTest {
    @Test void previousGoalTimerCannotCloseNextGoalsTwentySecondOpportunity() {
        var f = new ProtocolFixture(2); completeFirstGoalAtTwoSeconds(f);
        f.scenes.getLast().complete(ProtocolFixture.scene()); f.intro();
        assertThat(f.protocol.currentGoal().generation()).isEqualTo(2);
        f.timers.advance(Duration.ofSeconds(18)); // t=20: 이전 목표의 첫 반응 만료
        assertThat(f.protocol.currentGoal().currentStage()).isEqualTo(Stage.FIRST_RESPONSE);
        assertThat(f.db.repository.snapshot(f.db.session).responses()).hasSize(2);
        f.timers.advance(Duration.ofSeconds(2).minusNanos(1));
        assertThat(f.protocol.currentGoal().currentStage()).isEqualTo(Stage.FIRST_RESPONSE);
        f.timers.advance(Duration.ofNanos(1)); // t=22: 현재 목표의 실제 만료
        assertThat(f.protocol.currentGoal().currentStage()).isEqualTo(Stage.HELP_OFFER);
        var responses = f.db.repository.snapshot(f.db.session).responses();
        assertThat(responses).hasSize(3);
        assertThat(responses).filteredOn(p -> p.label() == Label.NOT_PERFORMED).hasSize(1);
        assertThat(f.judgments).hasSize(2);
        f.timers.advance(Duration.ofSeconds(1));
        assertThat(f.db.repository.snapshot(f.db.session).responses()).hasSize(3);
    }
    @Test void failedPreviousPreparationTimerCannotExpireNextGoalPreparation() {
        var f = new ProtocolFixture(3); completeFirstGoalAtTwoSeconds(f);
        f.timers.advance(Duration.ofSeconds(1)); // t=3: 목표2 실패, 목표3 준비 시작
        f.scenes.getLast().completeExceptionally(new IllegalStateException("목표2 준비 실패"));
        var third = f.protocol.currentGoal().id();
        f.timers.advance(Duration.ofSeconds(14)); // t=17: 목표2 준비의 잔존 타이머
        assertThat(f.protocol.currentGoal().id()).isEqualTo(third);
        assertThat(f.protocol.currentGoal().status()).isEqualTo(GoalStatus.RUNNING);
        assertThat(f.lastGoals).isZero();
        assertThat(f.db.repository.snapshot(f.db.session).attempts())
                .filteredOn(a -> a.sessionGoalId().equals(third)).singleElement()
                .extracting(PracticeAttempt::state).isEqualTo(AttemptState.PREPARING);
        f.timers.advance(Duration.ofSeconds(1)); // t=18: 목표3 자신의 만료
        assertThat(f.protocol.currentGoal().status()).isEqualTo(GoalStatus.SKIPPED);
        assertThat(f.lastGoals).isEqualTo(1);
    }
    @Test void timedOutPreviousGoalResultCannotSupplyNextGoalScenario() {
        var f = new ProtocolFixture(3); completeFirstGoalAtTwoSeconds(f);
        var secondPreparation = f.scenes.getLast();
        f.timers.advance(Duration.ofSeconds(15)); // 목표2 만료 뒤 목표3 준비
        var third = f.protocol.currentGoal().id();
        var stale = ProtocolFixture.scene(); secondPreparation.complete(stale);
        assertThat(f.protocol.currentGoal().order()).isEqualTo(3);
        assertThat(f.outputs).hasSize(2);
        assertThat(f.db.repository.snapshot(f.db.session).attempts())
                .filteredOn(a -> a.sessionGoalId().equals(third)).singleElement()
                .extracting(PracticeAttempt::state).isEqualTo(AttemptState.PREPARING);
        var valid = ProtocolFixture.scene(); f.scenes.getLast().complete(valid);
        assertThat(f.output().scenarioId()).isEqualTo(valid.id());
        assertThat(f.output().goalId()).isEqualTo(third);
        f.intro();
        assertThat(f.protocol.currentGoal().currentStage()).isEqualTo(Stage.FIRST_RESPONSE);
        assertThat(f.db.repository.snapshot(f.db.session).attempts()).noneMatch(a -> stale.id().equals(a.scenarioId()));
    }
    private static void completeFirstGoalAtTwoSeconds(ProtocolFixture f) {
        f.intro(); f.timers.advance(Duration.ofSeconds(1)); f.respond(); f.judge(Label.functional);
        f.scenes.getFirst().complete(ProtocolFixture.scene()); f.intro();
        f.timers.advance(Duration.ofSeconds(1)); f.respond(); f.judge(Label.functional);
        assertThat(f.protocol.currentGoal().order()).isEqualTo(2);
    }
    @Test void onlyRealPlaybackCompletionOpensFirstOpportunity() {
        var f = new ProtocolFixture(); f.timers.advance(Duration.ofSeconds(25));
        assertThat(f.db.repository.snapshot(f.db.session).responses()).isEmpty();
        f.playback(Playback.STARTED); assertThat(f.protocol.currentGoal().currentStage()).isEqualTo(Stage.SCENARIO_INTRO);
        f.intro(); assertThat(f.protocol.currentGoal().currentStage()).isEqualTo(Stage.FIRST_RESPONSE);
        assertThat(f.db.repository.snapshot(f.db.session).attempts().getFirst().exposedAt()).isEqualTo(f.clock.now());
    }
    @Test void firstFunctionalThenNewResponseCompletesWithoutCue() {
        var f = new ProtocolFixture(); f.intro(); f.respond(); f.judge(Label.functional);
        assertThat(f.previous).containsExactly(f.first.id()); f.scenes.getFirst().complete(ProtocolFixture.scene());
        f.playback(Playback.COMPLETED); f.respond(); f.judge(Label.partial);
        assertThat(f.lastGoals).isEqualTo(1); assertThat(f.outputs).allMatch(o -> o.kind() == OutputKind.SCENARIO);
        assertThat(f.db.repository.snapshot(f.db.session).responses()).hasSize(2);
        f.protocol.judged(f.judgments.getLast().id()); assertThat(f.lastGoals).isEqualTo(1);
        var multi = new ProtocolFixture(3); multi.silenceToHelp(); multi.playback(Playback.COMPLETED); multi.consent(Consent.DECLINED);
        multi.scenes.getFirst().complete(ProtocolFixture.scene()); multi.playback(Playback.COMPLETED); multi.timers.advance(Duration.ofSeconds(20));
        assertThat(multi.protocol.currentGoal().order()).isEqualTo(2);
        assertThat(multi.previous.getLast()).isNull();
        multi.scenes.getLast().completeExceptionally(new IllegalStateException("다음 목표 준비 실패"));
        assertThat(multi.protocol.currentGoal().order()).isEqualTo(3);
        multi.protocol.terminate();
        assertThat(multi.db.repository.snapshot(multi.db.session).goals()).extracting(SessionGoal::status)
                .containsExactly(GoalStatus.DONE, GoalStatus.SKIPPED, GoalStatus.NOT_REACHED);
    }
    @Test void partialRequestsHelpAndUncertainSkipsCue() {
        var f = new ProtocolFixture(); f.intro(); f.respond(); f.judge(Label.partial);
        assertThat(f.output().kind()).isEqualTo(OutputKind.HELP_QUESTION);
        var uncertain = new ProtocolFixture(); uncertain.intro(); uncertain.respond(); uncertain.judge(Label.uncertain);
        assertThat(uncertain.scenes).hasSize(1); assertThat(uncertain.outputs).hasSize(1);
    }
    @Test void newScenarioFailureKeepsOriginalAndDoesNotInventGoalOutcome() {
        var f = new ProtocolFixture(); f.intro(); f.respond(); f.judge(Label.functional);
        f.scenes.getFirst().completeExceptionally(new IllegalStateException());
        var saved = f.db.repository.snapshot(f.db.session);
        assertThat(saved.responses()).hasSize(1); assertThat(saved.responses().getFirst().label()).isEqualTo(Label.functional);
        assertThat(saved.attempts()).anyMatch(a -> a.state() == AttemptState.PREPARATION_FAILED && a.scenarioId() == null);
        assertThat(f.protocol.awaitingAgreement()).isTrue(); assertThat(f.lastGoals).isZero();
        assertThat(f.protocol.currentGoal().status()).isEqualTo(GoalStatus.RUNNING);
        var failedStore = new ProtocolFixture(1, repository -> {
            var faulty = org.mockito.Mockito.spy((JdbcPracticeRepository) repository);
            org.mockito.Mockito.doAnswer(invocation -> {
                PracticeAttempt value = invocation.getArgument(0);
                if (value.attemptType() == AttemptType.NEW_SCENARIO && value.state() == AttemptState.ISSUED)
                    throw new IllegalStateException("합성 장면 저장 실패");
                return invocation.callRealMethod();
            }).when(faulty).saveAttempt(org.mockito.ArgumentMatchers.any());
            return faulty;
        });
        failedStore.intro(); failedStore.respond(); failedStore.judge(Label.functional);
        failedStore.scenes.getFirst().complete(ProtocolFixture.scene());
        assertThat(failedStore.failures).hasSize(1); assertThat(failedStore.protocol.awaitingAgreement()).isTrue();
        assertThat(failedStore.db.repository.snapshot(failedStore.db.session).responses()).hasSize(1);
    }
    @Test void generationTimeoutAndLateCompletionCannotReviveScenario() {
        var f = new ProtocolFixture(); f.intro(); f.respond(); f.judge(Label.functional);
        f.timers.advance(Duration.ofSeconds(15)); f.scenes.getFirst().complete(ProtocolFixture.scene());
        assertThat(f.protocol.awaitingAgreement()).isTrue(); assertThat(f.outputs).hasSize(1);
    }
    @Test void rejectsForeignSceneAndDuplicateResponse() {
        var f = new ProtocolFixture(); f.intro();
        f.protocol.response(1, "합성 반응", UUID.randomUUID(), f.protocol.currentGoal().generation());
        assertThat(f.judgments).isEmpty(); f.respond(); f.respond();
        assertThat(f.judgments).hasSize(1); assertThat(f.db.repository.snapshot(f.db.session).responses()).hasSize(1);
    }
    @Test void terminationPreventsLatePlaybackAndResponses() {
        var f = new ProtocolFixture(); f.protocol.terminate(); f.intro(); f.timers.advance(Duration.ofSeconds(50)); f.respond();
        assertThat(f.db.repository.snapshot(f.db.session).responses()).isEmpty(); assertThat(f.judgments).isEmpty();
        var pending = new ProtocolFixture(); pending.intro(); pending.respond(); pending.protocol.terminate();
        assertThat(pending.db.repository.snapshot(pending.db.session).responses().getFirst().evaluationState())
                .isEqualTo(EvaluationState.TERMINATED_PENDING);
        var remaining = new ProtocolFixture(2); remaining.protocol.terminate();
        assertThat(remaining.db.repository.snapshot(remaining.db.session).goals().getLast().status()).isEqualTo(GoalStatus.NOT_REACHED);
    }
    @Test void failedInterruptedAndOmittedIntroAreNotExposure() {
        for (var fact : new Playback[] {Playback.FAILED, Playback.INTERRUPTED, Playback.OMITTED}) {
            var f = new ProtocolFixture(); f.playback(fact); f.intro();
            assertThat(f.protocol.awaitingAgreement()).isTrue();
            assertThat(f.db.repository.snapshot(f.db.session).attempts().getFirst().exposedAt()).isNull();
        }
    }
}
