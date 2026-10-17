package kr.mujoong.realtime.session;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.voice.ProviderPort.ProviderSession;
import kr.mujoong.realtime.voice.ProviderPort.ProviderUnavailableException;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/**
 * 공급자 직렬 송신 큐 (U11 W9, <b>설계 개정 A2</b>로 단순해졌다). 서버가 보내는 지시({@code session.instructions.append})는 모두 이 세션 큐 하나를
 * 지난다 — 지시 순서를 지키는 일만 한다. 공급자에는 응답 요청 type이 없어서 지시가 곧 발화다. 끊김 허가 · 송신 직전 대조 · '보내는 중' 표시 · 송신 중단
 * 사건은 없다: 실행기가 지시를 큐에 넣는 순간 출력 세대를 올려 낡은 조각을 버리고(DISCARDING), 새 말의 시작은 확인 사건 · 전사 시간선으로 찾는다.
 *
 * <p>큐 작업은 별도 스레드(운영은 가상 스레드, 시험은 수동 장치)에서 하나씩 돈다. 공급자 송신 실패는 그 작업의 {@code onFailure}로 알린다(사유는 로그에
 * 남기지 않는다 — 끊김은 리더의 닫힘 사건으로 따로 온다).
 */
final class ProviderSendQueue {

    /** 송신 작업 하나. */
    static final class Job {
        final String name;
        final ProviderSession session;
        final ProviderRequestFactory.InstructionsAppend append;
        /** 보내지 못했을 때 부를 일(없으면 null). 큐 스레드에서 불리므로 스레드 안전한 일만 한다(예: 편지함에 넣기). */
        final Runnable onFailure;

        Job(String name, ProviderSession session, ProviderRequestFactory.InstructionsAppend append, Runnable onFailure) {
            this.name = Objects.requireNonNull(name, "name");
            this.session = Objects.requireNonNull(session, "session");
            this.append = Objects.requireNonNull(append, "append");
            this.onFailure = onFailure;
        }
    }

    private final TaskLauncher launcher;
    private final String label;
    private final Consumer<Throwable> onUnexpected;
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private boolean running;
    private boolean shutdown;

    ProviderSendQueue(TaskLauncher launcher, String label, Consumer<Throwable> onUnexpected) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.label = Objects.requireNonNull(label, "label");
        this.onUnexpected = Objects.requireNonNull(onUnexpected, "onUnexpected");
    }

    /** 작업을 큐 끝에 넣는다(실행기 스레드). 큐 작업이 없으면 하나 띄운다. 세션이 끝나는 중이면 보내지 않는다. */
    void submit(Job job) {
        Objects.requireNonNull(job, "job");
        synchronized (jobs) {
            if (shutdown) {
                return;
            }
            jobs.addLast(job);
            if (running) {
                return;
            }
            running = true;
        }
        launcher.launch(job.name + label, this::drain);
    }

    /** 세션 종료: 남은 작업을 버리고 새 작업을 받지 않는다. */
    void shutdown() {
        synchronized (jobs) {
            shutdown = true;
            jobs.clear();
        }
    }

    private void drain() {
        while (true) {
            Job next;
            synchronized (jobs) {
                next = jobs.pollFirst();
                if (next == null) {
                    running = false;
                    return;
                }
            }
            try {
                next.session.appendInstructions(next.append);
            } catch (ProviderUnavailableException e) {
                if (next.onFailure != null) {
                    try {
                        next.onFailure.run();
                    } catch (RuntimeException failure) {
                        onUnexpected.accept(failure);
                    }
                }
            } catch (RuntimeException failure) {
                onUnexpected.accept(failure);
            }
        }
    }
}
