package kr.mujoong.realtime.log;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비동기 기록기 (U1 3.5 Step 25 · PD-5 2.4 · BR7.5 · BR6.15 · J125 · NFR5.3 · NFR7.6).
 *
 * <p>전사 행 · 세션 이벤트 기록 · 기기 last_seen_at 쓰기를 음성 경로에서 떼어 낸다. 세션마다 번호 순서를 지키는 칸 목록이 있고(행 하나 =
 * 칸 하나, 같은 번호는 제자리 덮어쓰기), 확정 전 조각은 {@code mujoong.timing.recorder.flush-interval} 틱에, FINAL은 즉시 플러시한다.
 * 세션마다 진행 중 플러시는 하나(순서 보존)이고 진행 중에 신호가 오면 끝난 직후 한 번 더 돈다. 전체 동시 플러시는
 * {@code mujoong.limits.recorder-flush-concurrency}를 넘지 않는다. 한 플러시는 한 트랜잭션이라 한 행의 FINAL 전환과 승인 판정이 같은
 * 문장 · 같은 트랜잭션이다(BR7.5). 커밋이 실패하면 칸을 되돌려 놓고 다음 틱에 다시 쓴다(같은 번호 덮어쓰기라 멱등).
 *
 * <p>세션 칸이 {@code mujoong.limits.recorder-queue}(200)에 닿으면 새 행을 받지 않고(recorder_rejected · ERROR 한 줄) 그 세션을
 * 지연 상태로 둔다. 칸이 절반(100) 아래로 줄 때까지 게이트는 새 AI 구간을 RECORDER_DEGRADED로 폐기한다(fail-closed). 아이 입력 중계는
 * 멈추지 않는다. 기기 last_seen_at은 기기당 마지막 값 하나만 두고 HEARTBEAT 때만 넣는다(J125).
 */
@Component
@SingleInstanceAssumption(si = "SI-10",
        reason = "세션별 미커밋 기록 칸(최대 200행)과 기기 last_seen 칸이 이 프로세스 메모리에만 있어 프로세스가 죽으면 잃고 다른 인스턴스로 옮길 수 없다")
public class AsyncRecorder {

    /** 실제로 쓰는 곳. 운영은 {@link JdbcStore}, 단위 시험은 대역. */
    public interface Store {

        /** 한 세션의 칸(번호 순)과 이벤트 기록을 한 트랜잭션에 쓴다. 실패는 예외. */
        void write(UUID sessionId, List<TranscriptEntry> transcripts, List<SessionEvent> events);

        /** 기기별 마지막 시각을 쓴다(따로 한 트랜잭션). */
        void writeLastSeen(Collection<DeviceConnectionRepository.LastSeen> seen);
    }

    private final Store store;
    private final TaskLauncher launcher;
    private final LatencyRecorder latency;
    private final EventLogger events;
    private final RealtimeMetrics metrics;
    private final MujoongClock clock;
    private final int capacity;
    private final int releaseBelow;
    private final Semaphore flushPermits;
    private final ConcurrentHashMap<UUID, Unit> units = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, DeviceConnectionRepository.LastSeen> lastSeen = new ConcurrentHashMap<>();
    private final AtomicBoolean seenInFlight = new AtomicBoolean();
    // 진행 중 플러시가 끝났다는 신호(앱 종료 drain이 기다린다, 3.6 수정 J305). 잠금 순서는 이 잠금 → 칸 목록 모니터 하나뿐이다
    private final ReentrantLock flushEndedLock = new ReentrantLock();
    private final Condition flushEnded = flushEndedLock.newCondition();

    @Autowired
    public AsyncRecorder(
            TranscriptRepository transcripts,
            SessionEventRepository sessionEvents,
            DeviceConnectionRepository connections,
            PlatformTransactionManager transactionManager,
            TaskLauncher launcher,
            LatencyRecorder latency,
            EventLogger events,
            RealtimeMetrics metrics,
            MujoongClock clock,
            RealtimeProperties properties) {
        this(new JdbcStore(transcripts, sessionEvents, connections, new TransactionTemplate(transactionManager), clock),
                launcher, latency, events, metrics, clock, properties.limits().recorderQueue(),
                properties.limits().recorderFlushConcurrency());
    }

