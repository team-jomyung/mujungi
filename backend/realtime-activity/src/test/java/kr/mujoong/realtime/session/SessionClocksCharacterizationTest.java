package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.session.SessionTimers.Kind;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 시계 구역의 다른 시험이 비껴가는 가지를 지금 동작 그대로 고정한다: 시한보다 일찍 온 만기의 지연 재무장(시작 확인 · 닫기 확인),
 * 판정 준비 만기의 전달, 재생 상한 만기의 종료 확인 재생 처리. 공급자와 판정기는 가짜, 시각은 수동 시계다.
 */
@Tag("skeleton")
class SessionClocksCharacterizationTest {

    private static final Duration TICK = Duration.ofMillis(1);

    @Test
    void aDeviceAckExpiryThatArrivesEarlyRearmsWithTheRemainingTimeAndFailsAtTheThreshold() {
        SessionHarness h = new SessionHarness();
        h.boot();
        Duration threshold = h.settings.deviceAckTimeout();
        h.advance(Duration.ofSeconds(5));
        long before = currentGeneration(h, Kind.DEVICE_ACK);

        h.mailbox.putSafety(new SessionMailbox.TimerFired(Kind.DEVICE_ACK, before));
        h.drain();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(h.executor.timers().isArmed(Kind.DEVICE_ACK)).isTrue();
        assertThat(h.executor.timers().isCurrent(Kind.DEVICE_ACK, before)).isFalse();
        assertThat(h.executor.timers().deadline(Kind.DEVICE_ACK))
                .isEqualTo(h.clock.now().plus(threshold.minus(Duration.ofSeconds(5))));

        h.advance(threshold.minus(Duration.ofSeconds(5)).minus(TICK));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.PREPARING);
        h.advance(TICK);

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().failureCode()).isEqualTo(FailureCode.DEVICE_NO_ACK);
    }

    @Test
    void aClosingConfirmExpiryThatArrivesEarlyRearmsWithTheRemainingTimeAndEndsUnconfirmedAtTheThreshold() {
        SessionHarness h = new SessionHarness();
        h.activate();
        stop(h);
        Duration threshold = h.settings.closingConfirm();
        h.advance(Duration.ofSeconds(10));
        long before = currentGeneration(h, Kind.CLOSING_CONFIRM);

        h.mailbox.putSafety(new SessionMailbox.TimerFired(Kind.CLOSING_CONFIRM, before));
        h.drain();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.executor.timers().isCurrent(Kind.CLOSING_CONFIRM, before)).isFalse();
        assertThat(h.executor.timers().deadline(Kind.CLOSING_CONFIRM))
                .isEqualTo(h.clock.now().plus(threshold.minus(Duration.ofSeconds(10))));

        h.advance(threshold.minus(Duration.ofSeconds(10)).minus(TICK));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        h.advance(TICK);

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
    }

    @Test
    void aJudgmentReadyExpiryIsHandedToTheOpenTurnWhichAbstainsWithResponseNotReady() {
        JudgmentExecutorSupport t = new JudgmentExecutorSupport();
        t.openStopJudgment();
        long generation = currentGeneration(t.h, Kind.JUDGMENT_READY);

        t.h.mailbox.putSafety(new SessionMailbox.TimerFired(Kind.JUDGMENT_READY, generation));
        t.h.drain();

        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.ABSTAINED);
        assertThat(record.abstainReason()).isEqualTo(AbstainReason.RESPONSE_NOT_READY);
        assertThat(t.h.executor.timers().isArmed(Kind.JUDGMENT_READY)).isFalse();
    }

    @Test
    void aPlaybackExpiryWhileTheStopQuestionIsPlayingOpensTheConfirmWindowAsIfItHadEnded() {
        SessionHarness h = new SessionHarness();
        h.extraApproved.put(SessionExecutor.STOP_CONFIRM, "이야기를 그만할까?");
        h.activate();
        h.childSays("i-1", "쉬고 싶어");
        assertThat(h.executor.timers().isArmed(Kind.PLAYBACK_EXPIRY)).isTrue();
        assertThat(h.executor.timers().isArmed(Kind.STOP_CONFIRM)).as("창은 재생 완료부터").isFalse();

        h.advanceAlive(h.settings.playbackExpiry().minus(TICK));
        assertThat(h.executor.timers().isArmed(Kind.STOP_CONFIRM)).isFalse();
        h.advanceAlive(TICK);

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.timers().isArmed(Kind.PLAYBACK_EXPIRY)).isFalse();
        assertThat(h.executor.timers().isArmed(Kind.STOP_CONFIRM)).isTrue();
    }

    private static long currentGeneration(SessionHarness h, Kind kind) {
        for (long generation = 1; generation < 100; generation++) {
            if (h.executor.timers().isCurrent(kind, generation)) {
                return generation;
            }
        }
        throw new AssertionError(kind + " 시계가 무장돼 있지 않다");
    }

    private static void stop(SessionHarness h) {
        CompletableFuture<SessionMailbox.EndOutcome> result = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, result));
        h.drain();
        assertThat(result).isCompletedWithValue(SessionMailbox.EndOutcome.CLOSING);
    }
}
