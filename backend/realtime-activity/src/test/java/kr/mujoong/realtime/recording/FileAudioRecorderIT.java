package kr.mujoong.realtime.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.recording.AudioRecordingRepository.AudioRecordingRow;
import kr.mujoong.realtime.recording.AudioRecordingRepository.Speaker;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.ManualLauncher;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 원음 파일 기록기 통합 시험(U3 L3 · components AudioRecording · U3-R9 · U3-J14 · U3-J15 · U3-J16).
 *
 * <p>증명: 동의된 세션의 두 화자 파일(소유자만 읽는 권한) · 메타 행(메타가 파일보다 먼저) · 바이트 수와 끝 시각, 세션 상한 · 대기열 넘침의 버림과
 * 지표, 메타 실패 · 파일 실패가 그 세션 기록만 멈춤, 기록 디렉터리 밖 경로 거부, 앱 종료 때 남은 조각 쓰기와 파일 닫기.
 */
@Tag("u3")
@Tag("db")
class FileAudioRecorderIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final byte[] FRAME = filled(960);

    private static RealtimeDb db;

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(T0);
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final EventLogger events = TestEventLoggers.strict(clock, logs, metrics);
    private AudioRecordingRepository repository;
    private UUID sessionId;

    @BeforeAll
    static void openDatabase() {
        db = RealtimeDb.open();
    }

    @AfterAll
    static void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        db.wipe();
        repository = new AudioRecordingRepository(db.jdbc());
        sessionId = insertSession();
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void aConsentedSessionWritesBothSpeakerFilesForTheOwnerOnlyAndFinishesTheirRows() throws IOException {
        FileAudioRecorder recorder = recorder(new ManualLauncher(), 1_000_000, 100);
        AudioRecordings.Session session = recorder.open(sessionId);

        session.child(FRAME);
        session.child(FRAME);
        session.ai(filled(4_800));
        clock.advance(Duration.ofSeconds(5));
        Instant endedAt = clock.now();
        session.close(endedAt);
        session.child(FRAME);
        assertThat(recorder.drainNow()).isTrue();

        List<AudioRecordingRow> rows = repository.findBySession(sessionId);
        assertThat(rows).extracting(AudioRecordingRow::speaker).containsExactlyInAnyOrder(Speaker.CHILD, Speaker.AI);
        AudioRecordingRow child = repository.find(sessionId, Speaker.CHILD).orElseThrow();
        AudioRecordingRow ai = repository.find(sessionId, Speaker.AI).orElseThrow();
        assertThat(child.storagePath()).isEqualTo(sessionId + "/child.pcm");
        assertThat(ai.storagePath()).isEqualTo(sessionId + "/ai.pcm");
        assertThat(child.format()).isEqualTo(FileAudioRecorder.FORMAT);
        assertThat(child.byteCount()).as("닫은 뒤 조각은 받지 않는다").isEqualTo(2L * FRAME.length);
        assertThat(ai.byteCount()).isEqualTo(4_800L);
        assertThat(child.startedAt()).isEqualTo(T0);
        assertThat(child.endedAt()).isEqualTo(endedAt);
        assertThat(ai.endedAt()).isEqualTo(endedAt);

        Path childFile = recorder.resolve(child.storagePath());
        assertThat(childFile).startsWith(root.toAbsolutePath().normalize());
        assertThat(Files.readAllBytes(childFile)).hasSize(2 * FRAME.length).startsWith(FRAME);
        assertThat(Files.size(recorder.resolve(ai.storagePath()))).isEqualTo(4_800L);
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(childFile.getParent())))
                    .isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(childFile))).isEqualTo("rw-------");
        }
        assertThat(logs.events(EventCode.APP_ERROR)).isEmpty();
    }

    @Test
    void reachingTheSessionCapStopsThatSessionsRecordingAndCountsTheDrop() {
        FileAudioRecorder recorder = recorder(new ManualLauncher(), 2_000, 100);
        AudioRecordings.Session session = recorder.open(sessionId);

        session.child(FRAME);
        session.child(FRAME);
        clock.advance(Duration.ofSeconds(1));
        Instant stoppedAt = clock.now();
        session.ai(FRAME);
        session.child(FRAME);
        clock.advance(Duration.ofSeconds(1));
        session.close(clock.now());
        recorder.drainNow();

        assertThat(repository.findBySession(sessionId)).as("상한에 닿은 뒤 새 화자 파일을 만들지 않는다").singleElement()
                .satisfies(row -> {
                    assertThat(row.speaker()).isEqualTo(Speaker.CHILD);
                    assertThat(row.byteCount()).isEqualTo(2L * FRAME.length);
                    assertThat(row.endedAt()).isEqualTo(stoppedAt);
                });
        assertThat(dropped()).isEqualTo(1.0);
    }

    @Test
    void aFullQueueDropsChunksButNeverTheCloseSignal() {
        FileAudioRecorder recorder = recorder(new ManualLauncher(), 1_000_000, 2);
        AudioRecordings.Session session = recorder.open(sessionId);

        session.child(FRAME);
        session.child(FRAME);
        session.child(FRAME);
        assertThat(recorder.queuedChunks()).isEqualTo(2);
        session.close(clock.now());
        recorder.drainNow();

        assertThat(dropped()).isEqualTo(1.0);
        assertThat(recorder.queuedChunks()).isZero();
        AudioRecordingRow child = repository.find(sessionId, Speaker.CHILD).orElseThrow();
        assertThat(child.byteCount()).isEqualTo(2L * FRAME.length);
        assertThat(child.endedAt()).isEqualTo(T0);
    }

    @Test
    void aSessionWithoutItsRowStopsOnlyItsOwnRecordingAndLogsOneAppError() {
        FileAudioRecorder recorder = recorder(new ManualLauncher(), 1_000_000, 100);
        UUID unknown = UUID.randomUUID();
        AudioRecordings.Session orphan = recorder.open(unknown);
        AudioRecordings.Session session = recorder.open(sessionId);

        orphan.child(FRAME);
        orphan.child(FRAME);
        session.child(FRAME);
        orphan.close(clock.now());
        session.close(clock.now());
        recorder.drainNow();

        assertThat(repository.findBySession(unknown)).isEmpty();
        assertThat(Files.exists(root.resolve(unknown.toString()))).as("메타 없는 파일은 만들지 않는다").isFalse();
        assertThat(repository.find(sessionId, Speaker.CHILD).orElseThrow().byteCount()).isEqualTo(FRAME.length);
        assertThat(logs.events(EventCode.APP_ERROR)).hasSize(1);
    }

    @Test
    void aFileThatCannotBeCreatedLeavesAFinishedZeroByteRowAndNoAudioInTheLog() throws IOException {
        FileAudioRecorder recorder = recorder(new ManualLauncher(), 1_000_000, 100);
        // 세션 디렉터리 자리에 일반 파일이 있어 디렉터리를 만들 수 없다
        Files.writeString(root.resolve(sessionId.toString()), "x");
        AudioRecordings.Session session = recorder.open(sessionId);

        session.child(FRAME);
        session.ai(FRAME);
        session.close(clock.now());
        recorder.drainNow();

        assertThat(repository.findBySession(sessionId)).singleElement().satisfies(row -> {
            assertThat(row.speaker()).isEqualTo(Speaker.CHILD);
            assertThat(row.byteCount()).isZero();
            assertThat(row.endedAt()).isEqualTo(T0);
        });
        assertThat(logs.events(EventCode.APP_ERROR)).singleElement()
                .satisfies(event -> assertThat(LogCapture.attrs(event)).containsEntry("errorKind", "RecordingWriteFailed"));
    }

    @Test
    void pathsOutsideTheRecordingDirectoryAreRefused() {
        FileAudioRecorder recorder = recorder(new ManualLauncher(), 1_000_000, 100);

        assertThat(recorder.directory()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(recorder.resolve(sessionId + "/ai.pcm")).isEqualTo(recorder.directory().resolve(sessionId + "/ai.pcm"));
        assertThatThrownBy(() -> recorder.resolve("../escape.pcm")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder.resolve(sessionId + "/../../escape.pcm"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shuttingDownWithARealWriterWritesTheRestAndClosesOpenFilesThenRefusesNewChunks() {
        FileAudioRecorder recorder = recorder(TaskLauncher.virtualThreads(), 1_000_000, 100);
        AudioRecordings.Session session = recorder.open(sessionId);
        for (int i = 0; i < 10; i++) {
            session.child(FRAME);
        }
        assertThat(recorder.drainNow()).as("실제 기록기 스레드가 있으면 지금 스레드에서 쓰지 않는다").isFalse();
        clock.advance(Duration.ofSeconds(3));

        recorder.destroy();

        AudioRecordingRow child = repository.find(sessionId, Speaker.CHILD).orElseThrow();
        assertThat(child.byteCount()).isEqualTo(10L * FRAME.length);
        assertThat(child.endedAt()).isEqualTo(clock.now());
        session.child(FRAME);
        assertThat(dropped()).as("종료 뒤 조각은 버리고 센다").isEqualTo(1.0);
    }

    @Test
    void shuttingDownWithoutAWriterThreadFinishesOnTheCallingThread() {
        FileAudioRecorder recorder = recorder(new ManualLauncher(), 1_000_000, 100);
        AudioRecordings.Session session = recorder.open(sessionId);
        session.child(FRAME);
        session.ai(FRAME);

        recorder.destroy();

        assertThat(repository.findBySession(sessionId)).hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.byteCount()).isEqualTo(FRAME.length);
                    assertThat(row.endedAt()).isEqualTo(T0);
                });
    }

    private FileAudioRecorder recorder(TaskLauncher launcher, long maxBytes, int queue) {
        return new FileAudioRecorder(repository, db.transactions(), new DbRetry(2, () -> 0L, duration -> { }), events,
                metrics, clock, launcher, root, maxBytes, queue, Duration.ofSeconds(5));
    }

    private double dropped() {
        return metrics.registry().counter(RealtimeMetrics.RECORDING_DROPPED).count();
    }

    private static byte[] filled(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private static UUID insertSession() {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("1.0", true, true, true, false), new ChildContext("하늘", 8, List.of("공룡")), null,
                null);
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        return session.id();
    }
}
