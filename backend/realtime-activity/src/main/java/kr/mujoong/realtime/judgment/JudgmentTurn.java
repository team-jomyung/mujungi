package kr.mujoong.realtime.judgment;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.realtime.dialog.InstructionTemplates;

/**
 * 판정 턴 한 번의 수명 (U11 functional-spec 2.2, 설계 개정 A2 · A4 · A8, 설계 검토 R-04(이번 판) · R-15). 세션 실행기 스레드만 읽고 쓰는 협력
 * 객체이고 저장하지 않는다. 세션당 열린 턴은 하나뿐이다(실행기가 지킨다). 이 패키지는 실행기 · 출력 게이트 · 기기 게이트웨이를 모르므로, 바깥 세계와는
 * {@link Host}(실행기가 구현)와 {@link ResultHandler}(지점별 분기 W1 · W2 · W4)로만 닿는다.
 *
 * <p>상태: {@code AWAITING_RESULT}(판정 요청 중, 시계 LIVE · READY) → {@code INSTRUCTING}(지시 하나를 직렬 큐로 보내고 확인 사건 · 새 말을 기다림, 시계
 * INSTRUCTION) → {@code CLOSED}. 공급자 응답 요청(response.create)도, 송신 허가도, 송신 취소 사건도 없다(설계 개정 A2): 지시를 보낸다는 것은
 * {@link Host#sendInstruction}이 {@code append} 하나를 직렬 큐에 넣으며 출력 세대를 올린다는 뜻이다. 새 말의 시작 판단(A4)은 출력 세대가 하고, 실행기가
 * 그 결과를 {@link #onInstructionAcked} · {@link #onNewSpeechStarted} · {@link #onInstructionNotHeard}로 알린다.
 *
 * <p>닫힘은 한 번뿐이다. 닫힐 때 시계 셋을 모두 풀고 진행 중인 판정 요청을 취소한 뒤 {@link Host#onClosed}에 {@link Closure}를 한 번 알린다(보기
 * 복원 · 판정 기록 · 미룬 참여 창 tick은 실행기 몫). 늦은 결과 · 낡은 차례 id · 닫힌 뒤 사건은 버리고 사유 코드만 알린다(BR5.2). 전사 · 지시문 본문은
 * 로그에 쓰지 않는다(R-F14).
 */
public final class JudgmentTurn {

    /** 턴 상태. */
    public enum State {
        AWAITING_RESULT,
        INSTRUCTING,
        CLOSED
    }

    /** 턴이 쓰는 시계 셋. 실행기가 {@code SessionTimers.Kind}의 같은 이름으로 옮긴다. */
    public enum TimerKind {
        /** 열기 + live-budget(4s). */
        LIVE,
        /** 판정 대상 확정 전사 시각 + response-ready(10s). 판정 단계에만 있다. */
        READY,
        /** 지시 단계 진입 + response-ready(10s). */
        INSTRUCTION
    }

    /** 닫힌 까닭(사유 코드만 로그에 남긴다). 앞의 둘은 출력 세대의 지시 응답 시한 사유와 같은 이름이다. */
    public enum CloseReason {
        /** 지시 확인 사건이 시한 안에 오지 않았다(OutputGeneration ACK_MISSING). */
        ACK_MISSING,
        /** 확인은 왔으나 새 말 전사가 시한 안에 없다(OutputGeneration NEW_SPEECH_NOT_OBSERVED). */
        NEW_SPEECH_NOT_OBSERVED,
        /** 지시를 직렬 큐에 넣지 못했다(공급자 연결 없음). */
        INSTRUCTION_NOT_SENT,
        /** 결과 분기가 지시 없이 마무리했다(확장 소유 · 보류 줄 등). */
        RESULT_HANDLED,
        /** 지시에 따른 새 말이 시작했다(보기 복원은 하지 않는다, 이 구간이 곧 응답이다). */
        INSTRUCTION_STARTED,
        /** 아이 확정 전사가 종료 표현이다. */
        STOPPED,
        /** 아이 확정 전사가 새로 왔다. 새 전사를 처음부터 다시 처리한다. */
        SUPERSEDED,
        /** 공급자 끊김 · 기술적 일시 정지. */
        FAULT,
        /** 세션 상태가 턴을 연 상태를 벗어났다. */
        SESSION_EXITED,
        /** 결과 분기 처리 중 예외가 났다. */
        HANDLER_ERROR
    }

