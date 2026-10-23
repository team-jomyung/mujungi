package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.testing.ManualLauncher;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.testing.TestEventLoggers;
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
@Tag("skeleton")
@org.junit.jupiter.api.Tag("service-guardrail")
class AsyncRecorderTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final EventLogger events = TestEventLoggers.strict(clock, logs, metrics);
    private final LatencyRecorder latency = new LatencyRecorder(events, metrics, clock, 100, Duration.ofMinutes(10));
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

    // ------------------------------------------------------------------ 3.6 수정 J298 · J300 · J305

    @Test
    void drainWaitsForTheInFlightFlushToSignalItsEndAndThenWritesWhatIsLeft() throws Exception {
        AsyncRecorder recorder = new AsyncRecorder(store, launcher, latency, events, metrics, clock, 200, 2);
        recorder.putTranscript(TranscriptEntry.partial(session, 1, Speaker.CHILD, "진행 중", clock.now()));
        recorder.tick();
        recorder.putTranscript(TranscriptEntry.partial(session, 2, Speaker.CHILD, "남은 칸", clock.now()));

        CompletableFuture<Integer> draining = CompletableFuture.supplyAsync(() -> recorder.drain(Duration.ofSeconds(30)));
        assertThat(draining).as("진행 중 플러시가 끝났다는 신호 전에는 쓰지 않는다").isNotDone();
        launcher.runAll("recorder-flush-");

        assertThat(draining.get(10, TimeUnit.SECONDS)).as("신호를 받고 바로 남은 칸을 쓴다(30초를 기다리지 않는다)").isZero();
        assertThat(store.transcripts).extracting(TranscriptEntry::sequenceNo).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void drainGivesUpOnAStuckFlushAtTheWaitAndReportsTheRowsLeft() {
        AsyncRecorder recorder = new AsyncRecorder(store, launcher, latency, events, metrics, clock, 200, 2);
        recorder.putTranscript(TranscriptEntry.partial(session, 1, Speaker.CHILD, "막힌 칸", clock.now()));
        recorder.tick();
        recorder.putTranscript(TranscriptEntry.partial(session, 2, Speaker.CHILD, "남은 칸", clock.now()));

        int left = recorder.drain(Duration.ofMillis(50));

        assertThat(left).as("끝나지 않은 플러시의 칸은 남은 수로 돌려준다").isEqualTo(2);
        assertThat(store.writes).isZero();
        assertThat(recorder.drain(Duration.ZERO)).as("기다릴 시간이 없으면 바로 돌아온다").isEqualTo(2);
    }

    @Test
    void heldRowsTotalSumsEverySessionAndOnlyApprovedFinalRowsBecomeReady() {
        AsyncRecorder held = new AsyncRecorder(store, launcher, latency, events, metrics, clock, 200, 2);
        UUID other = UUID.randomUUID();
        held.putTranscript(TranscriptEntry.partial(session, 1, Speaker.CHILD, "가", clock.now()));
        held.putTranscript(TranscriptEntry.partial(session, 2, Speaker.CHILD, "나", clock.now()));
        held.putTranscript(TranscriptEntry.partial(other, 1, Speaker.AI, "", clock.now()));
        assertThat(held.heldRowsTotal()).as("mujoong_queue_depth{queue=recorder}").isEqualTo(3);

        AsyncRecorder recorder = new AsyncRecorder(store, TaskLauncher.inline(), latency, events, metrics, clock, 200, 2);
        latency.register(session, null, null);
        recorder.putTranscript(TranscriptEntry.finalized(session, 1, Speaker.CHILD, "공룡", true, clock.now(), clock.now()));
        recorder.putTranscript(TranscriptEntry.finalized(session, 2, Speaker.AI, "폐기한 말", false, clock.now(),
                clock.now()));
        latency.itemsServed(session, java.util.List.of(new LatencyRecorder.ServedItem(1, Speaker.CHILD),
                new LatencyRecorder.ServedItem(2, Speaker.AI)), clock.now());

        java.util.List<java.util.Map<String, Object>> served = logs.events(EventCode.SESSION_ITEMS_SERVED);
        assertThat(LogCapture.attrs(served.get(0))).containsKey("tViewItemReady");
        assertThat(LogCapture.attrs(served.get(1))).as("승인 false로 닫힌 AI 행은 12에 실릴 수 있게 된 적이 없다")
                .doesNotContainKey("tViewItemReady");
        assertThat(recorder.heldRowsTotal()).isZero();
    }
}
