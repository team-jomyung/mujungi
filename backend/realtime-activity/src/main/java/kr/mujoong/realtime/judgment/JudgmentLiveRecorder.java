package kr.mujoong.realtime.judgment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.realtime.common.collect.BoundedCollections;
import kr.mujoong.realtime.common.db.SqlErrors;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.runtime.TaskLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판정 기록 비동기 기록기 (U11 FR2.1 · BR2.1 · BR2.5 · NFR8). 판정 턴 기록을 실행기 스레드에서 떼어 쓴다 — {@link #put}은 칸에 넣기만 하고
 * DB를 기다리지 않는다. 전사 기록기({@link AsyncRecorder})와 따로 판정 기록 전용 칸 목록을 둔다(id = turnId, 같은 id는 제자리 덮어쓰기).
 *
 * <p>재시도 규칙(BR2.5): 일시 실패(연결 · 시간 초과 · 교착 등)는 칸을 그대로 두고 앱 오류 한 줄(사유 코드만)을 남긴 뒤 <b>다음 틱</b>에 같은 id로
 * 다시 쓴다(곧바로 되풀이하지 않는다). 외래 키 · 검사 제약 위반(세션이 이미 지워짐 등)은 재시도해도 소용없으므로 그 기록을 버리고 사유 코드만
 * 남긴다. 계정 삭제 중인 보호자의 기록({@link #block})도 받지 않고 버린다 — 삭제 참가자(순서 180)가 지운 뒤 늦은 기록이 되살아나 세션 삭제(200)를
 * 막는 일이 없게 한다. 칸 수에 상한이 있고 넘으면 새 기록을 받지 않는다(앱 오류 한 줄). 틱 · 앱 종료 정리는 전사 기록기의 틱 · 정리에 얹혀 돈다.
 */
@Component
@SingleInstanceAssumption(si = "SI-10",
        reason = "아직 쓰지 못한 판정 기록 칸이 이 프로세스 메모리에만 있어 프로세스가 죽으면 잃고 다른 인스턴스로 옮길 수 없다")
public class JudgmentLiveRecorder {

    /** 실제로 쓰는 곳. 운영은 {@link JdbcStore}, 시험은 대역. 한 건을 한 트랜잭션에 쓰고 실패는 예외다. */
    public interface Store {

        void write(JudgmentLiveRecord record);
    }

    /** 칸 상한(판정 턴은 드물어 넉넉한 값이다). */
    static final int MAX_PENDING = 500;
    /** 기억하는 차단 보호자 수 상한(오래된 것부터 잊는다). */
    static final int MAX_BLOCKED = 10_000;

    /** 앱 오류로 남기는 사유 코드(원문 없음, R-F14). */
    static final String REASON_WRITE_FAILED = "JudgmentRecordWriteFailed";
    static final String REASON_DISCARDED = "JudgmentRecordDiscarded";
    static final String REASON_REJECTED = "JudgmentRecordRejected";

    private final Store store;
    private final TaskLauncher launcher;
    private final EventLogger events;
    private final LinkedHashMap<UUID, JudgmentLiveRecord> pending = new LinkedHashMap<>();
    private final Set<UUID> blocked = BoundedCollections.synchronizedSet(MAX_BLOCKED);
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition flushEnded = lock.newCondition();
    private boolean inFlight;

    @Autowired
    public JudgmentLiveRecorder(JudgmentLiveRepository repository, PlatformTransactionManager transactionManager,
            TaskLauncher launcher, EventLogger events, AsyncRecorder host) {
        this(new JdbcStore(repository, new TransactionTemplate(transactionManager)), launcher, events);
        host.onTick(this::tick);
        host.onDrain(this::drain);
    }

    public JudgmentLiveRecorder(Store store, TaskLauncher launcher, EventLogger events) {
        this.store = Objects.requireNonNull(store, "store");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 판정 기록을 칸에 넣고 쓰기를 신호한다(같은 id는 덮어쓴다). 기다리지 않는다.
     *
     * @return 받았으면 참. 차단된 보호자의 기록(버림) · 칸이 가득 찬 새 기록은 거짓
     */
    public boolean put(JudgmentLiveRecord record) {
        Objects.requireNonNull(record, "record");
        if (blocked.contains(record.guardianId())) {
            events.appError(EventLog.Component.SESSION_LIFECYCLE, REASON_DISCARDED, EventCode.Level.WARN);
            return false;
        }
        lock.lock();
        try {
            if (!pending.containsKey(record.id()) && pending.size() >= MAX_PENDING) {
                events.appError(EventLog.Component.SESSION_LIFECYCLE, REASON_REJECTED, EventCode.Level.WARN);
                return false;
            }
            pending.put(record.id(), record);
        } finally {
            lock.unlock();
        }
        signal();
        return true;
    }

    /** 틱: 남은 칸(앞서 일시 실패한 것 포함)의 쓰기를 신호한다. */
    public void tick() {
        signal();
    }

    /** 아직 쓰지 못한 칸 수(시험 · 진단). */
    public int held() {
        lock.lock();
        try {
            return pending.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 계정 삭제 전에 그 보호자의 기록을 막는다: 대기 칸을 버리고 이후 기록은 받지 않으며, 진행 중 쓰기가 있으면 끝나기를 {@code wait}까지
     * 기다린다. 돌아온 뒤 판정 기록을 지우면 지운 기록이 되살아나지 않는다.
     *
     * @return 진행 중 쓰기가 없거나 끝났으면 참(상한을 넘기면 거짓 — 차단은 그래도 걸려 있다)
     */
    public boolean block(UUID guardianId, Duration wait) {
        Objects.requireNonNull(guardianId, "guardianId");
        blocked.add(guardianId);
        long remaining = Math.max(0L, Objects.requireNonNull(wait, "wait").toNanos());
        lock.lock();
        try {
            pending.values().removeIf(record -> record.guardianId().equals(guardianId));
            while (inFlight) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = flushEnded.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 앱 종료: 남은 칸을 지금 스레드에서 한 번씩 쓴다. 진행 중 쓰기가 있으면 끝나기를 {@code wait}까지 기다린다.
     *
     * @return 쓰지 못하고 남은 칸 수
     */
    public int drain(Duration wait) {
        long remaining = Math.max(0L, Objects.requireNonNull(wait, "wait").toNanos());
        lock.lock();
        try {
            while (inFlight) {
                if (remaining <= 0) {
                    return held();
                }
                remaining = flushEnded.awaitNanos(remaining);
            }
            inFlight = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return held();
        } finally {
            lock.unlock();
        }
        flush();
        return held();
    }

    private void signal() {
        lock.lock();
        try {
            if (pending.isEmpty() || inFlight) {
                return;
            }
            inFlight = true;
        } finally {
            lock.unlock();
        }
        try {
            launcher.launch("judgment-record-flush", this::flush);
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.SESSION_LIFECYCLE, e);
            finishFlush();
        }
    }

    /** 칸을 가져와 한 건씩 쓴다. 호출 전에 inFlight를 세웠다. */
    private void flush() {
        List<JudgmentLiveRecord> batch;
        lock.lock();
        try {
            batch = new ArrayList<>(pending.values());
        } finally {
            lock.unlock();
        }
        try {
            for (JudgmentLiveRecord record : batch) {
                writeOne(record);
            }
        } finally {
            finishFlush();
        }
    }

    private void writeOne(JudgmentLiveRecord record) {
        if (blocked.contains(record.guardianId())) {
            drop(record);
            return;
        }
        try {
            store.write(record);
            lock.lock();
            try {
                // 쓰는 사이 같은 id가 더 새 값으로 덮였으면 그 칸은 남긴다
                pending.remove(record.id(), record);
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            if (isPermanent(e)) {
                events.appError(EventLog.Component.SESSION_LIFECYCLE, REASON_DISCARDED, EventCode.Level.WARN);
                drop(record);
            } else {
                // 일시 실패: 칸을 그대로 두고 다음 틱이 다시 쓴다
                events.appError(EventLog.Component.SESSION_LIFECYCLE, REASON_WRITE_FAILED, EventCode.Level.WARN);
            }
        }
    }

    private void drop(JudgmentLiveRecord record) {
        lock.lock();
        try {
            pending.remove(record.id(), record);
        } finally {
            lock.unlock();
        }
    }

    /** 외래 키(23503) · 검사 제약(23514) 위반은 재시도해도 소용없다. */
    static boolean isPermanent(Throwable error) {
        return SqlErrors.sqlState(error).filter(state -> state.equals(SqlErrors.FOREIGN_KEY_VIOLATION) || state.equals("23514"))
                .isPresent();
    }

    private void finishFlush() {
        lock.lock();
        try {
            inFlight = false;
            flushEnded.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 운영 쓰기: 기록 한 건을 한 트랜잭션에 쓴다. */
    static final class JdbcStore implements Store {

        private final JudgmentLiveRepository repository;
        private final TransactionTemplate transactions;

        JdbcStore(JudgmentLiveRepository repository, TransactionTemplate transactions) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.transactions = Objects.requireNonNull(transactions, "transactions");
        }

        @Override
        public void write(JudgmentLiveRecord record) {
            transactions.executeWithoutResult(status -> repository.upsert(record));
        }
    }
}
