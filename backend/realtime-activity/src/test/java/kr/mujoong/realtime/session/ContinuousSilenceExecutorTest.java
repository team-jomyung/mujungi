package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Test;

class ContinuousSilenceExecutorTest {
    @Test
    void aRealStreamShapeSealsSpeechAndRequiresJevAllowEvenWhileSilenceKeepsArriving() {
        SessionHarness h = new SessionHarness();
        var guard = h.useGuardrail();
        h.activate();
        h.speak("공룡을 좋아하는구나", 4800);
        for (int i = 0; i < 8; i++) {
            h.provider.last().emit(new ProviderEvent.OutputSilenceDelta(4800));
            h.drain();
            h.advance(Duration.ofMillis(100));
        }
        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.link.outputs()).isEmpty();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.link.outputs().getFirst().totalMs()).isEqualTo(100);
        for (int i = 0; i < 250; i++) {
            h.provider.last().emit(new ProviderEvent.OutputSilenceDelta(4800));
            h.drain();
            h.advance(Duration.ofMillis(100));
        }
        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "segment_oversize")).isZero();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_empty")).isZero();
    }

    @Test
    void aForbiddenTranscriptWithTheSameContinuousSilenceNeverReachesJevOrTheDevice() {
        SessionHarness h = new SessionHarness();
        var guard = h.useGuardrail();
        h.activate();
        h.speak("검사해 보니 비정상이에요", 4800);
        for (int i = 0; i < 8; i++) {
            h.provider.last().emit(new ProviderEvent.OutputSilenceDelta(4800));
            h.drain();
            h.advance(Duration.ofMillis(100));
        }
        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isEqualTo(1);
    }
}
