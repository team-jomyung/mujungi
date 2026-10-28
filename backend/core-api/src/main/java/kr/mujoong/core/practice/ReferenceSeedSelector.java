package kr.mujoong.core.practice;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.knowledge.ChildKnowledgeService.ReferenceSeed;

/**
 * 연습 장면을 처음 만들 때 보호자가 연습에 고른 적 있는 목표의 씨앗 가운데 아이가 자유대화에서 자주 막힌 씨앗 하나를 소재 참고로 고른다.
 * 고르지 못하면(후보 없음 · 쉬는 기간 · 조회 실패 · 예산 초과) 지금처럼 참고 없이 만든다. 연습 시작을 막지 않는다.
 */
public final class ReferenceSeedSelector {
    private final ChildKnowledgeService knowledge;
    private final Function<UUID, Set<String>> savedSkillRefs;
    private final ExecutorService executor;
    private final Duration budget;
    private final Duration cooldown;

    public ReferenceSeedSelector(ChildKnowledgeService knowledge, Function<UUID, Set<String>> savedSkillRefs,
            ExecutorService executor, Duration budget, Duration cooldown) {
        this.knowledge = Objects.requireNonNull(knowledge);
        this.savedSkillRefs = Objects.requireNonNull(savedSkillRefs);
        this.executor = Objects.requireNonNull(executor);
        this.budget = Objects.requireNonNull(budget);
        this.cooldown = Objects.requireNonNull(cooldown);
    }

    public Optional<ReferenceSeed> select(UUID guardian, UUID child, String goalSkillRef, ScenarioDeadline deadline) {
        if (!knowledge.enabled()) return Optional.empty();
        try {
            // 후보 목표는 그래프 서비스가 권한을 확인한 뒤에 읽는다.
            return deadline.call(executor, deadline.limit(budget),
                    () -> knowledge.referenceSeed(guardian, child, goalSkillRef, () -> savedSkillRefs.apply(child), cooldown));
        } catch (RuntimeException failure) {
            // 전체 상황 생성 마감이 지났으면 그대로 실패시키고, 참고 조회만 실패했으면 참고 없이 만든다.
            deadline.check();
            return Optional.empty();
        }
    }

    /** 승인된 장면에 실제로 참고를 넣었을 때만 부른다. 쉬는 기간은 이 시점부터 센다. */
    public void used(UUID guardian, UUID child, ReferenceSeed seed) {
        knowledge.requestReferenceMark(guardian, child, seed.seedId());
    }
}
