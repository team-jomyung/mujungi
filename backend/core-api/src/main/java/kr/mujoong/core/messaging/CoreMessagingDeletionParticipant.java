package kr.mujoong.core.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Core 메시징 계정 삭제 참가자 (U1 3.5 Step 20 · BR5.14 · A1 · SD-21 · RD-11 · J58).
 *
 * <p>한 트랜잭션에서 core 스키마에만 쓴다(BR8.1):
 *
 * <ol>
 *   <li>보호자의 FAILED 제어 이벤트를 재무장 함수로 PENDING으로 되살린다(지우지 않는다 — 해제 · 삭제 신호가 사라지지 않게, A1 · SD-21).
 *   <li>Outbox PUBLISHED · FAILED(되살리지 못한 것) 행을 지운다. PENDING은 남긴다(BR5.14 (b)).
 *   <li>Inbox SessionRecordCaptured 행은 상태와 무관하게 payload를 비우고 RECEIVED면 IGNORED로 닫는다(BR5.14 (c)).
 *   <li>Inbox PROCESSED · IGNORED · DEAD 행을 지운다. RECEIVED는 남긴다(BR5.14 (a)(b)).
 * </ol>
 *
 * <p>Core Outbox에는 SessionRecordCaptured가 없다(Realtime이 만든다). 멱등이며 다른 보호자 행은 건드리지 않는다. 호출 시점 · 순서는 U2
 * 삭제 지휘가 정한다(BR5.14 (d)).
 */
@Component
@Order(DeletionParticipant.ORDER_MESSAGING)
public class CoreMessagingDeletionParticipant implements DeletionParticipant {

    /** 반환 지도의 키: Outbox에서 지운 행 수. */
    public static final String OUTBOX = "core_outbox_event";
    /** 반환 지도의 키: Inbox에서 지운 행 수. */
    public static final String INBOX = "core_inbox_event";

    private final CoreOutboxRepository outbox;
    private final CoreInboxRepository inbox;
    private final ControlEventRearm rearm;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    public CoreMessagingDeletionParticipant(
            CoreOutboxRepository outbox,
            CoreInboxRepository inbox,
            ControlEventRearm rearm,
            DbRetry dbRetry,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.rearm = Objects.requireNonNull(rearm, "rearm");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        return dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT, () -> transactions.execute(status -> {
            Instant now = clock.now();
            rearm.rearmForGuardian(guardianId, now);
            int outboxDeleted = outbox.deleteDeliveredByGuardian(guardianId);
            inbox.closeTranscriptEventsForGuardian(guardianId, EventType.SESSION_RECORD_CAPTURED, now);
            int inboxDeleted = inbox.deleteFinishedByGuardian(guardianId);
            Map<String, Integer> deleted = new LinkedHashMap<>();
            deleted.put(OUTBOX, outboxDeleted);
            deleted.put(INBOX, inboxDeleted);
            return Map.copyOf(deleted);
        }));
    }
}
