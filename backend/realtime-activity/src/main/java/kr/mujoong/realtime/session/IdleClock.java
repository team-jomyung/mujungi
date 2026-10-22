package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.realtime.dialog.SilenceClock;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.session.SessionTimers.Kind;
import kr.mujoong.realtime.session.TransitionTable.Trigger;

/**
 * 세션 실행기의 공백 시계 · 기술 일시정지 · 진행 조회 구역이다: 아이가 말이 없는 시간을 재서 경고 · 종료를 걸고, 기기 메시지가 끊기면 기술
 * 일시정지를 켜고 끄며, 진행 조회가 읽는 살아 있는 값을 바꿔 쓴다.
 *
 * <p>세션 실행기 스레드에서만 바꿔 쓰므로 잠금이 없다. 다른 스레드(조회)가 읽는 {@code live}만 원자 참조로 둔다. 공유 상태는
 * {@link SessionContext}로 읽고 쓰고, 실행기에 남은 로직(연습 확장점 · 참여 창 · 판정 · 출력)은 {@link Env}로 되돌려 부른다.
 */
public final class IdleClock {

    static final String EVENT_SILENCE_WARNED = "SILENCE_WARNED";
    static final String EVENT_SILENCE_RESET = "SILENCE_RESET";
    static final String EVENT_TECHNICAL_PAUSE_STARTED = "TECHNICAL_PAUSE_STARTED";
    static final String EVENT_TECHNICAL_PAUSE_ENDED = "TECHNICAL_PAUSE_ENDED";

    /**
     * 진행 조회가 읽는 살아 있는 값(WF10): 기술 일시정지 · 공백 경고. 실행기 스레드가 바뀔 때마다 불변 값 하나로 바꿔 쓰고 조회는 읽기만
     * 한다. 종료 세션은 둘 다 null이다.
     */
    public record LiveState(SessionStateChanged.TechnicalPause technicalPause, SilenceClock.Warning silenceWarning,
            PracticeSessionExtension.Progress practice) {

        public LiveState(SessionStateChanged.TechnicalPause pause, SilenceClock.Warning warning) { this(pause, warning, null); }

        static final LiveState NONE = new LiveState(null, null);
    }

    /** 공백 시계 구역이 실행기에 남은 로직으로 되돌려 부르는 자리. 모두 실행기 스레드에서 불린다. */
    interface Env {
        void reportMissing(String contentCode, Instant at);

        void approvedBubble(SessionExecutor.ApprovedItem item, Instant at);

        boolean beginClosing(Trigger trigger, UUID requestId);

        PracticeSessionExtension.Progress practiceProgress();

        boolean practiceControlsOutput();

        void judgmentOnFault();

        void pausePracticeTimers(Instant now);

        void resumePracticeTimers(Instant now);

        void interruptPracticeSpeech(Instant now);

        void stopOutputForIntent(Instant at);

        boolean hasJoin();

        void resumeJoinWindow(Duration paused);
    }

    private final SessionContext ctx;
    private final Env env;

    /** U4b 공백 시계(ACTIVE에서만). */
    private final SilenceClock silence;
    /** U4b 켜진 기술 일시정지(없으면 null). */
    private SessionStateChanged.TechnicalPause technicalPause;
    /** U4b 기술 일시정지를 켠 서버 시각(창 · 시계를 멈춘 시간 계산). */
    private Instant pauseStartedAt;
    /** U4b 진행 조회가 읽는 살아 있는 값(실행기 스레드만 바꿔 쓴다). */
    private final java.util.concurrent.atomic.AtomicReference<LiveState> live =
            new java.util.concurrent.atomic.AtomicReference<>(LiveState.NONE);

    IdleClock(SessionContext ctx, Env env) {
        this.ctx = ctx;
        this.env = env;
        SessionExecutor.Settings.Lifecycle lifecycle = ctx.services().settings().lifecycle();
        this.silence = new SilenceClock(lifecycle.silenceWarning(), lifecycle.silenceEnd());
    }

    /** 진행 조회 값(다른 스레드에서 읽는다). */
    LiveState liveState() {
        return ctx.status().isTerminal() ? LiveState.NONE : live.get();
    }

    /** 켜진 기술 일시정지(없으면 null). */
    SessionStateChanged.TechnicalPause technicalPause() {
        return technicalPause;
    }

