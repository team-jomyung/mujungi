package kr.mujoong.realtime.practice;

import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import static kr.mujoong.realtime.practice.PracticeTypes.*;

/** 최종 라벨을 요구하는 기존 record.practice의 독립 투영. 보류 결과는 외부 계약 상태가 아니다. */
public final class PracticeRecordProjector {
    public sealed interface Projection permits Ready, Deferred { }
    public record Ready(PracticeRecord practice) implements Projection { }
    public record Deferred(String agreement) implements Projection { }
    public record PracticeRecord(List<GoalRecord> goals) { public PracticeRecord { goals = List.copyOf(goals); } }
    public record GoalRecord(UUID goalSelectionId, String displayName, String behaviorKey, int behaviorVersion,
            int order, GoalStatus status, String skipReason,
            List<ScenarioRecord> scenarios, List<ResponseRecord> responses, List<CueRecord> cues) {
        public GoalRecord { scenarios = List.copyOf(scenarios); responses = List.copyOf(responses); cues = List.copyOf(cues); }
    }
    public record ScenarioRecord(UUID scenarioId, AttemptType exposureType, Instant exposedAt) { }
    public record ResponseRecord(UUID scenarioId, ResponseType responseType, Integer utteranceSeq, String notPerformedReason, Label label) { }
    public record CueRecord(UUID scenarioId, Consent consentResponse, String deliveredText, Boolean containsAnswerSentence, Instant givenAt) {
        @Override public String toString() { return "CueRecord[scenarioId=" + scenarioId + "]"; }
    }
    public Projection project(PracticeRepository.Snapshot snapshot) {
        return project(snapshot, false);
    }
    public Projection projectSettled(PracticeRepository.Snapshot snapshot) {
        return project(snapshot, true);
    }
    private Projection project(PracticeRepository.Snapshot snapshot, boolean settled) {
        if (snapshot.responses().stream().anyMatch(p -> p.evaluationState() != EvaluationState.FINALIZED)) return new Deferred("I2");
        if (!settled && snapshot.attempts().stream().anyMatch(a -> a.attemptType() == AttemptType.NEW_SCENARIO && a.state() == AttemptState.PREPARATION_FAILED))
            return new Deferred("I4");
        if (snapshot.goals().stream().anyMatch(g -> g.status() == GoalStatus.RUNNING || g.status() == GoalStatus.PENDING)
                || (!settled && snapshot.attempts().stream().anyMatch(a -> a.state() == AttemptState.PREPARING))) return new Deferred("I2/I4");
        var goals = snapshot.goals().stream().collect(Collectors.toMap(SessionGoal::id, Function.identity()));
        var attempts = snapshot.attempts().stream().collect(Collectors.toMap(PracticeAttempt::id, Function.identity()));
        var evaluations = snapshot.evaluations().stream().collect(Collectors.toMap(Evaluation::responseId, Function.identity()));
        for (var a : attempts.values()) PracticeChecks.require(goals.containsKey(a.sessionGoalId()), "기록 목표 귀속 오류");
        for (var p : snapshot.responses()) {
            var a = attempts.get(p.attemptId()); PracticeChecks.require(a != null && a.exposedAt() != null, "기록 장면 노출 증거 필요");
            p.requireAttempt(a);
            if (p.label() == Label.NOT_PERFORMED) PracticeChecks.require(!evaluations.containsKey(p.id()), "무반응 판정 행 금지");
            else { PracticeChecks.require(evaluations.containsKey(p.id()), "최종 판정 행 누락"); evaluations.get(p.id()).requireResponse(p); }
        }
        for (var e : snapshot.evaluations()) PracticeChecks.require(snapshot.responses().stream().anyMatch(p -> p.id().equals(e.responseId())), "다른 반응의 판정");
        for (var c : snapshot.cues()) {
            var a = attempts.get(c.attemptId()); PracticeChecks.require(a != null && a.exposedAt() != null, "도움 장면 노출 증거 필요"); c.requireAttempt(a);
        }
        return new Ready(new PracticeRecord(snapshot.goals().stream().sorted(Comparator.comparingInt(SessionGoal::order)).map(g -> new GoalRecord(g.goalSelectionId(), g.displayName(),
                g.behaviorKey(), g.behaviorVersion(), g.order(), g.status(), g.skipReason(), snapshot.attempts().stream().filter(a -> a.sessionGoalId().equals(g.id()) && a.exposedAt() != null)
                    .sorted(Comparator.comparing(PracticeAttempt::attemptType))
                    .map(a -> new ScenarioRecord(a.scenarioId(), a.attemptType(), a.exposedAt())).toList(),
                snapshot.responses().stream().filter(p -> attempts.get(p.attemptId()).sessionGoalId().equals(g.id()))
                    .sorted(Comparator.comparing(PracticeResponse::responseType))
                    .map(p -> new ResponseRecord(p.scenarioId(), p.responseType(), p.utteranceSeq(), p.notPerformedReason(), p.label())).toList(),
                snapshot.cues().stream().filter(c -> attempts.get(c.attemptId()).sessionGoalId().equals(g.id()))
                    .sorted(Comparator.comparing(c -> attempts.get(c.attemptId()).attemptType()))
                    .map(c -> new CueRecord(c.scenarioId(), c.consentResponse(), c.deliveredText(), c.containsAnswerSentence(), c.givenAt())).toList())).toList()));
    }
}
