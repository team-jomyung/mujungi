package kr.mujoong.realtime.practicelegacy;

import static kr.mujoong.realtime.practicelegacy.PracticeTypes.EvaluationState;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.session.PracticeRecordRecovery;
import org.springframework.stereotype.Component;

@Component
public final class JdbcPracticeRecordRecovery implements PracticeRecordRecovery {
    private final PracticeRepository repository;
    private final MujoongClock clock;
    private final EventLogger events;
    private final PracticeRecordProjector projector = new PracticeRecordProjector();

    public JdbcPracticeRecordRecovery(PracticeRepository repository, MujoongClock clock, EventLogger events) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = new EvaluationClock(Objects.requireNonNull(clock));
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public Object recover(UUID sessionId) {
        var snapshot = repository.snapshot(sessionId);
        if (snapshot.goals().isEmpty()) throw new IllegalStateException("복구할 연습 원본이 없다");
        for (var response : snapshot.responses()) {
            if (response.evaluationState() != EvaluationState.PENDING) continue;
            ResponseEvaluator.recoverExpired(repository, response.id(), clock).ifPresent(completion -> {
                if (!completion.committedWithinBudget())
                    events.appError(EventLog.Component.SESSION_LIFECYCLE, "PracticeEvaluationRecoveredAfterDeadline");
            });
        }
        if (repository.snapshot(sessionId).responses().stream()
                .anyMatch(p -> p.evaluationState() != EvaluationState.FINALIZED))
            throw new IllegalStateException("연습 판정 확정 전에는 스냅숏을 쓸 수 없다");
        repository.settle(sessionId);
        var projection = projector.projectSettled(repository.snapshot(sessionId));
        if (projection instanceof PracticeRecordProjector.Ready ready) return ready.practice();
        throw new IllegalStateException("연습 원본 정산이 완료되지 않았다");
    }
}
