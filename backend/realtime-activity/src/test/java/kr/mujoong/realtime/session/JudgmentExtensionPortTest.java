package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgeRequest;
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
@org.junit.jupiter.api.Tag("service-guardrail")
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

    // ---- 같은 걸음 묶음 (ai-io.md 10.10, 편차 FT-1024-f) ----

    @Test
    void sameStepGroupsInTheCriteriaMapReachTheJudgeRequestWithinTheOptionSetAndAreNotProviderInput() {
        practice();
        long seq = say("i-1", "무슨 말이야");
        Map<String, Object> criteria = criteria();
        criteria.put(PracticeSessionExtension.PracticeOutput.SAME_STEP_GROUPS,
                List.of(List.of("NOT_UNDERSTOOD", "IRRELEVANT"), List.of("FULL", "COPIED")));

        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria)).isTrue();
        t.h.drain();

        JudgeRequest request = t.judge.last().request();
        assertThat(request.sameStepGroups()).as("J2 선택지 밖 코드(COPIED)는 걷고 하나만 남은 묶음은 버린다")
                .containsExactly(List.of("NOT_UNDERSTOOD", "IRRELEVANT"));
        assertThat(request.criteria()).isNotNull();
        assertThat(request.toLunaInput()).as("묶음은 공급자 입력이 아니다")
                .doesNotContainKey(PracticeSessionExtension.PracticeOutput.SAME_STEP_GROUPS);
    }

    @Test
    void aJ4RequestMayCarryOnlyTheGroupsAndStillHasNoCriteria() {
        practice();
        long seq = say("i-1", "몰라");

        assertThat(extension.output.requestJudgment(JudgmentPoint.J4, seq, correlation,
                Map.of(PracticeSessionExtension.PracticeOutput.SAME_STEP_GROUPS, List.of(List.of("REFUSE", "UNCLEAR"))))).isTrue();
        t.h.drain();

        assertThat(t.judge.last().request().criteria()).as("J4에는 판정 기준이 없다(D2)").isNull();
        assertThat(t.judge.last().request().sameStepGroups()).containsExactly(List.of("REFUSE", "UNCLEAR"));
    }

    @Test
    void malformedSameStepGroupsAreRefusedLikeMalformedCriteria() {
        practice();
        long seq = say("i-1", "그거 줘");
        List<Object> malformed = List.of("FULL", List.of("FULL", "PARTIAL"), List.of(List.of("FULL")), List.of(List.of("FULL", 3)),
                List.of(List.of("FULL", " ")), List.of(List.of("FULL", "PARTIAL"), List.of("PARTIAL", "HELP")),
                List.of(List.of("FULL", "FULL")));

        for (Object bad : malformed) {
            Map<String, Object> criteria = criteria();
            criteria.put(PracticeSessionExtension.PracticeOutput.SAME_STEP_GROUPS, bad);
            assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria)).as(String.valueOf(bad)).isFalse();
        }
        assertThat(t.judge.calls()).as("모양이 틀린 묶음으로는 턴을 열지 않는다").isEmpty();
        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria())).as("묶음 없는 요청은 그대로").isTrue();
    }

    @Test
    void aSameStepSumConfirmationIsDeliveredAsAnOrdinaryResultAndLeavesOneFixedDiagnosticCode() {
        practice();
        long first = say("i-1", "그거 줘");
        extension.output.requestJudgment(JudgmentPoint.J2, first, correlation, criteria());
        t.h.drain();
        t.judge.confirm("FULL", 0.9, "PARTIAL", 0.05);
        t.h.drain();
        assertThat(t.h.logs.anyLineContains(JudgmentCoordinator.SAME_STEP_SUM_CONFIRMED)).as("1순위 혼자 확정은 진단 코드가 없다").isFalse();

        long retry = say("i-2", "내가 필요한 건 파란색이야");
        UUID retryCorrelation = UUID.randomUUID();
        extension.output.requestJudgment(JudgmentPoint.J5, retry, retryCorrelation, criteria());
        t.h.drain();
        t.judge.confirmBySameStep("FULL", 0.38, "COPIED", 0.29);
        t.h.drain();

        ExtensionEvent event = extension.events.getLast();
        assertThat(event.kind()).isEqualTo(ExtensionEvent.Kind.JUDGMENT_RESULT);
        assertThat(event.correlationId()).isEqualTo(retryCorrelation);
        assertThat(event.value().toString()).contains("FULL", "COPIED", "J5").doesNotContain("0.38", "0.29");
        assertThat(t.h.logs.anyLineContains("JudgmentConfirmedBySameStepSum_J5")).isTrue();
        assertThat(t.h.logs.anyLineContains("파란색")).isFalse();
        JudgmentLiveRecord record = t.records().getLast();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.CONFIRMED);
        assertThat(record.firstOption()).as("기록에는 모델이 낸 1순위 그대로").isEqualTo("FULL");
        assertThat(record.firstProbability()).isEqualTo(0.38);
        assertThat(record.secondOption()).isEqualTo("COPIED");
        assertThat(record.secondProbability()).isEqualTo(0.29);
    }

    // ---- 요청 하한 (ai-io.md 10.10, 편차 FT-1024-h) ----

    private static final String MIN_CONFIDENCE = PracticeSessionExtension.PracticeOutput.MIN_CONFIDENCE;

    @Test
    void aLoweredFloorInTheCriteriaMapReachesTheJudgeRequestAndIsNotProviderInput() {
        practice();
        long seq = say("i-1", "그거");
        Map<String, Object> criteria = criteria();
        criteria.put(MIN_CONFIDENCE, 0.45);

        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria)).isTrue();
        t.h.drain();

        JudgeRequest request = t.judge.last().request();
        assertThat(request.minConfidence()).isEqualTo(0.45);
        assertThat(request.criteria()).as("판정 기준은 그대로").isNotNull();
        assertThat(request.toLunaInput()).as("낮춘 하한은 공급자 입력이 아니다").doesNotContainKey(MIN_CONFIDENCE);
        assertThat(request.toLunaInput().toString()).doesNotContain("0.45");
    }

    @Test
    void withoutAFloorTheJudgeRequestKeepsTheGlobalOne() {
        practice();
        long seq = say("i-1", "그거");

        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria())).isTrue();
        t.h.drain();

        assertThat(t.judge.last().request().minConfidence()).isNull();
    }

    @Test
    void aFloorOutsideTheAcceptedShapeIsRefusedLikeMalformedCriteria() {
        practice();
        long seq = say("i-1", "그거 줘");
        List<Object> malformed = List.of("0.45", Double.NaN, Double.POSITIVE_INFINITY, 0.29, 1.01, -0.5, List.of(0.45), true);

        for (Object bad : malformed) {
            Map<String, Object> criteria = criteria();
            criteria.put(MIN_CONFIDENCE, bad);
            assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, criteria)).as(String.valueOf(bad)).isFalse();
        }
        assertThat(t.judge.calls()).as("모양이 틀린 하한으로는 턴을 열지 않는다").isEmpty();

        Map<String, Object> lowest = criteria();
        lowest.put(MIN_CONFIDENCE, PracticeSessionExtension.PracticeOutput.MIN_CONFIDENCE_LOWEST);
        assertThat(extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, lowest)).as("받는 범위의 아래 끝").isTrue();
        t.h.drain();
        assertThat(t.judge.last().request().minConfidence()).isEqualTo(0.3);
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