    /**
     * 메시지 하나를 처리한 뒤: 공백 시계의 멈춤 조건을 다시 보고 다음 판정 시각에 시계를 다시 걸며, 진행 조회 값을 바꿔 쓴다. 상태
     * 변화는 모두 메시지 처리 안에서만 일어나므로 메시지 경계에서 보면 충분하다.
     */
    void afterMessage() {
        if (ctx.terminated()) {
            live.set(LiveState.NONE);
            return;
        }
        Instant now = ctx.services().clock().now();
        if (ctx.status() == SessionStatus.ACTIVE) {
            silence.setPaused(silencePaused(), now);
            armSilence(now);
        } else if (ctx.timers().isArmed(Kind.SILENCE)) {
            ctx.timers().cancel(Kind.SILENCE);
        }
        publishLive(now);
    }

    /** 멈춤 조건: 기기 재생 중 · 서버 출력 대기(열린 구간 · 게이트 대기 · 대기 문구) · 기술 일시정지. */
    private boolean silencePaused() {
        return !ctx.devicePlaying().isEmpty() || ctx.playback().isPlaying() || ctx.segment().isOpen() || ctx.transcripts().hasOpenAi()
                || technicalPause != null;
    }

    private void armSilence(Instant now) {
        Instant deadline = silence.nextDeadline(now);
        if (deadline == null) {
            if (ctx.timers().isArmed(Kind.SILENCE)) {
                ctx.timers().cancel(Kind.SILENCE);
            }
            return;
        }
        if (ctx.timers().isArmed(Kind.SILENCE) && deadline.equals(ctx.timers().deadline(Kind.SILENCE))) {
            return;
        }
        Duration left = Duration.between(now, deadline);
        ctx.timers().arm(Kind.SILENCE, left.isNegative() ? Duration.ZERO : left);
    }

    /** 공백 시계 만기: 누적 4분이면 경고 1회, 6분이면 CLOSING(SILENCE). */
    void onSilenceTimer() {
        if (ctx.status() != SessionStatus.ACTIVE) {
            return;
        }
        Instant now = ctx.services().clock().now();
        switch (silence.tick(now)) {
            case WARN -> onSilenceWarning(now);
            case END -> env.beginClosing(Trigger.SILENCE_TIMEOUT, null);
            case NONE -> {
                // 멈췄다 이어진 만큼 늦게 다시 건다(afterMessage)
            }
        }
    }

    /** 공백 경고를 켜고 패키지에 있으면 SILENCE_WARNING을 한 번 재생한다(재생은 시계를 리셋하지 않는다). */
    private void onSilenceWarning(Instant now) {
        ctx.putEvent(EVENT_SILENCE_WARNED, "{\"silentMs\":" + silence.accumulated(now).toMillis() + "}", now);
        Optional<SessionExecutor.ApprovedItem> item = ctx.plan().approvedItem(SessionExecutor.SILENCE_WARNING);
        if (item.isEmpty()) {
            env.reportMissing(SessionExecutor.SILENCE_WARNING, now);
            return;
        }
        if (ctx.playback().isPlaying()) {
            return;
        }
        UUID playbackId = UUID.randomUUID();
        if (ctx.sendControl(new PlayApprovedContent(playbackId, SessionExecutor.SILENCE_WARNING, item.get().contentVersionId()))) {
            ctx.playback().started(playbackId, SessionExecutor.SILENCE_WARNING);
            ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
            env.approvedBubble(item.get(), now);
        }
    }

    /** ACTIVE 진입 · 아이의 빈 아닌 확정 전사가 공백을 0으로 한다. 경고를 껐으면 SILENCE_RESET을 남긴다. */
    void resetSilence(Instant at) {
        boolean wasWarned = silence.isWarned();
        silence.reset(at);
        if (wasWarned) {
            ctx.putEvent(EVENT_SILENCE_RESET, null, at);
        }
    }

    /** 진행 조회 값을 바꿔 쓴다. 공백 경고는 ACTIVE에서만 있다. */
    void publishLive(Instant now) {
        SilenceClock.Warning warning = ctx.status() == SessionStatus.ACTIVE ? silence.warning(now) : null;
        PracticeSessionExtension.Progress progress = env.practiceProgress();
        live.set(technicalPause == null && warning == null && progress == null ? LiveState.NONE
                : new LiveState(technicalPause, warning, progress));
    }

    /** 이 세션의 끊김 시계 기준(묶인 연결의 마지막 기기 메시지 수신 시각). */
    Instant lastDeviceMessageAt() {
        DeviceLink current = ctx.link();
        return current == null ? ctx.startAttemptAt() : current.lastMessageAt();
    }

