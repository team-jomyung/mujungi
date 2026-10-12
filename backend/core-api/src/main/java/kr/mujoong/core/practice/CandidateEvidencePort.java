package kr.mujoong.core.practice;
import java.util.List;
import java.util.UUID;
/** U4/U6 verifies actual recorded utterances. The default refuses unverified evidence. */
@FunctionalInterface
public interface CandidateEvidencePort {
    boolean verifies(UUID childId, UUID sourceSessionId, List<Evidence> evidence);
    record Evidence(UUID sessionId, long utteranceSequenceNo, String evidenceType) {}
}
