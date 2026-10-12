package kr.mujoong.realtime.recording;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.collect.BoundedCollections;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.recording.AudioRecordingRepository.Speaker;
import kr.mujoong.realtime.runtime.TaskLauncher;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원음 파일 기록기 (U3 L3 · components AudioRecording · U3-R9 · U3-J14 · U3-J15 · K6 · AC6.4.7).
 *
 * <ul>
 *   <li>파일: {@code mujoong.recording.directory}/{@code <sessionId>}/{@code child.pcm} · {@code ai.pcm}(PCM16LE 24kHz 모노 원시). 디렉터리는
 *       700, 파일은 600(POSIX일 때). 화자의 첫 조각이 올 때 메타 행({@code realtime_audio_recording})을 먼저 넣고 파일을 만든다 — 메타 없는 파일이
 *       생기지 않아 삭제 참가자가 메타로 파일을 모두 찾는다(U3-J16).
 *   <li>대기열: 조각은 {@code mujoong.limits.recording-queue-frames}를 넘으면 버리고 {@code mujoong_recording_dropped_total}을 센다(닫기 신호는
 *       버리지 않는다). 쓰기는 이 기록기의 스레드 하나가 순서대로 한다.
 *   <li>상한: 세션(두 화자 합)이 {@code mujoong.recording.max-bytes-per-session}을 넘으려 하면 그 세션 기록을 멈추고 파일을 닫는다(대화는
 *       계속, U3-J15).
 *   <li>실패: 파일 · DB 오류는 앱 오류 한 줄을 남기고 그 세션 기록만 멈춘다. 앱 종료 때는 남은 대기열을 쓰고 열린 파일을 모두 닫는다(DB 문장 제한까지
 *       기다린다).
 *   <li>차단: 계정 삭제 참가자가 진행 중 세션을 {@link #block}하면 그 세션의 새 메타 · 파일 · 조각을 받지 않고 열린 파일을 닫는다(리뷰 반영 8).
 * </ul>
 * 원음은 로그 · 지표 라벨에 쓰지 않는다.
 */
@Component
public class FileAudioRecorder implements AudioRecordings, DisposableBean {

    /** 메타 format 값. */
    public static final String FORMAT = "pcm_s16le_24000_mono";

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    /** 대기열 항목. */
    private sealed interface Item {
    }

    /** 조각과 받은 시각(파일 시작 · 상한 멈춤 시각은 쓴 때가 아니라 받은 때다). */
    private record Chunk(UUID sessionId, Speaker speaker, byte[] pcm, Instant at) implements Item {
    }

    private record Close(UUID sessionId, Instant endedAt) implements Item {
    }

    /** 기록 차단: 그 세션의 열린 파일을 닫고 {@code done}을 끝낸다(리뷰 반영 8). */
    private record Block(UUID sessionId, CompletableFuture<Void> done) implements Item {
    }

    /** 기억하는 차단 세션 수 상한(오래된 것부터 잊는다 — 차단은 지우는 동안의 경합만 막으면 된다). */
    static final int MAX_BLOCKED = 10_000;

    private final AudioRecordingRepository repository;
    private final TransactionTemplate transactions;
    private final DbRetry dbRetry;
    private final EventLogger events;
    private final RealtimeMetrics metrics;
    private final MujoongClock clock;
    private final TaskLauncher launcher;
    private final Path directory;
    private final long maxBytesPerSession;
    private final int queueCapacity;
    private final Duration stopWait;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    /** 차단 차례를 처리했다(차단을 부른 쪽이 기다린다). */
    private final Condition blockHandled = lock.newCondition();
    /** 기록기를 띄우는 호출이 돌아왔다(앱 종료가 기다린다, 3차 리뷰 5). */
    private final Condition launched = lock.newCondition();
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private int queuedChunks;
    private boolean stopping;
    private boolean writerStarted;
    /** 기록기를 띄우는 호출이 돌아왔다(그 뒤에도 {@link #writer}가 null이면 작업을 돌리지 않는 장치다). */
    private boolean writerLaunched;
    private Thread writer;

    /** 기록기 스레드만 쓰는 세션별 파일. */
    private final Map<UUID, SessionFiles> open = new HashMap<>();
    /** 기록을 막은 세션(계정 삭제 중). 파일 만들기와 차단 등록은 {@link #creation} 잠금으로 서로를 기다린다. */
    private final Set<UUID> blocked = BoundedCollections.synchronizedSet(MAX_BLOCKED);
    private final ReentrantLock creation = new ReentrantLock();

    @Autowired
    public FileAudioRecorder(AudioRecordingRepository repository, PlatformTransactionManager transactionManager,
            DbRetry dbRetry, EventLogger events, RealtimeMetrics metrics, MujoongClock clock, TaskLauncher launcher,
            RealtimeProperties properties) {
        this(repository, new TransactionTemplate(transactionManager), dbRetry, events, metrics, clock, launcher,
                Path.of(properties.recording().directory()), properties.recording().maxBytesPerSession().toBytes(),
                properties.limits().recordingQueueFrames(), properties.limits().dbStatementTimeout());
    }

    /**
     * @param launcher 기록기 작업을 띄우는 장치. 작업을 다른 스레드에서 돌리거나 돌리지 않아야 한다(지금 스레드에서 바로 돌리면 대기에서 돌아오지
     *     않는다). 돌리지 않는 장치면 {@link #drainNow()} · {@link #destroy()}가 지금 스레드에서 쓴다
     * @param stopWait 앱 종료 때 남은 쓰기를 기다리는 상한
     */
    public FileAudioRecorder(AudioRecordingRepository repository, TransactionTemplate transactions, DbRetry dbRetry,
            EventLogger events, RealtimeMetrics metrics, MujoongClock clock, TaskLauncher launcher, Path directory,
            long maxBytesPerSession, int queueCapacity, Duration stopWait) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (maxBytesPerSession <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("기록 상한 · 대기열은 0보다 커야 한다");
        }
        this.maxBytesPerSession = maxBytesPerSession;
        this.queueCapacity = queueCapacity;
        this.stopWait = Objects.requireNonNull(stopWait, "stopWait");
    }

    @Override
    public Session open(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        startWriter();
        return new Handle(sessionId);
    }

    /** 원음 파일 위치(삭제 참가자 · 시험). */
    public Path resolve(String storagePath) {
        Path resolved = directory.resolve(storagePath).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IllegalArgumentException("기록 디렉터리 밖 경로다");
        }
        return resolved;
    }

    /** 기록 디렉터리(시험 · 진단). */
    public Path directory() {
        return directory;
    }

    /** 대기열에 남은 조각 수(시험 · 진단). */
    public int queuedChunks() {
        lock.lock();
        try {
            return queuedChunks;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 대기열이 빌 때까지 지금 스레드에서 쓴다(시험의 결정적 실행 — 작업을 띄우지 않는 장치를 넣었을 때). 기록기 스레드가 실제로 돌고 있으면 쓰지
     * 않고 거짓을 돌려준다.
     */
    public boolean drainNow() {
        lock.lock();
        try {
            if (!writesOnCaller()) {
                return false;
            }
        } finally {
            lock.unlock();
        }
        Item item;
        while ((item = poll()) != null) {
            handle(item);
        }
        return true;
    }

    /**
     * 리뷰 반영 8: 세션의 원음 기록을 막는다(계정 삭제 참가자가 지우기 전에 부른다). 돌아오면 ① 그 세션의 새 메타 · 파일은 더 생기지 않고 — 만들던
     * 것은 메타가 커밋된 뒤다 — ② 이후 넘어오는 조각은 버리며 ③ 열린 파일은 닫혔다(기록기 스레드가 차례를 처리할 때까지 앱 종료 대기 상한만큼
     * 기다린다). 그래서 이 뒤에 메타를 조회해 지우면 지운 파일이 다시 생기지 않는다.
     *
     * @return 열린 파일을 닫은 것까지 확인했으면 참(기다림 상한을 넘기면 거짓 — 차단 ① ②는 그래도 걸려 있다)
     */
    public boolean block(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        creation.lock();
        try {
            blocked.add(sessionId);
        } finally {
            creation.unlock();
        }
        CompletableFuture<Void> closed = new CompletableFuture<>();
        boolean onCaller;
        lock.lock();
        try {
            if (!writerStarted) {
                // 2차 리뷰 7: 기록기가 아직 시작하지 않았다(첫 open 전) — 열린 파일이 없다. 파일 맵은 기록기 스레드 전용이라 여기서 만지지 않는다
                return true;
            }
            queue.addLast(new Block(sessionId, closed));
            available.signalAll();
            onCaller = writesOnCaller();
        } finally {
            lock.unlock();
        }
        if (onCaller && drainNow()) {
            // 띄운 장치가 작업을 돌리지 않는다(시험) — 파일 맵을 쓰는 쪽이 원래 이 호출 스레드다
            return closed.isDone();
        }
        long remaining = stopWait.toNanos();
        lock.lock();
        try {
            while (!closed.isDone()) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = blockHandled.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void signalBlockHandled() {
        lock.lock();
        try {
            blockHandled.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void startWriter() {
        lock.lock();
        try {
            if (writerStarted || stopping) {
                return;
            }
            writerStarted = true;
        } finally {
            lock.unlock();
        }
        Thread started = launcher.launch("audio-recorder", this::loop);
        lock.lock();
        try {
            writer = started;
            writerLaunched = true;
            launched.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 2차 리뷰 7: 파일 맵({@code open})을 부른 스레드에서 다뤄도 되는가 — 기록기를 띄웠는데 장치가 스레드를 돌려주지 않았을 때(작업을 돌리지
     * 않는 시험 장치)만이다. 띄우는 호출이 아직 돌아오지 않은 사이는 기록기 스레드가 이미 돌 수 있어 아니다. {@link #lock}을 쥐고 부른다.
     */
    private boolean writesOnCaller() {
        return writer == null && (writerLaunched || !writerStarted);
    }

    private void offerChunk(UUID sessionId, Speaker speaker, byte[] pcm) {
        if (pcm == null || pcm.length == 0 || blocked.contains(sessionId)) {
            return;
        }
        boolean dropped;
        lock.lock();
        try {
            dropped = stopping || queuedChunks >= queueCapacity;
            if (!dropped) {
                queue.addLast(new Chunk(sessionId, speaker, pcm.clone(), clock.now()));
                queuedChunks++;
                available.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (dropped) {
            metrics.recordingDropped();
        }
    }

    private void offerClose(UUID sessionId, Instant endedAt) {
        lock.lock();
        try {
            queue.addLast(new Close(sessionId, endedAt));
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private Item poll() {
        lock.lock();
        try {
            Item item = queue.pollFirst();
            if (item instanceof Chunk) {
                queuedChunks--;
            }
            return item;
        } finally {
            lock.unlock();
        }
    }

    private void loop() {
        while (true) {
            Item item;
            lock.lock();
            try {
                while (!stopping && queue.isEmpty()) {
                    // 시한 없이 기다린다: 넣기 · 멈추기가 잠금 안에서 signalAll을 부른다
                    available.awaitUninterruptibly();
                }
                if (queue.isEmpty()) {
                    break;
                }
                item = queue.pollFirst();
                if (item instanceof Chunk) {
                    queuedChunks--;
                }
            } finally {
                lock.unlock();
            }
            handle(item);
        }
        finishAll(clock.now());
    }

    private void handle(Item item) {
        try {
            switch (item) {
                case Chunk chunk -> write(chunk);
                case Close close -> {
                    SessionFiles files = open.remove(close.sessionId());
                    if (files != null) {
                        files.finish(close.endedAt());
                    }
                }
                case Block block -> {
                    try {
                        SessionFiles files = open.remove(block.sessionId());
                        if (files != null) {
                            files.finish(clock.now());
                        }
                    } finally {
                        block.done().complete(null);
                        signalBlockHandled();
                    }
                }
            }
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.VOICE_RELAY, e);
        }
    }

    private void write(Chunk chunk) {
        if (blocked.contains(chunk.sessionId())) {
            // 차단 전에 대기열에 들어온 조각(리뷰 반영 8)
            return;
        }
        SessionFiles files = open.computeIfAbsent(chunk.sessionId(), SessionFiles::new);
        if (files.stopped) {
            return;
        }
        if (files.bytes + chunk.pcm().length > maxBytesPerSession) {
            // U3-J15: 상한에 닿으면 그 세션 기록을 멈추고 지금까지의 파일을 닫는다(대화는 계속)
            files.stop(chunk.at());
            metrics.recordingDropped();
            return;
        }
        SpeakerFile file = files.file(chunk.speaker(), chunk.at());
        if (file == null) {
            return;
        }
        try {
            file.out.write(chunk.pcm());
            file.bytes += chunk.pcm().length;
            files.bytes += chunk.pcm().length;
        } catch (IOException e) {
            events.appError(EventLog.Component.VOICE_RELAY, "RecordingWriteFailed");
            files.stop(chunk.at());
        }
    }

    private void finishAll(Instant at) {
        for (SessionFiles files : List.copyOf(open.values())) {
            files.finish(at);
        }
        open.clear();
    }

    @Override
    public void destroy() {
        Thread running;
        boolean onCaller;
        lock.lock();
        try {
            stopping = true;
            available.signalAll();
            // 3차 리뷰 5: 띄우는 호출이 아직 돌아오지 않았으면 기록기 스레드가 이미 돌 수 있다 — 스레드를 받을 때까지 기다린다
            long remaining = stopWait.toNanos();
            while (writerStarted && !writerLaunched && remaining > 0) {
                remaining = launched.awaitNanos(remaining);
            }
            running = writer;
            onCaller = writesOnCaller();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            lock.unlock();
        }
        if (running == null && !onCaller) {
            // 기다림 상한까지 스레드를 받지 못했다 — 기록기 스레드가 멈춤 신호를 보고 남은 일을 쓰고 파일을 닫는다
            return;
        }
        if (running == null) {
            // 기록기 스레드가 없다(작업을 돌리지 않는 장치) — 남은 대기열을 지금 쓰고 열린 파일을 닫는다
            Item item;
            while ((item = poll()) != null) {
                handle(item);
            }
            finishAll(clock.now());
            return;
        }
        try {
            if (!running.join(stopWait)) {
                running.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> attributes(Set<PosixFilePermission> permissions) {
        return PosixFilePermissions.asFileAttribute(permissions);
    }

    /** 세션 하나의 열린 파일들(기록기 스레드 전용). */
    private final class SessionFiles {

        private final UUID sessionId;
        private final Map<Speaker, SpeakerFile> files = new EnumMap<>(Speaker.class);
        private long bytes;
        private boolean stopped;

        private SessionFiles(UUID sessionId) {
            this.sessionId = sessionId;
        }

        /** 화자 파일(없으면 메타 → 파일 순서로 만든다). 만들지 못하면 이 세션 기록을 멈추고 null. */
        private SpeakerFile file(Speaker speaker, Instant startedAt) {
            SpeakerFile existing = files.get(speaker);
            if (existing != null) {
                return existing;
            }
            String relative = sessionId + "/" + (speaker == Speaker.CHILD ? "child.pcm" : "ai.pcm");
            UUID id = UUID.randomUUID();
            // 리뷰 반영 8: 차단 확인과 메타 만들기를 한 잠금 안에서 — 차단이 돌아온 뒤에는 새 메타가 생기지 않는다
            creation.lock();
            try {
                if (blocked.contains(sessionId)) {
                    stop(startedAt);
                    return null;
                }
                transactions.executeWithoutResult(status ->
                        repository.insert(id, sessionId, speaker, relative, FORMAT, startedAt, clock.now()));
            } catch (RuntimeException e) {
                events.appError(EventLog.Component.VOICE_RELAY, e);
                stop(startedAt);
                return null;
            } finally {
                creation.unlock();
            }
            try {
                Path path = resolve(relative);
                createDirectory(path.getParent());
                createFile(path);
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
                SpeakerFile created = new SpeakerFile(id, out);
                files.put(speaker, created);
                return created;
            } catch (IOException | RuntimeException e) {
                events.appError(EventLog.Component.VOICE_RELAY, "RecordingWriteFailed");
                // 메타는 남기되 0바이트로 닫는다(파일이 없으면 삭제 참가자가 건너뛴다)
                finishRow(id, 0L, startedAt);
                stop(startedAt);
                return null;
            }
        }

        private void stop(Instant at) {
            if (!stopped) {
                stopped = true;
                closeAll(at);
            }
        }

        private void finish(Instant at) {
            stopped = true;
            closeAll(at);
        }

        private void closeAll(Instant at) {
            for (SpeakerFile file : files.values()) {
                try {
                    file.out.close();
                } catch (IOException e) {
                    events.appError(EventLog.Component.VOICE_RELAY, "RecordingWriteFailed");
                }
                finishRow(file.id, file.bytes, at);
            }
            files.clear();
        }
    }

    private void finishRow(UUID id, long byteCount, Instant endedAt) {
        try {
            dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                    () -> transactions.execute(status -> repository.finish(id, byteCount, endedAt)));
        } catch (RuntimeException e) {
            events.appError(EventLog.Component.VOICE_RELAY, e);
        }
    }

    private static void createDirectory(Path dir) throws IOException {
        try {
            Files.createDirectories(dir, attributes(DIRECTORY_PERMISSIONS));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir);
        }
    }

    private static void createFile(Path path) throws IOException {
        try {
            Files.createFile(path, attributes(FILE_PERMISSIONS));
        } catch (FileAlreadyExistsException e) {
            // 같은 세션 · 화자를 다시 연 경우는 메타 유일 제약이 먼저 막는다. 남은 빈 파일은 잘라 쓴다
        } catch (UnsupportedOperationException e) {
            Files.createFile(path);
        }
    }

    /** 화자 파일 하나. */
    private static final class SpeakerFile {

        private final UUID id;
        private final OutputStream out;
        private long bytes;

        private SpeakerFile(UUID id, OutputStream out) {
            this.id = id;
            this.out = out;
        }
    }

    /** 세션 기록 손잡이. */
    private final class Handle implements Session {

        private final UUID sessionId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(UUID sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void child(byte[] pcm) {
            if (!closed.get()) {
                offerChunk(sessionId, Speaker.CHILD, pcm);
            }
        }

        @Override
        public void ai(byte[] pcm) {
            if (!closed.get()) {
                offerChunk(sessionId, Speaker.AI, pcm);
            }
        }

        @Override
        public void close(Instant endedAt) {
            if (closed.compareAndSet(false, true)) {
                offerClose(sessionId, Objects.requireNonNull(endedAt, "endedAt"));
            }
        }
    }
}
