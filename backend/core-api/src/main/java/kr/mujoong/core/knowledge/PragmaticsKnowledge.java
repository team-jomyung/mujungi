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

    public BehaviorDefinition requireActive(String key, int version) {
        BehaviorDefinition definition = requireVersion(key, version);
        if (!"ACTIVE".equals(definition.status())) {
            throw new IllegalArgumentException("Behavior definition is not active");
        }
        return definition;
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

    /** Call inside the referencing write transaction. */
    public void markUsed(String key, int version) {
        requireActive(key, version);
        if (repository.markUsed(key, version, clock.now()) != 1) {
            throw new IllegalArgumentException("Behavior definition is no longer active");
        }
    }

    /** Freeze and read a pinned session version atomically in the caller's transaction. */
    public BehaviorDefinition pinVersion(String key, int version) {
        BehaviorDefinition definition = repository.lockVersion(key, version)
                .orElseThrow(() -> new IllegalArgumentException("Behavior definition not found"));
        if (!definition.complete() || !("ACTIVE".equals(definition.status()) || "RETIRED".equals(definition.status()))) {
            throw new IllegalArgumentException("Behavior definition is incomplete or unpublished");
        }
        if (repository.markPinned(key, version, clock.now()) != 1) {
            throw new IllegalArgumentException("Behavior definition cannot be pinned");
        }
        return repository.findVersion(key, version).orElseThrow();
    }

    public List<SupportItem> supportItems(String behaviorKey) {
        return repository.findApprovedSupport(behaviorKey);
    }

    public List<String> relatedBehaviorKeys(String behaviorKey) {
        return graph.map(adapter -> List.copyOf(adapter.findRelatedBehaviorKeys(behaviorKey))).orElseGet(List::of);
    }
}
