package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTrigger;
import kr.mujoong.realtime.judgment.NextAction;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 턴 실행기 연결 2 — 참여 확인 J1 (U11 W1 · BR6.x · BR7.1, 설계 편차 U11-D6). 공급자와 판정기는 가짜, 값은 합성이다(R-F1 · R-F3).
 */
@Tag("judgment-turn")
class JoinJudgmentExecutorTest {

    private final JudgmentExecutorSupport t = new JudgmentExecutorSupport();

    @Test
    void onlyAnUnclearReplyOpensJ1AndTheViewIsNotChanged() {
        t.waiting();
        int viewsBefore = t.views().size();

        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);

        assertThat(t.judge.calls()).hasSize(1);
        assertThat(t.judge.last().request().point()).isEqualTo(JudgmentPoint.J1);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        assertThat(t.views()).as("참여 확인 중 판정 턴은 보기를 바꾸지 않는다").hasSize(viewsBefore);
        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).as("결과 전에는 join.reply를 부르지 않는다").isEmpty();
    }

    @Test
    void aClearYesOrNoNeverOpensATurn() {
        t.waiting();

        t.h.join();

        assertThat(t.judge.calls()).isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.records()).isEmpty();
    }

    @Test
    void participateStartsTheActivityThroughTheNormalPathWithOneRecord() {
        t.waiting();
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);

        t.judge.confirm("PARTICIPATE", 0.9, "UNCLEAR", 0.05);
        t.h.drain();

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.events(JoinFlow.EVENT_JOIN_ACCEPTED)).hasSize(1);
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.trigger()).isEqualTo(JudgmentTrigger.JOIN_UNCLEAR);
        assertThat(record.nextAction()).isEqualTo(NextAction.START_JOIN);
        t.h.settleSend();
        assertThat(t.appendedContents()).as("참여 뒤 첫 응답은 평소 지시 경로").hasSize(1);
    }

    @Test
    void declineOffersTheAlternativeAndRestUnclearAndAbstainAllReprompt() {
        t.waiting();
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        t.judge.confirm("DECLINE", 0.9, "UNCLEAR", 0.05);
        t.h.drain();
        assertThat(t.events(JoinFlow.EVENT_JOIN_ALTERNATIVE_OFFERED)).hasSize(1);
        assertThat(t.records().getLast().nextAction()).isEqualTo(NextAction.DECLINE_JOIN);

        JudgmentExecutorSupport second = new JudgmentExecutorSupport();
        second.waiting();
        second.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        second.judge.confirm("REST", 0.9, "UNCLEAR", 0.05);
        second.h.drain();
        assertThat(second.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).contains("UNCLEAR"));
        assertThat(second.onlyRecord().nextAction()).isEqualTo(NextAction.REPROMPT_JOIN);
        assertThat(second.appendedContents()).as("휴식에 받아 주는 말을 만들지 않는다(U11-D6)").isEmpty();

        JudgmentExecutorSupport third = new JudgmentExecutorSupport();
        third.waiting();
        third.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        third.judge.abstain(AbstainReason.LOW_MARGIN);
        third.h.drain();
        assertThat(third.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).hasSize(1);
        assertThat(third.onlyRecord().outcome()).isEqualTo(JudgmentOutcomeKind.ABSTAINED);
    }

    @Test
    void theRepromptIsCountedOnlyOnceAcrossTwoUnclearAnswers() {
        t.waiting();
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();
        t.h.childSays("j-2", JudgmentExecutorSupport.UNCLEAR_JOIN);
        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();

        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).as("재안내는 한 번만 센다").hasSize(1);
        assertThat(t.judge.calls()).hasSize(2);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
    }

    @Test
    void aWindowExpiryDuringAnOpenJ1IsDeferredAndTheReplyKeepsItsOriginalTime() {
        t.waiting();
        t.h.advanceAlive(Duration.ofSeconds(19).plusMillis(900));
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);

        t.h.advanceAlive(Duration.ofMillis(300));
        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).as("창 만기는 J1 중 미뤄진다").isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);

        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();

        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).singleElement().satisfies(event -> {
            assertThat(event.getPayloadJson()).as("원래 전사 시각(창 안)으로 넣었으므로 무응답이 아니다").contains("\"cause\":\"UNCLEAR\"");
        });
        assertThat(t.events(JoinFlow.EVENT_JOIN_DECLINED)).isEmpty();
    }

    @Test
    void aNewReplyWhileJ1IsOpenSupersedesItAndIsProcessedFromTheStart() {
        t.waiting();
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);

        t.h.childSays("j-2", "응");

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.SUPERSEDED);
        assertThat(t.judge.calls()).hasSize(1);
        t.judge.confirm("DECLINE", 0.9, "REST", 0.05);
        t.h.drain();
        assertThat(t.records()).as("늦은 결과는 버린다").hasSize(1);
    }

    // ------------------------------------------------------------------ 참여 관문 안정화 (3): J1 진행 중 불명확한 새 대답

    @Test
    void aSecondUnclearReplyWhileJ1IsOpenIsHeldAndTheResultIsNotThrownAway() {
        t.waiting();
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);

        t.h.childSays("j-2", "모르겠는데");

        assertThat(t.judge.calls()).as("J1을 닫지 않는다").hasSize(1);
        assertThat(t.records()).as("SUPERSEDED 기록이 없다").isEmpty();
        t.judge.confirm("PARTICIPATE", 0.9, "UNCLEAR", 0.05);
        t.h.drain();

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.onlyRecord().outcome()).isEqualTo(JudgmentOutcomeKind.CONFIRMED);
        assertThat(t.judge.calls()).as("참여 결과면 대기 칸은 버린다").hasSize(1);
        assertThat(t.h.logs.events(EventCode.JOIN_REPLY)).extracting(LogCapture::attrs)
                .extracting(attrs -> attrs.get("joinReply") + "/" + attrs.get("joinAction"))
                .containsExactly("UNCLEAR/J1_OPENED", "UNCLEAR/J1_HELD", "ACCEPT/START");
    }

    @Test
    void anUnclearResultReopensJ1WithTheHeldReplyAndThePreviousOneAsChildContextAfterTheFirstRecord() {
        t.waiting();
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        t.h.childSays("j-2", "모르겠는데");

        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();

        assertThat(t.judge.calls()).hasSize(2);
        kr.mujoong.realtime.judgment.JudgeRequest second = t.judge.last().request();
        assertThat(second.childUtterance()).isEqualTo("모르겠는데");
        assertThat(second.recentTurns()).filteredOn(turn -> turn.speaker() == kr.mujoong.realtime.judgment.JudgeRequest.Speaker.CHILD)
                .extracting(kr.mujoong.realtime.judgment.JudgeRequest.RecentTurn::text)
                .containsExactly(JudgmentExecutorSupport.UNCLEAR_JOIN);
        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).as("첫 결과는 참여 판단에 넣지 않는다").isEmpty();

        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();

        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).as("두 대답에 재안내는 한 번(B17)").singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).contains("\"cause\":\"UNCLEAR\""));
        assertThat(t.records()).extracting(JudgmentLiveRecord::nextAction)
                .containsExactlyInAnyOrder(NextAction.NONE, NextAction.REPROMPT_JOIN);
        // 새 턴은 앞 턴의 판정 기록 · 진단 로그가 남은 뒤에 열린다(onClosed 안에서 열지 않는다)
        assertThat(t.h.logs.events().stream().filter(event -> List.of("JOIN_REPLY", "JUDGMENT_TURN").contains(event.get("event")))
                .map(event -> event.get("event") + ":" + LogCapture.attrs(event).getOrDefault("joinAction",
                        LogCapture.attrs(event).get("nextAction"))).toList())
                .containsExactly("JOIN_REPLY:J1_OPENED", "JOIN_REPLY:J1_HELD", "JUDGMENT_TURN:NONE", "JOIN_REPLY:J1_OPENED",
                        "JUDGMENT_TURN:REPROMPT_JOIN", "JOIN_REPLY:CLARIFY");
    }

    @Test
    void aWindowExpiryDeferredByTheFirstJ1SurvivesTheReopenWithTheHeldReply() {
        t.waiting();
        // 되묻기를 먼저 써서, 다음 창 만기는 DECLINED가 되게 한다
        t.h.childSays("j-0", JudgmentExecutorSupport.UNCLEAR_JOIN);
        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();
        t.mark(t.h.link.sent(kr.mujoong.contract.ws.message.PlayApprovedContent.class).getLast(),
                kr.mujoong.contract.ws.message.PlaybackMark.Status.COMPLETED);
        t.advance(Duration.ofSeconds(19));
        t.h.childSays("j-1", "음 그게");
        t.advance(Duration.ofMillis(500));
        t.h.childSays("j-2", "모르겠는데");
        t.advance(Duration.ofMillis(800));
        assertThat(t.h.executor.status()).as("창 만기는 J1 중 미뤄진다").isEqualTo(SessionStatus.WAITING_CHILD);

        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();
        assertThat(t.judge.calls()).as("대기 칸으로 다시 판정").hasSize(3);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);

        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();

        assertThat(t.h.transitions.status(t.h.sessionId)).as("새 J1이 닫힌 뒤 미룬 만기가 돌았다").isEqualTo(SessionStatus.DECLINED);
        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).hasSize(1);
    }

    @Test
    void eachClosedOrSkippedJ1LeavesOneJudgmentTurnLineWithCodesOnly() {
        t.waiting();
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        t.judge.confirm("PARTICIPATE", 0.9, "UNCLEAR", 0.05);
        t.h.drain();
        assertThat(turnLines(t)).singleElement().satisfies(line -> assertThat(line)
                .containsEntry("judgmentPoint", "J1").containsEntry("judgmentOutcome", "CONFIRMED")
                .containsEntry("firstOption", "PARTICIPATE").containsEntry("closeReason", "RESULT_HANDLED")
                .containsEntry("nextAction", "START_JOIN").containsEntry("promptVersion", "prompt-v1")
                .containsEntry("latencyMs", 900).doesNotContainKey("abstainReason"));
        assertThat(t.h.logs.events(EventCode.JOIN_REPLY)).extracting(LogCapture::attrs)
                .extracting(attrs -> attrs.get("joinReply") + "/" + attrs.get("joinAction"))
                .containsExactly("UNCLEAR/J1_OPENED", "ACCEPT/START");

        JudgmentExecutorSupport superseded = new JudgmentExecutorSupport();
        superseded.waiting();
        superseded.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        superseded.h.childSays("j-2", "응");
        assertThat(turnLines(superseded)).singleElement().satisfies(line -> assertThat(line)
                .containsEntry("judgmentOutcome", "SUPERSEDED").containsEntry("closeReason", "SUPERSEDED")
                .containsEntry("nextAction", "NONE").doesNotContainKey("firstOption"));

        JudgmentExecutorSupport none = new JudgmentExecutorSupport();
        none.judge.available(false);
        none.waiting();
        none.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        assertThat(turnLines(none)).singleElement().satisfies(line -> assertThat(line)
                .containsEntry("judgmentOutcome", "ABSTAINED").containsEntry("abstainReason", "NO_PROVIDER")
                .containsEntry("nextAction", "REPROMPT_JOIN").doesNotContainKey("closeReason"));
        for (JudgmentExecutorSupport each : List.of(t, superseded, none)) {
            assertThat(each.h.logs.lines()).noneMatch(line -> line.contains(JudgmentExecutorSupport.UNCLEAR_JOIN));
        }
    }

    private static List<Map<String, Object>> turnLines(JudgmentExecutorSupport support) {
        return support.h.logs.events(EventCode.JUDGMENT_TURN).stream().map(LogCapture::attrs).toList();
    }

    @Test
    void aBlankReplyOrNoProviderSkipsTheTurnWithOneRecordAndTodaysFlow() {
        t.waiting();

        t.h.childSays("j-1", "  ");

        assertThat(t.judge.calls()).isEmpty();
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.turnOpened()).isFalse();
        assertThat(record.abstainReason()).isEqualTo(AbstainReason.UNREADABLE_INPUT);
        assertThat(t.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).hasSize(1);

        JudgmentExecutorSupport none = new JudgmentExecutorSupport();
        none.judge.available(false);
        none.waiting();
        none.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        assertThat(none.onlyRecord().abstainReason()).isEqualTo(AbstainReason.NO_PROVIDER);
        assertThat(none.events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).hasSize(1);
    }
}
