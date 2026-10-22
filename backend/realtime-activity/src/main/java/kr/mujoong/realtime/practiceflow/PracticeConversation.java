package kr.mujoong.realtime.practiceflow;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practiceflow.FlowState.Phase;
import kr.mujoong.realtime.practiceflow.FlowState.Sub;
import kr.mujoong.realtime.practiceflow.FlowTransitions.SlotEnd;
import kr.mujoong.realtime.practiceflow.FlowTransitions.Step;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.practiceflow.PracticeScriptException.Reason;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.voice.PracticeSpeech;

/**
 * 새 연습 흐름의 상태 기계 (U12 FR2 · FR3 · FR5 · BR1 ~ BR6). 기존 확장점({@link PracticeSessionExtension}) 위에 얹혀 출력권을 쥐고(
 * {@link #controlsOutput()}), 도입 → 기회 문장 → 판정 → 전이표({@link FlowTransitions})대로 장면을 진행한다. 실행부는 상태를 모르고 이 클래스는
 * 실행부를 부르지 않는다 — 출력은 {@link PracticeOutput} 능력(재생 · 생성형 발화 · 판정 요청 · 시계 · 비동기 결과)으로만 낸다(한 방향, 순환 의존
 * 없음).
 *
 * <p>규칙 요약.
 *
 * <ul>
 *   <li>시계는 모두 확장점 시계({@code schedule}/{@code cancel})다: {@code no-response} · {@code focus-max} · {@code goal-limit} ·
 *       {@code slot-guard-<correlationId>}(§3.2). 시간 값은 {@link PracticeFlowSettings}가 설정 키에서 읽은 것뿐이고 한도는 만료 표지로 본다(D3).
 *   <li>판정은 {@code requestJudgment}로 요청한다(J2 · J3 · J5에만 판정 기준을 싣고 J4에는 싣지 않는다, D2). 거절(거짓) · 보류 · 장애는 판독
 *       불가와 같이 교정 없이 같은 질문을 한 번 다시 안내하고 두 번째면 정해진 방식으로 닫는다(BR3.7). 연습이 멈추지 않는다.
 *   <li>GPT-Live가 만드는 모든 역할 발화는 {@code speakGenerated} 한 길로만 낸다(D6). 낼 수 없으면(거짓) 그 슬롯을 실패로 닫고 이어 간다.
 *       역할 전환 · 복귀 알림은 승인 문장이 있으면 {@code announceRoleSwitch}로, 없거나 못 내면 생성형으로 낸다(BR5.4).
 *   <li>슬롯 종료 사건은 correlationId마다 첫 사건만 처리하고(중복 무시), 확장이 스스로 닫은 슬롯은 곧바로 처리 완료로 둔다(§3.5).
 *   <li>레거시 평가 · 단서 포트에 기대지 않는다: 이 클래스는 {@code practice} 패키지를 참조하지 않는다(BR2.5 · BR10.2).
 *   <li>아이 전사 · 대본 문장 · 판정 입력은 로그 · 기록 · 예외 메시지에 쓰지 않는다. 오류는 고정 사유 코드로만 알린다(R-F14).
 * </ul>
 */
public final class PracticeConversation implements PracticeSessionExtension {

    static final String TIMER_NO_RESPONSE = "no-response";
    static final String TIMER_FOCUS_MAX = "focus-max";
    static final String TIMER_GOAL_LIMIT = "goal-limit";
    static final String TIMER_SLOT_GUARD = "slot-guard-";
    /** announceRoleSwitch는 실행부가 슬롯을 만들어 correlationId를 모르므로 알림 슬롯은 이 이름 하나의 안전 시계를 쓴다. */
    static final String TIMER_NOTICE_GUARD = "slot-guard-notice";
    /** 다음 목표 대본 비동기 결과의 post 이름. */
    static final String ASYNC_SCRIPT = "practice-script";

    private static final String COACH_SWITCH_CODE = "COACH_SWITCH";
    private static final String PEER_RETURN_CODE = "PEER_RETURN";
    private static final int CORE_FUNCTION_MAX = 500;
    private static final String STAGE_SCENARIO_INTRO = "SCENARIO_INTRO";
    private static final String STAGE_FIRST_RESPONSE = "FIRST_RESPONSE";
    private static final String STAGE_HELP_OFFER = "HELP_OFFER";
    private static final String STAGE_CUE = "CUE";
    private static final String STAGE_AFTER_CUE_RESPONSE = "AFTER_CUE_RESPONSE";
    private static final String STAGE_NEW_SCENARIO_RESPONSE = "NEW_SCENARIO_RESPONSE";

    /** 다음 목표 대본 요청의 결과(비동기). 대본 문장은 문자열 표현에 내지 않는다. */
    record ScriptResult(int goalIndex, PracticeScript script, Reason failure) {
        @Override
        public String toString() {
            return "ScriptResult[goalIndex=" + goalIndex + ", ok=" + (script != null) + "]";
        }
    }

    private final UUID sessionId;
    private final UUID guardianId;
    private final MujoongClock clock;
    private final PracticeFlowSettings settings;
    private final PracticeScriptRequester requester;
    private final FlowRecorder recorder;
    private final Consumer<RuntimeException> failures;
    private final FlowTransitions transitions;
    private final FlowState state = FlowState.initial();
    private final Map<Integer, PracticeScript> scripts = new HashMap<>();
    private final Set<Integer> failedScripts = new HashSet<>();
    private final Set<Integer> requestedScripts = new HashSet<>();
    private final ArrayDeque<Runnable> deferred = new ArrayDeque<>();

    private PracticeOutput output;
    private PracticeStartPackage pack;
    private Set<String> approvedSpeechCodes = Set.of();
    private PracticeScript currentScript;
    private Scene currentScene;
    private UUID sceneRunId;
    private boolean sceneRowCreated;
    private UUID openSlot;
    private boolean openSlotPlaying;
    private boolean approvedReadingRetried;
    private boolean awaitingNotice;
    private boolean inputReady;
    private Utterance earlyUtterance;
    private UUID judgmentCorrelation;
    private boolean judgmentAsksProcedure;
    private ConsentAnswer pendingConsent;
    private int waitingGoal = -1;
    private boolean stopping;
    private Signal pendingSignal = Signal.NONE;
    private long speechGeneration;
    private String lastStage = STAGE_SCENARIO_INTRO;
    private boolean goalAchieved;
    private LiveTiming goalAchievementTiming;
    private int clarificationAttempts;
    private int uncertainAttempts;
    private boolean reflectionAwaiting;
    private boolean reflecting;
    private boolean goalBriefPlayed;
    private boolean goalRecapped;
    private boolean reviewPerformed;
    private boolean reviewAfterHelp;
    private boolean reviewClarified;
    private boolean reviewHelpProvided;
    private boolean reviewInterrupted;
    private boolean recapAdvances;

