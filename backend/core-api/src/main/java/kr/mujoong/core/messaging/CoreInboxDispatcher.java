package kr.mujoong.core.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.config.CoreInboxProperties;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import kr.mujoong.core.messaging.handler.InboxHandler;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Core Inbox 디스패처 (U1 3.5 Step 20 · ADR-010 · OD-7).
 *
 * <p>두 입구(수신 직후 제출 · 재호출 폴러)가 같은 문 {@link #process}로 들어온다. 문은 선점 UPDATE(next_attempt_at = now + claim-lease,
 * 조건 RECEIVED이고 도래)이고 갱신 행 수가 1인 호출만 처리기를 부른다. 처리기는 트랜잭션 안에서 업무 변경을 하고 같은 트랜잭션이
 * PROCESSED(payload 비움)를 쓴다. 뒷일(상대 앱 호출)은 커밋 뒤 트랜잭션 밖에서 하고 성공하면 짧은 문장으로 PROCESSED를 쓴다.
 *
 * <p>실패: retry_count + 1, next_attempt_at = now + 대기표(1 · 2 · 4 · 8 · 16초). retry_count가 이미 max-retries(5)인 행의
 * 6번째 실패는 DEAD(retry_count 5 유지, last_error는 예외 종류만, 경고 한 줄). 처리 풀은 {@code mujoong.inbox.handler-threads}개 ·
 * 대기열 {@code batch-size}칸이고 가득 차면 제출을 건너뛴다(AbortPolicy, 호출자 실행 금지 — 폴러가 다시 집는다). MDC traceId는 행의
 * trace_id다(OD-7 인과 사슬).
 */
@Component
public class CoreInboxDispatcher implements DisposableBean {

    /** 처리 결과. */
    public enum Outcome {
        /** 다른 호출이 선점했거나 대상이 아니다. */
        SKIPPED,
        PROCESSED,
        HANDLER_RETRY,
        DEAD
    }

    private final CoreInboxRepository inbox;
    private final Map<EventType, InboxHandler> handlers = new EnumMap<>(EventType.class);
    private final TransactionTemplate transactions;
    private final EventLogger events;
    private final CoreMetrics metrics;
    private final MujoongClock clock;
    private final List<Duration> retryBackoff;
    private final int maxRetries;
    private final Duration claimLease;
    private final ThreadPoolExecutor pool;

    @Autowired
    public CoreInboxDispatcher(
            CoreInboxRepository inbox,
            List<InboxHandler> handlers,
            PlatformTransactionManager transactionManager,
            EventLogger events,
            CoreMetrics metrics,
            MujoongClock clock,
            CoreProperties properties) {
        this(inbox, handlers, transactionManager, events, metrics, clock, properties.inbox());
    }

