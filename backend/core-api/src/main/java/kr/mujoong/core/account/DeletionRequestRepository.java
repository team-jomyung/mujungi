package kr.mujoong.core.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 계정 삭제 요청 {@code core.core_data_deletion_request} 저장소 (U2 AccountDeletion · AC1.6.1 · AC1.6.7 · AC1.6.8 · R10 · R11).
 *
 * <p>상태는 REQUESTED → DONE 하나뿐이고 진행은 시각 열(session_end_requested_at · core_cleared_at · realtime_requested_at ·
 * realtime_completed_at)로 남긴다. 트랜잭션은 호출자가 연다. 시각은 앱 Clock 값이다. last_error는 예외 종류 이름만 담는다.
 */
@Repository
public class DeletionRequestRepository {

    /** 접수 상태. */
    public static final String STATUS_REQUESTED = "REQUESTED";
    /** 완료 상태. */
    public static final String STATUS_DONE = "DONE";

    private static final String COLUMNS = """
            id, guardian_id, child_id, status, requested_at, session_end_requested_at, core_cleared_at,
            realtime_requested_at, realtime_completed_at, completed_at, attempt_count, next_attempt_at, last_error
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeletionRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 요청 한 행. */
    public record DeletionRequest(
            UUID id,
            UUID guardianId,
            UUID childId,
            String status,
            Instant requestedAt,
            Instant sessionEndRequestedAt,
            Instant coreClearedAt,
            Instant realtimeRequestedAt,
            Instant realtimeCompletedAt,
            Instant completedAt,
            int attemptCount,
            Instant nextAttemptAt,
            String lastError) {

        public boolean isDone() {
            return STATUS_DONE.equals(status);
        }
    }

    /** 새 REQUESTED 행(보호자당 하나, 부분 유일 색인). 지휘가 곧바로 집도록 next_attempt_at = 접수 시각. */
    public void insertRequested(UUID id, UUID guardianId, UUID childId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", Objects.requireNonNull(id, "id"));
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        ClockParams.uuid(params, "childId", childId);
        jdbc.update("""
                insert into core.core_data_deletion_request (id, guardian_id, child_id, status, requested_at, blocked_at,
                                                             attempt_count, next_attempt_at, created_at, updated_at)
                values (:id, :guardianId, :childId, 'REQUESTED', :now, :now, 0, :now, :now, :now)
                """, params);
    }

    /** 보호자의 열린(REQUESTED) 요청. */
    public Optional<DeletionRequest> findOpenByGuardian(UUID guardianId) {
        List<DeletionRequest> rows = jdbc.query("select " + COLUMNS
                + " from core.core_data_deletion_request where guardian_id = :guardianId and status = 'REQUESTED'",
                ClockParams.uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")),
                DeletionRequestRepository::map);
        return rows.stream().findFirst();
    }

    /** id로 읽는다. */
    public Optional<DeletionRequest> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from core.core_data_deletion_request where id = :id",
                ClockParams.uuid(new MapSqlParameterSource(), "id", Objects.requireNonNull(id, "id")),
                DeletionRequestRepository::map).stream().findFirst();
    }

    /** 처리할 때가 된 REQUESTED 요청 id(오래된 것부터). */
    public List<UUID> findDueIds(Instant now, int limit) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.integer(params, "limit", limit);
        return jdbc.queryForList("""
                select id from core.core_data_deletion_request
                 where status = 'REQUESTED' and next_attempt_at <= :now
                 order by next_attempt_at, id
                 limit :limit
                """, params, UUID.class);
    }

    /**
     * 처리할 요청을 선점한다: 때가 된 REQUESTED 행의 next_attempt_at을 {@code leaseUntil}로 밀고 그 행을 돌려준다. 다른 인스턴스 · 바퀴가 잠그고
     * 있거나 이미 선점 · 완료됐으면 빈 값이다(FOR UPDATE SKIP LOCKED, Outbox 선점과 같은 방식 RD-8). 트랜잭션은 호출자가 연다.
     */
    public Optional<DeletionRequest> claim(UUID id, Instant now, Instant leaseUntil) {
        MapSqlParameterSource params = progress(id, now, leaseUntil);
        Optional<DeletionRequest> row = jdbc.query("select " + COLUMNS + """
                 from core.core_data_deletion_request
                where id = :id and status = 'REQUESTED' and next_attempt_at <= :now
                for update skip locked
                """, params, DeletionRequestRepository::map).stream().findFirst();
        row.ifPresent(found -> jdbc.update("""
                update core.core_data_deletion_request set next_attempt_at = :next, updated_at = :now where id = :id
                """, params));
        return row;
    }

    /** 진행 세션 끝내기를 보낸 시각 · 다음 확인 시각. */
    public int markSessionEndRequested(UUID id, Instant now, Instant nextAttemptAt) {
        MapSqlParameterSource params = progress(id, now, nextAttemptAt);
        return jdbc.update("""
                update core.core_data_deletion_request
                   set session_end_requested_at = coalesce(session_end_requested_at, :now),
                       next_attempt_at = :next, updated_at = :now
                 where id = :id and status = 'REQUESTED'
                """, params);
    }

    /** 다음 확인 시각만 미룬다(세션 끝 · Realtime 완료 기다림). */
    public int postpone(UUID id, Instant now, Instant nextAttemptAt) {
        return jdbc.update("""
                update core.core_data_deletion_request set next_attempt_at = :next, updated_at = :now
                 where id = :id and status = 'REQUESTED'
                """, progress(id, now, nextAttemptAt));
    }

    /** Core 참가자를 마치고 DeletionRequested를 Outbox에 쓴 시각(같은 트랜잭션). */
    public int markRealtimeRequested(UUID id, Instant now, Instant nextAttemptAt) {
        return jdbc.update("""
                update core.core_data_deletion_request
                   set core_cleared_at = coalesce(core_cleared_at, :now),
                       realtime_requested_at = coalesce(realtime_requested_at, :now),
                       next_attempt_at = :next, last_error = null, updated_at = :now
                 where id = :id and status = 'REQUESTED'
                """, progress(id, now, nextAttemptAt));
    }

    /**
     * DeletionCompleted 수신(Realtime 쪽 삭제 끝). Realtime에 요청한 뒤의 열린 요청에만 반영하고 곧바로 마무리하게 next_attempt_at을 당긴다.
     *
     * @return 반영 행 수(이미 반영 · 완료 · 모르는 요청이면 0)
     */
    public int markRealtimeCompleted(UUID id, UUID guardianId, Instant completedAt, Instant now) {
        MapSqlParameterSource params = progress(id, now, now);
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        ClockParams.instant(params, "completedAt", Objects.requireNonNull(completedAt, "completedAt"));
        return jdbc.update("""
                update core.core_data_deletion_request
                   set realtime_completed_at = :completedAt, next_attempt_at = :next, updated_at = :now
                 where id = :id and guardian_id = :guardianId and status = 'REQUESTED'
                   and realtime_requested_at is not null and realtime_completed_at is null
                """, params);
    }

    /** 완료(REQUESTED → DONE). */
    public int markDone(UUID id, Instant now) {
        MapSqlParameterSource params = progress(id, now, now);
        return jdbc.update("""
                update core.core_data_deletion_request
                   set status = 'DONE', completed_at = :now, last_error = null, updated_at = :now
                 where id = :id and status = 'REQUESTED'
                """, params);
    }

    /** 실패 기록(시도 수 +1 · 다음 시각 · 예외 종류 이름). */
    public int recordFailure(UUID id, String errorKind, Instant now, Instant nextAttemptAt) {
        MapSqlParameterSource params = progress(id, now, nextAttemptAt);
        String kind = errorKind == null ? "Unknown" : errorKind;
        ClockParams.text(params, "error", kind.length() > 100 ? kind.substring(0, 100) : kind);
        return jdbc.update("""
                update core.core_data_deletion_request
                   set attempt_count = attempt_count + 1, last_error = :error, next_attempt_at = :next, updated_at = :now
                 where id = :id and status = 'REQUESTED'
                """, params);
    }

    private static MapSqlParameterSource progress(UUID id, Instant now, Instant next) {
        MapSqlParameterSource params = ClockParams.at(Objects.requireNonNull(now, "now"));
        ClockParams.uuid(params, "id", Objects.requireNonNull(id, "id"));
        ClockParams.instant(params, "next", Objects.requireNonNull(next, "next"));
        return params;
    }

    private static DeletionRequest map(ResultSet rs, int row) throws SQLException {
        return new DeletionRequest(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "guardian_id"),
                ClockParams.uuid(rs, "child_id"),
                rs.getString("status"),
                ClockParams.instant(rs, "requested_at"),
                ClockParams.instant(rs, "session_end_requested_at"),
                ClockParams.instant(rs, "core_cleared_at"),
                ClockParams.instant(rs, "realtime_requested_at"),
                ClockParams.instant(rs, "realtime_completed_at"),
                ClockParams.instant(rs, "completed_at"),
                rs.getInt("attempt_count"),
                ClockParams.instant(rs, "next_attempt_at"),
                rs.getString("last_error"));
    }
}
