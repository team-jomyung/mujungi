package kr.mujoong.core.account;

import java.time.Duration;
import java.util.Objects;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.startup.CoreStartupSequence;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 계정 삭제 지휘 주기 실행 (U2 AccountDeletion · R11 · U2-J18).
 *
 * <p>Core 기동 순서(S1 ~ S5, {@link CoreStartupSequence}) 뒤에 시작해 {@code mujoong.timing.account.deletion-tick}(5초)마다
 * {@link DeletionCommander#runOnce}를 부른다(고정 지연, 한 스레드). 예외는 APP_ERROR 한 줄로 남기고 다음 주기를 돈다. 정상 종료 때 진행 중
 * 한 바퀴는 마치게 둔다(각 단계 멱등 · 선점 시한 뒤 다시 집힌다). 여러 인스턴스가 함께 돌아도 요청 선점(SKIP LOCKED)이 한 곳만 처리하게 한다.
 */
@Component
public class AccountDeletionScheduler implements SmartLifecycle {

    /** Core 기동 순서 뒤(웹 서버 시작보다는 앞). */
    public static final int PHASE = CoreStartupSequence.PHASE + 1;

    private final DeletionCommander commander;
    private final EventLogger events;
    private final Duration tick;
    private ThreadPoolTaskScheduler scheduler;

    public AccountDeletionScheduler(DeletionCommander commander, EventLogger events, CoreProperties properties) {
        this.commander = Objects.requireNonNull(commander, "commander");
        this.events = Objects.requireNonNull(events, "events");
        this.tick = properties.timing().account().deletionTick();
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        ThreadPoolTaskScheduler started = new ThreadPoolTaskScheduler();
        started.setPoolSize(1);
        started.setThreadNamePrefix("account-deletion-");
        started.setDaemon(true);
        started.setWaitForTasksToCompleteOnShutdown(false);
        started.initialize();
        started.scheduleWithFixedDelay(this::runSafely, tick);
        scheduler = started;
    }

    void runSafely() {
        try {
            commander.runOnce();
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.CORE_MAINTENANCE, e);
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
        return PHASE;
    }
}
