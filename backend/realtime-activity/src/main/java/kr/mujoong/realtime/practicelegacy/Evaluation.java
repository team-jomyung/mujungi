package kr.mujoong.realtime.practicelegacy;

import java.util.Objects;
import java.util.UUID;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;

public record Evaluation(UUID id, UUID responseId, Label label, String reason,
        Integer evidenceUtteranceSeq, boolean reviewNeeded, int attempts,
        String modelVersion, String promptVersion) {
    public Evaluation {
        Objects.requireNonNull(id); Objects.requireNonNull(responseId); Objects.requireNonNull(label);
        PracticeChecks.require(label != Label.NOT_PERFORMED && attempts >= 0 && attempts <= 2, "판정 라벨/횟수 오류");
        PracticeChecks.require(evidenceUtteranceSeq == null || evidenceUtteranceSeq > 0, "증거 순번 오류");
        PracticeChecks.require(label != Label.uncertain || reviewNeeded, "불확실 판정은 검토 필요");
    }
    public void requireResponse(PracticeResponse response) {
        PracticeChecks.require(responseId.equals(response.id()) && response.utteranceSeq() != null, "판정 반응 참조 오류");
        PracticeChecks.require(evidenceUtteranceSeq == null || evidenceUtteranceSeq.equals(response.utteranceSeq()), "다른 발화의 판정 증거");
        PracticeChecks.require(response.label() == null || response.label() == label, "최종 라벨 불일치");
    }
    @Override public String toString() { return "Evaluation[id=" + id + ", responseId=" + responseId + "]"; }
}
