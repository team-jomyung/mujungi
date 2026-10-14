package kr.mujoong.realtime.practice;

import static kr.mujoong.realtime.practice.PracticeTypes.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import kr.mujoong.contract.json.JsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 주입받은 업무 트랜잭션 안에서 외부 호출 없이 원자 저장한다. */
public final class JdbcPracticeRepository implements PracticeRepository {
    private static final String G = "realtime.realtime_session_goal";
    private static final String A = "realtime.realtime_practice_attempt";
    private static final String R = "realtime.realtime_practice_response";
    private static final String C = "realtime.realtime_practice_cue";
    private static final String E = "realtime.realtime_practice_evaluation";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json = JsonCodec.shared();
    public JdbcPracticeRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = Objects.requireNonNull(jdbc); this.tx = Objects.requireNonNull(tx);
    }
    private <T> T atomic(Supplier<T> work) { return tx.execute(status -> work.get()); }
    private <T> List<T> read(String sql, Class<T> type, Object... args) {
        return jdbc.query(sql, (rs, n) -> json.read(rs.getString(1), type), args);
    }
    private boolean lock(UUID session) {
        var rows = jdbc.query("select terminated from " + G + " where session_id=? order by goal_order for update",
                (rs, n) -> rs.getBoolean(1), session);
        return !rows.isEmpty() && rows.stream().noneMatch(Boolean::booleanValue);
    }
    private SessionGoal goal(UUID id) {
        return read("select data from " + G + " where id=?", SessionGoal.class, id).stream().findFirst().orElseThrow();
    }
    private PracticeAttempt attempt(UUID id) {
        return read("select data from " + A + " where id=?", PracticeAttempt.class, id).stream().findFirst().orElseThrow();
    }
    private UUID session(PracticeResponse response) { return goal(attempt(response.attemptId()).sessionGoalId()).sessionId(); }
    @Override public void initialize(List<SessionGoal> goals) {
        PracticeChecks.require(!goals.isEmpty(), "목표가 비어 있다");
        UUID session = goals.getFirst().sessionId(); UUID guardian = goals.getFirst().guardianId();
        atomic(() -> {
            // 기존 세션 행은 생성하지 않는다. 동일 세션 초기화의 유일 직렬화 경계다.
            jdbc.queryForObject("select id from realtime.realtime_activity_session where id=? for update", UUID.class, session);
            for (int i = 0; i < goals.size(); i++) {
                SessionGoal g = goals.get(i);
                PracticeChecks.require(g.sessionId().equals(session) && g.guardianId().equals(guardian)
                        && g.order() == i + 1, "목표 묶음 소유/순서 오류");
                PracticeChecks.require(g.status() == GoalStatus.PENDING && g.generation() == 0
                        && g.currentStage() == null && g.skipReason() == null, "시작 목표 초기 상태 오류");
                jdbc.update("insert into " + G + "(id,session_id,guardian_id,goal_selection_id,goal_order,data)"
                        + " values (?,?,?,?,?,?::jsonb) on conflict do nothing", g.id(), session, guardian,
                        g.goalSelectionId(), g.order(), json.write(g));
            }
            var stored = read("select data from " + G + " where session_id=? order by goal_order", SessionGoal.class, session);
            var originals = stored.stream().map(g -> new SessionGoal(g.id(), g.sessionId(), g.guardianId(), g.goalSelectionId(),
                    g.order(), g.displayName(), g.behaviorKey(), g.behaviorVersion(), g.judgmentCriteriaSnapshot(),
                    GoalStatus.PENDING, null, 0, null)).toList();
            PracticeChecks.require(originals.equals(goals), "고정 목표 원본 충돌"); return null;
        });
    }
    @Override public void saveGoal(SessionGoal value) {
        atomic(() -> {
            if (!lock(value.sessionId())) return null;
            SessionGoal old = goal(value.id());
            PracticeChecks.require(old.progress(value.status(), value.currentStage(), value.skipReason()).equals(value),
                    "고정 목표 변경 또는 진행 세대 오류");
            jdbc.update("update " + G + " set data=?::jsonb where id=?", json.write(value), value.id()); return null;
        });
    }
    @Override public void saveAttempt(PracticeAttempt value) {
        atomic(() -> {
            if (!lock(goal(value.sessionGoalId()).sessionId())) return null;
            if (value.attemptType() == AttemptType.NEW_SCENARIO) {
                var originals = read("select data from " + A + " where session_goal_id=? and attempt_type='ORIGINAL'",
                        PracticeAttempt.class, value.sessionGoalId());
                PracticeChecks.require(originals.size() == 1 && value.previousScenarioId().equals(originals.getFirst().scenarioId()),
                        "다른 원 상황 참조");
            }
            var old = read("select data from " + A + " where id=?", PracticeAttempt.class, value.id());
            if (!old.isEmpty()) {
                var before = old.getFirst();
                PracticeChecks.require(before.sessionGoalId().equals(value.sessionGoalId()) && before.attemptType() == value.attemptType()
                        && (before.scenarioId() == null || before.scenarioId().equals(value.scenarioId()))
                        && (before.exposedAt() == null || before.exposedAt().equals(value.exposedAt())), "기존 장면/노출 변경 금지");
            }
            jdbc.update("insert into " + A + "(id,session_goal_id,attempt_type,scenario_id,data) values (?,?,?,?,?::jsonb)"
                    + " on conflict(id) do update set scenario_id=excluded.scenario_id,data=excluded.data",
                    value.id(), value.sessionGoalId(), value.attemptType().name(), value.scenarioId(), json.write(value)); return null;
        });
    }
    @Override public void saveCue(CueDelivery value) {
        atomic(() -> {
            var a = attempt(value.attemptId()); if (!lock(goal(a.sessionGoalId()).sessionId())) return null;
            value.requireAttempt(a);
            var old = read("select data from " + C + " where attempt_id=?", CueDelivery.class, a.id());
            if (!old.isEmpty()) {
                var before = old.getFirst();
                PracticeChecks.require(before.id().equals(value.id()) && before.consentResponse() == value.consentResponse()
                        && (before.givenAt() == null || (before.givenAt().equals(value.givenAt())
                        && before.deliveredText().equals(value.deliveredText()))), "기존 도움 변경 금지");
            }
            jdbc.update("insert into " + C + "(id,attempt_id,scenario_id,data) values (?,?,?,?::jsonb)"
                    + " on conflict(id) do update set data=excluded.data", value.id(), a.id(), value.scenarioId(), json.write(value)); return null;
        });
    }
    @Override public PracticeResponse accept(PracticeResponse value) {
        return atomic(() -> {
            if (!lock(session(value))) throw new IllegalStateException("종료된 연습 반응 수락 금지");
            value.requireAttempt(attempt(value.attemptId()));
            jdbc.update("insert into " + R + "(id,attempt_id,scenario_id,response_type,data) values (?,?,?,?,?::jsonb)"
                    + " on conflict(attempt_id,response_type) do nothing", value.id(), value.attemptId(), value.scenarioId(),
                    value.responseType().name(), json.write(value));
            return read("select data from " + R + " where attempt_id=? and response_type=?", PracticeResponse.class,
                    value.attemptId(), value.responseType().name()).getFirst();
        });
    }
    @Override public Optional<PracticeResponse> response(UUID id) {
        return read("select data from " + R + " where id=?", PracticeResponse.class, id).stream().findFirst();
    }
    private PracticeResponse update(PracticeResponse p, EvaluationState state, long generation,
            EvaluationWork work, Label label, Instant closedAt) {
        var next = new PracticeResponse(p.id(), p.attemptId(), p.scenarioId(), p.responseType(), p.utteranceSeq(),
                state, generation, p.acceptedAt(), work, label, p.notPerformedReason(), p.opportunityOpenedAt(), closedAt);
        jdbc.update("update " + R + " set data=?::jsonb where id=?", json.write(next), p.id()); return next;
    }
    private Optional<PracticeResponse> pending(UUID id, long generation) {
        var before = response(id); if (before.isEmpty() || !lock(session(before.get()))) return Optional.empty();
        return response(id).filter(p -> p.evaluationState() == EvaluationState.PENDING && p.evaluationGeneration() == generation);
    }
    @Override public Optional<PracticeResponse> claimAttempt(UUID id, long generation, Instant now, Duration callBudget) {
        return atomic(() -> pending(id, generation).filter(p -> !p.work().finalizationClaimed()
                && p.work().attemptsStarted() < 2 && now.isBefore(p.work().cutoff())
                && (p.work().activeAttemptToken() == null || !now.isBefore(p.work().activeAttemptDeadlineAt())))
                .map(p -> {
                    var w = p.work(); Instant limit = now.plus(callBudget);
                    if (limit.isAfter(w.cutoff())) limit = w.cutoff();
                    var next = new EvaluationWork(w.budgetVersion(), w.totalBudget(), w.deadlineAt(), w.finalizationReserve(),
                            w.attemptsStarted() + 1, UUID.randomUUID(), limit, false);
                    return update(p, p.evaluationState(), generation, next, null, null);
                }));
    }
    @Override public boolean releaseAttempt(UUID id, UUID token) {
        return atomic(() -> {
            var found = response(id); if (found.isEmpty()) return false;
            var current = pending(id, found.get().evaluationGeneration()); if (current.isEmpty()) return false;
            var p = current.get(); var w = p.work(); if (!Objects.equals(token, w.activeAttemptToken())) return false;
            update(p, p.evaluationState(), p.evaluationGeneration(), new EvaluationWork(w.budgetVersion(), w.totalBudget(),
                    w.deadlineAt(), w.finalizationReserve(), w.attemptsStarted(), null, null, false), null, null); return true;
        });
    }
    @Override public boolean finalizeResult(UUID id, long generation, UUID token, Evaluation result, Instant now) {
        return atomic(() -> {
            var found = pending(id, generation); if (found.isEmpty()) return false;
            var p = found.get(); var w = p.work(); result.requireResponse(p);
            if (token != null && (!token.equals(w.activeAttemptToken()) || !now.isBefore(w.activeAttemptDeadlineAt()))) return false;
            if (token == null && result.label() != Label.uncertain) return false;
            if (result.attempts() != w.attemptsStarted()) return false;
            var claimed = new EvaluationWork(w.budgetVersion(), w.totalBudget(), w.deadlineAt(), w.finalizationReserve(),
                    w.attemptsStarted(), null, null, true);
            jdbc.update("insert into " + E + "(id,response_id,data) values (?,?,?::jsonb)", result.id(), id, json.write(result));
            update(p, EvaluationState.FINALIZED, generation, claimed, result.label(), now); return true;
        });
    }
    @Override public boolean finalizeWithinBudget(UUID id, long generation, UUID token, Evaluation result,
            Instant deadline, kr.mujoong.contract.time.MujoongClock clock) {
        long remainingMillis = Duration.between(clock.now(), deadline).toMillis();
        if (remainingMillis <= 0) throw new IllegalStateException("판정 저장 시간 예산 소진");
        var bounded = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        bounded.setTimeout(Math.toIntExact(Math.max(1, (remainingMillis + 999) / 1000)));
        return Boolean.TRUE.equals(bounded.execute(status -> {
            // 잠금과 각 SQL에도 남은 전체 예산을 전파한다. 커밋 응답 지연은 호출자가 별도로 관측한다.
            jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, remainingMillis + "ms");
            jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, remainingMillis + "ms");
            boolean saved = finalizeResult(id, generation, token, result, clock.now());
            if (clock.now().isAfter(deadline)) throw new IllegalStateException("판정 저장 완료 전 시간 예산 초과");
            return saved;
        }));
    }
    @Override public void terminate(UUID session) {
        atomic(() -> {
            lock(session);
            for (var p : snapshot(session).responses()) if (p.evaluationState() == EvaluationState.PENDING) {
                var w = p.work();
                update(p, EvaluationState.TERMINATED_PENDING, p.evaluationGeneration() + 1,
                        new EvaluationWork(w.budgetVersion(), w.totalBudget(), w.deadlineAt(), w.finalizationReserve(),
                                w.attemptsStarted(), null, null, false), null, null);
            }
            var ending = snapshot(session);
            for (var g : ending.goals()) if (g.status() == GoalStatus.PENDING
                    || (g.status() == GoalStatus.RUNNING && ending.attempts().stream()
                        .noneMatch(a -> a.sessionGoalId().equals(g.id()) && a.exposedAt() != null))) {
                var remaining = g.progress(GoalStatus.NOT_REACHED, g.currentStage(), null);
                jdbc.update("update " + G + " set data=?::jsonb where id=?", json.write(remaining), g.id());
            }
            jdbc.update("update " + G + " set terminated=true where session_id=?", session); return null;
        });
    }
    @Override public void settle(UUID session) {
        atomic(() -> {
            lock(session);
            var ending = snapshot(session);
            if (ending.goals().isEmpty()) throw new IllegalStateException("정산할 연습 목표 원본이 없다");
            if (ending.responses().stream().anyMatch(p -> p.evaluationState() != EvaluationState.FINALIZED))
                throw new IllegalStateException("확정되지 않은 연습 반응이 남았다");
            for (var goal : ending.goals()) {
                if (goal.status() != GoalStatus.PENDING && goal.status() != GoalStatus.RUNNING) continue;
                boolean exposed = ending.attempts().stream()
                        .anyMatch(a -> a.sessionGoalId().equals(goal.id()) && a.exposedAt() != null);
                var finalGoal = goal.progress(exposed ? GoalStatus.SKIPPED : GoalStatus.NOT_REACHED,
                        goal.currentStage(), exposed ? "ACTIVITY_ENDED" : null);
                jdbc.update("update " + G + " set data=?::jsonb where id=?", json.write(finalGoal), goal.id());
            }
            jdbc.update("update " + G + " set terminated=true where session_id=?", session);
            return null;
        });
    }
    @Override public void delete(UUID guardian) {
        atomic(() -> {
            jdbc.query("select id from " + G + " where guardian_id=? order by session_id,goal_order for update", (rs,n) -> rs.getObject(1), guardian);
            String attempts = "select a.id from " + A + " a join " + G + " g on g.id=a.session_goal_id where g.guardian_id=?";
            jdbc.update("delete from " + E + " where response_id in(select id from " + R + " where attempt_id in(" + attempts + "))", guardian);
            jdbc.update("delete from " + R + " where attempt_id in(" + attempts + ")", guardian);
            jdbc.update("delete from " + C + " where attempt_id in(" + attempts + ")", guardian);
            jdbc.update("delete from " + A + " where session_goal_id in(select id from " + G + " where guardian_id=?)", guardian);
            jdbc.update("delete from " + G + " where guardian_id=?", guardian); return null;
        });
    }
    @Override public Snapshot snapshot(UUID session) {
        return atomic(() -> {
            lock(session);
            String attempts = "select a.id from " + A + " a join " + G + " g on g.id=a.session_goal_id where g.session_id=?";
            return new Snapshot(read("select data from " + G + " where session_id=? order by goal_order", SessionGoal.class, session),
                    read("select data from " + A + " where id in(" + attempts + ")", PracticeAttempt.class, session),
                    read("select data from " + R + " where attempt_id in(" + attempts + ")", PracticeResponse.class, session),
                    read("select data from " + C + " where attempt_id in(" + attempts + ")", CueDelivery.class, session),
                    read("select data from " + E + " where response_id in(select id from " + R + " where attempt_id in("
                            + attempts + "))", Evaluation.class, session));
        });
    }
}
