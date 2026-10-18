package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.practice.Scenario;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("practice-legacy")
class PracticePackageContractTest {
    final PreparedPracticePackageMapper mapper = new PreparedPracticePackageMapper();
    static PreparedPracticePackageMapper.PreparedSnapshot prepared() {
        return new PreparedPracticePackageMapper.PreparedSnapshot(UUID.randomUUID(), UUID.randomUUID(), FrozenGoalOrderTest.goals(),
                new Scenario(UUID.randomUUID(), "ORIGINAL", "친구", "차례 요청", "합성 가상 상황", "상대 역할", "차례를 말해 줘",
                        new Scenario.Validation(true, true, false)));
    }
    @Test void mapsExactlyExistingSixGoalFields() {
        var original = prepared(); var pack = mapper.map(original);
        for (int i = 0; i < original.goals().size(); i++) {
            var from = original.goals().get(i); var to = pack.goals().get(i);
            assertThat(to.goalSelectionId()).isEqualTo(from.goalSelectionId()); assertThat(to.order()).isEqualTo(from.order());
            assertThat(to.displayName()).isEqualTo(from.displayName()); assertThat(to.behaviorKey()).isEqualTo(from.behaviorKey());
            assertThat(to.behaviorVersion()).isEqualTo(from.behaviorVersion()); assertThat(to.judgmentCriteria()).isEqualTo(from.judgmentCriteriaSnapshot());
        }
    }
    @Test void c8MovesScenarioToOnlyFirstGoalAndDoesNotSendWeight() {
        var prepared = prepared(); var c8 = mapper.c8(prepared);
        @SuppressWarnings("unchecked") var goals = (List<Map<String, Object>>) c8.get("goals");
        assertThat(c8).containsOnlyKeys("goals");
        assertThat(goals.getFirst()).containsOnlyKeys("goalSelectionId", "order", "displayName", "behaviorKey", "behaviorVersion", "judgmentCriteria", "firstScenario");
        assertThat(goals.getFirst().get("firstScenario")).isEqualTo(mapper.map(prepared).firstScenario());
        assertThat(goals.subList(1, goals.size())).allMatch(g -> g.get("firstScenario") == null);
        assertThat(JsonCodec.shared().write(c8)).doesNotContain("favorite", "selectionRank", "weight", "savedSortOrder");
    }
    @Test void reusesIdenticalSessionKeyAndSnapshot() {
        var p = prepared(); var ids = p.goals().stream().map(FrozenGoal::goalSelectionId).collect(java.util.stream.Collectors.toSet());
        assertThat(mapper.reuse(p, p.sessionId(), p.idempotencyKey(), ids)).isSameAs(p);
        assertThat(mapper.map(p).firstScenario().get("scenarioId")).isEqualTo(p.firstScenario().scenarioId());
    }
    @Test void rejectsConflictingSessionOrKey() {
        var p = prepared(); var ids = p.goals().stream().map(FrozenGoal::goalSelectionId).collect(java.util.stream.Collectors.toSet());
        assertThatThrownBy(() -> mapper.reuse(p, UUID.randomUUID(), p.idempotencyKey(), ids)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.reuse(p, p.sessionId(), UUID.randomUUID(), ids)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnvalidatedFirstScenario() {
        var p = prepared(); var s = p.firstScenario();
        for (var validation : List.of(new Scenario.Validation(false, true, false), new Scenario.Validation(true, false, false), new Scenario.Validation(true, true, true)))
            assertThatThrownBy(() -> new PreparedPracticePackageMapper.PreparedSnapshot(p.sessionId(), p.idempotencyKey(), p.goals(),
                    new Scenario(s.scenarioId(), s.kind(), s.relationType(), s.opportunityCondition(), s.scenarioText(), s.partnerRoleText(), s.triggerScript(), validation)))
                    .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void snapshotAndPackageStringsDoNotExposeProtectedData() {
        var p = prepared(); assertThat(p.toString()).doesNotContain("고정 기준", "합성", "차례");
        assertThat(mapper.map(p).toString()).doesNotContain("고정 기준", "합성", "차례");
        assertThatThrownBy(() -> p.goals().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
