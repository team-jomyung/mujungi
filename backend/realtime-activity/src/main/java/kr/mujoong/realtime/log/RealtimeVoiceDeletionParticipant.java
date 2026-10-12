package kr.mujoong.realtime.log;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.gateway.DeviceDisconnectRepository;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.recording.AudioRecordingRepository;
import kr.mujoong.realtime.recording.AudioRecordingRepository.AudioRecordingRow;
import kr.mujoong.realtime.recording.FileAudioRecorder;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Realtime 음성 대화 계정 삭제 참가자 (U3 L4 · U3-J16 · S-9 · BR5.14 (d) · BR8.1 · AC6.4.7).
 *
 * <p>한 보호자의 음성 대화 기록을 realtime 스키마와 원음 디렉터리에서 지운다. 호출 시점 · 순서는 U4(Realtime DeletionRequested 처리기)가
 * 정하고, 어느 시점에 불려도 결과가 같다(멱등).
 *
 * <ol>
 *   <li>진행 중 세션의 기록 차단(리뷰 반영 8): 원음 · 전사 기록기가 그 세션의 새 기록을 받지 않게 하고 열린 파일 · 진행 중 쓰기를 마친다.
 *   <li>원음 파일: 살아 있는 메타 행마다 파일을 지운다(없으면 건너뜀). 비게 된 세션 디렉터리도 지운다.
 *   <li>한 트랜잭션: 전사 원문 비우기 + deleted_at(행 번호는 남김) · 끊김 구간 행 삭제 · 끊김 사건 행 삭제(보호자 기기) · 연결 원장 행 삭제 ·
 *       파일을 지운 원음 메타에 deleted_at(메타 행은 남김 — 메타만으로는 개인정보가 아니다).
 *   <li>지우지 못한 파일이 있으면 그 메타는 표시하지 않고 마지막에 예외를 던진다 — 부르는 쪽이 다시 부르면 남은 것만 다시 지운다.
 * </ol>
 *
 * <p>세션 행(realtime_activity_session)과 메시징 행은 각자의 참가자(U4 · {@code RealtimeMessagingDeletionParticipant})가 맡는다. 원문 ·
 * 경로는 로그에 쓰지 않는다.
 */
@Component
public class RealtimeVoiceDeletionParticipant {

    /** 반환 지도 키: 원문을 비운 전사 행 수. */
    public static final String TRANSCRIPT = "realtime_transcript";
    /** 반환 지도 키: 삭제 표시한 원음 메타 행 수. */
    public static final String AUDIO_RECORDING = "realtime_audio_recording";
    /** 반환 지도 키: 지운 원음 파일 수. */
    public static final String AUDIO_FILES = "audio_files";
    /** 반환 지도 키: 지운 끊김 구간 행 수. */
    public static final String SESSION_GAP = "realtime_session_gap";
    /** 반환 지도 키: 지운 끊김 사건 행 수. */
    public static final String DISCONNECT_EVENT = "realtime_device_disconnect_event";
    /** 반환 지도 키: 지운 연결 원장 행 수. */
    public static final String DEVICE_CONNECTION = "realtime_device_connection";

