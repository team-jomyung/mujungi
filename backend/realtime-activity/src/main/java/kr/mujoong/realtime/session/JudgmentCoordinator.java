package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.JudgeOutcome;
import kr.mujoong.realtime.judgment.JudgeRequest;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentLiveRecorder;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentOwner;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentTrigger;
import kr.mujoong.realtime.judgment.JudgmentTurn;
import kr.mujoong.realtime.judgment.NextAction;
import kr.mujoong.realtime.judgment.TurnStatus;

/**
 * 세션 실행기의 판정 턴 협력 객체 (U11 W1 ~ W6, BR1.8 · BR2.1 · BR3.1 · BR5.x). 세션마다 하나이고 실행기 스레드만 쓴다({@link #postResult}만
 * 완료 콜백 스레드에서 불린다). 열린 턴 하나와 그 턴의 지점별 분기(결과 처리 · 닫힘 뒤 일)를 쥐고, 턴이 부르는 {@link JudgmentTurn.Host}를 구현해 실행기의
 * 출력 세대 · 시계 · 지시 송신 · 보기에 {@link Env}로만 닿는다. 닫힐 때마다 판정 기록 한 건을 비동기로 남긴다. 전사 · 지시문 본문은 로그에 쓰지 않는다
 * (R-F14). 공급자가 없으면 턴을 열지 않고 지금 흐름이 돈다(BR1.6): 기록 한 건(NO_PROVIDER)만 남는다.
 */
final class JudgmentCoordinator implements JudgmentTurn.Host {

    /** 최근 아이 확정 전사 보관 수(판정 요청의 직전 대화와 확장 요청의 순번 확인에 쓴다, W4). */
    static final int RECENT_LIMIT = 4;
    /** 아이 이름 대신 공급자에 보내는 말(BR1.7). */
    static final String CHILD_ALIAS = "아이";

    /** 실행기가 구현하는 바깥 세계. 모두 실행기 스레드에서 불린다({@code postFromProvider}만 어느 스레드에서나). */
    interface Env {
        SessionStatus status();

        Instant now();

        /** 판정 턴을 열며 출력 세대를 올린다(JUDGMENT_TURN) — 낡은 응답 조각은 버려진다. */
        void bumpForJudgment();

        /** ACTIVE에서 '생각 중'을 보인다(보기가 듣는 중일 때만). */
        void showThinking();

        /** 닫힌 뒤 ACTIVE이고 재생 · 출력이 없으면 '듣는 중'으로 되돌린다. */
        void restoreListening();

        /** 지시 하나를 직렬 큐로 보낸다. 새 말이 시작하지 않으면 {@link SessionMailbox.JudgmentInstructionFailed}가 편지함으로 온다. */
        boolean sendInstruction(UUID turnId, InstructionTemplates.NextAction action);

        void armTimer(JudgmentTurn.TimerKind kind, Duration delay);

        void cancelTimer(JudgmentTurn.TimerKind kind);

        boolean postFromProvider(SessionMailbox.Message message);

        /** 낡은 결과 · 전사를 버렸다(사유 코드 {@code JUDGMENT_LATE_DROPPED}, 개수만). */
        void lateDropped(String reasonCode);
    }

    /** 턴을 열 수 있는 상태인가. */
    enum Readiness {
        READY,
        NO_PROVIDER,
        UNREADABLE,
        BUSY,
        WRONG_STATUS
    }

    /**
     * 닫힘 뒤 일의 결과. {@code next}는 판정 기록의 다음 행동, {@code restoreView}는 닫힌 뒤 '듣는 중'으로 되돌릴지(지시에 따른 응답이
     * 시작돼 닫혔으면 되돌리지 않는다)다.
     */
    record Handled(NextAction next, boolean restoreView) {
        static final Handled NOTHING = new Handled(NextAction.NONE, false);
    }

    /** 턴을 여는 요청: 지점 · 계기와 지점별 분기. */
    record Open(JudgmentPoint point, JudgmentTrigger trigger, JudgmentOwner owner, int transcriptSeq, String text, Instant at,
            JudgeRequest.Criteria criteria, List<JudgeRequest.RecentTurn> recent, JudgmentTurn.ResultHandler decide,
            Function<JudgmentTurn.Closure, Handled> after) {

        @Override
        public String toString() {
            return "Open[point=" + point + ", trigger=" + trigger + "]";
        }
    }

    /** 최근 아이 확정 전사(원문은 로그에 쓰지 않는다). */
    record Recent(int seq, String text, Instant at) {
        @Override
        public String toString() {
            return "Recent[seq=" + seq + "]";
        }
    }

    private final JudgmentSupport support;
    private final SessionExecutor.Plan plan;
    private final Env env;
    /** 10초 응답 준비 시한(= 지시 응답 시한, {@code mujoong.timing.judgment.response-ready}). */
    private final Duration responseReady;
    private final Deque<Recent> recent = new ArrayDeque<>();
    private JudgmentTurn current;
    private Open currentOpen;

