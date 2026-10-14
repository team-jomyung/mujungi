package kr.mujoong.core.notification;

import java.util.Optional;
import java.util.Set;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.event.payload.SessionStateChanged.TechnicalPause;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;

public final class PushEventRules {
    private static final Set<String> COMPLETED_REASONS = Set.of("CHILD_STOP", "GUARDIAN_STOP", "SILENCE");

    private PushEventRules() { }

    public record Candidate(PushEventType type, String eventKey) { }

    public static Optional<Candidate> evaluate(ActivityCopy copy, SessionStateChanged payload, boolean applied) {
        if (copy.confirmed() && copy.status() == SessionStatus.ABORTED && "DISCONNECT".equals(copy.endReason())) {
            return candidate(copy, PushEventType.ENDED_BY_CONNECTION);
        }
        if (copy.confirmed() && copy.status() == SessionStatus.ENDED && copy.startedAt() != null
                && copy.endReason() != null && COMPLETED_REASONS.contains(copy.endReason())) {
            return candidate(copy, PushEventType.ACTIVITY_COMPLETED);
        }
        if (!applied || !ActivityCopyRepository.IN_PROGRESS.contains(copy.status()) || payload == null) {
            return Optional.empty();
        }
        TechnicalPause pause = payload.technicalPause();
        if (pause == null || pause.since() == null || pause.kind() == null
                || pause.kind() == TechnicalPause.Kind.UNRECOGNIZED) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(PushEventType.VOICE_PROBLEM,
                copy.sessionId() + ":VOICE_PROBLEM:" + pause.kind() + ":" + pause.since()));
    }

    private static Optional<Candidate> candidate(ActivityCopy copy, PushEventType type) {
        return Optional.of(new Candidate(type, copy.sessionId() + ":" + type));
    }
}
