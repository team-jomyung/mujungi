package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.guardrail.GuardrailSupport;
import kr.mujoong.realtime.guardrail.GuardrailVerdict;
import kr.mujoong.realtime.guardrail.StubGuardrailClient;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.log.LatencyRecorder.SpeechEndSource;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 제브 계측 (U13 BR8.5): 출처별 호출 수 · 지연 히스토그램 · 차단 수 · 시간 초과 수 · 체감 대기 다섯 항목이 {@code mujoong.guardrail.*}로 나가고, 대역(STUB)
 * 지표가 실검사(REAL)와 태그로 구분된다. 제브 · 공급자는 모두 대역이다(실제 호출 없음, R-F1 · R-M11).
 */
@Tag("jev-guardrail")
@org.junit.jupiter.api.Tag("service-guardrail")
class GuardrailMetricsTest {

    private static final byte[] AUDIO = new byte[4800];
    private static final String CLEAN = "공룡을 좋아하는구나";

    private final SessionHarness h = new SessionHarness();
    private final FakeGuardrailClient guard = h.useGuardrail();

    @Test
    void everyGuardrailMeterNameIsRegisteredAtZeroForBothRealAndStubBeforeAnyCheck() {
        RealtimeMetrics metrics = RealtimeMetrics.inMemory();

        for (String mode : new String[] {"real", "stub"}) {
            for (String source : new String[] {"new_utterance", "approved_verbatim_skipped"}) {
                assertThat(metrics.registry().find(RealtimeMetrics.GUARDRAIL_CALLS).tags("mode", mode, "source", source).counter())
                        .isNotNull();
            }
            for (String decision : new String[] {"block", "review", "error"}) {
                assertThat(metrics.registry().find(RealtimeMetrics.GUARDRAIL_BLOCKED).tags("mode", mode, "decision", decision)
                        .counter()).isNotNull();
            }
            assertThat(metrics.registry().find(RealtimeMetrics.GUARDRAIL_TIMEOUT).tags("mode", mode).counter()).isNotNull();
            assertThat(metrics.registry().find(RealtimeMetrics.GUARDRAIL_LATENCY).tags("mode", mode).timer()).isNotNull();
            assertThat(metrics.registry().find(RealtimeMetrics.GUARDRAIL_PERCEIVED_WAIT).tags("mode", mode).timer())
                    .isNotNull();
        }
        assertThat(metrics.count(RealtimeMetrics.GUARDRAIL_CALLS, "mode", "real", "source", "new_utterance")).isZero();
        assertThat(metrics.recorded(RealtimeMetrics.GUARDRAIL_LATENCY, "mode", "real")).isZero();
    }