    public AsyncRecorder(Store store, TaskLauncher launcher, LatencyRecorder latency, EventLogger events,
            RealtimeMetrics metrics, MujoongClock clock, int capacity, int flushConcurrency) {
        this.store = Objects.requireNonNull(store, "store");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.latency = Objects.requireNonNull(latency, "latency");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (capacity < 2 || flushConcurrency < 1) {
            throw new IllegalArgumentException("recorder-queue는 2 이상, recorder-flush-concurrency는 1 이상이다");
        }
        this.capacity = capacity;
        this.releaseBelow = capacity / 2;
        this.flushPermits = new Semaphore(flushConcurrency, true);
    }

    /**
     * 전사 칸을 넣거나 덮어쓴다. FINAL이면 즉시 플러시를 신호한다.
     *
     * @return 받았으면 true. 새 행인데 칸이 가득 차 받지 않았으면 false(세션은 지연 상태가 된다)
     */
    public boolean putTranscript(TranscriptEntry entry) {
        Objects.requireNonNull(entry, "entry");
        while (true) {
            Unit unit = units.computeIfAbsent(entry.sessionId(), Unit::new);
            boolean flushNow;
            synchronized (unit) {
                if (unit.removed) {
                    continue;
                }
                boolean newRow = !unit.pending.containsKey(entry.sequenceNo());
                if (newRow && unit.held() >= capacity) {
                    unit.degraded = true;
                    reject(entry.sessionId());
                    return false;
                }
                unit.pending.put(entry.sequenceNo(), entry);
                flushNow = entry.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL;
            }
            if (flushNow) {
                signal(unit);
            }
            return true;
        }
    }

    /** 세션 이벤트 기록 행을 더한다(다음 플러시에 함께, 추가만). */
    public void putEvent(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        while (true) {
            Unit unit = units.computeIfAbsent(event.getSessionId(), Unit::new);
            synchronized (unit) {
                if (unit.removed) {
                    continue;
                }
                unit.events.add(event);
                return;
            }
        }
    }

    /** HEARTBEAT 때 기기의 마지막 시각을 남긴다(기기당 가장 늦은 값만, J125). */
    public void deviceSeen(DeviceConnectionRepository.LastSeen seen) {
        Objects.requireNonNull(seen, "seen");
        lastSeen.merge(seen.deviceId(), seen,
                (old, fresh) -> fresh.seenAt().isBefore(old.seenAt()) && fresh.connectionId().equals(old.connectionId())
                        ? old : fresh);
    }

    /** 그 세션이 지연 상태인가(칸이 상한에 닿은 뒤 절반 아래로 내려가기 전). 게이트 송신 직전 재확인이 읽는다. */
    public boolean isDegraded(UUID sessionId) {
        Unit unit = units.get(sessionId);
        if (unit == null) {
            return false;
        }
        synchronized (unit) {
            return unit.degraded;
        }
    }

    /** 세션이 들고 있는 칸 수(대기 + 진행 중, 시험 · 진단). */
    public int heldRows(UUID sessionId) {
        Unit unit = units.get(sessionId);
        if (unit == null) {
            return 0;
        }
        synchronized (unit) {
            return unit.held();
        }
    }

    /** 틱: 비어 있지 않은 세션 목록과 기기 칸의 플러시를 띄운다(recorder-tick 주기 작업). */
    public void tick() {
        for (Unit unit : units.values()) {
            signal(unit);
        }
        if (!lastSeen.isEmpty() && seenInFlight.compareAndSet(false, true)) {
            launch("recorder-seen", this::flushLastSeen);
        }
    }

    /** 그 세션의 플러시를 지금 신호한다(종료 상태 전이 때 마지막 플러시, PD-5 5). */
    public void finish(UUID sessionId) {
        Unit unit = units.get(sessionId);
        if (unit != null) {
            signal(unit);
        }
    }

    /** 모든 세션이 들고 있는 칸 수 합({@code mujoong_queue_depth{queue=recorder}}, J298). */
    public long heldRowsTotal() {
        long held = 0;
        for (Unit unit : units.values()) {
            synchronized (unit) {
                held += unit.held();
            }
        }
        return held;
    }

