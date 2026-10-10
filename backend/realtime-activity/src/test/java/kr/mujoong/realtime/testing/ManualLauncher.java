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

    /** 이름이 {@code prefix}로 시작하는 작업을 꺼내 돌린다. 돌린 수. */
    public int runAll(String prefix) {
        List<Task> due;
        synchronized (this) {
            due = tasks.stream().filter(task -> task.name().startsWith(prefix)).toList();
            tasks.removeAll(due);
        }
        due.forEach(task -> task.body().run());
        return due.size();
    }

    /** 기록한 작업 이름들. */
    public synchronized List<String> names() {
        return new ArrayList<>(tasks.stream().map(Task::name).toList());
    }
}
