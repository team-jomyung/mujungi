package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.testing.ManualLauncher;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 비동기 기록기 시험 (U1 3.5 Step 29 · PD-5 2.4 · BR7.5 · BR6.15 · J125).
 *
 * <p>칸 200행에서 새 행을 받지 않고 지연 상태가 되며 100행 아래로 줄면 풀린다. FINAL은 즉시 플러시, 확정 전 조각은 제자리 덮어쓰기(칸 수
 * 불변)로 틱을 기다린다. 커밋이 실패하면 칸을 되돌려 다음 틱에 다시 쓴다. 기기 last_seen은 기기당 마지막 값만 쓴다.
 */
@Tag("u1")
class AsyncRecorderTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final EventLogger events = EventLogger.forTest(clock, logs, metrics);
    private final LatencyRecorder latency = new LatencyRecorder(events, clock, 100, Duration.ofMinutes(10));
    private final SessionHarness.MemoryStore store = new SessionHarness.MemoryStore();
    private final ManualLauncher launcher = new ManualLauncher();
    private final UUID session = UUID.randomUUID();

    @Test
    void reachingTwoHundredRowsDegradesTheSessionUntilItDropsBelowOneHundred() {
        AsyncRecorder recorder = new AsyncRecorder(store, launcher, latency, events, metrics, clock, 200, 2);
        for (int seq = 1; seq <= 200; seq++) {
            assertThat(recorder.putTranscript(TranscriptEntry.partial(session, seq, Speaker.CHILD, "조각", clock.now())))
                    .isTrue();
        }
        assertThat(recorder.isDegraded(session)).isFalse();

        assertThat(recorder.putTranscript(TranscriptEntry.partial(session, 201, Speaker.AI, "", clock.now()))).isFalse();
        assertThat(recorder.isDegraded(session)).isTrue();
        assertThat(metrics.count("mujoong.recorder.rejected")).isEqualTo(1.0);
        assertThat(logs.last(EventCode.QUEUE_PRESSURE)).isPresent();
        // 같은 번호의 덮어쓰기는 칸을 늘리지 않으므로 받는다
        assertThat(recorder.putTranscript(TranscriptEntry.partial(session, 200, Speaker.CHILD, "조각2", clock.now())))
                .isTrue();

        recorder.tick();
        launcher.runAll("recorder-flush-");

        assertThat(recorder.heldRows(session)).isZero();
        assertThat(recorder.isDegraded(session)).isFalse();
        assertThat(store.transcripts).hasSize(200);
    }

    @Test
    void theDegradedStateHoldsWhileTheHeldRowsStayAtOrAboveHalf() {
        AsyncRecorder recorder = new AsyncRecorder(store, launcher, latency, events, metrics, clock, 4, 1);
        for (int seq = 1; seq <= 4; seq++) {
            recorder.putTranscript(TranscriptEntry.partial(session, seq, Speaker.CHILD, "a", clock.now()));
        }
        recorder.putTranscript(TranscriptEntry.partial(session, 5, Speaker.CHILD, "a", clock.now()));
        assertThat(recorder.isDegraded(session)).isTrue();
        store.failuresLeft = 1;

        recorder.tick();
        launcher.runAll("recorder-flush-");

        // 커밋 실패: 칸이 되돌아와 여전히 4행(절반 2 이상) → 지연 유지
        assertThat(recorder.heldRows(session)).isEqualTo(4);
        assertThat(recorder.isDegraded(session)).isTrue();
        assertThat(logs.last(EventCode.APP_ERROR)).isPresent();

        recorder.tick();
        launcher.runAll("recorder-flush-");
        assertThat(recorder.isDegraded(session)).isFalse();
    }

    @Test
    void aFinalRowIsFlushedImmediatelyAndPartialRowsWaitForTheTick() {
        AsyncRecorder recorder = new AsyncRecorder(store, TaskLauncher.inline(), latency, events, metrics, clock, 200, 2);
        latency.register(session, null, null);
        recorder.putTranscript(TranscriptEntry.partial(session, 1, Speaker.CHILD, "공", clock.now()));
        recorder.putTranscript(TranscriptEntry.partial(session, 1, Speaker.CHILD, "공룡", clock.now()));
        assertThat(store.transcripts).isEmpty();
        assertThat(recorder.heldRows(session)).isEqualTo(1);

        recorder.putTranscript(TranscriptEntry.finalized(session, 1, Speaker.CHILD, "공룡 좋아", true, clock.now(),
                clock.now()));

        assertThat(store.transcripts).singleElement().satisfies(row -> {
            assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
            assertThat(row.approvedForDisplay()).isTrue();
            assertThat(row.text()).isEqualTo("공룡 좋아");
        });
        assertThat(store.writes).isEqualTo(1);

        recorder.putTranscript(TranscriptEntry.partial(session, 2, Speaker.AI, "", clock.now()));
        assertThat(store.writes).isEqualTo(1);
        recorder.tick();
        assertThat(store.writes).isEqualTo(2);
    }

    @Test
    void rowsKeepNumberOrderAndEventsAreWrittenInTheSameFlush() {
        AsyncRecorder recorder = new AsyncRecorder(store, TaskLauncher.inline(), latency, events, metrics, clock, 200, 2);
        recorder.putEvent(new SessionEvent(UUID.randomUUID(), session, "END_REQUESTED", "req", null, clock.now()));
        recorder.putTranscript(TranscriptEntry.partial(session, 1, Speaker.AI, "", clock.now()));
        recorder.putTranscript(TranscriptEntry.partial(session, 2, Speaker.CHILD, "응", clock.now()));
        recorder.putTranscript(TranscriptEntry.finalized(session, 1, Speaker.AI, "안녕", false, clock.now(), clock.now()));

        assertThat(store.transcripts).extracting(TranscriptEntry::sequenceNo).containsExactly(1, 2);
        assertThat(store.sessionEvents).extracting(SessionEvent::getEventType).containsExactly("END_REQUESTED");
        assertThat(recorder.heldRows(session)).isZero();
    }

    @Test
    void lastSeenKeepsOnlyTheLatestValuePerDeviceAndDrainWritesEverythingLeft() {
        AsyncRecorder recorder = new AsyncRecorder(store, launcher, latency, events, metrics, clock, 200, 2);
        UUID device = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        recorder.deviceSeen(new DeviceConnectionRepository.LastSeen(device, connection, clock.now(), 30));
        clock.advanceMillis(500);
        recorder.deviceSeen(new DeviceConnectionRepository.LastSeen(device, connection, clock.now(), 40));
        recorder.deviceSeen(new DeviceConnectionRepository.LastSeen(device, connection, clock.now().minusSeconds(5), 50));
        recorder.putTranscript(TranscriptEntry.partial(session, 1, Speaker.CHILD, "남은 칸", clock.now()));

        int left = recorder.drain(Duration.ofSeconds(1));

        assertThat(left).isZero();
        assertThat(store.lastSeen).singleElement().satisfies(seen -> {
            assertThat(seen.seenAt()).isEqualTo(clock.now());
            assertThat(seen.rttMs()).isEqualTo(40);
        });
        assertThat(store.transcripts).hasSize(1);
    }
}
