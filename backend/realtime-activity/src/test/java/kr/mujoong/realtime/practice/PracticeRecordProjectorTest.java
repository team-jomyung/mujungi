package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practice.PracticeTypes.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class PracticeRecordProjectorTest {
    final PracticeRecordProjector projector = new PracticeRecordProjector();
    ProtocolFixture finished() {
        var f = new ProtocolFixture(); f.silenceToHelp(); f.playback(PracticePorts.Playback.COMPLETED); f.consent(Consent.DECLINED);
        f.scenes.getFirst().complete(ProtocolFixture.scene()); f.playback(PracticePorts.Playback.COMPLETED);
        f.timers.advance(java.time.Duration.ofSeconds(20)); return f;
    }
    @Test void projectsIndependentResponsesAndConsentWithoutFabricatingCue() {
        var f = finished(); var projected = (PracticeRecordProjector.Ready) projector.project(f.db.repository.snapshot(f.db.session));
        var goal = projected.practice().goals().getFirst();
        assertThat(goal.responses()).extracting(PracticeRecordProjector.ResponseRecord::responseType).containsExactlyInAnyOrder(ResponseType.FIRST, ResponseType.NEW_SCENARIO);
        assertThat(goal.cues().getFirst().givenAt()).isNull(); assertThat(goal.scenarios()).hasSize(2);
    }
    @Test void duplicateSettlementHasSameProjection() {
        var f = finished(); var snapshot = f.db.repository.snapshot(f.db.session);
        assertThat(projector.project(snapshot)).isEqualTo(projector.project(snapshot));
    }
    @Test void pendingAndTerminatedPendingAreExplicitlyDeferred() {
        var f = new ProtocolFixture(); f.intro(); f.respond();
        assertThat(projector.project(f.db.repository.snapshot(f.db.session))).isEqualTo(new PracticeRecordProjector.Deferred("I2"));
        f.protocol.terminate();
        assertThat(projector.project(f.db.repository.snapshot(f.db.session))).isEqualTo(new PracticeRecordProjector.Deferred("I2"));
    }
    @Test void failedNewScenarioKeepsOriginalAndRequiresI4() {
        var f = new ProtocolFixture(); f.intro(); f.respond(); f.judge(Label.functional); f.scenes.getFirst().completeExceptionally(new IllegalStateException());
        var snapshot = f.db.repository.snapshot(f.db.session);
        assertThat(projector.project(snapshot)).isEqualTo(new PracticeRecordProjector.Deferred("I4"));
        assertThat(snapshot.responses().getFirst().label()).isEqualTo(Label.functional);
    }
    @Test void issuedOnlySceneIsNotExposure() {
        var db = new PracticeDb(false);
        var issued = new PracticeAttempt(UUID.randomUUID(), db.goal.id(), AttemptType.ORIGINAL, AttemptState.ISSUED, UUID.randomUUID(), null, null, null);
        var snapshot = new PracticeRepository.Snapshot(List.of(db.goal.progress(GoalStatus.NOT_REACHED, null, null)), List.of(issued), List.of(), List.of(), List.of());
        var ready = (PracticeRecordProjector.Ready) projector.project(snapshot);
        assertThat(ready.practice().goals().getFirst().scenarios()).isEmpty();
    }
    @Test void finalActualResponseRequiresMatchingEvaluation() {
        var f = new ProtocolFixture(); f.intro(); f.respond(); f.judge(Label.functional);
        f.scenes.getFirst().complete(ProtocolFixture.scene()); f.playback(PracticePorts.Playback.COMPLETED); f.timers.advance(java.time.Duration.ofSeconds(20));
        var snapshot = f.db.repository.snapshot(f.db.session);
        assertThat(projector.project(snapshot)).isInstanceOf(PracticeRecordProjector.Ready.class);
        assertThatThrownBy(() -> projector.project(new PracticeRepository.Snapshot(snapshot.goals(), snapshot.attempts(), snapshot.responses(), snapshot.cues(), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void foreignAttemptCannotAppearInRecord() {
        var f = finished(); var s = f.db.repository.snapshot(f.db.session);
        var foreign = new PracticeAttempt(UUID.randomUUID(), UUID.randomUUID(), AttemptType.ORIGINAL, AttemptState.EXPOSED, UUID.randomUUID(), null, PracticeModelTest.T, null);
        assertThatThrownBy(() -> projector.project(new PracticeRepository.Snapshot(s.goals(), List.of(foreign), s.responses(), s.cues(), s.evaluations())))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsettledGoalIsNotNormalNullPractice() {
        var f = new ProtocolFixture();
        assertThat(projector.project(f.db.repository.snapshot(f.db.session))).isEqualTo(new PracticeRecordProjector.Deferred("I2/I4"));
    }
}
