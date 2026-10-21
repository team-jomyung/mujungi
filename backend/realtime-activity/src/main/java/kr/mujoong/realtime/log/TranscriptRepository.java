package kr.mujoong.realtime.log;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import kr.mujoong.realtime.log.TranscriptEntry.AssemblyStatus;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_transcript} 데이터 접근 (U1 3.5 Step 16 · BR7.5 · BR6.15 · PD-5 · SQ-10 · SQ-11).
 *
 * <ul>
 *   <li>기록기 플러시(SQ-11): 칸을 번호 순으로 {@code insert ... on conflict (session_id, sequence_no) do update}를 JDBC 배치로
 *       쓴다. 한 행의 FINAL 전환과 승인 판정은 한 문장이라 BR7.5의 '같은 트랜잭션'이 지켜지고, 배치 전체도 호출자의 한 트랜잭션
 *       안에서만 쓴다(MANDATORY). 덮어쓰기는 확정 전(PARTIAL) 행에만 한다 — FINAL은 닫혀 늦은 조각이 다시 열지 못하고, 폐기된 AI
 *       말(approved false)이 뒤늦게 승인되지 않는다. 다시 보내도 같은 결과다(커밋 실패 뒤 재시도, PD-5 4).
 *   <li>12 진행 조회(SQ-10 · BR6.15): 아이 전사와 확정 · 승인된 AI 말만 읽고, nextSeq 계산용으로 확정 전 행의 가장 작은 번호를 따로 준다.
 * </ul>
 */
@Repository
public class TranscriptRepository {

    private static final String COLUMNS = "id, session_id, sequence_no, speaker, text, assembly_status, review_needed,"
            + " approved_for_display, started_at, ended_at, created_at, deleted_at";

    // U3: review_needed(불확실 표시, U3-R10)도 함께 쓴다 — 확정 전 행을 덮을 때만 바뀌는 것은 다른 열과 같다
    private static final String UPSERT = "insert into realtime.realtime_transcript as t (id, session_id, sequence_no,"
            + " speaker, text, assembly_status, review_needed, approved_for_display, started_at, ended_at, created_at)"
            + " values (:id, :sessionId, :sequenceNo, :speaker, :text, :assemblyStatus, :reviewNeeded, :approved,"
            + " :startedAt, :endedAt, :now)"
            + " on conflict (session_id, sequence_no) do update set text = excluded.text,"
            + " assembly_status = excluded.assembly_status, review_needed = excluded.review_needed,"
            + " approved_for_display = excluded.approved_for_display,"
            + " started_at = coalesce(t.started_at, excluded.started_at), ended_at = coalesce(excluded.ended_at, t.ended_at)"
            // 확정 전 행만 덮는다. 같은 번호에 다른 화자가 오면(조립 결함) 덮지 않는다
            + " where t.assembly_status = 'PARTIAL' and t.speaker = excluded.speaker";

    private static final String SELECT_ALL = "select " + COLUMNS + " from realtime.realtime_transcript"
            + " where session_id = :sessionId and deleted_at is null order by sequence_no";

    private static final String SELECT_VISIBLE_AFTER = "select " + COLUMNS + " from realtime.realtime_transcript"
            + " where session_id = :sessionId and sequence_no > :sinceSeq and deleted_at is null"
            + " and (speaker = 'CHILD' or (speaker = 'AI' and assembly_status = 'FINAL' and approved_for_display))"
            + " order by sequence_no";

    private static final String SELECT_LOWEST_PARTIAL = "select min(sequence_no) from realtime.realtime_transcript"
            + " where session_id = :sessionId and sequence_no > :sinceSeq and deleted_at is null"
            + " and assembly_status = 'PARTIAL'";

    private static final String SELECT_MAX_SEQUENCE = "select coalesce(max(sequence_no), 0)"
            + " from realtime.realtime_transcript where session_id = :sessionId";

    // U3-J16: 계정 삭제 — 원문을 비우고 deleted_at을 쓴다(행 번호는 남긴다). 다시 불러도 같다
    private static final String ERASE_BY_GUARDIAN = "update realtime.realtime_transcript t"
            + " set text = '', deleted_at = coalesce(t.deleted_at, :now)"
            + " from realtime.realtime_activity_session s"
            + " where t.session_id = s.id and s.guardian_id = :guardianId and (t.deleted_at is null or t.text <> '')";

    // U4b BR3.2: DECLINED 세션은 그 세션의 전사 행을 모두 지운다(전이와 같은 트랜잭션)
    private static final String DELETE_BY_SESSION = "delete from realtime.realtime_transcript where session_id = :sessionId";

    // U4b BR9.3: 계정 삭제 세션 참가자(200)가 남은 전사 행을 실제로 지운다(세션 행보다 먼저)
    private static final String DELETE_BY_SESSIONS = "delete from realtime.realtime_transcript"
            + " where session_id = any(:sessionIds)";

    private static final Comparator<TranscriptEntry> FLUSH_ORDER =
            Comparator.comparing(TranscriptEntry::sessionId).thenComparingInt(TranscriptEntry::sequenceNo);

    private final NamedParameterJdbcTemplate jdbc;

