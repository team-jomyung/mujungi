package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTrigger;
import kr.mujoong.realtime.judgment.NextAction;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 턴 실행기 연결 1 (U11 W3 · W5 · W6, BR3.1 ~ BR3.6 · BR4.1 · BR5.x): 열린 동안 출력 · 기록, 생각 중 유지 · 복원, 시간 초과, 늦은 결과,
 * 상태 이탈. 실행부가 여는 J1 · J6는 2026-10-22 결정으로 없앴으므로 턴은 연습 확장점이 요청하는 J2로 연다. 공급자와 판정기는 가짜, 값은
 * 합성이다(R-F1 · R-F3). 증거 종류: 테스트 대역.
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class JudgmentTurnExecutorTest {

    private final JudgmentExecutorSupport t = new JudgmentExecutorSupport();

    @Test
    void whileATurnIsOpenNothingReachesTheDeviceOrTheTranscriptAndThePiecesAreHeld() {
        t.openExtensionJudgment();
        int outputsBefore = t.h.link.outputs().size();

        t.h.speak("낡은 응답 문장", 4_800);
        t.advance(Duration.ofSeconds(1));
        t.h.recorder.tick();

        assertThat(t.judge.calls()).hasSize(1);
        assertThat(t.h.link.outputs()).hasSize(outputsBefore);
        assertThat(t.h.store.transcripts).noneMatch(row -> row.text().contains("낡은 응답 문장"));

        t.judge.confirm("PARTIAL", 0.9, "FULL", 0.05);
        t.h.drain();

        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.heldOutputTranscript()).isEqualTo("낡은 응답 문장");
        assertThat(record.point()).isEqualTo(JudgmentPoint.J2);
        assertThat(record.trigger()).isEqualTo(JudgmentTrigger.EXTENSION_REQUEST);
    }

    @Test
    void theThinkingViewIsKeptWhileOpenAndListeningIsRestoredWhenItClosesWithNoSpeech() {
        t.openExtensionJudgment();
        assertThat(t.views()).last().isEqualTo(View.THINKING);

        t.judge.confirm("PARTIAL", 0.9, "FULL", 0.05);
        t.h.drain();

        assertThat(t.views()).last().isEqualTo(View.LISTENING);
        assertThat(t.onlyRecord().nextAction()).isEqualTo(NextAction.DELEGATED_TO_EXTENSION);
        assertThat(t.onlyRecord().outcome()).isEqualTo(JudgmentOutcomeKind.CONFIRMED);
    }

    @Test
    void exactlyFourSecondsIsATimeoutAndThreeNinetyNineStillWaits() {
        t.openExtensionJudgment();

        t.advance(Duration.ofMillis(3_999));
        assertThat(t.records()).isEmpty();
        t.advance(Duration.ofMillis(1));

        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.ABSTAINED);
        assertThat(record.abstainReason()).isEqualTo(AbstainReason.TIMEOUT);
        assertThat(t.extension.events).anySatisfy(event -> {
            assertThat(event.kind()).isEqualTo(ExtensionEvent.Kind.JUDGMENT_ABSTAINED);
            assertThat(event.value().toString()).contains("TIMEOUT");
            assertThat(event.correlationId()).isEqualTo(t.correlation);
        });
    }

    @Test
    void aResultThatArrivesAfterTheTurnClosedIsDroppedWithoutChangingAnything() {
        t.openExtensionJudgment();
        t.advance(Duration.ofSeconds(4));
        int records = t.records().size();
        int events = t.extension.events.size();
        CompletableFuture<?> late = t.judge.last().future();

        t.judge.confirm("FULL", 0.99, "PARTIAL", 0.0);
        t.h.drain();

        assertThat(late.isDone()).isTrue();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.records()).hasSize(records);
        assertThat(t.extension.events).as("닫힌 턴의 결과는 확장점에 가지 않는다").hasSize(events);
    }

    @Test
    void aResultFromAnotherTurnIdIsDroppedAndTheOpenTurnKeepsWaiting() {
        t.openExtensionJudgment();

        t.h.mailbox.offerGeneral(new SessionMailbox.JudgmentArrived(UUID.randomUUID(), kr.mujoong.realtime.judgment.JudgeOutcome
                .abstained(AbstainReason.TIMEOUT, Duration.ZERO, null, "prompt-v1")));
        t.h.drain();

        assertThat(t.records()).isEmpty();
        assertThat(t.h.logs.anyLineContains("JUDGMENT_LATE_DROPPED")).isTrue();
        assertThat(t.views()).as("열린 턴은 계속 기다린다").last().isEqualTo(View.THINKING);
    }

    @Test
    void leavingTheOpenedStatusClosesTheTurnAsSessionExited() {
        t.openExtensionJudgment();

        CompletableFuture<SessionMailbox.EndOutcome> ended = new CompletableFuture<>();
        t.h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, ended));
        t.h.drain();

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.SESSION_EXITED);
        assertThat(t.h.executor.timers().isArmed(SessionTimers.Kind.JUDGMENT_LIVE)).isFalse();
        assertThat(t.h.executor.timers().isArmed(SessionTimers.Kind.JUDGMENT_READY)).isFalse();
    }

    @Test
    void anOrdinaryFreeTalkReplyNeverOpensATurn() {
        t.h.activate();

        t.h.childSays("i-1", "그만");
        t.h.childSays("i-2", "음 글쎄");

        assertThat(t.judge.calls()).as("실행부는 J1 · J6를 열지 않는다").isEmpty();
        assertThat(t.records()).as("건너뛴 판정 기록도 남지 않는다").isEmpty();
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }
}
