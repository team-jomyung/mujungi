package kr.mujoong.core.record;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 활동 기록 계정 삭제 참가자 (U4a ActivityRecord · J19 · 설계 R10).
 *
 * <p>core_activity_record는 세션 사본을 가리키므로 사본 참가자보다 먼저 지운다({@link DeletionParticipant#ORDER_ACTIVITY_RECORD}). 한
 * 트랜잭션 · 멱등이고 다른 보호자 행은 건드리지 않는다. Inbox에 남은 SessionRecordCaptured payload는 U1 메시징 참가자가 비운다(BR5.14).
 */
@Component
@Order(DeletionParticipant.ORDER_ACTIVITY_RECORD)
public class ActivityRecordDeletionParticipant implements DeletionParticipant {

    /** 반환 지도의 키. */
    public static final String RECORD = "core_activity_record";

    private final ActivityRecordRepository records;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;

    public ActivityRecordDeletionParticipant(
            ActivityRecordRepository records, DbRetry dbRetry, PlatformTransactionManager transactionManager) {
        this.records = Objects.requireNonNull(records, "records");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        Integer deleted = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> records.deleteAllByGuardian(guardianId)));
        return Map.of(RECORD, deleted == null ? 0 : deleted);
    }
}
