package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.Function;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.gate.GateDecision;
import kr.mujoong.realtime.gate.SegmentBuffer;
import kr.mujoong.realtime.guardrail.GuardrailEventRecord;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.guardrail.GuardrailRequestFactory;
import kr.mujoong.realtime.guardrail.GuardrailSupport;
import kr.mujoong.realtime.guardrail.GuardrailVerdict;
import kr.mujoong.realtime.log.TranscriptWriter;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.voice.ProviderRequestFactory;

/**
 * 세션 실행기의 제브 게이트 협력 객체 (U13 W1 ~ W4, BR3.x ~ BR6.x). 세션마다 하나이고 실행기 스레드만 쓴다. 완료 콜백 스레드는 결과를
 * {@link SessionMailbox.GuardrailArrived}로 일반 편지함에 넣기만 한다(BR3.1). 검사 상태 기계(IDLE · CHECKING · RETRYING), 검사표, 대기열 1칸, 턴
 * 재지시 상태를 쥐고 실행기에는 {@link Env}로만 닿는다(NFR8: 상태 필드는 여기에만 둔다).
 *
 * <p>하는 일: 새 발화 구간이 inspect를 통과하면 {@link #begin}이 AI 행을 보관 손잡이로 빼고 검사표를 만들어 비동기 요청을 보낸다. 결과가 오면 검사표
 * 유효 조건(BR4.2)을 확인해 유효한 ALLOW만 recheck → send로 잇고, 막힘은 턴당 한 번 재지시하며(BR6.1), 무효는 재생하지 않는다(BR4.3). 자동 ALLOW는
 * 없다. 전사 · 후보 문장 · 지시문 본문은 로그와 문자열 표현에 쓰지 않는다(R-F14).
 */
final class GuardrailCoordinator {

    /** 검사 상태(BR3.4 · 설계 2.1). */
    enum State {
        /** 검사 중인 구간이 없다. */
        IDLE,
        /** 제브 결과를 기다린다(최대 jev-budget). 기기는 '생각 중'을 유지한다. */
        CHECKING,
        /** 재지시를 보냈고 그 응답이 봉인되기를 기다린다. 기기는 '생각 중'을 유지한다. */
        RETRYING
    }

    /** 늦은 ALLOW가 무효가 되는 까닭(BR4.2 · BR4.3, 사유 코드만 기록). */
    enum VoidCause {
        SESSION_ENDED,
        CONNECTION_CHANGED,
        GENERATION_CHANGED,
        HELD,
        CHILD_SPOKE,
        PRACTICE_CHANGED
    }

    /** 대기열 구간을 폐기하는 까닭의 사유 코드(BR5.4). */
    static final String REASON_QUEUE_DISCARDED = "QUEUE_DISCARDED";
    /** 대기열이 차서 새 구간을 폐기한 사유 코드(BR5.3). */
    static final String REASON_QUEUE_FULL = "QUEUE_FULL";
    /** 재지시가 소리로 이어지지 않아 턴을 포기했다(BR6.6, 앱 오류 사유 코드). */
    static final String REASON_RETRY_NOT_HEARD = "GuardrailRetryNotHeard";
    /** 늦게 도착한 결과를 버렸다. */
    static final String REASON_LATE_DROPPED = "GUARDRAIL_LATE_DROPPED";
    /** 협력 객체 안 예외로 fail-closed 했다. */
    static final String REASON_FAULT = "GuardrailFault";
    /** 검사 안전 타이머가 먼저 만기되어 시간 초과 막힘으로 닫았다(앱 오류 사유 코드). */
    static final String REASON_CHECK_TIMER = "GuardrailCheckTimer";
    /** 연습이 쥔 승인 발화와 원문이 같아 제브를 건너뛴 재생의 사유 코드(BR7.5). 레지스트리 출처는 {@code APPROVED_SPEECH} · {@code SESSION_SCRIPT}다. */
    static final String SOURCE_PRACTICE_SPEECH = "PRACTICE_SPEECH";

    /**
     * 다시 보낼 수 있는 서버 지시(마지막 서버 지시, BR6.2 · W2b). 이름과 지시를 만드는 방법만 쥐고 전사 원문은 담지 않는다.
     *
     * @param name 직렬 큐 작업 이름 접두어
     * @param build 지시 id를 받아 보낼 지시를 만든다
     */
    record ReplayableInstruction(String name, Function<String, ProviderRequestFactory.InstructionsAppend> build) {

