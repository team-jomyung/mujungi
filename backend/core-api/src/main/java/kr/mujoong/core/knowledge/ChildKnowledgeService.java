package kr.mujoong.core.knowledge;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kr.mujoong.contract.time.MujoongClock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** BR1·BR5·BR6: 조회 작업은 읽기만 하며, 복구/초기화 쓰기는 별도 잠금 경계다. */
public class ChildKnowledgeService implements AutoCloseable {
    final KnowledgeLedgerRepository ledger;
    final KnowledgeAccessPolicy access;
    final ChildKnowledgeGraphPort graph;
    final TransactionTemplate transactions;
    final MujoongClock clock;
    private final SeedCatalog catalog;
    private final boolean enabled;
    private final Duration budget;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> initializing = ConcurrentHashMap.newKeySet();
    private final VariantPlanner planner = new VariantPlanner();

    public ChildKnowledgeService(KnowledgeLedgerRepository ledger, KnowledgeAccessPolicy access, ChildKnowledgeGraphPort graph,
            PlatformTransactionManager manager, MujoongClock clock, SeedCatalog catalog, boolean enabled, Duration budget) {
        this.ledger = ledger;
        this.access = access;
        this.graph = graph;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(Math.max(1, (int) Math.ceil(budget.toMillis() / 1000.0)));
        this.clock = clock;
        this.catalog = catalog;
        this.enabled = enabled;
        this.budget = budget;
    }

    public boolean enabled() { return enabled; }
    <T> T transaction(Supplier<T> action) { return transactions.execute(status -> action.get()); }

