package kr.mujoong.realtime.practicelegacy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 연습 원본 다섯 표를 세션 참가자(200)보다 먼저 한 트랜잭션에서 지운다. */
@Component
public final class RealtimePracticeDeletionParticipant implements RealtimeDeletionParticipant {
    private static final String GOALS = "select id from realtime.realtime_session_goal where guardian_id=:guardianId";
    private static final String ATTEMPTS = "select id from realtime.realtime_practice_attempt where session_goal_id in ("
            + GOALS + ")";
    private final NamedParameterJdbcTemplate jdbc;
    private final DbRetry retry;
    private final TransactionTemplate transactions;

    public RealtimePracticeDeletionParticipant(NamedParameterJdbcTemplate jdbc, DbRetry retry,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public int order() {
        return ORDER_PRACTICE;
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        return retry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            var params = ClockParams.uuid(new MapSqlParameterSource(), "guardianId", guardianId);
            // 시작 초기화와 같은 세션→목표 잠금 순서를 쓴다.
            jdbc.query("select id from realtime.realtime_activity_session where guardian_id=:guardianId"
                    + " order by id for update", params, (rs, n) -> rs.getObject(1));
            jdbc.query(GOALS + " order by session_id,goal_order for update", params, (rs, n) -> rs.getObject(1));
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("realtime_practice_evaluation", jdbc.update("delete from realtime.realtime_practice_evaluation"
                    + " where response_id in (select id from realtime.realtime_practice_response"
                    + " where attempt_id in (" + ATTEMPTS + "))", params));
            counts.put("realtime_practice_response", jdbc.update("delete from realtime.realtime_practice_response"
                    + " where attempt_id in (" + ATTEMPTS + ")", params));
            counts.put("realtime_practice_cue", jdbc.update("delete from realtime.realtime_practice_cue"
                    + " where attempt_id in (" + ATTEMPTS + ")", params));
            counts.put("realtime_practice_attempt", jdbc.update("delete from realtime.realtime_practice_attempt"
                    + " where session_goal_id in (" + GOALS + ")", params));
            counts.put("realtime_session_goal", jdbc.update("delete from realtime.realtime_session_goal"
                    + " where guardian_id=:guardianId", params));
            return Map.copyOf(counts);
        }));
    }
}
