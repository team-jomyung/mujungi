package kr.mujoong.realtime.session;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import kr.mujoong.realtime.gate.OutputGeneration;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.SessionMailbox.SendAbortReason;
import kr.mujoong.realtime.voice.ProviderPort.ProviderSession;
import kr.mujoong.realtime.voice.ProviderPort.ProviderUnavailableException;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/**
 * 공급자 직렬 송신 경로 (U11 W9 · BR3.3 · BR11.4 · 설계 검토 R-04 · R-16). 서버가 보내는 지시 · 응답 요청은 모두 이 세션 큐 하나를 지난다.
 *
 * <p>공급자에는 응답 취소가 없어서, 서버가 지시({@code session.instructions.append})와 응답 요청({@code response.create})을 보낼 때는 앞 응답의
 * 꼬리가 새 응답과 섞이지 않게 <b>출력 세대를 올리고 조각이 끊기기를 기다린 뒤</b> 보낸다. 작업 한 개의 순서는 다음과 같다.
 *
 * <ol>
 *   <li>실행기가 세대를 올리고({@link OutputGeneration#bumpForSend}) 작업을 큐에 넣는다.
 *   <li>실행기가 끊김을 확인하면 그 작업의 <b>허가 칸</b>(한 번만 채워지는 값)에 {@link Permit}을 채운다. 큐 작업은 그때까지 이 칸에서 기다린다.
 *   <li>작업은 송신 직전에 실행기가 내보낸 읽기 전용 현재 세대 · 허가 번호({@link OutputGeneration#published()})와 허가를 대조한다. 같으면
 *       {@code append}(있으면) → 다시 대조 → 일반 편지함에 {@link SessionMailbox.InstructionSent}('보내는 중' 표시)를 넣고 → 그 <b>다음</b>
 *       {@code response.create}를 보낸다. 표시가 {@code create}보다 먼저 편지함에 들어가므로 응답의 첫 조각은 표시 뒤에 도착한다.
 *   <li>대조가 어긋나면 보내지 않고 {@link SessionMailbox.SendAborted}를 넣고 끝난다. 끊김 대기 상한 · 세션 종료 · 장애에서는 실행기가 허가 칸을
 *       <b>거부</b>로 채워 작업이 큐를 영영 쥐지 않게 한다.
 * </ol>
 *
 * <p>허가 칸 채우기 · 거부는 실행기 스레드가 한다. 큐 작업은 별도 스레드(운영은 가상 스레드, 시험은 수동 장치)에서 돌고 일반 편지함에는 {@code putGeneral}로
 * 넣는다(공급자 이벤트와 같은 줄, 순서가 보존된다). 끊김을 기다리지 않는 송신(연습 확장점의 지시문 추가)도 같은 큐를 지나 순서를 지킨다.
 */
final class ProviderSendQueue {

    /** 허가 칸에 채우는 값: 허가(세대 · 허가 번호) 또는 거부(사유). */
    record Permit(long generation, long permitSeq, SendAbortReason denial) {

        static Permit granted(long generation, long permitSeq) {
            return new Permit(generation, permitSeq, null);
        }

        static Permit denied(SendAbortReason reason) {
            return new Permit(-1L, -1L, Objects.requireNonNull(reason, "reason"));
        }

        boolean isGranted() {
            return denial == null;
        }
    }

    /**
     * 송신 작업 하나.
     *
     * @param generation 이 작업이 기다리는 출력 세대(끊김을 기다리지 않는 작업은 -1)
     * @param gapWait 끊김 허가를 기다리는가
     * @param append 먼저 보낼 지시 추가(없으면 null)
     * @param create 지시 뒤에 보낼 응답 요청(없으면 null)
     * @param turnId 판정 턴의 차례 id(판정 턴 밖 송신이면 null)
     */
    static final class Job {
        final String name;
        final ProviderSession session;
        final long generation;
        final boolean gapWait;
        final ProviderRequestFactory.InstructionsAppend append;
        final ProviderRequestFactory.ResponseCreate create;
        final UUID turnId;
        final CompletableFuture<Permit> permit = new CompletableFuture<>();
        /** 이 작업이 끝내 나가지 못했을 때(거부 · 송신 실패) 실행기 스레드에서 부를 일(없으면 null). 낡은 세대로 접힌 경우는 부르지 않는다. */
        final Runnable onNotSent;

        Job(String name, ProviderSession session, long generation, boolean gapWait,
                ProviderRequestFactory.InstructionsAppend append, ProviderRequestFactory.ResponseCreate create, UUID turnId,
                Runnable onNotSent) {
            this.name = Objects.requireNonNull(name, "name");
            this.session = Objects.requireNonNull(session, "session");
            this.generation = generation;
            this.gapWait = gapWait;
            this.append = append;
            this.create = create;
            this.turnId = turnId;
            this.onNotSent = onNotSent;
        }

