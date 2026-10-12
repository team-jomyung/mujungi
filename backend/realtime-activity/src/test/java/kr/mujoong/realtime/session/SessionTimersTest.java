package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.realtime.testing.FakeProviderPort;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 세션 시한 시험 (U1 3.5 Step 29 · BR6.12 ② ③ ④ · RD-1 · RD-3 · J59 (h) · J55 · NFR6.2 · NFR6.3).
 *
 * <p>경계 −1틱(1ms) · +1틱을 수동 타이머 구동기로 확인한다. 같은 시험을 운영 값(20 · 30 · 40초)과 시험 프로필처럼 줄인 값(2 · 3 · 4초)
 * 두 방식으로 돌린다. ④는 기기 메시지마다 다시 걸지 않고 만기 때 남은 시간으로 다시 건다. CLOSING 중에는 ④가 무동작이고 ③이 우선한다.
 */
@Tag("u1")
class SessionTimersTest {

    private static final Duration TICK = Duration.ofMillis(1);

    static Stream<Arguments> settings() {
        return Stream.of(
                Arguments.of("운영 값", SessionHarness.SETTINGS),
                Arguments.of("줄인 키", new SessionExecutor.Settings(Duration.ofSeconds(2), Duration.ofSeconds(3),
                        Duration.ofSeconds(4), Duration.ofMillis(50), Duration.ofSeconds(2), Duration.ofMillis(50), 50,
                        1000, 60)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void deviceAckTimeoutFiresExactlyAtTheThresholdWithDeviceNoAckAndUnresponsive(String label,
            SessionExecutor.Settings settings) {
        SessionHarness h = new SessionHarness(settings);
        h.boot();

        h.advance(settings.deviceAckTimeout().minus(TICK));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);
        h.advance(TICK);

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(h.unresponsive).hasSize(1);
        Map<String, Object> attrs = LogCapture.attrs(h.logs.last(EventCode.TIMEOUT_FIRED).orElseThrow());
        assertThat(attrs).containsEntry("type", "DEVICE_ACK");
        assertThat(((Number) attrs.get("thresholdMs")).longValue()).isEqualTo(settings.deviceAckTimeout().toMillis());
        assertThat(((Number) attrs.get("elapsedMs")).longValue()).isEqualTo(settings.deviceAckTimeout().toMillis());
    }

    @Test
    void ackAndReadyWithoutProviderStartedFailsWithProviderUnavailableAndNoUnresponsive() {
        SessionHarness h = new SessionHarness();
        h.provider.mode(FakeProviderPort.Mode.NO_STARTED);
        h.boot();
        h.ackAndReady();

        h.advance(Duration.ofSeconds(20));

        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode())
                .isEqualTo(FailureCode.PROVIDER_UNAVAILABLE);
        assertThat(h.unresponsive).isEmpty();
    }

    @Test
    void ackWithoutDeviceReadyIsStillDeviceNoAck() {
        SessionHarness h = new SessionHarness();
        h.boot();
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), h.startOpId(), h.clock.now()));
        h.drain();

        h.advance(Duration.ofSeconds(20));

        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
        assertThat(h.unresponsive).hasSize(1);
    }

    @Test
    void aSessionThatBecameActiveIgnoresTheDeviceAckClock() {
        SessionHarness h = new SessionHarness();
        h.activate();
        // 무장이 풀렸어도 늦게 도착한 만기는 세대 확인 · 상태 확인에서 버려진다
        h.mailbox.putSafety(new SessionMailbox.TimerFired(SessionTimers.Kind.DEVICE_ACK, 1));
        h.advance(Duration.ofSeconds(21));

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void closingConfirmTimeoutEndsUnconfirmedAtTheThreshold(String label, SessionExecutor.Settings settings) {
        SessionHarness h = new SessionHarness(settings);
        h.activate();
        stop(h);
        // 기기는 살아 있다(④ 영향 없이 ③만 본다)
        h.advance(settings.closingConfirm().minus(TICK));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        h.advance(TICK);

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
        assertThat(row.confirmed()).isFalse();
        assertThat(h.unresponsive).hasSize(1);
        assertThat(h.link.sent(SessionState.class)).extracting(SessionState::view).last().isEqualTo(View.STANDBY);
        assertThat(LogCapture.attrs(h.logs.last(EventCode.TIMEOUT_FIRED).orElseThrow()))
                .containsEntry("type", "CLOSING_CONFIRM");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void disconnectFiresFortySecondsAfterTheLastDeviceMessageWithoutRearmingPerMessage(String label,
            SessionExecutor.Settings settings) {
        SessionHarness h = new SessionHarness(settings);
        h.activate();
        Duration threshold = settings.disconnect();
        var deadline = h.executor.timers().deadline(SessionTimers.Kind.DISCONNECT);
        Duration half = threshold.dividedBy(2);

        h.advance(half);
        h.link.touch(h.clock.now());
        h.link.touch(h.clock.now().plusMillis(1));
        // 메시지마다 다시 걸지 않는다
        assertThat(h.executor.timers().deadline(SessionTimers.Kind.DISCONNECT)).isEqualTo(deadline);
        h.link.touch(h.clock.now());

        // 첫 만기에서는 남은 시간으로 다시 건다(지연 재무장)
        h.advance(half);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.advance(half.minus(TICK));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.advance(TICK);

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ABORTED);
        assertThat(LogCapture.attrs(h.logs.last(EventCode.TIMEOUT_FIRED).orElseThrow()))
                .containsEntry("type", "DISCONNECT");
        assertThat(h.provider.last().isOpen()).isFalse();
    }

    @Test
    void duringClosingTheDisconnectClockDoesNothingAndClosingConfirmWins() {
        SessionHarness h = new SessionHarness();
        h.activate();
        stop(h);
        h.link.setOpen(false);

        h.advance(Duration.ofSeconds(45));

        assertThat(h.transitions.applied()).noneMatch(a -> a.to() == SessionStatus.ABORTED);
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
    }

    @Test
    void armingAgainReplacesThePreviousReservationAndCancelledFiringsAreStale() {
        MutableClock clock = new MutableClock();
        ManualTimerDriver safety = new ManualTimerDriver(clock);
        ManualTimerDriver quiet = new ManualTimerDriver(clock);
        SessionMailbox mailbox = new SessionMailbox(10);
        SessionTimers timers = new SessionTimers(new TimerClocks(safety, quiet), mailbox::putSafety);

        long first = timers.arm(SessionTimers.Kind.DEVICE_ACK, Duration.ofSeconds(1));
        long second = timers.arm(SessionTimers.Kind.DEVICE_ACK, Duration.ofSeconds(2));
        long quietGeneration = timers.arm(SessionTimers.Kind.SEGMENT_QUIET, Duration.ofMillis(800));
        safety.advance(Duration.ofSeconds(2));
        quiet.runDue();

        assertThat(second).isGreaterThan(first);
        SessionMailbox.TimerFired fired = (SessionMailbox.TimerFired) mailbox.poll();
        assertThat(fired.generation()).isEqualTo(second);
        assertThat(timers.consume(fired)).isTrue();
        assertThat(timers.consume(fired)).isFalse();
        SessionMailbox.TimerFired quietFired = (SessionMailbox.TimerFired) mailbox.poll();
        assertThat(quietFired.kind()).isEqualTo(SessionTimers.Kind.SEGMENT_QUIET);
        assertThat(quietFired.generation()).isEqualTo(quietGeneration);
        assertThat(mailbox.poll()).isNull();

        timers.arm(SessionTimers.Kind.DISCONNECT, Duration.ofSeconds(1));
        timers.cancelAll();
        safety.advance(Duration.ofSeconds(5));
        assertThat(mailbox.poll()).isNull();
        assertThat(timers.isArmed(SessionTimers.Kind.DISCONNECT)).isFalse();
        assertThat(timers.isCurrent(SessionTimers.Kind.DISCONNECT, 1)).isFalse();
    }

    private static void stop(SessionHarness h) {
        CompletableFuture<SessionMailbox.EndOutcome> result = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, result));
        h.drain();
        assertThat(result).isCompletedWithValue(SessionMailbox.EndOutcome.CLOSING);
    }
}
