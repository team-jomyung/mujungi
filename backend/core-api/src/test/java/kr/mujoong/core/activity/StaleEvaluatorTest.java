package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.device.DeviceVerdict;
import kr.mujoong.core.device.DeviceVerdict.NetworkState;
import kr.mujoong.core.device.DeviceVerdict.ReasonCode;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 30초 확인 불가 판정 · 조회 1회 실패는 stale 아님 · 래치 1회 (RD-6 · NFR4.3 · BR6.15 · OD-10). */
@Tag("u1")
class StaleEvaluatorTest {

    private static final Instant NOW = Instant.parse("2001-03-01T03:00:00Z");
    private static final Duration STALE = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(NOW);
    private final TestEvents events = new TestEvents(clock);
    private final StaleEvaluator evaluator = new StaleEvaluator(STALE, events.metrics(), events.logger());

    @Test
    void boundaryIsExclusiveAtThirtySeconds() {
        assertThat(evaluator.isStale(NOW.minus(STALE), NOW)).isFalse();
        assertThat(evaluator.isStale(NOW.minus(STALE).minusMillis(1), NOW)).isTrue();
        assertThat(evaluator.threshold()).isEqualTo(STALE);
    }

    @Test
    void oneFailedViewIsNotStaleWhileLastObservationIsRecent() {
        Instant lastObserved = NOW.minusSeconds(2);

        assertThat(evaluator.isSessionStale(lastObserved, NOW.minusSeconds(60), false, NOW)).isFalse();
        assertThat(evaluator.isSessionStale(NOW.minusSeconds(31), NOW.minusSeconds(60), false, NOW)).isTrue();
    }

    @Test
    void neverObservedFallsBackToRequestedAtAndTerminalIsNeverStale() {
        assertThat(evaluator.isSessionStale(null, NOW.minusSeconds(10), false, NOW)).isFalse();
        assertThat(evaluator.isSessionStale(null, NOW.minusSeconds(31), false, NOW)).isTrue();
        assertThat(evaluator.isSessionStale(NOW.minusSeconds(600), NOW.minusSeconds(900), true, NOW)).isFalse();
        assertThat(evaluator.isSessionStale(null, null, false, NOW)).isFalse();
    }

    @Test
    void firstUnknownVerdictFiresOnceUntilFreshAgain() {
        UUID device = UUID.randomUUID();
        DeviceVerdict unknown = new DeviceVerdict(NetworkState.UNKNOWN, false, ReasonCode.DEVICE_STATUS_UNKNOWN);
        DeviceVerdict stable = new DeviceVerdict(NetworkState.STABLE, true, null);

        evaluator.observeDevice(device, unknown, NOW.minusSeconds(45), NOW);
        evaluator.observeDevice(device, unknown, NOW.minusSeconds(45), NOW);
        evaluator.observeDevice(device, stable, NOW, NOW);
        evaluator.observeDevice(device, unknown, NOW.minusSeconds(31), NOW);

        assertThat(events.metrics().count("mujoong.timeout.fired", "type", "device_status_stale")).isEqualTo(2.0);
        var fired = events.capture().events(EventCode.TIMEOUT_FIRED);
        assertThat(fired).hasSize(2);
        assertThat(LogCapture.attrs(fired.get(0))).containsEntry("type", "DEVICE_STATUS_STALE");
        assertThat(((Number) LogCapture.attrs(fired.get(0)).get("thresholdMs")).longValue()).isEqualTo(30_000L);
        assertThat(((Number) LogCapture.attrs(fired.get(0)).get("elapsedMs")).longValue()).isEqualTo(45_000L);
    }

    @Test
    void stableDevicesNeverFireAndNullInputsAreIgnored() {
        evaluator.observeDevice(UUID.randomUUID(), new DeviceVerdict(NetworkState.STABLE, true, null), NOW, NOW);
        evaluator.observeDevice(null, null, null, NOW);

        assertThat(events.capture().events(EventCode.TIMEOUT_FIRED)).isEmpty();
    }

    @Test
    void thresholdMustBePositive() {
        assertThatThrownBy(() -> new StaleEvaluator(Duration.ZERO, events.metrics(), events.logger()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaleEvaluator((Duration) null, events.metrics(), events.logger()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
