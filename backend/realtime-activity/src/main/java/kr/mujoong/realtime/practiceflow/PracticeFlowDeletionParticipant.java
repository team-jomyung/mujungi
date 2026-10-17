package kr.mujoong.realtime.practiceflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 새 연습 흐름 기록 계정 삭제 참가자 (U12 BR7.4). 순서 190 — 연습 150 · 판정 180 · 제브 185 뒤, 세션(200) 앞이다. 두 표가 세션 행을 외래
 * 키(ON DELETE RESTRICT)로 붙잡으므로 세션 삭제 전에 한 트랜잭션에서 도움 → 장면 실행 순서로 지운다. 보호자 기준 문장이라 몇 번 불러도
 * 같다(멱등). 삭제 처리기는 이 순서의 건수를 {@code DeletionCompleted.Counts}에 더하지 않는다(default 가지, U11 · U13 선례) — 그 모양은 바뀌지
 * 않는다.
 */
@Component
public class PracticeFlowDeletionParticipant implements RealtimeDeletionParticipant {

    /** 순서 값(작을수록 먼저): 제브 185와 세션 200 사이. */
    public static final int ORDER_PRACTICE_FLOW = 190;
    /** 반환 지도 키: 지운 도움 · 역할 전환 기록 수. */
    public static final String PRACTICE_HELP = "realtime_practice_help";
    /** 반환 지도 키: 지운 장면 실행 수. */
    public static final String PRACTICE_SCENE_RUN = "realtime_practice_scene_run";

    private final PracticeFlowRepository repository;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;

    @Autowired
    public PracticeFlowDeletionParticipant(PracticeFlowRepository repository, DbRetry dbRetry,
            PlatformTransactionManager transactionManager) {
        this(repository, dbRetry, new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager")));
    }

    PracticeFlowDeletionParticipant(PracticeFlowRepository repository, DbRetry dbRetry, TransactionTemplate transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public int order() {
        return ORDER_PRACTICE_FLOW;
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        PracticeFlowRepository.DeletedCounts deleted = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> repository.deleteByGuardian(guardianId)));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(PRACTICE_HELP, deleted.helps());
        counts.put(PRACTICE_SCENE_RUN, deleted.sceneRuns());
        return Map.copyOf(counts);
    }
}
