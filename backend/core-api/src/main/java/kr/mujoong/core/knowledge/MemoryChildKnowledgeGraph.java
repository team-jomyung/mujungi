package kr.mujoong.core.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BR2·BR5: 명시적 트랜잭션을 지원하는 개발/시험 대역. */
public final class MemoryChildKnowledgeGraph implements ChildKnowledgeGraphPort {
    private final Map<UUID, Map<String, KnowledgeSeed>> children = new HashMap<>();
    private final Map<String, String> versions = new HashMap<>();
    private final Map<String, List<Runnable>> transactions = new HashMap<>();

    @Override public synchronized List<KnowledgeSeed> seeds(UUID childId) {
        return children.getOrDefault(childId, Map.of()).values().stream()
                .filter(KnowledgeSeed::enabled).sorted(java.util.Comparator.comparing(KnowledgeSeed::seedId)).toList();
    }

    @Override public synchronized String begin() {
        String id = UUID.randomUUID().toString();
        transactions.put(id, new ArrayList<>());
        return id;
    }

    private List<Runnable> transaction(String id) {
        List<Runnable> work = transactions.get(id);
        if (work == null) throw new IllegalStateException("Graph transaction closed");
        return work;
    }

    @Override public synchronized void replaceBase(String id, UUID child, SeedCatalog catalog) {
        String existing = versions.get(catalog.version());
        if (existing != null && !existing.equals(catalog.digest())) {
            throw new IllegalArgumentException("Catalog version conflict");
        }
        transaction(id).add(() -> {
            versions.put(catalog.version(), catalog.digest());
            Map<String, KnowledgeSeed> seeds = children.computeIfAbsent(child, ignored -> new HashMap<>());
            seeds.values().removeIf(seed -> seed.origin() == KnowledgeSeed.Origin.POC);
            catalog.seeds().forEach(seed -> seeds.put(seed.seedId(), seed));
        });
    }

    @Override public synchronized void writeGeneratedSeed(String id, UUID child, KnowledgeSeed seed) {
        if (seed.origin() != KnowledgeSeed.Origin.GENERATED) throw new IllegalArgumentException("Expected generated seed");
        transaction(id).add(() -> children.computeIfAbsent(child, ignored -> new HashMap<>()).put(seed.seedId(), seed));
    }

    @Override public synchronized void deleteSeeds(String id, UUID child, List<String> seedIds) {
        List<String> copy = List.copyOf(seedIds);
        transaction(id).add(() -> {
            Map<String, KnowledgeSeed> seeds = children.get(child);
            if (seeds != null) copy.forEach(seeds::remove);
        });
    }

    @Override public synchronized void deleteChild(String id, UUID child) {
        transaction(id).add(() -> children.remove(child));
    }

    @Override public synchronized void commit(String id) {
        List<Runnable> work = transaction(id);
        work.forEach(Runnable::run);
        transactions.remove(id);
    }

    @Override public synchronized boolean terminate(String id) {
        transactions.remove(id);
        return true;
    }
}
