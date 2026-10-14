package kr.mujoong.core.notification.transport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.notification.PushEventType;
import kr.mujoong.testsupport.time.MutableClock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag("u7")
class PushDispatchWorkerTest {
    private final PushDispatchRepository repository = mock(PushDispatchRepository.class);
    private final PushDeliverySender sender = mock(PushDeliverySender.class);
    private final MutableClock clock = new MutableClock(Instant.EPOCH);
    private final List<Duration> backoff =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));
    private final Duration lease = Duration.ofSeconds(6);

    @Test
    void disabledOrUnreadyDoesNotClaimRows() {
        when(sender.enabled()).thenReturn(false);
        assertThat(worker(true).runOnce()).isZero();
        when(sender.enabled()).thenReturn(true);
        assertThat(worker(false).runOnce()).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void restoredClaimSkipsAlreadyAcceptedRecipientsAndFinishesAfterPendingCheckpoint() {
        when(sender.enabled()).thenReturn(true);
        UUID successful = UUID.randomUUID(), pending = UUID.randomUUID();
        var row =
                new PushDispatchRepository.Dispatch(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PushEventType.VOICE_PROBLEM,
                        ActivityType.FREE_TALK,
                        "event",
                        2,
                        UUID.randomUUID(),
                        Map.of(
                                successful.toString(),
                                new RecipientProgress(RecipientProgress.Status.SENT, 1, null),
                                pending.toString(),
                                RecipientProgress.pending()));
        when(repository.claimNext(clock.now(), lease)).thenReturn(Optional.of(row));
        assertThat(worker(true).runOnce()).isEqualTo(1);
        var order = inOrder(repository);
        order.verify(repository).claimNext(clock.now(), lease);
        order.verify(repository).processRecipient(row, pending, sender, 4, lease);
        order.verify(repository).finish(row, clock.now(), backoff);
        verify(repository, never())
                .processRecipient(eq(row), eq(successful), any(), anyInt(), any());
    }

    @Test
    void noDueRowAndFailedCheckpointDoNotClaimSuccessfulCompletion() {
        when(sender.enabled()).thenReturn(true);
        when(repository.claimNext(clock.now(), lease)).thenReturn(Optional.empty());
        assertThat(worker(true).runOnce()).isZero();
        verify(repository, never()).finish(any(), any(), any());
    }

    private PushDispatchWorker worker(boolean ready) {
        return new PushDispatchWorker(repository, sender, clock, () -> ready, backoff, lease);
    }
}
