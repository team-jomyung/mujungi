package kr.mujoong.realtime.guardrail;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 제브 검사 기록 계정 삭제 참가자 (U13 FR6.2 · BR8.3). 순서 185 — 판정 기록(180) 뒤, 세션(200) 앞이다. 검사 기록이 세션 행을 외래 키로 붙잡으므로
 * 세션 삭제 전에 지워야 하고, 먼저 그 보호자의 기록 쓰기를 막아 늦은 기록이 지운 뒤 되살아나지 않게 한다. 보호자 기준 한 문장이라 몇 번 불러도
 * 같다(멱등). {@code DeletionCompleted.Counts}에는 항목을 더하지 않는다 — 삭제 처리기의 {@code default} 가지가 이 순서를 건수에 넣지 않는다(U11
 * 판정 기록 선례와 같다).
 */
@Component
public class GuardrailEventDeletionParticipant implements RealtimeDeletionParticipant {

    /** 순서 값(작을수록 먼저): 판정 기록 180과 세션 200 사이. */
    public static final int ORDER_GUARDRAIL = 185;
    /** 반환 지도 키: 지운 제브 검사 기록 수. */
    public static final String GUARDRAIL_EVENT = "guardrail_event";

    private final GuardrailEventRepository repository;
    private final GuardrailEventRecorder recorder;
    private final DbRetry dbRetry;
    private final TransactionTemplate transactions;
    private final Duration blockWait;
    private final EventLogger events;

    @Autowired
    public GuardrailEventDeletionParticipant(GuardrailEventRepository repository, GuardrailEventRecorder recorder, DbRetry dbRetry,
            PlatformTransactionManager transactionManager, RealtimeProperties properties, EventLogger events) {
        this(repository, recorder, dbRetry, new TransactionTemplate(transactionManager),
                properties.limits().dbStatementTimeout(), events);
    }

    GuardrailEventDeletionParticipant(GuardrailEventRepository repository, GuardrailEventRecorder recorder, DbRetry dbRetry,
            TransactionTemplate transactions, Duration blockWait, EventLogger events) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.blockWait = Objects.requireNonNull(blockWait, "blockWait");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public int order() {
        return ORDER_GUARDRAIL;
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(deletionRequestId, "deletionRequestId");
        if (!recorder.block(guardianId, blockWait)) {
            events.appError(EventLog.Component.REALTIME_MAINTENANCE, "GuardrailRecordBlockTimeout");
        }
        int deleted = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                () -> transactions.execute(status -> repository.deleteByGuardian(guardianId)));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(GUARDRAIL_EVENT, deleted);
        return Map.copyOf(counts);
    }
}
