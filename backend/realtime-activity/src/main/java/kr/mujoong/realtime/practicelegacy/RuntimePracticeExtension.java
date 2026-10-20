package kr.mujoong.realtime.practicelegacy;

import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import static kr.mujoong.realtime.practicelegacy.PracticePorts.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.TimerDriver;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.voice.PracticeSpeech;

/** U4b의 직렬 큐와 U5b 프로토콜 사이의 실제 어댑터. */
public final class RuntimePracticeExtension implements PracticeSessionExtension {
    private final UUID sessionId, guardianId;
    private final PracticeRepository repository;
    private final MujoongClock clock, evaluationClock;
    private final TimerDriver evaluationTimers;
    private final Executor calls, finalizations;
    private final PracticeProperties settings;
    private final ScenarioPort scenarios;
    private final EvaluationProvider evaluations;
    private final CueProvider cues;
    private final Consumer<RuntimeException> failures;
    private PracticeOutput output;
    private PracticeProtocol protocol;
    private SessionPracticeTimers timers;
    private Output pending;
    private UUID playbackStarted;
    private boolean stopping, lastGoal;

    public RuntimePracticeExtension(UUID sessionId, UUID guardianId, PracticeRepository repository,
            MujoongClock clock, MujoongClock evaluationClock, TimerDriver evaluationTimers,
            Executor calls, Executor finalizations, PracticeProperties settings, ScenarioPort scenarios,
            EvaluationProvider evaluations, CueProvider cues, Consumer<RuntimeException> failures) {
        this.sessionId = sessionId; this.guardianId = guardianId; this.repository = repository;
        this.clock = clock; this.evaluationClock = evaluationClock; this.evaluationTimers = evaluationTimers;
        this.calls = calls; this.finalizations = finalizations; this.settings = settings;
        this.scenarios = scenarios; this.evaluations = evaluations; this.cues = cues; this.failures = failures;
    }
    record Package(List<Goal> goals) { }
    record Goal(UUID goalSelectionId, int order, String displayName, String behaviorKey, int behaviorVersion,
            String judgmentCriteria, PracticeScenarioClient.Scenario firstScenario) { }
    @Override public boolean controlsOutput() { return true; }
    @Override public Signal onActive(PracticeContext context, PracticeOutput target) {
        if (protocol != null) return Signal.NONE;
        output = target;
        Package pack = JsonCodec.shared().read(context.practiceJson(), Package.class);
        if (pack.goals() == null || pack.goals().isEmpty()) throw new IllegalArgumentException("연습 목표 없음");
        List<SessionGoal> goals = pack.goals().stream().map(g -> new SessionGoal(
                UUID.nameUUIDFromBytes((sessionId + ":" + g.goalSelectionId()).getBytes(StandardCharsets.UTF_8)),
                sessionId, guardianId, g.goalSelectionId(), g.order(), g.displayName(), g.behaviorKey(),
                g.behaviorVersion(), g.judgmentCriteria(), GoalStatus.PENDING, null, 0, null)).toList();
        for (int i = 0; i < goals.size(); i++) {
            if (goals.get(i).order() != i + 1) throw new IllegalArgumentException("연습 순서 오류");
        }
        var first = pack.goals().getFirst().firstScenario();
        if (first == null) throw new IllegalArgumentException("첫 상황 없음");
        timers = new SessionPracticeTimers(output, clock);
        var budget = settings.evaluationBudget().orElseThrow(() -> new IllegalStateException("판정 최종화 예산 없음"));
        ResponseEvaluator evaluator = new ResponseEvaluator(repository, budget, evaluations, evaluationClock,
                evaluationTimers, calls, finalizations, completion -> output.post("u5b-judged", completion),
                this::failed);
        protocol = new PracticeProtocol(repository, goals, clock, timers,
                action -> output.post("u5b-action", action), settings.timing(), budget, new OutputPort() {
                    @Override public void request(Output value) { requestSpeech(value); }
                    @Override public void cancel(UUID correlation) {
                        if (pending != null && pending.correlationId().equals(correlation)) { pending = null; playbackStarted = null; }
                        output.cancelSpeech(correlation);
                    }
                    @Override public void lastGoal(UUID ignored) { lastGoal = true; }
                }, scenarios, (response, criteria, text) -> evaluator.evaluate(response.id(), criteria, text), this::failed, true);
        protocol.start(first.scene("ORIGINAL"));
        return signal();
    }
    private void failed(RuntimeException failure) {
        failures.accept(failure);
        if (output != null) output.post("u5b-failure", null);
    }
    private void requestSpeech(Output value) {
        if (stopping) return;
        pending = value;
        playbackStarted = null;
        switch (value.kind()) {
            case SCENARIO -> speak(value, value.scene().partnerRole(), value.scene().situation() + "\n" + value.scene().trigger());
            case HELP_QUESTION -> speak(value, "친구", "도움을 받아 볼까?");
            case CUE -> {
                Scene scene = protocol.currentScene();
                CueBrief brief = new CueBrief(scene.situation(), scene.trigger(), "상황을 살펴보고 스스로 다음 말을 떠올리도록 돕기");
                calls.execute(() -> {
                    try {
                        cues.generate(brief).whenComplete((cue, failure) ->
                                output.post("u5b-cue", new CueReady(value, cue, failure != null)));
                    } catch (RuntimeException failure) { output.post("u5b-cue", new CueReady(value, null, true)); }
                });
            }
        }
    }
    private record CueReady(Output owner, CueProvider.Result result, boolean failed) { }
    private void speak(Output value, String role, String text) {
        try {
            if (!output.speak(new PracticeSpeech(value.correlationId(), value.scenarioId(), value.generation(), value.kind(), role, text))) {
                failPlayback(value);
            } else {
                Duration timeout = value.kind() == OutputKind.CUE ? settings.cueBudget() : settings.scenarioBudget();
                timers.schedule(timeout, () -> {
                    if (pending == value && !value.correlationId().equals(playbackStarted)) failPlayback(value);
                });
            }
        } catch (IllegalArgumentException invalid) { failPlayback(value); }
    }
    private void failPlayback(Output value) {
        output.post("u5b-playback-failed", value);
    }
    @Override public Signal onChildUtterance(Utterance utterance, PracticeOutput ignored) {
        if (protocol == null || stopping || utterance.seq() <= 0
                || utterance.devicePlaying() || utterance.text() == null || utterance.text().isBlank()) return Signal.NONE;
        var goal = protocol.currentGoal();
        if (goal.currentStage() == Stage.HELP_OFFER) {
            if (utterance.uncertain()) return Signal.NONE;
            String value = utterance.text().replaceAll("[\\s.!?~]+", "");
            if (List.of("응", "네", "좋아", "도와줘", "도움줘").contains(value)) protocol.consent(Consent.ACCEPTED, goal.generation());
            else if (List.of("아니", "아니요", "괜찮아", "혼자할래", "싫어").contains(value)) protocol.consent(Consent.DECLINED, goal.generation());
        } else if (protocol.currentScenarioId() != null) {
            protocol.response(utterance.seq(), utterance.text(), protocol.currentScenarioId(), goal.generation());
        }
        return signal();
    }
    @Override public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput ignored) {
        if (protocol == null) return Signal.NONE;
        if (event.kind() == ExtensionEvent.Kind.PLAYBACK_FACT && event.value() instanceof PlaybackFact fact) {
            var speech = fact.speech();
            if (pending == null || !pending.correlationId().equals(speech.correlationId())) return Signal.NONE;
            boolean terminal = fact.status() != Playback.STARTED;
            if (!terminal) playbackStarted = speech.correlationId();
            if (terminal) pending = null;
            protocol.playback(speech.correlationId(), speech.scenarioId(), speech.generation(), fact.status(), fact.text(), false,
                    fact.receivedAt() == null ? clock.now() : fact.receivedAt(), fact.pausedFor());
            if (terminal && fact.status() != Playback.COMPLETED && protocol.awaitingAgreement()) {
                protocol.failCurrentGoal("OUTPUT_FAILED");
            }
        } else if (event.kind() == ExtensionEvent.Kind.TIMER_FIRED) {
            if (!stopping) timers.fire(event.name());
        } else if (event.name().equals("u5b-judged") && event.value() instanceof ResponseEvaluator.Completion completion) {
            if (!completion.committedWithinBudget()) failures.accept(new IllegalStateException("연습 판정 시한 초과"));
            protocol.judged(completion.responseId());
        } else if (event.name().equals("u5b-action") && event.value() instanceof Runnable action) {
            if (!stopping) action.run();
        } else if (event.name().equals("u5b-cue") && event.value() instanceof CueReady ready && pending == ready.owner() && !stopping) {
            var cue = ready.result();
            var goal = protocol.currentGoal();
            boolean valid = cue != null && new StageOutputPolicy().cue(
                    new StageOutputPolicy.Context(protocol.currentScenarioId(), goal.currentStage(), goal.generation(),
                            Consent.ACCEPTED, !stopping, true, true, playbackStarted != null),
                    ready.owner().scenarioId(), ready.owner().generation(),
                    new CueBrief(protocol.currentScene().situation(), protocol.currentScene().trigger(), "스스로 말하기"),
                    cue.approved(), cue.containsAnswerSentence()).isPresent();
            if (ready.failed() || !valid) failPlayback(ready.owner());
            else speak(ready.owner(), protocol.currentScene().partnerRole(), cue.text());
        } else if (event.name().equals("u5b-playback-failed") && event.value() instanceof Output value && pending == value && !stopping) {
            pending = null;
            protocol.playback(value.correlationId(), value.scenarioId(), value.generation(), Playback.FAILED, null, false);
            if (protocol.awaitingAgreement()) protocol.failCurrentGoal("OUTPUT_FAILED");
        } else if (event.name().equals("u5b-failure")) {
            throw new IllegalStateException("연습 처리 실패");
        }
        return signal();
    }
    private Signal signal() { return !stopping && lastGoal ? Signal.LAST_GOAL : Signal.NONE; }
    @Override public Progress progress() {
        if (protocol == null) return null;
        var goal = protocol.currentGoal();
        return new Progress(goal.order(), protocol.goalCount(), goal.displayName(), goal.currentStage().name());
    }
    @Override public void onStopping() {
        if (stopping) return;
        stopping = true; pending = null;
        if (protocol != null) protocol.stopProgress();
        if (timers != null) timers.close();
    }
    @Override public Duration settlementDelay(Instant now) {
        return protocol == null ? Duration.ZERO : protocol.settlementDelay(now);
    }
    @Override public void onSettle(EndContext end) { onStopping(); if (protocol != null) protocol.settle(); }
    @Override public Object practiceRecord() {
        if (protocol == null) return null;
        var projected = new PracticeRecordProjector().projectSettled(repository.snapshot(sessionId));
        if (projected instanceof PracticeRecordProjector.Ready ready) return ready.practice();
        throw new IllegalStateException("연습 원본 정산 미완료");
    }
}
