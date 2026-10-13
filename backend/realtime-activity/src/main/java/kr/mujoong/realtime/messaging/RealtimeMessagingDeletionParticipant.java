package kr.mujoong.realtime.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.DbRetry;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Realtime 메시징 계정 삭제 참가자 (U1 3.5 Step 26 · BR5.14 · A1 · SD-21 · RD-11 · J58 · 계약 8절 DeletionParticipant).
 *
 * <p>한 트랜잭션에서 realtime 스키마에만 쓴다(BR8.1):
 *
 * <ol>
 *   <li>보호자의 FAILED 제어 이벤트를 재무장 함수로 PENDING으로 되살린다(지우지 않는다 — 삭제 완료 신호가 사라지지 않게, A1 · SD-21).
 *   <li>SessionRecordCaptured(대화 원문 포함) 행은 상태와 무관하게 payload를 비우고, Outbox PENDING이면 FAILED(ACCOUNT_DELETED), Inbox
 *       RECEIVED이면 IGNORED로 닫는다(BR5.14 (c)).
 *   <li>Outbox PUBLISHED · FAILED 행을 지운다. PENDING은 남긴다(BR5.14 (a)(b)).
 *   <li>Inbox PROCESSED · IGNORED · DEAD 행을 지운다. RECEIVED는 남긴다(BR5.14 (a)(b)).
 * </ol>
 *
 * <p>멱등이며 다른 보호자 행은 건드리지 않는다. 호출 시점 · 순서는 U4(Realtime DeletionRequested 처리기)가 정한다(BR5.14 (d)). U1은
 * 어느 시점에 불려도 결과가 같다. 반환 지도는 표마다 지운 행 수다(계약 8절 counts).
 */
@Component
public class RealtimeMessagingDeletionParticipant implements RealtimeDeletionParticipant {

    /** U4b(BR9.2): 메시징 참가자는 마지막(900)이다. DeletionCompleted가 발행되면 한 번 더 불린다(BR9.5). */
    @Override
    public int order() {
        return ORDER_MESSAGING;
    }

    /** 반환 지도의 키: Outbox에서 지운 행 수. */
    public static final String OUTBOX = "realtime_outbox_event";
    /** 반환 지도의 키: Inbox에서 지운 행 수. */
    public static final String INBOX = "realtime_inbox_event";

    private static final String CLOSE_OUTBOX_TRANSCRIPT = "update realtime.realtime_outbox_event"
            + " set payload_json = cast('{}' as jsonb), purged_at = coalesce(purged_at, :now),"
            + " status = case when status = 'PENDING' then 'FAILED' else status end,"
            + " last_error = case when status = 'PENDING' then :accountDeleted else last_error end"
            + " where guardian_id = :guardianId and event_type = :transcriptType"
            + " and (purged_at is null or status = 'PENDING')";

    private static final String DELETE_DELIVERED_OUTBOX = "delete from realtime.realtime_outbox_event"
            + " where guardian_id = :guardianId and status in ('PUBLISHED', 'FAILED')";

    private static final String CLOSE_INBOX_TRANSCRIPT = "update realtime.realtime_inbox_event"
            + " set payload_json = cast('{}' as jsonb), purged_at = coalesce(purged_at, :now),"
            + " status = case when status = 'RECEIVED' then 'IGNORED' else status end,"
            + " next_attempt_at = case when status = 'RECEIVED' then null else next_attempt_at end"
            + " where guardian_id = :guardianId and event_type = :transcriptType"
            + " and (purged_at is null or status = 'RECEIVED')";

    private static final String DELETE_FINISHED_INBOX = "delete from realtime.realtime_inbox_event"
            + " where guardian_id = :guardianId and status in ('PROCESSED', 'IGNORED', 'DEAD')";

    private final NamedParameterJdbcTemplate jdbc;
    private final ControlEventRearm rearm;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    public RealtimeMessagingDeletionParticipant(
            NamedParameterJdbcTemplate jdbc,
            ControlEventRearm rearm,
            DbRetry dbRetry,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.rearm = Objects.requireNonNull(rearm, "rearm");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 한 보호자의 Realtime 메시징 행을 정리한다.
     *
     * @param deletionRequestId 삭제 요청 id(기록 상관 용도, U1은 쓰지 않는다)
     * @return 표마다 지운 행 수
     */
    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        return dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            Instant now = clock.now();
            rearm.rearmForGuardian(guardianId, now);
            MapSqlParameterSource params = ClockParams.at(now);
            ClockParams.uuid(params, "guardianId", guardianId);
            ClockParams.text(params, "transcriptType", EventType.SESSION_RECORD_CAPTURED.wire());
            ClockParams.text(params, "accountDeleted", LastError.ACCOUNT_DELETED);
            jdbc.update(CLOSE_OUTBOX_TRANSCRIPT, params);
            int outboxDeleted = jdbc.update(DELETE_DELIVERED_OUTBOX, params);
            jdbc.update(CLOSE_INBOX_TRANSCRIPT, params);
            int inboxDeleted = jdbc.update(DELETE_FINISHED_INBOX, params);
            Map<String, Integer> deleted = new LinkedHashMap<>();
            deleted.put(OUTBOX, outboxDeleted);
            deleted.put(INBOX, inboxDeleted);
            return Map.copyOf(deleted);
        }));
    }
}
