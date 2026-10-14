package kr.mujoong.realtime.practice;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 독립 저장 경계. 모든 변경과 정산은 같은 세션 목표 행 잠금으로 직렬화한다. */
public interface PracticeRepository {
    void initialize(List<SessionGoal> goals);
    void saveGoal(SessionGoal goal);
    void saveAttempt(PracticeAttempt attempt);
    void saveCue(CueDelivery cue);
    PracticeResponse accept(PracticeResponse response);
    Optional<PracticeResponse> response(UUID responseId);
    Optional<PracticeResponse> claimAttempt(UUID responseId, long generation, Instant now, Duration callBudget);
    boolean releaseAttempt(UUID responseId, UUID token);
    boolean finalizeResult(UUID responseId, long generation, UUID token, Evaluation result, Instant now);
    boolean finalizeWithinBudget(UUID responseId, long generation, UUID token, Evaluation result,
            Instant deadline, kr.mujoong.contract.time.MujoongClock clock);
    void terminate(UUID sessionId);
    void delete(UUID guardianId);
    Snapshot snapshot(UUID sessionId);
    record Snapshot(List<SessionGoal> goals, List<PracticeAttempt> attempts, List<PracticeResponse> responses,
                    List<CueDelivery> cues, List<Evaluation> evaluations) {
        public Snapshot {
            goals = List.copyOf(goals); attempts = List.copyOf(attempts); responses = List.copyOf(responses);
            cues = List.copyOf(cues); evaluations = List.copyOf(evaluations);
        }
    }
}