    /** 결과 분기가 정하는 다음 일. */
    public record Decision(InstructionTemplates.NextAction instruction) {
        /** 지시 없이 닫는다. */
        public static Decision close() {
            return new Decision(null);
        }

        /** 지시 하나를 보내고 지시 단계로 간다. */
        public static Decision instruct(InstructionTemplates.NextAction action) {
            return new Decision(Objects.requireNonNull(action, "action"));
        }
    }

    /** 아이 확정 전사를 받았을 때 호출자가 할 일. */
    public enum TranscriptAction {
        /** 열린 턴이 없거나 이미 닫혔다 — 평소처럼 처리한다. */
        NONE,
        /** 확장 소유 판정 단계다 — 턴을 그대로 두고 확장에 전달한다. */
        PASS_TO_EXTENSION,
        /** 종료 표현으로 닫았다 — 종료 처리(BR6.2)를 한다. */
        STOP_CLOSED,
        /** 새 전사로 닫았다 — 새 전사를 처음부터 지금 흐름에 넣는다. */
        REPROCESS
    }

    /**
     * 닫힘 보고. {@code outcome}은 판정 결과 또는 중단(STOPPED · SUPERSEDED · FAULT_CANCELLED · SESSION_EXITED)이다. 지시를 낸 턴이면
     * {@code instruction}이 남는다.
     *
     * @param heldOutputTranscript 판정 중 모은 출력 전사(2000자 이하, 로그에 쓰지 않는다)
     * @param responseStarted 지시에 따른 응답이 시작돼 닫혔는가(참이면 보기를 복원하지 않는다)
     * @param joinTickDeferred 닫힌 뒤 참여 창 tick을 불러야 하는가(W1)
     */
    public record Closure(UUID turnId, JudgmentPoint point, JudgmentTrigger trigger, JudgmentOwner owner, TurnStatus openedInStatus,
            int inputTranscriptSeq, JudgeOutcome outcome, CloseReason reason, InstructionTemplates.NextAction instruction,
            String heldOutputTranscript, boolean responseStarted, boolean joinTickDeferred) {

        @Override
        public String toString() {
            return "Closure[turnId=" + turnId + ", point=" + point + ", reason=" + reason + ", outcome=" + outcome + "]";
        }
    }

    /** 실행기가 구현하는 바깥 세계. 모든 메서드는 실행기 스레드에서 불린다({@link #postResult}만 완료 콜백 스레드에서 불린다). */
    public interface Host {

        /** 턴이 열렸다 — 실행기는 출력 세대를 올리고(JUDGMENT_TURN) ACTIVE면 생각 중으로 바꾼다. */
        void onOpened(JudgmentTurn turn);

        void armTimer(TimerKind kind, Duration delay);

        void cancelTimer(TimerKind kind);

        /**
         * 공급자 완료 콜백 스레드에서 불린다. 실행기 일반 편지함에 {@code JudgmentArrived{turnId, outcome}}를 넣기만 한다(BR1.8).
         */
        void postResult(UUID turnId, JudgeOutcome outcome);

        /**
         * 지시 하나를 직렬 큐로 보내며 출력 세대를 올린다(SERVER_SEND, 설계 개정 A2).
         *
         * @return 보냈는가(공급자 연결이 없으면 거짓)
         */
        boolean sendInstruction(UUID turnId, InstructionTemplates.NextAction action);

        /** 낡은 결과 · 전사를 버렸다 — 사유 코드와 개수만 남긴다(BR5.2). */
        void onDropped(String reasonCode);

        /** 턴이 닫혔다 — 보기 복원 · 판정 기록 · 미룬 tick은 실행기가 한다. 한 번만 불린다. */
        void onClosed(Closure closure);
    }

    /** 결과(또는 4초 · 10초 보류)가 정해졌을 때 지점별로 다음 일을 정한다(W1 · W2 · W4). */
    @FunctionalInterface
    public interface ResultHandler {
        Decision decide(JudgmentTurn turn, JudgeOutcome outcome);
    }

