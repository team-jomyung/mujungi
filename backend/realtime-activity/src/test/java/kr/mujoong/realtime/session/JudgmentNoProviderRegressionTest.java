package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Consumer;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.realtime.judgment.UnavailableJudgeClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Luna 없음 회귀 (U11 FR1.6 · BR1.6 · NFR6). 키가 없거나 스위치가 꺼져 판정기를 쓸 수 없을 때 불명확한 참여 대답 · 불명확한 종료 신호의 보기와
 * 세션 사건 순서가 판정 장치 없는 흐름({@link JudgmentSupport#NONE})과 같다. 실행부가 여는 J1 · J6는 2026-10-22 결정으로 없앴으므로 판정기가
 * 있든 없든 흐름이 같고, 건너뛴 판정 기록도 남지 않는다. 키 없음 · 스위치 꺼짐은 둘 다 {@code available() = false}인 보류 판정기로
 * 같은 모양이다(빈 선택은 {@code JudgmentConfigurationTest}). 증거 종류: 테스트 대역.
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class JudgmentNoProviderRegressionTest {

    /** 한 시나리오의 관측: 보기 순서 · 세션 사건 종류 순서 · 재생 문구 코드 · 공급자 지시 수. */
    private record Observed(List<String> views, List<String> events, List<String> played, int appended) {
    }

    private static SessionHarness base() {
        SessionHarness h = new SessionHarness();
        h.extraApproved.put(SessionExecutor.STOP_CONFIRM, "이야기를 그만할까?");
        return h;
    }

    private static SessionHarness withUnavailableJudge() {
        SessionHarness h = base();
        h.useJudge().available(false);
        return h;
    }

    private static SessionHarness withUnavailablePort() {
        SessionHarness h = base();
        h.judgment = new JudgmentSupport(new UnavailableJudgeClient("prompt-v1"), h.judgmentRecorder,
                java.time.Duration.ZERO, "prompt-v1", null);
        return h;
    }

    private static Observed observe(SessionHarness h, Consumer<SessionHarness> script) {
        script.accept(h);
        h.settleSend();
        h.recorder.tick();
        return new Observed(
                h.link.sent(SessionState.class).stream().map(s -> s.view().name()).toList(),
                h.store.sessionEvents.stream().map(SessionEvent::getEventType).toList(),
                h.link.sent(PlayApprovedContent.class).stream().map(PlayApprovedContent::contentCode).toList(),
                h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).size());
    }

    private static final Consumer<SessionHarness> UNCLEAR_JOIN = h -> {
        h.boot();
        h.ackAndReady();
        h.completeNotice();
        h.childSays("j-1", "음 글쎄");
    };

    private static final Consumer<SessionHarness> UNCLEAR_STOP = h -> {
        h.activate();
        h.childSays("i-1", "그만");
        h.childSays("i-2", "티라노가 제일 커");
    };

    @Test
    void anUnclearJoinReplyBehavesTheSameWhenTheJudgeIsUnavailable() {
        Observed before = observe(base(), UNCLEAR_JOIN);

        assertThat(observe(withUnavailableJudge(), UNCLEAR_JOIN)).isEqualTo(before);
        assertThat(observe(withUnavailablePort(), UNCLEAR_JOIN)).isEqualTo(before);
        assertThat(before.events()).as("불명확한 대답도 곧바로 참여").contains(JoinFlow.EVENT_JOIN_ACCEPTED)
                .doesNotContain(JoinFlow.EVENT_JOIN_RE_PROMPTED);
    }

    @Test
    void anUnclearStopSignalBehavesTheSameWhenTheJudgeIsUnavailable() {
        Observed before = observe(base(), UNCLEAR_STOP);

        assertThat(observe(withUnavailableJudge(), UNCLEAR_STOP)).isEqualTo(before);
        assertThat(observe(withUnavailablePort(), UNCLEAR_STOP)).isEqualTo(before);
        assertThat(before.events()).as("확인 질문 없이 대화를 잇는다").doesNotContain(SessionExecutor.EVENT_STOP_CONFIRM_ASKED,
                SessionExecutor.EVENT_CHILD_STOP_INTENT);
        assertThat(before.played()).doesNotContain(SessionExecutor.STOP_CONFIRM);
    }

    @Test
    void anUnavailableJudgeLeavesNoJudgmentRecordForFreeTalk() {
        SessionHarness join = withUnavailableJudge();
        observe(join, UNCLEAR_JOIN);
        SessionHarness stop = withUnavailableJudge();
        observe(stop, UNCLEAR_STOP);

        join.judgmentRecorder.tick();
        stop.judgmentRecorder.tick();
        assertThat(join.store.judgments).isEmpty();
        assertThat(stop.store.judgments).isEmpty();
    }
}
