package kr.mujoong.realtime.observability.snapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.db.DbPoolWaitMonitor;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.messaging.RealtimeInboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Realtime METRIC_SNAPSHOT 한 줄과 DB에서 읽는 게이지 (U1 3.6 수정 · OD-6 · OD-12 · NFR7.8 · NFR4.15 · J299 · J302, Core와 같은 방식).
 *
 * <p>maintenance 스레드가 {@code mujoong.observability.system-sample-interval}(5초)마다 {@link #tick()}을 부른다. 매 표본은 CPU · 메모리
 * 비율과 풀 사용 연결 수를 모으고, Outbox · Inbox 적체와 연결 원장의 상태별 기기 수를 다시 읽어 {@code mujoong_outbox_*} ·
 * {@code mujoong_inbox_dead} · {@code mujoong_device_connection_state} 게이지를 갱신한다. 앱 Clock으로
 * {@code mujoong.observability.metric-snapshot-interval}(60초)이 차면 METRIC_SNAPSHOT 한 줄(숫자 키만)을 쓴다. 정상 종료 T5에서
 * {@link #finalSnapshot()}이 마지막 한 줄(부분 구간)을 쓴다(reliability-design.md 2.8).
 *
 * <p>값 규칙(구간 = 직전 줄 이후): outboxPending · outboxOldestPendingSec · outboxFailed · inboxDead · openConnections · sessionsActive ·
 * heapUsedPct는 시점 값, poolActive · poolWaitMaxMs · sysMemMaxPct는 구간 최댓값, sysCpuAvgPct는 구간 표본 평균, poolWaitExceeded는 구간
 * 증가분이다. Core 몫(restTotal · restLe300Ms · viewTotal · viewLe500Ms · viewFallback · breakerOpenSec)은 쓰지 않는다. 그 줄을 쓰는
 * 순간 적체를 읽지 못했으면(DB 장애) 낡은 시점 값을 쓰지 않고 그 네 키를 뺀다.
 */
@Component
public class MetricSnapshotJob {

    private final MujoongClock clock;
    private final Duration interval;
    private final RealtimeOutboxRepository outbox;
    private final RealtimeInboxRepository inbox;
    private final DeviceConnectionRepository connections;
    private final DbPoolWaitMonitor pool;
    private final RealtimeMetrics metrics;
    private final EventLogger events;
    private final SystemLoad system;

    private Instant nextAt;
    private long lastExceeded;
    private double cpuSum;
    private int cpuSamples;
    private double memoryMax = -1;
    private Optional<Backlog> latest = Optional.empty();

    @Autowired
    public MetricSnapshotJob(
            MujoongClock clock,
            RealtimeProperties properties,
            RealtimeOutboxRepository outbox,
            RealtimeInboxRepository inbox,
            DeviceConnectionRepository connections,
            DbPoolWaitMonitor pool,
            RealtimeMetrics metrics,
            EventLogger events) {
        this(clock, properties.observability().metricSnapshotInterval(), outbox, inbox, connections, pool, metrics,
                events, SystemLoad.platform());
    }

    public MetricSnapshotJob(
            MujoongClock clock,
            Duration interval,
            RealtimeOutboxRepository outbox,
            RealtimeInboxRepository inbox,
            DeviceConnectionRepository connections,
            DbPoolWaitMonitor pool,
            RealtimeMetrics metrics,
            EventLogger events,
            SystemLoad system) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval은 0보다 커야 한다");
        }
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.events = Objects.requireNonNull(events, "events");
        this.system = Objects.requireNonNull(system, "system");
        this.nextAt = clock.now().plus(interval);
        this.lastExceeded = pool.exceededTotal();
    }

    /** 표본 하나를 모으고, 스냅샷 주기가 찼으면 한 줄을 쓴다. 오래 멈췄다 돌아와도 밀린 줄을 몰아 쓰지 않는다. */
    public synchronized void tick() {
        sample();
        Instant now = clock.now();
        if (now.isBefore(nextAt)) {
            return;
        }
        emit();
        nextAt = nextAt.plus(interval);
        if (!nextAt.isAfter(now)) {
            nextAt = now.plus(interval);
        }
    }

    /** T5: 정상 종료 때 마지막 한 줄(부분 구간)을 쓴다. 다음 주기 줄은 쓰지 않는다. */
    public synchronized void finalSnapshot() {
        sample();
        emit();
        nextAt = Instant.MAX;
    }

    /** 마지막으로 읽은 적체(게이지 값과 같다, 읽은 적이 없으면 빈 값). */
    public synchronized Optional<Backlog> latestBacklog() {
        return latest;
    }

    private void sample() {
        system.cpu().ifPresent(load -> {
            cpuSum += load;
            cpuSamples++;
        });
        system.memory().ifPresent(used -> memoryMax = Math.max(memoryMax, used));
        pool.sampleActive();
        latest = readBacklog();
        readConnectionStates();
    }

    /** 적체를 읽어 게이지를 바꾼다. DB 장애면 APP_ERROR(병합 대상) 한 줄만 남기고 빈 값이다(게이지는 마지막 값 유지). */
    private Optional<Backlog> readBacklog() {
        try {
            RealtimeOutboxRepository.Backlog out = outbox.backlog();
            long dead = inbox.countDead();
            Instant now = clock.now();
            long oldestSeconds = out.oldestPendingCreatedAt()
                    .map(created -> Math.max(0L, Duration.between(created, now).toSeconds()))
                    .orElse(0L);
            Backlog backlog = new Backlog(out.pending(), oldestSeconds, out.failed(), dead);
            metrics.messagingBacklog(backlog.pending(), backlog.oldestPendingSeconds(), backlog.failed(), backlog.dead());
            return Optional.of(backlog);
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.REALTIME_MAINTENANCE, e);
            return Optional.empty();
        }
    }

    /** 연결 원장의 상태별 기기 수를 게이지에 옮긴다(J299). DB 장애면 마지막 값을 둔다. */
    private void readConnectionStates() {
        try {
            metrics.connectionStates(connections.countByState());
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.REALTIME_MAINTENANCE, e);
        }
    }

    private void emit() {
        long exceeded = pool.exceededTotal();
        DbPoolWaitMonitor.Window window = pool.drainWindow();
        Optional<Backlog> backlog = latest;
        long cpuPercent = cpuSamples == 0 ? -1 : Math.round(cpuSum / cpuSamples * 100);
        long memoryPercent = memoryMax < 0 ? -1 : Math.round(memoryMax * 100);
        long previousExceeded = lastExceeded;
        events.log(EventCode.METRIC_SNAPSHOT, EventLog.Component.REALTIME_MAINTENANCE, null, entry -> {
            backlog.ifPresent(value -> entry
                    .attr(AttrKey.OUTBOX_PENDING, value.pending())
                    .attr(AttrKey.OUTBOX_OLDEST_PENDING_SEC, value.oldestPendingSeconds())
                    .attr(AttrKey.OUTBOX_FAILED, value.failed())
                    .attr(AttrKey.INBOX_DEAD, value.dead()));
            entry.attr(AttrKey.POOL_ACTIVE, window.maxActive())
                    .attr(AttrKey.POOL_WAIT_MAX_MS, window.maxWait().toMillis())
                    .attr(AttrKey.POOL_WAIT_EXCEEDED, exceeded - previousExceeded)
                    .attr(AttrKey.OPEN_CONNECTIONS, (long) metrics.openConnections())
                    .attr(AttrKey.SESSIONS_ACTIVE, (long) metrics.sessionsActive());
            if (cpuPercent >= 0) {
                entry.attr(AttrKey.SYS_CPU_AVG_PCT, cpuPercent);
            }
            if (memoryPercent >= 0) {
                entry.attr(AttrKey.SYS_MEM_MAX_PCT, memoryPercent);
            }
            system.heap().ifPresent(heap -> entry.attr(AttrKey.HEAP_USED_PCT, Math.round(heap * 100)));
        });
        lastExceeded = exceeded;
        cpuSum = 0;
        cpuSamples = 0;
        memoryMax = -1;
    }

    /** 적체 시점 값. */
    public record Backlog(long pending, long oldestPendingSeconds, long failed, long dead) {
    }
}