    public CoreInboxDispatcher(
            CoreInboxRepository inbox,
            List<InboxHandler> handlers,
            PlatformTransactionManager transactionManager,
            EventLogger events,
            CoreMetrics metrics,
            MujoongClock clock,
            CoreInboxProperties config) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        for (InboxHandler handler : Objects.requireNonNull(handlers, "handlers")) {
            if (handlers.stream().filter(h -> h.eventType() == handler.eventType()).count() > 1) {
                throw new IllegalStateException("한 이벤트 종류에 처리기가 둘이다: " + handler.eventType());
            }
            this.handlers.put(handler.eventType(), handler);
        }
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryBackoff = List.copyOf(config.retryBackoff());
        this.maxRetries = config.maxRetries();
        this.claimLease = config.claimLease();
        AtomicInteger threadNumber = new AtomicInteger();
        // keepAlive 0은 시간 값이 아니다: core = max인 고정 풀이라 유휴 스레드를 줄이지 않고 이 값은 쓰이지 않는다(시간 상수 검사 허용 목록)
        this.pool = new ThreadPoolExecutor(config.handlerThreads(), config.handlerThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.batchSize()),
                runnable -> {
                    Thread thread = new Thread(runnable, "inbox-handler-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 이 종류의 처리기가 등록돼 있는가. */
    public boolean hasHandler(EventType type) {
        return handlers.containsKey(type);
    }

    /**
     * 처리 풀에 제출한다. 풀이 가득 찼거나 멈췄으면 건너뛴다(재호출 폴러가 next_attempt_at 도래 행을 다시 집는다).
     *
     * @return 제출했으면 true
     */
    public boolean submit(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        try {
            pool.execute(() -> processSafely(eventId));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** 처리 풀을 멈춘다(T4: 진행 중 처리는 마친다). */
    public void shutdown() {
        pool.shutdown();
    }

    /** 처리 풀이 진행 중 처리를 마칠 때까지 기다린다. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        pool.shutdownNow();
    }

    /** 선점에 성공하면 처리기를 부른다(같은 행을 두 입구가 동시에 불러도 한 번만 처리). */
    public Outcome process(UUID eventId) {
        Instant claimedAt = clock.now();
        if (inbox.claim(eventId, claimedAt, claimLease) != 1) {
            return Outcome.SKIPPED;
        }
        Optional<InboxEvent> found = inbox.findById(eventId);
        if (found.isEmpty() || !"RECEIVED".equals(found.get().status())) {
            return Outcome.SKIPPED;
        }
        InboxEvent row = found.get();
        String previousTrace = MDC.get(TraceIds.MDC_KEY);
        MDC.put(TraceIds.MDC_KEY, row.traceId() != null ? row.traceId() : TraceIds.newTraceId());
        try {
            return handle(row);
        } finally {
            if (previousTrace == null) {
                MDC.remove(TraceIds.MDC_KEY);
            } else {
                MDC.put(TraceIds.MDC_KEY, previousTrace);
            }
        }
    }

    private void processSafely(UUID eventId) {
        try {
            process(eventId);
        } catch (RuntimeException e) {
            // 선점 · 기록 단계의 DB 오류: 행은 선점 시간 뒤 폴러가 다시 집는다
            events.appError(EventLog.Component.CORE_MESSAGING, e);
        }
    }

    private Outcome handle(InboxEvent row) {
        EventType type = EventType.fromWire(row.eventType());
        InboxHandler handler = handlers.get(type);
        if (handler == null) {
            // 수신 때는 처리기가 있었는데 지금은 없다(배포로 빠짐): 실패로 세어 DEAD까지 간다
            return fail(row, "NoHandler");
        }
        try {
            Instant now = clock.now();
            InboxHandler.Result result = transactions.execute(status -> {
                InboxHandler.Result handled = Objects.requireNonNull(handler.handle(row, now), "처리기 결과");
                if (handled.followUp().isEmpty()) {
                    markProcessed(row, now);
                }
                return handled;
            });
            if (result != null && result.followUp().isPresent()) {
                result.followUp().get().run();
                markProcessed(row, clock.now());
            }
        } catch (Exception e) {
            return fail(row, e.getClass().getSimpleName());
        }
        Duration delay = Duration.between(row.occurredAt(), clock.now());
        metrics.eventDelivered(row.eventType(), delay);
        log(row, "PROCESSED", row.retryCount(), Math.max(0L, delay.toMillis()));
        return Outcome.PROCESSED;
    }

    private void markProcessed(InboxEvent row, Instant now) {
        if (inbox.markProcessed(row.eventId(), now) != 1) {
            throw new IllegalStateException("PROCESSED 표시가 0행이다");
        }
    }

    private Outcome fail(InboxEvent row, String errorKind) {
        Instant now = clock.now();
        if (row.retryCount() >= maxRetries) {
            inbox.markDead(row.eventId(), errorKind);
            log(row, "DEAD", row.retryCount(), null);
            return Outcome.DEAD;
        }
        int next = row.retryCount() + 1;
        inbox.recordHandlerFailure(row.eventId(), row.retryCount(), now.plus(CoreOutboxSender.backoff(retryBackoff, next)),
                errorKind);
        log(row, "HANDLER_RETRY", next, null);
        return Outcome.HANDLER_RETRY;
    }

    private void log(InboxEvent row, String status, int retryCount, Long durationMs) {
        events.log(EventCode.EVENT_DELIVERY, EventLog.Component.CORE_MESSAGING, row.aggregateId(), entry -> {
            entry.attr(AttrKey.EVENT_ID, row.eventId()).attr(AttrKey.STATUS, status)
                    .attr(AttrKey.RETRY_COUNT, (long) retryCount);
            EventType type = EventType.fromWire(row.eventType());
            if (type != EventType.UNRECOGNIZED) {
                entry.attr(AttrKey.EVENT_TYPE, type.wire());
            }
            if (durationMs != null) {
                entry.attr(AttrKey.DURATION_MS, durationMs);
            }
        });
    }
}