    /**
     * @param sessionId 이 세션
     * @param guardianId 소유 보호자(기록 행의 소유)
     * @param settings 시계 · 횟수 설정(설정 키에서 읽은 값)
     * @param requester 다음 목표 대본 요청(내부 호출, 안쪽 재시도 없음)
     * @param recorder 장면 · 사실 · 도움 기록 자리(원문 없음)
     * @param failures 앱 오류 한 줄(고정 사유 코드만, 운영은 {@code EventLog})
     */
    public PracticeConversation(UUID sessionId, UUID guardianId, MujoongClock clock, PracticeFlowSettings settings,
            PracticeScriptRequester requester, FlowRecorder recorder, Consumer<RuntimeException> failures) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.guardianId = Objects.requireNonNull(guardianId, "guardianId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.requester = Objects.requireNonNull(requester, "requester");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.transitions = new FlowTransitions(settings.retryMax(), settings.reexplainMax());
    }

    /** 이 상태 기계는 출력권을 쥔다(실행부가 첫 응답 요청 전에 묻는다, D1). */
    @Override
    public boolean controlsOutput() {
        return true;
    }

    @Override
    public boolean collectingIntroduction() {
        return state.phase() == Phase.SCENE_INTRO && openSlot != null && !stopping;
    }
    @Override public boolean canYieldUnplayedSpeech() {
        // No goal achievement, consent, coaching or closing transition may be inferred from an unheard output.
        return !stopping && !reflecting && state.phase() == Phase.OPPORTUNITY
                && (state.sub() == Sub.NONE || state.sub() == Sub.DIALOGUE_REPLY
                || state.sub() == Sub.CLARIFICATION_REPLY || state.sub() == Sub.INPUT_CONFIRMATION);
    }

    // ---- 확장점 시점 ----

    @Override
    public Signal onActive(PracticeContext context, PracticeOutput target) {
        if (output != null) {
            return Signal.NONE;
        }
        // 맥락의 practiceJson이 비었거나 계약 모양이 아니면 PracticeScriptException(BAD_PACKAGE)이 올라가 실행부가 연습을 실패로 처리한다(BR1.2)
        PracticeStartPackage parsed = PracticeStartPackage.parse(context.practiceJson());
        this.pack = parsed;
        this.output = Objects.requireNonNull(target, "output");
        this.approvedSpeechCodes = context.approvedSpeechTexts().keySet();
        if (parsed.firstScenario() != null) {
            scripts.put(0, parsed.firstScenario());
        } else {
            failedScripts.add(0);
            reportFailure(parsed.firstScenarioRejection());
        }
        startGoal(0);
        return finish();
    }

    @Override
    public Signal onChildUtterance(Utterance utterance, PracticeOutput target) {
        if (output == null || stopping || utterance == null || utterance.text() == null || utterance.text().isBlank()
                || utterance.seq() <= 0) {
            return Signal.NONE;
        }
        Phase phase = state.phase();
        if (reflecting) {
            reflectionUtterance(utterance);
            return finish();
        }
        if (phase.awaitsInput() && state.sub() == Sub.NONE) {
            if (!inputReady) {
                // 우리 질문이 아직 재생 중이다. 재생이 끝나는 대로 이 전사를 판정한다
                earlyUtterance = utterance;
            } else if (!state.judgmentOpen()) {
                judge(utterance);
            }
        } else if (phase.awaitsInput() && (state.sub() == Sub.REEXPLAIN || state.sub() == Sub.DIALOGUE_REPLY
                || state.sub() == Sub.CLARIFICATION_REPLY || state.sub() == Sub.INPUT_CONFIRMATION)) {
            // 재설명 중의 답도 보관하고 재생 종료 후 같은 판정 지점으로 이어 준다.
            earlyUtterance = utterance;
        } else if (phase == Phase.FOCUS && state.sub() == Sub.COACH_UTTERANCE) {
            // 코치 발화에 끼어든 전사는 재생이 끝난 뒤 RETRY 진입에서 곧바로 J5 입력으로 처리한다(§3.3)
            earlyUtterance = utterance;
        }
        return finish();
    }

    private void reflectionUtterance(Utterance utterance) {
        if (state.judgmentOpen()) return;
        if (!reflectionAwaiting || openSlot != null) earlyUtterance = utterance;
        else if (utterance.uncertain()) finishReflection();
        else if (state.sub() == Sub.REFLECTION_OFFER) judge(utterance);
        else {
            reflectionAwaiting = false;
            output.cancel(TIMER_NO_RESPONSE);
            state.phase(Phase.SCENE_INTRO, Sub.REFLECTION_ACK);
            speakGenerated(FlowRole.PEER, PracticeInstructions.reflectionAcknowledgment());
        }
    }

