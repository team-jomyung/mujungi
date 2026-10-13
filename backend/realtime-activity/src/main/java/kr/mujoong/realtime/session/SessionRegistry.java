package kr.mujoong.realtime.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import org.springframework.stereotype.Component;

/**
 * 살아 있는 세션 실행기 목록 (U1 3.5 Step 23 · SCD-1 INIT 선등록 · PD-1 생명주기 1 · 3 · BR6.6 ① · BR6.11).
 *
 * <p>시작 명령은 DB를 만지기 전에 sessionId를 INIT으로 등록한다(편지함도 이때 만든다). 같은 sessionId의 동시 시작은 여기서 합류해
 * 첫 시작의 결과를 기다리고(BR6.6 ①), INIT 동안 도착한 끝내기 · 취소 명령은 편지함에 쌓였다가 실행기가 뜬 뒤 순서대로 처리된다. 트랜잭션이
 * 롤백되면 등록을 지운다. 실행기가 종료 정리를 마치면 스스로 지운다 — 지운 뒤 도착한 기기 · 공급자 메시지는 세션이 없어 기록 없이
 * 버려진다(BR6.11).
 */
@Component
@SingleInstanceAssumption(si = "SI-2",
        reason = "sessionId → 실행기 지도가 이 프로세스 메모리에만 있어 명령 · 이벤트가 실행기 없는 인스턴스로 가면 처리할 수 없다")
public class SessionRegistry {

    /** 등록 상태. */
    public enum State {
        /** 시작 트랜잭션 중(실행기 없음). */
        INIT,
        /** 실행기가 돈다. */
        RUNNING
    }

    /** 등록 항목. */
    public static final class Entry {
        private final UUID sessionId;
        private final SessionMailbox mailbox;
        private final CompletableFuture<Boolean> settled = new CompletableFuture<>();
        private volatile State state = State.INIT;
        private volatile SessionExecutor executor;

        private Entry(UUID sessionId, SessionMailbox mailbox) {
            this.sessionId = sessionId;
            this.mailbox = mailbox;
        }

        public UUID sessionId() {
            return sessionId;
        }

        public SessionMailbox mailbox() {
            return mailbox;
        }

        public State state() {
            return state;
        }

        public Optional<SessionExecutor> executor() {
            return Optional.ofNullable(executor);
        }

        /** INIT이 끝나면(실행기 시작 true · 롤백 false) 완료되는 약속. 합류한 시작 명령이 기다린다. */
        public CompletableFuture<Boolean> settled() {
            return settled;
        }
    }

    /** 등록 결과. */
    public record Registration(Entry entry, boolean created) {
    }

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    /** INIT으로 등록한다. 이미 있으면 그 항목에 합류한다(created false). */
    public Registration register(UUID sessionId, Supplier<SessionMailbox> mailboxes) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(mailboxes, "mailboxes");
        boolean[] created = new boolean[1];
        Entry entry = entries.computeIfAbsent(sessionId, id -> {
            created[0] = true;
            return new Entry(id, mailboxes.get());
        });
        return new Registration(entry, created[0]);
    }

    /** INIT → RUNNING. */
    public void activate(Entry entry, SessionExecutor executor) {
        Objects.requireNonNull(entry, "entry");
        entry.executor = Objects.requireNonNull(executor, "executor");
        entry.state = State.RUNNING;
        entry.settled.complete(Boolean.TRUE);
    }

    /** 시작이 롤백 · 거절됐다: 등록과 편지함을 지운다. */
    public void abandon(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.remove(entry.sessionId, entry);
        entry.mailbox.closeAndRelease();
        entry.settled.complete(Boolean.FALSE);
    }

    /** 실행기가 종료 정리를 마쳤다. */
    public void remove(UUID sessionId, SessionExecutor executor) {
        Entry entry = entries.get(sessionId);
        if (entry != null && entry.executor == executor) {
            entries.remove(sessionId, entry);
        }
    }

    /** 등록 항목. */
    public Optional<Entry> entry(UUID sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(entries.get(sessionId));
    }

    /** 편지함(INIT이어도 있다). */
    public Optional<SessionMailbox> mailbox(UUID sessionId) {
        return entry(sessionId).map(Entry::mailbox);
    }

    /** 실행기(RUNNING일 때만). */
    public Optional<SessionExecutor> executor(UUID sessionId) {
        return entry(sessionId).flatMap(Entry::executor);
    }

    /** 실행기가 살아 있는가(INIT 포함). 고아 세션 점검이 쓴다. */
    public boolean isAlive(UUID sessionId) {
        Entry entry = entries.get(sessionId);
        if (entry == null) {
            return false;
        }
        SessionExecutor executor = entry.executor;
        return entry.state == State.INIT || (executor != null && !executor.isTerminated());
    }

    /**
     * U3-J4: 기기의 살아 있는(종료 상태가 아닌) 세션 실행기. HELLO_OK 인계가 새 연결을 다시 묶을 때 쓴다. 기기당 진행 중 세션은 하나다
     * (부분 유일 색인). 없으면 빈 값.
     */
    public Optional<SessionExecutor> liveExecutorForDevice(UUID deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        for (Entry entry : entries.values()) {
            SessionExecutor executor = entry.executor;
            if (executor != null && !executor.isTerminated() && deviceId.equals(executor.deviceId())
                    && !executor.status().isTerminal()) {
                return Optional.of(executor);
            }
        }
        return Optional.empty();
    }

    /** 실행 중인 실행기들(종료 · 진단). */
    public Collection<SessionExecutor> executors() {
        List<SessionExecutor> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            SessionExecutor executor = entry.executor;
            if (executor != null) {
                result.add(executor);
            }
        }
        return result;
    }

    /** 등록 수. */
    public int size() {
        return entries.size();
    }

    /** 등록된 세션 편지함들의 대기 메시지 합(안전 + 일반, INIT 포함). {@code mujoong_queue_depth{queue=session_mailbox}}(J298). */
    public long queuedMessages() {
        long queued = 0;
        for (Entry entry : entries.values()) {
            queued += entry.mailbox.safetySize() + entry.mailbox.generalSize();
        }
        return queued;
    }
}