    /**
     * 턴을 여는 값.
     *
     * @param speechEndAt 판정 대상 확정 전사의 확정 시각(설계 개정 A8). 10초 시계 기준이다
     * @param liveBudget {@code mujoong.timing.judgment.live-budget}(4s)
     * @param responseReady {@code mujoong.timing.judgment.response-ready}(10s)
     */
    public record Spec(UUID turnId, JudgmentPoint point, JudgmentTrigger trigger, JudgmentOwner owner, TurnStatus openedInStatus,
            int inputTranscriptSeq, Instant speechEndAt, Duration liveBudget, Duration responseReady, String promptVersion) {

        public Spec {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(openedInStatus, "openedInStatus");
            Objects.requireNonNull(speechEndAt, "speechEndAt");
            Objects.requireNonNull(liveBudget, "liveBudget");
            Objects.requireNonNull(responseReady, "responseReady");
            Objects.requireNonNull(promptVersion, "promptVersion");
            if (inputTranscriptSeq < 0) {
                throw new IllegalArgumentException("입력 전사 순번은 0 이상이다");
            }
            if (liveBudget.isNegative() || liveBudget.isZero() || responseReady.isNegative() || responseReady.isZero()) {
                throw new IllegalArgumentException("판정 시한은 0보다 커야 한다");
            }
        }
    }

    private final Spec spec;
    private final Host host;
    private final ResultHandler handler;
    private final Instant openedAt;
    private final Map<TimerKind, Boolean> armedTimers = new EnumMap<>(TimerKind.class);
    private final StringBuilder heldTranscript = new StringBuilder();
    private CompletableFuture<JudgeOutcome> pending;
    private State state = State.AWAITING_RESULT;
    private boolean instructionAcked;
    private boolean joinTickDeferred;
    private InstructionTemplates.NextAction instruction;
    private JudgeOutcome decidedOutcome;

    private JudgmentTurn(Spec spec, Host host, ResultHandler handler, Instant openedAt) {
        this.spec = spec;
        this.host = host;
        this.handler = handler;
        this.openedAt = openedAt;
    }

    /**
     * 턴을 열고 판정을 요청한다(공급자가 있고 입력이 판독 가능할 때만 부른다, BR1.6 · BR4.3 — 실행기가 먼저 확인한다). 시계 LIVE(열기 + live-budget)와
     * READY(확정 전사 시각 + response-ready, 이미 지났으면 바로)를 건다.
     */
    public static JudgmentTurn open(Spec spec, JudgeClient judge, JudgeRequest request, Host host, ResultHandler handler, Instant now) {
        Objects.requireNonNull(judge, "judge");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(now, "now");
        if (!judge.available()) {
            throw new IllegalStateException("공급자가 없으면 판정 턴을 열지 않는다(BR1.6)");
        }
        JudgmentTurn turn = new JudgmentTurn(spec, host, handler, now);
        host.onOpened(turn);
        host.armTimer(TimerKind.LIVE, spec.liveBudget());
        turn.armedTimers.put(TimerKind.LIVE, true);
        Duration untilReady = Duration.between(now, spec.speechEndAt().plus(spec.responseReady()));
        host.armTimer(TimerKind.READY, untilReady.isNegative() ? Duration.ZERO : untilReady);
        turn.armedTimers.put(TimerKind.READY, true);
        turn.request(judge, request);
        return turn;
    }

    private void request(JudgeClient judge, JudgeRequest request) {
        CompletableFuture<JudgeOutcome> future;
        try {
            future = judge.judge(request, spec.turnId()).toCompletableFuture();
        } catch (RuntimeException unexpected) {
            // 포트는 던지지 않는다는 계약이지만, 어겨도 보류로 바꿔 턴이 멈추지 않게 한다
            host.postResult(spec.turnId(), JudgeOutcome.abstained(AbstainReason.PROVIDER_ERROR, Duration.ZERO, null, spec.promptVersion()));
            return;
        }
        pending = future;
        future.whenComplete((outcome, failure) -> {
            if (future.isCancelled()) {
                return;
            }
            host.postResult(spec.turnId(), outcome != null ? outcome
                    : JudgeOutcome.abstained(AbstainReason.PROVIDER_ERROR, Duration.ZERO, null, spec.promptVersion()));
        });
    }

    // ---- 읽기 ----

    public UUID turnId() {
        return spec.turnId();
    }

    public State state() {
        return state;
    }

    public JudgmentPoint point() {
        return spec.point();
    }

    public JudgmentOwner owner() {
        return spec.owner();
    }

    public JudgmentTrigger trigger() {
        return spec.trigger();
    }

    public TurnStatus openedInStatus() {
        return spec.openedInStatus();
    }

    public Instant openedAt() {
        return openedAt;
    }

    /** 열기 + live-budget. 이 시각에 닿으면(정확히 같아도) 보류다. */
    public Instant liveDeadline() {
        return openedAt.plus(spec.liveBudget());
    }

    /** 판정 대상 확정 전사 시각 + response-ready. */
    public Instant readyDeadline() {
        return spec.speechEndAt().plus(spec.responseReady());
    }

