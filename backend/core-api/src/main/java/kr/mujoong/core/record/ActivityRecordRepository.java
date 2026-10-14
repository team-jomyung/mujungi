package kr.mujoong.core.record;

import static kr.mujoong.core.common.db.ClockParams.instant;
import static kr.mujoong.core.common.db.ClockParams.integer;
import static kr.mujoong.core.common.db.ClockParams.text;
import static kr.mujoong.core.common.db.ClockParams.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 활동 기록 {@code core.core_activity_record} 데이터 접근 (U4a ActivityRecord · 계약 6절 SessionRecordCaptured · AC5.1.8 · 설계 R5 ~ R7).
 *
 * <p>쓰기는 넣기 한 가지다(같은 세션이 이미 있으면 넣지 않고 그 해시를 돌려준다 — 멱등 · 충돌 판정은 호출자). 대화 원문이 담긴 열(json)은 로그 ·
 * 문자열 표현에 내지 않는다. 트랜잭션은 호출자가 연다.
 */
@Repository
public class ActivityRecordRepository {

    /** BR14.1: 발화가 있고 사본이 SERVER_RESTART로 실패한 기록도 보인다. */
    public static final String LISTED_STATUSES = RecordEligibility.LISTED_SQL;

    private static final String COLUMNS = """
            session_id, guardian_id, child_id, activity_type, final_status, end_reason, confirmed, started_at, ended_at,
            listed_at, record_from, record_to, transcript_json::text as transcript_json, gaps_json::text as gaps_json,
            practice_json::text as practice_json, snapshot_hash, recorded_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ActivityRecordRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 기록을 넣는다. 같은 세션 행이 이미 있으면 넣지 않는다.
     *
     * @return 넣었으면 빈 값, 이미 있었으면 그 행의 snapshot_hash
     */
    public Optional<String> insertIfAbsent(NewRecord record, Instant now) {
        Objects.requireNonNull(record, "record");
        MapSqlParameterSource params = ClockParams.at(Objects.requireNonNull(now, "now"));
        uuid(params, "sessionId", record.sessionId());
        uuid(params, "guardianId", record.guardianId());
        uuid(params, "childId", record.childId());
        text(params, "activityType", record.activityType());
        text(params, "finalStatus", record.finalStatus());
        text(params, "endReason", record.endReason());
        params.addValue("confirmed", record.confirmed());
        instant(params, "startedAt", record.startedAt());
        instant(params, "endedAt", record.endedAt());
        instant(params, "listedAt", record.listedAt());
        instant(params, "recordFrom", record.recordFrom());
        instant(params, "recordTo", record.recordTo());
        text(params, "transcript", record.transcriptJson());
        text(params, "gaps", record.gapsJson());
        text(params, "practice", record.practiceJson());
        text(params, "audio", record.audioRefsJson());
        text(params, "termination", record.terminationJson());
        uuid(params, "snapshotId", record.snapshotId());
        integer(params, "snapshotVersion", record.snapshotVersion());
        text(params, "snapshotHash", record.snapshotHash());
        int inserted = jdbc.update("""
                insert into core.core_activity_record (session_id, guardian_id, child_id, activity_type, final_status, end_reason,
                    confirmed, started_at, ended_at, listed_at, record_from, record_to, transcript_json, gaps_json, practice_json,
                    audio_refs_json, termination_json, source_snapshot_id, snapshot_version, snapshot_hash, recorded_at, created_at,
                    updated_at)
                values (:sessionId, :guardianId, :childId, :activityType, :finalStatus, :endReason, :confirmed, :startedAt,
                    :endedAt, :listedAt, :recordFrom, :recordTo, cast(:transcript as jsonb), cast(:gaps as jsonb),
                    cast(:practice as jsonb), cast(:audio as jsonb), cast(:termination as jsonb), :snapshotId, :snapshotVersion,
                    :snapshotHash, :now, :now, :now)
                on conflict (session_id) do nothing
                """, params);
        if (inserted == 1) {
            return Optional.empty();
        }
        List<String> existing = jdbc.queryForList(
                "select snapshot_hash from core.core_activity_record where session_id = :sessionId", params, String.class);
        return Optional.of(existing.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("기록 넣기가 0행인데 기존 행이 없다")));
    }

    /** 보호자 소유의 기록(남의 기록은 없는 것과 같다). */
    public Optional<StoredRecord> findOwned(UUID sessionId, UUID guardianId) {
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "sessionId",
                Objects.requireNonNull(sessionId, "sessionId"));
        uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        return jdbc.query("select " + COLUMNS
                + " from core.core_activity_record where session_id = :sessionId and guardian_id = :guardianId", params,
                ActivityRecordRepository::map).stream().findFirst();
    }
    public List<StoredRecord> missingResultHeads(){
        return jdbc.query("select "+COLUMNS+" from core.core_activity_record where not exists (select 1 from core.core_session_result_state s where s.session_id=core_activity_record.session_id) order by recorded_at,session_id limit 100",new MapSqlParameterSource(),ActivityRecordRepository::map);
    }
    public Optional<StoredRecord> findForChild(UUID session,UUID child){return jdbc.query("select "+COLUMNS+" from core.core_activity_record where session_id=:session and child_id=:child",new MapSqlParameterSource("session",session).addValue("child",child),ActivityRecordRepository::map).stream().findFirst();}
    /** BR9.1: 현재 아이의 보고 가능한 전체 기록. DECLINED는 리포트에서 제외한다. */
    public List<StoredRecord> reportRecords(UUID guardian,UUID child){return jdbc.query("select "+COLUMNS+" from core.core_activity_record where guardian_id=:guardian and child_id=:child and "+LISTED_STATUSES+" and final_status <> 'DECLINED' order by listed_at,session_id",new MapSqlParameterSource("guardian",guardian).addValue("child",child),ActivityRecordRepository::map);}

    /** [from, to) 안에 줄 시각이 있는 26 목록 기록(시각 순). */
    public List<StoredRecord> findListedBetween(UUID guardianId, Instant from, Instant to) {
        return jdbc.query("select " + COLUMNS + " from core.core_activity_record where guardian_id = :guardianId and "
                + LISTED_STATUSES + " and listed_at >= :from and listed_at < :to order by listed_at, session_id",
                range(guardianId, from, to), ActivityRecordRepository::map);
    }

    /** [from, to) 안의 날짜별 기록 수(날짜는 {@code zone} 기준, 26 달력 점). */
    public List<DayCount> countByDay(UUID guardianId, Instant from, Instant to, String zone) {
        MapSqlParameterSource params = range(guardianId, from, to);
        text(params, "zone", Objects.requireNonNull(zone, "zone"));
        return jdbc.query("select cast(listed_at at time zone :zone as date) as day, count(*) as cnt"
                + " from core.core_activity_record where guardian_id = :guardianId and " + LISTED_STATUSES
                + " and listed_at >= :from and listed_at < :to group by day order by day",
                params, (rs, rowNum) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getInt("cnt")));
    }

    /** 보호자의 기록을 모두 지운다(계정 삭제 참가자 전용, 호출자 트랜잭션). */
    public int deleteAllByGuardian(UUID guardianId) {
        return jdbc.update("delete from core.core_activity_record where guardian_id = :guardianId",
                uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }

    private static MapSqlParameterSource range(UUID guardianId, Instant from, Instant to) {
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "guardianId",
                Objects.requireNonNull(guardianId, "guardianId"));
        instant(params, "from", Objects.requireNonNull(from, "from"));
        instant(params, "to", Objects.requireNonNull(to, "to"));
        return params;
    }

    private static StoredRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new StoredRecord(
                ClockParams.uuid(rs, "session_id"),
                ClockParams.uuid(rs, "guardian_id"),
                ClockParams.uuid(rs, "child_id"),
                rs.getString("activity_type"),
                rs.getString("final_status"),
                rs.getString("end_reason"),
                rs.getBoolean("confirmed"),
                ClockParams.instant(rs, "started_at"),
                ClockParams.instant(rs, "ended_at"),
                ClockParams.instant(rs, "listed_at"),
                ClockParams.instant(rs, "record_from"),
                ClockParams.instant(rs, "record_to"),
                rs.getString("transcript_json"),
                rs.getString("gaps_json"),
                rs.getString("practice_json"),
                rs.getString("snapshot_hash"),
                ClockParams.instant(rs, "recorded_at"));
    }

    /** 넣을 기록(json 열은 이미 직렬화한 글). */
    public record NewRecord(
            UUID sessionId,
            UUID guardianId,
            UUID childId,
            String activityType,
            String finalStatus,
            String endReason,
            boolean confirmed,
            Instant startedAt,
            Instant endedAt,
            Instant listedAt,
            Instant recordFrom,
            Instant recordTo,
            String transcriptJson,
            String gapsJson,
            String practiceJson,
            String audioRefsJson,
            String terminationJson,
            UUID snapshotId,
            Integer snapshotVersion,
            String snapshotHash) {

        public NewRecord {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(guardianId, "guardianId");
            Objects.requireNonNull(childId, "childId");
            Objects.requireNonNull(activityType, "activityType");
            Objects.requireNonNull(finalStatus, "finalStatus");
            Objects.requireNonNull(listedAt, "listedAt");
            Objects.requireNonNull(transcriptJson, "transcriptJson");
            Objects.requireNonNull(gapsJson, "gapsJson");
            Objects.requireNonNull(audioRefsJson, "audioRefsJson");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(snapshotHash, "snapshotHash");
        }

        /** 대화 원문이 담겨 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "NewRecord[sessionId=" + sessionId + ", finalStatus=" + finalStatus + "]";
        }
    }

    /** 저장된 기록(원음 경로 · termination은 읽지 않는다 — 보호자 응답에 쓰지 않는다). */
    public record StoredRecord(
            UUID sessionId,
            UUID guardianId,
            UUID childId,
            String activityType,
            String finalStatus,
            String endReason,
            boolean confirmed,
            Instant startedAt,
            Instant endedAt,
            Instant listedAt,
            Instant recordFrom,
            Instant recordTo,
            String transcriptJson,
            String gapsJson,
            String practiceJson,
            String snapshotHash,
            Instant recordedAt) {

        @Override
        public String toString() {
            return "StoredRecord[sessionId=" + sessionId + ", finalStatus=" + finalStatus + "]";
        }
    }

    /** 날짜 하나의 기록 수. */
    public record DayCount(LocalDate day, int count) {
    }
}
