package kr.mujoong.realtime.practiceflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import org.springframework.stereotype.Component;

/**
 * 새 연습 흐름의 정산 복구 (U12 BR7.4 · FR4.1). 실행기 없이 닫힌 연습 세션의 {@code record.practice} 값을 V7 표에서 다시 만든다. 이 세션에
 * 새 흐름의 장면 실행이 없으면 빈 값을 돌려 호출자(복구 분기)가 레거시 복구로 넘기게 한다 — 이 클래스는 레거시를 모른다(BR10.2).
 */
@Component
public class PracticeFlowRecordRecovery {

    private final PracticeFlowRepository repository;

    public PracticeFlowRecordRecovery(PracticeFlowRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** 새 흐름의 장면 실행이 있으면 그 {@code practice} 값, 없으면 빈 값. */
    public Optional<Object> recover(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        List<SceneRunRecord> runs = repository.findSceneRuns(sessionId);
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PracticeFlowRecordAssembler.assemble(runs));
    }
}
