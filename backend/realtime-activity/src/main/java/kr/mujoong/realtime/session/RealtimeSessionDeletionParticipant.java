package kr.mujoong.realtime.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Realtime 세션 계정 삭제 참가자 (U4b L6 · FR8.1 · BR9.3 · NFR9 · U4b-R16).
 *
 * <p>음성 참가자(100)가 전사 원문 · 원음 파일 · 끊김 구간을 정리한 뒤(200번째로) 그 보호자의 세션 행과 딸린 행을 외래 키 순서로 실제 삭제한다: 결과 스냅숏
 * → 세션 사건 → 종료 표현 → 전사 행 → 원음 메타 행 → 끊김 구간 → 세션 행. 한 트랜잭션이고 멱등이다(두 번째부터 0건).
 */
@Component
public class RealtimeSessionDeletionParticipant implements RealtimeDeletionParticipant {

    /** 반환 지도 키: 지운 세션 행 수(DeletionCompleted.counts.sessions). */
    public static final String SESSION = "realtime_activity_session";
    public static final String SNAPSHOT = "realtime_result_input_snapshot";
    public static final String SESSION_EVENT = "realtime_session_event";
    public static final String STOP_EXPRESSION = "realtime_session_stop_expression";
    public static final String TRANSCRIPT = "realtime_transcript";
    public static final String AUDIO_RECORDING = "realtime_audio_recording";
    public static final String SESSION_GAP = "realtime_session_gap";

    private static final String SELECT_IDS = "select id from realtime.realtime_activity_session"
            + " where guardian_id = :guardianId order by created_at, id";
    private static final String DELETE_EVENTS = "delete from realtime.realtime_session_event"
            + " where session_id = any(:sessionIds)";
    private static final String DELETE_STOPS = "delete from realtime.realtime_session_stop_expression"
            + " where session_id = any(:sessionIds)";
    private static final String DELETE_AUDIO = "delete from realtime.realtime_audio_recording"
            + " where session_id = any(:sessionIds)";
    private static final String DELETE_SESSIONS = "delete from realtime.realtime_activity_session"
            + " where id = any(:sessionIds)";

    private final NamedParameterJdbcTemplate jdbc;
    private final ResultInputSnapshotRepository snapshots;
    private final TranscriptRepository transcripts;
    private final SessionGapRepository gaps;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;

    public RealtimeSessionDeletionParticipant(NamedParameterJdbcTemplate jdbc, ResultInputSnapshotRepository snapshots,
            TranscriptRepository transcripts, SessionGapRepository gaps, DbRetry dbRetry,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.transcripts = Objects.requireNonNull(transcripts, "transcripts");
        this.gaps = Objects.requireNonNull(gaps, "gaps");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public int order() {
        return ORDER_SESSION;
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        return dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            List<UUID> ids = jdbc.query(SELECT_IDS,
                    ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId),
                    (rs, rowNum) -> ClockParams.uuid(rs, "id"));
            Map<String, Integer> deleted = new LinkedHashMap<>();
            MapSqlParameterSource params = ClockParams.uuidArray(new MapSqlParameterSource(), "sessionIds", ids);
            deleted.put(SNAPSHOT, snapshots.deleteBySessions(ids));
            deleted.put(SESSION_EVENT, ids.isEmpty() ? 0 : jdbc.update(DELETE_EVENTS, params));
            deleted.put(STOP_EXPRESSION, ids.isEmpty() ? 0 : jdbc.update(DELETE_STOPS, params));
            deleted.put(TRANSCRIPT, transcripts.deleteBySessions(ids));
            deleted.put(AUDIO_RECORDING, ids.isEmpty() ? 0 : jdbc.update(DELETE_AUDIO, params));
            deleted.put(SESSION_GAP, gaps.deleteBySessions(ids));
            deleted.put(SESSION, ids.isEmpty() ? 0 : jdbc.update(DELETE_SESSIONS, params));
            return Map.copyOf(deleted);
        }));
    }
}
