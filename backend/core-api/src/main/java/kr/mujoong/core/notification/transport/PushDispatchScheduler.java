package kr.mujoong.core.notification.transport;

import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.startup.CoreStartupSequence;

import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class PushDispatchScheduler implements SmartLifecycle {
    private final PushDispatchWorker worker;
    private final PushDeliverySender sender;
    private final EventLogger events;
    private final Duration tick;
    private ThreadPoolTaskScheduler scheduler;

    public PushDispatchScheduler(
            PushDispatchWorker worker,
            PushDeliverySender sender,
            EventLogger events,
            CoreProperties properties) {
        this.worker = worker;
        this.sender = sender;
        this.events = events;
        this.tick = properties.timing().push().dispatchPoll();
    }

    @Override
    public synchronized void start() {
        if (scheduler != null || !sender.enabled()) return;
        var started = new ThreadPoolTaskScheduler();
        started.setPoolSize(1);
        started.setDaemon(true);
        started.setThreadNamePrefix("push-dispatch-");
        started.setWaitForTasksToCompleteOnShutdown(false);
        started.initialize();
        started.scheduleWithFixedDelay(this::runSafely, tick);
        scheduler = started;
    }

    void runSafely() {
        try {
            worker.runOnce();
        } catch (RuntimeException failure) {
            events.appError(EventLog.Component.CORE_MAINTENANCE, "PushDispatchFailure");
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    @Override
    public int getPhase() {
        return CoreStartupSequence.PHASE + 1;
    }
}
