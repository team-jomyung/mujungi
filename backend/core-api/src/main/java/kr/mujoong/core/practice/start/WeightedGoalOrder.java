package kr.mujoong.core.practice.start;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 저장 순위 기반 비복원 추첨. 기존 원본 재사용은 난수기를 호출하지 않는다. */
public final class WeightedGoalOrder {
    public record Selection(UUID guardianId, UUID childId, UUID goalSelectionId, String displayName,
            String behaviorKey, int behaviorVersion, String criteria, int savedSortOrder, boolean favorite,kr.mujoong.core.practice.GoalContext goalContext) {
        public Selection(UUID guardianId,UUID childId,UUID goalSelectionId,String displayName,String behaviorKey,int behaviorVersion,String criteria,int savedSortOrder,boolean favorite){this(guardianId,childId,goalSelectionId,displayName,behaviorKey,behaviorVersion,criteria,savedSortOrder,favorite,null);}
        public Selection{if(goalContext==null)goalContext=kr.mujoong.core.practice.GoalContext.legacy(behaviorKey,behaviorVersion);}
        @Override public String toString() { return "Selection[id=" + goalSelectionId + "]"; }
    }
    public List<FrozenGoal> freeze(UUID guardianId, UUID childId, List<Selection> selections, RandomGenerator random) {
        Objects.requireNonNull(guardianId); Objects.requireNonNull(childId); Objects.requireNonNull(random);
        if (selections.isEmpty()) throw new IllegalArgumentException("선택 목표 필요");
        var ordered = selections.stream().sorted(selectionOrder()).toList();
        var ids = new HashSet<UUID>(); var sorts = new HashSet<Integer>();
        for (var s : ordered) {
            if (!guardianId.equals(s.guardianId()) || !childId.equals(s.childId()) || s.goalSelectionId() == null
                    || !ids.add(s.goalSelectionId())) throw new IllegalArgumentException("목표 소유/중복 오류");
            if (!sorts.add(s.savedSortOrder())&&ordered.stream().anyMatch(other->other!=s&&other.savedSortOrder()==s.savedSortOrder()&&other.goalContext().kind()==s.goalContext().kind()))throw new IllegalArgumentException("같은 종류 저장 순위 중복");
        }
        var remaining = new ArrayList<Integer>();
        for (int rank = 0; rank < ordered.size(); rank++) remaining.add(rank);
        var frozen = new ArrayList<FrozenGoal>();
        while (!remaining.isEmpty()) {
            long total = remaining.stream().mapToLong(rank -> weight(ordered, rank)).sum();
            long selected = random.nextLong(total); int chosen = 0;
            for (; chosen < remaining.size() - 1; chosen++) {
                long weight = weight(ordered, remaining.get(chosen)); if (selected < weight) break; selected -= weight;
            }
            int rank = remaining.remove(chosen); var s = ordered.get(rank);
            frozen.add(new FrozenGoal(s.goalSelectionId(), s.displayName(), s.behaviorKey(), s.behaviorVersion(), s.criteria(),
                    s.savedSortOrder(), rank + 1, ordered.size(), s.favorite(), s.favorite() ? 2 : 1,
                    weight(ordered, rank), frozen.size() + 1,s.goalContext()));
        }
        return List.copyOf(frozen);
    }
    private int weight(List<Selection> selections, int rank) {
        return Math.multiplyExact(selections.get(rank).favorite() ? 2 : 1, selections.size() - rank);
    }
    public List<FrozenGoal> reuse(List<FrozenGoal> stored, Set<UUID> requested) {
        var result = validate(stored);
        if (!result.stream().map(FrozenGoal::goalSelectionId).collect(java.util.stream.Collectors.toSet()).equals(requested))
            throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
        return result;
    }
    public List<FrozenGoal> validate(List<FrozenGoal> stored) {
        if (stored.isEmpty()) throw new IllegalArgumentException("준비 원본 필요");
        var ids = new HashSet<UUID>(); var ranks = new HashSet<Integer>(); var orders = new HashSet<Integer>();
        var sorted = stored.stream().sorted(frozenOrder()).toList();
        for (int i = 0; i < sorted.size(); i++) {
            var g = sorted.get(i);
            if (g.selectedCount() != stored.size() || !ids.add(g.goalSelectionId()) || !ranks.add(g.selectionRank())
                    || !orders.add(g.order()) || g.selectionRank() != i + 1
                    || (i > 0 && sorted.get(i - 1).savedSortOrder() == g.savedSortOrder()&&sorted.get(i-1).goalContext().kind()==g.goalContext().kind()))
                throw new IllegalArgumentException("준비 원본 순열 불일치");
        }
        return stored.stream().sorted(Comparator.comparingInt(FrozenGoal::order)).toList();
    }
    private static Comparator<Selection> selectionOrder(){return Comparator.comparingInt(Selection::savedSortOrder).thenComparing(s->s.goalContext().kind()).thenComparing(Selection::goalSelectionId);}
    private static Comparator<FrozenGoal> frozenOrder(){return Comparator.comparingInt(FrozenGoal::savedSortOrder).thenComparing(s->s.goalContext().kind()).thenComparing(FrozenGoal::goalSelectionId);}
}
