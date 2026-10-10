package kr.mujoong.realtime.runtime;

import java.util.Objects;

/**
 * 짧은 보조 작업을 띄우는 장치 (U1 3.5 Step 23 · PD-1 표 2.1-A · RD-23).
 *
 * <p>운영은 작업마다 이름 있는 가상 스레드(공급자 연결 스레드 · 연결 송신기 · 입력 중계 · 기록기 플러시)다. 단위 시험은 같은 호출을 지금
 * 스레드에서 바로 실행하는 {@link #inline()}을 넣어 실제 스레드 없이 결정적으로 돈다(NFR6.2). 운영 코드에 시험용 분기는 없다 — 교체 지점만
 * 있다(SD-25).
 */
@FunctionalInterface
public interface TaskLauncher {

    /**
     * 작업을 띄운다.
     *
     * @return 작업을 돌리는 스레드(중단에 쓴다). 지금 스레드에서 이미 끝냈으면 null
     */
    Thread launch(String name, Runnable task);

    /** 작업마다 가상 스레드. */
    static TaskLauncher virtualThreads() {
        return (name, task) -> Thread.ofVirtual().name(Objects.requireNonNull(name, "name"))
                .start(Objects.requireNonNull(task, "task"));
    }

    /** 지금 스레드에서 곧바로 실행한다(시험). */
    static TaskLauncher inline() {
        return (name, task) -> {
            task.run();
            return null;
        };
    }
}
