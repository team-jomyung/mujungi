package kr.mujoong.realtime.practice;

import static kr.mujoong.realtime.practice.PracticeTypes.*;
import static kr.mujoong.realtime.practice.PracticePorts.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.testsupport.time.MutableClock;
import kr.mujoong.testsupport.time.ManualTimerDriver;

final class ProtocolFixture implements OutputPort {
    final PracticeDb db = new PracticeDb(false);
    final MutableClock clock = new MutableClock(PracticeModelTest.T);
    final ManualTimerDriver timers = new ManualTimerDriver(clock);
    final List<Output> outputs = new ArrayList<>();
    final List<UUID> cancelled = new ArrayList<>();
    final List<PracticeResponse> judgments = new ArrayList<>();
    final List<CompletableFuture<Scene>> scenes = new ArrayList<>();
    final List<UUID> previous = new ArrayList<>();
    final List<RuntimeException> failures = new ArrayList<>();
    int lastGoals;
    final PracticeProtocol protocol;
    final Scene first = scene();
    ProtocolFixture() { this(1); }
    ProtocolFixture(int count) { this(count, java.util.function.UnaryOperator.identity()); }
    ProtocolFixture(int count, java.util.function.UnaryOperator<PracticeRepository> repository) {
        this(count, repository, false);
    }
    ProtocolFixture(int count, java.util.function.UnaryOperator<PracticeRepository> repository, boolean skipFailedScenario) {
        var goals = new ArrayList<SessionGoal>(); goals.add(db.goal);
        for (int i = 2; i <= count; i++) goals.add(new SessionGoal(UUID.randomUUID(), db.session, db.guardian,
                UUID.randomUUID(), i, "목표 " + i, "turn", 1, "고정 기준", GoalStatus.PENDING, null, 0, null));
        protocol = new PracticeProtocol(repository.apply(db.repository), goals, clock, timers, Runnable::run,
            new PracticeTiming(Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(20),
                    Duration.ofSeconds(20), Duration.ofSeconds(15)), EvaluationFixture.budget(), this,
            (session, goal, prior) -> { previous.add(prior); var future = new CompletableFuture<Scene>(); scenes.add(future); return future; },
            (response, criteria, text) -> judgments.add(response), failures::add, skipFailedScenario);
        protocol.start(first);
    }
    static Scene scene() { return new Scene(UUID.randomUUID(), "합성 상황", "친구", "차례를 말해 줘"); }
    @Override public void request(Output value) { outputs.add(value); }
    @Override public void cancel(UUID id) { cancelled.add(id); }
    @Override public void lastGoal(UUID id) { lastGoals++; }
    Output output() { return outputs.getLast(); }
    void playback(Playback fact) {
        var o = output(); protocol.playback(o.correlationId(), o.scenarioId(), o.generation(), fact, "실제 도움", false);
    }
    void intro() { playback(Playback.COMPLETED); }
    void silenceToHelp() { intro(); timers.advance(Duration.ofSeconds(20)); }
    void consent(Consent consent) { protocol.consent(consent, protocol.currentGoal().generation()); }
    void respond() { protocol.response(1, "합성 반응", output().scenarioId(), protocol.currentGoal().generation()); }
    void judge(Label label) {
        var p = judgments.getLast();
        var owned = db.repository.claimAttempt(p.id(), p.evaluationGeneration(), clock.now(), Duration.ofSeconds(5)).orElseThrow();
        db.repository.finalizeResult(p.id(), p.evaluationGeneration(), owned.work().activeAttemptToken(),
                new Evaluation(UUID.randomUUID(), p.id(), label, null, 1, label == Label.uncertain, 1, "fixture", "fixture"), clock.now());
        protocol.judged(p.id());
    }
}