    public void requestInitialization(UUID guardian, UUID child) {
        if (!enabled || !initializing.add(child)) return;
        Instant deadline = clock.now().plus(budget);
        try {
            executor.execute(() -> {
                try { initialize(guardian, child, deadline); }
                finally { initializing.remove(child); }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // 종료 중 초기화 예약은 거절하되 이미 커밋한 아동 등록에는 영향을 주지 않는다.
            initializing.remove(child);
        }
    }

    public boolean initializeChild(UUID guardian, UUID child) {
        return enabled && initialize(guardian, child, clock.now().plus(budget));
    }

    private boolean initialize(UUID guardian, UUID child, Instant deadline) {
        try {
            graph.prepareSchema();
            String handle = transaction(() -> {
                access.requireAllowed(guardian, child);
                var state = ledger.ensure(guardian, child);
                requireActive(state);
                recover(child);
                requireTime(deadline);
                String tx = graph.begin();
                try { ledger.initialization(child, tx); }
                catch (RuntimeException e) { graph.terminate(tx); throw e; }
                return tx;
            });
            if (handle == null) return true;
            return transaction(() -> {
                access.requireAllowed(guardian, child);
                var state = ledger.child(child).orElseThrow();
                requireActive(state);
                requireTime(deadline);
                if (!handle.equals(state.initializationTransactionId())) throw new IllegalStateException("Initialization replaced");
                ledger.baseLoaded(child, catalog, clock.now());
                graph.replaceBase(handle, child, catalog);
                for (KnowledgeSeed seed : ledger.successful(child)) graph.writeGeneratedSeed(handle, child, seed);
                requireTime(deadline);
                graph.commit(handle);
                ledger.initialization(child, null);
                return true;
            });
        } catch (RuntimeException e) {
            // BR6: 원격 실패는 초기화 실패로 번역한다. 핸들은 남겨 다음 접근/삭제에서 회수한다.
            return false;
        }
    }

    void recover(UUID child) {
        ledger.expireReservations(child, clock.now());
        var state = ledger.child(child).orElseThrow();
        if (state.initializationTransactionId() != null) {
            if (!graph.terminate(state.initializationTransactionId())) throw new IllegalStateException("Initialization unresolved");
            ledger.initialization(child, null);
        }
        for (var operation : ledger.pending(child)) {
            if (!graph.terminate(operation.transactionId())) throw new IllegalStateException("Projection unresolved");
            String cleanup = graph.begin();
            try {
                graph.deleteSeeds(cleanup, child, List.of(operation.seedId()));
                graph.commit(cleanup);
                ledger.finish(operation, null);
            } finally {
                graph.terminate(cleanup);
            }
        }
    }

    public List<KnowledgeSeed> seedSummaries(UUID guardian, UUID child) {
        return readBounded(guardian, child).seeds();
    }

    private Read readBounded(UUID guardian, UUID child) {
        if (!enabled) return new Read(List.of(), false, null);
        Read result = bounded(() -> transaction(() -> read(guardian, child)), new Read(List.of(), false, null));
        if (result.initialize()) requestInitialization(guardian, child);
        return result;
    }

    /** 그래프 예산 안에 끝나지 않거나 실패한 조회는 빈 값으로 바꾼다(FR9.5). */
    private <T> T bounded(java.util.concurrent.Callable<T> work, T fallback) {
        var future = executor.submit(work);
        try {
            return future.get(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return fallback;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return fallback;
        }
    }

    private Read read(UUID guardian, UUID child) {
        access.requireAllowed(guardian, child);
        if (ledger.deletedChild(child)) return new Read(List.of(), false, null);
        var state = ledger.child(child);
        if (state.isEmpty()) return new Read(List.of(), true, null);
        requireActive(state.get());
        if (!catalog.version().equals(state.get().baseVersion()) || state.get().initializationTransactionId() != null
                || !ledger.pending(child).isEmpty()) return new Read(List.of(), true, null);
        Set<String> success = ledger.successful(child).stream().map(KnowledgeSeed::seedId).collect(java.util.stream.Collectors.toSet());
        List<KnowledgeSeed> seeds = graph.seeds(child).stream().filter(KnowledgeSeed::enabled)
                .filter(seed -> seed.origin() == KnowledgeSeed.Origin.POC || success.contains(seed.seedId())).toList();
        return new Read(seeds, seeds.isEmpty(), state.get().baseVersion());
    }

    public record SkillContext(KnowledgeSeed seed, String graphVersion) {}

    public Optional<SkillContext> skillContext(UUID guardian, UUID child, String seedId) {
        if (seedId == null || "NONE".equals(seedId)) return Optional.empty();
        Read read = readBounded(guardian, child);
        return read.seeds().stream().filter(seed -> seed.seedId().equals(seedId))
                .map(seed -> new SkillContext(seed, read.version())).findFirst();
    }

    public Optional<VariantPlanner.Plan> variantPlan(UUID guardian, UUID child, String seedId,
            VariantPlanner.Context context, List<VariantPlanner.Exposure> actualExposures) {
        return skillContext(guardian, child, seedId).flatMap(value -> planner.plan(value.seed(), context, actualExposures));
    }

    /** 참고 씨앗 후보가 되려면 자유대화에서 이만큼 이상 관찰돼야 한다. 태그 한 번의 오류에 끌려가지 않게 한다. */
    static final int REFERENCE_MIN_OBSERVATIONS = 2;

    /** 연습 장면의 소재 참고로만 쓰는 씨앗. 목표 기술 · 보존 조건은 바꾸지 않는다. */
    public record ReferenceSeed(String seedId, String invariantSentence) {
        @Override public String toString() { return "ReferenceSeed[" + seedId + "]"; }
    }

    /**
     * 자유대화 목표 후보의 씨앗 태그 횟수를 그 아이 그래프에 덮어쓴다. 원문 · 세션 출처는 그래프에 두지 않는다(BR2).
     * 횟수는 권한을 확인한 같은 트랜잭션에서 센다. 그래프가 아직 준비되지 않았으면 쓰지 않고 다음 동기화에 맡긴다.
     *
     * @return 그래프에 썼으면 true
     */
    public boolean recordObservations(UUID guardian, UUID child, Supplier<Map<String, SeedObservation>> observations) {
        if (!enabled) return false;
        return writeRecord(guardian, child, () -> Map.copyOf(observations.get()),
                (tx, counted) -> graph.writeObservations(tx, child, counted));
    }

    /**
     * 담은 목표의 씨앗 가운데 장면 참고로 쓸 씨앗 하나를 고른다. 연습 목표 자신의 씨앗, 관찰이 적은 씨앗, 쉬는 기간 안의 씨앗은 빼고
     * 관찰 횟수가 많은 순으로 고른다. 담은 목표의 씨앗은 권한을 확인한 뒤에 읽는다. 고를 씨앗이 없거나 조회가 예산을 넘기면 빈 값이다
     * (참고 없이 장면을 만든다).
     */
    public Optional<ReferenceSeed> referenceSeed(UUID guardian, UUID child, String goalSeedId,
            Supplier<Set<String>> eligibleSeedIds, Duration cooldown) {
        if (!enabled) return Optional.empty();
        return bounded(() -> chooseReference(guardian, child, goalSeedId, eligibleSeedIds, cooldown), Optional.empty());
    }

    private record Candidates(Set<String> eligible, Set<String> generated) {}

    private Optional<ReferenceSeed> chooseReference(UUID guardian, UUID child, String goalSeedId,
            Supplier<Set<String>> eligibleSeedIds, Duration cooldown) {
        Candidates candidates = transaction(() -> {
            access.requireAllowed(guardian, child);
            if (!ready(child)) return null;
            return new Candidates(Set.copyOf(eligibleSeedIds.get()),
                    ledger.successful(child).stream().map(KnowledgeSeed::seedId).collect(java.util.stream.Collectors.toSet()));
        });
        if (candidates == null || candidates.eligible().isEmpty()) return Optional.empty();
        // 그래프 조회는 보호자 잠금 밖에서 한다. 같은 보호자의 연습 저장이 그래프 응답을 기다리지 않게 한다.
        Instant now = clock.now();
        return graph.profiles(child).stream()
                .filter(profile -> profile.seed().enabled())
                .filter(profile -> profile.seed().origin() == KnowledgeSeed.Origin.POC
                        || candidates.generated().contains(profile.seed().seedId()))
                .filter(profile -> candidates.eligible().contains(profile.seed().seedId())
                        && !profile.seed().seedId().equals(goalSeedId))
                .filter(profile -> profile.observedCount() >= REFERENCE_MIN_OBSERVATIONS)
                .filter(profile -> profile.lastReferencedAt() == null || !now.isBefore(profile.lastReferencedAt().plus(cooldown)))
                .sorted(java.util.Comparator.comparingInt(SeedProfile::observedCount).reversed()
                        .thenComparing(SeedProfile::lastObservedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(profile -> profile.seed().seedId()))
                .findFirst()
                .map(profile -> new ReferenceSeed(profile.seed().seedId(), profile.seed().invariantSentence()));
    }

    private kr.mujoong.core.observability.EventLogger events;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void connectEvents(kr.mujoong.core.observability.EventLogger events) { this.events = events; }

    /** 연습 장면을 저장한 뒤 참고로 쓴 씨앗의 시점을 남긴다. 연습 시작을 기다리게 하지 않으려고 따로 실행한다. */
    public void requestReferenceMark(UUID guardian, UUID child, String seedId) {
        if (!enabled) return;
        Instant referencedAt = clock.now();
        try {
            executor.execute(() -> {
                try { markReferenced(guardian, child, seedId, referencedAt); }
                catch (RuntimeException failed) {
                    // 남기지 못하면 다음 연습에서 같은 씨앗이 쉬지 않고 다시 참고될 수 있을 뿐이라 연습 결과는 바꾸지 않고 기록만 남긴다.
                    if (events != null) events.appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE, "ReferenceMarkFailed");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // 종료 중에는 참고 시점을 남기지 않는다. 위와 같은 이유로 연습 결과에는 영향이 없다.
        }
    }

    boolean markReferenced(UUID guardian, UUID child, String seedId, Instant referencedAt) {
        return writeRecord(guardian, child, () -> Boolean.TRUE, (tx, ignored) -> graph.markReferenced(tx, child, seedId, referencedAt));
    }

    /**
     * 권한 · 그래프 상태는 짧은 PostgreSQL 트랜잭션에서 확인하고, Neo4j 쓰기는 보호자 잠금 밖에서 한다. 쓰기는 MATCH만 쓰므로
     * 확인 뒤 삭제가 끼어들어도 지운 아이 그래프를 되살리지 않는다. 생성 씨앗과 달리 원장을 거치지 않는 파생 기록이며,
     * 결과가 불명확해도 아이 삭제(`deleteChild`)가 노드째 지운다.
     */
    private <T> boolean writeRecord(UUID guardian, UUID child, Supplier<T> prepare, java.util.function.BiConsumer<String, T> write) {
        T prepared = transaction(() -> {
            access.requireAllowed(guardian, child);
            return ready(child) ? prepare.get() : null;
        });
        if (prepared == null) return false;
        String tx = graph.begin();
        try {
            write.accept(tx, prepared);
            graph.commit(tx);
        } finally { graph.terminate(tx); }
        return true;
    }

    /** 기본 씨앗이 현재 판으로 적재돼 있고, 삭제 · 초기화 · 복구 중이 아닌 아이 그래프만 기록을 읽고 쓴다. */
    private boolean ready(UUID child) {
        if (ledger.deletedChild(child)) return false;
        var state = ledger.child(child);
        return state.isPresent() && "ACTIVE".equals(state.get().state())
                && catalog.version().equals(state.get().baseVersion())
                && state.get().initializationTransactionId() == null && ledger.pending(child).isEmpty();
    }

    public void deleteChild(UUID guardian, UUID child) { delete(guardian, child, null); }
    public void deleteGeneratedSeedsForRecord(UUID guardian, UUID child, UUID activity) { delete(guardian, child, activity); }

    private void delete(UUID guardian, UUID child, UUID activity) {
        transaction(() -> {
            access.requireDeletionOwner(guardian, child);
            if (ledger.child(child).isEmpty() && ledger.deletedChild(child)) return null;
            if (ledger.child(child).isEmpty()) ledger.ensure(guardian, child);
            ledger.markDeleting(child, activity);
            return null;
        });
        transaction(() -> {
            access.requireDeletionOwner(guardian, child);
            if (ledger.child(child).isEmpty() && ledger.deletedChild(child)) return null;
            var state = ledger.child(child).orElseThrow();
            if (!enabled && state.graphMayExist()) throw new IllegalStateException("Historical graph deletion requires retry");
            if (enabled && state.graphMayExist()) {
                recover(child);
                String tx = graph.begin();
                try {
                    if (activity == null) graph.deleteChild(tx, child);
                    else graph.deleteSeeds(tx, child, ledger.sourceSeeds(child, activity));
                    graph.commit(tx);
                } finally { graph.terminate(tx); }
            }
            ledger.deleteLedger(child, activity);
            return null;
        });
    }

    static void requireActive(KnowledgeLedgerRepository.ChildState state) {
        if (!"ACTIVE".equals(state.state())) throw new IllegalStateException("Knowledge deletion in progress");
    }
    void requireTime(Instant deadline) {
        if (!clock.now().isBefore(deadline)) throw new IllegalStateException("Knowledge deadline expired");
    }
    private record Read(List<KnowledgeSeed> seeds, boolean initialize, String version) {}
    @Override public void close() { executor.shutdownNow(); }
}
