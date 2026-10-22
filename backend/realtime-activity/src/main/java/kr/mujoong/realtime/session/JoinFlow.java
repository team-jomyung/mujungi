package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.realtime.dialog.JoinTracker;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.session.SessionTimers.Kind;
import kr.mujoong.realtime.session.TransitionTable.Trigger;

import static kr.mujoong.realtime.session.SessionExecutor.DECLINE_ALTERNATIVE;
import static kr.mujoong.realtime.session.SessionExecutor.INTRO_FREE_TALK;
import static kr.mujoong.realtime.session.SessionExecutor.INTRO_PRACTICE;
import static kr.mujoong.realtime.session.SessionExecutor.RE_PROMPT;

/**
 * 세션 실행기의 참여 확인 구역이다: WAITING_CHILD에서 참여 안내를 재생하고, 아이 대답을 분류해 수락(ACTIVE) · 재안내 · 대안 제시 · 거절(DECLINED)로
 * 옮기며, 참여 창 시계 만기를 처리한다. 참여 판단 객체({@link JoinTracker})를 이 클래스가 쥔다.
 *
 * <p>세션 실행기 스레드에서만 불리므로 잠금이 없다. 공유 상태는 {@link SessionContext}로 읽고 쓰고, 불명확한 대답의 판정 턴은
 * {@link JudgmentCoordinator}가 열며, 거절 뒤 마무리 인사는 {@link EndingFlow}가 맡는다. 실행기에 남은 로직은 {@link Env}로 되돌려 부른다.
 */
final class JoinFlow {

    /** U4b 세션 사건 종류(payload는 코드 · 횟수 · 분류 이름 · 시각뿐, entities SessionEvent). */
    static final String EVENT_JOIN_PROMPTED = "JOIN_PROMPTED";
    static final String EVENT_JOIN_RE_PROMPTED = "JOIN_RE_PROMPTED";
    static final String EVENT_JOIN_ALTERNATIVE_OFFERED = "JOIN_ALTERNATIVE_OFFERED";
    static final String EVENT_JOIN_ACCEPTED = "JOIN_ACCEPTED";
    static final String EVENT_JOIN_DECLINED = "JOIN_DECLINED";

    /** 참여 확인 구역이 실행기에 남은 로직으로 되돌려 부르는 자리. 모두 실행기 스레드에서 불린다. */
    interface Env {
        void reportMissing(String contentCode, Instant at);

        void approvedBubble(SessionExecutor.ApprovedItem item, Instant at);

        /** ACTIVE 전이 승자의 뒷일: 진입 몫과 발언권 반환(응답 생성 요청 1회). */
        void afterActiveWon();

        void participationWindowOpened();

        /**
         * 참여 수락을 일으킨 아이 발화를 대화 행으로 남긴다. 참여 확인 중의 아이 말은 분류에만 쓰고 저장하지 않지만, 수락된 발화는 그 뒤 대화의
         * 첫 아이 말이라 수락 때 한 번 남긴다(거절 · 무응답으로 끝나면 남기지 않는다).
         */
        void acceptedReply(String transcript, Instant at);
    }

    private final SessionContext ctx;
    private final IdleClock idle;
    private final JudgmentCoordinator judgment;
    private final EndingFlow ending;
    private final Env env;

    /** U4b 참여 판단(WAITING_CHILD에 들어갈 때 만든다). */
    private JoinTracker join;
    private boolean noticeDelivered;
    /** 가장 최근에 분류하러 받은 참여 대답의 전사(수락되면 대화 행으로 남긴다, 원문은 여기에만 잠깐 둔다). */
    private String lastReplyTranscript;
    private boolean currentIncludesQuestion;

    JoinFlow(SessionContext ctx, IdleClock idle, JudgmentCoordinator judgment, EndingFlow ending, Env env) {
        this.ctx = ctx;
        this.idle = idle;
        this.judgment = judgment;
        this.ending = ending;
        this.env = env;
    }

    /** 참여 판단이 있는가(기술 일시정지가 끝났을 때 참여 창을 밀어 줄 대상이 있는지 본다). */
    boolean hasJoin() {
        return join != null;
    }

