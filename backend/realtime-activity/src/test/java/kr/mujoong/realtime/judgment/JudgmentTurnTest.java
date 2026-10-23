package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.judgment.JudgmentTurn.CloseReason;
import kr.mujoong.realtime.judgment.JudgmentTurn.Decision;
import kr.mujoong.realtime.judgment.JudgmentTurn.State;
import kr.mujoong.realtime.judgment.JudgmentTurn.TimerKind;
import kr.mujoong.realtime.judgment.JudgmentTurn.TranscriptAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 턴 협력 객체 (U11 functional-spec 2.2, 설계 개정 A2 · A4 · A8, 설계 검토 R-04 · R-15). 실행기 없이 가짜 Host와 손으로 푸는 판정 약속으로
 * 사건 하나하나를 확인한다. 합성 값만 쓴다.
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class JudgmentTurnTest {

    private static final Instant T0 = Instant.parse("2026-10-17T09:00:00Z");
    private static final Duration LIVE = Duration.ofSeconds(4);
    private static final Duration READY = Duration.ofSeconds(10);

    private FakeHost host;
    private FakeJudge judge;
    private final List<JudgeOutcome> decided = new ArrayList<>();
    private Decision nextDecision = Decision.close();

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        judge = new FakeJudge();
    }

    private static JudgeOutcome confirmed() {
        return JudgeOutcome.confirmed("REST", 0.8, "PARTICIPATE", 0.1, Duration.ofMillis(700), "luna-test", "prompt-v1");
    }

    private JudgmentTurn open(JudgmentOwner owner, Instant speechEndAt) {
        JudgmentTrigger trigger = owner == JudgmentOwner.EXECUTOR ? JudgmentTrigger.STOP_CONFIRM_CANDIDATE : JudgmentTrigger.EXTENSION_REQUEST;
        JudgmentPoint point = owner == JudgmentOwner.EXECUTOR ? JudgmentPoint.J6 : JudgmentPoint.J2;
        JudgmentTurn.Spec spec = new JudgmentTurn.Spec(UUID.randomUUID(), point, trigger, owner, TurnStatus.ACTIVE, 3, speechEndAt, LIVE,
                READY, "prompt-v1");
        JudgeRequest request = new JudgeRequest(point, "judgment-v1", List.of("A", "B"), "합성 전사", List.of(), null, Map.of(), false,
                T0.plus(LIVE));
        return JudgmentTurn.open(spec, judge, request, host, (turn, outcome) -> {
            decided.add(outcome);
            return nextDecision;
        }, T0);
    }

    private JudgmentTurn openExecutor() {
        return open(JudgmentOwner.EXECUTOR, T0.minusMillis(500));
    }

    @Test
    void openingArmsTheTwoJudgmentClocksAndAsksTheProviderOnce() {
        JudgmentTurn turn = open(JudgmentOwner.EXECUTOR, T0.minusSeconds(1));

        assertThat(turn.state()).isEqualTo(State.AWAITING_RESULT);
        assertThat(host.opened).isEqualTo(1);
        assertThat(host.armed).containsEntry(TimerKind.LIVE, LIVE).containsEntry(TimerKind.READY, Duration.ofSeconds(9))
                .doesNotContainKey(TimerKind.INSTRUCTION);
        assertThat(judge.calls).isEqualTo(1);
        assertThat(turn.liveDeadline()).isEqualTo(T0.plusSeconds(4));
        assertThat(turn.readyDeadline()).isEqualTo(T0.plusSeconds(9));
    }

    @Test
    void aResultJustBeforeTheLimitIsHandledAndAClosingDecisionReleasesEveryClock() {
        JudgmentTurn turn = openExecutor();
        judge.future.complete(confirmed());

        turn.onJudgmentArrived(turn.turnId(), confirmed(), T0.plusMillis(3_999));

        assertThat(decided).hasSize(1);
        assertThat(turn.state()).isEqualTo(State.CLOSED);
        JudgmentTurn.Closure closure = host.onlyClosure();
        assertThat(closure.reason()).isEqualTo(CloseReason.RESULT_HANDLED);
        assertThat(closure.outcome().isConfirmed()).isTrue();
        assertThat(closure.responseStarted()).isFalse();
        assertThat(host.cancelled).containsExactlyInAnyOrder(TimerKind.LIVE, TimerKind.READY);
        assertThat(judge.future.isCancelled()).isFalse();
    }

    @Test
    void anInstructingDecisionSendsOneInstructionAndSwapsTheClocks() {
        nextDecision = Decision.instruct(InstructionTemplates.NextAction.HOLD_REASK);
        JudgmentTurn turn = openExecutor();

        turn.onJudgmentArrived(turn.turnId(), JudgeOutcome.abstained(AbstainReason.LOW_MARGIN, Duration.ofMillis(900), "m", "prompt-v1"),
                T0.plusSeconds(1));

        assertThat(turn.state()).isEqualTo(State.INSTRUCTING);
        assertThat(host.sent).containsExactly(InstructionTemplates.NextAction.HOLD_REASK);
        assertThat(host.cancelled).containsExactlyInAnyOrder(TimerKind.LIVE, TimerKind.READY);
        assertThat(host.armed).containsEntry(TimerKind.INSTRUCTION, READY);
        assertThat(host.closures).isEmpty();
        // 판정 단계 시계는 풀렸으므로 늦게 온 READY · LIVE 만기는 아무것도 하지 않는다
        turn.onTimer(TimerKind.READY, T0.plusSeconds(10));
        turn.onTimer(TimerKind.LIVE, T0.plusSeconds(4));
        assertThat(turn.state()).isEqualTo(State.INSTRUCTING);
        assertThat(decided).hasSize(1);
    }

    @Test
    void theNewSpeechStartClosesTheInstructionStepWithoutRestoringTheView() {
        nextDecision = Decision.instruct(InstructionTemplates.NextAction.REST_ACK_ONLY);
        JudgmentTurn turn = openExecutor();
        turn.onJudgmentArrived(turn.turnId(), confirmed(), T0.plusSeconds(1));
        turn.onInstructionAcked();

        turn.onNewSpeechStarted(T0.plusSeconds(2));

        JudgmentTurn.Closure closure = host.onlyClosure();
        assertThat(closure.reason()).isEqualTo(CloseReason.INSTRUCTION_STARTED);
        assertThat(closure.responseStarted()).isTrue();
        assertThat(closure.instruction()).isEqualTo(InstructionTemplates.NextAction.REST_ACK_ONLY);
        assertThat(host.cancelled).contains(TimerKind.INSTRUCTION);
    }

    @Test
    void exactlyFourSecondsIsAnAbstainAndALaterArrivalIsDropped() {
        JudgmentTurn turn = openExecutor();

        turn.onTimer(TimerKind.LIVE, T0.plus(LIVE));
        turn.onJudgmentArrived(turn.turnId(), confirmed(), T0.plus(LIVE));

        assertThat(decided).singleElement().satisfies(outcome -> {
            assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.TIMEOUT);
            assertThat(outcome.latency()).isEqualTo(LIVE);
        });
        assertThat(judge.future.isCancelled()).isTrue();
        assertThat(host.dropped).containsExactly("JUDGMENT_LATE_DROPPED");
        assertThat(host.closures).hasSize(1);
    }

    @Test
    void theReadyClockOnlyWinsWhenTheTurnOpenedMoreThanSixSecondsAfterTheTranscript() {
        JudgmentTurn turn = open(JudgmentOwner.EXECUTOR, T0.minusSeconds(7));

        assertThat(host.armed.get(TimerKind.READY)).isEqualTo(Duration.ofSeconds(3));
        turn.onTimer(TimerKind.READY, T0.plusSeconds(3));

        assertThat(decided).singleElement().extracting(JudgeOutcome::abstainReason).isEqualTo(AbstainReason.RESPONSE_NOT_READY);
        assertThat(judge.future.isCancelled()).isTrue();
    }

    @Test
    void aTranscriptOlderThanTenSecondsArmsTheReadyClockAtZero() {
        open(JudgmentOwner.EXECUTOR, T0.minusSeconds(12));

        assertThat(host.armed.get(TimerKind.READY)).isEqualTo(Duration.ZERO);
    }

    @Test
    void theInstructionClockClosesWithTheOutputGenerationReasonCodes() {
        nextDecision = Decision.instruct(InstructionTemplates.NextAction.HOLD_REASK);
        JudgmentTurn noAck = openExecutor();
        noAck.onJudgmentArrived(noAck.turnId(), confirmed(), T0.plusSeconds(1));
        noAck.onTimer(TimerKind.INSTRUCTION, T0.plusSeconds(11));

        assertThat(host.onlyClosure().reason()).isEqualTo(CloseReason.ACK_MISSING);
        assertThat(host.sent).hasSize(1);

        host = new FakeHost();
        judge = new FakeJudge();
        JudgmentTurn acked = openExecutor();
        acked.onJudgmentArrived(acked.turnId(), confirmed(), T0.plusSeconds(1));
        acked.onInstructionAcked();
        acked.onTimer(TimerKind.INSTRUCTION, T0.plusSeconds(11));

        assertThat(host.onlyClosure().reason()).isEqualTo(CloseReason.NEW_SPEECH_NOT_OBSERVED);
        assertThat(host.sent).hasSize(1);
    }

    @Test
    void theOutputGenerationTimeoutCodesCloseTheTurnAndNeverReSendTheInstruction() {
        nextDecision = Decision.instruct(InstructionTemplates.NextAction.REST_ACK_AND_RETURN);
        JudgmentTurn turn = openExecutor();
        turn.onJudgmentArrived(turn.turnId(), confirmed(), T0.plusSeconds(1));

        turn.onInstructionNotHeard(CloseReason.NEW_SPEECH_NOT_OBSERVED, T0.plusSeconds(12));
        turn.onInstructionNotHeard(CloseReason.ACK_MISSING, T0.plusSeconds(13));

        assertThat(host.onlyClosure().reason()).isEqualTo(CloseReason.NEW_SPEECH_NOT_OBSERVED);
        assertThat(host.sent).hasSize(1);
    }

    @Test
    void aResultForAnotherTurnIsDroppedAndTheTurnStaysOpen() {
        JudgmentTurn turn = openExecutor();

        turn.onJudgmentArrived(UUID.randomUUID(), confirmed(), T0.plusSeconds(1));

        assertThat(turn.state()).isEqualTo(State.AWAITING_RESULT);
        assertThat(decided).isEmpty();
        assertThat(host.dropped).containsExactly("JUDGMENT_LATE_DROPPED");
    }

    @Test
    void aStopExpressionClosesTheTurnInEitherStep() {
        JudgmentTurn judging = openExecutor();
        assertThat(judging.onConfirmedTranscript(true, T0.plusSeconds(1))).isEqualTo(TranscriptAction.STOP_CLOSED);
        assertThat(host.onlyClosure().outcome().kind()).isEqualTo(JudgmentOutcomeKind.STOPPED);
        assertThat(judge.future.isCancelled()).isTrue();

        host = new FakeHost();
        judge = new FakeJudge();
        nextDecision = Decision.instruct(InstructionTemplates.NextAction.HOLD_REASK);
        JudgmentTurn instructing = openExecutor();
        instructing.onJudgmentArrived(instructing.turnId(), confirmed(), T0.plusSeconds(1));
        assertThat(instructing.onConfirmedTranscript(true, T0.plusSeconds(2))).isEqualTo(TranscriptAction.STOP_CLOSED);
        assertThat(host.onlyClosure().reason()).isEqualTo(CloseReason.STOPPED);
        assertThat(host.onlyClosure().instruction()).isEqualTo(InstructionTemplates.NextAction.HOLD_REASK);
    }

    @Test
    void aNewTranscriptSupersedesAnExecutorTurnInBothStepsAndAnExtensionTurnOnlyWhileInstructing() {
        JudgmentTurn executorTurn = openExecutor();
        assertThat(executorTurn.onConfirmedTranscript(false, T0.plusSeconds(1))).isEqualTo(TranscriptAction.REPROCESS);
        assertThat(host.onlyClosure().outcome().kind()).isEqualTo(JudgmentOutcomeKind.SUPERSEDED);

        host = new FakeHost();
        judge = new FakeJudge();
        JudgmentTurn extensionTurn = open(JudgmentOwner.EXTENSION, T0.minusMillis(500));
        assertThat(extensionTurn.onConfirmedTranscript(false, T0.plusSeconds(1))).isEqualTo(TranscriptAction.PASS_TO_EXTENSION);
        assertThat(extensionTurn.state()).isEqualTo(State.AWAITING_RESULT);

        nextDecision = Decision.instruct(InstructionTemplates.NextAction.HOLD_REASK);
        extensionTurn.onJudgmentArrived(extensionTurn.turnId(), confirmed(), T0.plusSeconds(2));
        assertThat(extensionTurn.onConfirmedTranscript(false, T0.plusSeconds(3))).isEqualTo(TranscriptAction.REPROCESS);
        assertThat(host.onlyClosure().reason()).isEqualTo(CloseReason.SUPERSEDED);
    }

    @Test
    void faultAndSessionExitCloseWithoutUsingAnyResult() {
        JudgmentTurn faulted = openExecutor();
        faulted.onFault(T0.plusSeconds(1));
        assertThat(host.onlyClosure().outcome().kind()).isEqualTo(JudgmentOutcomeKind.FAULT_CANCELLED);
        assertThat(judge.future.isCancelled()).isTrue();

        host = new FakeHost();
        judge = new FakeJudge();
        JudgmentTurn stays = openExecutor();
        stays.onSessionStatus(TurnStatus.ACTIVE, T0.plusSeconds(1));
        assertThat(stays.isOpen()).isTrue();
        stays.onSessionStatus(null, T0.plusSeconds(2));
        assertThat(host.onlyClosure().outcome().kind()).isEqualTo(JudgmentOutcomeKind.SESSION_EXITED);
        stays.onSessionStatus(null, T0.plusSeconds(3));
        assertThat(host.closures).hasSize(1);
    }

    @Test
    void eventsAfterTheTurnClosedAreIgnoredAndTheClosureIsReportedOnce() {
        JudgmentTurn turn = openExecutor();
        turn.onFault(T0.plusSeconds(1));

        turn.onTimer(TimerKind.LIVE, T0.plusSeconds(4));
        turn.onFault(T0.plusSeconds(5));
        turn.onNewSpeechStarted(T0.plusSeconds(5));
        turn.onJudgmentArrived(turn.turnId(), confirmed(), T0.plusSeconds(5));

        assertThat(turn.onConfirmedTranscript(true, T0.plusSeconds(6))).isEqualTo(TranscriptAction.NONE);
        assertThat(host.closures).hasSize(1);
        assertThat(turn.holdTranscript("뒤늦은 조각")).isFalse();
    }

    @Test
    void heldOutputTranscriptIsCappedAndTravelsWithTheClosureOnly() {
        JudgmentTurn turn = openExecutor();
        assertThat(turn.holdTranscript("가".repeat(1_500))).isTrue();
        assertThat(turn.holdTranscript("나".repeat(1_500))).isTrue();
        assertThat(turn.holdTranscript("다")).isTrue();

        turn.onFault(T0.plusSeconds(1));

        assertThat(host.onlyClosure().heldOutputTranscript()).hasSize(2_000).endsWith("나");
        assertThat(host.onlyClosure().toString()).doesNotContain("가가가");
    }

    @Test
    void anInstructionThatCannotBeQueuedClosesTheTurnWithoutWaiting() {
        nextDecision = Decision.instruct(InstructionTemplates.NextAction.HOLD_REASK);
        host.canSend = false;
        JudgmentTurn turn = openExecutor();

        turn.onJudgmentArrived(turn.turnId(), confirmed(), T0.plusSeconds(1));

        assertThat(host.onlyClosure().reason()).isEqualTo(CloseReason.INSTRUCTION_NOT_SENT);
        assertThat(host.cancelled).contains(TimerKind.INSTRUCTION);
    }

    @Test
    void aFailingBranchHandlerStillClosesTheTurnBeforeTheErrorPropagates() {
        JudgmentTurn.Spec spec = new JudgmentTurn.Spec(UUID.randomUUID(), JudgmentPoint.J1, JudgmentTrigger.JOIN_UNCLEAR,
                JudgmentOwner.EXECUTOR, TurnStatus.WAITING_CHILD, 1, T0, LIVE, READY, "prompt-v1");
        JudgeRequest request = new JudgeRequest(JudgmentPoint.J1, "judgment-v1", List.of("A"), "합성", List.of(), null, Map.of(), false,
                T0.plus(LIVE));
        JudgmentTurn turn = JudgmentTurn.open(spec, judge, request, host, (t, o) -> {
            throw new IllegalStateException("boom");
        }, T0);

        assertThatThrownBy(() -> turn.onJudgmentArrived(turn.turnId(), confirmed(), T0.plusSeconds(1))).isInstanceOf(IllegalStateException.class);

        assertThat(host.onlyClosure().reason()).isEqualTo(CloseReason.HANDLER_ERROR);
        assertThat(turn.isOpen()).isFalse();
    }

    @Test
    void aTurnNeverOpensWithoutAProviderAndTheDeferredJoinTickTravelsWithTheClosure() {
        JudgeClient none = new UnavailableJudgeClient("prompt-v1");
        JudgmentTurn.Spec spec = new JudgmentTurn.Spec(UUID.randomUUID(), JudgmentPoint.J1, JudgmentTrigger.JOIN_UNCLEAR,
                JudgmentOwner.EXECUTOR, TurnStatus.WAITING_CHILD, 1, T0, LIVE, READY, "prompt-v1");
        JudgeRequest request = new JudgeRequest(JudgmentPoint.J1, "judgment-v1", List.of("A"), "합성", List.of(), null, Map.of(), false,
                T0.plus(LIVE));
        assertThatThrownBy(() -> JudgmentTurn.open(spec, none, request, host, (t, o) -> Decision.close(), T0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(host.opened).isZero();

        JudgmentTurn turn = JudgmentTurn.open(spec, judge, request, host, (t, o) -> Decision.close(), T0);
        turn.deferJoinTick();
        turn.onJudgmentArrived(turn.turnId(), confirmed(), T0.plusSeconds(1));
        assertThat(host.onlyClosure().joinTickDeferred()).isTrue();
    }

    /** 실행기를 대신하는 가짜 바깥 세계. */
    private static final class FakeHost implements JudgmentTurn.Host {
        int opened;
        boolean canSend = true;
        final Map<TimerKind, Duration> armed = new EnumMap<>(TimerKind.class);
        final List<TimerKind> cancelled = new ArrayList<>();
        final List<InstructionTemplates.NextAction> sent = new ArrayList<>();
        final List<String> dropped = new ArrayList<>();
        final List<JudgmentTurn.Closure> closures = new ArrayList<>();

        @Override
        public void onOpened(JudgmentTurn turn) {
            opened++;
        }

        @Override
        public void armTimer(TimerKind kind, Duration delay) {
            armed.put(kind, delay);
        }

        @Override
        public void cancelTimer(TimerKind kind) {
            cancelled.add(kind);
        }

        @Override
        public void postResult(UUID turnId, JudgeOutcome outcome) {
            // 시험은 결과를 onJudgmentArrived로 직접 넣는다
        }

        @Override
        public boolean sendInstruction(UUID turnId, InstructionTemplates.NextAction action) {
            sent.add(action);
            return canSend;
        }

        @Override
        public void onDropped(String reasonCode) {
            dropped.add(reasonCode);
        }

        @Override
        public void onClosed(JudgmentTurn.Closure closure) {
            closures.add(closure);
        }

        JudgmentTurn.Closure onlyClosure() {
            assertThat(closures).hasSize(1);
            return closures.getFirst();
        }
    }

    /** 약속을 손으로 푸는 판정기. */
    private static final class FakeJudge implements JudgeClient {
        int calls;
        final CompletableFuture<JudgeOutcome> future = new CompletableFuture<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId) {
            calls++;
            return future;
        }
    }
}
