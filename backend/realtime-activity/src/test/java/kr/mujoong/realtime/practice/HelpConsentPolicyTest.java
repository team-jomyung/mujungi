package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practice.PracticeTypes.*;
import static kr.mujoong.realtime.practice.PracticePorts.*;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class HelpConsentPolicyTest {
    @Test void silenceAtTwentyRecordsNoResponseAndAsksWithoutEvaluation() {
        var f = new ProtocolFixture(); f.intro(); f.timers.advance(Duration.ofMillis(19_999));
        assertThat(f.db.repository.snapshot(f.db.session).responses()).isEmpty();
        f.timers.advance(Duration.ofMillis(1));
        var response = f.db.repository.snapshot(f.db.session).responses().getFirst();
        assertThat(response.label()).isEqualTo(Label.NOT_PERFORMED); assertThat(response.notPerformedReason()).isEqualTo("NO_RESPONSE");
        assertThat(f.judgments).isEmpty(); assertThat(f.output().kind()).isEqualTo(OutputKind.HELP_QUESTION);
        f.timers.advance(Duration.ofMillis(1)); assertThat(f.db.repository.snapshot(f.db.session).responses()).hasSize(1);
    }
    @Test void consentClockStartsAtRealQuestionCompletionAndDuplicateDoesNotExtend() {
        var f = new ProtocolFixture(); f.silenceToHelp(); f.timers.advance(Duration.ofSeconds(30));
        assertThat(f.scenes).isEmpty(); f.playback(Playback.COMPLETED);
        f.timers.advance(Duration.ofMillis(9_999)); f.playback(Playback.COMPLETED); assertThat(f.scenes).isEmpty();
        f.timers.advance(Duration.ofMillis(1)); assertThat(f.scenes).hasSize(1);
        assertThat(f.db.repository.snapshot(f.db.session).cues().getFirst().consentResponse()).isEqualTo(Consent.NO_RESPONSE);
    }
    @Test void acceptedDeliversCueOnceThenOpensAfterCue() {
        var f = new ProtocolFixture(); f.silenceToHelp(); f.playback(Playback.COMPLETED);
        long generation = f.protocol.currentGoal().generation(); f.consent(Consent.ACCEPTED);
        f.protocol.consent(Consent.ACCEPTED, generation); assertThat(f.output().kind()).isEqualTo(OutputKind.CUE);
        f.playback(Playback.COMPLETED); assertThat(f.protocol.currentGoal().currentStage()).isEqualTo(Stage.CUE);
        f.playback(Playback.STARTED); f.playback(Playback.COMPLETED);
        assertThat(f.protocol.currentGoal().currentStage()).isEqualTo(Stage.AFTER_CUE_RESPONSE);
        assertThat(f.db.repository.snapshot(f.db.session).cues().getFirst().deliveryState()).isEqualTo(DeliveryState.COMPLETED);
        f.timers.advance(Duration.ofSeconds(20)); assertThat(f.scenes).hasSize(1); assertThat(f.judgments).isEmpty();
        var actual = new ProtocolFixture(); actual.silenceToHelp(); actual.playback(Playback.COMPLETED); actual.consent(Consent.ACCEPTED);
        actual.playback(Playback.STARTED); actual.playback(Playback.COMPLETED); actual.respond(); actual.judge(Label.functional);
        assertThat(actual.db.repository.snapshot(actual.db.session).responses()).extracting(PracticeResponse::responseType)
                .containsExactlyInAnyOrder(ResponseType.FIRST, ResponseType.AFTER_CUE);
        assertThat(actual.scenes).hasSize(1);
    }
    @Test void declinedNeverRequestsCue() {
        var f = new ProtocolFixture(); f.silenceToHelp(); f.playback(Playback.COMPLETED); f.consent(Consent.DECLINED);
        assertThat(f.outputs).noneMatch(o -> o.kind() == OutputKind.CUE);
        assertThat(f.db.repository.snapshot(f.db.session).cues().getFirst().consentResponse()).isEqualTo(Consent.DECLINED);
        assertThat(f.scenes).hasSize(1);
    }
    @Test void cueStartTimeoutDoesNotBecomeDeliveredAndLateStartIsIgnored() {
        var f = new ProtocolFixture(); f.silenceToHelp(); f.playback(Playback.COMPLETED); f.consent(Consent.ACCEPTED);
        var cue = f.output(); f.timers.advance(Duration.ofSeconds(5));
        f.protocol.playback(cue.correlationId(), cue.scenarioId(), cue.generation(), Playback.STARTED, "늦은 도움", false);
        assertThat(f.db.repository.snapshot(f.db.session).cues().getFirst().givenAt()).isNull(); assertThat(f.scenes).hasSize(1);
    }
    @Test void terminationAndQuestionFailureNeverFabricateConsentTimeout() {
        var f = new ProtocolFixture(); f.silenceToHelp(); f.playback(Playback.FAILED); f.timers.advance(Duration.ofSeconds(10));
        assertThat(f.db.repository.snapshot(f.db.session).cues()).isEmpty(); assertThat(f.scenes).isEmpty();
        var ended = new ProtocolFixture(); ended.silenceToHelp(); ended.playback(Playback.COMPLETED); ended.protocol.terminate();
        ended.timers.advance(Duration.ofSeconds(10)); ended.consent(Consent.ACCEPTED);
        assertThat(ended.db.repository.snapshot(ended.db.session).cues()).isEmpty();
    }
    @Test void sameBoundarySerialOrdersHaveOneWinner() {
        var responseFirst = new ProtocolFixture(); responseFirst.intro(); responseFirst.clock.advance(Duration.ofSeconds(20));
        responseFirst.respond(); responseFirst.timers.runDue(); assertThat(responseFirst.judgments).hasSize(1);
        var timeoutFirst = new ProtocolFixture(); timeoutFirst.intro(); timeoutFirst.timers.advance(Duration.ofSeconds(20));
        timeoutFirst.respond(); assertThat(timeoutFirst.judgments).isEmpty();
        assertThat(timeoutFirst.db.repository.snapshot(timeoutFirst.db.session).responses()).hasSize(1);
        var consentFirst = new ProtocolFixture(); consentFirst.silenceToHelp(); consentFirst.playback(Playback.COMPLETED);
        consentFirst.clock.advance(Duration.ofSeconds(10)); consentFirst.consent(Consent.ACCEPTED); consentFirst.timers.runDue();
        assertThat(consentFirst.db.repository.snapshot(consentFirst.db.session).cues().getFirst().consentResponse()).isEqualTo(Consent.ACCEPTED);
        var consentTimeoutFirst = new ProtocolFixture(); consentTimeoutFirst.silenceToHelp(); consentTimeoutFirst.playback(Playback.COMPLETED);
        consentTimeoutFirst.timers.advance(Duration.ofSeconds(10)); consentTimeoutFirst.consent(Consent.ACCEPTED);
        assertThat(consentTimeoutFirst.db.repository.snapshot(consentTimeoutFirst.db.session).cues().getFirst().consentResponse()).isEqualTo(Consent.NO_RESPONSE);
    }
    @Test void newScenarioSilenceCompletesWithoutAdditionalCue() {
        var f = new ProtocolFixture(); f.silenceToHelp(); f.playback(Playback.COMPLETED); f.consent(Consent.DECLINED);
        f.scenes.getFirst().complete(ProtocolFixture.scene()); f.playback(Playback.COMPLETED);
        f.timers.advance(Duration.ofSeconds(20)); assertThat(f.lastGoals).isEqualTo(1);
        assertThat(f.outputs).noneMatch(o -> o.kind() == OutputKind.CUE);
        assertThat(f.db.repository.snapshot(f.db.session).responses()).hasSize(2);
    }
}
