package kr.mujoong.realtime.practicelegacy;

import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;

/** 실제 어댑터의 순서/재생 경합 시험용 대역. DB 잠금·제약의 검증을 대신하지 않는다. */
final class MemoryPracticeRepository implements PracticeRepository {
    final Map<UUID, SessionGoal> goals = new LinkedHashMap<>();
    final Map<UUID, PracticeAttempt> attempts = new LinkedHashMap<>();
    final Map<UUID, PracticeResponse> responses = new LinkedHashMap<>();
    final Map<UUID, CueDelivery> cues = new LinkedHashMap<>();
    final Map<UUID, Evaluation> evaluations = new LinkedHashMap<>();

    @Override public synchronized void initialize(List<SessionGoal> values) { values.forEach(v -> goals.putIfAbsent(v.id(), v)); }
    @Override public synchronized void saveGoal(SessionGoal value) { goals.put(value.id(), value); }
    @Override public synchronized void saveAttempt(PracticeAttempt value) { attempts.put(value.id(), value); }
    @Override public synchronized void saveCue(CueDelivery value) { cues.put(value.id(), value); }
    @Override public synchronized PracticeResponse accept(PracticeResponse value) {
        value.requireAttempt(attempts.get(value.attemptId()));
        var prior = responses.values().stream().filter(r -> r.attemptId().equals(value.attemptId())
                && r.responseType() == value.responseType()).findFirst();
        if (prior.isPresent()) return prior.get();
        responses.put(value.id(), value); return value;
    }
    @Override public synchronized Optional<PracticeResponse> response(UUID id) { return Optional.ofNullable(responses.get(id)); }
    private Optional<PracticeResponse> pending(UUID id, long generation) {
        return response(id).filter(p -> p.evaluationState() == EvaluationState.PENDING && p.evaluationGeneration() == generation);
    }
    private void update(PracticeResponse p, EvaluationState state, long generation, EvaluationWork work, Label label, Instant closedAt) {
        responses.put(p.id(), new PracticeResponse(p.id(), p.attemptId(), p.scenarioId(), p.responseType(), p.utteranceSeq(),
                state, generation, p.acceptedAt(), work, label, p.notPerformedReason(), p.opportunityOpenedAt(), closedAt));
    }
    @Override public synchronized Optional<PracticeResponse> claimAttempt(UUID id, long generation, Instant now, Duration callBudget) {
        var found = pending(id, generation); if (found.isEmpty()) return Optional.empty();
        var p = found.get(); var w = p.work();
        if (w.activeAttemptToken() != null || w.finalizationClaimed() || w.attemptsStarted() >= 2 || !now.isBefore(w.cutoff())) return Optional.empty();
        Instant deadline = now.plus(callBudget).isBefore(w.cutoff()) ? now.plus(callBudget) : w.cutoff();
        update(p, p.evaluationState(), generation, new EvaluationWork(w.budgetVersion(), w.totalBudget(), w.deadlineAt(),
                w.finalizationReserve(), w.attemptsStarted() + 1, UUID.randomUUID(), deadline, false), null, null);
        return response(id);
    }
    @Override public synchronized boolean releaseAttempt(UUID id, UUID token) {
        var p = responses.get(id); if (p == null || p.evaluationState() != EvaluationState.PENDING || !Objects.equals(token, p.work().activeAttemptToken())) return false;
        var w = p.work(); update(p, p.evaluationState(), p.evaluationGeneration(), new EvaluationWork(w.budgetVersion(),
                w.totalBudget(), w.deadlineAt(), w.finalizationReserve(), w.attemptsStarted(), null, null, false), null, null);
        return true;
    }
    @Override public synchronized boolean finalizeResult(UUID id, long generation, UUID token, Evaluation result, Instant now) {
        var found = pending(id, generation); if (found.isEmpty()) return false;
        var p = found.get(); var w = p.work(); result.requireResponse(p);
        if (token != null && (!token.equals(w.activeAttemptToken()) || !now.isBefore(w.activeAttemptDeadlineAt()))) return false;
        if (token == null && result.label() != Label.uncertain || result.attempts() != w.attemptsStarted()) return false;
        evaluations.put(result.id(), result);
        update(p, EvaluationState.FINALIZED, generation, new EvaluationWork(w.budgetVersion(), w.totalBudget(),
                w.deadlineAt(), w.finalizationReserve(), w.attemptsStarted(), null, null, true), result.label(), now);
        return true;
    }
    @Override public synchronized boolean finalizeWithinBudget(UUID id, long generation, UUID token, Evaluation result, Instant deadline, MujoongClock clock) {
        if (!clock.now().isBefore(deadline)) throw new IllegalStateException("시험 판정 시한 만료");
        return finalizeResult(id, generation, token, result, clock.now());
    }
    @Override public synchronized void terminate(UUID session) {
        for (var p : snapshot(session).responses()) if (p.evaluationState() == EvaluationState.PENDING) {
            var w = p.work(); update(p, EvaluationState.TERMINATED_PENDING, p.evaluationGeneration() + 1,
                    new EvaluationWork(w.budgetVersion(), w.totalBudget(), w.deadlineAt(), w.finalizationReserve(),
                            w.attemptsStarted(), null, null, false), null, null);
        }
    }
    @Override public synchronized void settle(UUID session) {
        var saved = snapshot(session);
        if (saved.responses().stream().anyMatch(r -> r.evaluationState() != EvaluationState.FINALIZED)) throw new IllegalStateException("시험 판정 미완료");
        for (var goal : saved.goals()) if (goal.status() == GoalStatus.PENDING || goal.status() == GoalStatus.RUNNING) {
            boolean exposed = saved.attempts().stream().anyMatch(a -> a.sessionGoalId().equals(goal.id()) && a.exposedAt() != null);
            saveGoal(goal.progress(exposed ? GoalStatus.SKIPPED : GoalStatus.NOT_REACHED, goal.currentStage(), exposed ? "ACTIVITY_ENDED" : null));
        }
    }
    @Override public synchronized void delete(UUID guardian) {
        for (var goal : List.copyOf(goals.values())) if (goal.guardianId().equals(guardian)) {
            var saved = snapshot(goal.sessionId());
            saved.evaluations().forEach(v -> evaluations.remove(v.id())); saved.responses().forEach(v -> responses.remove(v.id()));
            saved.cues().forEach(v -> cues.remove(v.id())); saved.attempts().forEach(v -> attempts.remove(v.id()));
            saved.goals().forEach(v -> goals.remove(v.id()));
        }
    }
    @Override public synchronized Snapshot snapshot(UUID session) {
        var gs = goals.values().stream().filter(g -> g.sessionId().equals(session)).sorted(Comparator.comparingInt(SessionGoal::order)).toList();
        var as = attempts.values().stream().filter(a -> gs.stream().anyMatch(g -> g.id().equals(a.sessionGoalId()))).toList();
        var rs = responses.values().stream().filter(r -> as.stream().anyMatch(a -> a.id().equals(r.attemptId()))).toList();
        return new Snapshot(gs, as, rs, cues.values().stream().filter(c -> as.stream().anyMatch(a -> a.id().equals(c.attemptId()))).toList(),
                evaluations.values().stream().filter(e -> rs.stream().anyMatch(r -> r.id().equals(e.responseId()))).toList());
    }
}