    public boolean isOpen() {
        return state != State.CLOSED;
    }

    /** 모은 출력 전사(2000자 이하). 로그에 쓰지 않는다. */
    public String heldOutputTranscript() {
        return heldTranscript.toString();
    }

    public boolean instructionAcked() {
        return instructionAcked;
    }

    /** 닫힌 뒤 참여 창 tick이 필요한가(W1). */
    public boolean joinTickDeferred() {
        return joinTickDeferred;
    }

    /** J1 중 JOIN_WINDOW 만기가 왔음을 표시한다(닫힌 뒤 tick, BR7.1). */
    public void deferJoinTick() {
        joinTickDeferred = true;
    }

    // ---- 출력 보류 중 전사 모으기 (BR3.1 · BR3.2) ----

    /**
     * 판정 턴 중 출력 전사 조각을 모은다(음성은 호출자가 버린다). 2000자를 넘으면 자른다.
     *
     * @return 모았는가(턴이 열려 있고 새 말 전이면 참)
     */
    public boolean holdTranscript(String piece) {
        if (state == State.CLOSED || piece == null) {
            return false;
        }
        int room = JudgmentLiveRecord.MAX_HELD_CHARS - heldTranscript.length();
        if (room > 0) {
            heldTranscript.append(piece, 0, Math.min(room, piece.length()));
        }
        return true;
    }

    // ---- 사건 (실행기 스레드) ----

    /** 판정 결과가 일반 편지함에서 나왔다. 낡은 차례 id · 닫힌 턴 · 다른 상태의 결과는 버린다(BR5.2). */
    public void onJudgmentArrived(UUID turnId, JudgeOutcome outcome, Instant now) {
        if (!spec.turnId().equals(turnId) || state != State.AWAITING_RESULT) {
            host.onDropped("JUDGMENT_LATE_DROPPED");
            return;
        }
        resolve(outcome, now);
    }

    /** 시계가 만기됐다(차례 번호 확인은 실행기가 이미 했다). */
    public void onTimer(TimerKind kind, Instant now) {
        armedTimers.remove(kind);
        switch (kind) {
            case LIVE -> {
                if (state == State.AWAITING_RESULT) {
                    resolve(JudgeOutcome.abstained(AbstainReason.TIMEOUT, since(now), null, spec.promptVersion()), now);
                }
            }
            case READY -> {
                if (state == State.AWAITING_RESULT) {
                    resolve(JudgeOutcome.abstained(AbstainReason.RESPONSE_NOT_READY, since(now), null, spec.promptVersion()), now);
                }
            }
            case INSTRUCTION -> {
                if (state == State.INSTRUCTING) {
                    close(instructionAcked ? CloseReason.NEW_SPEECH_NOT_OBSERVED : CloseReason.ACK_MISSING, null, now, false);
                }
            }
        }
    }

    /** 지시 확인 사건({@code session.instructions.appended})이 왔다. */
    public void onInstructionAcked() {
        if (state == State.INSTRUCTING) {
            instructionAcked = true;
        }
    }

    /** 지시에 따른 새 말이 시작했다(출력 세대가 받는 단계로 바뀐 첫 조각, 설계 개정 A4). 보기는 복원하지 않는다. */
    public void onNewSpeechStarted(Instant now) {
        if (state == State.INSTRUCTING) {
            close(CloseReason.INSTRUCTION_STARTED, null, now, true);
        }
    }

    /** 출력 세대가 지시 응답 시한을 알렸다(ACK_MISSING · NEW_SPEECH_NOT_OBSERVED). 지시를 다시 내지 않고 닫는다. */
    public void onInstructionNotHeard(CloseReason reason, Instant now) {
        if (state == State.INSTRUCTING) {
            close(reason == CloseReason.ACK_MISSING ? CloseReason.ACK_MISSING : CloseReason.NEW_SPEECH_NOT_OBSERVED, null, now, false);
        }
    }