    /** 원음 파일을 다 지우지 못했다(남은 것은 다시 부르면 지운다). */
    public static final class AudioFilesRemainException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public AudioFilesRemainException(int remaining) {
            super("지우지 못한 원음 파일 " + remaining + "개");
        }
    }

    private final TranscriptRepository transcripts;
    private final SessionGapRepository gaps;
    private final DeviceDisconnectRepository disconnects;
    private final DeviceConnectionRepository connections;
    private final AudioRecordingRepository recordings;
    private final FileAudioRecorder recorder;
    private final ActivitySessionRepository sessions;
    private final AsyncRecorder transcriptRecorder;
    private final Duration blockWait;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;
    private final EventLogger events;
    private final MujoongClock clock;

    @Autowired
    public RealtimeVoiceDeletionParticipant(TranscriptRepository transcripts, SessionGapRepository gaps,
            DeviceDisconnectRepository disconnects, DeviceConnectionRepository connections,
            AudioRecordingRepository recordings, FileAudioRecorder recorder, ActivitySessionRepository sessions,
            AsyncRecorder transcriptRecorder, DbRetry dbRetry, PlatformTransactionManager transactionManager,
            EventLogger events, MujoongClock clock, RealtimeProperties properties) {
        this(transcripts, gaps, disconnects, connections, recordings, recorder, sessions, transcriptRecorder, dbRetry,
                transactionManager, events, clock, properties.limits().dbStatementTimeout());
    }

    /**
     * @param blockWait 진행 중 세션 기록을 막을 때 진행 중인 전사 플러시가 끝나기를 기다리는 상한
     */
    public RealtimeVoiceDeletionParticipant(TranscriptRepository transcripts, SessionGapRepository gaps,
            DeviceDisconnectRepository disconnects, DeviceConnectionRepository connections,
            AudioRecordingRepository recordings, FileAudioRecorder recorder, ActivitySessionRepository sessions,
            AsyncRecorder transcriptRecorder, DbRetry dbRetry, PlatformTransactionManager transactionManager,
            EventLogger events, MujoongClock clock, Duration blockWait) {
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.gaps = Objects.requireNonNull(gaps, "gaps");
        this.disconnects = Objects.requireNonNull(disconnects, "disconnects");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.recordings = Objects.requireNonNull(recordings, "recordings");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.transcriptRecorder = Objects.requireNonNull(transcriptRecorder, "transcriptRecorder");
        this.blockWait = Objects.requireNonNull(blockWait, "blockWait");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 한 보호자의 음성 대화 기록을 지운다.
     *
     * @param deletionRequestId 삭제 요청 id(기록 상관 용도)
     * @return 표 · 파일마다 지운(표시한) 수
     * @throws AudioFilesRemainException 지우지 못한 원음 파일이 남았다(DB 정리는 끝났다)
     */
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        blockLiveSessions(guardianId);
        List<UUID> filesGone = new ArrayList<>();
        int filesDeleted = 0;
        int remaining = 0;
        for (AudioRecordingRow row : recordings.findLiveByGuardian(guardianId)) {
            try {
                Path file = recorder.resolve(row.storagePath());
                if (Files.deleteIfExists(file)) {
                    filesDeleted++;
                }
                removeIfEmpty(file.getParent());
                filesGone.add(row.id());
            } catch (IOException | RuntimeException e) {
                remaining++;
                events.appError(EventLog.Component.REALTIME_MAINTENANCE, "RecordingDeleteFailed");
            }
        }
        int files = filesDeleted;
        Map<String, Integer> counts = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            Instant now = clock.now();
            Map<String, Integer> deleted = new LinkedHashMap<>();
            deleted.put(TRANSCRIPT, transcripts.eraseByGuardian(guardianId, now));
            deleted.put(AUDIO_RECORDING, recordings.markDeleted(filesGone, now));
            deleted.put(AUDIO_FILES, files);
            deleted.put(SESSION_GAP, gaps.deleteByGuardian(guardianId));
            deleted.put(DISCONNECT_EVENT, disconnects.deleteByGuardian(guardianId));
            deleted.put(DEVICE_CONNECTION, connections.deleteByGuardian(guardianId));
            return Map.copyOf(deleted);
        }));
        if (remaining > 0) {
            throw new AudioFilesRemainException(remaining);
        }
        return counts;
    }

    /**
     * 리뷰 반영 8: 진행 중 세션은 지우기 전에 기록부터 막는다 — 원음 기록기(새 메타 · 파일 · 조각 거부, 열린 파일 닫기)와 전사 기록기(대기 칸 버림 ·
     * 새 칸 거부 · 진행 중 플러시 끝 대기). 막은 뒤에 조회 · 삭제하므로 실행기가 계속 넘기는 원음 · 전사가 지운 기록을 되살리지 않는다. 기다림 상한을
     * 넘기면 앱 오류 한 줄만 남기고 진행한다(차단 자체는 걸려 있다). 세션 전이(진행 중 세션 끝내기)는 U4 삭제 지휘 몫이다.
     */
    private void blockLiveSessions(UUID guardianId) {
        List<UUID> live = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> sessions.findInProgressIds(guardianId));
        for (UUID sessionId : live == null ? List.<UUID>of() : live) {
            boolean audioClosed = recorder.block(sessionId);
            boolean transcriptsSettled = transcriptRecorder.discardTranscripts(sessionId, blockWait);
            if (!audioClosed || !transcriptsSettled) {
                events.appError(EventLog.Component.REALTIME_MAINTENANCE, "RecordingBlockTimeout");
            }
        }
    }

    /** 세션 디렉터리가 비었으면 지운다(기록 디렉터리 자체는 지우지 않는다). */
    private void removeIfEmpty(Path dir) throws IOException {
        if (dir == null || dir.equals(recorder.directory()) || !dir.startsWith(recorder.directory())) {
            return;
        }
        try {
            Files.deleteIfExists(dir);
        } catch (DirectoryNotEmptyException e) {
            // 다른 화자 파일이 아직 있다 — 그 파일을 지울 때 다시 본다
        }
    }
}