    public TranscriptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 칸들을 한 배치로 쓴다(기록기 플러시). 활성 트랜잭션 안에서만 부른다. 세션별 번호 순으로 쓴다(같은 번호는 넣은 순서).
     *
     * @param now 새 행의 created_at(Clock)
     */
    public void upsertAll(Collection<TranscriptEntry> entries, Instant now) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(now, "now");
        Transactions.requireActive("TranscriptRepository.upsertAll");
        if (entries.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = entries.stream()
                .sorted(FLUSH_ORDER)
                .map(entry -> params(entry, now))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT, batch);
    }

    /** 보호자 세션들의 전사 원문을 비우고 삭제 시각을 쓴다(계정 삭제 참가자, U3-J16). 바뀐 행 수. */
    public int eraseByGuardian(UUID guardianId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(Objects.requireNonNull(now, "now"));
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        return jdbc.update(ERASE_BY_GUARDIAN, params);
    }

    /** U4b BR3.2: 세션의 전사 행을 모두 지운다(DECLINED 전이 트랜잭션 안). 지운 행 수. */
    public int deleteBySession(UUID sessionId) {
        return jdbc.update(DELETE_BY_SESSION, sessionParam(sessionId));
    }

    /** U4b BR9.3: 세션들의 전사 행을 실제로 지운다(계정 삭제 세션 참가자). 지운 행 수. */
    public int deleteBySessions(java.util.List<UUID> sessionIds) {
        Objects.requireNonNull(sessionIds, "sessionIds");
        if (sessionIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuidArray(params, "sessionIds", sessionIds);
        return jdbc.update(DELETE_BY_SESSIONS, params);
    }

    /** 세션의 행 전부(지워지지 않은 것, 번호 순). */
    public List<TranscriptRow> findAll(UUID sessionId) {
        return jdbc.query(SELECT_ALL, sessionParam(sessionId), ROW_MAPPER);
    }

    /** 12에 나갈 수 있는 행(BR6.15): {@code sinceSeq} 뒤의 아이 전사와 확정 · 승인된 AI 말(번호 순). */
    public List<TranscriptRow> findVisibleAfter(UUID sessionId, int sinceSeq) {
        return jdbc.query(SELECT_VISIBLE_AFTER, sinceParams(sessionId, sinceSeq), ROW_MAPPER);
    }

    public boolean isVisibleFinalChild(UUID sessionId, int sequenceNo) {
        Boolean visible = jdbc.queryForObject("select exists(select 1 from realtime.realtime_transcript"
                + " where session_id = :sessionId and sequence_no = :seq and speaker = 'CHILD'"
                + " and assembly_status = 'FINAL' and not review_needed and deleted_at is null and text <> '')",
                sessionParam(sessionId).addValue("seq", sequenceNo), Boolean.class);
        return Boolean.TRUE.equals(visible);
    }

    /** {@code sinceSeq} 뒤의 확정 전 행 중 가장 작은 번호(BR6.15: nextSeq는 이 번호 − 1을 넘지 않는다). */
    public Optional<Integer> lowestPartialAfter(UUID sessionId, int sinceSeq) {
        Integer lowest = jdbc.queryForObject(SELECT_LOWEST_PARTIAL, sinceParams(sessionId, sinceSeq), Integer.class);
        return Optional.ofNullable(lowest);
    }

    /** 세션의 가장 큰 번호(행이 없으면 0, 다음 번호는 이 값 + 1). */
    public int maxSequenceNo(UUID sessionId) {
        Integer max = jdbc.queryForObject(SELECT_MAX_SEQUENCE, sessionParam(sessionId), Integer.class);
        return max == null ? 0 : max;
    }

    private static MapSqlParameterSource params(TranscriptEntry entry, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "sessionId", entry.sessionId());
        params.addValue("sequenceNo", entry.sequenceNo());
        ClockParams.text(params, "speaker", entry.speaker().name());
        ClockParams.text(params, "text", entry.text());
        ClockParams.text(params, "assemblyStatus", entry.assemblyStatus().name());
        params.addValue("approved", entry.approvedForDisplay());
        params.addValue("reviewNeeded", entry.reviewNeeded());
        ClockParams.instant(params, "startedAt", entry.startedAt());
        ClockParams.instant(params, "endedAt", entry.endedAt());
        return params;
    }

    private static MapSqlParameterSource sessionParam(UUID sessionId) {
        return ClockParams.uuid(new MapSqlParameterSource(), "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
    }

    private static MapSqlParameterSource sinceParams(UUID sessionId, int sinceSeq) {
        if (sinceSeq < 0) {
            throw new IllegalArgumentException("sinceSeq는 0 이상이어야 한다");
        }
        return sessionParam(sessionId).addValue("sinceSeq", sinceSeq);
    }

    private static final RowMapper<TranscriptRow> ROW_MAPPER = TranscriptRepository::mapRow;

    private static TranscriptRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TranscriptRow(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "session_id"),
                rs.getInt("sequence_no"),
                Speaker.valueOf(rs.getString("speaker")),
                rs.getString("text"),
                AssemblyStatus.valueOf(rs.getString("assembly_status")),
                rs.getBoolean("review_needed"),
                rs.getBoolean("approved_for_display"),
                ClockParams.instant(rs, "started_at"),
                ClockParams.instant(rs, "ended_at"),
                ClockParams.instant(rs, "created_at"),
                ClockParams.instant(rs, "deleted_at"));
    }
}
