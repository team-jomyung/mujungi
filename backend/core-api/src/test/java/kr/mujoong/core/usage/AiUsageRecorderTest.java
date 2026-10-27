package kr.mujoong.core.usage;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 전용 큐 · 저장 작업. DB 대신 저장 대역을 쓴다. 시간 값은 시험 값이다. */
@Tag("ai-usage")
class AiUsageRecorderTest {

    private static final AiUsageProperties.Timing TIMING =
            new AiUsageProperties.Timing(Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(1));

    private MutableClock clock;
    private MeterRegistry registry;
    private List<AiUsageRecord> stored;
    private RuntimeException failure;
    private AiUsageRecorder.Result result;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        registry = new SimpleMeterRegistry();
        stored = new ArrayList<>();
        failure = null;
        result = AiUsageRecorder.Result.STORED;
    }

    private AiUsageRecorder recorder(boolean enabled, int capacity, int batch) {
        AiUsageRecorder.Store store = record -> {
            if (failure != null) {
                throw failure;
            }
            stored.add(record);
            return result;
        };
        return new AiUsageRecorder(store, clock, new AiUsageProperties(enabled), TIMING,
                new AiUsageProperties.Limits(capacity, batch), registry);
    }

    private AiUsageRecord record(UUID guardian) {
        return new AiUsageRecord(UUID.randomUUID(), UUID.randomUUID(), 1, "RESULT_TEXT",
                guardian == null ? null : new AiUsageLink(guardian, UUID.randomUUID()), "v1", "gpt-6-luna", null, "OK",
                10L, 2L, null, null, null, AiUsageRecord.Status.COMPLETE, clock.now());
    }

    private double count(String outcome) {
        return registry.counter("mujoong.ai.usage.records", "outcome", outcome).count();
    }

    @Test
    void whenDisabledNothingIsQueuedAndTheCallerIsNotRejected() {
        AiUsageRecorder recorder = recorder(false, 10, 5);

        assertThat(recorder.offer(record(UUID.randomUUID()))).isTrue();
        assertThat(recorder.enabled()).isFalse();
        assertThat(recorder.pendingCount()).isZero();
    }

    @Test
    void queuedRecordsAreStoredInBatchesAndLeaveTheQueue() {
        AiUsageRecorder recorder = recorder(true, 10, 2);
        for (int i = 0; i < 3; i++) {
            assertThat(recorder.offer(record(UUID.randomUUID()))).isTrue();
        }

        recorder.flushOnce();
        assertThat(stored).hasSize(2);
        assertThat(recorder.pendingCount()).isEqualTo(1);
        recorder.flushOnce();

        assertThat(stored).hasSize(3);
        assertThat(recorder.pendingCount()).isZero();
        assertThat(count("stored")).isEqualTo(3);
    }

    @Test
    void aFullQueueRejectsTheNewRecordButKeepsTheOnesAlreadyQueued() {
        AiUsageRecorder recorder = recorder(true, 2, 1);
        recorder.offer(record(UUID.randomUUID()));
        recorder.offer(record(UUID.randomUUID()));

        assertThat(recorder.offer(record(UUID.randomUUID()))).isFalse();

        assertThat(recorder.pendingCount()).isEqualTo(2);
        assertThat(count("full")).isEqualTo(1);
    }

    @Test
    void theSameRecordOfferedTwiceIsQueuedOnce() {
        AiUsageRecorder recorder = recorder(true, 10, 10);
        AiUsageRecord record = record(UUID.randomUUID());

        recorder.offer(record);
        recorder.offer(record);
        recorder.flushOnce();

        assertThat(stored).containsExactly(record);
    }

    @Test
    void aFailedWriteIsRetriedOnTheNextTickAndExpiresAfterTheTtl() {
        AiUsageRecorder recorder = recorder(true, 10, 10);
        recorder.offer(record(UUID.randomUUID()));
        failure = new IllegalStateException("DB 불가");

        recorder.flushOnce();
        assertThat(recorder.pendingCount()).isEqualTo(1);
        assertThat(count("retry")).isEqualTo(1);

        failure = null;
        recorder.flushOnce();
        assertThat(stored).hasSize(1);

        recorder.offer(record(UUID.randomUUID()));
        failure = new IllegalStateException("DB 불가");
        clock.advance(TIMING.pendingTtl());
        recorder.flushOnce();

        assertThat(recorder.pendingCount()).isZero();
        assertThat(count("expired")).isEqualTo(1);
    }

    @Test
    void aRecordWhoseGuardianWasDeletedIsDroppedWithoutRetry() {
        AiUsageRecorder recorder = recorder(true, 10, 10);
        result = AiUsageRecorder.Result.DISCARDED;
        recorder.offer(record(UUID.randomUUID()));

        recorder.flushOnce();

        assertThat(recorder.pendingCount()).isZero();
        assertThat(count("discarded")).isEqualTo(1);
    }

    @Test
    void accountDeletionDropsOnlyThatGuardiansQueuedRecords() {
        AiUsageRecorder recorder = recorder(true, 10, 10);
        UUID deleted = UUID.randomUUID();
        AiUsageRecord other = record(UUID.randomUUID());
        AiUsageRecord unlinked = record(null);
        recorder.offer(record(deleted));
        recorder.offer(other);
        recorder.offer(unlinked);

        assertThat(recorder.discardGuardian(deleted, Duration.ZERO)).as("진행 중 저장이 없으면 바로 끝난다").isTrue();
        recorder.flushOnce();

        assertThat(stored).containsExactlyInAnyOrder(other, unlinked);
    }

    @Test
    void afterStopNewRecordsAreRejected() {
        AiUsageRecorder recorder = recorder(true, 10, 10);
        recorder.start();
        recorder.stop();

        assertThat(recorder.offer(record(UUID.randomUUID()))).isFalse();
        assertThat(count("stopped")).isEqualTo(1);
        assertThat(recorder.isRunning()).isFalse();
    }
}
