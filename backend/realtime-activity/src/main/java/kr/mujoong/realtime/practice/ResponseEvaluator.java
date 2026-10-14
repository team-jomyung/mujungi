package kr.mujoong.realtime.practice;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.TimerDriver;
import static kr.mujoong.realtime.practice.PracticeTypes.*;

/** 저장된 반응을 판정한다. 타이머는 독립 최종화 큐에 전달만 하며 외부 호출 동안 DB 잠금을 잡지 않는다. */
public final class ResponseEvaluator {
    public record Completion(UUID responseId, boolean committedWithinBudget) { }
    private final PracticeRepository repository;
    private final EvaluationBudget budget;
    private final EvaluationProvider provider;
    private final MujoongClock clock;
    private final TimerDriver timers;
    private final Executor calls;
    private final Executor finalizations;
    private final Consumer<Completion> completion;
    private final Consumer<RuntimeException> failures;

    public ResponseEvaluator(PracticeRepository repository, EvaluationBudget budget, EvaluationProvider provider,
            MujoongClock clock, TimerDriver timers, Executor calls, Executor finalizations,
            Consumer<Completion> completion, Consumer<RuntimeException> failures) {
        this.repository = Objects.requireNonNull(repository); this.budget = Objects.requireNonNull(budget);
        this.provider = Objects.requireNonNull(provider); Objects.requireNonNull(clock);
        this.clock = clock instanceof EvaluationClock ? clock : new EvaluationClock(clock);
        this.timers = Objects.requireNonNull(timers); this.calls = Objects.requireNonNull(calls);
        this.finalizations = Objects.requireNonNull(finalizations); this.completion = Objects.requireNonNull(completion);
        this.failures = Objects.requireNonNull(failures);
    }
    /** 수락 저장은 호출자가 먼저 완료한다. 재시작·중복 호출에서도 원래 시한과 시도 횟수를 사용한다. */
    public void evaluate(UUID responseId, String criteria, String responseText) {
        PracticeChecks.text(criteria, "판정 기준"); PracticeChecks.text(responseText, "반응");
        repository.response(responseId).filter(p -> p.evaluationState() == EvaluationState.PENDING).ifPresent(p -> {
            timers.scheduleAt(p.work().cutoff(), () -> dispatch(() -> timeout(p.id(), p.evaluationGeneration())));
            calls.execute(() -> guarded(() -> attempt(p.id(), p.evaluationGeneration(), criteria, responseText)));
        });
    }
    /** 기동 중 놓친 판정은 원래 호출 시한이 지난 뒤에만 복구한다. 지연 복구를 성능 통과로 세지 않는다. */
    public static Optional<Completion> recoverExpired(PracticeRepository repository, UUID responseId, MujoongClock clock) {
        var pending = repository.response(responseId)
                .filter(p -> p.evaluationState() == EvaluationState.PENDING);
        if (pending.isEmpty()) return Optional.empty();
        var response = pending.get();
        Instant now = clock.now();
        if (now.isBefore(response.work().cutoff())) return Optional.empty();
        var result = new Evaluation(UUID.randomUUID(), responseId, Label.uncertain, "원래 판정 시한 만료 후 복구",
                null, true, response.work().attemptsStarted(), null, null);
        Instant deadline = response.work().deadlineAt();
        boolean saved = now.isBefore(deadline)
                ? repository.finalizeWithinBudget(responseId, response.evaluationGeneration(), null, result, deadline, clock)
                : repository.finalizeResult(responseId, response.evaluationGeneration(), null, result, now);
        return saved ? Optional.of(new Completion(responseId, clock.now().isBefore(deadline))) : Optional.empty();
    }
    private void guarded(Runnable action) {
        try { action.run(); } catch (RuntimeException failure) { failures.accept(failure); }
    }
    private void dispatch(Runnable action) { finalizations.execute(() -> guarded(action)); }
    private void attempt(UUID id, long generation, String criteria, String text) {
        var found = repository.response(id);
        if (found.isEmpty() || found.get().evaluationState() != EvaluationState.PENDING) return;
        var before = found.get();
        if (!clock.now().isBefore(before.work().cutoff()) || before.work().attemptsStarted() >= 2) {
            dispatch(() -> timeout(id, generation)); return;
        }
        var claimed = repository.claimAttempt(id, generation, clock.now(), budget.call());
        if (claimed.isEmpty()) return;
        var p = claimed.get(); var token = p.work().activeAttemptToken();
        timers.scheduleAt(p.work().activeAttemptDeadlineAt(),
                () -> dispatch(() -> failedAttempt(p, token, criteria, text)));
        // 선점 DB 지연도 전체 예산에 포함한다. 이미 만료된 호출은 시작하지 않는다.
        if (!clock.now().isBefore(p.work().activeAttemptDeadlineAt())) {
            dispatch(() -> failedAttempt(p, token, criteria, text)); return;
        }
        try {
            provider.evaluate(new EvaluationProvider.Request(id, token, p.scenarioId(), p.utteranceSeq(), criteria, text, p.work().activeAttemptDeadlineAt()))
                    .whenComplete((result, failure) -> dispatch(() -> {
                        if (failure != null) { failedAttempt(p, token, criteria, text); return; }
                        Evaluation value;
                        try {
                            value = new Evaluation(UUID.randomUUID(), id, result.label(), result.reason(),
                                    result.evidenceUtteranceSeq(), result.reviewNeeded(), p.work().attemptsStarted(),
                                    result.modelVersion(), result.promptVersion());
                            value.requireResponse(p);
                        } catch (RuntimeException invalid) {
                            failedAttempt(p, token, criteria, text); return;
                        }
                        if (!clock.now().isBefore(p.work().activeAttemptDeadlineAt())) {
                            failedAttempt(p, token, criteria, text); return;
                        }
                        commit(p, token, value);
                    }));
        } catch (RuntimeException failure) { dispatch(() -> failedAttempt(p, token, criteria, text)); }
    }
    private void failedAttempt(PracticeResponse p, UUID token, String criteria, String text) {
        if (!repository.releaseAttempt(p.id(), token)) return;
        Instant next = clock.now().plus(budget.retryDelay());
        if (p.work().attemptsStarted() >= 2 || !next.isBefore(p.work().cutoff())) {
            timeout(p.id(), p.evaluationGeneration()); return;
        }
        timers.scheduleAt(next, () -> calls.execute(() -> guarded(() -> attempt(p.id(), p.evaluationGeneration(), criteria, text))));
    }
    private void timeout(UUID id, long generation) {
        repository.response(id).filter(p -> p.evaluationState() == EvaluationState.PENDING
                && p.evaluationGeneration() == generation).ifPresent(p -> {
            // 최초 전체 마감 또는 재시도 소진 경로에서만 내부 uncertain을 확정한다.
            if (clock.now().isBefore(p.work().cutoff()) && p.work().activeAttemptToken() != null) return;
            commit(p, null, new Evaluation(UUID.randomUUID(), id, Label.uncertain, "판정 시간 또는 시도 예산 소진",
                    null, true, p.work().attemptsStarted(), null, null));
        });
    }
    private void commit(PracticeResponse p, UUID token, Evaluation evaluation) {
        if (repository.finalizeWithinBudget(p.id(), p.evaluationGeneration(), token, evaluation, p.work().deadlineAt(), clock)) {
            // 반환은 트랜잭션 커밋 뒤다. 저장 지연을 숨기거나 앞선 시각으로 성능을 주장하지 않는다.
            completion.accept(new Completion(p.id(), !clock.now().isAfter(p.work().deadlineAt())));
        }
    }
}
