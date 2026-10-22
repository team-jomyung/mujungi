package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import kr.mujoong.contract.status.SessionStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 참여 확인과 판정 AI (U11 W1 폐기, 2026-10-22 결정). 참여 대답 판정 AI(J1)를 없앴다: 자유대화의 시작 의사는 '거절인가'만 보고, 거절이 아니면
 * 불명확한 대답도 판정 AI 없이 곧바로 참여를 받는다. 공급자와 판정기는 가짜, 값은 합성이다(R-F1 · R-F3). 증거 종류: 테스트 대역.
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class JoinJudgmentExecutorTest {

    private final JudgmentExecutorSupport t = new JudgmentExecutorSupport();

    @Test
    void aClearYesOrNoNeverOpensATurn() {
        t.waiting();

        t.h.join();

        assertThat(t.judge.calls()).isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.records()).isEmpty();
    }

    @Test
    void anUnclearReplyJoinsAtOnceWithoutTheJudge() {
        t.waiting();

        t.h.childSays("j-1", "어떤 내용으로");

        assertThat(t.judge.calls()).as("J1을 열지 않는다").isEmpty();
        assertThat(t.records()).as("건너뛴 판정 기록도 없다").isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.events(JoinFlow.EVENT_JOIN_ACCEPTED)).hasSize(1);
        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).isEmpty();
    }

    @Test
    void anotherUnclearReplyAlsoJoinsAndTheFirstAnswerIsTheUsualInstruction() {
        t.waiting();

        t.h.childSays("j-1", "음 글쎄");
        t.h.settleSend();

        assertThat(t.judge.calls()).isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.appendedContents()).as("참여 뒤 첫 응답은 평소 지시 경로").hasSize(1);
    }

    @Test
    void aRefusalStillDeclinesWithoutTheJudge() {
        t.waiting();

        t.h.childSays("j-1", "싫어");

        assertThat(t.judge.calls()).isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.DECLINED);
        assertThat(t.events(JoinFlow.EVENT_JOIN_ALTERNATIVE_OFFERED)).isEmpty();
        assertThat(t.records()).isEmpty();
    }

    @Test
    void aWindowExpiryIsNoLongerDeferredByAJudgment() {
        t.waiting();
        t.h.advanceAlive(Duration.ofSeconds(19).plusMillis(900));

        t.h.childSays("j-1", "어떤 내용으로");

        assertThat(t.h.executor.status()).as("창 안의 불명확한 대답은 곧바로 참여").isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.h.executor.timers().isArmed(SessionTimers.Kind.JOIN_WINDOW)).isFalse();
        assertThat(t.judge.calls()).isEmpty();
    }

    @Test
    void withoutAProviderTheJoinFlowIsTheSameAndNoRecordIsKept() {
        t.judge.available(false);
        t.waiting();

        t.h.childSays("j-1", "어떤 내용으로");

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.records()).isEmpty();
    }
}
