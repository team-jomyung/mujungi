package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTrigger;
import kr.mujoong.realtime.judgment.NextAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 턴 실행기 연결 1 (U11 W3 · W5 · W6, BR3.1 ~ BR3.6 · BR4.1 · BR4.5 · BR5.x): 열린 동안 출력 · 기록, 생각 중 유지 · 복원, 지시 순서, 지시를 못 들은
 * 경우, 상태 이탈. 공급자와 판정기는 가짜, 값은 합성이다(R-F1 · R-F3). 증거 종류: 테스트 대역.
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class JudgmentTurnExecutorTest {

    private final JudgmentExecutorSupport t = new JudgmentExecutorSupport();

    @Test
    void whileATurnIsOpenNothingReachesTheDeviceOrTheTranscriptAndThePiecesAreHeld() {
        t.openStopJudgment();
        int outputsBefore = t.h.link.outputs().size();

        t.h.speak("낡은 응답 문장", 4_800);
        t.advance(Duration.ofSeconds(1));
        t.h.recorder.tick();

        assertThat(t.judge.calls()).hasSize(1);
        assertThat(t.h.link.outputs()).hasSize(outputsBefore);
        assertThat(t.h.store.transcripts).noneMatch(row -> row.text().contains("낡은 응답 문장"));

        t.judge.confirm("NOT_END", 0.9, "UNCLEAR", 0.05);
        t.h.drain();

        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.heldOutputTranscript()).isEqualTo("낡은 응답 문장");
        assertThat(record.point()).isEqualTo(JudgmentPoint.J6);
        assertThat(record.trigger()).isEqualTo(JudgmentTrigger.STOP_CONFIRM_CANDIDATE);
    }

    @Test
    void theThinkingViewIsKeptWhileOpenAndListeningIsRestoredWhenItClosesWithNoSpeech() {
        t.openStopJudgment();
        assertThat(t.views()).last().isEqualTo(View.THINKING);

        t.judge.confirm("NOT_END", 0.9, "UNCLEAR", 0.05);
        t.h.drain();

        assertThat(t.views()).last().isEqualTo(View.LISTENING);
        assertThat(t.onlyRecord().nextAction()).isEqualTo(NextAction.RESUME);
        assertThat(t.onlyRecord().outcome()).isEqualTo(JudgmentOutcomeKind.CONFIRMED);
    }

    @Test
    void theInstructionIsOneAppendWithTheOneShotTemplateAndTheNewSpeechClosesTheTurnWithoutRestoringTheView() {
        t.openStopJudgment();
        int before = t.appendedContents().size();

        t.judge.confirm("REST", 0.9, "NOT_END", 0.05);
        t.h.drain();
        assertThat(t.appendedContents()).as("직렬 큐에 들어가기 전").hasSize(before);
        t.h.settleSend();

        assertThat(t.appendedContents()).hasSize(before + 1);
        String sent = t.appendedContents().getLast();
        assertThat(sent).isEqualTo(InstructionTemplates.NextAction.REST_ACK_ONLY.content(kr.mujoong.contract.status.ActivityType.FREE_TALK))
                .doesNotContain(InstructionTemplates.INTERJECTION_LEAD).endsWith(InstructionTemplates.SCOPE_CLOSE);
        assertThat(t.records()).as("지시를 보낸 동안은 턴이 열려 있다").isEmpty();

        t.h.speak("좋아, 잠깐 쉬자", 4_800);
        t.advance(Duration.ofSeconds(1));

        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.nextAction()).isEqualTo(NextAction.REST_ACK_ONLY);
        assertThat(record.firstOption()).isEqualTo("REST");
        assertThat(t.h.link.outputs()).as("지시 응답은 평소 경로로 기기에 간다").isNotEmpty();
    }

    @Test
    void anInstructionThatIsNeverAnsweredClosesTheTurnAfterTenSecondsWithoutSendingAgain() {
        t.openStopJudgment();
        t.judge.confirm("REST", 0.9, "NOT_END", 0.05);
        t.h.drain();
        t.h.settleSend();
        int appended = t.appendedContents().size();

        t.advance(Duration.ofSeconds(11));

        assertThat(t.records()).hasSize(1);
        assertThat(t.appendedContents()).hasSize(appended);
        assertThat(t.views()).last().isEqualTo(View.LISTENING);
        assertThat(t.h.executor.timers().isArmed(SessionTimers.Kind.JUDGMENT_INSTRUCTION)).isFalse();
    }

    @Test
    void exactlyFourSecondsIsATimeoutAndThreeNinetyNineStillWaits() {
        t.h.extraApproved.put(SessionExecutor.STOP_CONFIRM, "이야기를 그만할까?");
        t.openStopJudgment();

        t.advance(Duration.ofMillis(3_999));
        assertThat(t.records()).isEmpty();
        t.advance(Duration.ofMillis(1));

        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.ABSTAINED);
        assertThat(record.abstainReason()).isEqualTo(AbstainReason.TIMEOUT);
        assertThat(record.nextAction()).isEqualTo(NextAction.ASK_STOP_CONFIRM);
        assertThat(t.h.link.sent(PlayApprovedContent.class).getLast().contentCode()).isEqualTo(SessionExecutor.STOP_CONFIRM);
    }

    @Test
    void aResultThatArrivesAfterTheTurnClosedIsDroppedWithoutChangingAnything() {
        t.openStopJudgment();
        t.advance(Duration.ofSeconds(4));
        int records = t.records().size();
        CompletableFuture<?> late = t.judge.last().future();

        t.judge.confirm("END_CLEAR", 0.99, "NOT_END", 0.0);
        t.h.drain();

        assertThat(late.isDone()).isTrue();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.records()).hasSize(records);
    }

    @Test
    void aResultFromAnotherTurnIdIsDroppedAndTheOpenTurnKeepsWaiting() {
        t.openStopJudgment();

        t.h.mailbox.offerGeneral(new SessionMailbox.JudgmentArrived(UUID.randomUUID(), kr.mujoong.realtime.judgment.JudgeOutcome
                .abstained(AbstainReason.TIMEOUT, Duration.ZERO, null, "prompt-v1")));
        t.h.drain();

        assertThat(t.records()).isEmpty();
        assertThat(t.h.logs.anyLineContains("JUDGMENT_LATE_DROPPED")).isTrue();
    }

    @Test
    void leavingTheOpenedStatusClosesTheTurnAsSessionExited() {
        t.openStopJudgment();

        java.util.concurrent.CompletableFuture<SessionMailbox.EndOutcome> ended = new java.util.concurrent.CompletableFuture<>();
        t.h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, ended));
        t.h.drain();

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.SESSION_EXITED);
        assertThat(t.h.executor.timers().isArmed(SessionTimers.Kind.JUDGMENT_LIVE)).isFalse();
        assertThat(t.h.executor.timers().isArmed(SessionTimers.Kind.JUDGMENT_READY)).isFalse();
    }

    @Test
    void whenNoProviderExistsNoTurnOpensTodaysFlowRunsAndOneRecordIsKept() {
        t.judge.available(false);
        t.h.activate();
        int statesBefore = t.views().size();

        t.h.childSays("i-1", JudgmentExecutorSupport.UNCLEAR_STOP);

        assertThat(t.judge.calls()).isEmpty();
        assertThat(t.views()).as("생각 중 표시 없음").hasSize(statesBefore);
        assertThat(t.events(SessionExecutor.EVENT_STOP_CONFIRM_ASKED)).hasSize(1);
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.turnOpened()).isFalse();
        assertThat(record.abstainReason()).isEqualTo(AbstainReason.NO_PROVIDER);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.h.transitions.find(t.h.sessionId).orElseThrow().endReason()).isNotEqualTo(EndReason.CHILD_STOP);
    }
}
