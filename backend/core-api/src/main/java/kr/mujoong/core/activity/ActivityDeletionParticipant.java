package kr.mujoong.core.activity;

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
 * 세션 사본 계정 삭제 참가자 (U2 지휘 틀 · U1 데이터 · BR2.10 · J58 · J19 · U2-J13).
 *
 * <p>core_activity는 core_device · core_child를 가리키므로(ON DELETE RESTRICT) 기기 · 아이 참가자보다 먼저 지운다
 * ({@link DeletionParticipant#ORDER_ACTIVITY}). 한 트랜잭션 · 멱등(두 번째 호출은 0건)이고 다른 보호자 행은 건드리지 않는다. 지우는 문장은
 * 사본 저장소 한 곳에 있다(구조 시험). U4가 세션 · 기록 표를 더할 때 이 참가자를 넘겨받는다.
 */
@Component
@Order(DeletionParticipant.ORDER_ACTIVITY)
public class ActivityDeletionParticipant implements DeletionParticipant {

    /** 반환 지도의 키. */
    public static final String ACTIVITY = "core_activity";

    private final ActivityCopyRepository activities;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;

    public ActivityDeletionParticipant(
            ActivityCopyRepository activities, DbRetry dbRetry, PlatformTransactionManager transactionManager) {
        this.activities = Objects.requireNonNull(activities, "activities");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        Integer deleted = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> activities.deleteAllByGuardian(guardianId)));
        return Map.of(ACTIVITY, deleted == null ? 0 : deleted);
    }
}
