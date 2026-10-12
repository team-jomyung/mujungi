package kr.mujoong.realtime.recording;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_audio_recording} 데이터 접근 — 원음 파일 메타 (U3 L1 · components AudioRecording · U3-R9 · U3-J14 · K6 ·
 * AC6.4.7).
 *
 * <p>세션 · 화자당 행 하나(유일 제약). 기록기가 파일을 열 때 넣고(byte_count 0 · ended_at 없음), 닫을 때 바이트 수와 끝 시각을 쓴다.
 * 삭제 참가자는 파일을 지운 뒤 {@code deleted_at}을 쓴다(행은 남긴다 — 메타만으로는 개인정보가 아니다). 저장 경로는 설정 디렉터리 기준
 * 상대 경로라 디렉터리를 옮겨도 메타가 맞는다. 시각은 호출자가 Clock에서 읽은 값만 쓴다(R2).
 */
@Repository
public class AudioRecordingRepository {

    /** 화자(V2 CHECK). */
    public enum Speaker {
        CHILD,
        AI
    }

    /** 한 행. */
    public record AudioRecordingRow(UUID id, UUID sessionId, Speaker speaker, String storagePath, String format,
            long byteCount, Instant startedAt, Instant endedAt, Instant deletedAt) {

        /** 닫힌 파일인가. */
        public boolean isFinished() {
            return endedAt != null;
        }

        @Override
        public String toString() {
            return "AudioRecordingRow[sessionId=" + sessionId + ", speaker=" + speaker + ", bytes=" + byteCount + "]";
        }
    }

    private static final String COLUMNS =
            "id, session_id, speaker, storage_path, format, byte_count, started_at, ended_at, deleted_at";

    private static final String INSERT = "insert into realtime.realtime_audio_recording"
            + " (id, session_id, speaker, storage_path, format, byte_count, started_at, created_at)"
            + " values (:id, :sessionId, :speaker, :storagePath, :format, 0, :startedAt, :now)";

    private static final String FINISH = "update realtime.realtime_audio_recording"
            + " set byte_count = :byteCount, ended_at = :endedAt where id = :id and ended_at is null";

    private static final String SELECT_BY_SESSION = "select " + COLUMNS + " from realtime.realtime_audio_recording"
            + " where session_id = :sessionId order by speaker";

    private static final String SELECT_LIVE_BY_GUARDIAN = "select r.id, r.session_id, r.speaker, r.storage_path, r.format,"
            + " r.byte_count, r.started_at, r.ended_at, r.deleted_at from realtime.realtime_audio_recording r"
            + " join realtime.realtime_activity_session s on s.id = r.session_id"
            + " where s.guardian_id = :guardianId and r.deleted_at is null order by r.session_id, r.speaker";

    private static final String MARK_DELETED = "update realtime.realtime_audio_recording"
            + " set deleted_at = :now where id = any(:ids) and deleted_at is null";

    private final NamedParameterJdbcTemplate jdbc;

    public AudioRecordingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 파일을 열었다(byte_count 0 · ended_at 없음). 같은 세션 · 화자의 행이 이미 있으면 유일 제약 위반이다.
     *
     * @param now created_at(Clock)
     */
    public void insert(UUID id, UUID sessionId, Speaker speaker, String storagePath, String format, Instant startedAt,
            Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", Objects.requireNonNull(id, "id"));
        ClockParams.uuid(params, "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
        ClockParams.text(params, "speaker", Objects.requireNonNull(speaker, "speaker").name());
        ClockParams.text(params, "storagePath", Objects.requireNonNull(storagePath, "storagePath"));
        ClockParams.text(params, "format", Objects.requireNonNull(format, "format"));
        ClockParams.instant(params, "startedAt", Objects.requireNonNull(startedAt, "startedAt"));
        jdbc.update(INSERT, params);
    }

    /** 파일을 닫았다. 이미 닫힌 행은 바꾸지 않는다(멱등). 썼으면 true. */
    public boolean finish(UUID id, long byteCount, Instant endedAt) {
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount는 0 이상이다");
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "id", Objects.requireNonNull(id, "id"));
        params.addValue("byteCount", byteCount);
        ClockParams.instant(params, "endedAt", Objects.requireNonNull(endedAt, "endedAt"));
        return jdbc.update(FINISH, params) == 1;
    }

    /** 세션의 원음 메타(화자 순). */
    public List<AudioRecordingRow> findBySession(UUID sessionId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
        return jdbc.query(SELECT_BY_SESSION, params, ROW_MAPPER);
    }

    /** 세션 · 화자 하나. */
    public Optional<AudioRecordingRow> find(UUID sessionId, Speaker speaker) {
        return findBySession(sessionId).stream().filter(row -> row.speaker() == speaker).findFirst();
    }

    /** 보호자의 세션들에 남아 있는(지우지 않은) 원음 메타 — 삭제 참가자가 파일을 찾는 목록(U3-J16). */
    public List<AudioRecordingRow> findLiveByGuardian(UUID guardianId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        return jdbc.query(SELECT_LIVE_BY_GUARDIAN, params, ROW_MAPPER);
    }

    /** 파일을 지운 뒤 deleted_at을 쓴다(멱등). 바뀐 행 수. */
    public int markDeleted(Collection<UUID> ids, Instant now) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuidArray(params, "ids", ids);
        return jdbc.update(MARK_DELETED, params);
    }

    private static final RowMapper<AudioRecordingRow> ROW_MAPPER = AudioRecordingRepository::mapRow;

    private static AudioRecordingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AudioRecordingRow(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "session_id"),
                Speaker.valueOf(rs.getString("speaker")),
                rs.getString("storage_path"),
                rs.getString("format"),
                rs.getLong("byte_count"),
                ClockParams.instant(rs, "started_at"),
                ClockParams.instant(rs, "ended_at"),
                ClockParams.instant(rs, "deleted_at"));
    }
}
