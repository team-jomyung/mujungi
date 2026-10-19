package kr.mujoong.realtime.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.recording.AudioRecordingRepository.AudioRecordingRow;
import kr.mujoong.realtime.recording.AudioRecordingRepository.Speaker;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 원음 파일 메타 데이터 접근 통합 시험(U3 L1 · components AudioRecording · U3-J14 · U3-J16).
 *
 * <p>증명: 세션 · 화자당 행 하나(유일 제약) · 파일 열기(0바이트 · 끝 없음) → 닫기(바이트 · 끝 시각, 한 번만) · 세션 외래 키 · 보호자 기준
 * 살아 있는 메타 목록과 삭제 표시의 멱등성(삭제 참가자 K6 · AC6.4.7).
 */
@Tag("voice-session")
@Tag("db")
class AudioRecordingRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final String FORMAT = "pcm_s16le_24000_mono";

    private static RealtimeDb db;
    private AudioRecordingRepository recordings;
    private UUID guardianId;
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
        recordings = new AudioRecordingRepository(db.jdbc());
        guardianId = UUID.randomUUID();
        sessionId = insertSession(guardianId);
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void openingAFileInsertsAZeroByteRowAndFinishingWritesBytesAndEndOnlyOnce() {
        UUID id = UUID.randomUUID();
        String path = sessionId + "/child.pcm";
        recordings.insert(id, sessionId, Speaker.CHILD, path, FORMAT, T0, T0);

        AudioRecordingRow open = recordings.find(sessionId, Speaker.CHILD).orElseThrow();
        assertThat(open.id()).isEqualTo(id);
        assertThat(open.storagePath()).isEqualTo(path);
        assertThat(open.format()).isEqualTo(FORMAT);
        assertThat(open.byteCount()).isZero();
        assertThat(open.startedAt()).isEqualTo(T0);
        assertThat(open.endedAt()).isNull();
        assertThat(open.deletedAt()).isNull();
        assertThat(open.isFinished()).isFalse();
        // 문자열 표현에 저장 경로를 내지 않는다
        assertThat(open.toString()).doesNotContain(path).contains(sessionId.toString());

        assertThat(recordings.finish(id, 96_000L, T0.plusSeconds(2))).isTrue();
        AudioRecordingRow finished = recordings.find(sessionId, Speaker.CHILD).orElseThrow();
        assertThat(finished.byteCount()).isEqualTo(96_000L);
        assertThat(finished.endedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(finished.isFinished()).isTrue();

        // 닫힌 행은 다시 닫지 못한다(멱등)
        assertThat(recordings.finish(id, 1L, T0.plusSeconds(3))).isFalse();
        assertThat(recordings.find(sessionId, Speaker.CHILD).orElseThrow().byteCount()).isEqualTo(96_000L);
        assertThat(recordings.finish(UUID.randomUUID(), 1L, T0.plusSeconds(3))).isFalse();
        assertThatThrownBy(() -> recordings.finish(id, -1L, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(recordings.find(sessionId, Speaker.AI)).isEmpty();
    }

    @Test
    void oneRowPerSessionAndSpeakerAndTheSessionMustExist() {
        recordings.insert(UUID.randomUUID(), sessionId, Speaker.CHILD, sessionId + "/child.pcm", FORMAT, T0, T0);
        assertThatThrownBy(() -> recordings.insert(UUID.randomUUID(), sessionId, Speaker.CHILD, sessionId + "/child-2.pcm",
                FORMAT, T0.plusSeconds(1), T0.plusSeconds(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        recordings.insert(UUID.randomUUID(), sessionId, Speaker.AI, sessionId + "/ai.pcm", FORMAT, T0.plusSeconds(1),
                T0.plusSeconds(1));
        assertThat(recordings.findBySession(sessionId)).extracting(AudioRecordingRow::speaker)
                .containsExactly(Speaker.AI, Speaker.CHILD);

        // 세션 행이 없으면 외래 키가 거부한다(세션 없는 원음 메타 없음)
        UUID unknownSession = UUID.randomUUID();
        assertThatThrownBy(() -> recordings.insert(UUID.randomUUID(), unknownSession, Speaker.CHILD,
                unknownSession + "/child.pcm", FORMAT, T0, T0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(recordings.findBySession(unknownSession)).isEmpty();
    }

    @Test
    void liveRowsOfAGuardianShrinkAsTheDeletionParticipantMarksThem() {
        UUID childRow = UUID.randomUUID();
        UUID aiRow = UUID.randomUUID();
        recordings.insert(childRow, sessionId, Speaker.CHILD, sessionId + "/child.pcm", FORMAT, T0, T0);
        recordings.insert(aiRow, sessionId, Speaker.AI, sessionId + "/ai.pcm", FORMAT, T0, T0);
        UUID otherGuardian = UUID.randomUUID();
        UUID otherSession = insertSession(otherGuardian);
        UUID otherRow = UUID.randomUUID();
        recordings.insert(otherRow, otherSession, Speaker.CHILD, otherSession + "/child.pcm", FORMAT, T0, T0);

        assertThat(recordings.findLiveByGuardian(guardianId)).extracting(AudioRecordingRow::id)
                .containsExactly(aiRow, childRow);
        assertThat(recordings.findLiveByGuardian(otherGuardian)).extracting(AudioRecordingRow::id).containsExactly(otherRow);
        assertThat(recordings.findLiveByGuardian(UUID.randomUUID())).isEmpty();

        assertThat(recordings.markDeleted(List.of(), T0.plusSeconds(10))).isZero();
        assertThat(recordings.markDeleted(List.of(childRow), T0.plusSeconds(10))).isEqualTo(1);
        // 다시 표시해도 바뀌지 않는다(멱등) — 첫 삭제 시각이 남는다
        assertThat(recordings.markDeleted(List.of(childRow), T0.plusSeconds(20))).isZero();

        assertThat(recordings.findLiveByGuardian(guardianId)).extracting(AudioRecordingRow::id).containsExactly(aiRow);
        AudioRecordingRow deleted = recordings.find(sessionId, Speaker.CHILD).orElseThrow();
        assertThat(deleted.deletedAt()).isEqualTo(T0.plusSeconds(10));
        // 행은 남는다(메타만으로는 개인정보가 아니다)
        assertThat(recordings.findBySession(sessionId)).hasSize(2);
        // 다른 보호자의 행은 그대로
        assertThat(recordings.findLiveByGuardian(otherGuardian)).hasSize(1);
    }

    private static UUID insertSession(UUID guardianId) {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), guardianId, UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("하늘", 8, List.of("공룡")), null,
                null);
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        return session.id();
    }
}
