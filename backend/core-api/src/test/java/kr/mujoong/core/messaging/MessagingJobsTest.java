package kr.mujoong.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 메시징 주기 작업 (BR5.7 · BR5.8 · BR5.11 · BR5.12 · RD-9): 재호출 폴러는 준비 완료일 때만 도래 행을 제출하고 풀이 거절하면 그
 * 사이클을 멈춘다. 보존 정리는 Outbox FAILED · Inbox DEAD를 7일 기준으로 배치가 가득 찬 동안 되풀이해 비우고 비운 수를 돌려준다.
 */
@Tag("u1")
class MessagingJobsTest {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final MutableClock clock = new MutableClock(Instant.parse("2001-11-01T00:00:00Z"));
    private final CoreInboxRepository inbox = mock(CoreInboxRepository.class);
    private final CoreOutboxRepository outbox = mock(CoreOutboxRepository.class);
    private final CoreInboxDispatcher dispatcher = mock(CoreInboxDispatcher.class);

    @Test
    void repollerSubmitsEveryDueRowWhenReady() {
        List<InboxEvent> due = List.of(row(), row(), row());
        when(inbox.findDue(clock.now(), 20)).thenReturn(due);
        when(dispatcher.submit(any())).thenReturn(true);
        CoreInboxRepoller repoller = new CoreInboxRepoller(inbox, dispatcher, () -> true, clock, 20);

        assertThat(repoller.runOnce()).isEqualTo(3);

        for (InboxEvent event : due) {
            verify(dispatcher).submit(event.eventId());
        }
    }

    @Test
    void repollerDoesNothingBeforeReadiness() {
        AtomicBoolean ready = new AtomicBoolean(false);
        CoreInboxRepoller repoller = new CoreInboxRepoller(inbox, dispatcher, ready::get, clock, 20);

        assertThat(repoller.runOnce()).isZero();

        verify(inbox, never()).findDue(any(), anyInt());
        verify(dispatcher, never()).submit(any());
    }

    @Test
    void repollerStopsTheCycleWhenThePoolRejects() {
        List<InboxEvent> due = List.of(row(), row(), row());
        when(inbox.findDue(clock.now(), 20)).thenReturn(due);
        when(dispatcher.submit(any())).thenReturn(true, false, true);
        CoreInboxRepoller repoller = new CoreInboxRepoller(inbox, dispatcher, () -> true, clock, 20);

        assertThat(repoller.runOnce()).isEqualTo(1);

        verify(dispatcher, times(2)).submit(any());
        verify(dispatcher, never()).submit(due.get(2).eventId());
    }

    @Test
    void cleanupRepeatsWhileBatchesAreFullAndReportsCounts() {
        when(outbox.purgeFailedOlderThan(clock.now(), RETENTION, 2)).thenReturn(2, 2, 1);
        when(inbox.purgeDeadOlderThan(clock.now(), RETENTION, 2)).thenReturn(0);
        MessagingCleanupJob job = new MessagingCleanupJob(outbox, inbox, clock, RETENTION, 2);

        MessagingCleanupJob.Result result = job.runOnce();

        assertThat(result).isEqualTo(new MessagingCleanupJob.Result(5, 0));
        verify(outbox, times(3)).purgeFailedOlderThan(clock.now(), RETENTION, 2);
        verify(inbox, times(1)).purgeDeadOlderThan(clock.now(), RETENTION, 2);
    }

    @Test
    void cleanupStopsOnAnExactlyFullLastBatchFollowedByEmpty() {
        when(outbox.purgeFailedOlderThan(clock.now(), RETENTION, 3)).thenReturn(3, 0);
        when(inbox.purgeDeadOlderThan(clock.now(), RETENTION, 3)).thenReturn(3, 3, 0);
        MessagingCleanupJob job = new MessagingCleanupJob(outbox, inbox, clock, RETENTION, 3);

        assertThat(job.runOnce()).isEqualTo(new MessagingCleanupJob.Result(3, 6));
    }

    @Test
    void nonPositiveBatchSizesAreRejected() {
        assertThatThrownBy(() -> new MessagingCleanupJob(outbox, inbox, clock, RETENTION, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreInboxRepoller(inbox, dispatcher, () -> true, clock, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private InboxEvent row() {
        return new InboxEvent(UUID.randomUUID(), "realtime", "DeviceStatusChanged", 1, clock.now(), UUID.randomUUID(),
                UUID.randomUUID(), "{}", "hash", "RECEIVED", 0, clock.now(), clock.now(), null, null, null, null);
    }
}
