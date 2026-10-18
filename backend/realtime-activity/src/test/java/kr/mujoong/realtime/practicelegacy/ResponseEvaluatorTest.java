package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("practice-legacy")
@Tag("db")
class ResponseEvaluatorTest {
    @Test void savesBeforeCallAndCommitsOnce() {
        var f = new EvaluationFixture(); f.start(f.evaluator());
        assertThat(f.saved().evaluationState()).isEqualTo(EvaluationState.PENDING);
        assertThat(f.requests.getFirst().scenarioId()).isEqualTo(f.response.scenarioId());
        assertThat(f.requests.getFirst().utteranceSeq()).isEqualTo(f.response.utteranceSeq());
        f.answers.getFirst().complete(f.good());
        assertThat(f.saved().label()).isEqualTo(Label.functional);
        assertThat(f.completed).containsExactly(new ResponseEvaluator.Completion(f.response.id(), true));
        f.start(f.evaluator()); assertThat(f.requests).hasSize(1);
    }
    @Test void retryAfterFailureKeepsOriginalDeadline() {
        var f = new EvaluationFixture(); f.start(f.evaluator());
        f.answers.getFirst().completeExceptionally(new IllegalStateException("합성 공급자 오류")); f.timers.runDue();
        assertThat(f.requests).hasSize(2);
        f.answers.get(1).complete(f.good());
        assertThat(f.saved().work().deadlineAt()).isEqualTo(f.response.work().deadlineAt());
        assertThat(f.saved().work().attemptsStarted()).isEqualTo(2);
    }
    @Test void validationFailureRetriesThenBecomesUncertain() {
        var f = new EvaluationFixture(); f.start(f.evaluator());
        var invalid = new EvaluationProvider.Result(Label.NOT_PERFORMED, null, null, false, null, null);
        f.answers.getFirst().complete(invalid); f.timers.runDue(); f.answers.get(1).complete(invalid);
        assertThat(f.saved().label()).isEqualTo(Label.uncertain);
        assertThat(f.saved().utteranceSeq()).isEqualTo(1); assertThat(f.failures).isEmpty();
    }
    @Test void duplicateWorkHasSingleProviderOwner() {
        var f = new EvaluationFixture(); var evaluator = f.evaluator(); f.start(evaluator); f.start(evaluator);
        assertThat(f.requests).hasSize(1); f.answers.getFirst().complete(f.good());
        f.timers.advance(Duration.ofSeconds(12)); assertThat(f.completed).hasSize(1);
    }
    @Test void expiredResultCannotOverwriteRetry() {
        var f = new EvaluationFixture(); f.start(f.evaluator()); f.timers.advance(Duration.ofSeconds(5));
        assertThat(f.requests).hasSize(2); f.answers.getFirst().complete(f.good());
        assertThat(f.saved().evaluationState()).isEqualTo(EvaluationState.PENDING);
        f.answers.get(1).complete(f.good()); assertThat(f.saved().label()).isEqualTo(Label.functional);
    }
    @Test void terminationAndDeletionSuppressLateResult() {
        var f = new EvaluationFixture(); f.start(f.evaluator()); f.db.repository.terminate(f.db.session);
        f.answers.getFirst().complete(f.good()); assertThat(f.saved().evaluationState()).isEqualTo(EvaluationState.TERMINATED_PENDING);
        var deleted = new EvaluationFixture(); deleted.start(deleted.evaluator()); deleted.db.repository.delete(deleted.db.guardian);
        deleted.answers.getFirst().complete(deleted.good()); deleted.timers.advance(Duration.ofSeconds(12));
        assertThat(deleted.completed).isEmpty(); assertThat(deleted.failures).isEmpty();
    }
    @Test void firstTimeoutStartsOnlyOneRetryAndSecondTimeoutFinalizes() {
        var f = new EvaluationFixture(); f.start(f.evaluator());
        f.timers.advance(Duration.ofMillis(4_999)); assertThat(f.requests).hasSize(1);
        f.timers.advance(Duration.ofMillis(1)); assertThat(f.requests).hasSize(2);
        f.timers.advance(Duration.ofSeconds(5)); assertThat(f.saved().label()).isEqualTo(Label.uncertain);
        assertThat(f.requests).hasSize(2);
    }
    @Test void rejectsInvalidBudgetAndRedactsRequests() {
        assertThatThrownBy(() -> new EvaluationBudget("v", Duration.ofSeconds(13), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationBudget("v", Duration.ofSeconds(12), Duration.ofSeconds(6), Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationBudget("v", Duration.ofSeconds(12), Duration.ofSeconds(5), Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        var f = new EvaluationFixture(); f.start(f.evaluator());
        assertThat(f.requests.getFirst().toString()).doesNotContain("고정 기준", "합성 반응");
    }
}
