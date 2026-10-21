package kr.mujoong.realtime.gate;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.mujoong.contract.status.ActivityType;

/** 검수 허용과 별개인 현재 actor 출력 권한. 본문을 담지 않으며 송신 프레임에서도 같은 값을 재확인한다. */
public final class OutputAuthority {
    public enum Purpose { NORMAL, REST_ACK }
    private final UUID sessionId;
    private final ActivityType mode;
    private final long deviceGeneration;
    private final long providerGeneration;
    private final String policyVersion;
    private final String jevVersion;
    private final Purpose purpose;
    private final AtomicReference<Object> restCandidate = new AtomicReference<>();

    public OutputAuthority(UUID sessionId, ActivityType mode, long deviceGeneration, long providerGeneration,
            String policyVersion, String jevVersion, Purpose purpose) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.mode = Objects.requireNonNull(mode);
        this.deviceGeneration = deviceGeneration;
        this.providerGeneration = providerGeneration;
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.jevVersion = Objects.requireNonNull(jevVersion);
        this.purpose = Objects.requireNonNull(purpose);
    }

    public boolean permits(UUID session, ActivityType activity, long device, long provider, String policy, String jev,
            boolean active, boolean resting, Object candidate) {
        if (!active || !sessionId.equals(session) || mode != activity || deviceGeneration != device
                || providerGeneration != provider || !policyVersion.equals(policy) || !jevVersion.equals(jev)) return false;
        if (purpose == Purpose.REST_ACK) {
            return resting && candidate != null && (restCandidate.compareAndSet(null, candidate) || restCandidate.get() == candidate);
        }
        return !resting;
    }

    public Purpose purpose() { return purpose; }
    @Override public String toString() { return "OutputAuthority[purpose=" + purpose + "]"; }
}
