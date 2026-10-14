package kr.mujoong.core.notification;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.stereotype.Component;

@Component
public class PushEventRecorder {
    private final GuardianWriteLock guardians;
    private final PushEventRepository events;

    public PushEventRecorder(GuardianWriteLock guardians, PushEventRepository events) {
        this.guardians = guardians;
        this.events = events;
    }

    public boolean lockGuardian(UUID guardianId) {
        return guardians.statusForShare(guardianId).filter("ACTIVE"::equals).isPresent();
    }

    public void record(ActivityCopy copy, SessionStateChanged payload, boolean applied, Instant now) {
        var event = PushEventRules.evaluate(copy, payload, applied);
        if (!ActivityCopyRepository.IN_PROGRESS.contains(copy.status())) {
            events.skipTechnical(copy.sessionId());
        } else if (applied) {
            events.skipOtherVoice(copy.sessionId(), event.filter(e -> e.type() == PushEventType.VOICE_PROBLEM)
                    .map(PushEventRules.Candidate::eventKey).orElse(null));
        }
        event.ifPresent(value -> events.enqueue(copy, value.type(), value.eventKey(), now));
    }
}