    /** WAITING_CHILD 진입 몫의 끝: 참여 판단을 만들고 시작 안내(안내 1번째)를 재생한다. */
    void begin() {
        join = new JoinTracker(lifecycle().joinWait(), lifecycle().joinWaitAfterReprompt());
        ctx.putEvent(EVENT_JOIN_PROMPTED, "{\"prompts\":1}", ctx.services().clock().now());
        playJoinPrompt(ctx.plan().activityType() == ActivityType.PRACTICE ? INTRO_PRACTICE : INTRO_FREE_TALK);
    }

    /** 기술 일시정지가 끝났다: 멈춘 시간만큼 참여 창을 밀고 시계를 다시 건다. */
    void resumeJoinWindow(Duration paused) {
        join.shift(paused);
        armJoinWindow();
    }

    /**
     * 참여 안내 하나를 재생한다. 보낸 순간 말풍선 행을 남기고 그 끝 표식이 창을 연다. 패키지에 없으면 재생만 건너뛰고
     * APPROVED_CONTENT_MISSING을 코드별 1회 남긴 뒤 곧바로 재생이 끝난 것으로 본다(시계 · 횟수는 그대로). 보내지 못해도 끝난 것으로 본다.
     */
    private void playJoinPrompt(String contentCode) {
        if (freeTalk()) {
            playVerifiedPrompt(contentCode);
            return;
        }
        Instant now = ctx.services().clock().now();
        Optional<SessionExecutor.ApprovedItem> item = ctx.plan().approvedItem(contentCode);
        if (item.isEmpty()) {
            env.reportMissing(contentCode, now);
        } else {
            UUID playbackId = UUID.randomUUID();
            if (ctx.sendControl(new PlayApprovedContent(playbackId, contentCode, item.get().contentVersionId()))) {
                // 안내를 재생하는 동안 아이 소리는 공급자에 들려주지 않는다(끝 표식이 풀어 준다)
                ctx.relay().setMuted(true);
                ctx.joinPromptPlaybackId(playbackId);
                ctx.playback().started(playbackId, contentCode);
                ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
                env.approvedBubble(item.get(), now);
                return;
            }
        }
        ctx.joinPromptPlaybackId(null);
        onJoinPromptPlayed(now);
    }

    /** 참여 안내 재생이 끝났다(서버가 끝 표식을 받은 시각): 창을 연다. */
    void onJoinPromptPlayed(Instant at) {
        ctx.relay().setMuted(false);
        if (freeTalk()) {
            failNotice(FailureCode.DEVICE_NO_ACK);
            return;
        }
        if (ctx.status() != SessionStatus.WAITING_CHILD || join == null || join.isFinished()) {
            return;
        }
        join.promptPlayed(at);
        armJoinWindow();
    }

    private void armJoinWindow() {
        Instant end = join == null ? null : join.windowEnd();
        if (end == null || idle.technicalPause() != null) {
            return;
        }
        Duration left = Duration.between(ctx.services().clock().now(), end);
        ctx.timers().arm(Kind.JOIN_WINDOW, left.isNegative() ? Duration.ZERO : left);
    }

    /** 참여 창 시계 만기: 결정표의 무응답 칸. 창이 밀렸으면(일시정지) 남은 시간으로 다시 건다. */
    void onJoinWindow() {
        if (ctx.status() != SessionStatus.WAITING_CHILD || join == null || join.isFinished()) {
            return;
        }
        if (judgment.openPoint() == JudgmentPoint.J1) {
            // U11 W1 6: J1이 열린 동안의 창 만기는 표시만 하고 턴이 닫힌 뒤 tick을 부른다
            judgment.deferJoinTick();
            return;
        }
        JoinTracker.Action action = join.tick(ctx.services().clock().now());
        if (action == JoinTracker.Action.NONE) {
            armJoinWindow();
            return;
        }
        applyJoinAction(action, null, ctx.services().clock().now());
    }

    /**
     * WAITING_CHILD의 아이 확정 전사: 저장하지 않고 분류에만 쓴다. 안내를 재생하는 중의 대답도 곧바로 분류한다(5354a32
     * JoinTracker 동작).
     */
    void onJoinReply(String transcript, Instant at) {
        onJoinReply(transcript, at, at);
    }

