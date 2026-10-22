package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 장애 · 세션 이탈 회복 (U11 W6 · BR5.1 ~ BR5.4). 공급자 끊김 · 오류 · 기술적 일시 정지 · 상태 이탈 · 늦은 결과. 실행부가 여는 J1 · J6는
 * 2026-10-22 결정으로 없앴으므로 턴은 연습 확장점이 요청하는 J2로 연다. 공급자와 판정기는 가짜, 값은 합성이다(R-F1 · R-F3).
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class JudgmentFaultExecutorTest {

    private final JudgmentExecutorSupport t = new JudgmentExecutorSupport();

    @Test
    void aLostProviderClosesTheOpenTurnAsFaultCancelledBeforeTheSessionEnds() {
        t.h.provider.signalClosedOnClose(true);
        t.openExtensionJudgment();

        t.h.provider.last().close();
        t.h.drain();

        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.FAULT_CANCELLED);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ABORTED);
        assertThat(t.h.executor.timers().isArmed(SessionTimers.Kind.JUDGMENT_LIVE)).isFalse();
    }

    @Test
    void aResultThatArrivesAfterTheFaultIsDroppedByTurnIdWithTheReasonCodeOnly() {
        t.practice();
        t.h.link.touch(t.h.clock.now());
        t.h.advance(Duration.ofSeconds(8));
        assertThat(t.requestJudgment()).isTrue();
        t.h.advance(Duration.ofSeconds(3));
        assertThat(t.onlyRecord().outcome()).isEqualTo(JudgmentOutcomeKind.FAULT_CANCELLED);
        t.h.logs.clear();

        assertThat(t.judge.last().future().isCancelled()).as("닫히면 공급자 요청을 취소한다").isTrue();
        // 취소 전에 이미 편지함에 들어가 있던 결과가 닫힌 뒤 처리되는 경우를 흉내 낸다
        t.h.mailbox.offerGeneral(new SessionMailbox.JudgmentArrived(t.judge.last().turnId(), kr.mujoong.realtime.judgment.JudgeOutcome
                .confirmed("FULL", 0.99, "PARTIAL", 0.0, Duration.ofMillis(500), "luna-test", "prompt-v1")));
        t.h.drain();

        assertThat(t.records()).hasSize(1);
        assertThat(t.h.executor.status()).as("늦은 결과로 행동이 바뀌지 않는다").isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.h.logs.anyLineContains("JUDGMENT_LATE_DROPPED")).isTrue();
        assertThat(t.h.logs.anyLineContains(JudgmentExecutorSupport.CHILD_SAYS)).as("전사 원문은 로그에 없다").isFalse();
    }

    @Test
    void aTechnicalPauseCancelsTheOpenTurnAndTheSessionStaysActiveUntilTheDeviceIsBack() {
        t.practice();
        t.h.link.touch(t.h.clock.now());
        t.h.advance(Duration.ofSeconds(8));
        assertThat(t.requestJudgment()).isTrue();

        t.h.advance(Duration.ofSeconds(3));

        assertThat(t.h.executor.liveState().technicalPause()).isNotNull();
        JudgmentLiveRecord record = t.onlyRecord();
        assertThat(record.outcome()).isEqualTo(JudgmentOutcomeKind.FAULT_CANCELLED);
        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        t.h.link.touch(t.h.clock.now());
        t.h.advance(t.h.settings.timerTick());
        assertThat(t.h.executor.liveState().technicalPause()).isNull();
        assertThat(t.events("STOP_CONFIRM_ASKED")).as("장애로 닫힌 턴은 확인 질문을 하지 않는다").isEmpty();
    }

    @Test
    void anExtensionOwnedTurnReportsAFaultAbstainToTheExtension() {
        RecordingPracticeExtension extension = new RecordingPracticeExtension();
        t.h.activityType = ActivityType.PRACTICE;
        t.h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        t.h.practice = extension.factory;
        t.h.activate();
        t.h.link.touch(t.h.clock.now());
        t.h.advance(Duration.ofSeconds(8));
        t.h.childSays("i-1", "그거 줘");
        long seq = extension.utterances.getLast().seq();
        UUID correlation = UUID.randomUUID();
        extension.output.requestJudgment(JudgmentPoint.J2, seq, correlation, java.util.Map.of("coreFunction", "부탁한다",
                "judgmentCriteria", "이유가 드러나면 충분하다"));

        t.h.advance(Duration.ofSeconds(3));
        t.h.link.touch(t.h.clock.now());
        t.h.advance(t.h.settings.timerTick());

        assertThat(extension.events).anySatisfy(event -> {
            assertThat(event.kind()).isEqualTo(ExtensionEvent.Kind.JUDGMENT_ABSTAINED);
            assertThat(event.value().toString()).contains("FAULT");
            assertThat(event.correlationId()).isEqualTo(correlation);
        });
    }

    @Test
    void aProviderErrorEventFollowsTodaysPathAndDoesNotCloseTheTurn() {
        t.openExtensionJudgment();

        // 공급자 오류는 짧은 식별자 꼴일 때만 사유 코드에 싣는다(ProviderErrorCode_*). 자유 문자열은 unknown으로 바꿔 원문을 남기지 않는다
        t.h.provider.last().emit(new ProviderEvent.Error("Rate limit reached for 하준"));
        t.h.drain();

        assertThat(t.h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(t.records()).isEmpty();
        assertThat(t.h.logs.anyLineContains("Rate limit reached")).as("사유 원문 없이 고정 사유만").isFalse();
        assertThat(t.h.logs.anyLineContains("하준")).isFalse();
        assertThat(t.h.logs.anyLineContains("ProviderErrorCode_unknown")).isTrue();
        t.judge.confirm("PARTIAL", 0.9, "FULL", 0.05);
        t.h.drain();
        assertThat(t.records()).hasSize(1);
    }

    @Test
    void deletingTheSessionOrStoppingTheServerWhileATurnIsOpenClosesItAsExited() {
        t.openExtensionJudgment();

        t.h.mailbox.putSafety(new SessionMailbox.DeletionStop());
        t.h.drain();

        assertThat(t.onlyRecord().outcome()).isEqualTo(JudgmentOutcomeKind.SESSION_EXITED);

        JudgmentExecutorSupport shutdown = new JudgmentExecutorSupport();
        shutdown.openExtensionJudgment();
        shutdown.h.mailbox.putSafety(new SessionMailbox.ServerShutdown(new java.util.concurrent.CompletableFuture<>()));
        shutdown.h.drain();
        assertThat(shutdown.onlyRecord().outcome()).isEqualTo(JudgmentOutcomeKind.SESSION_EXITED);
    }
}