    /**
     * 앱 종료(BR6.13 T5): 남은 칸을 지금 스레드에서 한 번씩 쓴다. 진행 중 플러시는 끝났다는 신호를 받을 때까지 모두 합쳐 {@code wait}까지
     * 기다린다(3.6 수정 J305: 예전의 5ms 되풀이 확인 대신 신호). 기다릴 시간을 다 쓰면 그 세션은 건너뛴다.
     *
     * @return 남은(쓰지 못한) 칸 수
     */
    public int drain(Duration wait) {
        long remaining = Math.max(0L, Objects.requireNonNull(wait, "wait").toNanos());
        for (Unit unit : units.values()) {
            remaining = runFlushInline(unit, remaining);
        }
        if (!lastSeen.isEmpty() && seenInFlight.compareAndSet(false, true)) {
            flushLastSeen();
        }
        int left = 0;
        for (Unit unit : units.values()) {
            synchronized (unit) {
                left += unit.held() + unit.events.size();
            }
        }
        return left;
    }

    /** 진행 중 플러시가 끝나기를 기다렸다가 지금 스레드에서 쓴다. 남은 기다림 시간(나노초)을 돌려준다. */
    private long runFlushInline(Unit unit, long remainingNanos) {
        long remaining = remainingNanos;
        flushEndedLock.lock();
        try {
            while (true) {
                synchronized (unit) {
                    if (!unit.inFlight) {
                        unit.inFlight = true;
                        break;
                    }
                }
                if (remaining <= 0) {
                    return 0L;
                }
                remaining = flushEnded.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0L;
        } finally {
            flushEndedLock.unlock();
        }
        flush(unit);
        return remaining;
    }

    /** 진행 중 플러시가 끝났다: 앱 종료 drain을 깨운다. 칸 목록 모니터 밖에서 부른다. */
    private void signalFlushEnded() {
        flushEndedLock.lock();
        try {
            flushEnded.signalAll();
        } finally {
            flushEndedLock.unlock();
        }
    }

    private void signal(Unit unit) {
        synchronized (unit) {
            if (unit.pending.isEmpty() && unit.events.isEmpty()) {
                return;
            }
            if (unit.inFlight) {
                unit.rerun = true;
                return;
            }
            unit.inFlight = true;
        }
        launch("recorder-flush-" + unit.sessionId, () -> flush(unit));
    }

    private void launch(String name, Runnable task) {
        try {
            launcher.launch(name, task);
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.SESSION_LIFECYCLE, e);
        }
    }

