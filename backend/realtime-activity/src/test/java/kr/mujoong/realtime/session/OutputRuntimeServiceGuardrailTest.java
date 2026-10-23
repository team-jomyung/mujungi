package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class OutputRuntimeServiceGuardrailTest {
    private SessionHarness active() {
        var h = ServiceGuardrailFixtures.harness();
        h.boot(); h.ackAndReady(); mark(h, PlaybackMark.Status.COMPLETED); h.join(); h.settleSend();
        return h;
    }
    private void mark(SessionHarness h, PlaybackMark.Status status) {
        var play = h.link.sent(PlayApprovedContent.class).getLast();
        h.mailbox.putSafety(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }
    private SessionGuardrailFacts facts(SessionHarness h) { return h.transitions.guardrailFacts.get(h.sessionId); }
    @Test void closingIsPendingUntilTheCurrentApprovedGoodbyeStarts() {
        var h = active(); h.childSays("stop", "무중아 그만할래");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(facts(h).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.PENDING);
        assertThat(facts(h).confirmedStopBubbleSeq()).isPositive();
        mark(h, PlaybackMark.Status.STARTED);
        assertThat(facts(h).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.PLAYING);
    }
    @Test void completionClearsPlaying() {
        var h = active(); h.childSays("stop", "무중아 그만할래"); mark(h, PlaybackMark.Status.STARTED); mark(h, PlaybackMark.Status.COMPLETED);
        assertThat(facts(h).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.COMPLETED);
        int stopSeq = facts(h).confirmedStopBubbleSeq();
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new kr.mujoong.contract.ws.message.VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(facts(h).statusVersion()).isEqualTo(h.transitions.find(h.sessionId).orElseThrow().statusVersion());
        assertThat(facts(h).confirmedStopBubbleSeq()).isEqualTo(stopSeq);
        assertThat(facts(h).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.COMPLETED);
    }
    @Test void failedAndStoppedGoodbyesHaveDistinctFacts() {
        var failed = active(); failed.childSays("stop", "무중아 그만할래"); mark(failed, PlaybackMark.Status.FAILED);
        assertThat(facts(failed).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.FAILED);
        var stopped = active(); stopped.childSays("stop", "무중아 그만할래"); mark(stopped, PlaybackMark.Status.STOPPED);
        assertThat(facts(stopped).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.STOPPED);
    }
    @Test void expiryCannotRestorePlayingFromALateStartedMark() {
        var h = active(); h.childSays("stop", "무중아 그만할래"); mark(h, PlaybackMark.Status.STARTED);
        h.advanceAlive(h.settings.playbackExpiry()); mark(h, PlaybackMark.Status.STARTED);
        assertThat(facts(h).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.UNKNOWN);
        h.advanceAlive(h.settings.closingConfirm().minus(h.settings.playbackExpiry()));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
        assertThat(facts(h).statusVersion()).isEqualTo(h.transitions.find(h.sessionId).orElseThrow().statusVersion());
        assertThat(facts(h).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.UNKNOWN);
    }
    @Test void immediateMuteSendsNoAdditionalApprovedSpeechAndDoesNotEndTheSession() {
        var h = active(); int count = h.link.sent(PlayApprovedContent.class).size();
        h.childSays("mute", "지금 말하지 마");
        // 즉시 멈춤은 지금 말만 멈춘다. 세션은 등록 종료 표현으로만 끝난다(FT-1023-a)
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.drain();
        assertThat(h.store.sessionEvents.stream().map(kr.mujoong.realtime.session.SessionEvent::getEventType).toList())
                .doesNotContain(SessionExecutor.EVENT_CHILD_STOP_INTENT);
        assertThat(h.link.sent(PlayApprovedContent.class)).hasSize(count);
        assertThat(facts(h).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.NONE);
    }
    @Test void allowThenRestRevokesAlreadyQueuedFrames() {
        var h = ServiceGuardrailFixtures.harness(); var guard = h.useGuardrail();
        h.boot(); h.ackAndReady(); mark(h, PlaybackMark.Status.COMPLETED); h.join(); h.settleSend();
        h.speak("공룡을 좋아하는구나", 4800); h.advanceAlive(Duration.ofSeconds(1));
        assertThat(h.link.outputs()).isEmpty(); guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        var queued = h.link.outputs().getFirst();
        h.childSays("rest", "잠깐 쉴게");
        assertThat(h.link.deliver(queued, h.clock.now())).isZero();
    }
    @Test void aLateAllowAfterStopSendsNothing() {
        var h = ServiceGuardrailFixtures.harness(); var guard = h.useGuardrail();
        h.boot(); h.ackAndReady(); mark(h, PlaybackMark.Status.COMPLETED); h.join(); h.settleSend();
        h.speak("공룡을 좋아하는구나", 4800); h.advanceAlive(Duration.ofSeconds(1));
        h.childSays("stop", "무중아 그만할래"); guard.allow(); h.drain();
        assertThat(h.link.outputs()).isEmpty();
    }
    @Test void anUnverifiedGoodbyeIsNeverPlayedAsSpokenContent() {
        var h = active();
        // 안내는 검증 완료했지만 세션에 고정된 CLOSING 본문은 등록 해시와 불일치한다.
        var absent = ServiceGuardrailFixtures.harness(); absent.closingText = "다른 본문";
        absent.boot(); absent.ackAndReady(); mark(absent, PlaybackMark.Status.COMPLETED); absent.join(); absent.settleSend();
        int count = absent.link.sent(PlayApprovedContent.class).size(); absent.childSays("stop", "무중아 그만할래");
        assertThat(absent.link.sent(PlayApprovedContent.class)).hasSize(count);
        assertThat(facts(absent).goodbye()).isEqualTo(SessionGuardrailFacts.Goodbye.NONE);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }
}
