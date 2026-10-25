package kr.mujoong.realtime.usage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용량 전용 유한 큐. 수신 경로에서는 tryLock과 값 교체만 하며 DB·빈자리·재시도를 기다리지 않는다.
 * 단일 저장 작업으로 DB 연결 점유를 제한한다. 전사 기록기의 지연 상태와 연결하지 않는다.
 */
@Component
@SingleInstanceAssumption(si = "SI-10", reason = "미저장 사용량은 프로세스 메모리에 있어 강제 종료 시 유실될 수 있다")
public class AiUsageRecorder implements AiUsageCollector, SmartLifecycle {
    @FunctionalInterface
    public interface Store { AiUsageRepository.Result write(AiUsageRecord record, Predicate<UUID> allowed); }
    private enum Outcome { ACCEPTED, FULL, CONTENTION, STOPPED, STORED, RETRY, UNLINKED, EXPIRED, DISCARDED }
    private record Pending(AiUsageRecord record, long firstSeen) { }

    private final Store store;
    private final MujoongClock clock;
    private final AiUsageTimingProperties timing;
    private final AiUsageLimitsProperties limits;
    private final boolean enabled;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();
    private final Set<UUID> blockedSessions = kr.mujoong.realtime.common.collect.BoundedCollections.synchronizedSet(10_000);
    private final EnumMap<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile long stopStarted;
    private Thread worker;
    private boolean inFlight;

    @Autowired
    public AiUsageRecorder(AiUsageRepository repository, PlatformTransactionManager manager, MujoongClock clock,
            AiUsageProperties properties, AiUsageTimingProperties timing, AiUsageLimitsProperties limits,
            MeterRegistry registry) {
        this(transactionalStore(repository, manager, timing), clock, properties, timing, limits, registry);
    }

    public AiUsageRecorder(Store store, MujoongClock clock, AiUsageProperties properties,
            AiUsageTimingProperties timing, AiUsageLimitsProperties limits, MeterRegistry registry) {
        this.store = store;
        this.clock = clock;
        this.enabled = properties.enabled();
        this.timing = timing;
        this.limits = limits;
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, registry.counter("mujoong.ai.usage.records", "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)));
        }
        io.micrometer.core.instrument.Gauge.builder("mujoong.ai.usage.pending", this, AiUsageRecorder::pendingCount)
                .register(registry);
    }

    private static Store transactionalStore(AiUsageRepository repository, PlatformTransactionManager manager,
            AiUsageTimingProperties timing) {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setTimeout(Math.toIntExact(Math.max(1L, (timing.writeTimeout().toMillis() + 999L) / 1000L)));
        return (record, allowed) -> tx.execute(status -> repository.write(record, allowed));
    }

    @Override public boolean enabled() { return enabled; }

    @Override
    public boolean offer(AiUsageRecord record) {
        if (!enabled) return true;
        if (stopping) { count(Outcome.STOPPED); return false; }
        if (!lock.tryLock()) { count(Outcome.CONTENTION); return false; }
        try {
            if (stopping || record.sessionId() != null && blockedSessions.contains(record.sessionId())) {
                count(Outcome.DISCARDED); return false;
            }
            Pending old = pending.get(record.id());
            if (old == null && pending.size() >= limits.queueCapacity()) { count(Outcome.FULL); return false; }
            if (old == null || record.revision() > old.record().revision()) {
                pending.put(record.id(), new Pending(record, old == null ? clock.monotonicNanos() : old.firstSeen()));
            }
            count(Outcome.ACCEPTED);
            return true;
        } finally { lock.unlock(); }
    }

    public int pendingCount() {
        lock.lock();
        try { return pending.size(); } finally { lock.unlock(); }
    }

    /** 시험과 저장 작업이 쓰는 한 배치. DB는 큐 잠금 밖에서 실행한다. */
    public void flushOnce() {
        List<Pending> batch;
        lock.lock();
        try {
            if (inFlight || pending.isEmpty()) return;
            inFlight = true;
            batch = new ArrayList<>(pending.values().stream().limit(limits.batchSize()).toList());
        } finally { lock.unlock(); }
        try {
            for (Pending item : batch) {
                if (stopping && clock.elapsedSince(stopStarted).compareTo(timing.shutdownWait()) >= 0) break;
                if (clock.elapsedSince(item.firstSeen()).compareTo(timing.pendingTtl()) >= 0) {
                    remove(item); count(Outcome.EXPIRED); continue;
                }
                try {
                    AiUsageRepository.Result result = store.write(item.record(), id -> !blockedSessions.contains(id));
                    if (result == AiUsageRepository.Result.UNLINKED) { count(Outcome.UNLINKED); rotate(item); continue; }
                    remove(item);
                    count(result == AiUsageRepository.Result.STORED ? Outcome.STORED : Outcome.DISCARDED);
                } catch (RuntimeException failure) {
                    // 원문·주소·예외 메시지는 기록하지 않는다. 같은 키로 다음 틱에 재시도한다.
                    count(Outcome.RETRY);
                    rotate(item);
                }
            }
        } finally {
            lock.lock();
            try { inFlight = false; idle.signalAll(); } finally { lock.unlock(); }
        }
    }

    private void remove(Pending item) {
        lock.lock();
        try { pending.remove(item.record().id(), item); } finally { lock.unlock(); }
    }

    private void rotate(Pending item) {
        lock.lock();
        try {
            if (pending.remove(item.record().id(), item)) pending.put(item.record().id(), item);
        } finally { lock.unlock(); }
    }

    /** 삭제 시 대기·새 기록을 막고 진행 중 저장이 끝난 뒤에만 DB 삭제를 허용한다. */
    public boolean blockSessions(List<UUID> sessions, Duration wait) {
        blockedSessions.addAll(sessions);
        long left = wait.toNanos();
        lock.lock();
        try {
            pending.values().removeIf(p -> p.record().sessionId() != null && blockedSessions.contains(p.record().sessionId()));
            while (inFlight) {
                if (left <= 0) return false;
                left = idle.awaitNanos(left);
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return false;
        } finally { lock.unlock(); }
    }

    private void count(Outcome outcome) { counters.get(outcome).increment(); }

    @Override public void start() {
        if (!enabled || running) return;
        stopping = false;
        running = true;
        worker = Thread.ofVirtual().name("ai-usage-writer").start(() -> {
            while (running || stopping && clock.elapsedSince(stopStarted).compareTo(timing.shutdownWait()) < 0 && pendingCount() > 0) {
                flushOnce();
                if (!running && pendingCount() == 0) break;
                LockSupport.parkNanos(timing.flushInterval().toNanos());
                if (Thread.currentThread().isInterrupted()) break;
            }
        });
    }

    @Override public void stop() {
        stopStarted = clock.monotonicNanos();
        stopping = true;
        running = false;
        Thread current = worker;
        if (current == null) return;
        LockSupport.unpark(current);
        try { if (!current.join(timing.shutdownWait())) current.interrupt(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); current.interrupt(); }
    }

    @Override public boolean isRunning() { return running; }
    // 공급자 종료·전사 drain이 먼저 끝나야 마지막 사용량을 저장할 수 있다.
    @Override public int getPhase() { return Integer.MIN_VALUE; }
}
