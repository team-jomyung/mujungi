package kr.mujoong.realtime.usage;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("ai-usage")
class AiUsageRecorderTest {
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final UUID session = UUID.randomUUID();
    private final AiUsageTimingProperties timing = new AiUsageTimingProperties(Duration.ofSeconds(1),
            Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(1));

    private AiUsageRecord voice(UUID id, long revision, double seconds) {
        return new AiUsageRecord(id, AiUsageRecord.Source.VOICE, session, null, "synthetic-session", null,
                "synthetic-model", null, null, null, null, seconds, AiUsageRecord.Status.PARTIAL, revision, clock.now());
    }

    private AiUsageRecorder recorder(AiUsageRecorder.Store store, int capacity, int batch) {
        return new AiUsageRecorder(store, clock, new AiUsageProperties(true), timing,
                new AiUsageLimitsProperties(capacity, batch), metrics);
    }

    @Test void latestCumulativeSnapshotReplacesThePendingValueAndNeverSumsIt() {
        List<AiUsageRecord> saved = new ArrayList<>();
        var recorder = recorder((r, allowed) -> { saved.add(r); return AiUsageRepository.Result.STORED; }, 2, 2);
        UUID id = UUID.randomUUID();
        recorder.offer(voice(id, 1, 10));
        recorder.offer(voice(id, 2, 20));
        recorder.offer(voice(id, 1, 10));
        assertThat(recorder.pendingCount()).isEqualTo(1);
        recorder.flushOnce();
        assertThat(saved).extracting(AiUsageRecord::voiceSeconds).containsExactly(20.0);
        assertThat(recorder.pendingCount()).isZero();
    }

    @Test void fullQueueRejectsNewKeysButStillAcceptsANewerSnapshot() {
        var recorder = recorder((r, allowed) -> AiUsageRepository.Result.STORED, 1, 1);
        UUID id = UUID.randomUUID();
        assertThat(recorder.offer(voice(id, 1, 10))).isTrue();
        assertThat(recorder.offer(voice(UUID.randomUUID(), 1, 10))).isFalse();
        assertThat(recorder.offer(voice(id, 2, 20))).isTrue();
        assertThat(metrics.get("mujoong.ai.usage.records").tag("outcome", "full").counter().count()).isEqualTo(1);
    }

    @Test void anUnlinkedTurnDoesNotStarveLaterRecordsAndExpiresWithAMetric() {
        List<UUID> written = new ArrayList<>();
        UUID unlinked = UUID.randomUUID();
        var recorder = recorder((r, allowed) -> {
            written.add(r.id());
            return r.id().equals(unlinked) ? AiUsageRepository.Result.UNLINKED : AiUsageRepository.Result.STORED;
        }, 2, 1);
        UUID linked = UUID.randomUUID();
        recorder.offer(voice(unlinked, 1, 1));
        recorder.offer(voice(linked, 1, 1));
        recorder.flushOnce();
        recorder.flushOnce();
        assertThat(written).containsExactly(unlinked, linked);
        clock.advance(Duration.ofMinutes(5));
        recorder.flushOnce();
        assertThat(recorder.pendingCount()).isZero();
        assertThat(metrics.get("mujoong.ai.usage.records").tag("outcome", "expired").counter().count()).isEqualTo(1);
    }

    @Test void failedWritesStayPendingAndAnUpdateDuringTheWriteIsNotRemoved() {
        UUID id = UUID.randomUUID();
        AiUsageRecorder[] holder = new AiUsageRecorder[1];
        int[] attempts = {0};
        holder[0] = recorder((r, allowed) -> {
            if (attempts[0]++ == 0) throw new IllegalStateException("synthetic failure");
            if (r.revision() == 1) holder[0].offer(voice(id, 2, 20));
            return AiUsageRepository.Result.STORED;
        }, 2, 2);
        holder[0].offer(voice(id, 1, 10));
        holder[0].flushOnce();
        holder[0].flushOnce();
        assertThat(holder[0].pendingCount()).isEqualTo(1);
        holder[0].flushOnce();
        assertThat(holder[0].pendingCount()).isZero();
        assertThat(attempts[0]).isEqualTo(3);
    }

    @Test void producerDoesNotWaitForABlockedStoreAndDeletionWaitsForTheWrite() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var recorder = recorder((r, allowed) -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            return allowed.test(session) ? AiUsageRepository.Result.STORED : AiUsageRepository.Result.DISCARDED;
        }, 2, 2);
        recorder.offer(voice(UUID.randomUUID(), 1, 1));
        CompletableFuture<Void> flushing = CompletableFuture.runAsync(recorder::flushOnce);
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(CompletableFuture.supplyAsync(() -> recorder.offer(voice(UUID.randomUUID(), 1, 2)))
                    .get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(recorder.blockSessions(List.of(session), Duration.ofMillis(10))).isFalse();
            assertThat(recorder.offer(voice(UUID.randomUUID(), 1, 3))).isFalse();
        } finally { release.countDown(); }
        flushing.get(2, TimeUnit.SECONDS);
        assertThat(recorder.blockSessions(List.of(session), Duration.ofSeconds(1))).isTrue();
    }

    @Test void disabledCollectionNeverCallsTheStore() {
        var recorder = new AiUsageRecorder((r, allowed) -> { throw new AssertionError("disabled store"); }, clock,
                new AiUsageProperties(false), timing, new AiUsageLimitsProperties(1, 1), metrics);
        assertThat(recorder.enabled()).isFalse();
        assertThat(recorder.offer(voice(UUID.randomUUID(), 1, 0))).isTrue();
        recorder.flushOnce();
        assertThat(recorder.pendingCount()).isZero();
    }
}