    void onJoinReply(String transcript, Instant at, Instant beganAt) {
        if (join == null || join.isFinished()) {
            return;
        }
        if (freeTalk() && !currentInput(beganAt)) return;
        lastReplyTranscript = transcript;
        // J1이 열린 동안 새 확정 전사가 오면 이전 J1을 SUPERSEDED로 닫고 새 전사를 처음부터 다시 처리한다
        judgment.onTranscript(false);
        JoinTracker.Reply reply = JoinTracker.classify(transcript);
        if (freeTalk() && reply == JoinTracker.Reply.TOPIC_REFUSAL) {
            reply = JoinTracker.Reply.UNCLEAR;
        }
        if (freeTalk() && reply == JoinTracker.Reply.REFUSE) {
            declineJoin(SessionGuardrailFacts.Participation.REFUSED);
            return;
        }
        if (reply == JoinTracker.Reply.UNCLEAR && judgment.openJoinJudgment(transcript, at)) {
            return;
        }
        if (freeTalk() && reply != JoinTracker.Reply.REFUSE) {
            // 자유대화의 시작 의사는 '거절인가'만 본다: 거절이 아니면(판정을 열 수 없는 불명확 대답 포함) 대화를 시작한다
            reply = JoinTracker.Reply.ACCEPT;
        }
        applyJoinAction(join.reply(reply, at), reply, at);
    }

    /** 결과를 참여 대답 하나로 넣는다(참여 대기 중이 아니면 아무것도 하지 않는다). */
    void applyJoinReply(JoinTracker.Reply reply, Instant at) {
        if (ctx.status() == SessionStatus.WAITING_CHILD && join != null && !join.isFinished()) {
            if (freeTalk() && !currentInput(at)) return;
            if (freeTalk() && reply == JoinTracker.Reply.REFUSE) {
                declineJoin(SessionGuardrailFacts.Participation.REFUSED);
                return;
            }
            applyJoinAction(join.reply(reply, at), reply, at);
        }
    }

    /** J1이 열린 동안 미뤄 둔 참여 창 만기를 처리한다(참여 대기 중이고 기술 일시정지가 아닐 때만). */
    void runDeferredJoinTick() {
        if (ctx.status() == SessionStatus.WAITING_CHILD && join != null && !join.isFinished()
                && idle.technicalPause() == null) {
            onJoinWindow();
        }
    }

    private void applyJoinAction(JoinTracker.Action action, JoinTracker.Reply reply, Instant at) {
        switch (action) {
            case START -> acceptJoin();
            case REPROMPT, CLARIFY -> {
                ctx.timers().cancel(Kind.JOIN_WINDOW);
                ctx.putEvent(EVENT_JOIN_RE_PROMPTED, "{\"prompts\":" + join.prompts() + ",\"cause\":\""
                        + (action == JoinTracker.Action.REPROMPT ? "NO_REPLY" : "UNCLEAR") + "\"}", at);
                playJoinPrompt(RE_PROMPT);
            }
            case OFFER_ALTERNATIVE -> {
                ctx.timers().cancel(Kind.JOIN_WINDOW);
                ctx.putEvent(EVENT_JOIN_ALTERNATIVE_OFFERED, "{\"prompts\":" + join.prompts() + ",\"reply\":\""
                        + (reply == null ? "NONE" : reply.name()) + "\"}", at);
                playJoinPrompt(DECLINE_ALTERNATIVE);
            }
            case DECLINE -> declineJoin(SessionGuardrailFacts.Participation.NO_RESPONSE);
            case NONE -> {
                // 안내 없이 같은 창을 계속 기다린다(재안내를 이미 쓴 불명확 대답)
            }
        }
    }