    /** 한 세션 플러시. 호출 전에 inFlight를 세웠다. */
    private void flush(Unit unit) {
        List<TranscriptEntry> rows;
        List<SessionEvent> sessionEvents;
        synchronized (unit) {
            rows = new ArrayList<>(unit.pending.values());
            sessionEvents = new ArrayList<>(unit.events);
            unit.taken = new LinkedHashMap<>(unit.pending);
            unit.pending.clear();
            unit.events.clear();
            unit.rerun = false;
        }
        boolean ok = false;
        boolean acquired = false;
        try {
            flushPermits.acquire();
            acquired = true;
            if (!rows.isEmpty() || !sessionEvents.isEmpty()) {
                store.write(unit.sessionId, rows, sessionEvents);
            }
            ok = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.SESSION_LIFECYCLE, e);
        } finally {
            if (acquired) {
                flushPermits.release();
            }
        }
        boolean again;
        synchronized (unit) {
            if (ok) {
                unit.taken = Map.of();
                if (unit.degraded && unit.held() < releaseBelow) {
                    unit.degraded = false;
                }
            } else {
                restore(unit, sessionEvents);
            }
            unit.inFlight = false;
            // 실패면 다음 틱이 다시 쓴다(곧바로 되풀이하지 않는다)
            again = ok && (unit.rerun || hasFinal(unit));
        }
        signalFlushEnded();
        if (ok) {
            Instant committedAt = clock.now();
            for (TranscriptEntry row : rows) {
                // tViewItemReady는 FINAL 전환과 승인 true를 함께 커밋한 행만이다(OD-8 · BR7.5). 폐기한 AI 행은 12에 실리지 않는다
                if (row.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL && row.approvedForDisplay()) {
                    latency.itemReady(row.sessionId(), row.sequenceNo(), row.speaker(), committedAt);
                }
            }
        }
        if (again) {
            signal(unit);
        }
        removeIfDone(unit);
    }

    /** 실패한 플러시의 칸을 되돌린다. 그사이 새로 들어온 같은 번호 칸이 더 새 값이다. */
    private static void restore(Unit unit, List<SessionEvent> sessionEvents) {
        LinkedHashMap<Integer, TranscriptEntry> merged = new LinkedHashMap<>(unit.taken);
        merged.putAll(unit.pending);
        unit.pending.clear();
        unit.pending.putAll(merged);
        unit.taken = Map.of();
        List<SessionEvent> restored = new ArrayList<>(sessionEvents);
        restored.addAll(unit.events);
        unit.events.clear();
        unit.events.addAll(restored);
    }

    private static boolean hasFinal(Unit unit) {
        for (TranscriptEntry entry : unit.pending.values()) {
            if (entry.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL) {
                return true;
            }
        }
        return false;
    }

    /** 빈 칸 목록은 지운다(지연 상태가 아닐 때만). 지운 목록에 늦게 넣으려던 쪽은 새 목록을 만든다. */
    private void removeIfDone(Unit unit) {
        synchronized (unit) {
            if (!unit.inFlight && !unit.degraded && unit.pending.isEmpty() && unit.events.isEmpty()
                    && unit.taken.isEmpty()) {
                unit.removed = true;
                units.remove(unit.sessionId, unit);
            }
        }
    }

    private void flushLastSeen() {
        List<DeviceConnectionRepository.LastSeen> batch = new ArrayList<>();
        try {
            for (DeviceConnectionRepository.LastSeen seen : lastSeen.values()) {
                if (lastSeen.remove(seen.deviceId(), seen)) {
                    batch.add(seen);
                }
            }
            if (!batch.isEmpty()) {
                store.writeLastSeen(batch);
            }
        } catch (RuntimeException e) {
            // 마지막 값만 의미가 있으므로 더 새 값이 없을 때만 되돌린다
            for (DeviceConnectionRepository.LastSeen seen : batch) {
                lastSeen.putIfAbsent(seen.deviceId(), seen);
            }
            events.appError(EventLog.Component.DEVICE_GATEWAY, e);
        } finally {
            seenInFlight.set(false);
        }
    }

    private void reject(UUID sessionId) {
        metrics.recorderRejected();
        events.log(EventCode.QUEUE_PRESSURE, EventLog.Component.SESSION_LIFECYCLE, sessionId, entry -> entry
                .attr(AttrKey.QUEUE, "RECORDER").attr(AttrKey.DROPPED, 1L).level(EventCode.Level.ERROR));
    }

    /** 세션 칸 목록. 잠금은 이 객체다. */
    private static final class Unit {
        private final UUID sessionId;
        private final LinkedHashMap<Integer, TranscriptEntry> pending = new LinkedHashMap<>();
        private final List<SessionEvent> events = new ArrayList<>();
        private Map<Integer, TranscriptEntry> taken = Map.of();
        private boolean inFlight;
        private boolean rerun;
        private boolean degraded;
        private boolean removed;

        private Unit(UUID sessionId) {
            this.sessionId = sessionId;
        }

        /** 들고 있는 칸 수(같은 번호가 대기와 진행 중 양쪽에 있으면 하나로 센다). */
        private int held() {
            int count = pending.size();
            for (Integer seq : taken.keySet()) {
                if (!pending.containsKey(seq)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 운영 쓰기: 전사 배치 + 이벤트 기록을 한 트랜잭션, last_seen은 따로 한 트랜잭션(RD-22). */
    static final class JdbcStore implements Store {

        private final TranscriptRepository transcripts;
        private final SessionEventRepository sessionEvents;
        private final DeviceConnectionRepository connections;
        private final TransactionTemplate transactions;
        private final MujoongClock clock;

        JdbcStore(TranscriptRepository transcripts, SessionEventRepository sessionEvents,
                DeviceConnectionRepository connections, TransactionTemplate transactions, MujoongClock clock) {
            this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
            this.sessionEvents = Objects.requireNonNull(sessionEvents, "sessionEvents");
            this.connections = Objects.requireNonNull(connections, "connections");
            this.transactions = Objects.requireNonNull(transactions, "transactions");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public void write(UUID sessionId, List<TranscriptEntry> rows, List<SessionEvent> events) {
            transactions.executeWithoutResult(status -> {
                if (!rows.isEmpty()) {
                    transcripts.upsertAll(rows, clock.now());
                }
                sessionEvents.insertAll(events);
            });
        }

        @Override
        public void writeLastSeen(Collection<DeviceConnectionRepository.LastSeen> seen) {
            transactions.executeWithoutResult(status -> connections.markSeenAll(seen));
        }
    }
}