    /**
     * 아이 확정 전사가 왔다(설계 검토 R-04: 지시 단계까지 BR5.3을 넓힌다). 종료 표현이면 어느 단계든 STOPPED로 닫는다. 아니면 실행부 소유 턴은 판정 단계
     * · 지시 단계 모두 SUPERSEDED로 닫고, 확장 소유 턴은 판정 단계에서는 그대로 두고 확장에 넘기며 지시 단계에서는 SUPERSEDED로 닫는다.
     */
    public TranscriptAction onConfirmedTranscript(boolean stopExpression, Instant now) {
        if (state == State.CLOSED) {
            return TranscriptAction.NONE;
        }
        if (stopExpression) {
            closeWith(JudgmentOutcomeKind.STOPPED, CloseReason.STOPPED, now);
            return TranscriptAction.STOP_CLOSED;
        }
        if (state == State.AWAITING_RESULT && spec.owner() == JudgmentOwner.EXTENSION) {
            return TranscriptAction.PASS_TO_EXTENSION;
        }
        closeWith(JudgmentOutcomeKind.SUPERSEDED, CloseReason.SUPERSEDED, now);
        return TranscriptAction.REPROCESS;
    }

    /** 공급자 끊김 · 기술적 일시 정지(BR5.1). 결과를 쓰지 않고 FAULT_CANCELLED로 닫는다. */
    public void onFault(Instant now) {
        if (state != State.CLOSED) {
            closeWith(JudgmentOutcomeKind.FAULT_CANCELLED, CloseReason.FAULT, now);
        }
    }

    /** 세션 상태가 바뀌었다(설계 검토 R-15: 실행기의 상태 변경 훅이 부른다). 턴을 연 상태가 아니면 SESSION_EXITED로 닫는다(BR5.4). */
    public void onSessionStatus(TurnStatus current, Instant now) {
        if (state != State.CLOSED && current != spec.openedInStatus()) {
            closeWith(JudgmentOutcomeKind.SESSION_EXITED, CloseReason.SESSION_EXITED, now);
        }
    }

    // ---- 안쪽 ----

    private Duration since(Instant now) {
        Duration elapsed = Duration.between(openedAt, now);
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    /** 결과(또는 보류)를 지점별 분기에 넘기고 지시 단계 또는 닫힘으로 간다. */
    private void resolve(JudgeOutcome outcome, Instant now) {
        decidedOutcome = outcome;
        cancelRequest();
        Decision decision;
        try {
            decision = handler.decide(this, outcome);
        } catch (RuntimeException failed) {
            close(CloseReason.HANDLER_ERROR, null, now, false);
            throw failed;
        }
        if (state == State.CLOSED) {
            return;
        }
        if (decision == null || decision.instruction() == null) {
            close(CloseReason.RESULT_HANDLED, null, now, false);
            return;
        }
        enterInstructing(decision.instruction(), now);
    }

    private void enterInstructing(InstructionTemplates.NextAction action, Instant now) {
        state = State.INSTRUCTING;
        instruction = action;
        cancelTimer(TimerKind.READY);
        cancelTimer(TimerKind.LIVE);
        host.armTimer(TimerKind.INSTRUCTION, spec.responseReady());
        armedTimers.put(TimerKind.INSTRUCTION, true);
        boolean sent;
        try {
            sent = host.sendInstruction(spec.turnId(), action);
        } catch (RuntimeException failed) {
            close(CloseReason.INSTRUCTION_NOT_SENT, null, now, false);
            throw failed;
        }
        if (!sent) {
            close(CloseReason.INSTRUCTION_NOT_SENT, null, now, false);
        }
    }

    private void closeWith(JudgmentOutcomeKind kind, CloseReason reason, Instant now) {
        close(reason, JudgeOutcome.ended(kind, since(now), spec.promptVersion()), now, false);
    }

    private void close(CloseReason reason, JudgeOutcome override, Instant now, boolean responseStarted) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        for (TimerKind kind : TimerKind.values()) {
            cancelTimer(kind);
        }
        cancelRequest();
        JudgeOutcome outcome = override != null ? override
                : decidedOutcome != null ? decidedOutcome
                : JudgeOutcome.abstained(AbstainReason.PROVIDER_ERROR, since(now), null, spec.promptVersion());
        host.onClosed(new Closure(spec.turnId(), spec.point(), spec.trigger(), spec.owner(), spec.openedInStatus(),
                spec.inputTranscriptSeq(), outcome, reason, instruction, heldTranscript.toString(), responseStarted, joinTickDeferred));
    }

    private void cancelTimer(TimerKind kind) {
        if (armedTimers.remove(kind) != null) {
            host.cancelTimer(kind);
        }
    }

    private void cancelRequest() {
        CompletableFuture<JudgeOutcome> future = pending;
        pending = null;
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    @Override
    public String toString() {
        return "JudgmentTurn[turnId=" + spec.turnId() + ", point=" + spec.point() + ", state=" + state + "]";
    }
}
