package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTrigger;
import kr.mujoong.realtime.judgment.NextAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 턴 실행기 연결 2 — 참여 확인 J1 (U11 W1 · BR6.x · BR7.1, 설계 편차 U11-D6). 공급자와 판정기는 가짜, 값은 합성이다(R-F1 · R-F3).
 */
@Tag("u11")
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
        assertThat(t.events(SessionExecutor.EVENT_JOIN_RE_PROMPTED)).as("결과 전에는 join.reply를 부르지 않는다").isEmpty();
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
        assertThat(t.events(SessionExecutor.EVENT_JOIN_ACCEPTED)).hasSize(1);
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
        assertThat(t.events(SessionExecutor.EVENT_JOIN_ALTERNATIVE_OFFERED)).hasSize(1);
        assertThat(t.records().getLast().nextAction()).isEqualTo(NextAction.DECLINE_JOIN);

        JudgmentExecutorSupport second = new JudgmentExecutorSupport();
        second.waiting();
        second.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        second.judge.confirm("REST", 0.9, "UNCLEAR", 0.05);
        second.h.drain();
        assertThat(second.events(SessionExecutor.EVENT_JOIN_RE_PROMPTED)).singleElement()
                .satisfies(event -> assertThat(event.getPayloadJson()).contains("UNCLEAR"));
        assertThat(second.onlyRecord().nextAction()).isEqualTo(NextAction.REPROMPT_JOIN);
        assertThat(second.appendedContents()).as("휴식에 받아 주는 말을 만들지 않는다(U11-D6)").isEmpty();

        JudgmentExecutorSupport third = new JudgmentExecutorSupport();
        third.waiting();
        third.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        third.judge.abstain(AbstainReason.LOW_MARGIN);
        third.h.drain();
        assertThat(third.events(SessionExecutor.EVENT_JOIN_RE_PROMPTED)).hasSize(1);
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

        assertThat(t.events(SessionExecutor.EVENT_JOIN_RE_PROMPTED)).as("재안내는 한 번만 센다").hasSize(1);
        assertThat(t.judge.calls()).hasSize(2);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
    }

    @Test
    void aWindowExpiryDuringAnOpenJ1IsDeferredAndTheReplyKeepsItsOriginalTime() {
        t.waiting();
        t.h.advanceAlive(Duration.ofSeconds(19).plusMillis(900));
        t.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);

        t.h.advanceAlive(Duration.ofMillis(300));
        assertThat(t.events(SessionExecutor.EVENT_JOIN_RE_PROMPTED)).as("창 만기는 J1 중 미뤄진다").isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);

        t.judge.confirm("UNCLEAR", 0.9, "REST", 0.05);
        t.h.drain();

        assertThat(t.events(SessionExecutor.EVENT_JOIN_RE_PROMPTED)).singleElement().satisfies(event -> {
            assertThat(event.getPayloadJson()).as("원래 전사 시각(창 안)으로 넣었으므로 무응답이 아니다").contains("\"cause\":\"UNCLEAR\"");
        });
        assertThat(t.events(SessionExecutor.EVENT_JOIN_DECLINED)).isEmpty();
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

    @Test
    void aBlankReplyOrNoProviderSkipsTheTurnWithOneRecordAndTodaysFlow() {
        t.waiting();

        t.h.childSays("j-1", "  ");

        assertThat(t.judge.calls()).isEmpty();
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.turnOpened()).isFalse();
        assertThat(record.abstainReason()).isEqualTo(AbstainReason.UNREADABLE_INPUT);
        assertThat(t.events(SessionExecutor.EVENT_JOIN_RE_PROMPTED)).hasSize(1);

        JudgmentExecutorSupport none = new JudgmentExecutorSupport();
        none.judge.available(false);
        none.waiting();
        none.h.childSays("j-1", JudgmentExecutorSupport.UNCLEAR_JOIN);
        assertThat(none.onlyRecord().abstainReason()).isEqualTo(AbstainReason.NO_PROVIDER);
        assertThat(none.events(SessionExecutor.EVENT_JOIN_RE_PROMPTED)).hasSize(1);
    }
}
