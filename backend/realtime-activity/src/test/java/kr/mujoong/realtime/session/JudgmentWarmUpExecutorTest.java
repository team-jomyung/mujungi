package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import kr.mujoong.realtime.testing.FakeJudgeClient;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 시작 때 판정 경로 예열 — 첫 판정이 처음 도는 코드 · 새 연결 비용으로 live-budget을 넘어 보류되던 문제(2026-10-27 실키 세션). 기기에 시작
 * 신호를 보낸 세션만 한 번 예열하고, 판정 턴은 열지 않는다. 증거 종류: 테스트 대역.
 */
@Tag("judgment-turn")
class JudgmentWarmUpExecutorTest {

    @Test
    void aStartedSessionWarmsTheJudgeOnceWithoutOpeningATurn() {
        SessionHarness h = new SessionHarness();
        FakeJudgeClient judge = h.useJudge();

        h.boot();
        h.ackAndReady();

        assertThat(judge.warmUps()).isEqualTo(1);
        assertThat(judge.calls()).as("예열은 판정이 아니다").isEmpty();
    }

    @Test
    void aSessionThatCannotReachTheDeviceDoesNotWarmTheJudge() {
        SessionHarness h = new SessionHarness();
        FakeJudgeClient judge = h.useJudge();
        h.link = null;
        h.create();
        h.executor.bootstrapOnly();
        h.drain();

        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(judge.warmUps()).isZero();
    }
}
