package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.judgment.JudgmentTurn;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.session.SessionTimers.Kind;
import kr.mujoong.realtime.session.TransitionTable.Trigger;

import static kr.mujoong.realtime.session.SessionContext.START_PHASE;

/**
 * 세션 실행기의 시계 구역이다: 세션 시계 만기를 종류별로 나눠 처리하고, 시작 확인 · 닫기 확인 · 끊김 만기와 계정 삭제 강제 정지를 맡는다.
 *
 * <p>세션 실행기 스레드에서만 불리므로 잠금이 없다. 소유하는 필드가 없고 공유 상태는 {@link SessionContext}로 읽는다. 실행기에 남은 로직은
 * {@link Env}로 되돌려 부른다.
 */
final class SessionClocks {

    /** 시계 구역이 실행기에 남은 로직으로 되돌려 부르는 자리. 모두 실행기 스레드에서 불린다. */
    interface Env {
        void onOutputQuiet();

        void onInstructionWaitExpired();
        default void onPracticeOutputDrain() {}

        void onInputTranscriptQuiet();

        void onJudgmentTimer(JudgmentTurn.TimerKind kind);

        void onJudgmentExit();

        void onGuardrailCheckTimer();

        void onJoinPromptPlayed(Instant at);

        void onStopConfirmPlayed(Instant at);

        void redeliver();

        void onJoinWindow();

        void onStopConfirmWindow();
    }

    private final SessionContext ctx;
    private final IdleClock idle;
    private final EndingFlow ending;
    private final Env env;

    SessionClocks(SessionContext ctx, IdleClock idle, EndingFlow ending, Env env) {
        this.ctx = ctx;
        this.idle = idle;
        this.ending = ending;
        this.env = env;
    }

    void onTimer(SessionMailbox.TimerFired fired) {
        if (!ctx.timers().consume(fired)) {
            return;
        }
        runTimer(fired.kind());
    }

    void runTimer(Kind kind) {
        switch (kind) {
            case DEVICE_ACK -> onStartTimeout();
            case CLOSING_CONFIRM -> {
                if (ending.farewellPending()) {
                    // DECLINED 인사 상한 — CLOSE_TRANSPORT를 바로 보내고 정리
                    boolean ours = ending.deviceStillOurs();
                    if (ours) {
                        ctx.closing().closeNow(ending.closingDevice());
                    }
                    ending.finishFarewell(ours);
                } else {
                    onClosingTimeout();
                }
            }
            case DISCONNECT -> onDisconnectCheck();
            case SEGMENT_QUIET -> env.onOutputQuiet();
            case INSTRUCTION_WAIT -> env.onInstructionWaitExpired();
            case PRACTICE_OUTPUT_DRAIN -> env.onPracticeOutputDrain();
            case INPUT_TRANSCRIPT_QUIET -> env.onInputTranscriptQuiet();
            case JUDGMENT_LIVE -> env.onJudgmentTimer(JudgmentTurn.TimerKind.LIVE);
            case JUDGMENT_READY -> env.onJudgmentTimer(JudgmentTurn.TimerKind.READY);
            case JUDGMENT_INSTRUCTION -> env.onJudgmentTimer(JudgmentTurn.TimerKind.INSTRUCTION);
            case GUARDRAIL_CHECK -> env.onGuardrailCheckTimer();
            case PLAYBACK_EXPIRY -> {
                ctx.playback().clear();
                if (ctx.status() == SessionStatus.WAITING_CHILD && ctx.joinPromptPlaybackId() != null) {
                    // 끝 표식이 오지 않은 참여 안내는 출력 상한에서 끝난 것으로 본다(창이 영영 열리지 않는 일을 막는다)
                    ctx.joinPromptPlaybackId(null);
                    env.onJoinPromptPlayed(ctx.services().clock().now());
                }
                if (ctx.status() == SessionStatus.ACTIVE && ctx.stopConfirmPlaybackId() != null) {
                    ctx.stopConfirmPlaybackId(null);
                    env.onStopConfirmPlayed(ctx.services().clock().now());
                }
            }
            case REDELIVER -> env.redeliver();
            case JOIN_WINDOW -> env.onJoinWindow();
            case STOP_CONFIRM -> env.onStopConfirmWindow();
            case SILENCE -> idle.onSilenceTimer();
            case GAP_DISPLAY -> idle.onGapDisplayCheck();
            case RECORD_SETTLE -> ending.finishSettle();
        }
    }

    /**
     * U4b 계정 삭제 강제 정지: DB 전이 · 사건 · 스냅숏을 만들지 않는다. 원음 기록을 떼고(정리가 파일을 닫는다) 연결이 살아 있고
     * START_SESSION을 보냈으면 CLOSE_TRANSPORT(CONSENT_WITHDRAWN) + STANDBY를 보낸 뒤 정리한다(정산 창도 건너뛴다).
     */
    void onDeletionStop(SessionMailbox.DeletionStop stop) {
        try {
            env.onJudgmentExit();
            ctx.relay().tap(null);
            ctx.relay().setAccepting(false);
            DeviceLink current = ctx.link();
            if (ctx.startSent() && current != null && current.isOpen() && ending.deviceStillOurs()) {
                ctx.bumpGeneration();
                ctx.sendControl(new CloseTransport(CloseTransport.Reason.CONSENT_WITHDRAWN));
                ctx.sendControl(new SessionState(View.STANDBY));
            }
            ending.teardown();
        } finally {
            stop.stopped().complete(null);
        }
    }

