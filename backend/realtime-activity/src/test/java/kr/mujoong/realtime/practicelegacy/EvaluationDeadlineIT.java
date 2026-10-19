package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("practice-legacy")
@Tag("db")
class EvaluationDeadlineIT {
    @Test void clockRollbackCannotMakeExpiredProviderResponseValidAgain() {
        var f = new EvaluationFixture(); f.start(f.evaluator());
        f.clock.advance(Duration.ofSeconds(5)); f.clock.setInstant(PracticeModelTest.T.minusSeconds(10));
        f.answers.getFirst().complete(f.good());
        assertThat(f.saved().label()).isNull();
        assertThat(f.saved().work().activeAttemptToken()).isNull();
        assertThat(f.completed).isEmpty();
    }
    @Test void evaluationClockUsesEarlierBudgetExpiryOfWallAndMonotonicTime() {
        var source = new kr.mujoong.testsupport.time.MutableClock(PracticeModelTest.T);
        var clock = new EvaluationClock(source);
        source.advance(Duration.ofSeconds(12)); source.setInstant(PracticeModelTest.T.minusSeconds(20));
        assertThat(clock.now()).isEqualTo(PracticeModelTest.T.plusSeconds(12));
        assertThat(clock.monotonicNanos()).isEqualTo(source.monotonicNanos());
        source.setInstant(PracticeModelTest.T.plusSeconds(30)); assertThat(clock.now()).isEqualTo(source.now());
        source.setInstant(PracticeModelTest.T); assertThat(clock.now()).isEqualTo(PracticeModelTest.T.plusSeconds(30));
    }
    @Test void independentCutoffFinalizesWhenProviderQueueNeverRuns() {
        var f = new EvaluationFixture(); var queue = new ArrayList<Runnable>();
        f.start(f.evaluator(f.db.repository, queue::add, EvaluationFixture.budget()));
        f.timers.advance(Duration.ofMillis(10_999)); assertThat(f.saved().label()).isNull();
        f.timers.advance(Duration.ofMillis(1)); assertThat(f.saved().label()).isEqualTo(Label.uncertain);
        queue.forEach(Runnable::run); assertThat(f.requests).isEmpty();
        assertThat(f.completed.getFirst().committedWithinBudget()).isTrue();
    }
    @Test void restartUsesPersistedDeadlineDespiteNewSettings() {
        var f = new EvaluationFixture(); f.clock.advance(Duration.ofSeconds(10));
        var changed = new EvaluationBudget("next", Duration.ofSeconds(8), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ZERO);
        f.start(f.evaluator(f.db.repository, Runnable::run, changed));
        assertThat(f.requests.getFirst().deadline()).isEqualTo(PracticeModelTest.T.plusSeconds(11));
        assertThat(f.saved().work().budgetVersion()).isEqualTo(f.response.work().budgetVersion());
        f.timers.advance(Duration.ofSeconds(1)); assertThat(f.saved().label()).isEqualTo(Label.uncertain);
    }
    @Test void retryDelayThatConsumesRemainingBudgetSkipsRetry() {
        var f = new EvaluationFixture();
        var budget = new EvaluationBudget("delay", Duration.ofSeconds(12), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(11));
        f.start(f.evaluator(f.db.repository, Runnable::run, budget)); f.answers.getFirst().completeExceptionally(new IllegalStateException());
        assertThat(f.requests).hasSize(1); assertThat(f.saved().label()).isEqualTo(Label.uncertain);
    }
    @Test void reportsCommitAtDeadlineAsWithinAndAfterDeadlineAsViolation() {
        for (long delay : new long[] {1_000, 1_001}) {
            var f = new EvaluationFixture(); var delayed = spy(f.db.repository);
            doAnswer(invocation -> { Object result = invocation.callRealMethod(); f.clock.advanceMillis(delay); return result; })
                    .when(delayed).finalizeWithinBudget(any(), anyLong(), nullable(java.util.UUID.class), any(), any(), any());
            var queue = new ArrayList<Runnable>(); f.start(f.evaluator(delayed, queue::add, EvaluationFixture.budget()));
            f.timers.advance(Duration.ofSeconds(11));
            assertThat(f.completed.getFirst().committedWithinBudget()).isEqualTo(delay == 1_000);
            assertThat(f.saved().utteranceSeq()).isEqualTo(1);
        }
    }
    @Test void dbFailureIsExposedWithoutInventingCompletion() {
        var f = new EvaluationFixture(); var broken = spy(f.db.repository);
        doThrow(new IllegalStateException("합성 저장 실패")).when(broken).finalizeResult(any(), anyLong(), nullable(java.util.UUID.class), any(), any());
        f.start(f.evaluator(broken, Runnable::run, EvaluationFixture.budget())); f.answers.getFirst().complete(f.good());
        assertThat(f.failures).hasSize(1); assertThat(f.completed).isEmpty();
        assertThat(f.saved().evaluationState()).isEqualTo(EvaluationState.PENDING);
    }
    @Test void delayedClaimDoesNotStartExpiredNetworkRequest() {
        var f = new EvaluationFixture(); var delayed = spy(f.db.repository);
        doAnswer(invocation -> { Object result = invocation.callRealMethod(); f.clock.advance(Duration.ofSeconds(5)); return result; })
                    .when(delayed).claimAttempt(any(), anyLong(), any(), any());
        f.start(f.evaluator(delayed, Runnable::run, EvaluationFixture.budget()));
        assertThat(f.requests).isEmpty(); f.timers.runDue();
        assertThat(f.requests).isEmpty(); assertThat(f.saved().label()).isEqualTo(Label.uncertain);
    }
}
