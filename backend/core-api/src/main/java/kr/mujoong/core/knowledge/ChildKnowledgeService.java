package kr.mujoong.core.knowledge;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
        if (!enabled) return List.of();
        var future = executor.submit(() -> transaction(() -> read(guardian, child)));
        try {
            Read result = future.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            if (result.initialize()) requestInitialization(guardian, child);
            return result.seeds();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return List.of();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return List.of();
        }
    }

    private Read read(UUID guardian, UUID child) {
        access.requireAllowed(guardian, child);
        if (ledger.deletedChild(child)) return new Read(List.of(), false);
        var state = ledger.child(child);
        if (state.isEmpty()) return new Read(List.of(), true);
        requireActive(state.get());
        if (!catalog.version().equals(state.get().baseVersion()) || state.get().initializationTransactionId() != null
                || !ledger.pending(child).isEmpty()) return new Read(List.of(), true);
        Set<String> success = ledger.successful(child).stream().map(KnowledgeSeed::seedId).collect(java.util.stream.Collectors.toSet());
        List<KnowledgeSeed> seeds = graph.seeds(child).stream().filter(KnowledgeSeed::enabled)
                .filter(seed -> seed.origin() == KnowledgeSeed.Origin.POC || success.contains(seed.seedId())).toList();
        return new Read(seeds, seeds.isEmpty());
    }

    public Optional<KnowledgeSeed> skillContext(UUID guardian, UUID child, String seedId) {
        if (seedId == null || "NONE".equals(seedId)) return Optional.empty();
        return seedSummaries(guardian, child).stream().filter(seed -> seed.seedId().equals(seedId)).findFirst();
    }

    public Optional<VariantPlanner.Plan> variantPlan(UUID guardian, UUID child, String seedId,
            VariantPlanner.Context context, List<VariantPlanner.Exposure> actualExposures) {
        return skillContext(guardian, child, seedId).flatMap(seed -> planner.plan(seed, context, actualExposures));
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
    private record Read(List<KnowledgeSeed> seeds, boolean initialize) {}
    @Override public void close() { executor.shutdownNow(); }
}