    /** 마지막 기기 메시지 뒤 gap-display에 기술 일시정지를 켜는 시계(WAITING_CHILD · ACTIVE). */
    void armGapDisplay() {
        if (!Trigger.DISCONNECT.accepts(ctx.status()) || technicalPause != null) {
            return;
        }
        Duration threshold = ctx.services().settings().lifecycle().gapDisplay();
        Duration elapsed = Duration.between(lastDeviceMessageAt(), ctx.services().clock().now());
        ctx.timers().arm(Kind.GAP_DISPLAY, elapsed.isNegative() ? threshold : threshold.minus(elapsed));
    }

    /**
     * 점선 시계 만기. 일시정지 중이면 회복을 본다 — HEARTBEAT는 편지함을 거치지 않으므로 timer-tick마다 마지막 메시지 시각을 본다.
     */
    void onGapDisplayCheck() {
        Instant now = ctx.services().clock().now();
        if (technicalPause != null) {
            DeviceLink current = ctx.link();
            if (current != null && current.isOpen() && current.lastMessageAt().isAfter(technicalPause.since())) {
                endPause(now);
            } else {
                ctx.timers().arm(Kind.GAP_DISPLAY, ctx.services().settings().timerTick());
            }
            return;
        }
        if (!Trigger.DISCONNECT.accepts(ctx.status())) {
            return;
        }
        Instant last = lastDeviceMessageAt();
        Duration threshold = ctx.services().settings().lifecycle().gapDisplay();
        Duration elapsed = Duration.between(last, now);
        if (elapsed.compareTo(threshold) < 0) {
            ctx.timers().arm(Kind.GAP_DISPLAY, threshold.minus(elapsed));
            return;
        }
        startPause(last, last);
    }

    /**
     * 기술 일시정지를 켠다: technicalPause{DEVICE, since}, 끊김 구간(U3와 같은 행), 참여 창 · 확인 질문 창 · 공백 시계 멈춤.
     *
     * @param since 마지막 기기 메시지 시각
     * @param gapFrom 끊김 구간을 새로 열 때의 시작 시각
     */
    void startPause(Instant since, Instant gapFrom) {
        if (technicalPause != null || !Trigger.DISCONNECT.accepts(ctx.status())) {
            return;
        }
        Instant now = ctx.services().clock().now();
        technicalPause = new SessionStateChanged.TechnicalPause(SessionStateChanged.TechnicalPause.Kind.DEVICE, since);
        pauseStartedAt = now;
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        env.judgmentOnFault();
        env.pausePracticeTimers(now);
        if (env.practiceControlsOutput()) {
            env.interruptPracticeSpeech(now);
            env.stopOutputForIntent(now);
        }
        if (ctx.openGapId() == null) {
            ctx.openGapId(ctx.services().gaps().open(ctx.plan().sessionId(), SessionGapRepository.Kind.DISCONNECT, gapFrom));
        }
        ctx.putEvent(EVENT_TECHNICAL_PAUSE_STARTED, "{\"kind\":\"DEVICE\"}", now);
        ctx.timers().arm(Kind.GAP_DISPLAY, ctx.services().settings().timerTick());
    }

    /** 기기 메시지가 다시 왔거나 다시 묶였다: 구간을 닫고 일시정지를 끈 뒤 멈췄던 창을 남은 시간부터 잇는다. */
    void endPause(Instant now) {
        if (technicalPause == null) {
            return;
        }
        Duration paused = pauseStartedAt == null ? Duration.ZERO : Duration.between(pauseStartedAt, now);
        technicalPause = null;
        pauseStartedAt = null;
        if (paused.isNegative()) {
            paused = Duration.ZERO;
        }
        if (ctx.openGapId() != null) {
            ctx.services().gaps().close(ctx.openGapId(), now);
            ctx.openGapId(null);
        }
        ctx.putEvent(EVENT_TECHNICAL_PAUSE_ENDED, "{\"pausedMs\":" + paused.toMillis() + "}", now);
        if (ctx.status() == SessionStatus.WAITING_CHILD && env.hasJoin()) {
            env.resumeJoinWindow(paused);
        }
        env.resumePracticeTimers(now);
        armGapDisplay();
    }

    /** 묶인 연결에서 기기 메시지가 왔다. */
    void deviceHeard() {
        if (technicalPause != null) {
            endPause(ctx.services().clock().now());
        }
    }
}