        /** 같은 내용으로 새 허가 칸을 가진 작업(같은 세대에서 허가만 무효가 됐을 때 다시 큐에 넣는다). */
        Job retry() {
            return new Job(name, session, generation, gapWait, append, create, turnId, onNotSent);
        }
    }

    private final TaskLauncher launcher;
    private final String label;
    private final SessionMailbox mailbox;
    private final Supplier<OutputGeneration.Published> published;
    private final java.util.function.Consumer<Throwable> onUnexpected;
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private boolean running;
    private volatile boolean shutdown;

    ProviderSendQueue(TaskLauncher launcher, String label, SessionMailbox mailbox,
            Supplier<OutputGeneration.Published> published, java.util.function.Consumer<Throwable> onUnexpected) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.label = Objects.requireNonNull(label, "label");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        this.published = Objects.requireNonNull(published, "published");
        this.onUnexpected = Objects.requireNonNull(onUnexpected, "onUnexpected");
    }

    /** 작업을 큐 끝에 넣는다(실행기 스레드). 큐 작업이 없으면 하나 띄운다. */
    void submit(Job job) {
        Objects.requireNonNull(job, "job");
        if (shutdown) {
            job.permit.complete(Permit.denied(SendAbortReason.SESSION_END));
            return;
        }
        synchronized (jobs) {
            jobs.addLast(job);
            if (running) {
                return;
            }
            running = true;
        }
        launcher.launch(job.name + label, this::drain);
    }

    /** 끊김을 확인해 허가를 내준다(실행기 스레드). */
    void grant(Job job, long generation, long permitSeq) {
        job.permit.complete(Permit.granted(generation, permitSeq));
    }

    /** 허가 칸을 거부로 채운다(실행기 스레드, 끊김 대기 상한 · 세대 변경 · 세션 종료 · 장애). 이미 채워졌으면 아무 일도 하지 않는다. */
    void deny(Job job, SendAbortReason reason) {
        job.permit.complete(Permit.denied(reason));
    }

    /** 세션 종료: 남은 작업을 모두 거부하고 새 작업을 받지 않는다. */
    void shutdown() {
        shutdown = true;
        synchronized (jobs) {
            for (Job job : jobs) {
                job.permit.complete(Permit.denied(SendAbortReason.SESSION_END));
            }
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
                run(next);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synchronized (jobs) {
                    running = false;
                }
                return;
            } catch (RuntimeException failure) {
                onUnexpected.accept(failure);
            }
        }
    }

    private void run(Job job) throws InterruptedException {
        long generation = job.generation;
        long permitSeq = -1L;
        if (job.gapWait) {
            Permit permit;
            try {
                permit = job.permit.get();
            } catch (java.util.concurrent.ExecutionException e) {
                permit = Permit.denied(SendAbortReason.FAULT);
            }
            if (!permit.isGranted()) {
                abort(job, generation, -1L, permit.denial());
                return;
            }
            generation = permit.generation();
            permitSeq = permit.permitSeq();
            SendAbortReason mismatch = mismatch(generation, permitSeq);
            if (mismatch != null) {
                abort(job, generation, permitSeq, mismatch);
                return;
            }
        }
        try {
            if (job.append != null) {
                job.session.appendInstructions(job.append);
            }
            if (job.gapWait) {
                SendAbortReason mismatch = mismatch(generation, permitSeq);
                if (mismatch != null) {
                    abort(job, generation, permitSeq, mismatch);
                    return;
                }
                // 표시를 create보다 먼저 일반 편지함에 넣는다: 지시 응답의 첫 조각은 항상 이 표시 뒤에 들어온다(R-16)
                if (!mailbox.putGeneral(new SessionMailbox.InstructionSent(job.turnId, generation, permitSeq))) {
                    return;
                }
            }
            if (job.create != null) {
                job.session.createResponse(job.create);
            }
        } catch (ProviderUnavailableException e) {
            // 공급자 끊김은 리더의 Closed로 따로 온다. 이 작업은 보내지 못한 것으로 알린다(사유 코드만)
            abort(job, generation, permitSeq, SendAbortReason.SEND_FAILED);
        }
    }

    private SendAbortReason mismatch(long generation, long permitSeq) {
        OutputGeneration.Published now = published.get();
        if (now.generation() != generation) {
            return SendAbortReason.GENERATION_CHANGED;
        }
        if (now.permitSeq() != permitSeq) {
            return SendAbortReason.PERMIT_STALE;
        }
        return null;
    }

    private void abort(Job job, long generation, long permitSeq, SendAbortReason reason) throws InterruptedException {
        mailbox.putGeneral(new SessionMailbox.SendAborted(job.turnId, generation, permitSeq, reason));
    }
}
