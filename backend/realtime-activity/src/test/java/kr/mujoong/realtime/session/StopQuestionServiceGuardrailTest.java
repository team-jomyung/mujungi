package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Test;

/**
 * 서비스 가드레일 경로의 종료 확인 질문 폐기(2026-10-22 결정) 상태효과 대역 시험. 불명확한 종료 신호는 질문 · 창 없이 대화를 잇고, 물음꼴 종료 표현은
 * 곧바로 끝낸다. 값은 합성이다(R-F1).
 */
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

    private static long questions(SessionHarness h) {
        return h.link.sent(PlayApprovedContent.class).stream()
                .filter(play -> play.contentCode().equals("STOP_CONFIRM")).count();
    }

    @Test
    void anUnclearSignalPlaysNoQuestionAndOpensNoWindow() {
        var h = active();

        h.childSays("candidate", "그만");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(questions(h)).isZero();
    }

    @Test
    void aYesAfterAnUnclearSignalIsOrdinaryTalkAndDoesNotEndTheSession() {
        var h = active();
        h.childSays("candidate", "그만");

        h.childSays("current", "응");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(questions(h)).isZero();
    }

    @Test
    void aQuestionShapedStopPhraseEndsAtOnce() {
        var h = active();

        h.childSays("candidate", "그만할래?");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(questions(h)).isZero();
    }
}
