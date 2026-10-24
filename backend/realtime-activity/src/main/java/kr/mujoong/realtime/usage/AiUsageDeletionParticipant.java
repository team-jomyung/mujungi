package kr.mujoong.realtime.usage;

import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 판정 기록(180)과 세션(200)보다 먼저 사용량 연결·쓰기를 차단한다. */
@Component
public class AiUsageDeletionParticipant implements RealtimeDeletionParticipant {
    private final AiUsageRecorder recorder;
    private final AiUsageRepository repository;
    private final TransactionTemplate tx;
    private final AiUsageTimingProperties timing;

    public AiUsageDeletionParticipant(AiUsageRecorder recorder, AiUsageRepository repository,
            PlatformTransactionManager manager, AiUsageTimingProperties timing) {
        this.recorder = recorder;
        this.repository = repository;
        this.tx = new TransactionTemplate(manager);
        this.timing = timing;
    }

    @Override public int order() { return 170; }

    @Override public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        if (!recorder.blockSessions(repository.sessions(guardianId), timing.shutdownWait())) {
            throw new IllegalStateException("사용량 저장 정리 미완료");
        }
        Integer count = tx.execute(status -> repository.deleteByGuardian(guardianId));
        return Map.of("realtime_ai_usage", count == null ? 0 : count);
    }
}