        ReplayableInstruction {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(build, "build");
        }

        @Override
        public String toString() {
            return "ReplayableInstruction[name=" + name + "]";
        }
    }

    /** 실행기가 구현하는 바깥 세계. 모두 실행기 스레드에서 불린다({@link #postFromProvider}만 어느 스레드에서나). */
    interface Env {
        SessionStatus status();

        boolean terminated();

        Instant now();

        /** 열린 기기 연결의 id(연결이 없거나 닫혔으면 null). */
        UUID connectionId();

        /** 기기 프레임 세대. */
        long deviceGeneration();

        /** 공급자 출력 세대. */
        long providerGeneration();

        /** 출력 보류(WAITING_CHILD · 종료 확인 · 판정 턴 · 연습 보류) 중인가. */
        boolean outputHeld();

        /** 입력 묶음을 처음 연 횟수(단조 증가, BR4.4). */
        long inputGroupOpenCount();

        /** 지금 기다리는 연습 승인 발화의 id(없으면 null). */
        UUID practiceSpeechId();

        /** 열린 AI 행을 보관 손잡이로 뺀다(BR5.5). */
        TranscriptWriter.Held holdAiRow();

        /** 유효한 ALLOW: recheck → send. 보낸 구간이면 참, recheck에서 폐기했으면 거짓. */
        boolean recheckAndSend(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, boolean ownsLatency);

        /** 첫 막힘: 지표 · AI 행 FINAL 거짓만 하고 마무리(대기 문구 · 듣기 복귀)는 하지 않는다. */
        void discardForRetry(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, GateDecision.DiscardReason reason,
                boolean ownsLatency);

        /** 턴 포기: 기존 discard의 마무리(대기 문구 또는 듣기 복귀)까지 한다(교정 지시는 보내지 않는다). */
        void abandon(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, GateDecision.DiscardReason reason);

        /** 조용한 폐기(무효 · 대기열): AI 행 FINAL 거짓만 한다. 사유가 있으면 지표를 올린다. */
        void discardSilent(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, GateDecision.DiscardReason reasonOrNull);

        /** 구간은 이미 닫았고 마무리(대기 문구 또는 듣기 복귀)만 한다. */
        void finishAbandoned();

        /** 무효 뒤 '듣는 중' 복귀(재생 · 출력이 없을 때만). */
        void listeningAgain();

        /** 마지막 서버 지시를 다시 보낸다(출력 세대를 올린다). 공급자 연결이 없으면 거짓. */
        boolean sendReplay(ReplayableInstruction instruction, UUID retryId);

        /** 서버 지시 없는 자유 응답용 재지시를 보낸다. 공급자 연결이 없으면 거짓. */
        boolean sendRetryFreeTurn(UUID retryId);

        boolean postFromProvider(SessionMailbox.Message message);

        /** 지표(U13 BR8.5). 없으면 null — 지표 없는 시험 환경에서는 세지 않는다. */
        default RealtimeMetrics metrics() {
            return null;
        }

        /** 검사 안전 타이머를 건다(검사 요청을 보낸 직후, 같은 종류는 바뀐다). */
        default void armCheckTimer(Duration delay) {
        }

        /** 검사 안전 타이머를 끈다. */
        default void cancelCheckTimer() {
        }

        /** 지금 턴이 제브 검사를 거친다고 지연 기록에 표시한다(체감 대기 지표, BR8.5 ⑤). */
        default void markCheckedTurn(String mode) {
        }

        /** 연습 모드 요청의 두 번째 막힘을 연습 확장점에 알린다(BR6.5, 운영 호출자는 U12). */
        default void practiceBlocked(PracticeSessionExtension.GuardrailBlockedEvent event) {
        }

        void appError(String reasonCode, EventCode.Level level);

        void appError(RuntimeException error);
    }

    /** 검사표(BR4.1): 요청 한 건의 유효 조건. 같은 {@link SegmentBuffer.Sealed}를 쥐어 전사와 소리가 한 구간이라는 구조를 지킨다. */
    private record Ticket(UUID requestId, SegmentBuffer.Sealed sealed, long deviceGeneration, long providerGeneration,
            UUID connectionId, long inputGroupOpens, UUID practiceSpeechId, TranscriptWriter.Held held,
            GuardrailRequest.Mode mode, boolean fromQueue) {

        @Override
        public String toString() {
            return "Ticket[requestId=" + requestId + ", mode=" + mode + "]";
        }
    }

