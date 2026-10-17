package kr.mujoong.realtime.testing;

import java.util.ArrayList;
import java.util.List;
import kr.mujoong.realtime.runtime.TaskLauncher;

/** 시험용 작업 장치: 띄우라는 작업을 기록만 하고 시험이 {@link #runAll(String)}로 지금 스레드에서 돌린다(스레드 없음). */
public final class ManualLauncher implements TaskLauncher {

    /** 기록한 작업. */
    public record Task(String name, Runnable body) {
    }

    private final List<Task> tasks = new ArrayList<>();

    @Override
    public synchronized Thread launch(String name, Runnable task) {
        tasks.add(new Task(name, task));
        return null;
    }

    /**
     * 이름이 {@code prefix}로 시작하는 작업을 꺼내 돌린다. 돌린 수. 작업은 따로 띄운 스레드에서 돌고 이 호출은 끝나기를 기다린다 — 끊김 허가를
     * 기다리는 직렬 송신 작업(U11 W9)이 허가 없이 막혀 시험이 영영 멈추는 일을 막으려고, {@value #RUN_LIMIT_SECONDS}초 안에 끝나지 않으면
     * 작업을 중단하고 시험을 실패시킨다(시험은 먼저 시간을 앞당겨 끊김을 확인시켜야 한다).
     */
    public int runAll(String prefix) {
        List<Task> due;
        synchronized (this) {
            due = tasks.stream().filter(task -> task.name().startsWith(prefix)).toList();
            tasks.removeAll(due);
        }
        for (Task task : due) {
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = Thread.ofVirtual().name("manual-" + task.name()).start(() -> {
                try {
                    task.body().run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            try {
                worker.join(java.time.Duration.ofSeconds(RUN_LIMIT_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.interrupt();
                throw new AssertionError("시험 스레드가 중단됐다: " + task.name(), e);
            }
            if (worker.isAlive()) {
                worker.interrupt();
                throw new AssertionError("작업이 " + RUN_LIMIT_SECONDS + "초 안에 끝나지 않았다(끊김 허가를 기다리는 송신 작업은 먼저 시간을 앞당겨"
                        + " 끊김을 확인시켜야 한다): " + task.name());
            }
            Throwable thrown = failure.get();
            if (thrown instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (thrown instanceof Error error) {
                throw error;
            }
            if (thrown != null) {
                throw new IllegalStateException(thrown);
            }
        }
        return due.size();
    }

    private static final int RUN_LIMIT_SECONDS = 5;

    /** 기록한 작업 이름들. */
    public synchronized List<String> names() {
        return new ArrayList<>(tasks.stream().map(Task::name).toList());
    }
}