    @Override
    public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput target) {
        if (output == null || stopping) {
            return Signal.NONE;
        }
        switch (event.kind()) {
            case PLAYBACK_FACT -> {
                if (event.value() instanceof PlaybackFact fact) {
                    onPlayback(fact, event.correlationId());
                }
            }
            case TIMER_FIRED -> onTimer(event.name());
            case ASYNC_RESULT -> {
                if (ASYNC_SCRIPT.equals(event.name()) && event.value() instanceof ScriptResult result) {
                    onScript(result);
                } else if ("approved-text-mismatch".equals(event.name()) && event.value() instanceof PracticeSpeech speech) {
                    onApprovedTextMismatch(speech);
                }
            }
            case JUDGMENT_RESULT -> onJudgmentResult(event);
            case JUDGMENT_ABSTAINED -> onJudgmentAbstained(event);
            case GUARDRAIL_BLOCKED -> {
                if (event.value() instanceof GuardrailBlockedEvent blocked) {
                    handleSlotEnd(blocked.correlationId(), SlotEnd.BLOCKED);
                }
            }
            case REST_REQUESTED -> onRest();
            default -> {
                // PLAYBACK_DONE(playApproved를 쓰지 않는다) · HELD_OUTPUT_TRANSCRIPT(모은 출력 전사는 저장하지 않는다, R-F14)
            }
        }
        return finish();
    }

    @Override
    public void onStopping() {
        if (stopping) {
            return;
        }
        stopping = true;
        // 실행부는 종료 전이 때 확장점 시계를 직접 비우고 ACTIVE가 아닌 출력 능력 호출은 거절한다. 여기서는 기록만 닫는다
        if (sceneRowCreated && (state.sceneEndCause() == null
                || (state.phase() == Phase.SCENE_WRAP && state.sceneEndCause() == FlowTransitions.EndCause.NORMAL))
                && state.phase() != Phase.CLOSING
                && state.phase() != Phase.NEXT_GOAL) {
            recordFact(FactKind.END_REQUEST);
            recordOutcome(VariantSkipReason.END_OR_REST, LiveTiming.NONE);
        }
    }

    @Override
    public void onSettle(EndContext end) {
        onStopping();
    }

    @Override
    public Object practiceRecord() {
        return recorder.practiceRecord();
    }

    /** 보호자 화면 진행 6값으로 접은 단계(BR8.1, §4). 접기는 이 표시 매핑에만 있고 저장 값은 새 단계를 사실대로 둔다. */
    @Override
    public Progress progress() {
        if (pack == null) {
            return null;
        }
        return new Progress(state.goalIndex() + 1, pack.goals().size(), pack.goals().get(state.goalIndex()).goalName(), stage());
    }

    private String stage() {
        if (state.phase() == Phase.CLOSING) {
            return lastStage;
        }
        if (state.sceneKind() == SceneKind.VARIANT && state.phase() != Phase.NEXT_GOAL) {
            lastStage = STAGE_NEW_SCENARIO_RESPONSE;
            return lastStage;
        }
        lastStage = switch (state.phase()) {
            case SCENE_INTRO, NEXT_GOAL -> STAGE_SCENARIO_INTRO;
            case OPPORTUNITY, REASK -> STAGE_FIRST_RESPONSE;
            case HELP_OFFER -> STAGE_HELP_OFFER;
            case FOCUS -> STAGE_CUE;
            case RETRY, SCENE_WRAP, VARIANT_OFFER -> STAGE_AFTER_CUE_RESPONSE;
            case CLOSING -> lastStage;
        };
        return lastStage;
    }

    // ---- 목표 · 장면 시작 ----

    /** 다음 목표를 시작한다. 대본이 거절된 목표는 '미실시'로 건너뛰고, 아직 안 온 대본은 기다린다(BR1.3). */
    private void startGoal(int index) {
        int next = index;
        while (next < pack.goals().size()) {
            if (scripts.containsKey(next)) {
                beginGoal(next);
                return;
            }
            if (failedScripts.contains(next)) {
                next++;
                continue;
            }
            state.phase(Phase.NEXT_GOAL, Sub.NONE);
            waitingGoal = next;
            requestScript(next);
            return;
        }
        closing(false);
    }

    private void beginGoal(int index) {
        currentScript = scripts.get(index);
        state.goal(index, index == pack.goals().size() - 1, clock.now());
        goalBriefPlayed = false;
        goalRecapped = false;
        reviewPerformed = reviewAfterHelp = reviewClarified = reviewHelpProvided = reviewInterrupted = false;
        output.schedule(TIMER_GOAL_LIMIT, settings.goalActiveLimit());
        requestScript(index + 1);
        enterScene(SceneKind.BASE);
    }

    private void enterScene(SceneKind kind) {
        Scene scene = kind == SceneKind.VARIANT ? currentScript.variantScene().orElse(null) : currentScript.baseScene();
        if (scene == null) {
            // 수락된 변형에 쓸 장면이 없다(변형 평가가 막으므로 일어나지 않는다). 연습을 멈추지 않고 다음으로 간다
            moveOn();
            return;
        }
        currentScene = scene;
        state.resetScene(kind);
        state.phase(Phase.SCENE_INTRO, Sub.NONE);
        sceneRunId = UUID.randomUUID();
        sceneRowCreated = false;
        earlyUtterance = null;
        pendingConsent = null;
        goalAchieved = false;
        goalAchievementTiming = null;
        clarificationAttempts = 0;
        uncertainAttempts = 0;
        if (settings.flexibleDialogue() && kind == SceneKind.BASE && !goalBriefPlayed) {
            state.phase(Phase.SCENE_INTRO, Sub.GOAL_BRIEF);
            speakGenerated(FlowRole.PEER, PracticeInstructions.goalBrief(pack.goals().get(state.goalIndex()).goalName(),
                    state.goalIndex() + 1, pack.goals().size()));
            return;
        }
        beginReflectionOrIntroduction();
    }

    private void beginReflectionOrIntroduction() {
        Scene scene = currentScene;
        if (settings.flexibleDialogue() && state.sceneKind() == SceneKind.BASE && scene.reflectionPrompt() != null) {
            reflecting = true;
            reflectionAwaiting = false;
            state.phase(Phase.SCENE_INTRO, Sub.REFLECTION_OFFER);
            speakGenerated(FlowRole.PEER, PracticeInstructions.reflectionOffer());
            return;
        }
        beginIntroduction();
    }

    private void finishReflection() {
        reflecting = false;
        reflectionAwaiting = false;
        earlyUtterance = null;
        output.cancel(TIMER_NO_RESPONSE);
        beginIntroduction();
    }

    private void beginIntroduction() {
        state.phase(Phase.SCENE_INTRO, Sub.NONE);
        Scene scene = currentScene;
        if (settings.flexibleDialogue()) speakApproved(PracticeInstructions.spokenIntroduction(scene));
        else speakApproved(scene.sceneDescription());
    }

    private void moveOn() {
        if (state.lastGoal()) {
            closing(false);
        } else {
            advanceGoal();
        }
    }

    private void advanceGoal() {
        if (beginGoalRecap(true)) return;
        output.cancel(TIMER_GOAL_LIMIT);
        output.cancel(TIMER_FOCUS_MAX);
        output.cancel(TIMER_NO_RESPONSE);
        pendingSignal = Signal.NEXT_GOAL;
        startGoal(state.goalIndex() + 1);
    }

    private boolean outputFailed;

    private void closing(boolean byRequest) {
        if (!byRequest && beginGoalRecap(false)) return;
        output.cancel(TIMER_GOAL_LIMIT);
        output.cancel(TIMER_FOCUS_MAX);
        output.cancel(TIMER_NO_RESPONSE);
        cancelOpenSlot();
        state.phase(Phase.CLOSING, Sub.NONE);
        if (byRequest) {
            // 종료 · 휴식 판정은 실행부(J6) 소유다. 확장은 말을 멈추고 출력을 막은 채 실행부의 결정을 기다린다(신호를 내지 않는다)
            output.holdOutput(true);
        } else {
            pendingSignal = outputFailed ? Signal.OUTPUT_FAILED : Signal.LAST_GOAL;
        }
    }

    private boolean beginGoalRecap(boolean advances) {
        if (!settings.flexibleDialogue() || goalRecapped || !sceneRowCreated || outputFailed || stopping) return false;
        goalRecapped = true;
        recapAdvances = advances;
        output.cancel(TIMER_GOAL_LIMIT);
        output.cancel(TIMER_FOCUS_MAX);
        output.cancel(TIMER_NO_RESPONSE);
        cancelOpenSlot();
        dropJudgment();
        earlyUtterance = null;
        state.phase(Phase.SCENE_WRAP, Sub.GOAL_RECAP);
        speakGenerated(FlowRole.PEER, PracticeInstructions.goalRecap(pack.goals().get(state.goalIndex()).goalName(),
                reviewPerformed, reviewAfterHelp, reviewClarified, reviewHelpProvided, reviewInterrupted, !advances));
        return true;
    }

    // ---- 다음 목표 대본 ----

    private void requestScript(int index) {
        if (index >= pack.goals().size() || scripts.containsKey(index) || failedScripts.contains(index)
                || !requestedScripts.add(index)) {
            return;
        }
        PracticeOutput out = output;
        try {
            requester.request(sessionId, pack.goals().get(index).goalSelectionId()).whenComplete((script, failure) -> {
                Reason reason = null;
                if (failure != null) {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                    reason = cause instanceof PracticeScriptException e ? e.reason() : Reason.REQUEST_FAILED;
                }
                out.post(ASYNC_SCRIPT, new ScriptResult(index, failure == null ? script : null, reason));
            });
        } catch (RuntimeException e) {
            // 호출을 시작하지도 못했다: 그 목표는 미실시로 두고 연습은 잇는다(BR1.3). 원인은 고정 사유 코드로만 남긴다
            failedScripts.add(index);
            reportFailure(Reason.REQUEST_FAILED);
        }
    }

    private void onScript(ScriptResult result) {
        if (result.script() != null) {
            scripts.put(result.goalIndex(), result.script());
        } else {
            failedScripts.add(result.goalIndex());
            reportFailure(result.failure());
        }
        if (waitingGoal == result.goalIndex()) {
            int waiting = waitingGoal;
            waitingGoal = -1;
            startGoal(waiting);
        }
    }

    // ---- 아이 말 · 판정 ----

    private void judge(Utterance utterance) {
        if (settings.flexibleDialogue() && !reflecting && state.phase() == Phase.OPPORTUNITY && utterance.uncertain()) {
            confirmUncertainInput(false);
            return;
        }
        JudgmentPoint point = expectedJudgmentPoint();
        UUID correlation = UUID.randomUUID();
        output.cancel(TIMER_NO_RESPONSE);
        judgmentCorrelation = correlation;
        judgmentAsksProcedure = PracticeProcedureQuestion.matches(utterance.text());
        state.judgmentOpen(true);
        boolean accepted = output.requestJudgment(point, utterance.seq(), correlation, criteriaFor(point));
        if (!accepted) {
            // 거절(열린 턴 · 공급자 없음 · 전사 범위 밖 등)은 보류와 같게 이어 간다. 연습은 멈추지 않는다(BR3.7)
            judgmentCorrelation = null;
            judgmentAsksProcedure = false;
            state.judgmentOpen(false);
            unavailableJudgment(point);
        }
    }

    /** J2 · J3 · J5에만 판정 기준을 싣는다(D2). coreFunction은 목표의 판정 기준 문자열에서 만든다(상한 안으로 줄인다). */
    private Map<String, Object> criteriaFor(JudgmentPoint point) {
        if (reflecting) return null;
        if (settings.flexibleDialogue() && goalAchieved && state.phase() == Phase.OPPORTUNITY) {
            return Map.of("coreFunction", "ROLE_DIALOGUE_CLOSURE", "judgmentCriteria",
                    "친구 역할 대화에서 제안에 동의하거나 대안을 합의하는 등 현재 상황이 정리됐는지 본다. "
                    + "새 질문·이유 묻기·조건 제시·반대·진행 방법 질문은 대화가 더 필요하다. 짧은 응도 직전 제안을 받아들이는 맥락이면 정리될 수 있다.");
        }
        if (point != JudgmentPoint.J2 && point != JudgmentPoint.J3 && point != JudgmentPoint.J5) {
            return null;
        }
        String criteria = pack.goals().get(state.goalIndex()).judgmentCriteria();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("coreFunction", criteria.length() <= CORE_FUNCTION_MAX ? criteria : criteria.substring(0, CORE_FUNCTION_MAX));
        map.put("judgmentCriteria", criteria);
        return map;
    }

    private void onJudgmentResult(ExtensionEvent event) {
        boolean asksProcedure = judgmentAsksProcedure;
        if (!closeJudgment(event.correlationId())) {
            return;
        }
        if (!(event.value() instanceof Map<?, ?> value)) {
            // 모양이 틀린 결과는 보류와 같게 이어 간다(교정 없이, 연습은 멈추지 않는다)
            unavailableJudgment(expectedJudgmentPoint());
            return;
        }
        JudgmentPoint point = pointOf(value.get("point"));
        Object first = value.get("firstOption");
        String option = first == null ? null : first.toString();
        if (reflecting) {
            if ("ACCEPT".equals(option)) {
                reflectionAwaiting = false;
                state.phase(Phase.SCENE_INTRO, Sub.REFLECTION_PROMPT);
                speakApproved(currentScene.reflectionPrompt());
            } else finishReflection();
            return;
        }
        if (settings.flexibleDialogue() && flexibleJudgment(point, option, asksProcedure)) return;
        // Preserve the model's recorded result; the flow must not finish on a recognized question about how to practise.
        if (asksProcedure && (point == JudgmentPoint.J2 || point == JudgmentPoint.J3 || point == JudgmentPoint.J5)
                && ("FULL".equals(option) || "DELIVERED".equals(option) || "COPIED".equals(option))) option = "HELP";
        afterJudgment(point == null ? Step.ignore() : transitions.onJudgment(state, point, option));
    }

    private void onJudgmentAbstained(ExtensionEvent event) {
        if (!closeJudgment(event.correlationId())) {
            return;
        }
        JudgmentPoint point = event.value() instanceof Map<?, ?> value ? pointOf(value.get("point"))
                : expectedJudgmentPoint();
        unavailableJudgment(point);
    }

    private JudgmentPoint expectedJudgmentPoint() {
        if (reflecting) return JudgmentPoint.J4;
        return settings.flexibleDialogue() && goalAchieved && state.phase() == Phase.OPPORTUNITY
                ? JudgmentPoint.J3 : FlowTransitions.expectedPoint(state.phase());
    }

    private void unavailableJudgment(JudgmentPoint point) {
        if (reflecting) { finishReflection(); return; }
        if (settings.flexibleDialogue() && state.phase() == Phase.OPPORTUNITY) {
            confirmUncertainInput(true);
        } else {
            afterJudgment(point == null ? Step.ignore() : transitions.onJudgmentUnavailable(state, point));
        }
    }

    private boolean flexibleJudgment(JudgmentPoint point, String option, boolean asksProcedure) {
        if (state.phase() == Phase.OPPORTUNITY && point == expectedJudgmentPoint()) {
            if ("UNREADABLE".equals(option)) {
                confirmUncertainInput(false);
            } else if (asksProcedure || "HELP".equals(option)) {
                uncertainAttempts = 0;
                applyStep(transitions.onJudgment(state, JudgmentPoint.J2, "HELP"));
            } else if ("NOT_UNDERSTOOD".equals(option)) {
                uncertainAttempts = 0;
                if (state.reexplainCount() < settings.reexplainMax()) applyStep(Step.reexplain(Phase.OPPORTUNITY));
                else applyStep(Step.enter(Phase.HELP_OFFER, Sub.NONE));
            } else if (!goalAchieved && "PARTIAL".equals(option)) {
                uncertainAttempts = 0;
                if (++clarificationAttempts > 1) applyStep(Step.enter(Phase.HELP_OFFER, Sub.NONE));
                else applyStep(Step.enter(Phase.OPPORTUNITY, Sub.CLARIFICATION_REPLY));
            } else if (goalAchieved && "DELIVERED".equals(option)) {
                applyStep(Step.enter(Phase.SCENE_WRAP, Sub.PEER_REPLY)
                        .cause(FlowTransitions.EndCause.NORMAL).timing(goalAchievementTiming));
            } else {
                if (!goalAchieved && "FULL".equals(option)) {
                    goalAchieved = true;
                    goalAchievementTiming = clarificationAttempts > 0 ? LiveTiming.AFTER_REASK : LiveTiming.IMMEDIATE;
                    if (clarificationAttempts > 0) recordFact(FactKind.REASK_RESOLVED);
                }
                uncertainAttempts = 0;
                continueDialogue();
            }
            return true;
        }
        if (state.phase() == Phase.RETRY && point == JudgmentPoint.J5 && !asksProcedure
                && ("FULL".equals(option) || "COPIED".equals(option))) {
            if (!goalAchieved) goalAchievementTiming = LiveTiming.AFTER_HELP;
            goalAchieved = true;
            applyStep(Step.enter(Phase.OPPORTUNITY, Sub.RETURN_NOTICE));
            return true;
        }
        return false;
    }

    private void continueDialogue() {
        applyStep(Step.enter(Phase.OPPORTUNITY, Sub.DIALOGUE_REPLY));
    }

    private void confirmUncertainInput(boolean abstained) {
        output.cancel(TIMER_NO_RESPONSE);
        if (++uncertainAttempts > 1) {
            applyStep(Step.enter(Phase.SCENE_WRAP, Sub.VARIANT_EVAL)
                    .cause(abstained ? FlowTransitions.EndCause.ABSTAINED : FlowTransitions.EndCause.TRANSCRIPT_FAILED)
                    .timing(abstained ? LiveTiming.ABSTAINED : LiveTiming.NONE)
                    .fact(abstained ? null : FactKind.TRANSCRIPT_FAILED));
        } else applyStep(Step.enter(Phase.OPPORTUNITY, Sub.INPUT_CONFIRMATION));
    }

    /** 이 사건이 지금 열린 판정의 것인가(늦은 · 다른 판정의 사건은 무시). 맞으면 판정을 닫는다. */
    private boolean closeJudgment(UUID correlationId) {
        if (judgmentCorrelation == null || !judgmentCorrelation.equals(correlationId)) {
            return false;
        }
        judgmentCorrelation = null;
        judgmentAsksProcedure = false;
        state.judgmentOpen(false);
        return true;
    }

    private static JudgmentPoint pointOf(Object value) {
        try {
            return value == null ? null : JudgmentPoint.valueOf(value.toString());
        } catch (IllegalArgumentException unknown) {
            // 모르는 지점 이름은 표 밖 입력으로 본다(내용 없이 무시)
            return null;
        }
    }

    private void afterJudgment(Step step) {
        if (step.action() == FlowTransitions.Action.IGNORE) {
            unexpected();
            if (state.phase().awaitsInput() && state.sub() == Sub.NONE && inputReady) {
                armNoResponse();
            }
            return;
        }
        applyStep(step);
    }

    // ---- 슬롯 · 재생 사건 ----

    private void onPlayback(PlaybackFact fact, UUID eventCorrelation) {
        UUID correlation = fact.speech() != null ? fact.speech().correlationId() : eventCorrelation;
        if (fact.started()) {
            if (correlation != null && correlation.equals(openSlot) && !openSlotPlaying) {
                // Generation and actual playback each have a bounded guard. Duplicate STARTED marks cannot extend it.
                output.cancel(slotGuardName());
                openSlotPlaying = true;
                output.schedule(slotGuardName(), settings.slotGuard());
            }
            return;
        }
        SlotEnd end = fact.played() ? SlotEnd.PLAYED : fact.interrupted() ? SlotEnd.INTERRUPTED : SlotEnd.FAILED;
        handleSlotEnd(correlation, end);
    }

    /** 슬롯 종료 사건 하나. correlationId마다 첫 사건만 처리하고 확장이 닫은 슬롯 · 모르는 슬롯의 늦은 사건은 무시한다(§3.5). */
    private void handleSlotEnd(UUID correlation, SlotEnd end) {
        if (correlation == null || state.isHandled(correlation)) {
            return;
        }
        boolean mine = correlation.equals(openSlot);
        boolean notice = !mine && awaitingNotice;
        if (!mine && !notice) {
            return;
        }
        state.markHandled(correlation);
        if (end == SlotEnd.FAILED || end == SlotEnd.BLOCKED
                || (end == SlotEnd.INTERRUPTED && state.sub() == Sub.PEER_REPLY)) {
            outputFailed = true;
        }
        if (mine) {
            output.cancel(slotGuardName());
            openSlot = null;
            openSlotPlaying = false;
        } else {
            output.cancel(TIMER_NOTICE_GUARD);
            awaitingNotice = false;
        }
        if (mine && state.sub() == Sub.GOAL_RECAP) {
            // No retry or approved-speech fallback: safety refusal and missing playback stay failures.
            if (end != SlotEnd.PLAYED) outputFailed = true;
            if (recapAdvances && !outputFailed) advanceGoal();
            else closing(false);
            return;
        }
        if (mine && state.sub() == Sub.GOAL_BRIEF && end == SlotEnd.PLAYED) {
            goalBriefPlayed = true;
            beginReflectionOrIntroduction();
            return;
        }
        if (mine && state.sub() == Sub.GOAL_BRIEF && end == SlotEnd.INTERRUPTED) {
            // A played, guarded brief interrupted by speech is not a transport failure.
            // Continue to the reviewed scene introduction; never repeat the brief indefinitely.
            goalBriefPlayed = true;
            beginReflectionOrIntroduction();
            return;
        }
        if (reflecting && mine && (end == SlotEnd.PLAYED || end == SlotEnd.INTERRUPTED)) {
            if (state.sub() == Sub.REFLECTION_ACK) finishReflection();
            else {
                reflectionAwaiting = true;
                output.schedule(TIMER_NO_RESPONSE, settings.noResponseWait());
                if (earlyUtterance != null) {
                    Utterance early = earlyUtterance;
                    earlyUtterance = null;
                    reflectionUtterance(early);
                }
            }
            return;
        }
        if (mine && state.phase() == Phase.SCENE_INTRO && !sceneRowCreated
                && (end == SlotEnd.FAILED || end == SlotEnd.BLOCKED)) {
            // 들려주지 못한 도입을 '장면 완료'로 건너뛰지 않는다. 안전 검사 실패도 그대로 막는다.
            output.cancel(TIMER_GOAL_LIMIT);
            state.phase(Phase.CLOSING, Sub.NONE);
            pendingSignal = Signal.OUTPUT_FAILED;
            return;
        }
        boolean introEnded = state.phase() == Phase.SCENE_INTRO && state.sub() == Sub.NONE && !sceneRowCreated
                && (end == SlotEnd.PLAYED || end == SlotEnd.INTERRUPTED);
        if (introEnded) {
            createSceneRow();
        }
        if (settings.flexibleDialogue() && state.phase() == Phase.OPPORTUNITY) {
            if ((state.sub() == Sub.DIALOGUE_REPLY || state.sub() == Sub.CLARIFICATION_REPLY || state.sub() == Sub.INPUT_CONFIRMATION)
                    && (end == SlotEnd.PLAYED || end == SlotEnd.INTERRUPTED)) {
                applyStep(Step.enter(Phase.OPPORTUNITY, Sub.NONE));
                return;
            }
            if (state.sub() == Sub.RETURN_NOTICE && end == SlotEnd.PLAYED) {
                applyStep(Step.enter(Phase.OPPORTUNITY, Sub.DIALOGUE_REPLY)
                        .role(FlowRole.PEER).fact(FactKind.COACH_RETURN).fact(FactKind.ORIGINAL_SCENE_RETURN));
                return;
            }
            if (state.sub() == Sub.RETURN_NOTICE) {
                outputFailed = true;
                applyStep(Step.enter(Phase.SCENE_WRAP, Sub.VARIANT_EVAL).role(FlowRole.PEER)
                        .cause(FlowTransitions.EndCause.PLAYBACK_FAILED).timing(goalAchievementTiming)
                        .fact(FactKind.PLAYBACK_FAILED));
                return;
            }
        }
        Step step = transitions.onSlotEnded(state, end);
        if (step.action() == FlowTransitions.Action.IGNORE) {
            unexpected();
            return;
        }
        applyStep(step);
    }

    private void onTimer(String name) {
        if (reflecting && TIMER_NO_RESPONSE.equals(name)) { finishReflection(); return; }
        switch (name) {
            case TIMER_NO_RESPONSE -> applyStep(transitions.onNoResponse(state));
            case TIMER_FOCUS_MAX -> onFocusMax();
            case TIMER_GOAL_LIMIT -> onGoalLimit();
            case TIMER_NOTICE_GUARD -> {
                if (awaitingNotice) {
                    awaitingNotice = false;
                    applyStep(transitions.onSlotEnded(state, SlotEnd.FAILED));
                }
            }
            default -> {
                if (name.startsWith(TIMER_SLOT_GUARD) && openSlot != null && name.equals(slotGuardName())) {
                    // 종료 사건이 끝내 오지 않았다: 실패로 본다(안전망). 늦게 닿는 사건은 처리 완료로 무시한다
                    UUID stuck = openSlot;
                    output.cancelSpeech(stuck);
                    handleSlotEnd(stuck, SlotEnd.FAILED);
                }
            }
        }
    }

    private void onFocusMax() {
        if (state.phase() != Phase.FOCUS && state.phase() != Phase.RETRY) {
            return;
        }
        state.focusLimitReached(true);
        Step step = transitions.onFocusLimit(state);
        if (step.action() != FlowTransitions.Action.IGNORE) {
            cancelOpenSlot();
            dropJudgment();
            applyStep(step);
        }
    }

    private void onGoalLimit() {
        state.goalLimitReached(true);
        Step step = transitions.onGoalLimit(state);
        if (step.action() != FlowTransitions.Action.IGNORE) {
            cancelOpenSlot();
            dropJudgment();
            applyStep(step);
        }
    }

    private void onRest() {
        Step step = transitions.onEndRequested(state);
        if (step.action() == FlowTransitions.Action.IGNORE) {
            return;
        }
        cancelOpenSlot();
        dropJudgment();
        applyStep(step);
    }

    /** 열린 판정 표지만 거둔다(결과가 늦게 와도 correlationId가 달라 무시된다). */
    private void dropJudgment() {
        judgmentCorrelation = null;
        judgmentAsksProcedure = false;
        state.judgmentOpen(false);
    }

    // ---- 전이 적용 ----

    private void applyStep(Step step) {
        switch (step.action()) {
            case IGNORE -> {
                // 표에 없는 입력: 상태를 바꾸지 않는다
            }
            case AWAIT -> awaitInput();
            case REGUIDE -> reguide(step);
            case REEXPLAIN -> reexplain(step);
            case ENTER -> enter(step);
        }
    }

    private void awaitInput() {
        inputReady = true;
        if (stopping || !state.phase().awaitsInput() || state.sub() != Sub.NONE) {
            return;
        }
        if (earlyUtterance != null) {
            Utterance early = earlyUtterance;
            earlyUtterance = null;
            judge(early);
            return;
        }
        armNoResponse();
    }

    private void armNoResponse() {
        if (!state.judgmentOpen()) {
            output.schedule(TIMER_NO_RESPONSE, settings.noResponseWait());
        }
    }

    private void reguide(Step step) {
        output.cancel(TIMER_NO_RESPONSE);
        earlyUtterance = null;
        switch (step.guard()) {
            case NO_RESPONSE -> state.noResponseGuided(true);
            case UNREADABLE -> state.unreadableGuided(true);
            case NONE -> {
                // 재안내 표지 없음
            }
        }
        switch (state.phase()) {
            case OPPORTUNITY -> {
                if (settings.flexibleDialogue()) speakGenerated(FlowRole.PEER, PracticeInstructions.peerCheckIn(currentScene));
                else speakApproved(currentScene.opportunitySentence());
            }
            case REASK -> speakGenerated(FlowRole.PEER, PracticeInstructions.peerReask(currentScene, true));
            case HELP_OFFER -> speakGenerated(FlowRole.PEER, PracticeInstructions.peerHelpOffer(currentScene, true));
            case RETRY -> speakGenerated(FlowRole.COACH, PracticeInstructions.coachRetryPrompt(currentScene));
            case VARIANT_OFFER -> speakGenerated(FlowRole.PEER, PracticeInstructions.peerVariantOffer(currentScene, true));
            default -> {
                // 입력을 기다리는 단계가 아니면 재안내하지 않는다
            }
        }
    }

    private void reexplain(Step step) {
        output.cancel(TIMER_NO_RESPONSE);
        earlyUtterance = null;
        state.incrementReexplain();
        step.facts().forEach(this::recordFact);
        state.phase(step.phase(), Sub.REEXPLAIN);
        help(HelpKind.SITUATION_EXPLAIN, state.role(), false, null, currentScene.reexplanation().length());
        speakApproved(currentScene.reexplanation());
    }

    private void enter(Step step) {
        Phase previous = state.phase();
        Sub previousSub = state.sub();
        output.cancel(TIMER_NO_RESPONSE);
        if (step.consent() != null) {
            pendingConsent = step.consent();
        }
        if (step.consentsToHelp()) {
            state.helpConsented(true);
        }
        if (step.role() != null && step.role() != state.role()) {
            help(step.role() == FlowRole.COACH ? HelpKind.ROLE_SWITCH : HelpKind.ROLE_RETURN, step.role(), false,
                    step.role() == FlowRole.COACH ? pendingConsent : null, 0);
            state.role(step.role());
        }
        if (step.incrementsRetry()) {
            state.incrementRetry();
        }
        if (step.cause() != null) {
            state.sceneEnd(step.cause(), step.timing() == null ? LiveTiming.NONE : step.timing());
        }
        if (step.endsByRequest()) {
            state.endRequested(true);
        }
        state.phase(step.phase(), step.sub());
        state.noResponseGuided(false);
        state.unreadableGuided(false);
        step.facts().forEach(this::recordFact);
        if (step.cause() != null && step.phase() == Phase.SCENE_WRAP) {
            wrapScene();
        }
        if (step.skip() != null) {
            recordOutcome(step.skip(), state.sceneTiming());
        }
        dispatch(step, previous, previousSub);
    }

    /** 장면 정리 진입: 시계 · 열린 판정 · 끼어든 전사를 거두고 장면 결과를 남긴다. */
    private void wrapScene() {
        output.cancel(TIMER_FOCUS_MAX);
        cancelOpenSlot();
        dropJudgment();
        earlyUtterance = null;
        if (state.sceneEndCause() != FlowTransitions.EndCause.NORMAL) {
            recordOutcome(null, state.sceneTiming());
        }
    }

    private void dispatch(Step step, Phase previous, Sub previousSub) {
        switch (step.phase()) {
            case SCENE_INTRO -> enterScene(SceneKind.VARIANT);
            case OPPORTUNITY -> {
                if (step.sub() == Sub.DIALOGUE_REPLY) speakGenerated(FlowRole.PEER, PracticeInstructions.peerDialogue(currentScene));
                else if (step.sub() == Sub.CLARIFICATION_REPLY) speakGenerated(FlowRole.PEER, PracticeInstructions.peerClarification(currentScene));
                else if (step.sub() == Sub.INPUT_CONFIRMATION) speakGenerated(FlowRole.PEER, PracticeInstructions.inputConfirmation());
                else if (step.sub() == Sub.RETURN_NOTICE) announce(FlowRole.PEER);
                else if (previousSub == Sub.REEXPLAIN || previousSub == Sub.DIALOGUE_REPLY
                        || previousSub == Sub.CLARIFICATION_REPLY || previousSub == Sub.INPUT_CONFIRMATION) awaitInput();
                else speakApproved(currentScene.opportunitySentence());
            }
            case REASK -> speakGenerated(FlowRole.PEER, PracticeInstructions.peerReask(currentScene, false));
            case HELP_OFFER -> speakGenerated(FlowRole.PEER, PracticeInstructions.peerHelpOffer(currentScene, false));
            case FOCUS -> {
                if (step.sub() == Sub.ROLE_NOTICE) {
                    announce(FlowRole.COACH);
                } else {
                    coachUtterance();
                }
            }
            case RETRY -> awaitInput();
            case SCENE_WRAP -> {
                if (step.sub() == Sub.RETURN_NOTICE) {
                    announce(FlowRole.PEER);
                } else if (step.sub() == Sub.PEER_REPLY) {
                    speakGenerated(FlowRole.PEER, PracticeInstructions.peerCompletion(currentScene));
                } else {
                    recordOutcome(null, state.sceneTiming());
                    applyStep(transitions.evaluateVariant(state, currentScript.variantScene().isPresent()));
                }
            }
            case VARIANT_OFFER -> speakGenerated(FlowRole.PEER, PracticeInstructions.peerVariantOffer(currentScene, false));
            case NEXT_GOAL -> advanceGoal();
            case CLOSING -> {
                boolean sceneWasOpen = (previous != Phase.SCENE_WRAP || previousSub == Sub.PEER_REPLY
                        || previousSub == Sub.RETURN_NOTICE) && previous != Phase.NEXT_GOAL && previous != Phase.CLOSING;
                if (step.endsByRequest() && sceneWasOpen) {
                    state.sceneEnd(FlowTransitions.EndCause.END_REQUEST, LiveTiming.NONE);
                    recordOutcome(VariantSkipReason.END_OR_REST, LiveTiming.NONE);
                }
                closing(step.endsByRequest());
            }
        }
    }

    // ---- 출력 ----

    /** 코치 첫 도움 · 다시 해 보기 권유 발화(FOCUS 진입 ②). 집중 시계는 코치가 말하기 시작할 때 한 번 건다. */
    private void coachUtterance() {
        if (state.focusStartedAt() == null) {
            state.focusStartedAt(clock.now());
            output.schedule(TIMER_FOCUS_MAX, settings.focusMax());
        }
        boolean example = RolePolicy.mayGiveExample(state.helpConsented(), state.retryCount());
        String text = PracticeInstructions.coachHelp(currentScene, example);
        recordFact(FactKind.FOCUS_HELP);
        if (example) {
            recordFact(FactKind.EXPRESSION_EXAMPLE);
        }
        help(HelpKind.FOCUS_HELP, FlowRole.COACH, example, state.retryCount() == 0 ? pendingConsent : null, text.length());
        speakGenerated(FlowRole.COACH, text);
    }

    /** 역할 전환 · 복귀 알림. 승인 문장이 있으면 실행부의 알림 능력으로, 없거나 못 내면 생성형 발화로 낸다(BR5.4). */
    private void announce(FlowRole toRole) {
        String code = toRole == FlowRole.COACH ? COACH_SWITCH_CODE : PEER_RETURN_CODE;
        if (approvedSpeechCodes.contains(code) && output.announceRoleSwitch(toRole.name())) {
            awaitingNotice = true;
            inputReady = false;
            output.schedule(TIMER_NOTICE_GUARD, settings.slotGuard());
            return;
        }
        speakGenerated(toRole, PracticeInstructions.roleNotice(toRole.name(), currentScene));
    }

    private void speakApproved(String text) {
        approvedReadingRetried = false;
        submitApproved(text);
    }

    private void onApprovedTextMismatch(PracticeSpeech speech) {
        if (speech.generated() || !speech.correlationId().equals(openSlot) || openSlotPlaying) return;
        if (approvedReadingRetried) {
            handleSlotEnd(speech.correlationId(), SlotEnd.FAILED);
            return;
        }
        // No mismatched audio was delivered. One fresh slot gets one bounded attempt at the SAME approved text.
        cancelOpenSlot();
        approvedReadingRetried = true;
        submitApproved(speech.text());
    }

    private void submitApproved(String text) {
        UUID correlation = UUID.randomUUID();
        openSlot(correlation);
        PracticeSpeech speech = null;
        try {
            speech = PracticeSpeech.scenario(correlation, currentScript.scenarioId(), speechGeneration++,
                    RolePolicy.partnerLabel(state.role(), currentScene.partnerRole()), text,
                    state.sub() == Sub.REFLECTION_PROMPT ? PracticeSpeech.ReadingCue.ROLE_NOTICE
                            : state.phase() == Phase.SCENE_INTRO ? PracticeSpeech.ReadingCue.SCENE
                            : state.sub() == Sub.REEXPLAIN ? PracticeSpeech.ReadingCue.REEXPLANATION : PracticeSpeech.ReadingCue.PEER);
        } catch (IllegalArgumentException invalid) {
            // 승인 문장이 재생 형식(길이 · 제어 문자)을 못 맞춘다: 문장 내용은 남기지 않고 재생 실패로 닫는다
            speech = null;
        }
        launch(correlation, speech != null && output.speak(speech));
    }

    private void speakGenerated(FlowRole role, String instruction) {
        UUID correlation = UUID.randomUUID();
        openSlot(correlation);
        boolean sent = output.speakGenerated(correlation, RolePolicy.slotRole(role), instruction);
        launch(correlation, sent);
    }

    /** 낼 수 없었으면(거짓) 그 슬롯을 실패로 닫는다. 곧바로 처리하면 호출이 겹치므로 현재 사건이 끝난 뒤에 처리한다. */
    private void launch(UUID correlation, boolean sent) {
        if (!sent) {
            deferred.add(() -> handleSlotEnd(correlation, SlotEnd.FAILED));
        }
    }

    private void openSlot(UUID correlation) {
        openSlot = correlation;
        openSlotPlaying = false;
        inputReady = false;
        output.schedule(slotGuardName(), settings.slotGuard());
    }

    private String slotGuardName() {
        return TIMER_SLOT_GUARD + openSlot + (openSlotPlaying ? "-playing" : "");
    }

    /** 확장이 스스로 슬롯을 닫는다(한도 만료 · 단계 변경): 취소 즉시 처리 완료로 두어 늦게 닿는 사건을 무시한다. */
    private void cancelOpenSlot() {
        if (openSlot != null) {
            UUID slot = openSlot;
            output.cancel(slotGuardName());
            openSlot = null;
            openSlotPlaying = false;
            state.markHandled(slot);
            output.cancelSpeech(slot);
        }
        if (awaitingNotice) {
            awaitingNotice = false;
            output.cancel(TIMER_NOTICE_GUARD);
        }
    }

    // ---- 기록 ----

    private void createSceneRow() {
        GoalSpec goal = pack.goals().get(state.goalIndex());
        sceneRowCreated = safely(() -> recorder.sceneStarted(SceneRunRecord.started(sceneRunId, sessionId, guardianId,
                goal.goalSelectionId(), state.goalIndex(), currentScript.scenarioId(), state.sceneKind(), clock.now())));
    }

    private void recordFact(FactKind kind) {
        if (kind == FactKind.FOCUS_HELP || kind == FactKind.EXPRESSION_EXAMPLE) reviewHelpProvided = true;
        if (kind == FactKind.TIMEOUT || kind == FactKind.TRANSCRIPT_FAILED || kind == FactKind.END_REQUEST) reviewInterrupted = true;
        if (sceneRowCreated) {
            Instant at = clock.now();
            safely(() -> recorder.fact(sceneRunId, kind, at));
        }
    }

    private void recordOutcome(VariantSkipReason skip, LiveTiming timing) {
        if (sceneRowCreated) {
            Instant at = clock.now();
            LiveTiming recordedTiming = settings.flexibleDialogue() && goalAchieved ? goalAchievementTiming : timing;
            if (recordedTiming == LiveTiming.IMMEDIATE || recordedTiming == LiveTiming.AFTER_REASK || recordedTiming == LiveTiming.AFTER_HELP) {
                reviewPerformed = true;
                reviewAfterHelp |= recordedTiming == LiveTiming.AFTER_HELP;
                reviewClarified |= recordedTiming == LiveTiming.AFTER_REASK;
            }
            if (recordedTiming == LiveTiming.NONE || recordedTiming == LiveTiming.ABSTAINED) reviewInterrupted = true;
            safely(() -> recorder.sceneOutcome(sceneRunId, at, recordedTiming, skip, state.retryCount(), state.reexplainCount()));
        }
    }

    private void help(HelpKind kind, FlowRole role, boolean example, ConsentAnswer consent, int chars) {
        if (sceneRowCreated) {
            Instant at = clock.now();
            safely(() -> recorder.help(new HelpRecord(UUID.randomUUID(), sceneRunId, guardianId, kind, role, example, consent,
                    chars, at)));
        }
    }

    /** 기록이 실패해도 연습은 잇는다: 앱 오류 한 줄(고정 사유, 내용 없음)을 남기고 계속한다. */
    private boolean safely(Runnable write) {
        try {
            write.run();
            return true;
        } catch (RuntimeException e) {
            failures.accept(e);
            return false;
        }
    }

    // ---- 보조 ----

    private void unexpected() {
        failures.accept(new IllegalStateException("PRACTICE_FLOW_UNEXPECTED_INPUT"));
    }

    private void reportFailure(Reason reason) {
        failures.accept(new IllegalStateException("PRACTICE_SCRIPT_" + (reason == null ? "UNKNOWN" : reason.name())));
    }

    private Signal finish() {
        while (!deferred.isEmpty()) {
            deferred.poll().run();
        }
        Signal signal = pendingSignal;
        pendingSignal = Signal.NONE;
        return signal;
    }

    /** 시험용: 지금 단계. */
    FlowState state() {
        return state;
    }

    @Override
    public String toString() {
        return "PracticeConversation[sessionId=" + sessionId + ", phase=" + state.phase() + "]";
    }
}
