package kr.mujoong.realtime.practice;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static kr.mujoong.realtime.practice.PracticeTypes.*;

public record CueDelivery(UUID id, UUID attemptId, UUID scenarioId, Consent consentResponse,
        DeliveryState deliveryState, String deliveredText, Instant givenAt, Boolean containsAnswerSentence) {
    public CueDelivery {
        Objects.requireNonNull(id); Objects.requireNonNull(attemptId); Objects.requireNonNull(scenarioId);
        Objects.requireNonNull(consentResponse); Objects.requireNonNull(deliveryState);
        boolean given = givenAt != null;
        PracticeChecks.require(given == (deliveredText != null) && given == (containsAnswerSentence != null), "실제 도움 증거 불일치");
        PracticeChecks.require(!given || consentResponse == Consent.ACCEPTED, "동의 없는 도움 금지");
        PracticeChecks.require((deliveryState != DeliveryState.STARTED && deliveryState != DeliveryState.COMPLETED) || given,
                "도움 재생 증거 필요");
        PracticeChecks.require((deliveryState != DeliveryState.NOT_GIVEN && deliveryState != DeliveryState.REQUESTED) || !given,
                "요청은 도움 제공 증거가 아님");
        PracticeChecks.require(consentResponse == Consent.ACCEPTED || deliveryState == DeliveryState.NOT_GIVEN,
                "거부/무응답에서 도움 요청 금지");
    }
    public void requireAttempt(PracticeAttempt attempt) {
        PracticeChecks.require(attempt.id().equals(attemptId) && scenarioId.equals(attempt.scenarioId())
                && attempt.attemptType() == AttemptType.ORIGINAL, "도움 장면 참조 오류");
    }
    @Override public String toString() { return "CueDelivery[id=" + id + ", state=" + deliveryState + "]"; }
}
