package kr.mujoong.realtime.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_result_input_snapshot} 데이터 접근 — 종료 세션의 결과 입력 스냅숏 (U4b L1 · L5 · FR7.1 · BR8.1 · BR8.5 ·
 * BR9.3 · entities ResultInputSnapshot).
 *
 * <p>쓰기는 SessionRecordCaptured Outbox와 같은 트랜잭션에서만 한다(MANDATORY, BR8.1). (session_id, snapshot_version) 유일과
 * {@code ON CONFLICT DO NOTHING}으로 세션당 한 번만 쓰고(BR8.5), 이미 있으면 false를 돌려 호출자가 Outbox를 쓰지 않게 한다. 기록 본문
 * ({@code record_json})은 전사 원문을 담으므로 문자열 표현 · 로그에 넣지 않는다(BR12.1). 시각은 호출자가 Clock에서 읽어 넘긴 값만 쓴다(R2).
 */
@Repository
public class ResultInputSnapshotRepository {

    /** U4b가 쓰는 판(세션당 한 행, U4b-J15). */
    public static final int SNAPSHOT_VERSION = 1;

    /** 쓸 스냅숏 한 행. */
    public record NewSnapshot(UUID id, UUID sessionId, int snapshotVersion, SessionStatus finalStatus,
            EndReason endReason, boolean confirmed, String policyVersion, Instant cutoffAt, String snapshotHash,
            String recordJson) {

        public NewSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(finalStatus, "finalStatus");
            Objects.requireNonNull(endReason, "endReason");
            Objects.requireNonNull(policyVersion, "policyVersion");
            Objects.requireNonNull(cutoffAt, "cutoffAt");
            Objects.requireNonNull(snapshotHash, "snapshotHash");
            Objects.requireNonNull(recordJson, "recordJson");
            if (!finalStatus.isTerminal()) {
                throw new IllegalArgumentException("스냅숏은 종료 상태에서만 만든다: " + finalStatus);
            }
            if (snapshotVersion < 1) {
                throw new IllegalArgumentException("snapshotVersion은 1 이상이다");
            }
        }

        /** 기록 본문은 문자열 표현에 넣지 않는다(BR12.1). */
        @Override
        public String toString() {
            return "NewSnapshot[sessionId=" + sessionId + ", finalStatus=" + finalStatus + "]";
        }
    }

    /** 읽은 한 행. */
    public record SnapshotRow(UUID id, UUID sessionId, int snapshotVersion, SessionStatus finalStatus,
            EndReason endReason, boolean confirmed, String policyVersion, Instant cutoffAt, String snapshotHash,
            String recordJson, Instant capturedAt, Instant createdAt) {

        /** 기록 본문은 문자열 표현에 넣지 않는다(BR12.1). */
        @Override
        public String toString() {
            return "SnapshotRow[sessionId=" + sessionId + ", finalStatus=" + finalStatus + ", hash=" + snapshotHash + "]";
        }
    }

    private static final String COLUMNS = "id, session_id, snapshot_version, final_status, end_reason, confirmed,"
            + " policy_version, cutoff_at, snapshot_hash, record_json, captured_at, created_at";

    private static final String INSERT = "insert into realtime.realtime_result_input_snapshot (id, session_id,"
            + " snapshot_version, final_status, end_reason, confirmed, policy_version, cutoff_at, snapshot_hash,"
            + " record_json, captured_at, created_at)"
            + " values (:id, :sessionId, :snapshotVersion, :finalStatus, :endReason, :confirmed, :policyVersion,"
            + " :cutoffAt, :snapshotHash, cast(:recordJson as jsonb), :now, :now)"
            + " on conflict (session_id, snapshot_version) do nothing";

    private static final String SELECT_BY_SESSION = "select " + COLUMNS
            + " from realtime.realtime_result_input_snapshot where session_id = :sessionId order by snapshot_version";

    private static final String EXISTS = "select exists(select 1 from realtime.realtime_result_input_snapshot"
            + " where session_id = :sessionId)";

    private static final String DELETE_BY_SESSIONS = "delete from realtime.realtime_result_input_snapshot"
            + " where session_id = any(:sessionIds)";

    private final NamedParameterJdbcTemplate jdbc;

    public ResultInputSnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 스냅숏을 쓴다. 활성 트랜잭션 안에서만 부른다(Outbox와 한 트랜잭션, BR8.1).
     *
     * @param now captured_at · created_at(Clock)
     * @return 새로 썼으면 true, 그 세션 · 판이 이미 있으면 false(BR8.5)
     */
    public boolean insert(NewSnapshot snapshot, Instant now) {
        Objects.requireNonNull(snapshot, "snapshot");
        Transactions.requireActive("ResultInputSnapshotRepository.insert");
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", snapshot.id());
        ClockParams.uuid(params, "sessionId", snapshot.sessionId());
        ClockParams.integer(params, "snapshotVersion", snapshot.snapshotVersion());
        ClockParams.text(params, "finalStatus", snapshot.finalStatus().name());
        ClockParams.text(params, "endReason", snapshot.endReason().name());
        params.addValue("confirmed", snapshot.confirmed());
        ClockParams.text(params, "policyVersion", snapshot.policyVersion());
        ClockParams.instant(params, "cutoffAt", snapshot.cutoffAt());
        ClockParams.text(params, "snapshotHash", snapshot.snapshotHash());
        ClockParams.text(params, "recordJson", snapshot.recordJson());
        return jdbc.update(INSERT, params) == 1;
    }

    /** 그 세션의 스냅숏이 있는가. */
    public boolean exists(UUID sessionId) {
        Boolean exists = jdbc.queryForObject(EXISTS, sessionParam(sessionId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** 그 세션의 첫 판(없으면 빈 값). */
    public Optional<SnapshotRow> find(UUID sessionId) {
        return jdbc.query(SELECT_BY_SESSION, sessionParam(sessionId), ROW_MAPPER).stream().findFirst();
    }

    /** 세션들의 스냅숏을 지운다(계정 삭제 세션 참가자, BR9.3 — 세션 행보다 먼저). 지운 행 수. */
    public int deleteBySessions(List<UUID> sessionIds) {
        Objects.requireNonNull(sessionIds, "sessionIds");
        if (sessionIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuidArray(params, "sessionIds", sessionIds);
        return jdbc.update(DELETE_BY_SESSIONS, params);
    }

    private static MapSqlParameterSource sessionParam(UUID sessionId) {
        return ClockParams.uuid(new MapSqlParameterSource(), "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
    }

    private static final RowMapper<SnapshotRow> ROW_MAPPER = ResultInputSnapshotRepository::mapRow;

    private static SnapshotRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SnapshotRow(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "session_id"),
                rs.getInt("snapshot_version"),
                SessionStatus.fromWire(rs.getString("final_status")),
                EndReason.fromWire(rs.getString("end_reason")),
                rs.getBoolean("confirmed"),
                rs.getString("policy_version"),
                ClockParams.instant(rs, "cutoff_at"),
                rs.getString("snapshot_hash"),
                rs.getString("record_json"),
                ClockParams.instant(rs, "captured_at"),
                ClockParams.instant(rs, "created_at"));
    }
}
