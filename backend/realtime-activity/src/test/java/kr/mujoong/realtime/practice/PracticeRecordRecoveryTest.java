package kr.mujoong.realtime.practice;

import static kr.mujoong.realtime.practice.PracticeTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u5b")
class PracticeRecordRecoveryTest {
    private final PracticeRepository repository = mock(PracticeRepository.class);
    private final MutableClock clock = new MutableClock(PracticeModelTest.T);
    private final EventLogger events = mock(EventLogger.class);
    private final UUID sessionId = UUID.randomUUID();
    private final SessionGoal goal = new SessionGoal(UUID.randomUUID(), sessionId, UUID.randomUUID(), UUID.randomUUID(),
            1, "차례", "turn", 1, "고정 기준", GoalStatus.NOT_REACHED, null, 0, null);
    private final PracticeRepository.Snapshot empty = new PracticeRepository.Snapshot(List.of(), List.of(), List.of(), List.of(), List.of());

    @Test
    void recoveryCannotReplaceMissingPracticeWithNull() {
        when(repository.snapshot(sessionId)).thenReturn(empty);
        assertThatThrownBy(() -> recovery().recover(sessionId)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).settle(any());
    }

    @Test
    void settledSourceIsProjectedAndDuplicateRecoveryDoesNotChangeTheRecord() {
        var snapshot = new PracticeRepository.Snapshot(List.of(goal), List.of(), List.of(), List.of(), List.of());
        when(repository.snapshot(sessionId)).thenReturn(snapshot);

        Object first = recovery().recover(sessionId);

        assertThat(recovery().recover(sessionId)).isEqualTo(first);
        assertThat(first).isInstanceOf(PracticeRecordProjector.PracticeRecord.class);
        verify(repository, times(2)).settle(sessionId);
    }

    @Test
    void pendingJudgmentBeforeTheOriginalCutoffDefersTheSnapshot() {
        var response = PracticeModelTest.pending(PracticeModelTest.original(), ResponseType.FIRST);
        var snapshot = new PracticeRepository.Snapshot(List.of(goal), List.of(), List.of(response), List.of(), List.of());
        when(repository.snapshot(sessionId)).thenReturn(snapshot);
        when(repository.response(response.id())).thenReturn(Optional.of(response));
        clock.advance(Duration.ofSeconds(10));

        assertThatThrownBy(() -> recovery().recover(sessionId)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).settle(sessionId);
        verifyNoInteractions(events);
    }

    private JdbcPracticeRecordRecovery recovery() {
        return new JdbcPracticeRecordRecovery(repository, clock, events);
    }
}
