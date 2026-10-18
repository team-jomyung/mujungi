package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsPayload;
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

    /** U4b 세션 사건 종류(payload는 코드 · 횟수 · 분류 이름 · 시각뿐, BR12.1 · entities SessionEvent). */
    static final String EVENT_JOIN_PROMPTED = "JOIN_PROMPTED";
    static final String EVENT_JOIN_RE_PROMPTED = "JOIN_RE_PROMPTED";
    static final String EVENT_JOIN_ALTERNATIVE_OFFERED = "JOIN_ALTERNATIVE_OFFERED";
    static final String EVENT_JOIN_ACCEPTED = "JOIN_ACCEPTED";
    static final String EVENT_JOIN_DECLINED = "JOIN_DECLINED";

    /** 참여 확인 구역이 실행기에 남은 로직으로 되돌려 부르는 자리. 모두 실행기 스레드에서 불린다. */
    interface Env {
        void putEvent(String type, String payloadJson, Instant at);

        boolean sendControl(WsPayload payload);

        void reportMissing(String contentCode, Instant at);

        void approvedBubble(SessionExecutor.ApprovedItem item, Instant at);

        void changeStatus(SessionStatus next, Instant now);

        void syncFromDb();

        /** ACTIVE 전이 승자의 뒷일: 진입 몫과 발언권 반환(응답 생성 요청 1회, BR2.6). */
        void afterActiveWon();
    }

    private final SessionContext ctx;
    private final IdleClock idle;
    private final JudgmentCoordinator judgment;
    private final EndingFlow ending;
    private final Env env;

    /** U4b 참여 판단(WAITING_CHILD에 들어갈 때 만든다, BR2.2 ~ BR2.4). */
    private JoinTracker join;

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
        env.putEvent(EVENT_JOIN_PROMPTED, "{\"prompts\":1}", ctx.services().clock().now());
        playJoinPrompt(ctx.plan().activityType() == ActivityType.PRACTICE ? INTRO_PRACTICE : INTRO_FREE_TALK);
    }

    /** 기술 일시정지가 끝났다: 멈춘 시간만큼 참여 창을 밀고 시계를 다시 건다. */
    void resumeJoinWindow(Duration paused) {
        join.shift(paused);
        armJoinWindow();
    }

    /**
     * 참여 안내 하나를 재생한다(BR2.1 · BR3.1 · BR3.3). 보낸 순간 말풍선 행을 남기고 그 끝 표식이 창을 연다. 패키지에 없으면 재생만 건너뛰고
     * APPROVED_CONTENT_MISSING을 코드별 1회 남긴 뒤 곧바로 재생이 끝난 것으로 본다(시계 · 횟수는 그대로). 보내지 못해도 끝난 것으로 본다.
     */
    private void playJoinPrompt(String contentCode) {
        Instant now = ctx.services().clock().now();
        Optional<SessionExecutor.ApprovedItem> item = ctx.plan().approvedItem(contentCode);
        if (item.isEmpty()) {
            env.reportMissing(contentCode, now);
        } else {
            UUID playbackId = UUID.randomUUID();
            if (env.sendControl(new PlayApprovedContent(playbackId, contentCode, item.get().contentVersionId()))) {
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

    /** 참여 안내 재생이 끝났다(서버가 끝 표식을 받은 시각, BR2.2): 창을 연다. */
    void onJoinPromptPlayed(Instant at) {
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

    /** 참여 창 시계 만기: 결정표의 무응답 칸(BR2.4). 창이 밀렸으면(일시정지) 남은 시간으로 다시 건다. */
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
     * WAITING_CHILD의 아이 확정 전사(BR2.3 · BR2.5): 저장하지 않고 분류에만 쓴다. 안내를 재생하는 중의 대답도 곧바로 분류한다(R-11 · 5354a32
     * JoinTracker 동작).
     */
    void onJoinReply(String transcript, Instant at) {
        if (join == null || join.isFinished()) {
            return;
        }
        // U11 BR5.3: J1이 열린 동안 새 확정 전사가 오면 이전 J1을 SUPERSEDED로 닫고 새 전사를 처음부터 다시 처리한다
        judgment.onTranscript(false);
        JoinTracker.Reply reply = JoinTracker.classify(transcript);
        if (reply == JoinTracker.Reply.UNCLEAR && judgment.openJoinJudgment(transcript, at)) {
            return;
        }
        applyJoinAction(join.reply(reply, at), reply, at);
    }

    /** J1 결과를 참여 대답 하나로 넣는다(참여 대기 중이 아니면 아무것도 하지 않는다). */
    void applyJoinReply(JoinTracker.Reply reply, Instant at) {
        if (ctx.status() == SessionStatus.WAITING_CHILD && join != null && !join.isFinished()) {
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
                env.putEvent(EVENT_JOIN_RE_PROMPTED, "{\"prompts\":" + join.prompts() + ",\"cause\":\""
                        + (action == JoinTracker.Action.REPROMPT ? "NO_REPLY" : "UNCLEAR") + "\"}", at);
                playJoinPrompt(RE_PROMPT);
            }
            case OFFER_ALTERNATIVE -> {
                ctx.timers().cancel(Kind.JOIN_WINDOW);
                env.putEvent(EVENT_JOIN_ALTERNATIVE_OFFERED, "{\"prompts\":" + join.prompts() + ",\"reply\":\""
                        + (reply == null ? "NONE" : reply.name()) + "\"}", at);
                playJoinPrompt(DECLINE_ALTERNATIVE);
            }
            case DECLINE -> declineJoin();
            case NONE -> {
                // 안내 없이 같은 창을 계속 기다린다(재안내를 이미 쓴 불명확 대답)
            }
        }
    }

    /** 참여 수락 → ACTIVE(BR2.4 · BR2.6): started_at, 원음 기록 시작, 응답 생성 요청 1회. */
    private void acceptJoin() {
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        Instant now = ctx.services().clock().now();
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(),
                SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE, SessionChange.none().withStartedAt(now),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
        if (row.isEmpty()) {
            env.syncFromDb();
            return;
        }
        env.changeStatus(SessionStatus.ACTIVE, ctx.services().clock().now());
        env.putEvent(EVENT_JOIN_ACCEPTED, "{\"prompts\":" + (join == null ? 1 : join.prompts()) + "}", now);
        env.afterActiveWon();
    }

    /**
     * 참여 거절 → DECLINED(BR2.4 · BR3.2 · BR4.7): 기록기의 대기 칸을 버린 뒤 전이 트랜잭션에서 그 세션의 전사 행을 지우고, 마무리 인사를 closing-confirm
     * 상한 안에서 돌린다(말풍선 행 없음).
     */
    private void declineJoin() {
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        ctx.services().recorder().discardTranscripts(ctx.plan().sessionId(), ctx.services().settings().timerTick());
        Instant now = ctx.services().clock().now();
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(),
                SessionStatus.WAITING_CHILD, SessionStatus.DECLINED,
                TransitionTable.changeFor(Trigger.JOIN_DECLINED, null, now), SessionTransitions.Origin.REALTIME,
                ctx.plan().traceId(), new SessionTransitions.Extras(null, true));
        if (row.isEmpty()) {
            env.syncFromDb();
            return;
        }
        env.changeStatus(SessionStatus.DECLINED, ctx.services().clock().now());
        env.putEvent(EVENT_JOIN_DECLINED, "{\"prompts\":" + (join == null ? 1 : join.prompts()) + "}", now);
        ending.startFarewell();
    }

    private SessionExecutor.Settings.Lifecycle lifecycle() {
        return ctx.services().settings().lifecycle();
    }
}
