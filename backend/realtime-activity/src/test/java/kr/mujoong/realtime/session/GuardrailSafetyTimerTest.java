package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import kr.mujoong.realtime.guardrail.GuardrailEventRecord;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 검사 안전 타이머 (U13 BR3.4 보강): 포트 규약을 어겨 결과가 검사 상한의 두 배가 되도록 오지 않는 클라이언트가 협력 객체를 검사 중에 영영 가두지
 * 못한다. 정상 결과가 오면 타이머는 꺼지고, 늦게 도착하는 결과는 버려진다. 제브는 시험이 손으로 푸는 대역이다(R-F1 · R-M11).
 */
@Tag("jev-guardrail")
@org.junit.jupiter.api.Tag("service-guardrail")
class GuardrailSafetyTimerTest {

    private static final byte[] AUDIO = new byte[4800];
    private static final String CLEAN = "공룡을 좋아하는구나";

    private final SessionHarness h = new SessionHarness();
    private final FakeGuardrailClient guard = h.useGuardrail();

    @Test
    void aCheckThatNeverCompletesIsClosedAsATimeoutAfterTwiceTheBudgetAndTheDeviceIsReleased() {
        h.activate();
        say(CLEAN);
        assertThat(h.executor.guardrail().state()).isEqualTo(GuardrailCoordinator.State.CHECKING);

        h.advance(Duration.ofMillis(1500));
        assertThat(h.executor.guardrail().state()).as("상한의 두 배(1.6초) 전에는 그대로").isEqualTo(GuardrailCoordinator.State.CHECKING);
        h.advance(Duration.ofMillis(200));

        assertThat(h.executor.guardrail().state()).isNotEqualTo(GuardrailCoordinator.State.CHECKING);
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.guardrail.timeout", "mode", "real")).isEqualTo(1.0);
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "guardrail_timeout")).isEqualTo(1.0);
        assertThat(records()).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(GuardrailEventRecord.Decision.TIMEOUT);
            assertThat(event.latencyMs()).isEqualTo(800);
        });
    }

    @Test
    void aResultThatArrivesAfterTheSafetyTimerIsDroppedAndNeverPlays() {
        h.activate();
        say(CLEAN);
        FakeGuardrailClient.Call call = guard.last();
        h.advance(Duration.ofMillis(1700));

        guard.allow(call);
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.executor.guardrail().state()).isNotEqualTo(GuardrailCoordinator.State.CHECKING);
    }

    @Test
    void aNormalResultTurnsTheSafetyTimerOffSoNothingFiresLater() {
        h.activate();
        say(CLEAN);
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);

        h.advanceAlive(Duration.ofSeconds(5));

        assertThat(h.metrics.count("mujoong.guardrail.timeout", "mode", "real")).isZero();
        assertThat(h.link.outputs()).hasSize(1);
    }

    @Test
    void aWaitingSegmentIsReleasedWhenTheSafetyTimerClosesTheCheckBeforeIt() {
        h.activate();
        say(CLEAN);
        say("티라노도 좋아하니");
        assertThat(h.executor.guardrail().hasQueued()).isTrue();

        h.advance(Duration.ofMillis(900));

        assertThat(h.executor.guardrail().hasQueued()).isFalse();
        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.link.outputs()).isEmpty();
    }

    private void say(String text) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        h.advance(Duration.ofMillis(801));
    }

    private List<GuardrailEventRecord> records() {
        h.guardrailRecorder.tick();
        return List.copyOf(h.store.guardrails);
    }
}
