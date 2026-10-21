package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.realtime.dialog.JoinTracker;
import kr.mujoong.realtime.judgment.JudgeRequest;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTurn;
import kr.mujoong.realtime.judgment.NextAction;
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

        /** 아이 말의 입력 전사 조각을 묶는 중인가(말을 시작했고 아직 확정 전사가 되지 않았다). */
        boolean childSpeaking();
    }

    private final SessionContext ctx;
    private final IdleClock idle;
    private final JudgmentCoordinator judgment;
    private final EndingFlow ending;
    private final Env env;

    /** U4b 참여 판단(WAITING_CHILD에 들어갈 때 만든다). */
    private JoinTracker join;
    /** 지금 J1이 판정하는 대답(없으면 null). */
    private PendingReply judging;
    /** J1이 열린 동안 온 불명확한 대답 한 칸(새 대답이 오면 덮어쓴다, 메모리만). J1이 참여 · 거절로 닫히지 않으면 이것으로 J1을 한 번 더 연다. */
    private PendingReply held;
    /** J1을 SUPERSEDED로 닫은 새 대답을 처리한 뒤 부를, 그 J1이 미뤄 둔 참여 창 만기. */
    private boolean windowTickPending;
    /** 아이가 말하는 중(입력 묶음이 열린 동안)에 울려 미룬 참여 창 만기. 묶음이 확정 · 처리된 뒤 부른다. */
    private boolean speechTickPending;
    /** 마지막으로 기기에 보낸 참여 안내의 승인 글(J1 요청의 COACH 줄, 로그에 쓰지 않는다). 글을 모르면 null. */
    private String lastPromptText;
    /** 이 세션에서 기기에 보낸 참여 안내의 승인 글(최대 3개, 에코 의심 대조에만 쓴다). */
    private final List<String> playedPromptTexts = new ArrayList<>();

    /** 진단 로그(contracts/ai-io.md 10.15)에 남기는 확정 전사 하나의 모양. 원문은 담지 않는다. */
    private record ReplyMeta(int textLen, int wordCount, InputTranscriptGroup.Shape shape, boolean lateForWindow) {
    }

    /**
     * 아직 참여 판단에 넣지 않은 불명확한 대답(원문은 판정 요청에만 쓰고 저장 · 로그에 쓰지 않는다).
     *
     * @param at 참여 판단에 넣을 대답 시각
     * @param confirmedAt 확정 전사 시각(J1 10초 시계 기준, 설계 개정 A8)
     */
    private record PendingReply(String transcript, Instant at, Instant confirmedAt, ReplyMeta meta) {
        @Override
        public String toString() {
            return "PendingReply[at=" + at + "]";
        }
    }

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
        Instant now = ctx.services().clock().now();
        Optional<SessionExecutor.ApprovedItem> item = ctx.plan().approvedItem(contentCode);
        if (item.isEmpty()) {
            env.reportMissing(contentCode, now);
        } else {
            UUID playbackId = UUID.randomUUID();
            if (ctx.sendControl(new PlayApprovedContent(playbackId, contentCode, item.get().contentVersionId()))) {
                lastPromptText = item.get().text();
                if (lastPromptText != null && !lastPromptText.isBlank()) {
                    playedPromptTexts.add(lastPromptText);
                }
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
        if (env.childSpeaking()) {
            // 조각이 오는 중이면 무응답이 아니다(AC6.3.3): 그 대답이 확정 · 처리된 뒤 tick을 부른다
            speechTickPending = true;
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
     * JoinTracker 동작). 처리마다 진단 로그 한 줄을 남긴다(원문 없음).
     *
     * <p>J1이 열린 동안 온 대답이 불명확하면 J1을 닫지 않고 대기 칸에 둔다(판정 결과를 버리지 않는다). 참여 · 거절 · 주제 거부처럼 분명하면 J1을
     * SUPERSEDED로 닫고 새 대답을 처음부터 처리한다(docs/join-gate-stabilization.md (3)). 이 세션에서 재생한 참여 안내 글의 에코로 보이는 전사
     * ({@link JoinTracker#echoOf})는 분류도 J1도 하지 않고 버린다((6)).
     *
     * @param confirmedAt 확정 전사 시각
     * @param shape 이 전사로 묶인 입력 전사 조각의 모양
     */
    void onJoinReply(String transcript, Instant confirmedAt, InputTranscriptGroup.Shape shape) {
        if (join == null || join.isFinished()) {
            return;
        }
        // 대답 시각은 말을 시작한 때(그 묶음의 첫 조각을 서버가 받은 시각)다: 창 끝 직전에 시작한 말은 확정이 창 뒤여도 창 안의 대답이다
        Instant at = shape != null ? shape.openedAt() : confirmedAt;
        ReplyMeta meta = meta(transcript, at, shape);
        if (JoinTracker.echoOf(transcript, playedPromptTexts)) {
            // 재생한 안내 글이 마이크로 다시 들어와 전사된 것으로 본다: 분류 · J1 없이 버리고 열린 J1도 그대로 둔다
            logReply("ECHO_SUSPECT", JoinTracker.Action.NONE.name(), meta);
            return;
        }
        JoinTracker.Reply reply = JoinTracker.classify(transcript);
        if (reply == JoinTracker.Reply.UNCLEAR && judgment.openPoint() == JudgmentPoint.J1) {
            held = new PendingReply(transcript, at, confirmedAt, meta);
            logReply(reply.name(), "J1_HELD", meta);
            return;
        }
        // 분명한 대답이 열린 J1을 대체한다: SUPERSEDED로 닫고(대기 칸도 버린다) 새 대답을 처음부터 처리한다
        judgment.onTranscript(false);
        if (reply == JoinTracker.Reply.UNCLEAR && openJoinJudgment(new PendingReply(transcript, at, confirmedAt, meta), null)) {
            return;
        }
        JoinTracker.Action action = join.reply(reply, at);
        logReply(reply.name(), action.name(), meta);
        applyJoinAction(action, reply, at);
        if (windowTickPending) {
            windowTickPending = false;
            runDeferredJoinTick();
        }
    }

    /**
     * 불명확한 대답으로 J1을 연다. 직전 대화에는 마지막으로 보낸 참여 안내 글을 COACH 줄로(prompt-v2 J1 규칙: 그 말 끝의 참여 질문에 대한 대답인지
     * 판단), 앞서 판정한 불명확 대답(대기 칸으로 다시 여는 경우)을 CHILD 줄로 넣는다(최대 2줄). 승인 글을 모르면 COACH 줄은 없다. 열 수 없으면(공급자
     * 없음 · 판독 불가) 거짓이고 부르는 쪽이 지금 흐름으로 간다.
     */
    private boolean openJoinJudgment(PendingReply reply, String previousChild) {
        List<JudgeRequest.RecentTurn> recent = new ArrayList<>();
        if (lastPromptText != null && !lastPromptText.isBlank()) {
            recent.add(new JudgeRequest.RecentTurn(JudgeRequest.Speaker.COACH, clip(lastPromptText)));
        }
        if (previousChild != null && !previousChild.isBlank()) {
            recent.add(new JudgeRequest.RecentTurn(JudgeRequest.Speaker.CHILD, clip(previousChild)));
        }
        if (!judgment.openJoinJudgment(reply.transcript(), reply.confirmedAt(), recent, this::afterJoinJudgment)) {
            return false;
        }
        judging = reply;
        logReply(JoinTracker.Reply.UNCLEAR.name(), "J1_OPENED", reply.meta());
        return true;
    }

    /**
     * J1이 닫혔다(판정 기록 전, {@link JudgmentCoordinator#onClosed} 안). 결과를 PARTICIPATE → 참여, DECLINE → 거절, 그 밖(휴식 · 불명확 ·
     * 판독 불가 · 보류) → 불명확으로 바꾼다. 불명확이고 대기 칸에 대답이 있으면 그 대답으로 J1을 한 번 더 열고, 아니면 원래 대답 시각으로 참여 판단에
     * 한 번만 넣는다(W1 4, 재안내는 B17대로 한 번만 센다). 둘 다 판정 기록이 남은 뒤로 미룬다. 미뤄 둔 참여 창 만기는 어느 길에서도 잃지 않는다.
     */
    private JudgmentCoordinator.Handled afterJoinJudgment(JudgmentTurn.Closure closure) {
        PendingReply judged = judging;
        judging = null;
        JudgmentOutcomeKind kind = closure.outcome().kind();
        if (kind != JudgmentOutcomeKind.CONFIRMED && kind != JudgmentOutcomeKind.ABSTAINED) {
            // SUPERSEDED: 지금 처리하는 새 대답 뒤에 미룬 만기를 부른다. FAULT · 상태 이탈: 창은 재개 · 전이가 다시 다룬다
            held = null;
            if (kind == JudgmentOutcomeKind.SUPERSEDED && closure.joinTickDeferred()) {
                windowTickPending = true;
            }
            return JudgmentCoordinator.Handled.NOTHING;
        }
        JoinTracker.Reply reply = JoinTracker.Reply.UNCLEAR;
        NextAction next = NextAction.REPROMPT_JOIN;
        if (kind == JudgmentOutcomeKind.CONFIRMED && "PARTICIPATE".equals(closure.outcome().firstOption())) {
            reply = JoinTracker.Reply.ACCEPT;
            next = NextAction.START_JOIN;
        } else if (kind == JudgmentOutcomeKind.CONFIRMED && "DECLINE".equals(closure.outcome().firstOption())) {
            reply = JoinTracker.Reply.REFUSE;
            next = NextAction.DECLINE_JOIN;
        }
        boolean tick = closure.joinTickDeferred();
        PendingReply waiting = held;
        held = null;
        if (reply == JoinTracker.Reply.UNCLEAR && waiting != null) {
            String previous = judged == null ? null : judged.transcript();
            judgment.afterClose(() -> reopenWithHeld(waiting, previous, tick));
            return new JudgmentCoordinator.Handled(NextAction.NONE, false);
        }
        JoinTracker.Reply decided = reply;
        judgment.afterClose(() -> applyJudged(decided, judged, tick));
        return new JudgmentCoordinator.Handled(next, false);
    }

    /** 대기 칸의 대답으로 J1을 다시 연다(앞 판정이 닫힌 뒤). 열 수 없으면 그 대답을 불명확으로 넣는다. 미룬 창 만기는 새 J1로 넘긴다. */
    private void reopenWithHeld(PendingReply waiting, String previousChild, boolean tick) {
        if (ctx.status() != SessionStatus.WAITING_CHILD || join == null || join.isFinished()) {
            return;
        }
        if (openJoinJudgment(waiting, previousChild)) {
            if (tick) {
                judgment.deferJoinTick();
            }
            return;
        }
        applyJudged(JoinTracker.Reply.UNCLEAR, waiting, tick);
    }

    /** J1 결과를 참여 대답 하나로 넣고(참여 대기 중일 때만), 미룬 창 만기를 처리한다. */
    private void applyJudged(JoinTracker.Reply reply, PendingReply judged, boolean tick) {
        if (ctx.status() == SessionStatus.WAITING_CHILD && join != null && !join.isFinished() && judged != null) {
            JoinTracker.Action action = join.reply(reply, judged.at());
            logReply(reply.name(), action.name(), judged.meta());
            applyJoinAction(action, reply, judged.at());
        }
        if (tick) {
            runDeferredJoinTick();
        }
    }

    /** 입력 묶음이 확정 · 처리됐다(또는 빈 묶음으로 버려졌다): 아이가 말하는 동안 미룬 참여 창 만기가 있으면 지금 처리한다. */
    void onInputSettled() {
        if (speechTickPending) {
            speechTickPending = false;
            runDeferredJoinTick();
        }
    }

    /** J1이 열린 동안 미뤄 둔 참여 창 만기를 처리한다(참여 대기 중이고 기술 일시정지가 아닐 때만). */
    private void runDeferredJoinTick() {
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
            case DECLINE -> declineJoin();
            case NONE -> {
                // 안내 없이 같은 창을 계속 기다린다(재안내를 이미 쓴 불명확 대답)
            }
        }
    }

    /** 참여 수락 → ACTIVE: started_at, 원음 기록 시작, 응답 생성 요청 1회. */
    private void acceptJoin() {
        ctx.timers().cancel(Kind.JOIN_WINDOW);
        Instant now = ctx.services().clock().now();
        Optional<ActivitySessionRow> row = ctx.services().transitions().transition(ctx.plan().sessionId(),
                SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE, SessionChange.none().withStartedAt(now),
                SessionTransitions.Origin.REALTIME, ctx.plan().traceId());
        if (row.isEmpty()) {
            ending.syncFromDb();
            return;
        }
        ending.changeStatus(SessionStatus.ACTIVE, ctx.services().clock().now());
        ctx.putEvent(EVENT_JOIN_ACCEPTED, "{\"prompts\":" + (join == null ? 1 : join.prompts()) + "}", now);
        env.afterActiveWon();
    }

    /**
     * 참여 거절 → DECLINED: 기록기의 대기 칸을 버린 뒤 전이 트랜잭션에서 그 세션의 전사 행을 지우고, 마무리 인사를 closing-confirm
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
            ending.syncFromDb();
            return;
        }
        ending.changeStatus(SessionStatus.DECLINED, ctx.services().clock().now());
        ctx.putEvent(EVENT_JOIN_DECLINED, "{\"prompts\":" + (join == null ? 1 : join.prompts()) + "}", now);
        ending.startFarewell();
    }

    private SessionExecutor.Settings.Lifecycle lifecycle() {
        return ctx.services().settings().lifecycle();
    }

    private static String clip(String text) {
        return text.length() <= JudgeRequest.MAX_TEXT_CHARS ? text : text.substring(0, JudgeRequest.MAX_TEXT_CHARS);
    }

    private ReplyMeta meta(String transcript, Instant at, InputTranscriptGroup.Shape shape) {
        return new ReplyMeta(JoinTracker.normalizedLength(transcript), JoinTracker.wordCount(transcript), shape, lateAt(at));
    }

    /** 그 시각의 대답이 참여 창 끝과 같거나 늦은가(창이 없으면 거짓). */
    private boolean lateAt(Instant at) {
        Instant end = join == null ? null : join.windowEnd();
        return end != null && !at.isBefore(end);
    }

    /** 진단 로그 JOIN_REPLY 한 줄(contracts/ai-io.md 10.15). 분류 · 할 일 코드와 전사의 모양만 남긴다(R-F14). */
    private void logReply(String reply, String action, ReplyMeta meta) {
        InputTranscriptGroup.Shape shape = meta.shape();
        ctx.services().events().log(EventCode.JOIN_REPLY, EventLog.Component.SESSION_LIFECYCLE, ctx.plan().sessionId(),
                ctx.plan().traceId(), entry -> entry
                        .attr(AttrKey.JOIN_REPLY_KIND, reply)
                        .attr(AttrKey.JOIN_ACTION, action)
                        .attr(AttrKey.PROMPTS, join == null ? 1 : join.prompts())
                        .attr(AttrKey.TEXT_LEN, meta.textLen())
                        .attr(AttrKey.WORD_COUNT, meta.wordCount())
                        .attr(AttrKey.DELTA_COUNT, shape == null ? 0 : shape.deltaCount())
                        .attr(AttrKey.MAX_GAP_MS, shape == null ? InputTranscriptGroup.NO_GAP : shape.maxGapMs())
                        .attr(AttrKey.DEVICE_PLAYING, shape == null ? !ctx.devicePlaying().isEmpty() : shape.playingAtOpen())
                        .attr(AttrKey.LATE_FOR_WINDOW, meta.lateForWindow()));
    }
}
