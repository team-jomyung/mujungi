package kr.mujoong.core.notification;

import java.time.Duration;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.startup.CoreStartupSequence;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class PushStaleScheduler implements SmartLifecycle {
    private final PushStaleScanner scanner;
    private final EventLogger events;
    private final Duration tick;
    private ThreadPoolTaskScheduler scheduler;

    public PushStaleScheduler(PushStaleScanner scanner, EventLogger events, CoreProperties properties) {
        this.scanner = scanner;
        this.events = events;
        this.tick = properties.timing().push().deviceStatusCheck();
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) return;
        var started = new ThreadPoolTaskScheduler();
        started.setPoolSize(1);
        started.setThreadNamePrefix("push-stale-");
        started.setDaemon(true);
        started.setWaitForTasksToCompleteOnShutdown(false);
        started.initialize();
        started.scheduleWithFixedDelay(this::runSafely, tick);
        scheduler = started;
    }

    void runSafely() {
        try {
            scanner.runOnce();
        } catch (RuntimeException failure) {
            events.appError(EventLog.Component.CORE_MAINTENANCE, "PushStaleScanFailure");
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
    public synchronized boolean isRunning() { return scheduler != null; }

    @Override
    public int getPhase() { return CoreStartupSequence.PHASE + 1; }
}