    JudgmentCoordinator(JudgmentSupport support, SessionExecutor.Plan plan, Duration responseReady, Env env) {
        this.responseReady = Objects.requireNonNull(responseReady, "responseReady");
        this.support = Objects.requireNonNull(support, "support");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.env = Objects.requireNonNull(env, "env");
    }

    // ---- 읽기 ----

    boolean available() {
        return support.judge().available();
    }

    boolean isOpen() {
        return current != null && current.isOpen();
    }

    /** 판정 단계라 공급자 출력을 모두 버리고 전사만 모으는 중인가(BR3.1). 지시 단계에서는 새 말을 받아야 하므로 아니다. */
    boolean holdsOutput() {
        return current != null && current.state() == JudgmentTurn.State.AWAITING_RESULT;
    }

    JudgmentPoint openPoint() {
        return current == null ? null : current.point();
    }

    JudgmentOwner openOwner() {
        return current == null ? null : current.owner();
    }

    // ---- 최근 전사 (W4) ----

    void remember(int seq, String text, Instant at) {
        if (text == null || text.isBlank()) {
            return;
        }
        recent.addLast(new Recent(seq, text, at));
        while (recent.size() > RECENT_LIMIT) {
            recent.removeFirst();
        }
    }

    /** 최근 확정 전사 중 그 순번의 것(없으면 null). */
    Recent findRecent(long seq) {
        for (Recent item : recent) {
            if (item.seq() == seq) {
                return item;
            }
        }
        return null;
    }

    /** 그 전사 바로 앞의 아이 전사 최대 3개(오래된 것부터). */
    List<JudgeRequest.RecentTurn> recentBefore(int seq) {
        List<JudgeRequest.RecentTurn> before = new ArrayList<>();
        for (Recent item : recent) {
            if (item.seq() < seq) {
                before.add(new JudgeRequest.RecentTurn(JudgeRequest.Speaker.CHILD, clip(item.text())));
            }
        }
        int from = Math.max(0, before.size() - JudgeRequest.MAX_RECENT_TURNS);
        return List.copyOf(before.subList(from, before.size()));
    }

    // ---- 열기 ----

    /** 턴을 열 수 있는지 부작용 없이 본다. */
    Readiness preflight(String text) {
        if (isOpen()) {
            return Readiness.BUSY;
        }
        SessionStatus status = env.status();
        if (status != SessionStatus.WAITING_CHILD && status != SessionStatus.ACTIVE) {
            return Readiness.WRONG_STATUS;
        }
        if (!available()) {
            return Readiness.NO_PROVIDER;
        }
        if (text == null || text.isBlank()) {
            return Readiness.UNREADABLE;
        }
        return Readiness.READY;
    }

    /** 열 수 없었던 까닭이 공급자 없음 · 판독 불가면 턴 없이 기록 한 건을 남긴다(BR1.6 · BR4.3). */
    void recordSkipped(Readiness why, JudgmentPoint point, JudgmentTrigger trigger, int transcriptSeq, NextAction next) {
        AbstainReason reason;
        if (why == Readiness.NO_PROVIDER) {
            reason = AbstainReason.NO_PROVIDER;
        } else if (why == Readiness.UNREADABLE) {
            reason = AbstainReason.UNREADABLE_INPUT;
        } else {
            return;
        }
        write(UUID.randomUUID(), point, trigger, transcriptSeq, false,
                JudgeOutcome.abstained(reason, Duration.ZERO, null, support.promptVersion()), next, null);
    }

    /** 턴을 연다({@link #preflight}가 READY일 때). */
    void open(Open request) {
        Objects.requireNonNull(request, "request");
        if (preflight(request.text()) != Readiness.READY) {
            throw new IllegalStateException("판정 턴을 열 수 없는 때다");
        }
        Instant now = env.now();
        Duration liveBudget = support.liveBudget();
        LiveContentResources.PointOptions options = support.optionSet().points()
                .get(LiveContentResources.JudgmentPoint.valueOf(request.point().name()));
        JudgeRequest judgeRequest = new JudgeRequest(request.point(), support.optionSet().version(), options.optionCodes(),
                clip(request.text()), request.recent(), request.criteria(), aliases(), false, now.plus(liveBudget));
        TurnStatus openedIn = env.status() == SessionStatus.ACTIVE ? TurnStatus.ACTIVE : TurnStatus.WAITING_CHILD;
        JudgmentTurn.Spec spec = new JudgmentTurn.Spec(UUID.randomUUID(), request.point(), request.trigger(), request.owner(),
                openedIn, request.transcriptSeq(), request.at(), liveBudget, responseReady, support.promptVersion());
        currentOpen = request;
        JudgmentTurn.open(spec, support.judge(), judgeRequest, this, request.decide(), now);
    }

    private Map<String, String> aliases() {
        String name = plan.childContext().displayName();
        return name == null || name.isBlank() ? Map.of() : Map.of(name, CHILD_ALIAS);
    }

    private static String clip(String text) {
        return text.length() <= JudgeRequest.MAX_TEXT_CHARS ? text : text.substring(0, JudgeRequest.MAX_TEXT_CHARS);
    }

    // ---- 사건 (실행기 스레드) ----