    @Test
    void anAllowCountsOneNewUtteranceCallAndRecordsTheLatencyWithoutCountingABlock() {
        h.activate();
        say(CLEAN);

        guard.allow();
        h.drain();

        assertThat(h.metrics.count("mujoong.guardrail.calls", "mode", "real", "source", "new_utterance")).isEqualTo(1.0);
        assertThat(h.metrics.recorded("mujoong.guardrail.latency", "mode", "real")).isEqualTo(1);
        Timer latency = h.metrics.registry().find("mujoong.guardrail.latency").tags("mode", "real").timer();
        assertThat(latency.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(310.0);
        assertThat(blocked("block") + blocked("review") + blocked("error")).isZero();
        assertThat(h.metrics.count("mujoong.guardrail.timeout", "mode", "real")).isZero();
    }

    @Test
    void aBlockAndAReviewAreCountedAsBlocksWithTheirOwnDecisionTag() {
        h.activate();
        say(CLEAN);
        guard.block();
        h.drain();
        h.settleSend();
        assertThat(blocked("block")).isEqualTo(1.0);

        SessionHarness other = new SessionHarness();
        FakeGuardrailClient otherGuard = other.useGuardrail();
        other.activate();
        sayTo(other, CLEAN);
        otherGuard.review();
        other.drain();

        assertThat(other.metrics.count("mujoong.guardrail.blocked", "mode", "real", "decision", "review")).isEqualTo(1.0);
        assertThat(other.metrics.count("mujoong.guardrail.blocked", "mode", "real", "decision", "block")).isZero();
    }

    @Test
    void aFailedCheckIsAnErrorBlockAndATimeoutHasItsOwnCounterAndNoBlockCount() {
        h.activate();
        say(CLEAN);
        guard.fail(GuardrailVerdict.Failure.TRANSPORT_ERROR);
        h.drain();
        assertThat(blocked("error")).isEqualTo(1.0);
        assertThat(h.metrics.count("mujoong.guardrail.timeout", "mode", "real")).isZero();

        SessionHarness other = new SessionHarness();
        FakeGuardrailClient otherGuard = other.useGuardrail();
        other.activate();
        sayTo(other, CLEAN);
        otherGuard.fail(GuardrailVerdict.Failure.TIMEOUT);
        other.drain();

        assertThat(other.metrics.count("mujoong.guardrail.timeout", "mode", "real")).isEqualTo(1.0);
        assertThat(other.metrics.count("mujoong.guardrail.blocked", "mode", "real", "decision", "error")).isZero();
    }

    @Test
    void theStubModeIsSeparatedFromTheRealModeByTheModeTag() {
        SessionHarness stub = new SessionHarness();
        stub.guardrail = new GuardrailSupport(GuardrailMode.STUB, new StubGuardrailClient(), stub.guardrailRecorder,
                Duration.ofMillis(800), FakeGuardrailClient.POLICY);
        stub.activate();

        sayTo(stub, CLEAN);

        assertThat(stub.metrics.count("mujoong.guardrail.calls", "mode", "stub", "source", "new_utterance")).isEqualTo(1.0);
        assertThat(stub.metrics.count("mujoong.guardrail.calls", "mode", "real", "source", "new_utterance")).isZero();
        assertThat(stub.link.outputs()).hasSize(1);
    }

    @Test
    void aTurnThatWentThroughTheCheckAddsItsPerceivedWaitAndAnUncheckedTurnDoesNot() {
        MutableClock clock = new MutableClock();
        LogCapture logs = LogCapture.detached();
        RealtimeMetrics metrics = RealtimeMetrics.inMemory();
        EventLogger events = TestEventLoggers.strict(clock, logs, metrics);
        LatencyRecorder recorder = new LatencyRecorder(events, metrics, clock, 100, Duration.ofMinutes(10));
        UUID checked = UUID.randomUUID();
        UUID plain = UUID.randomUUID();

        turn(recorder, clock, checked, true);
        turn(recorder, clock, plain, false);

        assertThat(metrics.recorded("mujoong.turn.first.audio")).isEqualTo(2);
        assertThat(metrics.recorded("mujoong.guardrail.perceived.wait", "mode", "real")).isEqualTo(1);
        Timer wait = metrics.registry().find("mujoong.guardrail.perceived.wait").tags("mode", "real").timer();
        Timer total = metrics.registry().find("mujoong.turn.first.audio").timer();
        assertThat(wait.totalTime(TimeUnit.MILLISECONDS)).as("검사를 거친 턴의 말 끝 → 첫 소리(900ms)").isEqualTo(900.0);
        assertThat(total.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1800.0);
    }

    private void turn(LatencyRecorder recorder, MutableClock clock, UUID session, boolean checked) {
        recorder.register(session, null, null);
        UUID playback = UUID.randomUUID();
        recorder.inputActivity(session);
        Instant speechEnd = clock.now().plusMillis(100);
        clock.advance(Duration.ofMillis(100));
        recorder.speechEnd(session, SpeechEndSource.PROVIDER_EVENT, speechEnd);
        recorder.providerFirstOut(session, clock.now());
        if (checked) {
            recorder.guardrailChecked(session, "REAL");
        }
        recorder.gatePassed(session, playback, clock.now());
        clock.advance(Duration.ofMillis(900));
        Instant played = clock.now();
        recorder.playbackStarted(session, playback, played, played, 0L, 10L);
    }

    private double blocked(String decision) {
        return h.metrics.count("mujoong.guardrail.blocked", "mode", "real", "decision", decision);
    }

    private void say(String text) {
        sayTo(h, text);
    }

    private static void sayTo(SessionHarness target, String text) {
        target.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text));
        target.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        target.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        target.drain();
        target.advance(Duration.ofMillis(801));
    }
}
