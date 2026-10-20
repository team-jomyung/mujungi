package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTrigger;
import kr.mujoong.realtime.judgment.NextAction;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 확장 능력 판정 요청 (U11 W4 · BR8.1 ~ BR8.4). 연습 확장점 대역이 {@code requestJudgment}를 부르고 결과 사건을 받는다. 판정기는 가짜, 값은 합성이다
 * (R-F1 · R-F3).
 */
@Tag("judgment-turn")
class JudgmentExtensionPortTest {

    private final JudgmentExecutorSupport t = new JudgmentExecutorSupport();
    private final RecordingPracticeExtension extension = new RecordingPracticeExtension();
    private final UUID correlation = UUID.randomUUID();

    private void practice() {
        t.h.activityType = kr.mujoong.contract.status.ActivityType.PRACTICE;
        t.h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        t.h.practice = extension.factory;
        t.h.activate();
    }

    private static Map<String, Object> criteria() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("coreFunction", "필요한 것과 이유를 함께 말해 부탁한다");
        criteria.put("judgmentCriteria", "무엇이 필요한지와 왜 필요한지가 드러나면 충분히 전달");
        return criteria;
    }

    private long say(String itemId, String text) {
        t.h.childSays(itemId, text);
        return extension.utterances.getLast().seq();
    }

    @Test
    void aRecentTranscriptIsAcceptedAndOpensAnExtensionOwnedTurnWithTheGivenCriteria() {
        practice();
        long seq = say("i-1", "그거 줘");

        boolean accepted = extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria());
        t.h.drain();

        assertThat(accepted).isTrue();
        assertThat(t.judge.calls()).hasSize(1);
        assertThat(t.judge.last().request().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(t.judge.last().request().criteria()).isNotNull();
        assertThat(t.views()).last().isEqualTo(View.THINKING);
    }

    @Test
    void theResultEventCarriesOptionCodesWithoutProbabilitiesAndNoReaskInstructionIsSent() {
        practice();
        long seq = say("i-1", "그거 줘");
        extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria());
        t.h.settleSend();
        int appended = t.appendedContents().size();

        t.judge.confirm("PARTIAL", 0.81, "FULL", 0.12);
        t.h.drain();
        t.h.settleSend();

        ExtensionEvent event = extension.events.getLast();
        assertThat(event.kind()).isEqualTo(ExtensionEvent.Kind.JUDGMENT_RESULT);
        assertThat(event.correlationId()).isEqualTo(correlation);
        assertThat(event.value().toString()).contains("PARTIAL", "FULL", "J2").doesNotContain("0.81", "0.12");
        assertThat(t.appendedContents()).as("확장 소유 판정은 되묻기를 넣지 않는다(BR8.2)").hasSize(appended);
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.trigger()).isEqualTo(JudgmentTrigger.EXTENSION_REQUEST);
        assertThat(record.nextAction()).isEqualTo(NextAction.DELEGATED_TO_EXTENSION);
        assertThat(t.views()).last().isEqualTo(View.LISTENING);
    }

    @Test
    void anAbstainAndTheHeldOutputTranscriptAreSentAsEvents() {
        practice();
        long seq = say("i-1", "그거 줘");
        extension.output.requestJudgment(JudgmentPoint.J3, seq, correlation, criteria());
        t.h.speak("이전 응답 문장", 4_800);

        t.judge.abstain(AbstainReason.LOW_CONFIDENCE);
        t.h.drain();

        assertThat(extension.events).extracting(ExtensionEvent::kind).containsSubsequence(
                ExtensionEvent.Kind.JUDGMENT_ABSTAINED, ExtensionEvent.Kind.HELD_OUTPUT_TRANSCRIPT);
        assertThat(extension.events.stream().filter(e -> e.kind() == ExtensionEvent.Kind.JUDGMENT_ABSTAINED).findFirst()
                .orElseThrow().value().toString()).contains("LOW_CONFIDENCE");
        assertThat(extension.events.getLast().value().toString()).contains("이전 응답 문장");
    }

    @Test
    void requestsOutsideTheRulesAreRefusedWithoutOpeningATurn() {
        practice();
        long seq = say("i-1", "그거 줘");

        assertThat(extension.output.requestJudgment(JudgmentPoint.J1, seq, correlation, criteria())).as("J1은 실행부 몫").isFalse();
        assertThat(extension.output.requestJudgment(JudgmentPoint.J6, seq, correlation, criteria())).as("J6은 실행부 몫").isFalse();
        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq + 99, correlation, criteria())).as("최근 전사에 없다").isFalse();
        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, null)).as("기준 없음").isFalse();
        assertThat(t.judge.calls()).isEmpty();

        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria())).isTrue();
        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, UUID.randomUUID(), criteria())).as("열린 턴이 있다").isFalse();
        assertThat(t.judge.calls()).hasSize(1);
    }

    @Test
    void onlyTheLastFourConfirmedTranscriptsCanBeJudged() {
        practice();
        long first = say("i-1", "하나");
        for (int i = 2; i <= 5; i++) {
            say("i-" + i, "말 " + i);
        }
        long last = extension.utterances.getLast().seq();

        assertThat(extension.output.requestJudgment(JudgmentPoint.J4, first, correlation, null)).as("다섯 번째 전에 밀려났다").isFalse();
        assertThat(extension.output.requestJudgment(JudgmentPoint.J4, last, correlation, null)).isTrue();
        assertThat(t.judge.last().request().recentTurns()).hasSize(3);
        assertThat(t.judge.last().request().childUtterance()).isEqualTo("말 5");
    }

    @Test
    void whenTheSessionIsNotActiveTheRequestIsRefusedWithAFailureEvent() {
        practice();
        long seq = say("i-1", "그거 줘");
        t.h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(),
                kr.mujoong.contract.status.EndRequestReason.GUARDIAN_STOP, new java.util.concurrent.CompletableFuture<>()));
        t.h.drain();

        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria())).isFalse();

        assertThat(t.events(SessionExecutor.EVENT_PRACTICE_EXTENSION_FAILED)).anySatisfy(event -> assertThat(event.getPayloadJson())
                .contains("NOT_ACTIVE").contains("requestJudgment"));
        assertThat(t.judge.calls()).isEmpty();
    }

    @Test
    void withoutAProviderTheRequestIsRefusedAndOneNoProviderRecordIsKept() {
        t.judge.available(false);
        practice();
        long seq = say("i-1", "그거 줘");

        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria())).isFalse();

        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.abstainReason()).isEqualTo(AbstainReason.NO_PROVIDER);
        assertThat(record.turnOpened()).isFalse();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.ABSTAINED);
    }

    @Test
    void setThinkingKeepsTheThinkingViewAndRoleSwitchOnlyReservesTheSlot() {
        practice();
        say("i-1", "그거 줘");
        t.h.drain();

        assertThat(extension.output.announceRoleSwitch("COACH")).isFalse();
        assertThat(extension.output.setThinking(true)).isTrue();
        assertThat(t.h.link.sent(SessionState.class)).extracting(SessionState::view).last().isEqualTo(View.THINKING);
        t.h.speak("응답", 4_800);
        t.advance(Duration.ofSeconds(1));
        assertThat(extension.output.setThinking(false)).isTrue();
    }

    @Test
    void existingExtensionsCompileWithoutTheNewCapabilitiesAndTheyDefaultToFalse() {
        PracticeSessionExtension.PracticeOutput bare = new PracticeSessionExtension.PracticeOutput() {
            @Override public UUID sessionId() { return UUID.randomUUID(); }
            @Override public boolean playApproved(String contentCode) { return false; }
            @Override public boolean instructProvider(String text) { return false; }
            @Override public boolean requestResponse() { return false; }
            @Override public boolean cancelResponse() { return false; }
            @Override public boolean holdOutput(boolean hold) { return false; }
            @Override public boolean schedule(String name, Duration delay) { return false; }
            @Override public boolean cancel(String name) { return false; }
            @Override public boolean post(String name, Object value) { return false; }
        };

        assertThat(bare.requestJudgment(JudgmentPoint.J2, 1, UUID.randomUUID(), Map.of())).isFalse();
        assertThat(bare.announceRoleSwitch("COACH")).isFalse();
        assertThat(bare.setThinking(true)).isFalse();
    }
}
