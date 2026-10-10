package kr.mujoong.core.startup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.PreparingTimeoutChecker;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.device.DeviceExpiryCleanupJob;
import kr.mujoong.core.health.DbHealthChecker;
import kr.mujoong.core.health.ReadinessState;
import kr.mujoong.core.messaging.ControlEventRearm;
import kr.mujoong.core.messaging.CoreInboxDispatcher;
import kr.mujoong.core.messaging.CoreInboxRepoller;
import kr.mujoong.core.messaging.CoreInboxRepository;
import kr.mujoong.core.messaging.CoreOutboxRepository;
import kr.mujoong.core.messaging.CoreOutboxSender;
import kr.mujoong.core.messaging.MessagingCleanupJob;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Core 앱 안 기동 순서 S2 ~ S4 · S6 (U1 3.5 Step 21 · RD-12 · BR5.12 · A1 · R3-5 · J124).
 *
 * <p>웹 서버 시작 단계(phase {@code Integer.MAX_VALUE - 2048})보다 먼저 도는 SmartLifecycle이라 HTTP 수신(S5)이 열리기 전에 끝난다.
 * S1(설정 바인딩 · 프로필 가드 · 필수 비밀)은 그보다 앞의 환경 후처리기와 빈 생성 단계가 맡는다.
 *
 * <ol>
 *   <li>S2: 이력 버전 확인({@link SchemaVersionCheck}). JPA validate는 빈 생성 때 끝났다.
 *   <li>S3c: Inbox RECEIVED 전부의 next_attempt_at을 now로 당긴다(retry_count 유지, BR5.12).
 *   <li>S3d: FAILED 제어 이벤트 재무장(A1).
 *   <li>S3f: 크래시로 남은 Outbox 선점 · 재시도 대기를 걷는다(R3-5).
 *   <li>S3e: STARTUP_RECOVERY 한 줄(실패해도 기동은 계속).
 *   <li>S4: outbox-sender · inbox-repoll · maintenance 스케줄러를 고정 지연으로 시작한다(처리 풀은 디스패처가 가진다).
 *   <li>S6: 준비 상태 READY.
 * </ol>
 *
 * <p>S2 ~ S4 실패는 예외로 기동을 끝낸다(종료 코드 1). 정지는 {@link DrainingLifecycle}이 먼저 부르고 이 빈의 stop이 한 번 더 확인한다.
 */
@Component
public class CoreStartupSequence implements SmartLifecycle {

    /** 웹 서버 시작(MAX − 2048)보다 앞. */
    public static final int PHASE = Integer.MAX_VALUE - 4096;

    private final SchemaVersionCheck schemaVersionCheck;
    private final CoreInboxRepository inbox;
    private final CoreOutboxRepository outbox;
    private final ControlEventRearm rearm;
    private final CoreOutboxSender sender;
    private final CoreInboxRepoller repoller;
    private final CoreInboxDispatcher dispatcher;
    private final PreparingTimeoutChecker preparingTimeoutChecker;
    private final DbHealthChecker dbHealthChecker;
    private final DeviceExpiryCleanupJob deviceExpiryCleanupJob;
    private final MessagingCleanupJob messagingCleanupJob;
    private final EventLogger events;
    private final ReadinessState readiness;
    private final MujoongClock clock;
    private final CoreProperties properties;

    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();
    private volatile boolean running;

