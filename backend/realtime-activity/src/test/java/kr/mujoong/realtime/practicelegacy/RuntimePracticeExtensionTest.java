package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import static kr.mujoong.realtime.practicelegacy.PracticePorts.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.PracticeSessionExtension.*;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.testsupport.time.MutableClock;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class RuntimePracticeExtensionTest {
    static class Fixture implements PracticeOutput {
        final UUID session = UUID.randomUUID(), guardian = UUID.randomUUID();
        final MutableClock clock = new MutableClock();
        final ManualTimerDriver timer = new ManualTimerDriver(clock);
        final MemoryPracticeRepository repo = new MemoryPracticeRepository();
        final ArrayDeque<ExtensionEvent> queue = new ArrayDeque<>();
        final List<PracticeSpeech> speeches = new ArrayList<>();
        final Map<String, kr.mujoong.contract.time.TimerDriver.TimerHandle> handles = new HashMap<>();
        final List<RuntimeException> failures = new ArrayList<>();
        final List<CueBrief> briefs = new ArrayList<>();
        final CompletableFuture<EvaluationProvider.Result> pendingEvaluation = new CompletableFuture<>();
        final RuntimePracticeExtension extension;
        Label label = Label.functional;
        boolean evaluationPending, sceneFails, rejectSpeech, invalidCue;
        Signal signal = Signal.NONE;
        Fixture() {
            extension = new RuntimePracticeExtension(session, guardian, repo, clock, new EvaluationClock(clock), timer,
                    Runnable::run, Runnable::run,
                    new PracticeProperties(Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofSeconds(5),
                            Duration.ofSeconds(20), Duration.ofSeconds(20), Duration.ofSeconds(15), Duration.ofSeconds(5),
                            Duration.ofSeconds(12), Duration.ofSeconds(2), Duration.ZERO, "test"),
                    (id, goal, previous) -> sceneFails ? CompletableFuture.failedFuture(new IllegalStateException("fixture"))
                            : CompletableFuture.completedFuture(new Scene(UUID.randomUUID(), "새로운 놀이야.", "친구", "다음은 누구 차례야?")),
                    request -> evaluationPending ? pendingEvaluation : CompletableFuture.completedFuture(result(label, request.utteranceSeq())),
                    brief -> { briefs.add(brief); return CompletableFuture.completedFuture(new CueProvider.Result("친구의 말을 떠올려 보자.", !invalidCue, invalidCue)); },
                    failures::add);
        }
        static EvaluationProvider.Result result(Label label, int seq) { return new EvaluationProvider.Result(label, null, seq, label == Label.uncertain, "test", "test"); }
        void start() {
            var scenario = new PracticeScenarioClient.Scenario(UUID.randomUUID(), "ORIGINAL", "놀이 시간이야.", "친구", "내 차례야.",
                    new PracticeScenarioClient.Validation(true, true, false));
            var goal = new RuntimePracticeExtension.Goal(UUID.randomUUID(), 1, "차례", "turn", 1, "private-criteria", scenario);
            extension.onActive(new PracticeContext(session, JsonCodec.shared().write(new RuntimePracticeExtension.Package(List.of(goal))), Set.of(), false, clock.now()), this);
            drain();
        }
        PracticeSpeech speech() { return speeches.getLast(); }
        void playback(Playback status) {
            var speech = speech();
            signal = extension.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.PLAYBACK_FACT, "actual", speech.correlationId(),
                    new PlaybackFact(speech, status, speech.text())), this); drain();
        }
        void say(int seq, String text) { signal = extension.onChildUtterance(new Utterance(seq, clock.now(), text, false, false), this); drain(); }
        void drain() { while (!queue.isEmpty()) { var next = extension.onExtensionEvent(queue.removeFirst(), this); if (next != Signal.NONE) signal = next; } }
        void advance(int seconds) { timer.advance(Duration.ofSeconds(seconds)); drain(); }
        void settle() { extension.onStopping(); extension.onSettle(new EndContext(session, SessionStatus.ENDED, null, null, clock.now(), 0)); }
        @Override public UUID sessionId() { return session; }
        @Override public boolean speak(PracticeSpeech speech) { speeches.add(speech); return !rejectSpeech; }
        @Override public boolean cancelSpeech(UUID id) { return true; }
        @Override public boolean playApproved(String code) { return false; }
        @Override public boolean instructProvider(String text) { throw new AssertionError("typed speech only"); }
        @Override public boolean requestResponse() { throw new AssertionError("typed speech only"); }
        @Override public boolean cancelResponse() { return true; }
        @Override public boolean holdOutput(boolean hold) { return true; }
        @Override public boolean schedule(String name, Duration delay) {
            cancel(name); handles.put(name, timer.schedule(delay, () -> queue.add(new ExtensionEvent(ExtensionEvent.Kind.TIMER_FIRED, name, null, null)))); return true;
        }
        @Override public boolean cancel(String name) { var h = handles.remove(name); return h != null && h.cancel(); }
        @Override public boolean post(String name, Object value) { return queue.add(new ExtensionEvent(ExtensionEvent.Kind.ASYNC_RESULT, name, null, value)); }
    }
    @Test void realAdapterPreservesBothScenariosAndFinalResponsesThroughLastGoalAndSettlement() {
        var f = new Fixture(); f.start();
        assertThat(f.extension.progress().stage()).isEqualTo("SCENARIO_INTRO");
        assertThat(f.repo.snapshot(f.session).attempts()).allMatch(a -> a.exposedAt() == null);
        f.playback(Playback.COMPLETED); f.say(1, "기다릴게");
        assertThat(f.speeches).hasSize(2); f.playback(Playback.COMPLETED); f.say(2, "네 다음에 할게");
        assertThat(f.signal).isEqualTo(Signal.LAST_GOAL); f.settle();
        var record = (PracticeRecordProjector.PracticeRecord) f.extension.practiceRecord();
        assertThat(record.goals()).singleElement().satisfies(goal -> {
            assertThat(goal.status()).isEqualTo(GoalStatus.DONE); assertThat(goal.scenarios()).hasSize(2);
            assertThat(goal.responses()).extracting(PracticeRecordProjector.ResponseRecord::responseType).containsExactly(ResponseType.FIRST, ResponseType.NEW_SCENARIO);
        });
        assertThat(f.failures).isEmpty();
    }
    @Test void partialResponseNeedsConsentAndActualCueStartAndCompletionBeforeSecondResponse() {
        var f = new Fixture(); f.label = Label.partial; f.start(); f.playback(Playback.COMPLETED); f.say(1, "몰라");
        assertThat(f.speech().kind()).isEqualTo(OutputKind.HELP_QUESTION); f.playback(Playback.COMPLETED); f.say(2, "응");
        assertThat(f.speech().kind()).isEqualTo(OutputKind.CUE); f.playback(Playback.STARTED); f.playback(Playback.COMPLETED);
        assertThat(f.extension.progress().stage()).isEqualTo("AFTER_CUE_RESPONSE"); f.say(3, "네 다음에 할게");
        assertThat(f.briefs).singleElement().satisfies(brief -> assertThat(JsonCodec.shared().write(brief)).doesNotContain("private-criteria", "judgmentCriteria"));
        assertThat(f.repo.snapshot(f.session).cues()).singleElement().satisfies(cue -> {
            assertThat(cue.deliveryState()).isEqualTo(DeliveryState.COMPLETED); assertThat(cue.givenAt()).isNotNull();
        });
    }
    @Test void noResponseAndDeclinedHelpAreDistinctAndDoNotCreateEvaluationOrCueSpeech() {
        var f = new Fixture(); f.start(); f.playback(Playback.COMPLETED); f.advance(20); f.playback(Playback.COMPLETED); f.say(1, "아니");
        assertThat(f.repo.snapshot(f.session).responses()).singleElement().satisfies(r -> {
            assertThat(r.label()).isEqualTo(Label.NOT_PERFORMED); assertThat(r.notPerformedReason()).isEqualTo("NO_RESPONSE");
        });
        assertThat(f.repo.evaluations).isEmpty(); assertThat(f.briefs).isEmpty();
        assertThat(f.repo.snapshot(f.session).cues()).singleElement().extracting(CueDelivery::consentResponse).isEqualTo(Consent.DECLINED);
    }
    @Test void newSceneFailureKeepsOriginalEvidenceAndSkipsWithoutInventingScene() {
        var f = new Fixture(); f.sceneFails = true; f.start(); f.playback(Playback.COMPLETED); f.say(1, "내 다음이야"); f.settle();
        var record = (PracticeRecordProjector.PracticeRecord) f.extension.practiceRecord();
        assertThat(record.goals()).singleElement().satisfies(goal -> {
            assertThat(goal.status()).isEqualTo(GoalStatus.SKIPPED); assertThat(goal.skipReason()).isEqualTo("SCENARIO_FAILED");
            assertThat(goal.responses()).hasSize(1); assertThat(goal.scenarios()).hasSize(1);
        });
    }
    @Test void stoppingWaitsForTheAcceptedEvaluationAndNeverStartsAnotherScene() {
        var f = new Fixture(); f.evaluationPending = true; f.start(); f.playback(Playback.COMPLETED); f.say(1, "기다릴게");
        f.extension.onStopping();
        assertThat(f.extension.settlementDelay(f.clock.now())).isEqualTo(Duration.ofSeconds(12));
        assertThatThrownBy(f::settle).isInstanceOf(IllegalStateException.class);
        f.pendingEvaluation.complete(Fixture.result(Label.functional, 1)); f.drain();
        assertThat(f.extension.settlementDelay(f.clock.now())).isZero(); f.settle();
        assertThat(f.speeches).hasSize(1);
        assertThat(((PracticeRecordProjector.PracticeRecord) f.extension.practiceRecord()).goals()).singleElement()
                .extracting(PracticeRecordProjector.GoalRecord::skipReason).isEqualTo("ACTIVITY_ENDED");
    }
    @Test void unapprovedCueAndOutputFailureCannotBecomeExposedOrGiven() {
        var f = new Fixture(); f.invalidCue = true; f.start(); f.playback(Playback.COMPLETED); f.advance(20);
        f.playback(Playback.COMPLETED); f.say(1, "응");
        assertThat(f.speeches).noneMatch(s -> s.kind() == OutputKind.CUE);
        assertThat(f.repo.snapshot(f.session).cues()).singleElement().satisfies(c -> assertThat(c.givenAt()).isNull());
        var failed = new Fixture(); failed.rejectSpeech = true; failed.start(); failed.settle();
        assertThat(failed.repo.snapshot(failed.session).attempts()).allMatch(a -> a.exposedAt() == null);
    }
    @Test void missingPackageAndFirstScenarioFailClosed() {
        var f = new Fixture();
        assertThatThrownBy(() -> f.extension.onActive(new PracticeContext(f.session, "{\"goals\":[]}", Set.of(), false, f.clock.now()), f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.speeches).isEmpty(); assertThat(f.extension.practiceRecord()).isNull();
    }
}