    /** 대기열 1칸의 구간(BR5.3). */
    private record Queued(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, long deviceGeneration, long providerGeneration,
            UUID connectionId, long inputGroupOpens, UUID practiceSpeechId, GuardrailRequest.Mode mode) {

        @Override
        public String toString() {
            return "Queued[mode=" + mode + "]";
        }
    }

    private final GuardrailSupport support;
    private final SessionExecutor.Plan plan;
    private final Env env;
    private final GuardrailRequestFactory factory = new GuardrailRequestFactory();
    /** 익명화 전의 최근 대화 꼬리(아이 확정 전사 · 기기로 나간 무중 발화, 실행기 스레드 전용). */
    private final Deque<GuardrailRequestFactory.Turn> context = new ArrayDeque<>();

    private State state = State.IDLE;
    private Ticket ticket;
    private CompletionStage<GuardrailVerdict> inFlight;
    private Queued queue;
    /** 턴의 재지시 수(0 또는 1, BR6.1). */
    private int retries;
    private ReplayableInstruction lastInstruction;
    private UUID retryId;
    private long retryProviderGeneration;
    private long retryDeviceGeneration;
    /** {@link #begin} 안에서 보관한 행(예외 때 닫기 위해 기억한다). */
    private TranscriptWriter.Held pendingHeld;

    GuardrailCoordinator(GuardrailSupport support, SessionExecutor.Plan plan, Env env) {
        this.support = Objects.requireNonNull(support, "support");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.env = Objects.requireNonNull(env, "env");
    }

    // ---- 읽기 ----

    /** 제브 단계를 지나는가(OFF가 아니다). */
    boolean enabled() {
        return support.enabled();
    }

    State state() {
        return state;
    }

    /** 검사 결과를 기다리는 중인가(대기열에 쌓이는 중). */
    boolean isChecking() {
        return state == State.CHECKING;
    }

    /** 기기를 '생각 중'에 두어야 하는가(CHECKING 또는 유효한 RETRYING, BR3.4). */
    boolean holdsThinking() {
        refreshRetrying();
        return state != State.IDLE;
    }

    boolean hasQueued() {
        return queue != null;
    }

    int retries() {
        return retries;
    }

    // ---- 호출 지점 ----

