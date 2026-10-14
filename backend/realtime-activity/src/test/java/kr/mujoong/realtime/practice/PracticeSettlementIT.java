package kr.mujoong.realtime.practice;

import static kr.mujoong.realtime.practice.PracticeTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
@Tag("db")
class PracticeSettlementIT {
    private final PracticeRecordProjector projector = new PracticeRecordProjector();

    @Test
    void stoppingPreservesTheOriginalBudgetAndIncludesTheCompletedJudgmentWithoutAdvancing() {
        var f = new ProtocolFixture(3);
        f.intro(); f.respond();
        var response = f.judgments.getFirst();
        int outputs = f.outputs.size();

        f.protocol.stopProgress();
        assertThat(f.protocol.settlementDelay(f.clock.now())).isEqualTo(Duration.ofSeconds(12));
        f.clock.advance(Duration.ofSeconds(2));
        assertThat(f.protocol.settlementDelay(f.clock.now())).isEqualTo(Duration.ofSeconds(10));
        f.judge(Label.functional);

        assertThat(f.outputs).hasSize(outputs);
        assertThat(f.scenes).isEmpty();
        assertThat(f.protocol.settlementDelay(f.clock.now())).isZero();
        f.protocol.settle();
        var snapshot = f.db.repository.snapshot(f.db.session);
        assertThat(snapshot.responses().getFirst().work().deadlineAt()).isEqualTo(response.work().deadlineAt());
        assertThat(snapshot.responses().getFirst().label()).isEqualTo(Label.functional);
        assertThat(snapshot.goals()).extracting(SessionGoal::status)
                .containsExactly(GoalStatus.SKIPPED, GoalStatus.NOT_REACHED, GoalStatus.NOT_REACHED);
        assertThat(snapshot.goals().getFirst().skipReason()).isEqualTo("ACTIVITY_ENDED");
        assertThat(projector.projectSettled(snapshot)).isInstanceOf(PracticeRecordProjector.Ready.class);
    }

    @Test
    void endingAnOpenOpportunityDoesNotInventAnAbsentResponse() {
        var f = new ProtocolFixture(); f.intro();
        f.protocol.stopProgress();
        f.timers.advance(Duration.ofSeconds(30));
        f.protocol.settle();

        var snapshot = f.db.repository.snapshot(f.db.session);
        assertThat(snapshot.responses()).isEmpty();
        assertThat(snapshot.evaluations()).isEmpty();
        assertThat(snapshot.attempts().getFirst().exposedAt()).isNotNull();
        assertThat(projector.projectSettled(snapshot)).isInstanceOf(PracticeRecordProjector.Ready.class);
    }

    @Test
    void endingBeforePlaybackHasNoExposureAndSettlementIsIdempotent() {
        var f = new ProtocolFixture(2);
        f.protocol.stopProgress(); f.protocol.settle();
        var first = f.db.repository.snapshot(f.db.session);
        f.protocol.settle();

        assertThat(f.db.repository.snapshot(f.db.session)).isEqualTo(first);
        assertThat(first.goals()).extracting(SessionGoal::status).containsOnly(GoalStatus.NOT_REACHED);
        var record = (PracticeRecordProjector.Ready) projector.projectSettled(first);
        assertThat(record.practice().goals()).allSatisfy(goal -> assertThat(goal.scenarios()).isEmpty());
    }

    @Test
    void aPendingJudgmentIsPreservedWhenStorageCannotFinalizeBeforeTheOriginalDeadline() {
        var f = new ProtocolFixture(); f.intro(); f.respond(); f.protocol.stopProgress();
        var original = f.db.repository.response(f.judgments.getFirst().id()).orElseThrow();
        assertThatThrownBy(f.protocol::settle).isInstanceOf(IllegalStateException.class);
        f.clock.advance(Duration.ofSeconds(13));
        assertThat(f.protocol.settlementDelay(f.clock.now())).isZero();
        assertThatThrownBy(f.protocol::settle).isInstanceOf(IllegalStateException.class);
        assertThat(f.db.repository.response(original.id())).contains(original);
        assertThat(projector.projectSettled(f.db.repository.snapshot(f.db.session)))
                .isInstanceOf(PracticeRecordProjector.Deferred.class);
    }

