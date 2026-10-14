package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class FrozenGoalOrderTest {
    static final UUID GUARDIAN = UUID.randomUUID(), CHILD = UUID.randomUUID();
    static WeightedGoalOrder.Selection selection(int sort, boolean favorite) {
        return new WeightedGoalOrder.Selection(GUARDIAN, CHILD, UUID.randomUUID(), "차례", "turn", 2, "고정 기준", sort, favorite);
    }
    static List<FrozenGoal> goals() { return new WeightedGoalOrder().freeze(GUARDIAN, CHILD,
            List.of(selection(10, false), selection(20, true), selection(30, false)), new Random(7)); }
    @Test void freezesSavedOrderRankWeightsAndDefinitions() {
        var input = List.of(selection(30, false), selection(10, false), selection(20, true));
        var result = new WeightedGoalOrder().freeze(GUARDIAN, CHILD, input, new Random(7));
        assertThat(result).extracting(FrozenGoal::order).containsExactly(1, 2, 3);
        assertThat(result).filteredOn(g -> g.savedSortOrder() == 20).singleElement().satisfies(g -> {
            assertThat(g.selectionRank()).isEqualTo(2); assertThat(g.weight()).isEqualTo(4);
            assertThat(g.judgmentCriteriaSnapshot()).isEqualTo("고정 기준"); assertThat(g.behaviorVersion()).isEqualTo(2);
        });
    }
    @Test void drawsWithoutReplacementAndMatchesThousandSampleDistribution() {
        var input = List.of(selection(10, false), selection(20, true), selection(30, false));
        var order = new WeightedGoalOrder(); var random = new Random(934); int[] counts = new int[3];
        for (int trial = 0; trial < 1000; trial++) {
            var result = order.freeze(GUARDIAN, CHILD, input, random);
            assertThat(result).extracting(FrozenGoal::goalSelectionId).doesNotHaveDuplicates();
            counts[result.getFirst().selectionRank() - 1]++;
        }
        assertThat(counts[0] / 1000.0).isCloseTo(3.0 / 8, within(0.05));
        assertThat(counts[1] / 1000.0).isCloseTo(4.0 / 8, within(0.05));
        assertThat(counts[2] / 1000.0).isCloseTo(1.0 / 8, within(0.05));
    }
    @Test void storedOriginalReusesWithoutReorderingOrReadingChangedPreferences() {
        var stored = goals(); var ids = stored.stream().map(FrozenGoal::goalSelectionId).collect(java.util.stream.Collectors.toSet());
        assertThat(new WeightedGoalOrder().reuse(stored.reversed(), ids)).isEqualTo(stored);
        assertThatThrownBy(() -> new WeightedGoalOrder().reuse(stored, Set.of(UUID.randomUUID()))).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> stored.add(stored.getFirst())).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsEmptyDuplicateForeignOwnerAndUnagreedTies() {
        var order = new WeightedGoalOrder(); var selected = selection(1, false);
        assertThatThrownBy(() -> order.freeze(GUARDIAN, CHILD, List.of(), new Random())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.freeze(GUARDIAN, CHILD, List.of(selected, selected), new Random())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.freeze(UUID.randomUUID(), CHILD, List.of(selected), new Random())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.freeze(GUARDIAN, CHILD, List.of(selected, selection(1, true)), new Random())).hasMessageContaining("I5");
    }
    @Test void rejectsInvalidFrozenConstraintsAndRedactsCriteria() {
        var g = goals().getFirst(); assertThat(g.toString()).doesNotContain("고정 기준", "차례");
        assertThatThrownBy(() -> new FrozenGoal(g.goalSelectionId(), g.displayName(), g.behaviorKey(), 2, "", 1, 1, 1, false, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrozenGoal(g.goalSelectionId(), g.displayName(), g.behaviorKey(), 2, "기준", 1, 1, 1, true, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeightedGoalOrder().validate(List.of(g, g))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oneGoalAlwaysHasOnePositionAndFavoriteMultiplier() {
        var result = new WeightedGoalOrder().freeze(GUARDIAN, CHILD, List.of(selection(99, true)), new Random(1));
        assertThat(result).singleElement().satisfies(g -> {
            assertThat(g.order()).isEqualTo(1); assertThat(g.selectionRank()).isEqualTo(1); assertThat(g.weight()).isEqualTo(2);
        });
    }
}
