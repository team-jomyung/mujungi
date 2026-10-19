package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.log.LatencyRecorder.SpeechEndSource;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.voice.InputRelay;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 지연 측정 기록 시험 (U1 3.5 Step 29 · PD-6 · OD-8 · R3-4 · NFR5.1 · NFR5.3 · J143).
 *
 * <p>LATENCY_TURN 시각 키 8종 + SESSION_ITEMS_SERVED 2종, tSpeechEndSource 우선순위, HEARTBEAT 봉투 sentAt으로 계산한 clockOffsetMs가
 * 주입한 오프셋 ± rttMs/2 안, 오프셋이 없으면 CLOCK_OFFSET_MISSING, 프레임 단위 로그 0줄을 본다.
 */
@Tag("skeleton")
class LatencyRecorderTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final EventLogger events = TestEventLoggers.strict(clock, logs, metrics);
    private final LatencyRecorder recorder = new LatencyRecorder(events, metrics, clock, 100, Duration.ofMinutes(10));
    private final UUID session = UUID.randomUUID();

    @Test
    void aFullTurnLogsTheEightTimeKeysAndTheDerivedDurations() {
        recorder.register(session, "0123456789abcdef0123456789abcdef", LatencyRecorder.inputSide(() -> 12L, () -> null));
        UUID playback = UUID.randomUUID();
        recorder.inputActivity(session);
        Instant speechEnd = tick(100);
        recorder.speechEnd(session, SpeechEndSource.PROVIDER_EVENT, speechEnd);
        recorder.transcriptFinal(session, 1, tick(50));
        recorder.providerFirstOut(session, tick(300));
        long firstNanos = clock.monotonicNanos();
        tick(400);
        long lastNanos = clock.monotonicNanos();
        tick(800);
        recorder.segmentConfirmed(session, clock.now(), clock.monotonicNanos(), firstNanos, lastNanos);
        recorder.gatePassed(session, playback, tick(2));
        recorder.firstFrameSent(session, playback, tick(3));
        Instant recv = tick(80);
        recorder.playbackStarted(session, playback, recv.minusMillis(500), recv, -470L, 60L);

        Map<String, Object> turn = LogCapture.attrs(logs.last(EventCode.LATENCY_TURN).orElseThrow());
        assertThat(turn).containsKeys("tSpeechEnd", "tProviderFirstOut", "tSegmentConfirmed", "tGatePass",
                "tFirstFrameSent", "tDevicePlayStart", "tDevicePlayStartRecv", "tTranscriptFinal");
        assertThat(turn).containsEntry("tSpeechEndSource", "PROVIDER_EVENT").doesNotContainKey("unobservedReason");
        assertThat(number(turn, "relayInMs")).isEqualTo(12L);
        assertThat(number(turn, "relayOutMs")).isEqualTo(5L);
        assertThat(number(turn, "providerResponseMs")).isEqualTo(400L);
        assertThat(number(turn, "quietWaitMs")).isEqualTo(800L);
        assertThat(number(turn, "clockOffsetMs")).isEqualTo(-470L);
        assertThat(number(turn, "rttMs")).isEqualTo(60L);
    }

    @Test
    void theSpeechEndSourceFollowsPriorityAndFallsBackToTheLastVoiceFrame() {
        Instant lastVoice = clock.now().minusMillis(30);
        recorder.register(session, null, LatencyRecorder.inputSide(() -> null, () -> lastVoice));
        recorder.inputActivity(session);
        recorder.transcriptFinal(session, 1, tick(10));
        recorder.speechEnd(session, SpeechEndSource.LAST_INPUT_FRAME, tick(10));
        recorder.inputActivity(session);
        recorder.sessionEnded(session);
        assertThat(LogCapture.attrs(logs.last(EventCode.LATENCY_TURN).orElseThrow()))
                .containsEntry("tSpeechEndSource", "INPUT_TRANSCRIPT_FINAL").containsEntry("unobservedReason", "NO_OUTPUT");

        UUID other = UUID.randomUUID();
        recorder.register(other, null, LatencyRecorder.inputSide(() -> null, () -> lastVoice));
        recorder.inputActivity(other);
        recorder.providerFirstOut(other, tick(10));
        recorder.sessionEnded(other);
        assertThat(LogCapture.attrs(logs.last(EventCode.LATENCY_TURN).orElseThrow()))
                .containsEntry("tSpeechEndSource", "LAST_INPUT_FRAME")
                .containsEntry("unobservedReason", "SESSION_ENDED_EARLY");
    }

    @Test
    void aMissingClockOffsetIsReportedAsClockOffsetMissing() {
        recorder.register(session, null, null);
        UUID playback = UUID.randomUUID();
        recorder.inputActivity(session);
        recorder.providerFirstOut(session, tick(10));
        recorder.gatePassed(session, playback, tick(10));
        recorder.playbackStarted(session, UUID.randomUUID(), clock.now(), clock.now(), 5L, 5L);
        assertThat(logs.events(EventCode.LATENCY_TURN)).isEmpty();

        recorder.playbackStarted(session, playback, clock.now(), clock.now(), null, 40L);

        Map<String, Object> turn = LogCapture.attrs(logs.last(EventCode.LATENCY_TURN).orElseThrow());
        assertThat(turn).containsEntry("unobservedReason", "CLOCK_OFFSET_MISSING").doesNotContainKey("clockOffsetMs");
    }

    @Test
    void theHeartbeatOffsetLiesWithinHalfTheRoundTripOfTheInjectedSkew() {
        long injectedSkew = -1_234L;
        long rtt = 80L;
        Instant serverSend = clock.now();
        // 기기 시계 = 서버 시계 + skew. 기기가 보낸 시각을 rtt/2 뒤에 서버가 받는다
        Instant deviceSentAt = serverSend.plusMillis(injectedSkew);
        Instant serverReceivedAt = serverSend.plusMillis(rtt / 2);

        Long offset = LatencyRecorder.clockOffsetMs(deviceSentAt, serverReceivedAt, rtt);

        assertThat(offset).isBetween(injectedSkew - rtt / 2, injectedSkew + rtt / 2);
        assertThat(LatencyRecorder.clockOffsetMs(null, serverReceivedAt, rtt)).isNull();
        assertThat(LatencyRecorder.clockOffsetMs(deviceSentAt, serverReceivedAt, null))
                .isEqualTo(deviceSentAt.toEpochMilli() - serverReceivedAt.toEpochMilli());
    }

    @Test
    void itemsServedLogsEachRowOnceWithReadyAndFinalTimes() {
        recorder.register(session, null, null);
        recorder.transcriptFinal(session, 1, tick(5));
        recorder.itemReady(session, 1, Speaker.CHILD, tick(20));
        recorder.aiItem(session, 2);
        recorder.itemReady(session, 2, Speaker.AI, tick(20));

        recorder.itemsServed(session, List.of(new LatencyRecorder.ServedItem(1, Speaker.CHILD),
                new LatencyRecorder.ServedItem(2, Speaker.AI)), tick(1000));
        recorder.itemsServed(session, List.of(new LatencyRecorder.ServedItem(1, Speaker.CHILD)), tick(1000));

        assertThat(logs.events(EventCode.SESSION_ITEMS_SERVED)).hasSize(2);
        Map<String, Object> first = LogCapture.attrs(logs.events(EventCode.SESSION_ITEMS_SERVED).get(0));
        assertThat(first).containsKeys("tTranscriptFinal", "tViewItemReady", "tViewItemFirstServed")
                .containsEntry("speaker", "CHILD");
    }

    @Test
    void frameLevelRelayProducesNoLogLinesAndEndedSessionsArePurgedAfterRetention() {
        InputRelay relay = new InputRelay(session, 50, new ProviderRequestFactory(), clock, RealtimeMetrics.inMemory());
        recorder.register(session, null, LatencyRecorder.inputSide(relay::drainRelayInP95Millis, relay::lastVoiceFrameAt));
        relay.setAccepting(true);
        for (int seq = 1; seq <= 100; seq++) {
            relay.offer(seq, new byte[960], clock.monotonicNanos());
        }
        assertThat(logs.lines()).isEmpty();

        recorder.sessionEnded(session);
        assertThat(recorder.purgeEnded(clock.now().plus(Duration.ofMinutes(9)))).isZero();
        assertThat(recorder.purgeEnded(clock.now().plus(Duration.ofMinutes(10)))).isEqualTo(1);
        assertThat(recorder.trackedSessions()).isZero();
        // 등록되지 않은 세션의 기록은 무시한다
        recorder.inputActivity(UUID.randomUUID());
    }

    @Test
    void aZeroSampleRateSuppressesTurnLinesButNotServedLines() {
        LatencyRecorder silent = new LatencyRecorder(events, metrics, clock, 0, Duration.ofMinutes(10));
        silent.register(session, null, null);
        silent.inputActivity(session);
        silent.providerFirstOut(session, tick(1));
        silent.gateDiscarded(session);
        silent.itemsServed(session, List.of(new LatencyRecorder.ServedItem(3, Speaker.AI)), tick(1));

        assertThat(logs.events(EventCode.LATENCY_TURN)).isEmpty();
        assertThat(logs.events(EventCode.SESSION_ITEMS_SERVED)).hasSize(1);
    }

    // ------------------------------------------------------------------ 3.6 수정 J300 · J301: 지연 히스토그램

    @Test
    void aFullTurnFeedsTheRelayOutAndFirstAudioHistogramsWithServerClockValues() {
        recorder.register(session, null, null);
        UUID playback = UUID.randomUUID();
        recorder.inputActivity(session);
        Instant speechEnd = tick(100);
        recorder.speechEnd(session, SpeechEndSource.PROVIDER_EVENT, speechEnd);
        recorder.providerFirstOut(session, tick(300));
        recorder.segmentConfirmed(session, tick(800), clock.monotonicNanos(), 0L, 0L);
        recorder.gatePassed(session, playback, tick(2));
        recorder.firstFrameSent(session, playback, tick(3));
        Instant recv = tick(80);
        // 기기 시계 = 서버 시계 − 470ms: 기기가 recv − 500(기기 시계)에 재생을 시작했다 → 서버 시계 recv − 30
        recorder.playbackStarted(session, playback, recv.minusMillis(500), recv, -470L, 60L);

        assertThat(totalMillis(RealtimeMetrics.RELAY_OUT)).as("구간 확정 → 첫 프레임").isEqualTo(5L);
        assertThat(totalMillis(RealtimeMetrics.TURN_FIRST_AUDIO)).as("t0 → t3(서버 시계 환산)")
                .isEqualTo(Duration.between(speechEnd, recv.minusMillis(30)).toMillis());
        assertThat(metrics.recorded(RealtimeMetrics.RELAY_OUT)).isEqualTo(1);
        assertThat(metrics.recorded(RealtimeMetrics.TURN_FIRST_AUDIO)).isEqualTo(1);
    }

    @Test
    void unobservedTurnsDoNotInventTheMissingTimes() {
        recorder.register(session, null, null);
        // 게이트는 통과했지만 첫 프레임 · 시계 오프셋이 없는 턴(CLOCK_OFFSET_MISSING)
        UUID playback = UUID.randomUUID();
        recorder.inputActivity(session);
        recorder.speechEnd(session, SpeechEndSource.PROVIDER_EVENT, tick(10));
        recorder.providerFirstOut(session, tick(10));
        recorder.segmentConfirmed(session, tick(10), clock.monotonicNanos(), 0L, 0L);
        recorder.gatePassed(session, playback, tick(1));
        recorder.playbackStarted(session, playback, clock.now(), clock.now(), null, 40L);
        // 출력 없이 끝난 턴(NO_OUTPUT)
        recorder.inputActivity(session);
        recorder.speechEnd(session, SpeechEndSource.PROVIDER_EVENT, tick(10));
        recorder.sessionEnded(session);

        assertThat(logs.events(EventCode.LATENCY_TURN)).extracting(line -> LogCapture.attrs(line).get("unobservedReason"))
                .containsExactly("CLOCK_OFFSET_MISSING", "NO_OUTPUT");
        assertThat(metrics.recorded(RealtimeMetrics.RELAY_OUT)).as("tFirstFrameSent 없음").isZero();
        assertThat(metrics.recorded(RealtimeMetrics.TURN_FIRST_AUDIO)).as("clockOffsetMs 없음 · 첫 턴은 출력 없음").isZero();
    }

    @Test
    void viewItemReadyMeasuresFinalToReadyPerSpeakerOnceAndOnlyWithAKnownFinalTime() {
        recorder.register(session, null, null);
        recorder.transcriptFinal(session, 1, tick(5));
        recorder.itemReady(session, 1, Speaker.CHILD, tick(20));
        recorder.itemReady(session, 1, Speaker.CHILD, tick(500));
        recorder.aiItem(session, 2);
        recorder.aiTranscriptFinal(session, 2, tick(10));
        recorder.aiTranscriptFinal(session, 2, tick(100));
        recorder.itemReady(session, 2, Speaker.AI, tick(800));
        recorder.aiItem(session, 3);
        recorder.itemReady(session, 3, Speaker.AI, tick(10));

        assertThat(metrics.recorded(RealtimeMetrics.VIEW_ITEM_READY, "speaker", "child")).as("다시 커밋해도 한 번").isEqualTo(1);
        assertThat(totalMillis(RealtimeMetrics.VIEW_ITEM_READY, "speaker", "child")).isEqualTo(20L);
        assertThat(metrics.recorded(RealtimeMetrics.VIEW_ITEM_READY, "speaker", "ai"))
                .as("확정 시각(output.done)을 모르는 AI 행은 넣지 않는다").isEqualTo(1);
        assertThat(totalMillis(RealtimeMetrics.VIEW_ITEM_READY, "speaker", "ai")).as("처음 받은 확정 시각부터")
                .isEqualTo(900L);

        recorder.itemsServed(session, List.of(new LatencyRecorder.ServedItem(2, Speaker.AI)), tick(1));
        assertThat(LogCapture.attrs(logs.last(EventCode.SESSION_ITEMS_SERVED).orElseThrow()))
                .containsKeys("tTranscriptFinal", "tViewItemReady").containsEntry("speaker", "AI");
    }

    @Test
    void theHistogramsCountEveryTurnEvenWhenTheLogSampleRateIsZero() {
        LatencyRecorder silent = new LatencyRecorder(events, metrics, clock, 0, Duration.ofMinutes(10));
        silent.register(session, null, null);
        UUID playback = UUID.randomUUID();
        silent.inputActivity(session);
        silent.speechEnd(session, SpeechEndSource.PROVIDER_EVENT, tick(10));
        silent.providerFirstOut(session, tick(10));
        silent.segmentConfirmed(session, tick(10), clock.monotonicNanos(), 0L, 0L);
        silent.gatePassed(session, playback, tick(1));
        silent.firstFrameSent(session, playback, tick(7));
        silent.playbackStarted(session, playback, clock.now(), tick(5), 0L, 10L);

        assertThat(logs.events(EventCode.LATENCY_TURN)).as("로그는 표본률을 따른다").isEmpty();
        assertThat(metrics.recorded(RealtimeMetrics.RELAY_OUT)).isEqualTo(1);
        assertThat(metrics.recorded(RealtimeMetrics.TURN_FIRST_AUDIO)).isEqualTo(1);
    }

    private long totalMillis(String name, String... tags) {
        return Math.round(metrics.registry().find(name).tags(tags).timer().totalTime(TimeUnit.MILLISECONDS));
    }

    private Instant tick(long millis) {
        clock.advanceMillis(millis);
        return clock.now();
    }

    private static long number(Map<String, Object> attrs, String key) {
        return ((Number) attrs.get(key)).longValue();
    }
}
