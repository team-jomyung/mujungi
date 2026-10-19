package kr.mujoong.realtime.runtime;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.practicelegacy.JdbcPracticeRecordRecovery;
import kr.mujoong.realtime.practiceflow.PracticeFlowRecordRecovery;
import kr.mujoong.realtime.session.PracticeRecordRecovery;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 연습 정산 복구 분기. 실행기 없이 닫힌 연습 세션의 {@code practice} 값을 새 흐름 표에서 먼저 찾고, 새 흐름의 장면 실행이 없으면
 * 레거시(U5b) 복구로 넘긴다. 레거시 복구 클래스 · 표 · 동작은 그대로다. 두 복구를 아는 이 한 곳만 두 패키지를 함께 본다(새 패키지는 레거시를 가져오지
 * 않는다) — 정산 서비스는 {@link PracticeRecordRecovery} 하나만 받으므로 이 분기가 {@code @Primary}다.
 */
@Primary
@Component
public final class PracticeRecoveryRouter implements PracticeRecordRecovery {

    private final PracticeFlowRecordRecovery flow;
    private final JdbcPracticeRecordRecovery legacy;

    public PracticeRecoveryRouter(PracticeFlowRecordRecovery flow, JdbcPracticeRecordRecovery legacy) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.legacy = Objects.requireNonNull(legacy, "legacy");
    }

    @Override
    public Object recover(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return flow.recover(sessionId).orElseGet(() -> legacy.recover(sessionId));
    }
}
