package kr.mujoong.core.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BR2·BR5: 명시적 트랜잭션을 지원하는 개발/시험 대역. */
public final class MemoryChildKnowledgeGraph implements ChildKnowledgeGraphPort {
    private record Usage(int observedCount, Instant lastObservedAt, Instant lastReferencedAt) {
        static final Usage NONE = new Usage(0, null, null);
    }
    private final Map<UUID, Map<String, KnowledgeSeed>> children = new HashMap<>();
    private final Map<UUID, Map<String, Usage>> usage = new HashMap<>();
    private final Map<String, String> versions = new HashMap<>();
    private final Map<String, List<Runnable>> transactions = new HashMap<>();
    private final Map<String, Map<String, String>> transactionVersions = new HashMap<>();

    @Override public synchronized List<KnowledgeSeed> seeds(UUID childId) {
        return children.getOrDefault(childId, Map.of()).values().stream()
                .filter(KnowledgeSeed::enabled).sorted(java.util.Comparator.comparing(KnowledgeSeed::seedId)).toList();
    }

    @Override public synchronized List<SeedProfile> profiles(UUID childId) {
        Map<String, Usage> recorded = usage.getOrDefault(childId, Map.of());
        return seeds(childId).stream().map(seed -> {
            Usage value = recorded.getOrDefault(seed.seedId(), Usage.NONE);
            return new SeedProfile(seed, value.observedCount(), value.lastObservedAt(), value.lastReferencedAt());
        }).toList();
    }

    @Override public synchronized String begin() {
        String id = UUID.randomUUID().toString();
        transactions.put(id, new ArrayList<>());
        transactionVersions.put(id, new HashMap<>());
        return id;
    }

    private List<Runnable> transaction(String id) {
        List<Runnable> work = transactions.get(id);
        if (work == null) throw new IllegalStateException("Graph transaction closed");
        return work;
    }

    @Override public synchronized void replaceBase(String id, UUID child, SeedCatalog catalog) {
        transaction(id);
        String existing = versions.get(catalog.version());
        if (existing != null && !existing.equals(catalog.digest())) {
            throw new IllegalArgumentException("Catalog version conflict");
        }
        String staged = transactionVersions.get(id).putIfAbsent(catalog.version(), catalog.digest());
        if (staged != null && !staged.equals(catalog.digest())) throw new IllegalArgumentException("Catalog version conflict");
        transaction(id).add(() -> {
            versions.put(catalog.version(), catalog.digest());
            Map<String, KnowledgeSeed> seeds = children.computeIfAbsent(child, ignored -> new HashMap<>());
            // Neo4j 교체처럼 새 카탈로그에 없는 기본 씨앗만 기록과 함께 지우고, 남는 씨앗의 기록은 유지한다.
            var kept = catalog.seeds().stream().map(KnowledgeSeed::seedId).collect(java.util.stream.Collectors.toSet());
            seeds.values().stream().filter(seed -> seed.origin() == KnowledgeSeed.Origin.POC && !kept.contains(seed.seedId()))
                    .forEach(seed -> usage.getOrDefault(child, new HashMap<>()).remove(seed.seedId()));
            seeds.values().removeIf(seed -> seed.origin() == KnowledgeSeed.Origin.POC && !kept.contains(seed.seedId()));
            catalog.seeds().forEach(seed -> seeds.put(seed.seedId(), seed));
        });
    }

    @Override public synchronized void writeGeneratedSeed(String id, UUID child, KnowledgeSeed seed) {
        if (seed.origin() != KnowledgeSeed.Origin.GENERATED) throw new IllegalArgumentException("Expected generated seed");
        transaction(id).add(() -> children.computeIfAbsent(child, ignored -> new HashMap<>()).put(seed.seedId(), seed));
    }

    @Override public synchronized void writeObservations(String id, UUID child, Map<String, SeedObservation> observations) {
        Map<String, SeedObservation> copy = Map.copyOf(observations);
        transaction(id).add(() -> {
            Map<String, KnowledgeSeed> seeds = children.get(child);
            if (seeds == null) return;
            Map<String, Usage> recorded = usage.computeIfAbsent(child, ignored -> new HashMap<>());
            for (String seedId : seeds.keySet()) {
                Usage before = recorded.getOrDefault(seedId, Usage.NONE);
                SeedObservation observed = copy.get(seedId);
                recorded.put(seedId, observed == null ? new Usage(0, null, before.lastReferencedAt())
                        : new Usage(observed.count(), observed.lastObservedAt(), before.lastReferencedAt()));
            }
        });
    }

    @Override public synchronized void markReferenced(String id, UUID child, String seedId, Instant referencedAt) {
        transaction(id).add(() -> {
            Map<String, KnowledgeSeed> seeds = children.get(child);
            if (seeds == null || !seeds.containsKey(seedId)) return;
            Map<String, Usage> recorded = usage.computeIfAbsent(child, ignored -> new HashMap<>());
            Usage before = recorded.getOrDefault(seedId, Usage.NONE);
            recorded.put(seedId, new Usage(before.observedCount(), before.lastObservedAt(), referencedAt));
        });
    }

    @Override public synchronized void deleteSeeds(String id, UUID child, List<String> seedIds) {
        List<String> copy = List.copyOf(seedIds);
        transaction(id).add(() -> {
            Map<String, KnowledgeSeed> seeds = children.get(child);
            if (seeds != null) copy.forEach(seeds::remove);
            Map<String, Usage> recorded = usage.get(child);
            if (recorded != null) copy.forEach(recorded::remove);
        });
    }

    @Override public synchronized void deleteChild(String id, UUID child) {
        transaction(id).add(() -> {
            children.remove(child);
            usage.remove(child);
        });
    }

    @Override public synchronized void commit(String id) {
        List<Runnable> work = transaction(id);
        transactionVersions.get(id).forEach((version, digest) -> {
            if (versions.containsKey(version) && !versions.get(version).equals(digest)) {
                throw new IllegalArgumentException("Catalog version conflict");
            }
        });
        work.forEach(Runnable::run);
        transactions.remove(id);
        transactionVersions.remove(id);
    }

    @Override public synchronized boolean terminate(String id) {
        transactions.remove(id);
        transactionVersions.remove(id);
        return true;
    }
}
