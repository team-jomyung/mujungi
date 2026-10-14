package kr.mujoong.realtime.practice;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.TimerDriver;
import kr.mujoong.realtime.session.PracticeSessionExtension.PracticeOutput;

/** 실행기의 일시정지 가능한 시계로 예약하고 같은 직렬 큐에서 실행한다. */
final class SessionPracticeTimers implements TimerDriver {
    private final PracticeOutput output;
    private final MujoongClock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long sequence;
    private boolean closed;
    SessionPracticeTimers(PracticeOutput output, MujoongClock clock) { this.output = output; this.clock = clock; }
    @Override public TimerHandle schedule(Duration delay, Runnable task) { return scheduleAt(clock.now().plus(delay), task); }
    @Override public TimerHandle scheduleAt(Instant at, Runnable task) {
        if (closed) throw new IllegalStateException("닫힌 연습 시계");
        String name = "u5b-" + ++sequence;
        var entry = new Entry(name, at, task); entries.put(name, entry);
        if (!output.schedule(name, Duration.between(clock.now(), at))) { entries.remove(name); entry.cancelled = true; }
        return entry;
    }
    void fire(String name) {
        Entry entry = entries.remove(name);
        if (entry != null && !entry.cancelled) { entry.fired = true; entry.task.run(); }
    }
    @Override public void close() {
        closed = true;
        for (var entry : java.util.List.copyOf(entries.values())) entry.cancel();
    }
    private final class Entry implements TimerHandle {
        final String name; final Instant at; final Runnable task;
        boolean cancelled, fired;
        Entry(String name, Instant at, Runnable task) { this.name = name; this.at = at; this.task = task; }
        @Override public Instant deadline() { return at; }
        @Override public boolean cancel() {
            if (cancelled || fired) return false;
            cancelled = true; entries.remove(name); output.cancel(name); return true;
        }
        @Override public boolean isCancelled() { return cancelled; }
    }
}
