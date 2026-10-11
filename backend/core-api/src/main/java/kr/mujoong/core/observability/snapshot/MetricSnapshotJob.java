package kr.mujoong.core.observability.snapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.SessionStartBreaker;
import kr.mujoong.core.common.db.DbPoolWaitMonitor;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.messaging.CoreInboxRepository;
import kr.mujoong.core.messaging.CoreOutboxRepository;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.CoreMetrics.RequestTotals;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Core METRIC_SNAPSHOT 한 줄과 적체 게이지 (U1 3.6 수정 · OD-6 · OD-12 · NFR7.8 · NFR4.15 · J322 · J326).
 *
 * <p>표본 전용 스케줄러 metric-sample(3.6 수정 J368)이 {@code mujoong.observability.system-sample-interval}(5초)마다 {@link #tick()}을
 * 부른다. 매 표본은 CPU · 메모리 비율을 모으고 Outbox · Inbox 적체를 다시 읽어 {@code mujoong_outbox_*} · {@code mujoong_inbox_dead} 게이지를
 * 갱신한다. 앱 Clock으로 {@code mujoong.observability.metric-snapshot-interval}(60초)이 차면 METRIC_SNAPSHOT 한 줄(숫자 키만)을 쓴다. 정상
 * 종료 T5에서 {@link #finalSnapshot(Duration)}이 마지막 한 줄(부분 구간)을 쓴다(reliability-design.md 2.8).
 *
 * <p>값 규칙(구간 = 직전 줄 이후): outboxPending · outboxOldestPendingSec · outboxFailed · inboxDead · heapUsedPct는 시점 값, poolActive ·
 * poolWaitMaxMs · sysMemMaxPct는 구간 최댓값, sysCpuAvgPct는 구간 표본 평균, poolWaitExceeded · restTotal · restLe300Ms · viewTotal ·
 * viewLe500Ms · viewFallback · breakerOpenSec은 구간 증가분이다. Realtime 몫(openConnections · sessionsActive)은 쓰지 않는다. 그 줄을 쓰는
 * 순간 적체를 읽지 못했으면(DB 장애) 낡은 시점 값을 쓰지 않고 그 네 키를 뺀다.
 *
 * <p>DB 읽기는 이 객체의 잠금 밖에서 한다(3.6 수정 J369): DB 읽기에 막힌 표본이 있어도 T5 마지막 줄은 그 표본을 기다리지 않는다. 마지막 줄의
 * 적체 읽기는 정상 종료 마감의 남은 시간까지만 기다리고, 그 안에 못 읽으면 위 규칙대로 네 키를 뺀다.
 */
@Component
public class MetricSnapshotJob {

    /** 마지막 줄의 적체를 마감 안에 읽지 못했을 때 APP_ERROR의 errorKind(3.6 수정 J369). */
    public static final String FINAL_READ_TIMEOUT = "SnapshotReadTimeout";
    /** 마지막 줄의 적체를 읽는 스레드 이름. */
    private static final String FINAL_READ_THREAD = "metric-final-read";

    private final MujoongClock clock;
    private final Duration interval;
    private final CoreOutboxRepository outbox;
    private final CoreInboxRepository inbox;
    private final DbPoolWaitMonitor pool;
    private final SessionStartBreaker breaker;
    private final CoreMetrics metrics;
    private final EventLogger events;
    private final SystemLoad system;

    private Instant nextAt;
    private Totals last;
    private double cpuSum;
    private int cpuSamples;
    private double memoryMax = -1;
    private Optional<Backlog> latest = Optional.empty();

    @Autowired
    public MetricSnapshotJob(
            MujoongClock clock,
            CoreProperties properties,
            CoreOutboxRepository outbox,
            CoreInboxRepository inbox,
            DbPoolWaitMonitor pool,
            SessionStartBreaker breaker,
            CoreMetrics metrics,
            EventLogger events) {
        this(clock, properties.observability().metricSnapshotInterval(), outbox, inbox, pool, breaker, metrics, events,
                SystemLoad.platform());
    }

    public MetricSnapshotJob(
            MujoongClock clock,
            Duration interval,
            CoreOutboxRepository outbox,
            CoreInboxRepository inbox,
            DbPoolWaitMonitor pool,
            SessionStartBreaker breaker,
            CoreMetrics metrics,
            EventLogger events,
            SystemLoad system) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval은 0보다 커야 한다");
        }
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.events = Objects.requireNonNull(events, "events");
        this.system = Objects.requireNonNull(system, "system");
        this.nextAt = clock.now().plus(interval);
        this.last = totals();
    }

    /** 표본 하나를 모으고, 스냅샷 주기가 찼으면 한 줄을 쓴다. 오래 멈췄다 돌아와도 밀린 줄을 몰아 쓰지 않는다. DB 읽기는 잠금 밖이다(J369). */
    public void tick() {
        Optional<Backlog> backlog = readBacklog();
        synchronized (this) {
            sampleLoad();
            latest = backlog;
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
    }

    /**
     * T5: 정상 종료 때 마지막 한 줄(부분 구간)을 쓴다. 적체는 {@code limit}(정상 종료 마감의 남은 시간) 안에서만 읽고, 그 안에 못 읽으면
     * APP_ERROR({@value #FINAL_READ_TIMEOUT}) 한 줄을 남기고 적체 네 키를 뺀다(3.6 수정 J369). 다음 주기 줄은 쓰지 않는다.
     *
     * @param limit 정상 종료 마감까지 남은 시간
     */
    public void finalSnapshot(Duration limit) {
        Objects.requireNonNull(limit, "limit");
        Optional<Backlog> backlog = readBacklogWithin(limit);
        synchronized (this) {
            sampleLoad();
            latest = backlog;
            emit();
            nextAt = Instant.MAX;
        }
    }

    /** 마지막으로 읽은 적체(게이지 값과 같다, 읽은 적이 없으면 빈 값). */
    public synchronized Optional<Backlog> latestBacklog() {
        return latest;
    }

    /** 메모리에서 읽는 표본(CPU · 메모리 · 풀 사용 연결). 잠금 안에서 부른다. */
    private void sampleLoad() {
        system.cpu().ifPresent(load -> {
            cpuSum += load;
            cpuSamples++;
        });
        system.memory().ifPresent(used -> memoryMax = Math.max(memoryMax, used));
        pool.sampleActive();
    }

    /**
     * 적체를 {@code limit} 안에서만 읽는다. 읽는 스레드가 그 안에 끝나지 않으면 더 기다리지 않고 빈 값이다 — 그 스레드는 DB 제한(획득 · 문장
     * 제한)까지 홀로 돌다 끝나고, 늦은 결과는 게이지만 바꾼다(줄은 이미 썼다).
     */
    private Optional<Backlog> readBacklogWithin(Duration limit) {
        if (limit.isPositive()) {
            AtomicReference<Optional<Backlog>> read = new AtomicReference<>(Optional.empty());
            Thread reader = Thread.ofVirtual().name(FINAL_READ_THREAD).start(() -> read.set(readBacklog()));
            try {
                if (reader.join(limit)) {
                    return read.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        events.appError(EventLog.Component.CORE_MAINTENANCE, FINAL_READ_TIMEOUT);
        return Optional.empty();
    }

    /** 적체를 읽어 게이지를 바꾼다. DB 장애면 APP_ERROR(병합 대상) 한 줄만 남기고 빈 값이다(게이지는 마지막 값 유지). */
    private Optional<Backlog> readBacklog() {
        try {
            CoreOutboxRepository.Backlog out = outbox.backlog();
            long dead = inbox.countDead();
            Instant now = clock.now();
            long oldestSeconds = out.oldestPendingCreatedAt()
                    .map(created -> Math.max(0L, Duration.between(created, now).toSeconds()))
                    .orElse(0L);
            Backlog backlog = new Backlog(out.pending(), oldestSeconds, out.failed(), dead);
            metrics.messagingBacklog(backlog.pending(), backlog.oldestPendingSeconds(), backlog.failed(), backlog.dead());
            return Optional.of(backlog);
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.CORE_MAINTENANCE, e);
            return Optional.empty();
        }
    }

    private void emit() {
        Totals current = totals();
        Totals previous = last;
        DbPoolWaitMonitor.Window window = pool.drainWindow();
        Optional<Backlog> backlog = latest;
        long cpuPercent = cpuSamples == 0 ? -1 : Math.round(cpuSum / cpuSamples * 100);
        long memoryPercent = memoryMax < 0 ? -1 : Math.round(memoryMax * 100);
        events.log(EventCode.METRIC_SNAPSHOT, EventLog.Component.CORE_MAINTENANCE, null, entry -> {
            backlog.ifPresent(value -> entry
                    .attr(AttrKey.OUTBOX_PENDING, value.pending())
                    .attr(AttrKey.OUTBOX_OLDEST_PENDING_SEC, value.oldestPendingSeconds())
                    .attr(AttrKey.OUTBOX_FAILED, value.failed())
                    .attr(AttrKey.INBOX_DEAD, value.dead()));
            entry.attr(AttrKey.POOL_ACTIVE, window.maxActive())
                    .attr(AttrKey.POOL_WAIT_MAX_MS, window.maxWait().toMillis())
                    .attr(AttrKey.POOL_WAIT_EXCEEDED, current.poolWaitExceeded() - previous.poolWaitExceeded())
                    .attr(AttrKey.REST_TOTAL, current.requests().rest() - previous.requests().rest())
                    .attr(AttrKey.REST_LE_300_MS, current.requests().restFast() - previous.requests().restFast())
                    .attr(AttrKey.VIEW_TOTAL, current.requests().view() - previous.requests().view())
                    .attr(AttrKey.VIEW_LE_500_MS, current.requests().viewFast() - previous.requests().viewFast())
                    .attr(AttrKey.VIEW_FALLBACK, current.viewFallback() - previous.viewFallback())
                    .attr(AttrKey.BREAKER_OPEN_SEC, roundedSeconds(current.breakerOpen())
                            - roundedSeconds(previous.breakerOpen()));
            if (cpuPercent >= 0) {
                entry.attr(AttrKey.SYS_CPU_AVG_PCT, cpuPercent);
            }
            if (memoryPercent >= 0) {
                entry.attr(AttrKey.SYS_MEM_MAX_PCT, memoryPercent);
            }
            system.heap().ifPresent(heap -> entry.attr(AttrKey.HEAP_USED_PCT, Math.round(heap * 100)));
        });
        last = current;
        cpuSum = 0;
        cpuSamples = 0;
        memoryMax = -1;
    }

    private Totals totals() {
        return new Totals(metrics.requestTotals(), Math.round(metrics.count(CoreMetrics.SESSION_VIEW_FALLBACK)),
                pool.exceededTotal(), breaker.openTimeTotal());
    }

    /** 누계를 반올림한 초. 구간마다 차를 내므로 경계에서 잘린 열림 시간도 합이 맞는다. */
    private static long roundedSeconds(Duration total) {
        return Math.round(total.toMillis() / 1000.0);
    }

    /** 구간 증가분을 내는 누계들. */
    private record Totals(RequestTotals requests, long viewFallback, long poolWaitExceeded, Duration breakerOpen) {
    }

    /** 적체 시점 값. */
    public record Backlog(long pending, long oldestPendingSeconds, long failed, long dead) {
    }
}
