package kr.mujoong.core.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.core.activity.ActivityContextRepository;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.record.ActivityRecordRepository;
import org.springframework.stereotype.Component;

/** BR1·BR4: 현재 권한/동의와 서버 소유 원문을 동일한 잠금 경계에서 확인한다. */
@Component
public class KnowledgeAccessPolicy {
    private final ChildRepository children;
    private final ConsentPolicyService consent;
    private final GuardianWriteLock guardians;
    private final ActivityRecordRepository records;
    private final ActivityContextRepository contexts;
    public KnowledgeAccessPolicy(ChildRepository children, ConsentPolicyService consent, GuardianWriteLock guardians,
            ActivityRecordRepository records, ActivityContextRepository contexts) {
        this.children = children;
        this.consent = consent;
        this.guardians = guardians;
        this.records = records;
        this.contexts = contexts;
    }

    public void requireAllowed(UUID guardian, UUID child) {
        guardians.lockActive(guardian);
        requireOwner(guardian, child);
        if (!consent.check(guardian).ok()) throw new IllegalStateException("Knowledge consent unavailable");
    }

    public void requireDeletionOwner(UUID guardian, UUID child) {
        guardians.statusForUpdate(guardian).orElseThrow(() -> new IllegalStateException("Guardian unavailable"));
        requireOwner(guardian, child);
    }

    private Child requireOwner(UUID guardian, UUID child) {
        return children.findById(child).filter(c -> guardian.equals(c.getGuardianId()) && Child.STATUS_ACTIVE.equals(c.getStatus()))
                .orElseThrow(() -> new IllegalStateException("Knowledge child unavailable"));
    }

    public Source source(UUID guardian, UUID child, UUID activity) {
        requireAllowed(guardian, child);
        var record = records.findOwned(activity, guardian).filter(r -> child.equals(r.childId()) && "FREE_TALK".equals(r.activityType()))
                .orElseThrow(() -> new IllegalStateException("Knowledge source unavailable"));
        List<String> raw = new ArrayList<>();
        collectText(kr.mujoong.contract.json.JsonCodec.shared().read(record.transcriptJson(), Object.class), raw);
        contexts.findBySession(activity).ifPresent(c -> {
            if (c.knownContent() != null) raw.add(c.knownContent());
            if (c.question() != null) raw.add(c.question());
        });
        return new Source(raw, List.of(requireOwner(guardian, child).getDisplayName()));
    }

    public record Source(List<String> rawTexts, List<String> names) {
        public Source { rawTexts = List.copyOf(rawTexts); names = List.copyOf(names); }
        @Override public String toString() { return "KnowledgeSource[redacted]"; }
    }

    private static void collectText(Object value, List<String> output) {
        if (value instanceof String text) output.add(text);
        else if (value instanceof java.util.Map<?, ?> map) map.values().forEach(item -> collectText(item, output));
        else if (value instanceof List<?> list) list.forEach(item -> collectText(item, output));
    }
}