    /**
     * 새 발화 구간 검사를 시작한다(호출 지점 ①: inspect 통과 · 연습 확인 뒤, recheck 앞). 검사 중이면 대기열에 보관하고(차 있으면 폐기),
     * 아니면 AI 행을 보관 손잡이로 빼고 비동기 요청을 보낸다. 이 호출은 어떤 HTTP도 기다리지 않는다. 예외는 호출자가 {@link #onBeginFailed}로 받는다.
     */
    void begin(SegmentBuffer.Sealed sealed, GuardrailRequest.Mode mode, UUID practiceSpeechId) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(mode, "mode");
        if (!support.enabled()) {
            throw new IllegalStateException("제브 단계를 건너뛰는 모드에서는 검사를 시작하지 않는다");
        }
        refreshRetrying();
        if (state == State.CHECKING) {
            enqueue(sealed, mode, practiceSpeechId);
            return;
        }
        retryId = null;
        TranscriptWriter.Held held = env.holdAiRow();
        pendingHeld = held;
        start(sealed, mode, practiceSpeechId, held, false);
        pendingHeld = null;
    }

    /**
     * {@link #begin}이 예외를 던졌다(BR3.1 전용 catch): 보관 행을 FINAL 거짓으로 닫고 검사표를 버려 IDLE로 돌린 뒤
     * {@code GUARDRAIL_ERROR}로 구간을 폐기한다. 폐기의 마무리가 기기를 '생각 중'에서 풀어 준다. 대기열에 넣다가 실패했으면 진행 중인 검사는 그대로 두고
     * 그 구간만 조용히 버린다.
     */
    void onBeginFailed(SegmentBuffer.Sealed sealed, RuntimeException error) {
        env.appError(error);
        TranscriptWriter.Held held = pendingHeld;
        pendingHeld = null;
        if (queue != null && queue.held() == held) {
            queue = null;
            env.discardSilent(sealed, held, GateDecision.DiscardReason.GUARDRAIL_ERROR);
            return;
        }
        if (state == State.CHECKING && ticket != null && ticket.held() != held) {
            // 진행 중인 다른 검사의 대기열 진입이 실패했다(보관 전): 그 구간만 버린다
            env.discardSilent(sealed, held, GateDecision.DiscardReason.GUARDRAIL_ERROR);
            return;
        }
        cancelInFlight();
        ticket = null;
        state = State.IDLE;
        retryId = null;
        discardQueue();
        env.abandon(sealed, held, GateDecision.DiscardReason.GUARDRAIL_ERROR);
    }

    /** 호출 지점 ②: 제브 결과가 실행기 스레드에 도착했다. */
    void onArrived(SessionMailbox.GuardrailArrived arrived) {
        Ticket current = ticket;
        if (current == null || !current.requestId().equals(arrived.requestId())) {
            env.appError(REASON_LATE_DROPPED, EventCode.Level.INFO);
            return;
        }
        env.cancelCheckTimer();
        ticket = null;
        inFlight = null;
        countLatency(arrived.verdict());
        try {
            resolve(current, arrived.verdict());
        } catch (RuntimeException e) {
            failClosed(current, e);
        }
    }

    /**
     * 검사 안전 타이머가 만기됐다(포트 규약을 어겨 결과가 상한의 두 배가 되도록 오지 않았다). 시간 초과 막힘으로 닫는다 — 기기를 '생각 중'에서
     * 풀고 대기열을 놓는 길은 일반 시간 초과와 같다. 늦게 도착하는 결과는 검사표가 없어 버려진다. 검사 중이 아니면 아무것도 하지 않는다.
     */
    void onCheckTimerFired() {
        Ticket current = ticket;
        if (state != State.CHECKING || current == null) {
            return;
        }
        cancelInFlight();
        ticket = null;
        env.appError(REASON_CHECK_TIMER, EventCode.Level.WARN);
        GuardrailVerdict timedOut = GuardrailVerdict.failed(GuardrailVerdict.Failure.TIMEOUT, support.policyVersion(),
                support.budget().toMillis());
        countLatency(timedOut);
        try {
            resolve(current, timedOut);
        } catch (RuntimeException e) {
            failClosed(current, e);
        }
    }

    /**
     * 분류 ②(BR7.1): 이 출력 전사가 승인 원문 · 등록 대본과 원문 일치(MATCHED)이면 출처 사유 코드를 돌려준다. 앞 검사가 진행 중이거나 대기열에 구간이
     * 있으면 순서를 지키려고 생략하지 않고 비어 있다(그 구간은 새 발화로 검사한다 — 안전한 쪽). 제브를 건너뛰는 모드(OFF)에서도 비어 있다.
     */
    Optional<String> verbatimSource(String transcript) {
        if (!support.enabled()) {
            return Optional.empty();
        }
        refreshRetrying();
        if (state == State.CHECKING || queue != null) {
            return Optional.empty();
        }
        return support.approved().matchedSource(plan.sessionId(), transcript);
    }

    /**
     * 승인 원문과 일치해 제브를 부르지 않고 기존 게이트 검사와 송신 직전 재확인만 거치는 재생이다(BR7.1 · BR7.5). 호출 수 지표에는 '원문 재생 생략'으로
     * 세고 검사 기록 한 행(APPROVED_VERBATIM_SKIPPED, ALLOW, 지연 0)을 남긴다. 재지시를 기다리던 중이면 그 응답이 이 재생이므로 IDLE로 돌아온다.
     */
    void onVerbatimSkipped(String sourceReason, String transcript) {
        Objects.requireNonNull(sourceReason, "sourceReason");
        if (!support.enabled()) {
            return;
        }
        if (state == State.RETRYING) {
            state = State.IDLE;
            retryId = null;
        }
        RealtimeMetrics metrics = env.metrics();
        if (metrics != null) {
            metrics.guardrailCall(support.mode().name(), "approved_verbatim_skipped");
        }
        record(UUID.randomUUID(), GuardrailEventRecord.SourceKind.APPROVED_VERBATIM_SKIPPED,
                GuardrailEventRecord.Decision.ALLOW, sourceReason, 0L);
        if (transcript != null && !transcript.isBlank()) {
            remember(GuardrailRequest.Speaker.MUJOONG, transcript);
        }
    }

    /** 재지시를 보내지 못했거나 새 말이 시한 안에 시작하지 않았다(큐 스레드의 메시지, BR6.6). */
    void onRetryNotHeard(SessionMailbox.GuardrailRetryNotHeard message) {
        if (state != State.RETRYING || !message.retryId().equals(retryId)) {
            return;
        }
        retryNotHeard();
    }

    /**
     * 호출 지점 ⑤(폐기 훅, BR6.6): 구간이 {@code GUARDRAIL_*}가 아닌 사유로 폐기됐다. 재지시 응답이 제브에 닿기 전에 어떤 길로든 폐기되면 턴을 포기하고
     * IDLE로 돌아온다. 이 훅은 마무리(교정 지시 · 대기 문구 · 듣기 복귀)를 하지 않는다 — discard의 기존 마무리가 이어진다.
     */
    void onDiscardedBeforeCheck() {
        if (state == State.RETRYING) {
            state = State.IDLE;
            retryId = null;
        }
    }

    /** 호출 지점 ③: 아이 확정 전사(새 턴). */
    void onNewTurn(String childTranscript) {
        resetTurn();
        if (support.enabled() && childTranscript != null && !childTranscript.isBlank()) {
            remember(GuardrailRequest.Speaker.CHILD, childTranscript);
        }
    }

    /** 끼어들기 · 종료 의도: 새 턴과 같이 재지시 수를 0으로 돌린다(BR6.1). */
    void onTurnBoundary() {
        resetTurn();
    }

    /** 호출 지점 ⑥: 마지막 서버 지시를 기억한다(다른 종류의 지시나 재지시는 null로 지운다, W2b). */
    void onServerInstruction(ReplayableInstruction instruction) {
        this.lastInstruction = instruction;
    }

    /** 호출 지점 ④: 세션 이탈. 진행 중인 요청을 취소하고 모두 놓는다(보관 행은 closeAll이 닫는다, BR4.5). */
    void onExit() {
        cancelInFlight();
        ticket = null;
        queue = null;
        pendingHeld = null;
        retryId = null;
        state = State.IDLE;
    }

    // ---- 검사 시작 · 대기열 ----

    private void start(SegmentBuffer.Sealed sealed, GuardrailRequest.Mode mode, UUID practiceSpeechId,
            TranscriptWriter.Held held, boolean fromQueue) {
        Ticket created = new Ticket(UUID.randomUUID(), sealed, env.deviceGeneration(), env.providerGeneration(),
                env.connectionId(), env.inputGroupOpenCount(), practiceSpeechId, held, mode, fromQueue);
        ticket = created;
        state = State.CHECKING;
        Optional<GuardrailRequest> request = factory.create(mode, sealed.transcript(), List.copyOf(context),
                support.policyVersion(), names());
        if (request.isEmpty()) {
            // 요청을 만들 수 없다(후보 문장 길이 등): 부르지 않고 막힘으로 센다(BR2.4)
            ticket = null;
            resolve(created, GuardrailVerdict.failed(GuardrailVerdict.Failure.INVALID_REQUEST, support.policyVersion(), 0L));
            return;
        }
        Instant deadline = env.now().plus(support.budget());
        CompletionStage<GuardrailVerdict> stage = support.client().check(request.get(), deadline);
        inFlight = stage;
        RealtimeMetrics metrics = env.metrics();
        if (metrics != null) {
            metrics.guardrailCall(support.mode().name(), "new_utterance");
        }
        UUID requestId = created.requestId();
        String policy = support.policyVersion();
        stage.whenComplete((verdict, error) -> env.postFromProvider(new SessionMailbox.GuardrailArrived(requestId,
                verdict != null ? verdict
                        : GuardrailVerdict.failed(GuardrailVerdict.Failure.TRANSPORT_ERROR, policy, 0L))));
        env.armCheckTimer(support.safetyDelay());
    }

    private void enqueue(SegmentBuffer.Sealed sealed, GuardrailRequest.Mode mode, UUID practiceSpeechId) {
        if (queue != null) {
            // 대기열이 찼다(BR5.3): 보관하지 않고 바로 FINAL 거짓으로 닫는다. 앞 검사의 끝(재생 · 마무리)을 건드리지 않는다
            record(UUID.randomUUID(), GuardrailEventRecord.Decision.QUEUE_FULL, REASON_QUEUE_FULL, 0L);
            env.discardSilent(sealed, null, GateDecision.DiscardReason.GUARDRAIL_QUEUE_FULL);
            return;
        }
        TranscriptWriter.Held held = env.holdAiRow();
        pendingHeld = held;
        queue = new Queued(sealed, held, env.deviceGeneration(), env.providerGeneration(), env.connectionId(),
                env.inputGroupOpenCount(), practiceSpeechId, mode);
        pendingHeld = null;
    }

    /** 앞 검사가 끝났다: 대기 구간을 꺼내 검사하거나(유효한 ALLOW로 보낸 경우만) 모두 폐기한다(BR5.4). */
    private void settleQueue(boolean previousSent) {
        Queued waiting = queue;
        if (waiting == null) {
            return;
        }
        queue = null;
        if (!previousSent || !stillValid(waiting)) {
            record(UUID.randomUUID(), GuardrailEventRecord.Decision.VOIDED, REASON_QUEUE_DISCARDED, 0L);
            env.discardSilent(waiting.sealed(), waiting.held(), null);
            return;
        }
        try {
            start(waiting.sealed(), waiting.mode(), waiting.practiceSpeechId(), waiting.held(), true);
        } catch (RuntimeException e) {
            // 앞 구간은 이미 보냈다: 대기 구간만 조용히 버리고 앞 구간의 일에는 번지지 않게 한다(fail-closed)
            env.appError(e);
            cancelInFlight();
            ticket = null;
            state = State.IDLE;
            env.discardSilent(waiting.sealed(), waiting.held(), GateDecision.DiscardReason.GUARDRAIL_ERROR);
        }
    }

    private void discardQueue() {
        settleQueue(false);
    }

    private boolean stillValid(Queued waiting) {
        return env.status() == SessionStatus.ACTIVE && !env.terminated()
                && Objects.equals(env.connectionId(), waiting.connectionId()) && env.connectionId() != null
                && env.providerGeneration() == waiting.providerGeneration()
                && env.deviceGeneration() == waiting.deviceGeneration() && !env.outputHeld()
                && env.inputGroupOpenCount() == waiting.inputGroupOpens();
    }

    // ---- 결과 처리 ----

    private void resolve(Ticket current, GuardrailVerdict verdict) {
        countVerdict(verdict);
        VoidCause cause = voidCause(current);
        if (cause != null) {
            voided(current, verdict, cause);
        } else if (verdict.isAllow()) {
            allowed(current, verdict);
        } else {
            blocked(current, verdict);
        }
    }

    /** 검사표 유효 조건 다섯(BR4.2)을 확인해 첫 어긋남을 돌려준다(없으면 null). */
    private VoidCause voidCause(Ticket current) {
        if (env.terminated() || env.status() != SessionStatus.ACTIVE) {
            return VoidCause.SESSION_ENDED;
        }
        UUID connection = env.connectionId();
        if (connection == null || !connection.equals(current.connectionId())) {
            return VoidCause.CONNECTION_CHANGED;
        }
        if (env.providerGeneration() != current.providerGeneration() || env.deviceGeneration() != current.deviceGeneration()) {
            return VoidCause.GENERATION_CHANGED;
        }
        if (env.outputHeld()) {
            return VoidCause.HELD;
        }
        if (env.inputGroupOpenCount() > current.inputGroupOpens()) {
            return VoidCause.CHILD_SPOKE;
        }
        if (current.practiceSpeechId() != null && !current.practiceSpeechId().equals(env.practiceSpeechId())) {
            return VoidCause.PRACTICE_CHANGED;
        }
        return null;
    }

    /** 무효(BR4.3): 재생도 재지시도 하지 않는다. 기록을 남기고 보관 행을 닫고 대기 구간을 폐기한 뒤 '듣는 중'으로만 돌린다. */
    private void voided(Ticket current, GuardrailVerdict verdict, VoidCause cause) {
        state = State.IDLE;
        retryId = null;
        if (verdict.isAllow()) {
            record(current.requestId(), GuardrailEventRecord.Decision.VOIDED, cause.name(), verdict.latencyMs());
        } else {
            // 낡은 구간의 막힘은 막힘대로 남기되 재지시는 하지 않는다(상태가 이미 바뀌었다)
            record(current.requestId(), verdict.eventDecision(), verdict.reasonCode(), verdict.latencyMs());
        }
        env.discardSilent(current.sealed(), current.held(), null);
        discardQueue();
        env.listeningAgain();
    }

    private void allowed(Ticket current, GuardrailVerdict verdict) {
        state = State.IDLE;
        retryId = null;
        record(current.requestId(), GuardrailEventRecord.Decision.ALLOW, verdict.reasonCode(), verdict.latencyMs());
        if (!current.fromQueue()) {
            env.markCheckedTurn(support.mode().name());
        }
        boolean sent = env.recheckAndSend(current.sealed(), current.held(), !current.fromQueue());
        if (sent) {
            remember(GuardrailRequest.Speaker.MUJOONG, current.sealed().transcript());
        }
        settleQueue(sent);
    }

    private void blocked(Ticket current, GuardrailVerdict verdict) {
        GateDecision.DiscardReason reason = reasonFor(verdict);
        record(current.requestId(), verdict.eventDecision(), verdict.reasonCode(), verdict.latencyMs());
        if (retries >= 1) {
            // 두 번째 막힘(BR6.3): 턴을 포기한다. 고정 교정 문장은 보내지 않고 discard의 기존 마무리만 쓴다
            state = State.IDLE;
            retryId = null;
            discardQueue();
            if (current.mode() != GuardrailRequest.Mode.FREE_TALK) {
                // 연습 모드 요청의 두 번째 막힘은 연습 확장점에 알린다(BR6.5). 운영에서 이 모드를 시작하는 것은 U12다. 알림 실패가 포기를 막지 않는다
                try {
                    env.practiceBlocked(new PracticeSessionExtension.GuardrailBlockedEvent(current.practiceSpeechId(),
                            current.mode(), verdict.reasonCode(), true));
                } catch (RuntimeException e) {
                    env.appError(e);
                }
            }
            env.abandon(current.sealed(), current.held(), reason);
            return;
        }
        retries = 1;
        discardQueue();
        env.discardForRetry(current.sealed(), current.held(), reason, !current.fromQueue());
        ReplayableInstruction replay = lastInstruction;
        lastInstruction = null;
        UUID id = UUID.randomUUID();
        boolean sent = replay != null ? env.sendReplay(replay, id) : env.sendRetryFreeTurn(id);
        if (!sent) {
            retryNotHeard();
            return;
        }
        state = State.RETRYING;
        retryId = id;
        retryProviderGeneration = env.providerGeneration();
        retryDeviceGeneration = env.deviceGeneration();
    }

    /** 재지시가 소리로 이어지지 않는다: 턴을 포기하고 IDLE로 돌아온다. retries는 1로 둔다(더 재지시하지 않는다, BR6.6). */
    private void retryNotHeard() {
        state = State.IDLE;
        retryId = null;
        env.appError(REASON_RETRY_NOT_HEARD, EventCode.Level.WARN);
        discardQueue();
        env.finishAbandoned();
    }

    /** 협력 객체 안 예외: fail-closed. 재생하지 않고 구간을 폐기하며 기기를 '생각 중'에서 푼다. */
    private void failClosed(Ticket current, RuntimeException error) {
        env.appError(error);
        state = State.IDLE;
        retryId = null;
        ticket = null;
        cancelInFlight();
        try {
            discardQueue();
        } catch (RuntimeException ignored) {
            // 대기 구간 정리 실패는 앱 오류로 이미 남겼다. 아래 폐기가 기기를 풀어 준다
            env.appError(REASON_FAULT, EventCode.Level.WARN);
            queue = null;
        }
        env.abandon(current.sealed(), current.held(), GateDecision.DiscardReason.GUARDRAIL_ERROR);
    }

    private void resetTurn() {
        retries = 0;
        lastInstruction = null;
        if (state == State.RETRYING) {
            state = State.IDLE;
            retryId = null;
        }
    }

    /** 재지시 뒤 출력 세대나 기기 세대가 바뀌었으면(끼어들기 · 판정 턴 · 보류 해제 등) 그 재지시 응답은 오지 않는다 — RETRYING을 푼다. */
    private void refreshRetrying() {
        if (state != State.RETRYING) {
            return;
        }
        if (env.terminated() || env.providerGeneration() != retryProviderGeneration
                || env.deviceGeneration() != retryDeviceGeneration) {
            state = State.IDLE;
            retryId = null;
        }
    }

    // ---- 도우미 ----

    /** 판정별 지표(BR8.5 ③ ④): 시간 초과는 따로, 막힘 · 검토 · 검사 실패는 차단 수로 센다. 무효 여부와 상관없이 제브가 낸 결과를 센다. */
    private void countVerdict(GuardrailVerdict verdict) {
        RealtimeMetrics metrics = env.metrics();
        if (metrics == null) {
            return;
        }
        String mode = support.mode().name();
        switch (verdict.decision()) {
            case ALLOW -> {
            }
            case BLOCK -> metrics.guardrailBlocked(mode, "block");
            case REVIEW -> metrics.guardrailBlocked(mode, "review");
            case FAILED -> {
                if (verdict.failure() == GuardrailVerdict.Failure.TIMEOUT) {
                    metrics.guardrailTimeout(mode);
                } else {
                    metrics.guardrailBlocked(mode, "error");
                }
            }
        }
    }

    /** 제브 지연 지표(BR8.5 ②): 요청을 보낸 뒤 결과까지. 부르지 않은 결과(지연 0)는 넣지 않는다. */
    private void countLatency(GuardrailVerdict verdict) {
        RealtimeMetrics metrics = env.metrics();
        if (metrics != null && verdict.latencyMs() > 0L) {
            metrics.guardrailLatency(support.mode().name(), Duration.ofMillis(verdict.latencyMs()));
        }
    }

    private void cancelInFlight() {
        env.cancelCheckTimer();
        CompletionStage<GuardrailVerdict> stage = inFlight;
        inFlight = null;
        if (stage instanceof Future<?> future) {
            future.cancel(true);
        }
    }

    private void remember(GuardrailRequest.Speaker speaker, String text) {
        context.addLast(new GuardrailRequestFactory.Turn(speaker, text));
        while (context.size() > GuardrailRequest.CONTEXT_MAX_TURNS) {
            context.removeFirst();
        }
    }

    private List<String> names() {
        String name = plan.childContext().displayName();
        List<String> names = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            names.add(name);
        }
        return names;
    }

    static GateDecision.DiscardReason reasonFor(GuardrailVerdict verdict) {
        return switch (verdict.decision()) {
            case BLOCK -> GateDecision.DiscardReason.GUARDRAIL_BLOCK;
            case REVIEW -> GateDecision.DiscardReason.GUARDRAIL_REVIEW;
            case FAILED -> verdict.failure() == GuardrailVerdict.Failure.TIMEOUT
                    ? GateDecision.DiscardReason.GUARDRAIL_TIMEOUT
                    : GateDecision.DiscardReason.GUARDRAIL_ERROR;
            case ALLOW -> throw new IllegalArgumentException("ALLOW는 폐기 사유가 없다");
        };
    }

    /** 검사 기록 한 건(BR8.1, 새 발화). 기록 실패는 흐름을 막지 않는다. */
    private void record(UUID id, GuardrailEventRecord.Decision decision, String reasonCode, long latencyMs) {
        record(id, GuardrailEventRecord.SourceKind.NEW_UTTERANCE, decision, reasonCode, latencyMs);
    }

    /** 검사 기록 한 건(BR8.1 · BR7.5, 출처 구분 포함). 기록 실패는 흐름을 막지 않는다. */
    private void record(UUID id, GuardrailEventRecord.SourceKind sourceKind, GuardrailEventRecord.Decision decision,
            String reasonCode, long latencyMs) {
        kr.mujoong.realtime.guardrail.GuardrailEventRecorder recorder = support.recorder();
        if (recorder == null || plan.guardianId() == null) {
            return;
        }
        try {
            int latency = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, latencyMs));
            recorder.put(new GuardrailEventRecord(id, plan.guardianId(), plan.sessionId(), support.mode(),
                    sourceKind, decision, reasonCode, support.policyVersion(), latency, env.now()));
        } catch (RuntimeException e) {
            env.appError(e);
        }
    }
}
