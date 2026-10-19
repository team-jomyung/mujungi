package kr.mujoong.realtime.practicelegacy;

import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import static kr.mujoong.realtime.practicelegacy.PracticePorts.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.TimerDriver;

/** U5b 로컬 단계 조정자. 실제 U4b의 생명주기/우선순위/정지 시계는 복제하지 않는다. */
public final class PracticeProtocol {
    private final PracticeRepository repository;
    private final List<SessionGoal> goals;
    private final MujoongClock clock;
    private final TimerDriver timers;
    private final Executor events;
    private final PracticeTiming timing;
    private final EvaluationBudget evaluationBudget;
    private final OutputPort output;
    private final ScenarioPort scenarios;
    private final EvaluationPort evaluation;
    private final Consumer<RuntimeException> failures;
    private final boolean skipFailedScenario;
    private int index;
    private boolean started, ended, stopping, awaitingAgreement, opportunityOpen, questionDone, cueStarted;
    private PracticeAttempt attempt;
    private Scene scene;
    private Output pendingOutput;
    private CueDelivery cue;
    private UUID pendingResponse;
    private Instant openedAt;

    public PracticeProtocol(PracticeRepository repository, List<SessionGoal> goals, MujoongClock clock,
            TimerDriver timers, Executor events, PracticeTiming timing, EvaluationBudget evaluationBudget,
            OutputPort output, ScenarioPort scenarios, EvaluationPort evaluation, Consumer<RuntimeException> failures) {
        this(repository, goals, clock, timers, events, timing, evaluationBudget, output, scenarios, evaluation, failures, false);
    }
    public PracticeProtocol(PracticeRepository repository, List<SessionGoal> goals, MujoongClock clock,
            TimerDriver timers, Executor events, PracticeTiming timing, EvaluationBudget evaluationBudget,
            OutputPort output, ScenarioPort scenarios, EvaluationPort evaluation, Consumer<RuntimeException> failures,
            boolean skipFailedScenario) {
        this.repository = Objects.requireNonNull(repository); this.goals = new ArrayList<>(goals);
        PracticeChecks.require(!goals.isEmpty(), "고정 목표 필요");
        this.clock = Objects.requireNonNull(clock); this.timers = Objects.requireNonNull(timers);
        this.events = Objects.requireNonNull(events); this.timing = Objects.requireNonNull(timing);
        this.evaluationBudget = Objects.requireNonNull(evaluationBudget); this.output = Objects.requireNonNull(output);
        this.scenarios = Objects.requireNonNull(scenarios); this.evaluation = Objects.requireNonNull(evaluation);
        this.failures = Objects.requireNonNull(failures);
        this.skipFailedScenario = skipFailedScenario;
    }
    public synchronized SessionGoal currentGoal() { return goals.get(index); }
    public synchronized UUID currentScenarioId() { return attempt == null ? null : attempt.scenarioId(); }
    public synchronized Scene currentScene() { return scene; }
    public int goalCount() { return goals.size(); }
    public synchronized boolean awaitingAgreement() { return awaitingAgreement; }
    public synchronized void start(Scene firstScene) {
        if (started || ended || stopping) return;
        repository.initialize(goals); started = true;
        transition(Stage.SCENARIO_INTRO); issue(firstScene, AttemptType.ORIGINAL, null);
    }
    private void transition(Stage stage) {
        var next = currentGoal().progress(GoalStatus.RUNNING, stage, null);
        repository.saveGoal(next); goals.set(index, next);
        opportunityOpen = false; questionDone = false; pendingOutput = null;
    }
    private void issue(Scene value, AttemptType type, UUID previous) {
        scene = value;
        attempt = new PracticeAttempt(attempt == null || attempt.attemptType() != type
                || !attempt.sessionGoalId().equals(currentGoal().id()) ? UUID.randomUUID() : attempt.id(),
                currentGoal().id(), type, AttemptState.ISSUED, value.id(), previous, null, null);
        repository.saveAttempt(attempt); send(OutputKind.SCENARIO);
    }
    private void send(OutputKind kind) {
        var goal = currentGoal();
        pendingOutput = new Output(UUID.randomUUID(), goal.sessionId(), goal.id(), scene.id(), goal.generation(), kind,
                kind == OutputKind.SCENARIO ? scene : null);
        output.request(pendingOutput);
    }
    /** 세대는 목표마다 다시 시작하므로 목표·시도·단계까지 같은 작업만 소유한다. */
    private record WorkOwner(UUID sessionGoalId, UUID attemptId, Stage stage,
            long generation, AttemptState attemptState) {}
    private WorkOwner owner() {
        var goal = currentGoal();
        return new WorkOwner(goal.id(), attempt.id(), goal.currentStage(), goal.generation(), attempt.state());
    }
    private boolean owns(WorkOwner expected) {
        return !ended && !stopping && !awaitingAgreement && expected.equals(owner());
    }
    private void schedule(Duration delay, Runnable action) {
        scheduleFrom(clock.now(), delay, action);
    }
    private void scheduleFrom(Instant startedAt, Duration delay, Runnable action) {
        var expected = owner();
        Instant deadline = startedAt.plus(delay);
        timers.scheduleAt(deadline, () -> events.execute(() -> {
            synchronized (this) {
                if (owns(expected) && !clock.now().isBefore(deadline)) action.run();
            }
        }));
    }
    /** 합의된 어댑터가 실제 재생 사실만 전달해야 한다. 단순 PLAYBACK_DONE은 이 입력이 아니다. */
    public synchronized void playback(UUID correlation, UUID scenarioId, long generation, Playback fact,
            String actualText, boolean containsAnswerSentence) {
        playback(correlation, scenarioId, generation, fact, actualText, containsAnswerSentence, clock.now());
    }
    public synchronized void playback(UUID correlation, UUID scenarioId, long generation, Playback fact,
            String actualText, boolean containsAnswerSentence, Instant receivedAt) {
        playback(correlation, scenarioId, generation, fact, actualText, containsAnswerSentence, receivedAt, Duration.ZERO);
    }
    public synchronized void playback(UUID correlation, UUID scenarioId, long generation, Playback fact,
            String actualText, boolean containsAnswerSentence, Instant receivedAt, Duration pausedFor) {
        Objects.requireNonNull(receivedAt);
        PracticeChecks.require(pausedFor != null && !pausedFor.isNegative(), "일시정지 겹침 시간 오류");
        if (ended || stopping || awaitingAgreement || pendingOutput == null || !pendingOutput.correlationId().equals(correlation)
                || !pendingOutput.scenarioId().equals(scenarioId) || currentGoal().generation() != generation) return;
        var kind = pendingOutput.kind();
        if (fact == Playback.FAILED || fact == Playback.INTERRUPTED || fact == Playback.OMITTED) {
            output.cancel(correlation);
            if (kind == OutputKind.CUE) {
                cue = new CueDelivery(cue.id(), cue.attemptId(), cue.scenarioId(), Consent.ACCEPTED,
                        DeliveryState.FAILED, cue.deliveredText(), cue.givenAt(), cue.containsAnswerSentence());
                repository.saveCue(cue); prepareNew();
            } else { awaitingAgreement = true; opportunityOpen = false; }
            return;
        }
        if (kind == OutputKind.CUE && fact == Playback.STARTED && !cueStarted) {
            PracticeChecks.text(actualText, "실제 도움 전사"); cueStarted = true;
            cue = new CueDelivery(cue.id(), cue.attemptId(), cue.scenarioId(), Consent.ACCEPTED,
                    DeliveryState.STARTED, actualText, receivedAt, containsAnswerSentence);
            repository.saveCue(cue); return;
        }
        if (fact != Playback.COMPLETED) return;
        if (kind == OutputKind.SCENARIO) {
            attempt = new PracticeAttempt(attempt.id(), attempt.sessionGoalId(), attempt.attemptType(), AttemptState.EXPOSED,
                    attempt.scenarioId(), attempt.previousScenarioId(), receivedAt, null);
            repository.saveAttempt(attempt);
            open(attempt.attemptType() == AttemptType.ORIGINAL ? Stage.FIRST_RESPONSE : Stage.NEW_SCENARIO_RESPONSE, receivedAt, pausedFor);
        } else if (kind == OutputKind.HELP_QUESTION && !questionDone) {
            questionDone = true;
            scheduleFrom(receivedAt.plus(pausedFor), timing.helpConsent(), () -> consent(Consent.NO_RESPONSE, generation));
        } else if (kind == OutputKind.CUE && cueStarted) {
            cue = new CueDelivery(cue.id(), cue.attemptId(), cue.scenarioId(), Consent.ACCEPTED,
                    DeliveryState.COMPLETED, cue.deliveredText(), cue.givenAt(), cue.containsAnswerSentence());
            repository.saveCue(cue); open(Stage.AFTER_CUE_RESPONSE, receivedAt, pausedFor);
        }
    }
    private void open(Stage stage, Instant receivedAt, Duration pausedFor) {
        transition(stage); opportunityOpen = true; openedAt = receivedAt;
        Duration wait = switch (stage) {
            case FIRST_RESPONSE -> timing.firstResponse();
            case AFTER_CUE_RESPONSE -> timing.afterCueResponse();
            case NEW_SCENARIO_RESPONSE -> timing.newScenarioResponse();
            default -> throw new IllegalStateException("반응 단계가 아님");
        };
        scheduleFrom(receivedAt.plus(pausedFor), wait, this::noResponse);
    }
    private ResponseType responseType() {
        return switch (currentGoal().currentStage()) {
            case FIRST_RESPONSE -> ResponseType.FIRST;
            case AFTER_CUE_RESPONSE -> ResponseType.AFTER_CUE;
            case NEW_SCENARIO_RESPONSE -> ResponseType.NEW_SCENARIO;
            default -> throw new IllegalStateException("반응 단계가 아님");
        };
    }
    public synchronized void response(int utteranceSeq, String text, UUID scenarioId, long generation) {
        if (ended || stopping || !opportunityOpen || currentGoal().generation() != generation || !attempt.scenarioId().equals(scenarioId)) return;
        PracticeChecks.text(text, "반응"); Instant accepted = clock.now();
        var candidate = new PracticeResponse(UUID.randomUUID(), attempt.id(), scenarioId, responseType(), utteranceSeq,
                EvaluationState.PENDING, generation, accepted, evaluationBudget.begin(accepted), null, null, openedAt, null);
        var saved = repository.accept(candidate); opportunityOpen = false; pendingResponse = saved.id();
        evaluation.request(saved, currentGoal().judgmentCriteriaSnapshot(), text);
    }
    private void noResponse() {
        if (!opportunityOpen) return;
        Instant now = clock.now(); var type = responseType();
        repository.accept(new PracticeResponse(UUID.randomUUID(), attempt.id(), attempt.scenarioId(), type, null,
                EvaluationState.FINALIZED, currentGoal().generation(), now, null, Label.NOT_PERFORMED,
                "NO_RESPONSE", openedAt, now));
        opportunityOpen = false;
        if (type == ResponseType.FIRST) help(); else if (type == ResponseType.AFTER_CUE) prepareNew(); else completeGoal();
    }
    public synchronized void judged(UUID responseId) {
        if (ended || stopping || !responseId.equals(pendingResponse)) return;
        var p = repository.response(responseId).orElseThrow();
        if (p.evaluationState() != EvaluationState.FINALIZED || p.evaluationGeneration() != currentGoal().generation()) return;
        pendingResponse = null;
        if (p.responseType() == ResponseType.FIRST) {
            if (p.label() == Label.partial || p.label() == Label.none) help(); else prepareNew();
        } else if (p.responseType() == ResponseType.AFTER_CUE) prepareNew(); else completeGoal();
    }
    private void help() { transition(Stage.HELP_OFFER); send(OutputKind.HELP_QUESTION); }
    public synchronized void consent(Consent value, long generation) {
        if (ended || stopping || awaitingAgreement || currentGoal().currentStage() != Stage.HELP_OFFER
                || currentGoal().generation() != generation || !questionDone) return;
        cue = new CueDelivery(UUID.randomUUID(), attempt.id(), attempt.scenarioId(), value,
                value == Consent.ACCEPTED ? DeliveryState.REQUESTED : DeliveryState.NOT_GIVEN, null, null, null);
        repository.saveCue(cue);
        if (value != Consent.ACCEPTED) { prepareNew(); return; }
        transition(Stage.CUE); cueStarted = false; send(OutputKind.CUE);
        long current = currentGoal().generation();
        schedule(timing.cueStart(), () -> {
            if (!cueStarted) playback(pendingOutput.correlationId(), pendingOutput.scenarioId(), current, Playback.FAILED, null, false);
        });
    }
    private void prepareNew() {
        UUID previous = attempt.scenarioId(); transition(currentGoal().currentStage());
        prepare(AttemptType.NEW_SCENARIO, previous);
    }
    private void prepare(AttemptType type, UUID previous) {
        var goal = currentGoal();
        attempt = new PracticeAttempt(UUID.randomUUID(), goal.id(), type, AttemptState.PREPARING, null, previous, null, null);
        repository.saveAttempt(attempt);
        var expected = owner();
        schedule(timing.scenarioPreparation(), () -> preparationFailed(PreparationFailure.TIMEOUT));
        try {
            scenarios.prepare(goal.sessionId(), goal, previous).whenComplete((value, failure) -> events.execute(() -> {
                synchronized (this) {
                    try {
                    if (!owns(expected)) return;
                    if (failure != null || value == null) { preparationFailed(PreparationFailure.GENERATION_FAILED); return; }
                    if (value.id().equals(previous)) { preparationFailed(PreparationFailure.VALIDATION_FAILED); return; }
                    issue(value, type, previous);
                    } catch (RuntimeException storageOrOutputFailure) {
                        awaitingAgreement = true; opportunityOpen = false;
                        failures.accept(storageOrOutputFailure);
                    }
                }
            }));
        } catch (RuntimeException failure) { preparationFailed(PreparationFailure.UNAVAILABLE); }
    }
    private void preparationFailed(PreparationFailure reason) {
        if (attempt.state() != AttemptState.PREPARING) return;
        attempt = new PracticeAttempt(attempt.id(), attempt.sessionGoalId(), attempt.attemptType(), AttemptState.PREPARATION_FAILED,
                null, attempt.previousScenarioId(), null, reason); repository.saveAttempt(attempt);
        if (attempt.attemptType() == AttemptType.NEW_SCENARIO) {
            if (skipFailedScenario) failCurrentGoal(reason == PreparationFailure.TIMEOUT ? "TIME_OVER" : "SCENARIO_FAILED");
            else awaitingAgreement = true;
            return;
        }
        var skipped = currentGoal().progress(GoalStatus.SKIPPED, currentGoal().currentStage(), reason.name());
        repository.saveGoal(skipped); goals.set(index, skipped); nextGoal();
    }
    private void completeGoal() {
        var done = currentGoal().progress(GoalStatus.DONE, currentGoal().currentStage(), null);
        repository.saveGoal(done); goals.set(index, done); nextGoal();
    }
    private void nextGoal() {
        if (index + 1 == goals.size()) { ended = true; output.lastGoal(currentGoal().sessionId()); return; }
        index++; cue = null; transition(Stage.SCENARIO_INTRO); prepare(AttemptType.ORIGINAL, null);
    }
    public synchronized void failCurrentGoal(String reason) {
        if (ended || stopping) return;
        PracticeChecks.text(reason, "목표 생략 사유");
        if (pendingResponse != null && repository.response(pendingResponse)
                .map(p -> p.evaluationState() != EvaluationState.FINALIZED).orElse(false))
            throw new IllegalStateException("판정 중인 목표를 생략할 수 없다");
        if (pendingOutput != null) output.cancel(pendingOutput.correlationId());
        pendingOutput = null; pendingResponse = null; opportunityOpen = false; awaitingAgreement = false;
        var skipped = currentGoal().progress(GoalStatus.SKIPPED, currentGoal().currentStage(), reason);
        repository.saveGoal(skipped); goals.set(index, skipped); nextGoal();
    }
    /** 진행만 멈춘다. 이미 수락한 판정의 저장 시한과 소유권은 유지한다. */
    public synchronized void stopProgress() {
        if (stopping) return;
        stopping = true; opportunityOpen = false;
        if (pendingOutput != null) output.cancel(pendingOutput.correlationId());
        pendingOutput = null;
    }
    public synchronized Duration settlementDelay(Instant now) {
        Objects.requireNonNull(now);
        Instant deadline = repository.snapshot(currentGoal().sessionId()).responses().stream()
                .filter(p -> p.evaluationState() == EvaluationState.PENDING)
                .map(p -> p.work().deadlineAt()).max(Instant::compareTo).orElse(now);
        return deadline.isAfter(now) ? Duration.between(now, deadline) : Duration.ZERO;
    }
    public synchronized void settle() {
        stopProgress();
        repository.settle(currentGoal().sessionId());
        ended = true;
    }
    public synchronized void terminate() {
        if (ended) return;
        ended = true; opportunityOpen = false;
        if (pendingOutput != null) output.cancel(pendingOutput.correlationId());
        repository.terminate(currentGoal().sessionId());
    }
}