    public CoreStartupSequence(
            SchemaVersionCheck schemaVersionCheck,
            CoreInboxRepository inbox,
            CoreOutboxRepository outbox,
            ControlEventRearm rearm,
            CoreOutboxSender sender,
            CoreInboxRepoller repoller,
            CoreInboxDispatcher dispatcher,
            PreparingTimeoutChecker preparingTimeoutChecker,
            DbHealthChecker dbHealthChecker,
            DeviceExpiryCleanupJob deviceExpiryCleanupJob,
            MessagingCleanupJob messagingCleanupJob,
            EventLogger events,
            ReadinessState readiness,
            MujoongClock clock,
            CoreProperties properties) {
        this.schemaVersionCheck = Objects.requireNonNull(schemaVersionCheck, "schemaVersionCheck");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.rearm = Objects.requireNonNull(rearm, "rearm");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.repoller = Objects.requireNonNull(repoller, "repoller");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.preparingTimeoutChecker = Objects.requireNonNull(preparingTimeoutChecker, "preparingTimeoutChecker");
        this.dbHealthChecker = Objects.requireNonNull(dbHealthChecker, "dbHealthChecker");
        this.deviceExpiryCleanupJob = Objects.requireNonNull(deviceExpiryCleanupJob, "deviceExpiryCleanupJob");
        this.messagingCleanupJob = Objects.requireNonNull(messagingCleanupJob, "messagingCleanupJob");
        this.events = Objects.requireNonNull(events, "events");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void start() {
        schemaVersionCheck.verify();
        Recovery recovery = recover();
        logRecovery(recovery);
        startSchedulers();
        running = true;
        readiness.markReady();
    }

    /** S3c · S3d · S3f(각 문장 멱등). */
    public Recovery recover() {
        Instant now = clock.now();
        int reopened = inbox.pullForwardReceived(now);
        int rearmed = rearm.rearmOnStart(now);
        int released = outbox.releaseAllClaims(now);
        return new Recovery(reopened, rearmed, released);
    }

    private void logRecovery(Recovery recovery) {
        try {
            events.log(EventCode.STARTUP_RECOVERY, EventLog.Component.CORE_MAINTENANCE, null, entry -> entry
                    .attr(AttrKey.RECOVERED_SESSIONS, 0L)
                    .attr(AttrKey.REOPENED_INBOX_ROWS, (long) recovery.reopenedInboxRows())
                    .attr(AttrKey.RESET_CONNECTIONS, 0L)
                    .attr(AttrKey.REARMED_CONTROL_EVENTS, (long) recovery.rearmedControlEvents()));
        } catch (RuntimeException e) {
            // S3e 실패는 기동을 막지 않는다(RD-12 표)
            events.appError(EventLog.Component.CORE_MAINTENANCE, e);
        }
    }

    private synchronized void startSchedulers() {
        CoreProperties.Outbox outboxConfig = properties.outbox();
        ScheduledExecutorService outboxSender = scheduler("outbox-sender");
        fixedDelay(outboxSender, outboxConfig.pollInterval(), EventLog.Component.CORE_MESSAGING, sender::runCycle);

        ScheduledExecutorService inboxRepoll = scheduler("inbox-repoll");
        fixedDelay(inboxRepoll, properties.inbox().pollInterval(), EventLog.Component.CORE_MESSAGING, repoller::runOnce);

        ScheduledExecutorService maintenance = scheduler("maintenance");
        fixedDelay(maintenance, properties.session().timerTick(), EventLog.Component.ACTIVITY_COORDINATOR,
                preparingTimeoutChecker::runOnce);
        fixedDelay(maintenance, properties.health().dbCheckInterval(), EventLog.Component.HEALTH, dbHealthChecker::runOnce);
        fixedDelay(maintenance, properties.cleanup().deviceExpiryInterval(), EventLog.Component.DEVICE_REGISTRY,
                deviceExpiryCleanupJob::runOnce);
        fixedDelay(maintenance, properties.cleanup().messagingInterval(), EventLog.Component.CORE_MESSAGING,
                messagingCleanupJob::runOnce);
        fixedDelay(maintenance, properties.observability().logMergeWindow(), EventLog.Component.CORE_MAINTENANCE,
                events::flushMerged);
    }

    private ScheduledExecutorService scheduler(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        schedulers.add(executor);
        return executor;
    }

    /** 예외가 나도 다음 주기가 돌게 감싼다(ScheduledExecutorService는 예외 뒤 반복을 멈춘다). */
    private void fixedDelay(
            ScheduledExecutorService executor, Duration delay, EventLog.Component component, Runnable task) {
        executor.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                events.appError(component, e);
            }
        }, delay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * T4: 스케줄러를 멈춘다. 발송기는 진행 중 한 건을 마치고 남은 선점을 되돌린다. 진행 중 작업은 발행 제한 + 문장 제한까지 기다린다. 멱등이다.
     */
    public void stopSchedulers() {
        sender.requestStop();
        List<ScheduledExecutorService> toStop;
        synchronized (this) {
            toStop = new ArrayList<>(schedulers);
            schedulers.clear();
        }
        for (ScheduledExecutorService executor : toStop) {
            executor.shutdown();
        }
        dispatcher.shutdown();
        Duration grace = properties.internalHttp().eventPublishTimeout().plus(properties.limits().dbStatementTimeout());
        try {
            for (ScheduledExecutorService executor : toStop) {
                executor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS);
            }
            dispatcher.awaitTermination(grace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public void stop() {
        stopSchedulers();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** 시작 복구 결과(S3e 줄의 값). */
    public record Recovery(int reopenedInboxRows, int rearmedControlEvents, int releasedOutboxClaims) {
    }
}
