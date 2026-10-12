package kr.mujoong.core.activity;

import java.util.LinkedHashMap;
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
 * 세션 사본 · 08 배경 계정 삭제 참가자 (U2 지휘 틀 · U1 데이터 · BR2.10 · J58 · J19 · U2-J13, U4a가 넘겨받음 · 설계 R10).
 *
 * <p>core_activity는 core_device · core_child를 가리키므로(ON DELETE RESTRICT) 기기 · 아이 참가자보다 먼저 지운다
 * ({@link DeletionParticipant#ORDER_ACTIVITY}). 08 배경(core_activity_context)은 사본을 가리키므로 같은 트랜잭션에서 먼저 지운다. 활동 기록
 * (core_activity_record)은 더 앞 순서의 기록 참가자가 지운다. 한 트랜잭션 · 멱등(두 번째 호출은 0건)이고 다른 보호자 행은 건드리지 않는다.
 * 사본을 지우는 문장은 사본 저장소 한 곳에 있다(구조 시험).
 */
@Component
@Order(DeletionParticipant.ORDER_ACTIVITY)
public class ActivityDeletionParticipant implements DeletionParticipant {

    /** 반환 지도의 키. */
    public static final String ACTIVITY = "core_activity";
    public static final String CONTEXT = "core_activity_context";

    private final ActivityCopyRepository activities;
    private final ActivityContextRepository contexts;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;

    public ActivityDeletionParticipant(
            ActivityCopyRepository activities,
            ActivityContextRepository contexts,
            DbRetry dbRetry,
            PlatformTransactionManager transactionManager) {
        this.activities = Objects.requireNonNull(activities, "activities");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        Map<String, Integer> deleted = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    counts.put(CONTEXT, contexts.deleteAllByGuardian(guardianId));
                    counts.put(ACTIVITY, activities.deleteAllByGuardian(guardianId));
                    return counts;
                }));
        return deleted == null ? Map.of(CONTEXT, 0, ACTIVITY, 0) : Map.copyOf(deleted);
    }
}
