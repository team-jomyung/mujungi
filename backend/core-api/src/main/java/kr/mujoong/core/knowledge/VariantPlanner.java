package kr.mujoong.core.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** BR3: 승인된 아동 어휘에서 한 축만 결정적으로 바꾼다. */
public final class VariantPlanner {
    public static final Set<String> TOPICS = Set.of("그림", "독서", "놀이");
    public static final Set<String> PLACES = Set.of("교실", "도서관", "운동장");
    public static final Set<String> PARTICIPANTS = Set.of("아이와 친구", "아이와 또래", "아이와 모둠원");
    public record Context(String topic, String place, String participants, String relationship, String situation) {}
    public record Exposure(String seedId, Context context) {}
    public record Plan(String grade, String changedField, Context context, StructureSignature signature,
            String invariantSentence, String preservationRule, String misreadCost, boolean recoverable) {}

    public Optional<Plan> plan(KnowledgeSeed seed, Context base, List<Exposure> actualExposures) {
        if (!KnowledgeSeed.RELATIONSHIPS.contains(base.relationship()) || !KnowledgeSeed.SITUATIONS.contains(base.situation())
                || !TOPICS.contains(base.topic()) || !PLACES.contains(base.place()) || !PARTICIPANTS.contains(base.participants())) {
            return Optional.empty();
        }
        List<Exposure> exposures = List.copyOf(actualExposures);
        long same = exposures.stream().filter(e -> e.seedId().equals(seed.seedId())
                && e.context().relationship().equals(base.relationship()) && e.context().situation().equals(base.situation())).count();
        String grade = same >= 3 ? "NEAR" : "SAME_CONTEXT";
        List<Plan> plans = new ArrayList<>();
        if (same >= 3) {
            KnowledgeSeed.RELATIONSHIPS.forEach(v -> add(plans, seed, base, grade, "relationship", v));
            KnowledgeSeed.SITUATIONS.forEach(v -> add(plans, seed, base, grade, "situation", v));
        } else {
            TOPICS.forEach(v -> add(plans, seed, base, grade, "topic", v));
            PLACES.forEach(v -> add(plans, seed, base, grade, "place", v));
            PARTICIPANTS.forEach(v -> add(plans, seed, base, grade, "participants", v));
        }
        return plans.stream().sorted(Comparator
                .comparing((Plan p) -> exposures.stream().anyMatch(e -> e.seedId().equals(seed.seedId()) && e.context().equals(p.context())))
                .thenComparingInt((Plan p) -> AXIS_ORDER.indexOf(p.changedField()))
                .thenComparing(p -> p.context().toString())).findFirst();
    }

    /**
     * 바꿀 축의 우선순위. 이름순으로 고르면 늘 참여자(아이와 친구 → 아이와 또래)만 바뀌어 검토가 의미 있는 변형이 아니라고(UNCHANGED_VARIANT)
     * 거절했다(2026-10-30 실키 4번 모두). 아이가 달라졌다고 느끼는 장소 · 활동을 먼저 바꾸고, 참여자는 마지막에 둔다(FT-1030-p).
     * NEAR 단계의 관계 · 상황은 지금 순서를 지킨다.
     */
    private static final List<String> AXIS_ORDER = List.of("place", "topic", "participants", "relationship", "situation");

    private void add(List<Plan> plans, KnowledgeSeed seed, Context base, String grade, String field, String value) {
        Context changed = new Context(field.equals("topic") ? value : base.topic(),
                field.equals("place") ? value : base.place(), field.equals("participants") ? value : base.participants(),
                field.equals("relationship") ? value : base.relationship(), field.equals("situation") ? value : base.situation());
        if (!changed.equals(base)) plans.add(new Plan(grade, field, changed, seed.signature(), seed.invariantSentence(),
                seed.preservationRule(), "LOW", true));
    }
}
