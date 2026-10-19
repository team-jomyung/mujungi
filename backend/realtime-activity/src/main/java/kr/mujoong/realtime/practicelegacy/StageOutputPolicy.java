package kr.mujoong.realtime.practicelegacy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;

/** 송신 직전에 다시 검사할 로컬 정책. 실제 OutputGate/VoiceRelay 연결은 I1 합의 후 수행한다. */
public final class StageOutputPolicy {
    public record Context(UUID scenarioId, Stage stage, long generation, Consent consent,
            boolean active, boolean connected, boolean recordingReady, boolean cueAlreadyGiven) { }
    public Optional<CueBrief> cue(Context current, UUID scenarioId, long generation, CueBrief minimalBrief,
            boolean approvedContent, boolean containsAnswerSentence) {
        Objects.requireNonNull(current); Objects.requireNonNull(minimalBrief);
        if (!current.active() || !current.connected() || !current.recordingReady()
                || current.stage() != Stage.CUE || current.consent() != Consent.ACCEPTED
                || current.cueAlreadyGiven() || current.generation() != generation
                || !Objects.equals(current.scenarioId(), scenarioId) || !approvedContent || containsAnswerSentence) {
            return Optional.empty();
        }
        return Optional.of(minimalBrief);
    }
}
