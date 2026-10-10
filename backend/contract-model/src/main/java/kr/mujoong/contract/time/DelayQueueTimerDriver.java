package kr.mujoong.contract.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 운영 타이머 구동기(RD-2 · RD-1 · NFR7.3). 데몬 플랫폼 스레드 하나가 {@link DelayQueue}에서 마감이 된 작업을 꺼내 실행한다.
 * Realtime은 안전 시한 시계 · 구간 조용 시간 시계 두 개를 쓴다(reliability-design.md 2.2 스레드 표).
 *
 * <p>남은 시간은 주입한 {@link MujoongClock}으로 계산한다. 마감이 같으면 등록 순서대로 실행한다. 작업의 예외는 스레드를 죽이지
 * 않고 오류 처리기로 넘긴다(기본은 경고 로그 한 줄, 메시지 · 스택은 로그 변환에서 버려진다, OD-3).
 */
public final class DelayQueueTimerDriver implements TimerDriver {

    private static final Logger LOG = LoggerFactory.getLogger(DelayQueueTimerDriver.class);

    private final MujoongClock clock;
    private final DelayQueue<Entry> queue = new DelayQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<RuntimeException> errorHandler;
    private final Thread worker;

    /** 기본 오류 처리기(경고 로그)로 만든다. */
    public DelayQueueTimerDriver(MujoongClock clock, String threadName) {
        this(clock, threadName, failure -> LOG.warn("타이머 작업 실패", failure));
    }

    /**
     * @param clock 시각 원천
     * @param threadName 데몬 스레드 이름(예: {@code realtime-safety-timer})
     * @param errorHandler 작업 예외 처리기
     */
    public DelayQueueTimerDriver(MujoongClock clock, String threadName, Consumer<RuntimeException> errorHandler) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.worker = new Thread(this::runLoop, Objects.requireNonNull(threadName, "threadName"));
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public TimerHandle schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay");
        return scheduleAt(clock.now().plus(delay), task);
    }

    @Override
    public TimerHandle scheduleAt(Instant deadline, Runnable task) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(task, "task");
        if (closed.get()) {
            throw new IllegalStateException("닫힌 타이머 구동기에는 등록할 수 없다");
        }
        Entry entry = new Entry(deadline, sequence.getAndIncrement(), task);
        queue.add(entry);
        return entry;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear();
            worker.interrupt();
        }
    }

    /** 닫혔는가. */
    public boolean isClosed() {
        return closed.get();
    }

    private void runLoop() {
        while (!closed.get()) {
            Entry entry;
            try {
                entry = queue.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed.get()) {
                return;
            }
            entry.fire(errorHandler);
        }
    }

    private final class Entry implements Delayed, TimerHandle {

        private final Instant deadline;
        private final long order;
        private final Runnable task;
        private final AtomicBoolean state = new AtomicBoolean();
        private volatile boolean cancelled;

        Entry(Instant deadline, long order, Runnable task) {
            this.deadline = deadline;
            this.order = order;
            this.task = task;
        }

        void fire(Consumer<RuntimeException> handler) {
            if (!state.compareAndSet(false, true)) {
                return;
            }
            try {
                task.run();
            } catch (RuntimeException failure) {
                handler.accept(failure);
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long nanos = Duration.between(clock.now(), deadline).toNanos();
            return unit.convert(nanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other instanceof Entry that) {
                int byDeadline = deadline.compareTo(that.deadline);
                return byDeadline != 0 ? byDeadline : Long.compare(order, that.order);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public Instant deadline() {
            return deadline;
        }

        @Override
        public boolean cancel() {
            if (state.compareAndSet(false, true)) {
                cancelled = true;
                queue.remove(this);
                return true;
            }
            return false;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
