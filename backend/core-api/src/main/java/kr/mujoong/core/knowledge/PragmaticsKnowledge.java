package kr.mujoong.core.knowledge;

import java.util.List;
import java.util.Optional;
import kr.mujoong.contract.time.MujoongClock;
import org.springframework.stereotype.Service;

/** Versioned definitions and approved support, without personal record ownership. */
@Service
public class PragmaticsKnowledge {
    private final KnowledgeJdbcRepository repository;
    private final MujoongClock clock;
    private final Optional<KnowledgeGraphLookup> graph;

    public PragmaticsKnowledge(KnowledgeJdbcRepository repository, MujoongClock clock,
            KnowledgeProperties properties, Optional<KnowledgeGraphLookup> graph) {
        this.repository = repository;
        this.clock = clock;
        if (properties.enabled() && graph.isEmpty()) {
            throw new IllegalStateException("Knowledge graph enabled without a read adapter");
        }
        this.graph = properties.enabled() ? graph : Optional.empty();
    }

    /** A pinned historical version remains readable after retirement. */
    public BehaviorDefinition requireVersion(String key, int version) {
        BehaviorDefinition definition = repository.findVersion(key, version)
                .orElseThrow(() -> new IllegalArgumentException("Behavior definition not found"));
        if (!definition.complete() || "DRAFT".equals(definition.status())) {
            throw new IllegalArgumentException("Behavior definition is incomplete or unpublished");
        }
        return definition;
    }

    /**
     * 부르는 쪽 트랜잭션 안에서 버전을 고정(pin)하고 고정된 내용을 돌려준다. 행은 FOR KEY SHARE로만 읽어 이미 고정된 정의는 여러 보호자가
     * 서로 기다리지 않고, used_at은 처음 한 번만 찍는다(U5a 리뷰 1). 첫 고정이 겹치면 뒤쪽 UPDATE만 앞 트랜잭션이 끝날 때까지 짧게 줄 서고,
     * 그 뒤 0건이면 다시 읽어 찍힌 값을 쓴다.
     *
     * @throws IllegalArgumentException 없는 버전 · 미완성 · 초안이거나 고정하지 못했을 때
     */
    public BehaviorDefinition pinVersion(String key, int version) {
        BehaviorDefinition definition = repository.readForPin(key, version)
                .orElseThrow(() -> new IllegalArgumentException("Behavior definition not found"));
        requirePinnable(definition);
        if (definition.usedAt() != null) {
            return definition;
        }
        repository.markPinned(key, version, clock.now());
        BehaviorDefinition pinned = repository.findVersion(key, version)
                .orElseThrow(() -> new IllegalArgumentException("Behavior definition not found"));
        requirePinnable(pinned);
        if (pinned.usedAt() == null) {
            throw new IllegalArgumentException("Behavior definition cannot be pinned");
        }
        return pinned;
    }

    private static void requirePinnable(BehaviorDefinition definition) {
        if (!definition.complete() || !("ACTIVE".equals(definition.status()) || "RETIRED".equals(definition.status()))) {
            throw new IllegalArgumentException("Behavior definition is incomplete or unpublished");
        }
    }

    public List<SupportItem> supportItems(String behaviorKey) {
        return repository.findApprovedSupport(behaviorKey);
    }

    public List<String> relatedBehaviorKeys(String behaviorKey) {
        return graph.map(adapter -> List.copyOf(adapter.findRelatedBehaviorKeys(behaviorKey))).orElseGet(List::of);
    }
}
