package kr.mujoong.testsupport.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import kr.mujoong.contract.time.TimerDriver;

/**
 * 시험용 수동 타이머 구동기(RD-2 · RD-23 · NFR6.2). {@link #advance(Duration)}가 {@link MutableClock}을 앞당기며 도래한 타이머를 마감
 * 순서대로(마감이 같으면 등록 순서대로) 즉시 실행한다. 실제 시간을 기다리지 않는다.
 *
 * <p>작업을 실행할 때 Clock은 그 작업의 마감 시각에 맞춰져 있다(지난 마감은 현재 시각 그대로). 작업이 새 타이머를 걸고 그 마감이
 * 이번 앞당김 안이면 같은 호출에서 실행한다. 작업은 잠금 밖에서 실행하므로 다른 스레드가 동시에 등록해도 막히지 않는다.
 * 세션 편지함 비우기 같은 앱 쪽 후처리는 시험이 {@link #advance(Duration)} 뒤에 직접 한다.
 */
public final class ManualTimerDriver implements TimerDriver {

    private final MutableClock clock;
    private final PriorityQueue<Entry> queue = new PriorityQueue<>();
    private long sequence;
    private boolean closed;

    public ManualTimerDriver(MutableClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 이 구동기의 Clock. */
    public MutableClock clock() {
        return clock;
    }

    @Override
    public TimerHandle schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay");
        return scheduleAt(clock.now().plus(delay), task);
    }

    @Override
    public synchronized TimerHandle scheduleAt(Instant deadline, Runnable task) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(task, "task");
        if (closed) {
            throw new IllegalStateException("닫힌 타이머 구동기에는 등록할 수 없다");
        }
        Entry entry = new Entry(deadline, sequence++, task);
        queue.add(entry);
        return entry;
    }

    /**
     * Clock을 {@code duration}만큼 앞당기며 그 안에 도래한 타이머를 마감 순서대로 실행한다.
     *
     * @return 실행한 작업 수
     */
    public int advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("음수만큼 앞당길 수 없다");
        }
        Instant target = clock.now().plus(duration);
        int executed = 0;
        while (true) {
            Entry next = pollDue(target);
            if (next == null) {
                break;
            }
            Duration gap = Duration.between(clock.now(), next.deadline);
            if (gap.isPositive()) {
                clock.advance(gap);
            }
            next.task.run();
            executed++;
        }
        Duration rest = Duration.between(clock.now(), target);
        if (rest.isPositive()) {
            clock.advance(rest);
        }
        return executed;
    }

    /** 이미 도래한(마감 ≤ 지금) 타이머만 실행한다. {@code advance(Duration.ZERO)}와 같다. */
    public int runDue() {
        return advance(Duration.ZERO);
    }

    /** 다음(가장 이른) 타이머의 마감까지 앞당겨 그 타이머들을 실행한다. 없으면 0. */
    public int advanceToNext() {
        Optional<Instant> next = nextDeadline();
        if (next.isEmpty()) {
            return 0;
        }
        Duration gap = Duration.between(clock.now(), next.get());
        return advance(gap.isNegative() ? Duration.ZERO : gap);
    }

    /** 기다리는(취소되지 않은) 타이머 수. */
    public synchronized int pendingCount() {
        return (int) queue.stream().filter(entry -> !entry.done).count();
    }

    /** 가장 이른 마감. */
    public synchronized Optional<Instant> nextDeadline() {
        return queue.stream().filter(entry -> !entry.done).map(Entry::deadline).min(Instant::compareTo);
    }

    @Override
    public synchronized void close() {
        closed = true;
        queue.forEach(entry -> entry.done = true);
        queue.clear();
    }

    private synchronized Entry pollDue(Instant target) {
        while (!queue.isEmpty()) {
            Entry head = queue.peek();
            if (head.done) {
                queue.poll();
                continue;
            }
            if (head.deadline.isAfter(target)) {
                return null;
            }
            queue.poll();
            head.done = true;
            return head;
        }
        return null;
    }

    private final class Entry implements Comparable<Entry>, TimerHandle {

        private final Instant deadline;
        private final long order;
        private final Runnable task;
        private boolean done;
        private boolean cancelled;

        Entry(Instant deadline, long order, Runnable task) {
            this.deadline = deadline;
            this.order = order;
            this.task = task;
        }

        @Override
        public int compareTo(Entry other) {
            int byDeadline = deadline.compareTo(other.deadline);
            return byDeadline != 0 ? byDeadline : Long.compare(order, other.order);
        }

        @Override
        public Instant deadline() {
            return deadline;
        }

        @Override
        public boolean cancel() {
            synchronized (ManualTimerDriver.this) {
                if (done) {
                    return false;
                }
                done = true;
                cancelled = true;
                queue.remove(this);
                return true;
            }
        }

        @Override
        public boolean isCancelled() {
            synchronized (ManualTimerDriver.this) {
                return cancelled;
            }
        }
    }
}
