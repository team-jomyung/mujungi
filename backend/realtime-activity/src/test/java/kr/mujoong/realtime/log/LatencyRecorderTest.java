package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.log.LatencyRecorder.SpeechEndSource;
import kr.mujoong.realtime.log.TranscriptEntry.Speaker;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
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
@Tag("u1")
class LatencyRecorderTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture logs = LogCapture.detached();
    private final EventLogger events = EventLogger.forTest(clock, logs, RealtimeMetrics.inMemory());
    private final LatencyRecorder recorder = new LatencyRecorder(events, clock, 100, Duration.ofMinutes(10));
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
        LatencyRecorder silent = new LatencyRecorder(events, clock, 0, Duration.ofMinutes(10));
        silent.register(session, null, null);
        silent.inputActivity(session);
        silent.providerFirstOut(session, tick(1));
        silent.gateDiscarded(session);
        silent.itemsServed(session, List.of(new LatencyRecorder.ServedItem(3, Speaker.AI)), tick(1));

        assertThat(logs.events(EventCode.LATENCY_TURN)).isEmpty();
        assertThat(logs.events(EventCode.SESSION_ITEMS_SERVED)).hasSize(1);
    }

    private Instant tick(long millis) {
        clock.advanceMillis(millis);
        return clock.now();
    }

    private static long number(Map<String, Object> attrs, String key) {
        return ((Number) attrs.get(key)).longValue();
    }
}
