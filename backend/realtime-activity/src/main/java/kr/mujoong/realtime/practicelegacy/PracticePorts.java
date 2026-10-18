package kr.mujoong.realtime.practicelegacy;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** I1~I5 합의 전 U5b가 요구하는 능력만 표현한다. 실제 어댑터/빈은 등록하지 않는다. */
public final class PracticePorts {
    private PracticePorts() { }
    public enum OutputKind { SCENARIO, HELP_QUESTION, CUE }
    public enum Playback { STARTED, COMPLETED, FAILED, INTERRUPTED, OMITTED }
    public record Scene(UUID id, String situation, String partnerRole, String trigger) {
        public Scene {
            java.util.Objects.requireNonNull(id); PracticeChecks.text(situation, "상황");
            PracticeChecks.text(partnerRole, "역할"); PracticeChecks.text(trigger, "트리거");
        }
        @Override public String toString() { return "Scene[id=" + id + "]"; }
    }
    public record Output(UUID correlationId, UUID sessionId, UUID goalId, UUID scenarioId, long generation,
            OutputKind kind, Scene scene) { }
    public interface OutputPort {
        void request(Output output);
        void cancel(UUID correlationId);
        void lastGoal(UUID sessionId);
    }
    public interface ScenarioPort {
        CompletionStage<Scene> prepare(UUID sessionId, SessionGoal goal, UUID previousScenarioId);
    }
    public interface EvaluationPort {
        void request(PracticeResponse response, String fixedCriteria, String responseText);
    }
}
