package kr.mujoong.core.usage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.SqlErrors;
import kr.mujoong.core.observability.CoreMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용량 전용 유한 큐와 저장 작업 하나. Luna 호출부는 큐에 넣기만 하고 DB를 기다리지 않는다. 저장 작업 하나가 한 번에 한 연결만 써서 업무 요청의
 * DB 풀을 덜 점유한다. 가득 차면 새 기록을 버리고 지표로 센다(사용량은 예상 비용용이라 업무를 막지 않는 쪽을 고른다).
 *
 * <p>아직 저장하지 못한 기록은 프로세스 메모리에 있어 강제 종료 때 잃을 수 있다. 여러 인스턴스가 떠도 인스턴스마다 자기 큐만 저장하므로 서로 겹치지 않는다.
 */
@Component
public class AiUsageRecorder implements AiUsageCollector, SmartLifecycle {

    /** 한 기록을 저장한다. 시험은 DB 없이 대역을 넣는다. */
    @FunctionalInterface
    public interface Store {
        Result write(AiUsageRecord record);
    }

    public enum Result { STORED, DISCARDED }

    private enum Outcome { ACCEPTED, FULL, STOPPED, STORED, RETRY, EXPIRED, DISCARDED }

    private record Pending(AiUsageRecord record, long firstSeen) { }

    private final Store store;
    private final MujoongClock clock;
    private final AiUsageProperties.Timing timing;
    private final AiUsageProperties.Limits limits;
    private final boolean enabled;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();
    private final EnumMap<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile long stopStarted;
    private Thread worker;
    private boolean inFlight;

    @Autowired
    public AiUsageRecorder(AiUsageRepository repository, PlatformTransactionManager manager, MujoongClock clock,
            AiUsageProperties properties, AiUsageProperties.Timing timing, AiUsageProperties.Limits limits, CoreMetrics metrics) {
        this(transactionalStore(repository, manager, timing), clock, properties, timing, limits, metrics.registry());
    }

    public AiUsageRecorder(Store store, MujoongClock clock, AiUsageProperties properties, AiUsageProperties.Timing timing,
            AiUsageProperties.Limits limits, MeterRegistry registry) {
        this.store = store;
        this.clock = clock;
        this.enabled = properties.enabled();
        this.timing = timing;
        this.limits = limits;
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, registry.counter("mujoong.ai.usage.records", "outcome", outcome.name().toLowerCase(Locale.ROOT)));
        }
        Gauge.builder("mujoong.ai.usage.pending", this, AiUsageRecorder::pendingCount).register(registry);
    }

    /** 기록마다 짧은 트랜잭션 하나. 보호자가 이미 지워져 외래 키가 막으면 다시 시도하지 않고 버린다. */
    private static Store transactionalStore(AiUsageRepository repository, PlatformTransactionManager manager,
            AiUsageProperties.Timing timing) {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setTimeout(Math.toIntExact(Math.max(1L, (timing.writeTimeout().toMillis() + 999L) / 1000L)));
        return record -> {
            try {
                tx.executeWithoutResult(status -> repository.write(record));
                return Result.STORED;
            } catch (DataIntegrityViolationException rejected) {
                if (SqlErrors.isForeignKeyViolation(rejected)) {
                    return Result.DISCARDED;
                }
                throw rejected;
            }
        };
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean offer(AiUsageRecord record) {
        if (!enabled) {
            return true;
        }
        if (stopping) {
            count(Outcome.STOPPED);
            return false;
        }
        lock.lock();
        try {
            if (stopping) {
                count(Outcome.STOPPED);
                return false;
            }
            if (!pending.containsKey(record.id()) && pending.size() >= limits.queueCapacity()) {
                count(Outcome.FULL);
                return false;
            }
            pending.putIfAbsent(record.id(), new Pending(record, clock.monotonicNanos()));
            count(Outcome.ACCEPTED);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public int pendingCount() {
        lock.lock();
        try {
            return pending.size();
        } finally {
            lock.unlock();
        }
    }

    /** 한 배치를 저장한다. DB는 큐 잠금 밖에서 부른다. 저장 작업과 시험이 쓴다. */
    public void flushOnce() {
        List<Pending> batch;
        lock.lock();
        try {
            if (inFlight || pending.isEmpty()) {
                return;
            }
            inFlight = true;
            batch = new ArrayList<>(pending.values().stream().limit(limits.batchSize()).toList());
        } finally {
            lock.unlock();
        }
        try {
            for (Pending item : batch) {
                if (stopping && clock.elapsedSince(stopStarted).compareTo(timing.shutdownWait()) >= 0) {
                    break;
                }
                if (clock.elapsedSince(item.firstSeen()).compareTo(timing.pendingTtl()) >= 0) {
                    remove(item);
                    count(Outcome.EXPIRED);
                    continue;
                }
                try {
                    Result result = store.write(item.record());
                    remove(item);
                    count(result == Result.STORED ? Outcome.STORED : Outcome.DISCARDED);
                } catch (RuntimeException failure) {
                    // 원문 · 예외 메시지는 남기지 않고 지표로만 센다. 같은 행 키로 다음 주기에 다시 저장한다
                    count(Outcome.RETRY);
                    rotate(item);
                }
            }
        } finally {
            lock.lock();
            try {
                inFlight = false;
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 계정 삭제: 그 보호자의 대기 기록을 버리고 진행 중 저장이 끝나기를 제한 시간만큼 기다린다. 다 끝나야 DB 삭제가 늦게 도착한 행을 남기지 않는다.
     *
     * @return 진행 중 저장이 시간 안에 끝났는가
     */
    public boolean discardGuardian(UUID guardianId, Duration wait) {
        long left = wait.toNanos();
        lock.lock();
        try {
            pending.values().removeIf(p -> p.record().link() != null && guardianId.equals(p.record().link().guardianId()));
            while (inFlight) {
                if (left <= 0) {
                    return false;
                }
                left = idle.awaitNanos(left);
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void remove(Pending item) {
        lock.lock();
        try {
            pending.remove(item.record().id(), item);
        } finally {
            lock.unlock();
        }
    }

    private void rotate(Pending item) {
        lock.lock();
        try {
            if (pending.remove(item.record().id(), item)) {
                pending.put(item.record().id(), item);
            }
        } finally {
            lock.unlock();
        }
    }

    private void count(Outcome outcome) {
        counters.get(outcome).increment();
    }

    @Override
    public void start() {
        if (!enabled || running) {
            return;
        }
        stopping = false;
        running = true;
        worker = Thread.ofVirtual().name("core-ai-usage-writer").start(() -> {
            while (running || stopping && clock.elapsedSince(stopStarted).compareTo(timing.shutdownWait()) < 0 && pendingCount() > 0) {
                flushOnce();
                if (!running && pendingCount() == 0) {
                    break;
                }
                LockSupport.parkNanos(timing.flushInterval().toNanos());
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        });
    }

    @Override
    public void stop() {
        stopStarted = clock.monotonicNanos();
        stopping = true;
        running = false;
        Thread current = worker;
        if (current == null) {
            return;
        }
        LockSupport.unpark(current);
        try {
            if (!current.join(timing.shutdownWait())) {
                current.interrupt();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 결과 작업 · 장면 생성이 먼저 멈춰야 마지막 사용량을 저장할 수 있으므로 가장 늦게 멈춘다. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
