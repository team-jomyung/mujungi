package kr.mujoong.core.practice.start;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.core.activity.PracticeStart;
import kr.mujoong.core.practice.Scenario;

/** 기존 PracticeStart/C8 필드 대응만 담당한다. 실제 준비 저장·API·빈 등록은 하지 않는다. */
public final class PreparedPracticePackageMapper {
    public record PreparedSnapshot(UUID sessionId, UUID idempotencyKey, List<FrozenGoal> goals, Scenario firstScenario) {
        public PreparedSnapshot {
            Objects.requireNonNull(sessionId); Objects.requireNonNull(idempotencyKey); Objects.requireNonNull(firstScenario);
            goals = new WeightedGoalOrder().validate(goals);
            if (firstScenario.scenarioId() == null || !"ORIGINAL".equals(firstScenario.kind()) || firstScenario.validation() == null
                    || !firstScenario.validation().goalIncluded() || !firstScenario.validation().triggerIncluded()
                    || firstScenario.validation().forbiddenFound()) throw new IllegalArgumentException("검증된 첫 상황 필요");
        }
        @Override public String toString() { return "PreparedSnapshot[sessionId=" + sessionId + "]"; }
    }
    public PracticeStart.PracticePackage map(PreparedSnapshot prepared) {
        var goals = prepared.goals().stream().map(g -> new PracticeStart.Goal(g.goalSelectionId(), g.order(), g.displayName(),
                g.behaviorKey(), g.behaviorVersion(), g.judgmentCriteriaSnapshot())).toList();
        var s = prepared.firstScenario();
        Map<String, Object> scenario = new LinkedHashMap<>(Map.of("scenarioId", s.scenarioId(), "kind", s.kind(), "relationType", s.relationType(),
                "opportunityCondition", s.opportunityCondition(), "scenarioText", s.scenarioText(), "partnerRoleText", s.partnerRoleText(),
                "triggerScript", s.triggerScript(), "validation", s.validation()));
        if(s.scriptVersion()!=null)scenario.put("scriptVersion",s.scriptVersion());
        if(s.scenes()!=null)scenario.put("scenes",s.scenes());
        if(s.selfReview()!=null)scenario.put("selfReview",s.selfReview());
        if(s.generation()!=null)scenario.put("generation",s.generation());
        return new PracticeStart.PracticePackage(goals, scenario);
    }
    /** top-level firstScenario를 기존 C8 첫 목표 아래로 명시 이동한다. 추첨 입력은 내보내지 않는다. */
    public Map<String, Object> c8(PreparedSnapshot prepared) {
        var pack = map(prepared);
        return Map.of("goals", pack.goals().stream().map(g -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("goalSelectionId", g.goalSelectionId()); value.put("order", g.order()); value.put("displayName", g.displayName());
            value.put("behaviorKey", g.behaviorKey()); value.put("behaviorVersion", g.behaviorVersion()); value.put("judgmentCriteria", g.judgmentCriteria());
            value.put("firstScenario", g.order() == 1 ? pack.firstScenario() : null);
            var original=prepared.goals().get(g.order()-1).goalContext();
            if(original.candidate()){
                value.put("goalKind",original.kind());value.put("candidateRef",original.candidateRef());
                value.put("candidateVersion",original.candidateVersion());value.put("goalContext",original);
            }
            return java.util.Collections.unmodifiableMap(value);
        }).toList());
    }
    public PreparedSnapshot reuse(PreparedSnapshot stored, UUID sessionId, UUID idempotencyKey, Set<UUID> selections) {
        if (!stored.sessionId().equals(sessionId) || !stored.idempotencyKey().equals(idempotencyKey))
            throw new IllegalArgumentException("시작 준비 원본 식별자 충돌");
        new WeightedGoalOrder().reuse(stored.goals(), selections); return stored;
    }
}
