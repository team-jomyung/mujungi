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
 *   <li>시계는 모두 확장점 시계({@code schedule}/{@code cancel})다: {@code no-response} · {@code child-speech} · {@code focus-max} ·
 *       {@code goal-limit} · {@code slot-guard-<correlationId>}(§3.2). 시간 값은 {@link PracticeFlowSettings}가 설정 키에서 읽은 것뿐이고 한도는
 *       만료 표지로 본다(D3). 아이 말이 시작되면(실행부 알림) 무응답 시계를 멈추고 확정 전사를 기다린다(4회차 R1).
 *   <li>판정은 {@code requestJudgment}로 요청한다(J2 · J3 · J5에만 판정 기준을 싣고 J4에는 싣지 않는다, D2). 거절(거짓) · 보류 · 장애는 판독
 *       불가와 같이 교정 없이 같은 질문을 한 번 다시 안내하고 두 번째면 정해진 방식으로 닫는다(BR3.7). 연습이 멈추지 않는다. 다만 기회
 *       단계에서 확신이 모자란 보류(1순위 도움 요청이거나 다시 안내 뒤의 보류)는 닫지 않고 도움 제안으로 간다
 *       ({@link FlowTransitions#onJudgmentAbstained}, docs/practice-live-fixes.md).
 *   <li>GPT-Live가 만드는 모든 역할 발화는 {@code speakGenerated} 한 길로만 낸다(D6). 낼 수 없으면(거짓) 그 슬롯을 실패로 닫고 이어 간다.
 *       역할 전환 · 복귀 알림은 승인 문장이 있으면 {@code announceRoleSwitch}로, 없거나 못 내면 생성형으로 낸다(BR5.4).
 *   <li>슬롯 종료 사건은 correlationId마다 첫 사건만 처리하고(중복 무시), 확장이 스스로 닫은 슬롯은 곧바로 처리 완료로 둔다(§3.5).
 *   <li>장면은 무중의 장면 설명(역할 밖) → 친구 첫 대사(대본에 있으면) → 친구 기회 문장 순서로 승인 원문 그대로 재생한다. 첫 대사가 없는 옛
 *       대본에서 도입(장면 설명)이 기회 문장을 담고 끝까지 재생됐으면 OPPORTUNITY 진입에서 기회 문장을 다시 재생하지 않는다({@link SceneIntro}).
 *       설명은 자르지 않는다. 승인 발화의 말하는 사람 표시는 설명이면 무중, 대사면 친구다({@link RolePolicy}). 상황 다시 설명 뒤에는 첫 대사(있으면)
 *       → 기회 문장을 다시 들려주고, 무응답 재안내는 기회 문장만 다시 들려준다.
 *   <li>칭찬 · 채점 대신 친구가 역할 안에서 반응한다: 판정한 아이 대답이 장면을 닫으면(J2 충분 · J3 전달됨 · J5 결과) 친구 반응 한 마디(D_CONTINUE ·
 *       코치 역할이면 복귀 알림 뒤 D_RESUME)를 낸 뒤 변형 평가로 간다. 도움 제안 · 변형 제안은 무중이 역할 밖에서 한다.
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
    /**
     * 4회차 R1: 아이 말이 시작된 뒤 그 말의 확정 전사를 기다리는 안전 시계(무응답 대기와 같은 길이). 확정 전사가 오면 거두고, 끝내 오지 않으면(빈 묶음 ·
     * 실행부가 맡은 말) 만료 때 무응답 대기를 처음부터 다시 건다.
     */
    static final String TIMER_CHILD_SPEECH = "child-speech";
    /** 다음 목표 대본 비동기 결과의 post 이름. */
    static final String ASYNC_SCRIPT = "practice-script";

    private static final String COACH_SWITCH_CODE = "COACH_SWITCH";
    private static final String PEER_RETURN_CODE = "PEER_RETURN";
    /** 도움 제안 승인 발화(AI 동작 명세 §3.1 D_OFFER_HELP 예시 «무중이가 조금 도와줄까?», contracts/ai-io.md 10.6). */
    private static final String HELP_OFFER_CODE = "HELP_OFFER";
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
    private Map<String, String> approvedSpeech = Map.of();
    private PracticeScript currentScript;
    private Scene currentScene;
    private UUID sceneRunId;
    private boolean sceneRowCreated;
    private UUID openSlot;
    private boolean awaitingNotice;
    /** 지금 도입이 기회 문장으로 끝나는가. 도입이 끝까지 재생되지 않으면 거짓으로 바뀐다. */
    private boolean introCarriesOpportunity;
    private boolean inputReady;
    /** 4회차 R1: 아이 말이 시작됐고 그 말의 확정 전사가 아직 오지 않았다. 참인 동안 무응답 시계를 걸지 않는다. */
    private boolean childSpeaking;
    private Utterance earlyUtterance;
    private UUID judgmentCorrelation;
    private ConsentAnswer pendingConsent;
    private int waitingGoal = -1;
    private boolean stopping;
    private Signal pendingSignal = Signal.NONE;
    private long speechGeneration;
    private String lastStage = STAGE_SCENARIO_INTRO;

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

    /** 4회차 R2: GPT-Live 연습 발화 지시는 공급자 출력이 조용할 때 보내고, 새 말이 없었으면 한 번 다시 보내게 한다(실행부가 한다). */
    @Override
    public boolean pacesSpeech() {
        return true;
    }

    /**
     * 3회차 V1: 첫 대본의 승인 원문 대본 줄과 승인 발화 HELP_OFFER(실행부가 참여 확인 동안 음성을 미리 만든다). 상태를 바꾸지 않는다. 첫 대본이 없거나
     * 거절된 패키지는 없음이다(그 목표는 onActive가 지금처럼 '미실시'로 둔다). 다음 목표 대본은 도착할 때 {@link PracticeOutput#prepareSpeech}로 맡긴다.
     */
    @Override
    public java.util.List<SpeechLine> speechLines(String practiceJson, Map<String, String> approvedSpeechTexts) {
        PracticeStartPackage parsed = PracticeStartPackage.parse(practiceJson);
        if (parsed.firstScenario() == null) {
            return java.util.List.of();
        }
        return scriptLines(parsed.firstScenario(), approvedSpeechTexts == null ? null : approvedSpeechTexts.get(HELP_OFFER_CODE));
    }

    /**
     * 대본 하나에서 승인 원문 그대로 낼 대본 줄(말하는 사람 표시 · 원문은 {@link #speakNarration} · {@link #speakPeerLine}이 낼 때와 글자 그대로
     * 같다). 낼 차례가 이른 줄부터: 원 장면 설명 → 친구 첫 대사 → 기회 문장 → 상황 다시 설명 → 도움 제안 → 변형 제안(판 v6 대본은 변형 설명 +
     * «이어서 해 볼까?», 옛 대본은 변형 장면 설명) → 변형 첫 대사 → 변형 기회 문장 → 변형 상황 다시 설명.
     *
     * @param helpOffer 승인 발화 HELP_OFFER 원문(없으면 null — 그때 도움 제안은 생성형이라 줄이 아니다)
     */
    static java.util.List<SpeechLine> scriptLines(PracticeScript script, String helpOffer) {
        java.util.List<SpeechLine> lines = new java.util.ArrayList<>();
        Scene base = script.baseScene();
        narrationLine(lines, base.sceneDescription());
        peerLine(lines, base, base.partnerOpener());
        peerLine(lines, base, base.opportunitySentence());
        narrationLine(lines, base.reexplanation());
        if (helpOffer != null && !helpOffer.isBlank()) {
            narrationLine(lines, helpOffer);
        }
        script.variantScene().ifPresent(variant -> {
            // variantIntroducedInOffer와 같은 판단: 판 v6 대본(원 장면에 첫 대사가 있다)만 변형 설명을 제안에서 들려준다
            narrationLine(lines, base.hasPartnerOpener()
                    ? variant.sceneDescription() + " " + PracticeInstructions.VARIANT_OFFER_QUESTION : variant.sceneDescription());
            peerLine(lines, variant, variant.partnerOpener());
            peerLine(lines, variant, variant.opportunitySentence());
            narrationLine(lines, variant.reexplanation());
        });
        return java.util.List.copyOf(lines);
    }

    private static void narrationLine(java.util.List<SpeechLine> lines, String text) {
        if (text != null && !text.isBlank()) {
            lines.add(new SpeechLine(RolePolicy.NARRATOR_LABEL, text, SpeechVoice.NARRATOR));
        }
    }

    private static void peerLine(java.util.List<SpeechLine> lines, Scene scene, String text) {
        if (text != null && !text.isBlank()) {
            lines.add(new SpeechLine(RolePolicy.peerName(scene.partnerRole()), text, SpeechVoice.PEER));
        }
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
        this.approvedSpeech = context.approvedSpeechTexts();
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
        // 시작된 말의 확정 전사가 왔다: 아래에서 판정 · 보관 · 무시 어느 쪽으로 가든 '말하는 중'은 끝났다
        endChildSpeech();
        Phase phase = state.phase();
        if (phase.awaitsInput() && state.sub() == Sub.NONE) {
            if (!inputReady) {
                // 우리 질문이 아직 재생 중이다. 재생이 끝나는 대로 이 전사를 판정한다
                earlyUtterance = utterance;
            } else if (!state.judgmentOpen()) {
                judge(utterance);
            }
        } else if (phase == Phase.SCENE_INTRO && state.sub() == Sub.NONE && introCarriesOpportunity && openSlot != null) {
            // 도입이 기회 문장으로 끝나면 아이는 도입 재생 중에 대답할 수 있다. 기회 문장을 따로 재생할 때처럼 마지막 전사 하나만 두었다가
            // 도입이 끝까지 재생되면 OPPORTUNITY 입력 대기에서 곧바로 판정한다
            earlyUtterance = utterance;
        } else if (phase == Phase.FOCUS && state.sub() == Sub.COACH_UTTERANCE) {
            // 코치 발화에 끼어든 전사는 재생이 끝난 뒤 RETRY 진입에서 곧바로 J5 입력으로 처리한다(§3.3)
            earlyUtterance = utterance;
        }
        return finish();
    }

    /**
     * 4회차 R1: 아이 말이 시작됐다(실행부가 새 입력 전사 묶음의 첫 조각마다 한 번 알린다). 확정 전사는 말이 끝나고 조용 시간이 지나야 오므로, 그 사이
     * 무응답 시계가 말 도중에 울리지 않게 멈추고 안전 시계만 건다. 우리 말이 아직 재생 중일 때 시작된 말도 같다 — 재생이 끝나 입력을 기다리기 시작해도
     * 그 말의 확정 전사가 올 때까지 무응답 시계를 걸지 않는다({@link #armNoResponse}). 확정 전사는 지금처럼 {@link #onChildUtterance}로 판정한다.
     */
    @Override
    public Signal onChildSpeechStarted(Instant at, PracticeOutput target) {
        if (output == null || stopping || state.phase() == Phase.CLOSING) {
            return Signal.NONE;
        }
        childSpeaking = true;
        output.cancel(TIMER_NO_RESPONSE);
        output.schedule(TIMER_CHILD_SPEECH, settings.noResponseWait());
        return finish();
    }

    /** 시작된 말의 확정 전사가 왔다: 안전 시계를 거둔다. */
    private void endChildSpeech() {
        if (childSpeaking) {
            childSpeaking = false;
            output.cancel(TIMER_CHILD_SPEECH);
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
        if (!sceneRowCreated || state.phase() == Phase.CLOSING || state.phase() == Phase.NEXT_GOAL) {
            return;
        }
        if (state.sceneEndCause() == null) {
            recordFact(FactKind.END_REQUEST);
            recordOutcome(VariantSkipReason.END_OR_REST, LiveTiming.NONE);
        } else if (state.phase() == Phase.SCENE_WRAP || state.phase() == Phase.VARIANT_OFFER) {
            // 세션 종료도 변형 평가에는 종료 · 휴식이다(BR6.1 (2)). 끝나는 세션이라 표지를 세워도 뒤 흐름에 영향이 없다
            state.endRequested(true);
            recordVariantNotReached();
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
        if (state.phase() == Phase.SCENE_WRAP && state.sub() == Sub.PEER_REACTION) {
            // 친구 반응은 방금 끝난 대답의 연장이라 보호자 화면은 그 단계 값을 그대로 보여 준다
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
        state.partnerOpener(scene.hasPartnerOpener());
        sceneRunId = UUID.randomUUID();
        sceneRowCreated = false;
        earlyUtterance = null;
        pendingConsent = null;
        if (kind == SceneKind.VARIANT && variantIntroducedInOffer()) {
            startOfferedVariant(scene);
            return;
        }
        state.phase(Phase.SCENE_INTRO, Sub.NONE);
        // 친구 첫 대사가 있으면 아이가 마지막으로 듣는 말은 설명이 아니라 첫 대사다: 설명이 기회 문장으로 끝나도 기회 문장을 따로 재생한다
        introCarriesOpportunity = !scene.hasPartnerOpener()
                && SceneIntro.carriesOpportunity(scene.sceneDescription(), scene.opportunitySentence());
        speakNarration(scene.sceneDescription());
    }

    /**
     * 받아들인 변형 장면 시작(D_VARIANT_START, 판 v6 대본). 변형 설명은 제안(D_VARIANT_OFFER)에서 무중이 이미 들려줬으므로 다시 읽지 않고, 그
     * 설명이 들린 것을 장면 시작으로 보아 행을 만든 뒤 친구 첫 대사(있으면) → 기회 문장으로 시작한다. 옛 대본의 변형은 BASE와 같은 도입으로 시작한다.
     */
    private void startOfferedVariant(Scene scene) {
        introCarriesOpportunity = false;
        createSceneRow();
        if (scene.hasPartnerOpener()) {
            state.phase(Phase.SCENE_INTRO, Sub.PARTNER_OPENER);
            speakPeerLine(scene.partnerOpener());
        } else {
            state.phase(Phase.OPPORTUNITY, Sub.NONE);
            speakPeerLine(scene.opportunitySentence());
        }
    }

    private void moveOn() {
        if (state.lastGoal()) {
            closing(false);
        } else {
            advanceGoal();
        }
    }

    private void advanceGoal() {
        output.cancel(TIMER_GOAL_LIMIT);
        output.cancel(TIMER_FOCUS_MAX);
        output.cancel(TIMER_NO_RESPONSE);
        pendingSignal = Signal.NEXT_GOAL;
        startGoal(state.goalIndex() + 1);
    }

    private void closing(boolean byRequest) {
        output.cancel(TIMER_GOAL_LIMIT);
        output.cancel(TIMER_FOCUS_MAX);
        output.cancel(TIMER_NO_RESPONSE);
        endChildSpeech();
        cancelOpenSlot();
        state.phase(Phase.CLOSING, Sub.NONE);
        if (byRequest) {
            // 종료 · 휴식 판정은 실행부(J6) 소유다. 확장은 말을 멈추고 출력을 막은 채 실행부의 결정을 기다린다(신호를 내지 않는다)
            output.holdOutput(true);
        } else {
            pendingSignal = Signal.LAST_GOAL;
        }
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
            // 3회차 V1: 다음 목표 대본의 줄 음성을 지금 맡긴다(앞 목표를 하는 동안 만든다). 쓰지 않는 실행부면 거짓이고 낼 때 GPT-Live 원문 읽기다
            output.prepareSpeech(scriptLines(result.script(), approvedSpeech.get(HELP_OFFER_CODE)));
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
        JudgmentPoint point = FlowTransitions.expectedPoint(state.phase());
        UUID correlation = UUID.randomUUID();
        output.cancel(TIMER_NO_RESPONSE);
        judgmentCorrelation = correlation;
        state.judgmentOpen(true);
        boolean accepted = output.requestJudgment(point, utterance.seq(), correlation, criteriaFor(point));
        if (!accepted) {
            // 거절(열린 턴 · 공급자 없음 · 전사 범위 밖 등)은 보류와 같게 이어 간다. 연습은 멈추지 않는다(BR3.7)
            judgmentCorrelation = null;
            state.judgmentOpen(false);
            afterJudgment(transitions.onJudgmentUnavailable(state, point));
        }
    }

    /** J2 · J3 · J5에만 판정 기준을 싣는다(D2). coreFunction은 목표의 판정 기준 문자열에서 만든다(상한 안으로 줄인다). */
    private Map<String, Object> criteriaFor(JudgmentPoint point) {
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
        if (!closeJudgment(event.correlationId())) {
            return;
        }
        if (!(event.value() instanceof Map<?, ?> value)) {
            // 모양이 틀린 결과는 보류와 같게 이어 간다(교정 없이, 연습은 멈추지 않는다)
            afterJudgment(transitions.onJudgmentUnavailable(state, FlowTransitions.expectedPoint(state.phase())));
            return;
        }
        JudgmentPoint point = pointOf(value.get("point"));
        Object first = value.get("firstOption");
        afterJudgment(point == null ? Step.ignore() : transitions.onJudgment(state, point, first == null ? null : first.toString()));
    }

    private void onJudgmentAbstained(ExtensionEvent event) {
        if (!closeJudgment(event.correlationId())) {
            return;
        }
        Map<?, ?> value = event.value() instanceof Map<?, ?> map ? map : Map.of();
        JudgmentPoint point = event.value() instanceof Map<?, ?> ? pointOf(value.get("point"))
                : FlowTransitions.expectedPoint(state.phase());
        afterJudgment(point == null ? Step.ignore()
                : transitions.onJudgmentAbstained(state, point, text(value.get("abstainReason")), text(value.get("leadingOption"))));
    }

    /** 이 사건이 지금 열린 판정의 것인가(늦은 · 다른 판정의 사건은 무시). 맞으면 판정을 닫는다. */
    private boolean closeJudgment(UUID correlationId) {
        if (judgmentCorrelation == null || !judgmentCorrelation.equals(correlationId)) {
            return false;
        }
        judgmentCorrelation = null;
        state.judgmentOpen(false);
        return true;
    }

    /** 사건 값의 코드 칸(문자열이 아니면 없는 것으로 본다). */
    private static String text(Object value) {
        return value instanceof String code ? code : null;
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
        if (fact.started()) {
            rearmStartedSlot(fact.speech() != null ? fact.speech().correlationId() : eventCorrelation);
            return;
        }
        UUID correlation = fact.speech() != null ? fact.speech().correlationId() : eventCorrelation;
        SlotEnd end = fact.played() ? SlotEnd.PLAYED : fact.interrupted() ? SlotEnd.INTERRUPTED : SlotEnd.FAILED;
        handleSlotEnd(correlation, end);
    }

    /**
     * 4회차 R4: 재생이 시작됐다(STARTED). 시작 전 안전 시계(지시 미룸 · 새 말 기다림 · 한 번 다시 보냄 · 생성을 덮는 긴 시계)를 재생 길이를 덮는 시계로
     * 다시 건다 — 들리는 도중에는 만료하지 않고(한 줄은 출력 상한보다 길게 재생되지 않는다), 끝 사실이 끝내 오지 않으면 지금처럼 실패로 닫는다. 실행부가
     * 연 알림 슬롯(correlationId를 모른다)은 알림을 기다리는 동안의 시작 사실로 본다.
     */
    private void rearmStartedSlot(UUID correlation) {
        if (correlation == null || state.isHandled(correlation)) {
            return;
        }
        if (correlation.equals(openSlot)) {
            output.schedule(TIMER_SLOT_GUARD + correlation, settings.slotGuard());
        } else if (awaitingNotice) {
            output.schedule(TIMER_NOTICE_GUARD, settings.slotGuard());
        }
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
        if (mine) {
            output.cancel(TIMER_SLOT_GUARD + correlation);
            openSlot = null;
        } else {
            output.cancel(TIMER_NOTICE_GUARD);
            awaitingNotice = false;
        }
        boolean introEnded = state.phase() == Phase.SCENE_INTRO && state.sub() == Sub.NONE && !sceneRowCreated
                && (end == SlotEnd.PLAYED || end == SlotEnd.INTERRUPTED);
        if (introEnded) {
            createSceneRow();
        }
        if (state.phase() == Phase.SCENE_INTRO && end != SlotEnd.PLAYED) {
            // 도입이 끊기면 아이가 설명 끝의 기회 문장을 못 들었을 수 있다: 기회 문장을 따로 재생하고, 도입 중에 온 전사는 대답으로 보지 않는다
            introCarriesOpportunity = false;
            earlyUtterance = null;
        }
        Step step = transitions.onSlotEnded(state, end);
        if (step.action() == FlowTransitions.Action.IGNORE) {
            unexpected();
            return;
        }
        applyStep(step);
    }

    private void onTimer(String name) {
        switch (name) {
            case TIMER_NO_RESPONSE -> applyStep(transitions.onNoResponse(state));
            case TIMER_CHILD_SPEECH -> onChildSpeechGuard();
            case TIMER_FOCUS_MAX -> onFocusMax();
            case TIMER_GOAL_LIMIT -> onGoalLimit();
            case TIMER_NOTICE_GUARD -> {
                if (awaitingNotice) {
                    awaitingNotice = false;
                    applyStep(transitions.onSlotEnded(state, SlotEnd.FAILED));
                }
            }
            default -> {
                if (name.startsWith(TIMER_SLOT_GUARD) && openSlot != null && name.equals(TIMER_SLOT_GUARD + openSlot)) {
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

    /** 무응답 시계를 처음부터 건다. 판정 중이거나 아이가 말하는 중(확정 전사를 기다리는 중)이면 걸지 않는다(4회차 R1). */
    private void armNoResponse() {
        if (!state.judgmentOpen() && !childSpeaking) {
            output.schedule(TIMER_NO_RESPONSE, settings.noResponseWait());
        }
    }

    /**
     * 4회차 R1: 말이 시작됐는데 확정 전사가 끝내 오지 않았다(빈 묶음 · 종료 의도로 실행부가 맡은 말 등). 아직 아이 말을 기다리는 중이면 무응답 시계를
     * 처음부터 다시 건다 — 그 말을 대답으로 치지 않고, 무응답 재안내도 그만큼 늦춘다.
     */
    private void onChildSpeechGuard() {
        childSpeaking = false;
        if (state.phase().awaitsInput() && state.sub() == Sub.NONE && inputReady) {
            armNoResponse();
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
            case OPPORTUNITY -> speakPeerLine(currentScene.opportunitySentence());
            case REASK -> speakGenerated(FlowRole.PEER, PracticeInstructions.peerReask(currentScene, true));
            case HELP_OFFER -> helpOffer(true);
            case RETRY -> speakGenerated(FlowRole.COACH, PracticeInstructions.coachRetryPrompt(currentScene));
            case VARIANT_OFFER -> variantOffer(true);
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
        speakNarration(currentScene.reexplanation());
    }

    private void enter(Step step) {
        Phase previous = state.phase();
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
        if (step.cause() != null && !(step.endsByRequest() && state.sceneEndCause() != null)) {
            // 이미 정리된 장면(친구 반응 · 복귀 알림 · 변형 제안 중)에 온 종료 · 휴식 요청은 정리 때 정한 장면 결과를 덮지 않는다
            state.sceneEnd(step.cause(), step.timing() == null ? LiveTiming.NONE : step.timing());
            state.peerReaction(step.reactsToTheAnswer());
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
        dispatch(step, previous);
    }

    /** 장면 정리 진입: 시계 · 열린 판정 · 끼어든 전사를 거두고 장면 결과를 남긴다. */
    private void wrapScene() {
        output.cancel(TIMER_FOCUS_MAX);
        cancelOpenSlot();
        dropJudgment();
        earlyUtterance = null;
        recordOutcome(null, state.sceneTiming());
    }

    private void dispatch(Step step, Phase previous) {
        switch (step.phase()) {
            case SCENE_INTRO -> {
                if (step.sub() == Sub.PARTNER_OPENER) {
                    speakPeerLine(currentScene.partnerOpener());
                } else {
                    enterScene(SceneKind.VARIANT);
                }
            }
            case OPPORTUNITY -> {
                if (step.sub() == Sub.PARTNER_OPENER) {
                    // 상황 다시 설명 뒤 재진입: 단서가 첫 대사에 있을 수 있어 첫 대사 → 기회 문장을 다시 들려준다(실측 F3)
                    speakPeerLine(currentScene.partnerOpener());
                } else {
                    opportunity(previous);
                }
            }
            case REASK -> speakGenerated(FlowRole.PEER, PracticeInstructions.peerReask(currentScene, false));
            case HELP_OFFER -> helpOffer(false);
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
                } else if (step.sub() == Sub.PEER_REACTION) {
                    speakGenerated(FlowRole.PEER, PracticeInstructions.peerReaction(currentScene));
                } else {
                    applyStep(transitions.evaluateVariant(state, currentScript.variantScene().isPresent()));
                }
            }
            case VARIANT_OFFER -> variantOffer(false);
            case NEXT_GOAL -> advanceGoal();
            case CLOSING -> {
                if (step.endsByRequest()) {
                    recordEndByRequest(previous);
                }
                closing(step.endsByRequest());
            }
        }
    }

    /**
     * 종료 · 휴식 요청으로 닫을 때 지금 장면의 결과를 남긴다. 진행 중이던 장면은 END_REQUEST(진행 중 판정 시점 NONE)로 닫는다. 이미 정리된 장면(친구
     * 반응 · 복귀 알림 · 변형 제안 중)은 정리 때 정한 시점(예: 되묻기 뒤 전달)을 지우지 않고, 원 장면이면 변형을 못 한 까닭만 더한다.
     */
    private void recordEndByRequest(Phase previous) {
        switch (previous) {
            case NEXT_GOAL, CLOSING -> {
                // 열린 장면이 없다
            }
            case SCENE_WRAP, VARIANT_OFFER -> recordVariantNotReached();
            default -> {
                state.sceneEnd(FlowTransitions.EndCause.END_REQUEST, LiveTiming.NONE);
                recordOutcome(VariantSkipReason.END_OR_REST, LiveTiming.NONE);
            }
        }
    }

    /**
     * 정리된 원 장면이 변형 평가 · 변형 장면 전에 종료 · 휴식으로 끝났다. 정리 때 정한 시점은 두고, 변형 평가가 그 순간 냈을 생략 사유만 더한다(BR6.1
     * 순서: 변형이 없는 대본은 NO_VARIANT, 집중 한도로 닫힌 장면은 FOCUS_LIMIT, 나머지는 END_OR_REST). 변형 장면은 생략 사유가 없다.
     */
    private void recordVariantNotReached() {
        if (state.sceneKind() != SceneKind.BASE) {
            return;
        }
        VariantSkipReason skip = transitions.decideVariant(state, currentScript.variantScene().isPresent()).skip();
        recordOutcome(skip == null ? VariantSkipReason.END_OR_REST : skip, state.sceneTiming());
    }

    // ---- 출력 ----

    /**
     * 기회 문장 단계 진입. 방금 끝까지 재생한 도입이 기회 문장으로 끝났으면(첫 대사가 없는 옛 대본) 다시 재생하지 않고 바로 아이 말을 기다린다(도입
     * 중에 온 대답이 있으면 곧바로 판정한다). 친구 첫 대사 뒤 · 상황 다시 설명 뒤의 진입은 기회 문장을 친구 대사로 재생한다(상황 다시 설명 뒤에 첫 대사가
     * 있는 장면은 첫 대사를 먼저 다시 들려준 뒤 여기로 온다).
     */
    private void opportunity(Phase previous) {
        boolean heard = previous == Phase.SCENE_INTRO && introCarriesOpportunity;
        introCarriesOpportunity = false;
        if (heard) {
            awaitInput();
        } else {
            speakPeerLine(currentScene.opportunitySentence());
        }
    }

    /** 코치 첫 도움 · 다시 해 보기 권유 발화(FOCUS 진입 ②). 집중 시계는 코치가 말하기 시작할 때 한 번 건다. */
    private void coachUtterance() {
        if (state.focusStartedAt() == null) {
            state.focusStartedAt(clock.now());
            output.schedule(TIMER_FOCUS_MAX, settings.focusMax());
        }
        RolePolicy.Example example = RolePolicy.example(state.helpConsented(), state.retryCount());
        boolean exampleGiven = PracticeInstructions.coachGivesExample(currentScene, example);
        String text = PracticeInstructions.coachHelp(currentScene, example);
        recordFact(FactKind.FOCUS_HELP);
        if (exampleGiven) {
            recordFact(FactKind.EXPRESSION_EXAMPLE);
        }
        help(HelpKind.FOCUS_HELP, FlowRole.COACH, exampleGiven, state.retryCount() == 0 ? pendingConsent : null, text.length());
        speakGenerated(FlowRole.COACH, text);
    }

    /**
     * 도움 제안(D_OFFER_HELP): 무중이 친구 역할에서 잠깐 나와 묻는다. 승인 문장(HELP_OFFER)이 있으면 그 원문을 무중의 승인 발화로, 없으면 코치
     * 슬롯의 생성형 발화로 낸다. 재안내는 같은 문장을 다시 들려준다.
     */
    private void helpOffer(boolean again) {
        String approved = approvedSpeech.get(HELP_OFFER_CODE);
        if (approved != null && !approved.isBlank()) {
            speakNarration(approved);
        } else {
            speakGenerated(FlowRole.COACH, PracticeInstructions.mujoongHelpOffer(again));
        }
    }

    /**
     * 변형 제안(D_VARIANT_OFFER): 무중이 역할 밖에서 묻는다. 판 v6 대본은 변형 장면 설명(승인 대본 문장) 뒤에 고정 문장 «이어서 해 볼까?»를 붙인
     * 승인 발화라 평가 · 칭찬이 끼어들 자리가 없다. 옛 대본은 변형 설명이 기회 문장을 담아 제안에 쓰지 않고, 무중의 생성형 질문(코치 슬롯)으로
     * 묻는다. 재안내도 같은 길이다.
     */
    private void variantOffer(boolean again) {
        Scene variant = currentScript.variantScene().orElse(currentScene);
        if (variantIntroducedInOffer()) {
            speakNarration(variant.sceneDescription() + " " + PracticeInstructions.VARIANT_OFFER_QUESTION);
        } else {
            speakGenerated(FlowRole.COACH, PracticeInstructions.mujoongVariantOffer(again));
        }
    }

    /**
     * 변형 설명을 제안에서 들려주는가: 판 v6 대본(core가 BASE 친구 첫 대사를 필수로 둔다)이면 참이다. 판 v5까지의 변형 설명은 기회 문장을 글자 그대로
     * 담으므로(그 판의 생성 규칙) 제안에 넣지 않고 수락 뒤 장면 도입으로 들려준다.
     */
    private boolean variantIntroducedInOffer() {
        return currentScript.baseScene().hasPartnerOpener();
    }

    /** 역할 전환 · 복귀 알림. 승인 문장이 있으면 실행부의 알림 능력으로, 없거나 못 내면 생성형 발화로 낸다(BR5.4). */
    private void announce(FlowRole toRole) {
        String code = toRole == FlowRole.COACH ? COACH_SWITCH_CODE : PEER_RETURN_CODE;
        if (approvedSpeech.containsKey(code) && output.announceRoleSwitch(toRole.name())) {
            awaitingNotice = true;
            inputReady = false;
            output.schedule(TIMER_NOTICE_GUARD, settings.slotStartGuard());
            return;
        }
        speakGenerated(toRole, PracticeInstructions.roleNotice(toRole.name(), currentScene));
    }

    /** 무중이 역할 밖에서 하는 설명(장면 설명 · 상황 다시 설명)을 승인 원문 그대로 낸다. */
    private void speakNarration(String text) {
        speakApproved(RolePolicy.NARRATOR_LABEL, text);
    }

    /** 장면 속 친구의 대사(첫 대사 · 기회 문장)를 승인 원문 그대로 낸다. */
    private void speakPeerLine(String text) {
        speakApproved(RolePolicy.peerName(currentScene.partnerRole()), text);
    }

    private void speakApproved(String speaker, String text) {
        UUID correlation = UUID.randomUUID();
        openSlot(correlation);
        PracticeSpeech speech = null;
        try {
            speech = PracticeSpeech.scenario(correlation, currentScript.scenarioId(), speechGeneration++, speaker, text);
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
        inputReady = false;
        // 4회차 R4: 재생이 시작되기까지는 긴 시계, 시작(STARTED) 뒤에는 재생 길이를 덮는 시계로 다시 건다({@link #rearmStartedSlot})
        output.schedule(TIMER_SLOT_GUARD + correlation, settings.slotStartGuard());
    }

    /** 확장이 스스로 슬롯을 닫는다(한도 만료 · 단계 변경): 취소 즉시 처리 완료로 두어 늦게 닿는 사건을 무시한다. */
    private void cancelOpenSlot() {
        if (openSlot != null) {
            UUID slot = openSlot;
            openSlot = null;
            state.markHandled(slot);
            output.cancelSpeech(slot);
            output.cancel(TIMER_SLOT_GUARD + slot);
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
        if (sceneRowCreated) {
            Instant at = clock.now();
            safely(() -> recorder.fact(sceneRunId, kind, at));
        }
    }

    private void recordOutcome(VariantSkipReason skip, LiveTiming timing) {
        if (sceneRowCreated) {
            Instant at = clock.now();
            safely(() -> recorder.sceneOutcome(sceneRunId, at, timing, skip, state.retryCount(), state.reexplainCount()));
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
