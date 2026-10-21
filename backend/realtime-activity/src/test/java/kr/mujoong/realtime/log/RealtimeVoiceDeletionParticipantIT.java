package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.gateway.DeviceDisconnectRepository;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.recording.AudioRecordingRepository;
import kr.mujoong.realtime.recording.FileAudioRecorder;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.session.SessionEventRepository;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Realtime 음성 대화 계정 삭제 참가자 통합 시험(U3 L4 · U3-J16 · S-9 · AC6.4.7).
 *
 * <p>증명: 보호자 G의 전사 원문 비우기 · 원음 파일 · 세션 디렉터리 삭제와 메타 표시 · 끊김 구간 · 끊김 사건 · 연결 원장 삭제, 다른 보호자 H의 행 ·
 * 파일은 그대로, 다시 불러도 같은 결과(0건), 지우지 못한 파일은 표시하지 않고 예외로 알린 뒤 다음 호출이 마저 지움.
 */
@Tag("voice-session")
@Tag("db")
@org.junit.jupiter.api.Tag("service-guardrail")
class RealtimeVoiceDeletionParticipantIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    private static RealtimeDb db;

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(T0);
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final EventLogger events = TestEventLoggers.strict(clock, logs, metrics);
    private TranscriptRepository transcripts;
    private SessionGapRepository gaps;
    private DeviceDisconnectRepository disconnects;
    private DeviceConnectionRepository connections;
    private AudioRecordingRepository recordings;
    private FileAudioRecorder recorder;
    private AsyncRecorder transcriptRecorder;
    private RealtimeVoiceDeletionParticipant participant;

    private final UUID guardian = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();
    private final UUID otherDevice = UUID.randomUUID();
    private UUID first;
    private UUID second;
    private UUID otherSession;

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
    void setUp() throws Exception {
        db.wipe();
        transcripts = new TranscriptRepository(db.jdbc());
        gaps = new SessionGapRepository(db.jdbc());
        disconnects = new DeviceDisconnectRepository(db.jdbc());
        connections = new DeviceConnectionRepository(db.jdbc());
        recordings = new AudioRecordingRepository(db.jdbc());
        recorder = new FileAudioRecorder(recordings, db.transactions(), new DbRetry(2, () -> 0L, duration -> { }), events,
                metrics, clock, new ManualLauncher(), root, 1_000_000, 100, Duration.ofSeconds(5));
        transcriptRecorder = new AsyncRecorder(new AsyncRecorder.JdbcStore(transcripts,
                new SessionEventRepository(db.jdbc()), connections, db.transactions(), clock), TaskLauncher.inline(),
                new LatencyRecorder(events, metrics, clock, 100, Duration.ofMinutes(10)), events, metrics, clock, 200, 2);
        participant = new RealtimeVoiceDeletionParticipant(transcripts, gaps, disconnects, connections, recordings,
                recorder, new ActivitySessionRepository(db.jdbc()), transcriptRecorder,
                new DbRetry(2, () -> 0L, duration -> { }), new DataSourceTransactionManager(db.dataSource()), events, clock,
                Duration.ofSeconds(5));

        first = insertSession(guardian, device);
        second = insertSession(guardian, device);
        otherSession = insertSession(other, otherDevice);
        for (UUID session : List.of(first, second, otherSession)) {
            db.inTransaction(() -> transcripts.upsertAll(List.of(
                    TranscriptEntry.childFinal(session, 1, "공룡 좋아", T0, T0, false),
                    TranscriptEntry.finalized(session, 2, Speaker.AI, "나도 공룡이 좋아", true, T0, T0)), T0));
        }
        record(first, true);
        record(otherSession, false);
        db.inTransaction(() -> {
            gaps.open(UUID.randomUUID(), first, SessionGapRepository.Kind.DISCONNECT, T0, T0);
            gaps.open(UUID.randomUUID(), otherSession, SessionGapRepository.Kind.DISCONNECT, T0, T0);
            disconnects.record(new DeviceDisconnectRepository.DisconnectEvent(UUID.randomUUID(), device, UUID.randomUUID(),
                    DeviceDisconnectRepository.Kind.CLOSED, 1006, T0), T0);
            disconnects.record(new DeviceDisconnectRepository.DisconnectEvent(UUID.randomUUID(), otherDevice,
                    UUID.randomUUID(), DeviceDisconnectRepository.Kind.SILENT, null, T0), T0);
            connections.handover(device, UUID.randomUUID(), guardian, UUID.randomUUID(), T0);
            connections.handover(otherDevice, UUID.randomUUID(), other, UUID.randomUUID(), T0);
        });
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void oneGuardiansVoiceRecordsAreErasedAndAnotherGuardiansStayUntouched() {
        clock.advance(Duration.ofMinutes(5));

        Map<String, Integer> counts = participant.deleteByGuardian(guardian, UUID.randomUUID());

        assertThat(counts).containsEntry(RealtimeVoiceDeletionParticipant.TRANSCRIPT, 4)
                .containsEntry(RealtimeVoiceDeletionParticipant.AUDIO_RECORDING, 2)
                .containsEntry(RealtimeVoiceDeletionParticipant.AUDIO_FILES, 2)
                .containsEntry(RealtimeVoiceDeletionParticipant.SESSION_GAP, 1)
                .containsEntry(RealtimeVoiceDeletionParticipant.DISCONNECT_EVENT, 1)
                .containsEntry(RealtimeVoiceDeletionParticipant.DEVICE_CONNECTION, 1);
        // 전사: 원문이 비고 지워진 행은 조회에서 빠진다(행 번호는 남는다)
        assertThat(transcripts.findAll(first)).isEmpty();
        assertThat(transcripts.findAll(second)).isEmpty();
        assertThat(rawTexts(first)).containsOnly("");
        assertThat(transcripts.findAll(otherSession)).extracting(TranscriptRow::text)
                .containsExactly("공룡 좋아", "나도 공룡이 좋아");
        // 원음: 파일 · 세션 디렉터리가 사라지고 메타는 삭제 시각과 함께 남는다
        assertThat(Files.exists(root.resolve(first.toString()))).isFalse();
        assertThat(recordings.findBySession(first)).hasSize(2)
                .allSatisfy(row -> assertThat(row.deletedAt()).isEqualTo(clock.now()));
        assertThat(Files.exists(recorder.resolve(otherSession + "/child.pcm"))).isTrue();
        assertThat(recordings.findLiveByGuardian(other)).hasSize(1);
        // 구간 · 끊김 사건 · 연결 원장
        assertThat(gaps.findBySession(first)).isEmpty();
        assertThat(gaps.findBySession(otherSession)).hasSize(1);
        assertThat(disconnects.findByDevice(device)).isEmpty();
        assertThat(disconnects.findByDevice(otherDevice)).hasSize(1);
        assertThat(connections.find(device)).isEmpty();
        assertThat(connections.find(otherDevice)).isPresent();
        assertThat(logs.events(EventCode.APP_ERROR)).isEmpty();

        // 다시 불러도 같은 결과(멱등) — 바뀐 것이 없다
        Map<String, Integer> again = participant.deleteByGuardian(guardian, UUID.randomUUID());
        assertThat(again.values()).containsOnly(0);
        assertThat(recordings.findBySession(first)).allSatisfy(row ->
                assertThat(row.deletedAt()).as("첫 삭제 시각이 남는다").isEqualTo(T0.plus(Duration.ofMinutes(5))));
    }

    @Test
    void aFileThatCannotBeDeletedIsReportedLeftUnmarkedAndDeletedOnTheNextCall() throws Exception {
        // 아이 파일 자리를 비어 있지 않은 디렉터리로 바꿔 지우기가 실패하게 한다
        Path child = recorder.resolve(first + "/child.pcm");
        Files.delete(child);
        Files.createDirectories(child.resolve("stuck"));

        assertThatThrownBy(() -> participant.deleteByGuardian(guardian, UUID.randomUUID()))
                .isInstanceOf(RealtimeVoiceDeletionParticipant.AudioFilesRemainException.class)
                .hasMessageNotContaining(first.toString());

        // DB 정리는 끝났고, 지우지 못한 아이 파일 메타만 살아 있다
        assertThat(transcripts.findAll(first)).isEmpty();
        assertThat(connections.find(device)).isEmpty();
        assertThat(recordings.findLiveByGuardian(guardian)).singleElement()
                .satisfies(row -> assertThat(row.storagePath()).isEqualTo(first + "/child.pcm"));
        assertThat(logs.events(EventCode.APP_ERROR)).singleElement().satisfies(event ->
                assertThat(LogCapture.attrs(event)).containsEntry("errorKind", "RecordingDeleteFailed"));

        Files.delete(child.resolve("stuck"));
        Map<String, Integer> retry = participant.deleteByGuardian(guardian, UUID.randomUUID());

        assertThat(retry).containsEntry(RealtimeVoiceDeletionParticipant.AUDIO_RECORDING, 1)
                .containsEntry(RealtimeVoiceDeletionParticipant.AUDIO_FILES, 1);
        assertThat(recordings.findLiveByGuardian(guardian)).isEmpty();
        assertThat(Files.exists(root.resolve(first.toString()))).isFalse();
    }

    @Test
    void aMetaRowWhoseFileIsAlreadyGoneIsStillMarked() throws Exception {
        Files.delete(recorder.resolve(first + "/ai.pcm"));

        Map<String, Integer> counts = participant.deleteByGuardian(guardian, UUID.randomUUID());

        assertThat(counts).containsEntry(RealtimeVoiceDeletionParticipant.AUDIO_RECORDING, 2)
                .containsEntry(RealtimeVoiceDeletionParticipant.AUDIO_FILES, 1);
        assertThat(recordings.findLiveByGuardian(guardian)).isEmpty();
    }

    /**
     * 리뷰 반영 8: 진행 중 세션은 지우기 전에 기록을 막는다 — 지운 뒤 실행기가 계속 넘기는 원음 · 전사가 새 파일 · 메타 · 전사 행으로 되살아나지
     * 않고, 열려 있던 원음 파일도 닫힌 뒤 지워진다(다시 부르기에 기대지 않는다).
     */
    @Test
    void anInProgressSessionsRecordingIsBlockedBeforeItsRecordsAreErased() {
        UUID live = insertSession(guardian, UUID.randomUUID(), "ACTIVE");
        var handle = recorder.open(live);
        handle.child(new byte[960]);
        recorder.drainNow();
        assertThat(recordings.findBySession(live)).as("아이 파일만 열려 있다").hasSize(1);
        assertThat(transcriptRecorder.putTranscript(TranscriptEntry.childFinal(live, 1, "공룡 좋아", T0, T0, false))).isTrue();
        assertThat(transcripts.findAll(live)).hasSize(1);

        participant.deleteByGuardian(guardian, UUID.randomUUID());

        // 실행기는 아직 돌고 있어 원음 · 전사를 계속 넘긴다
        handle.child(new byte[960]);
        handle.ai(new byte[960]);
        recorder.drainNow();
        assertThat(transcriptRecorder.putTranscript(TranscriptEntry.childFinal(live, 2, "또 말했어", T0, T0, false)))
                .isTrue();
        transcriptRecorder.tick();
        handle.close(T0);
        recorder.drainNow();

        assertThat(recordings.findLiveByGuardian(guardian)).as("새 원음 메타가 생기지 않는다").isEmpty();
        assertThat(recordings.findBySession(live)).hasSize(1);
        assertThat(Files.exists(root.resolve(live.toString()))).as("세션 디렉터리가 되살아나지 않는다").isFalse();
        assertThat(rawTexts(live)).as("지운 뒤 들어온 전사는 쓰지 않는다").containsExactly("");
        assertThat(transcriptRecorder.heldRows(live)).isZero();
        assertThat(logs.events(EventCode.APP_ERROR)).isEmpty();
    }

    /** 원음 메타 + 파일(기록기를 지금 스레드에서 돌린다). */
    private void record(UUID session, boolean withAi) {
        var handle = recorder.open(session);
        handle.child(new byte[960]);
        if (withAi) {
            handle.ai(new byte[960]);
        }
        handle.close(T0);
        recorder.drainNow();
    }

    private List<String> rawTexts(UUID session) {
        return db.jdbc().queryForList("select text from realtime.realtime_transcript where session_id = :id",
                Map.of("id", session), String.class);
    }

    private static UUID insertSession(UUID guardianId, UUID deviceId) {
        return insertSession(guardianId, deviceId, "ENDED");
    }

    private static UUID insertSession(UUID guardianId, UUID deviceId, String status) {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), guardianId, UUID.randomUUID(), deviceId,
                ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("1.0", true, true, false, true), new ChildContext("하늘", 8, List.of("공룡")), null,
                null);
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        // 진행 중 세션은 보호자 · 기기당 하나라 끝난 세션으로 둔다(삭제는 끝난 기록을 지운다)
        db.jdbc().update("update realtime.realtime_activity_session set status = :status,"
                + " status_version = status_version + 1 where id = :id", Map.of("id", session.id(), "status", status));
        return session.id();
    }
}
