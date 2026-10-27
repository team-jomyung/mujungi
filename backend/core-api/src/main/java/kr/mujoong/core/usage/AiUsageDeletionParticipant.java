package kr.mujoong.core.usage;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.deletion.DeletionParticipant;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보호자의 사용량 행을 지운다. 사용량 표는 보호자 행만 가리키므로 보호자 삭제 전 어느 순서여도 되고, 다른 참가자와 겹치지 않는 값을 쓴다
 * ({@link DeletionParticipant#ORDER_AI_USAGE}). 이 삭제 뒤 진행 중이던 호출이 늦게 남긴 행은 지휘의 두 번째 참가자 호출이 지우고, 보호자 행이
 * 지워진 뒤에는 외래 키가 저장을 막는다.
 */
@Component
@Order(DeletionParticipant.ORDER_AI_USAGE)
public class AiUsageDeletionParticipant implements DeletionParticipant {

    private final AiUsageRecorder recorder;
    private final AiUsageRepository repository;
    private final TransactionTemplate tx;
    private final AiUsageProperties.Timing timing;

    public AiUsageDeletionParticipant(AiUsageRecorder recorder, AiUsageRepository repository, PlatformTransactionManager manager,
            AiUsageProperties.Timing timing) {
        this.recorder = recorder;
        this.repository = repository;
        this.tx = new TransactionTemplate(manager);
        this.timing = timing;
    }

    @Override
    public Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        if (!recorder.discardGuardian(guardianId, timing.shutdownWait())) {
            // 진행 중 저장이 끝나지 않았다: 예외로 지휘가 다시 부르게 한다(멱등)
            throw new IllegalStateException("사용량 저장 정리 미완료");
        }
        Integer count = tx.execute(status -> repository.deleteByGuardian(guardianId));
        return Map.of("core_ai_usage", count == null ? 0 : count);
    }
}