    /** 참여 수락 → ACTIVE: started_at, 원음 기록 시작, 응답 생성 요청 1회. */
    private void acceptJoin() {
        ctx.relay().setMuted(false);
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        Instant now = ctx.services().clock().now();
        SessionGuardrailFacts proposed = freeTalk() ? nextFacts(SessionGuardrailFacts.Participation.ACCEPTED) : null;
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(),
                SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE, SessionChange.none().withStartedAt(now),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId(), new SessionTransitions.Extras(null, false, proposed));
        if (row.isEmpty()) {
            ending.syncFromDb();
            return;
        }
        if (proposed != null) ctx.guardrailFacts(proposed);
        ending.changeStatus(SessionStatus.ACTIVE, ctx.services().clock().now());
        ctx.putEvent(EVENT_JOIN_ACCEPTED, "{\"prompts\":" + (join == null ? 1 : join.prompts()) + "}", now);
        if (freeTalk() && lastReplyTranscript != null) {
            env.acceptedReply(lastReplyTranscript, now);
        }
        lastReplyTranscript = null;
        env.afterActiveWon();
    }

    /**
     * 참여 거절 → DECLINED: 기록기의 대기 칸을 버린 뒤 전이 트랜잭션에서 그 세션의 전사 행을 지우고, 마무리 인사를 closing-confirm
     * 상한 안에서 돌린다(말풍선 행 없음).
     */
    private void declineJoin(SessionGuardrailFacts.Participation disposition) {
        ctx.relay().setMuted(false);
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        ctx.services().recorder().discardTranscripts(ctx.plan().sessionId(), ctx.services().settings().timerTick());
        Instant now = ctx.services().clock().now();
        SessionGuardrailFacts proposed = freeTalk() ? nextFacts(disposition) : null;
        if (proposed != null) proposed = new SessionGuardrailFacts(1, proposed.sessionId(), proposed.revision(), proposed.statusVersion(),
                proposed.notice(), proposed.participation(), ending.closingVersion() == null
                        ? SessionGuardrailFacts.Goodbye.NONE : SessionGuardrailFacts.Goodbye.PENDING, null, now);
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(),
                SessionStatus.WAITING_CHILD, SessionStatus.DECLINED,
                TransitionTable.changeFor(Trigger.JOIN_DECLINED, null, now), SessionTransitions.Origin.REALTIME,
                ctx.plan().traceId(), new SessionTransitions.Extras(null, true, proposed));
        if (row.isEmpty()) {
            ending.syncFromDb();
            return;
        }
        if (proposed != null) ctx.guardrailFacts(proposed);
        ending.changeStatus(SessionStatus.DECLINED, ctx.services().clock().now());
        ctx.putEvent(EVENT_JOIN_DECLINED, "{\"prompts\":" + (join == null ? 1 : join.prompts()) + "}", now);
        ending.startFarewell();
    }

    private SessionExecutor.Settings.Lifecycle lifecycle() {
        return ctx.services().settings().lifecycle();
    }

    private boolean freeTalk() { return ctx.plan().activityType() == ActivityType.FREE_TALK; }

    private boolean currentInput(Instant beganAt) {
        return join.phase() == JoinTracker.Phase.WAITING && ctx.noticeDelivery() != null
                && ctx.link() != null && ctx.noticeDelivery().boundTo(ctx.plan().sessionId(), ctx.link().connectionId())
                && ctx.noticeDelivery().permitsInput(beganAt);
    }

    private SessionGuardrailFacts nextFacts(SessionGuardrailFacts.Participation participation) {
        ActivitySessionRow row = ctx.services().transitions().find(ctx.plan().sessionId()).orElseThrow();
        return ctx.proposeFacts(row.statusVersion() + 1, SessionGuardrailFacts.Notice.COMPLETED, participation);
    }

    private void playVerifiedPrompt(String code) {
        var item = ctx.plan().approvedItem(code);
        var asset = item.flatMap(value -> ctx.services().notices().verified(value, ctx.plan().approvedAudio(),
                ctx.servicePolicy().policyVersion(), ctx.services().settings().playbackExpiry()));
        if (asset.isEmpty() || ctx.link() == null || !ctx.link().isOpen()) {
            failNotice(FailureCode.PROVIDER_UNAVAILABLE);
            return;
        }
        if (ctx.noticeDelivery() != null) ctx.noticeDelivery().invalidate();
        Instant now = ctx.services().clock().now();
        UUID playback = UUID.randomUUID();
        ctx.noticeDelivery(new NoticeDelivery(ctx.plan().sessionId(), ctx.link().connectionId(), playback,
                ctx.servicePolicy().promptVersion(), ctx.servicePolicy().policyVersion(),
                now.plus(ctx.services().settings().playbackExpiry())));
        currentIncludesQuestion = asset.get().includesParticipationQuestion();
        if (!ctx.recordFacts(noticeDelivered ? SessionGuardrailFacts.Notice.COMPLETED : SessionGuardrailFacts.Notice.PENDING,
                SessionGuardrailFacts.Participation.UNKNOWN)) {
            ending.syncFromDb();
            return;
        }
        ctx.joinPromptPlaybackId(playback);
        if (!ctx.sendControl(new PlayApprovedContent(playback, code, item.orElseThrow().contentVersionId()))) {
            failNotice(FailureCode.DEVICE_NO_ACK);
            return;
        }
        // 안내를 재생하는 동안 아이 소리는 공급자에 들려주지 않는다: 말해도 안내 도중에는 전사되지 않고, 안내를 따라 마이크에 새어 든
        // 소리가 참여 대답으로 섞이지 않는다. 끝 표식(COMPLETED)이 풀어 준다
        ctx.relay().setMuted(true);
        ctx.playback().started(playback, code);
        ctx.timers().arm(Kind.PLAYBACK_EXPIRY, ctx.services().settings().playbackExpiry());
        env.approvedBubble(item.orElseThrow(), now);
    }

    /** 자유대화 안내 표식만 가로채며 연습 재생은 기존 처리에 남긴다. */
    boolean onPlaybackMark(SessionMailbox.PlaybackMarkReceived received) {
        NoticeDelivery delivery = ctx.noticeDelivery();
        if (!freeTalk() || delivery == null || ctx.status() != SessionStatus.WAITING_CHILD
                || !delivery.playbackId().equals(received.mark().playbackId())) return false;
        if (!ctx.fromBoundConnection(received.connectionId())) return true;
        boolean completed = delivery.mark(ctx.plan().sessionId(), received.connectionId(), received.mark().playbackId(),
                received.mark().status(), received.serverRecvAt());
        if (completed) {
            ctx.relay().setMuted(false);
            ctx.timers().cancel(Kind.PLAYBACK_EXPIRY);
            ctx.playback().clearIfMatches(delivery.playbackId());
            ctx.joinPromptPlaybackId(null);
            noticeDelivered = true;
            if (!ctx.recordFacts(SessionGuardrailFacts.Notice.COMPLETED, SessionGuardrailFacts.Participation.PENDING)) {
                ending.syncFromDb();
                return true;
            }
            env.participationWindowOpened();
            if (!currentIncludesQuestion) {
                playJoinPrompt(RE_PROMPT);
                return true;
            }
            join.promptPlayed(received.serverRecvAt());
            armJoinWindow();
        } else if (delivery.state() == NoticeDelivery.State.FAILED || delivery.state() == NoticeDelivery.State.INVALIDATED) {
            failNotice(FailureCode.DEVICE_NO_ACK);
        }
        return true;
    }

    private void failNotice(FailureCode code) {
        if (ctx.status() != SessionStatus.WAITING_CHILD) return;
        ctx.relay().setMuted(false);
        if (ctx.noticeDelivery() != null) ctx.noticeDelivery().invalidate();
        ctx.joinPromptPlaybackId(null);
        ctx.timers().cancel(Kind.PLAYBACK_EXPIRY);
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        ActivitySessionRow current = ctx.services().transitions().find(ctx.plan().sessionId()).orElseThrow();
        var proposed = ctx.proposeFacts(current.statusVersion() + 1, SessionGuardrailFacts.Notice.FAILED,
                SessionGuardrailFacts.Participation.UNKNOWN);
        var changed = ctx.services().transitions().transition(ctx.plan().sessionId(), SessionStatus.WAITING_CHILD,
                SessionStatus.FAILED, SessionChange.failed(code, ctx.services().clock().now()),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId(), new SessionTransitions.Extras(null, false, proposed));
        if (changed.isPresent()) {
            ctx.guardrailFacts(proposed);
            ending.afterFailed();
        } else ending.syncFromDb();
    }
}
