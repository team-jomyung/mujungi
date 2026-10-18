package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class PracticeModelTest {
    static final Instant T = Instant.parse("2026-10-14T00:00:00Z");
    static EvaluationWork work() {
        return new EvaluationWork("u5b-q6", Duration.ofSeconds(12), T.plusSeconds(12),
                Duration.ofSeconds(1), 0, null, null, false);
    }
    static PracticeAttempt original() {
        return new PracticeAttempt(UUID.randomUUID(), UUID.randomUUID(), AttemptType.ORIGINAL,
                AttemptState.EXPOSED, UUID.randomUUID(), null, T, null);
    }
    static PracticeResponse pending(PracticeAttempt a, ResponseType type) {
        return new PracticeResponse(UUID.randomUUID(), a.id(), a.scenarioId(), type, 1,
                EvaluationState.PENDING, 1, T, work(), null, null, T, null);
    }
    @Test void separatesFirstAndAfterCueWithoutReplacingTheOriginal() {
        var a = original(); var first = pending(a, ResponseType.FIRST); var after = pending(a, ResponseType.AFTER_CUE);
        first.requireAttempt(a); after.requireAttempt(a);
        assertThat(first.id()).isNotEqualTo(after.id()); assertThat(first.label()).isNull();
        assertThat(first.work().cutoff()).isEqualTo(T.plusSeconds(11));
    }
    @Test void rejectsNewResponseOnOriginalAndDifferentScenario() {
        var a = original();
        assertThatThrownBy(() -> pending(a, ResponseType.NEW_SCENARIO).requireAttempt(a)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending(a, ResponseType.FIRST).requireAttempt(original())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void distinguishesNoResponseFromUncertainAndPending() {
        var a = original();
        var absent = new PracticeResponse(UUID.randomUUID(), a.id(), a.scenarioId(), ResponseType.FIRST, null,
                EvaluationState.FINALIZED, 1, T, null, Label.NOT_PERFORMED, "NO_RESPONSE", T, T);
        absent.requireAttempt(a); assertThat(absent.work()).isNull();
        assertThatThrownBy(() -> new Evaluation(UUID.randomUUID(), absent.id(), Label.NOT_PERFORMED,
                null, null, true, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PracticeResponse(UUID.randomUUID(), a.id(), a.scenarioId(), ResponseType.FIRST,
                1, EvaluationState.PENDING, 1, T, work(), Label.uncertain, null, T, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesFinalEvidenceAndPreservesTerminatedPending() {
        var p = pending(original(), ResponseType.FIRST);
        var terminated = new PracticeResponse(p.id(), p.attemptId(), p.scenarioId(), p.responseType(), 1,
                EvaluationState.TERMINATED_PENDING, 2, T, work(), null, null, T, null);
        assertThat(terminated.utteranceSeq()).isEqualTo(1); assertThat(terminated.label()).isNull();
        var valid = new Evaluation(UUID.randomUUID(), p.id(), Label.functional, "보호할 이유", 1, false, 1, "m", "p");
        valid.requireResponse(p); assertThat(valid.toString()).doesNotContain("보호할 이유");
        var invalid = new Evaluation(UUID.randomUUID(), p.id(), Label.functional, null, 2, false, 1, null, null);
        assertThatThrownBy(() -> invalid.requireResponse(p)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesBudgetAndAttemptOwnership() {
        assertThatThrownBy(() -> new EvaluationWork("v", Duration.ofSeconds(12), T, Duration.ZERO, 0, null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationWork("v", Duration.ofSeconds(12), T, Duration.ofSeconds(1), 3, null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationWork("v", Duration.ofSeconds(12), T, Duration.ofSeconds(1), 1,
                UUID.randomUUID(), null, false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preservesPreparationFailureWithoutInventingScenario() {
        var a = original();
        var failed = new PracticeAttempt(UUID.randomUUID(), a.sessionGoalId(), AttemptType.NEW_SCENARIO,
                AttemptState.PREPARATION_FAILED, null, a.scenarioId(), null, PreparationFailure.TIMEOUT);
        assertThat(failed.scenarioId()).isNull();
        assertThatThrownBy(() -> new PracticeAttempt(UUID.randomUUID(), a.sessionGoalId(), AttemptType.NEW_SCENARIO,
                AttemptState.ISSUED, a.scenarioId(), a.scenarioId(), null, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresActualCueEvidenceAndConsent() {
        var a = original();
        var declined = new CueDelivery(UUID.randomUUID(), a.id(), a.scenarioId(), Consent.DECLINED,
                DeliveryState.NOT_GIVEN, null, null, null);
        declined.requireAttempt(a); assertThat(declined.givenAt()).isNull();
        assertThatThrownBy(() -> new CueDelivery(UUID.randomUUID(), a.id(), a.scenarioId(), Consent.ACCEPTED,
                DeliveryState.COMPLETED, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CueDelivery(UUID.randomUUID(), a.id(), a.scenarioId(), Consent.NO_RESPONSE,
                DeliveryState.STARTED, "도움", T, false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void freezesGoalDefinitionAndRedactsSensitiveText() {
        var g = new SessionGoal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "차례", "turn", 1, "보호할 기준", GoalStatus.PENDING, null, 0, null);
        var next = g.progress(GoalStatus.RUNNING, Stage.FIRST_RESPONSE, null);
        assertThat(next.judgmentCriteriaSnapshot()).isEqualTo(g.judgmentCriteriaSnapshot());
        assertThat(next.order()).isEqualTo(g.order()); assertThat(next.generation()).isEqualTo(1);
        assertThat(g.toString()).doesNotContain("보호할 기준");
        assertThatThrownBy(() -> g.progress(GoalStatus.SKIPPED, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