    /** ②: 송신 시도 시각부터 20초. ACK · READY 빠짐 → DEVICE_NO_ACK + UNRESPONSIVE, 아니면 PROVIDER_UNAVAILABLE((h)). */
    private void onStartTimeout() {
        if (!START_PHASE.contains(ctx.status())) {
            return;
        }
        Duration threshold = ctx.services().settings().deviceAckTimeout();
        Duration elapsed = Duration.between(ctx.startAttemptAt(), ctx.services().clock().now());
        if (elapsed.compareTo(threshold) < 0) {
            ctx.timers().arm(Kind.DEVICE_ACK, threshold.minus(elapsed));
            return;
        }
        FailureCode code = TransitionTable.startTimeoutCode(ctx.ackReceived(), ctx.deviceReady());
        ActivitySessionRepository.MultiOriginTransition result = ctx.services().transitions().transitionFromAny(
                ctx.plan().sessionId(), Trigger.START_TIMEOUT.origins(), SessionStatus.FAILED,
                read -> TransitionTable.changeFor(Trigger.START_TIMEOUT, code, ctx.services().clock().now()),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
        if (!result.transitioned()) {
            ending.syncFromDb();
            return;
        }
        ending.changeStatus(SessionStatus.FAILED, ctx.services().clock().now());
        timeoutFired("DEVICE_ACK", threshold, elapsed);
        if (code == FailureCode.DEVICE_NO_ACK) {
            ctx.markUnresponsive();
        }
        ending.afterFailed();
    }

    /** ③: CLOSING 진입부터 30초. ENDED_UNCONFIRMED + UNRESPONSIVE. */
    private void onClosingTimeout() {
        if (ctx.status() != SessionStatus.CLOSING) {
            return;
        }
        Duration threshold = ctx.services().settings().closingConfirm();
        Duration elapsed = Duration.between(ctx.closingAt(), ctx.services().clock().now());
        if (elapsed.compareTo(threshold) < 0) {
            ctx.timers().arm(Kind.CLOSING_CONFIRM, threshold.minus(elapsed));
            return;
        }
        Instant now = ctx.services().clock().now();
        ending.closeProvider();
        // UNCONFIRMED로 덮고 처음 사유(와 세부)를 end_detail에 둔다
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(), SessionStatus.CLOSING,
                SessionStatus.ENDED_UNCONFIRMED,
                TransitionTable.closingTimeoutChange(ctx.closingReason(), ctx.closingDetail(), now).withProviderClosedAt(now),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
        if (row.isEmpty()) {
            ending.syncFromDb();
            return;
        }
        ending.changeStatus(SessionStatus.ENDED_UNCONFIRMED, ctx.services().clock().now());
        // CLOSING 진입부터 ended_at까지를 UNCONFIRMED 구간으로 남긴다
        ctx.quietly(() -> ctx.services().gaps().close(
                ctx.services().gaps().open(ctx.plan().sessionId(), SessionGapRepository.Kind.UNCONFIRMED, ctx.closingAt()), now));
        timeoutFired("CLOSING_CONFIRM", threshold, elapsed);
        ctx.markUnresponsive();
        ctx.bumpGeneration();
        ctx.sendControl(new SessionState(View.STANDBY));
        ending.afterTerminal();
    }

    /** ④: 묶인 연결의 마지막 기기 메시지부터 40초. 메시지마다 다시 걸지 않고 만기 때 남은 시간으로 다시 건다. */
    private void onDisconnectCheck() {
        // WAITING_CHILD까지 넓혔다. CLOSING에는 끊김 전이가 없다
        if (!Trigger.DISCONNECT.accepts(ctx.status())) {
            return;
        }
        Duration threshold = ctx.services().settings().disconnect();
        Instant last = ctx.link() == null ? ctx.startAttemptAt() : ctx.link().lastMessageAt();
        Duration elapsed = Duration.between(last, ctx.services().clock().now());
        if (elapsed.compareTo(threshold) < 0) {
            ctx.timers().arm(Kind.DISCONNECT, threshold.minus(elapsed));
            return;
        }
        Instant now = ctx.services().clock().now();
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(), ctx.status(),
                SessionStatus.ABORTED, TransitionTable.changeFor(Trigger.DISCONNECT, null, now).withProviderClosedAt(now),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId(), ending.transitionExtras());
        if (row.isEmpty()) {
            // 진 끊김 시계는 공급자를 닫지 않는다 — 행이 CLOSING이면 CLOSING이 끊김보다 우선하고(③) 공급자는
            // VOICE_SESSION_CLOSED · 30초 시한까지 열어 둔다(④)
            ending.syncFromDb();
            return;
        }
        ending.changeStatus(SessionStatus.ABORTED, ctx.services().clock().now());
        ending.closeProvider();
        timeoutFired("DISCONNECT", threshold, elapsed);
        ending.afterTerminal();
    }

    void armDisconnect() {
        Duration threshold = ctx.services().settings().disconnect();
        Instant last = ctx.link() == null ? ctx.services().clock().now() : ctx.link().lastMessageAt();
        Duration elapsed = Duration.between(last, ctx.services().clock().now());
        ctx.timers().arm(Kind.DISCONNECT, elapsed.isNegative() ? threshold : threshold.minus(elapsed));
        idle.armGapDisplay();
    }

    private void timeoutFired(String type, Duration threshold, Duration elapsed) {
        ctx.services().metrics().timeoutFired(type);
        ctx.services().events().log(EventCode.TIMEOUT_FIRED, EventLog.Component.SESSION_LIFECYCLE, ctx.plan().sessionId(),
                ctx.plan().traceId(), entry -> entry.attr(AttrKey.TYPE, type).attr(AttrKey.THRESHOLD_MS, threshold.toMillis())
                        .attr(AttrKey.ELAPSED_MS, Math.max(0L, elapsed.toMillis())));
    }
}
