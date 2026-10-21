package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Test;

/** 상태효과 대역 시험. 실제 J6 요청 경계는 JudgmentRequestServiceGuardrailTest에서 별도로 검사한다. */
@org.junit.jupiter.api.Tag("service-guardrail")
class StopQuestionServiceGuardrailTest {
    private SessionHarness active() {
        var h = ServiceGuardrailFixtures.harness();
        h.boot(); h.ackAndReady(); mark(h, PlaybackMark.Status.COMPLETED); h.join(); h.settleSend();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        return h;
    }
    private void mark(SessionHarness h, PlaybackMark.Status status) {
        var play = h.link.sent(PlayApprovedContent.class).getLast();
        h.mailbox.putSafety(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }
    @Test void aStartedQuestionDoesNotPermitAnEarlyYes() {
        var h = active(); h.childSays("candidate", "그만");
        assertThat(h.link.sent(PlayApprovedContent.class).getLast().contentCode()).isEqualTo(SessionExecutor.STOP_CONFIRM);
        mark(h, PlaybackMark.Status.STARTED); h.childSays("early", "응");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
    }
    @Test void actualCompletionThenAYesCloses() {
        var h = active(); h.childSays("candidate", "그만"); mark(h, PlaybackMark.Status.COMPLETED);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isTrue();
        h.childSays("current", "응");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
    }
    @Test void missingVerifiedQuestionReturnsToListeningWithoutAutomaticSpeech() {
        var h = ServiceGuardrailFixtures.harness(); h.extraApproved.remove(SessionExecutor.STOP_CONFIRM);
        h.boot(); h.ackAndReady(); mark(h, PlaybackMark.Status.COMPLETED); h.join(); h.settleSend();
        int requests = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).size();
        h.childSays("candidate", "그만"); h.settleSend();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class)).hasSize(requests);
    }
    @Test void aFailedQuestionDoesNotOpenAResponseWindow() {
        var h = active(); h.childSays("candidate", "그만"); mark(h, PlaybackMark.Status.FAILED);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
    }
    @Test void aStoppedQuestionDoesNotOpenAResponseWindow() {
        var h = active(); h.childSays("candidate", "그만"); mark(h, PlaybackMark.Status.STOPPED);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
    }
    @Test void playbackExpiryIsNotTwentySecondsOfChildSilence() {
        var h = active(); h.childSays("candidate", "그만");
        h.advanceAlive(h.settings.playbackExpiry());
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
        assertThat(h.link.sent(PlayApprovedContent.class).stream().filter(p -> p.contentCode().equals(SessionExecutor.STOP_CONFIRM)))
                .hasSize(1);
    }
    @Test void unclearRepliesKeepTheOneDeliveredQuestionUntilItsDeadline() {
        var h = active(); h.childSays("candidate", "그만"); mark(h, PlaybackMark.Status.COMPLETED);
        h.childSays("unclear", "잘 모르겠어");
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isTrue();
        h.advanceAlive(Duration.ofSeconds(20));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
        assertThat(h.link.sent(PlayApprovedContent.class).stream().filter(p -> p.contentCode().equals(SessionExecutor.STOP_CONFIRM)))
                .hasSize(1);
    }
    @Test void sendFailureDoesNotPretendTheQuestionWasDelivered() {
        var h = active(); h.link.setAcceptControl(false); h.childSays("candidate", "그만");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.executor.timers().isArmed(SessionTimers.Kind.STOP_CONFIRM)).isFalse();
    }
}