    @Test
    void stoppingDuringPreparationKeepsOriginalResponseAndNoFakeScenario() {
        var f = new ProtocolFixture(); f.intro(); f.respond(); f.judge(Label.functional);
        f.protocol.stopProgress(); f.protocol.settle();
        f.scenes.getFirst().complete(ProtocolFixture.scene());

        var snapshot = f.db.repository.snapshot(f.db.session);
        assertThat(snapshot.attempts()).filteredOn(a -> a.attemptType() == AttemptType.NEW_SCENARIO)
                .singleElement().satisfies(a -> assertThat(a.scenarioId()).isNull());
        var record = (PracticeRecordProjector.Ready) projector.projectSettled(snapshot);
        assertThat(record.practice().goals().getFirst().scenarios()).hasSize(1);
        assertThat(record.practice().goals().getFirst().responses().getFirst().label()).isEqualTo(Label.functional);
    }

    @Test
    void explicitOutputFailureSkipsTheGoalAndContinuesWithoutFabricatingExposure() {
        var f = new ProtocolFixture(2);
        f.playback(PracticePorts.Playback.FAILED);
        f.protocol.failCurrentGoal("OUTPUT_FAILED");

        assertThat(f.protocol.goalCount()).isEqualTo(2);
        assertThat(f.protocol.currentGoal().order()).isEqualTo(2);
        assertThat(f.protocol.currentScenarioId()).isNull();
        var next = ProtocolFixture.scene();
        f.scenes.getFirst().complete(next);
        assertThat(f.protocol.currentScene()).isEqualTo(next);
        assertThat(f.protocol.currentScenarioId()).isEqualTo(next.id());
        f.protocol.stopProgress(); f.protocol.settle();
        var record = (PracticeRecordProjector.Ready) projector.projectSettled(f.db.repository.snapshot(f.db.session));
        assertThat(record.practice().goals().getFirst().status()).isEqualTo(GoalStatus.SKIPPED);
        assertThat(record.practice().goals().getFirst().skipReason()).isEqualTo("OUTPUT_FAILED");
        assertThat(record.practice().goals().getFirst().scenarios()).isEmpty();
    }

    @Test
    void integratedNewScenarioFailureKeepsTheFirstResultAndMovesToTheNextGoal() {
        var f = new ProtocolFixture(2, java.util.function.UnaryOperator.identity(), true);
        f.intro(); f.respond(); f.judge(Label.functional);
        f.scenes.getFirst().completeExceptionally(new IllegalStateException("합성 생성 실패"));

        assertThat(f.protocol.currentGoal().order()).isEqualTo(2);
        assertThat(f.protocol.awaitingAgreement()).isFalse();
        var snapshot = f.db.repository.snapshot(f.db.session);
        assertThat(snapshot.goals().getFirst().status()).isEqualTo(GoalStatus.SKIPPED);
        assertThat(snapshot.goals().getFirst().skipReason()).isEqualTo("SCENARIO_FAILED");
        assertThat(snapshot.responses()).singleElement().satisfies(p -> assertThat(p.label()).isEqualTo(Label.functional));
        assertThat(snapshot.attempts()).filteredOn(a -> a.attemptType() == AttemptType.NEW_SCENARIO)
                .singleElement().satisfies(a -> assertThat(a.scenarioId()).isNull());
    }

    @Test
    void theLastGoalScenarioTimeoutClosesNormallyWithItsOriginalEvidence() {
        var f = new ProtocolFixture(1, java.util.function.UnaryOperator.identity(), true);
        f.intro(); f.respond(); f.judge(Label.functional);
        f.timers.advance(Duration.ofSeconds(15));

        assertThat(f.lastGoals).isEqualTo(1);
        f.protocol.settle();
        var snapshot = f.db.repository.snapshot(f.db.session);
        assertThat(snapshot.goals().getFirst().skipReason()).isEqualTo("TIME_OVER");
        var record = (PracticeRecordProjector.Ready) projector.projectSettled(snapshot);
        assertThat(record.practice().goals().getFirst().scenarios()).hasSize(1);
        assertThat(record.practice().goals().getFirst().responses()).hasSize(1);
    }
}
