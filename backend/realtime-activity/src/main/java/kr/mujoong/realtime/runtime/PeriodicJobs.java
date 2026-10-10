package kr.mujoong.realtime.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Realtime 주기 작업 스레드 (U1 3.5 Step 23 · RD-1 · RD-2 · R3-18 · reliability-design 2.2 스레드 수 표).
 *
 * <p>이름 있는 단일 플랫폼 스레드 스케줄러를 만들고 작업을 고정 지연(앞 실행이 끝난 뒤 간격)으로 건다. Realtime이 쓰는 이름은
 * {@value #OUTBOX_SENDER} · {@value #INBOX_REPOLL} · {@value #MAINTENANCE} · {@value #RECORDER_TICK} 넷이다(시계 두 개는
 * {@link TimerClocks}). 작업 예외는 오류 처리기로 넘기고 다음 주기를 계속 돈다(ScheduledExecutorService는 예외 뒤 반복을 멈추므로 감싼다).
 * 시작 순서는 기동 S4가 정하고 정지는 정상 종료 T4가 {@link #stopAll(Duration)}로 한다.
 */
public final class PeriodicJobs {

    /** Outbox 발송(RD-8). */
    public static final String OUTBOX_SENDER = "outbox-sender";
    /** Inbox 재호출(RD-9). */
    public static final String INBOX_REPOLL = "inbox-repoll";
    /** 점검 · 보고 · 정리 · 고아 세션 점검 · DB 점검(RD-1 주기 작업). */
    public static final String MAINTENANCE = "maintenance";
    /** 기록기 플러시 제출(PD-5). */
    public static final String RECORDER_TICK = "recorder-tick";

    private final Consumer<RuntimeException> errorHandler;
    private final Map<String, ScheduledExecutorService> schedulers = new LinkedHashMap<>();
    private boolean stopped;

    /**
     * @param errorHandler 작업 예외 처리기(APP_ERROR 한 줄 등). 처리기 자신은 던지지 않아야 한다
     */
    public PeriodicJobs(Consumer<RuntimeException> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    /**
     * {@code thread} 이름의 스레드에 고정 지연 작업을 건다. 첫 실행은 {@code interval} 뒤다.
     *
     * @throws IllegalStateException 이미 멈췄으면
     */
    public synchronized void fixedDelay(String thread, Duration interval, Runnable task) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(task, "task");
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("주기는 0보다 커야 한다: " + thread);
        }
        if (stopped) {
            throw new IllegalStateException("멈춘 주기 작업에는 등록할 수 없다");
        }
        ScheduledExecutorService executor = schedulers.computeIfAbsent(thread, PeriodicJobs::newScheduler);
        long millis = Math.max(1L, interval.toMillis());
        executor.scheduleWithFixedDelay(() -> runSafely(task), millis, millis, TimeUnit.MILLISECONDS);
    }

    /** 지금 있는 스레드 이름(시작한 순서). */
    public synchronized List<String> threadNames() {
        return List.copyOf(schedulers.keySet());
    }

    /** 멈췄는가. */
    public synchronized boolean isStopped() {
        return stopped;
    }

    /**
     * 새 주기를 막고 진행 중 실행이 끝나기를 {@code grace}까지 기다린다. 두 번 불러도 된다.
     *
     * @return 모든 스레드가 기한 안에 끝났으면 true
     */
    public boolean stopAll(Duration grace) {
        List<ScheduledExecutorService> toStop;
        synchronized (this) {
            stopped = true;
            toStop = new ArrayList<>(schedulers.values());
            schedulers.clear();
        }
        toStop.forEach(ScheduledExecutorService::shutdown);
        boolean finished = true;
        try {
            for (ScheduledExecutorService executor : toStop) {
                finished &= executor.awaitTermination(Math.max(0L, grace.toMillis()), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return finished;
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            try {
                errorHandler.accept(failure);
            } catch (RuntimeException ignored) {
                // 오류 처리기의 실패로 주기가 멈추지 않게 한다(오류 줄은 이미 시도했다)
            }
        }
    }

    private static ScheduledExecutorService newScheduler(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
