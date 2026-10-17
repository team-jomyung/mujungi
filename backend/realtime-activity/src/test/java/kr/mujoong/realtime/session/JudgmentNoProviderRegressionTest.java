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
 * Luna 없음 회귀 (U11 FR1.6 · BR1.6 · NFR6). 키가 없거나 스위치가 꺼져 판정기를 쓸 수 없을 때 J1 불명확 · J6 CONFIRM의 보기와 세션 사건 순서가
 * PR2 이전 흐름(판정 장치 없는 {@link JudgmentSupport#NONE})과 같다. 키 없음 · 스위치 꺼짐은 둘 다 {@code available() = false}인 보류 판정기로
 * 같은 모양이다(빈 선택은 {@code JudgmentConfigurationTest}). 증거 종류: 테스트 대역.
 */
@Tag("u11")
class JudgmentNoProviderRegressionTest {

    /** 한 시나리오의 관측: 보기 순서 · 세션 사건 종류 순서 · 재생 문구 코드 · 공급자 지시 수. */
    private record Observed(List<String> views, List<String> events, List<String> played, int appended) {
    }

    private static SessionHarness base() {
        SessionHarness h = new SessionHarness();
        h.extraApproved.put(SessionExecutor.STOP_CONFIRM, "이야기를 그만할까?");
        h.extraApproved.put(SessionExecutor.RE_PROMPT, "같이 이야기해 볼까?");
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

    private static final Consumer<SessionHarness> J1_UNCLEAR = h -> {
        h.boot();
        h.ackAndReady();
        h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        h.childSays("j-2", "응");
    };

    private static final Consumer<SessionHarness> J6_CONFIRM = h -> {
        h.activate();
        h.childSays("i-1", JudgmentExecutorSupport.UNCLEAR_STOP);
        h.childSays("i-2", "티라노가 제일 커");
    };

    @Test
    void anUnclearJoinReplyBehavesLikeBeforeWhenTheJudgeIsUnavailable() {
        Observed before = observe(base(), J1_UNCLEAR);

        assertThat(observe(withUnavailableJudge(), J1_UNCLEAR)).isEqualTo(before);
        assertThat(observe(withUnavailablePort(), J1_UNCLEAR)).isEqualTo(before);
        assertThat(before.events()).contains(SessionExecutor.EVENT_JOIN_RE_PROMPTED, SessionExecutor.EVENT_JOIN_ACCEPTED);
    }

    @Test
    void aStopConfirmCandidateBehavesLikeBeforeWhenTheJudgeIsUnavailable() {
        Observed before = observe(base(), J6_CONFIRM);

        assertThat(observe(withUnavailableJudge(), J6_CONFIRM)).isEqualTo(before);
        assertThat(observe(withUnavailablePort(), J6_CONFIRM)).isEqualTo(before);
        assertThat(before.events()).contains(SessionExecutor.EVENT_STOP_CONFIRM_ASKED);
    }

    @Test
    void theConfirmQuestionAndAYesStillEndTheSessionTheSameWayWithoutAProvider() {
        Consumer<SessionHarness> script = h -> {
            h.activate();
            h.childSays("i-1", JudgmentExecutorSupport.UNCLEAR_STOP);
            h.childSays("i-2", "응");
        };
        Observed before = observe(base(), script);

        assertThat(observe(withUnavailableJudge(), script)).isEqualTo(before);
        assertThat(before.events()).contains(SessionExecutor.EVENT_STOP_CONFIRM_RESOLVED);
    }

    @Test
    void theOnlyTraceOfAnUnavailableJudgeIsOneJudgmentRecordPerSkippedTurn() {
        SessionHarness h = withUnavailableJudge();
        observe(h, J6_CONFIRM);

        assertThat(h.store.judgments).singleElement().satisfies(record -> {
            assertThat(record.turnOpened()).isFalse();
            assertThat(record.abstainReason()).isEqualTo(kr.mujoong.realtime.judgment.AbstainReason.NO_PROVIDER);
        });
    }
}
