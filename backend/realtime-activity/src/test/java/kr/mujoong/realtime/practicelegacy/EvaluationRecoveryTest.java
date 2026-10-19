package kr.mujoong.realtime.practicelegacy;

import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("practice-legacy")
class EvaluationRecoveryTest {
    private final PracticeRepository repository = mock(PracticeRepository.class);
    private final MutableClock clock = new MutableClock(PracticeModelTest.T);
    private final PracticeResponse pending = PracticeModelTest.pending(PracticeModelTest.original(), ResponseType.FIRST);

    @Test
    void originalCutoffMustExpireBeforeRecoveryCanReplaceALostProviderCall() {
        when(repository.response(pending.id())).thenReturn(Optional.of(pending));
        clock.advance(Duration.ofSeconds(10));

        assertThat(ResponseEvaluator.recoverExpired(repository, pending.id(), clock)).isEmpty();

        verify(repository, never()).finalizeResult(any(), anyLong(), any(), any(), any());
        verify(repository, never()).finalizeWithinBudget(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void recoveryInsideTheOriginalReserveUsesTheSameDeadlineGenerationAndAttemptCount() {
        when(repository.response(pending.id())).thenReturn(Optional.of(pending));
        when(repository.finalizeWithinBudget(eq(pending.id()), eq(1L), isNull(), any(),
                eq(pending.work().deadlineAt()), eq(clock))).thenReturn(true);
        clock.advance(Duration.ofSeconds(11));

        assertThat(ResponseEvaluator.recoverExpired(repository, pending.id(), clock))
                .contains(new ResponseEvaluator.Completion(pending.id(), true));

        var value = ArgumentCaptor.forClass(Evaluation.class);
        verify(repository).finalizeWithinBudget(eq(pending.id()), eq(1L), isNull(), value.capture(),
                eq(pending.work().deadlineAt()), eq(clock));
        assertThat(value.getValue().label()).isEqualTo(Label.uncertain);
        assertThat(value.getValue().attempts()).isEqualTo(pending.work().attemptsStarted());
        assertThat(value.getValue().reviewNeeded()).isTrue();
    }

    @Test
    void recoveryAfterDowntimeKeepsTheOriginalBudgetAndReportsTheMissedDeadline() {
        when(repository.response(pending.id())).thenReturn(Optional.of(pending));
        when(repository.finalizeResult(eq(pending.id()), eq(1L), isNull(), any(), any())).thenReturn(true);
        clock.advance(Duration.ofSeconds(30));

        assertThat(ResponseEvaluator.recoverExpired(repository, pending.id(), clock))
                .contains(new ResponseEvaluator.Completion(pending.id(), false));

        verify(repository, never()).claimAttempt(any(), anyLong(), any(), any());
        verify(repository, never()).finalizeWithinBudget(any(), anyLong(), any(), any(), any(), any());
        assertThat(pending.work().deadlineAt()).isEqualTo(PracticeModelTest.T.plusSeconds(12));
    }

    @Test
    void concurrentFinalizationWinnerPreventsASecondRecoveryCompletion() {
        when(repository.response(pending.id())).thenReturn(Optional.of(pending));
        clock.advance(Duration.ofSeconds(30));

        assertThat(ResponseEvaluator.recoverExpired(repository, pending.id(), clock)).isEmpty();
    }

    @Test
    void missingOrAlreadyTerminatedResponseCannotBeResurrected() {
        when(repository.response(pending.id())).thenReturn(Optional.empty());
        assertThat(ResponseEvaluator.recoverExpired(repository, pending.id(), clock)).isEmpty();
        var terminated = new PracticeResponse(pending.id(), pending.attemptId(), pending.scenarioId(), pending.responseType(),
                pending.utteranceSeq(), EvaluationState.TERMINATED_PENDING, pending.evaluationGeneration(),
                pending.acceptedAt(), pending.work(), null, null, pending.opportunityOpenedAt(), null);
        when(repository.response(pending.id())).thenReturn(Optional.of(terminated));
        clock.advance(Duration.ofSeconds(30));
        assertThat(ResponseEvaluator.recoverExpired(repository, pending.id(), clock)).isEmpty();
        verify(repository, never()).finalizeResult(any(), anyLong(), any(), any(), any());
    }
}
