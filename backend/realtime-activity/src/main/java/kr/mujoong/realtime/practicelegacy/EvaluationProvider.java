package kr.mujoong.realtime.practicelegacy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;

/** 실제 공급자는 후속 연결한다. 요청 시한은 네트워크 왕복과 검증을 포함한다. */
public interface EvaluationProvider {
    CompletionStage<Result> evaluate(Request request);
    record Request(UUID responseId, UUID token, UUID scenarioId, int utteranceSeq, String criteria, String responseText, Instant deadline) {
        @Override public String toString() { return "EvaluationRequest[responseId=" + responseId + "]"; }
    }
    record Result(Label label, String reason, Integer evidenceUtteranceSeq, boolean reviewNeeded,
            String modelVersion, String promptVersion) {
        @Override public String toString() { return "EvaluationResult[label=" + label + "]"; }
    }
}