    void onArrived(SessionMailbox.JudgmentArrived arrived) {
        if (current == null || !current.turnId().equals(arrived.turnId())) {
            env.lateDropped("JUDGMENT_LATE_DROPPED");
            return;
        }
        current.onJudgmentArrived(arrived.turnId(), arrived.outcome(), env.now());
    }

    void onInstructionFailed(SessionMailbox.JudgmentInstructionFailed failed) {
        if (current == null || !current.turnId().equals(failed.turnId())) {
            env.lateDropped("JUDGMENT_LATE_DROPPED");
            return;
        }
        current.onInstructionNotHeard(JudgmentTurn.CloseReason.NEW_SPEECH_NOT_OBSERVED, env.now());
    }

    void onTimer(JudgmentTurn.TimerKind kind) {
        if (current != null) {
            current.onTimer(kind, env.now());
        }
    }

    void onInstructionAcked() {
        if (current != null) {
            current.onInstructionAcked();
        }
    }

    void onNewSpeechStarted() {
        if (current != null) {
            current.onNewSpeechStarted(env.now());
        }
    }

    /** 판정 중 출력 전사 조각을 모은다(음성은 호출자가 버린다). */
    void holdTranscript(String piece) {
        if (current != null) {
            current.holdTranscript(piece);
        }
    }

    JudgmentTurn.TranscriptAction onTranscript(boolean stopExpression) {
        return current == null ? JudgmentTurn.TranscriptAction.NONE : current.onConfirmedTranscript(stopExpression, env.now());
    }

    void deferJoinTick() {
        if (current != null) {
            current.deferJoinTick();
        }
    }

    void onFault() {
        if (current != null) {
            current.onFault(env.now());
        }
    }

    /** 세션 상태가 바뀌었다(R-15). 턴을 연 상태가 아니면 SESSION_EXITED로 닫는다. */
    void onStatus(SessionStatus next) {
        if (current == null) {
            return;
        }
        TurnStatus mapped = next == SessionStatus.ACTIVE ? TurnStatus.ACTIVE
                : next == SessionStatus.WAITING_CHILD ? TurnStatus.WAITING_CHILD : null;
        current.onSessionStatus(mapped, env.now());
    }

    /** 앱 종료 · 삭제 중단처럼 상태 전이 없이 세션이 멈출 때. */
    void onExit() {
        if (current != null) {
            current.onSessionStatus(null, env.now());
        }
    }

    // ---- Host ----

    @Override
    public void onOpened(JudgmentTurn turn) {
        current = turn;
        env.bumpForJudgment();
        if (env.status() == SessionStatus.ACTIVE) {
            env.showThinking();
        }
    }

    @Override
    public void armTimer(JudgmentTurn.TimerKind kind, Duration delay) {
        env.armTimer(kind, delay);
    }

    @Override
    public void cancelTimer(JudgmentTurn.TimerKind kind) {
        env.cancelTimer(kind);
    }

    @Override
    public void postResult(UUID turnId, JudgeOutcome outcome) {
        env.postFromProvider(new SessionMailbox.JudgmentArrived(turnId, outcome));
    }

    @Override
    public boolean sendInstruction(UUID turnId, InstructionTemplates.NextAction action) {
        return env.sendInstruction(turnId, action);
    }

    @Override
    public void onDropped(String reasonCode) {
        env.lateDropped(reasonCode);
    }

    @Override
    public void onClosed(JudgmentTurn.Closure closure) {
        Open open = currentOpen;
        current = null;
        currentOpen = null;
        Handled handled = Handled.NOTHING;
        try {
            if (open != null) {
                handled = open.after().apply(closure);
            }
        } finally {
            write(closure.turnId(), closure.point(), closure.trigger(), closure.inputTranscriptSeq(), true, closure.outcome(),
                    handled.next(), closure.heldOutputTranscript());
        }
        if (handled.restoreView() && !closure.responseStarted()) {
            env.restoreListening();
        }
    }

    // ---- 기록 (BR2.1) ----

    private void write(UUID id, JudgmentPoint point, JudgmentTrigger trigger, int seq, boolean opened, JudgeOutcome outcome,
            NextAction next, String held) {
        JudgmentLiveRecorder recorder = support.recorder();
        if (recorder == null || plan.guardianId() == null) {
            return;
        }
        boolean confirmed = outcome.kind() == JudgmentOutcomeKind.CONFIRMED;
        recorder.put(new JudgmentLiveRecord(id, plan.guardianId(), plan.sessionId(), point, trigger, null, null, seq,
                support.optionSet() == null ? "none" : support.optionSet().version(), outcome.promptVersion(), outcome.model(),
                outcome.kind(), outcome.abstainReason(), opened, confirmed ? outcome.firstOption() : null,
                confirmed ? outcome.firstProbability() : null, confirmed ? outcome.secondOption() : null,
                confirmed ? outcome.secondProbability() : null, outcome.latency().toMillis(), next,
                held == null || held.isEmpty() ? null : held, env.now()));
    }

    /** 세션 정리: 진행 중 판정 요청을 취소하고 턴을 놓는다(기록 · 보기 복원 없음). */
    void shutdown() {
        current = null;
        currentOpen = null;
    }
}
