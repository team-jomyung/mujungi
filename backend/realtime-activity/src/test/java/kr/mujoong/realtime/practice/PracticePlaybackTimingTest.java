package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practice.PracticePorts.*;
import static kr.mujoong.realtime.practice.PracticeTypes.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class PracticePlaybackTimingTest {
    static final Instant T = PracticeModelTest.T;

    static class Fixture implements OutputPort {
        final UUID session = UUID.randomUUID();
        final MemoryPracticeRepository repo = new MemoryPracticeRepository();
        final MutableClock clock = new MutableClock(T);
        final ManualTimerDriver timer = new ManualTimerDriver(clock);
        final List<Output> outputs = new ArrayList<>();
        final PracticeProtocol protocol;
        Fixture() {
            var goal = new SessionGoal(UUID.randomUUID(), session, UUID.randomUUID(), UUID.randomUUID(),
                    1, "차례", "turn", 1, "고정 기준", GoalStatus.PENDING, null, 0, null);
            protocol = new PracticeProtocol(repo, List.of(goal), clock, timer, Runnable::run,
                    new PracticeTiming(Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofSeconds(5),
                            Duration.ofSeconds(20), Duration.ofSeconds(20), Duration.ofSeconds(15)),
                    new EvaluationBudget("test", Duration.ofSeconds(12), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ZERO),
                    this, (id, current, previous) -> new CompletableFuture<>(),
                    (response, criteria, text) -> {}, failure -> { throw failure; });
            protocol.start(new Scene(UUID.randomUUID(), "놀이 시간", "친구", "누구 차례야?"));
        }
        @Override public void request(Output output) { outputs.add(output); }
        @Override public void cancel(UUID id) {}
        @Override public void lastGoal(UUID id) {}
        void playback(Playback fact, Instant receivedAt) {
            playback(fact, receivedAt, Duration.ZERO);
        }
        void playback(Playback fact, Instant receivedAt, Duration pausedFor) {
            var output = outputs.getLast();
            protocol.playback(output.correlationId(), output.scenarioId(), output.generation(), fact,
                    "친구의 말을 떠올려 보자.", false, receivedAt, pausedFor);
        }
        void helpQuestion() { playback(Playback.COMPLETED, T); timer.advance(Duration.ofSeconds(20)); }
    }

    @Test void queuedScenarioCompletionKeepsExposureAndFirstResponseDeadlineAtReceipt() {
        var f = new Fixture(); f.clock.advance(Duration.ofSeconds(3)); f.playback(Playback.COMPLETED, T);
        assertThat(f.repo.snapshot(f.session).attempts()).singleElement()
                .extracting(PracticeAttempt::exposedAt).isEqualTo(T);
        f.timer.advance(Duration.ofSeconds(16));
        assertThat(f.repo.snapshot(f.session).responses()).isEmpty();
        f.timer.advance(Duration.ofSeconds(1));
        assertThat(f.repo.snapshot(f.session).responses()).singleElement().satisfies(response -> {
            assertThat(response.opportunityOpenedAt()).isEqualTo(T);
            assertThat(response.closedAt()).isEqualTo(T.plusSeconds(20));
        });
    }

    @Test void queuedHelpQuestionCompletionDoesNotExtendTenSecondConsent() {
        var f = new Fixture(); f.helpQuestion(); f.clock.advance(Duration.ofSeconds(3));
        f.playback(Playback.COMPLETED, T.plusSeconds(20));
        f.timer.advance(Duration.ofSeconds(6));
        assertThat(f.repo.snapshot(f.session).cues()).isEmpty();
        f.timer.advance(Duration.ofSeconds(1));
        assertThat(f.repo.snapshot(f.session).cues()).singleElement()
                .extracting(CueDelivery::consentResponse).isEqualTo(Consent.NO_RESPONSE);
    }

    @Test void queuedCueFactsKeepGivenAtAndAfterCueResponseDeadlineAtReceipt() {
        var f = new Fixture(); f.helpQuestion(); f.playback(Playback.COMPLETED, T.plusSeconds(20));
        f.protocol.consent(Consent.ACCEPTED, f.protocol.currentGoal().generation());
        f.clock.advance(Duration.ofSeconds(3)); f.playback(Playback.STARTED, T.plusSeconds(20));
        f.playback(Playback.COMPLETED, T.plusSeconds(20));
        assertThat(f.repo.snapshot(f.session).cues()).singleElement()
                .extracting(CueDelivery::givenAt).isEqualTo(T.plusSeconds(20));
        f.timer.advance(Duration.ofSeconds(16));
        assertThat(f.repo.snapshot(f.session).responses()).hasSize(1);
        f.timer.advance(Duration.ofSeconds(1));
        assertThat(f.repo.snapshot(f.session).responses()).filteredOn(r -> r.responseType() == ResponseType.AFTER_CUE)
                .singleElement().satisfies(response -> {
                    assertThat(response.opportunityOpenedAt()).isEqualTo(T.plusSeconds(20));
                    assertThat(response.closedAt()).isEqualTo(T.plusSeconds(40));
                });
    }

    @Test void queuedScenarioCompletionExcludesOnlyPauseAndKeepsActualExposureTime() {
        var f = new Fixture(); f.clock.advance(Duration.ofSeconds(33));
        f.playback(Playback.COMPLETED, T, Duration.ofSeconds(30));
        assertThat(f.repo.snapshot(f.session).attempts()).singleElement()
                .extracting(PracticeAttempt::exposedAt).isEqualTo(T);
        f.timer.advance(Duration.ofSeconds(16));
        assertThat(f.repo.snapshot(f.session).responses()).isEmpty();
        f.timer.advance(Duration.ofSeconds(1));
        assertThat(f.repo.snapshot(f.session).responses()).singleElement().satisfies(response -> {
            assertThat(response.opportunityOpenedAt()).isEqualTo(T);
            assertThat(response.closedAt()).isEqualTo(T.plusSeconds(50));
        });
    }

    @Test void queuedHelpQuestionPreservesConsentWindowAcrossPauseWithoutAddingBacklog() {
        var f = new Fixture(); f.helpQuestion(); f.clock.advance(Duration.ofSeconds(33));
        f.playback(Playback.COMPLETED, T.plusSeconds(20), Duration.ofSeconds(30));
        f.timer.advance(Duration.ofSeconds(6));
        assertThat(f.repo.snapshot(f.session).cues()).isEmpty();
        f.timer.advance(Duration.ofSeconds(1));
        assertThat(f.repo.snapshot(f.session).cues()).singleElement()
                .extracting(CueDelivery::consentResponse).isEqualTo(Consent.NO_RESPONSE);
    }

    @Test void queuedCueCompletionPreservesResponseWindowAndActualGivenAtAcrossPause() {
        var f = new Fixture(); f.helpQuestion(); f.playback(Playback.COMPLETED, T.plusSeconds(20));
        f.protocol.consent(Consent.ACCEPTED, f.protocol.currentGoal().generation());
        f.clock.advance(Duration.ofSeconds(33));
        f.playback(Playback.STARTED, T.plusSeconds(20), Duration.ofSeconds(30));
        f.playback(Playback.COMPLETED, T.plusSeconds(20), Duration.ofSeconds(30));
        assertThat(f.repo.snapshot(f.session).cues()).singleElement()
                .extracting(CueDelivery::givenAt).isEqualTo(T.plusSeconds(20));
        f.timer.advance(Duration.ofSeconds(16));
        assertThat(f.repo.snapshot(f.session).responses()).hasSize(1);
        f.timer.advance(Duration.ofSeconds(1));
        assertThat(f.repo.snapshot(f.session).responses()).filteredOn(r -> r.responseType() == ResponseType.AFTER_CUE)
                .singleElement().satisfies(response -> {
                    assertThat(response.opportunityOpenedAt()).isEqualTo(T.plusSeconds(20));
                    assertThat(response.closedAt()).isEqualTo(T.plusSeconds(70));
                });
    }
}
