package kr.mujoong.realtime.practiceflow;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>경로는 둘이다({@link PracticeFlowSettings#flexibleDialogue()}). 운영 기본은 C안 '무중이 직접 묻기'(docs/practice-direct-ask.md)다: 장면 속
 * 친구 · 코치 역할이 없고, 무중이 상황과 상대의 말을 자기 말로 전한 뒤 "너라면 뭐라고 말할래?"라고 묻는다. 장면 안 무중의 말(상황 안내 · 전하기 +
 * 묻기 · 되묻기 · 다시 설명 · 도움 · 확인 · 다시 묻기 · 친구 반응 · 마무리 · 도움 제안 · 변형 제안)은 모두 대본 칸이나 고정 문장의 승인 낭독이다
 * ({@link DirectAskLines}). 장면 밖 말(목표 안내 · 목표 회고 · 낭독 실패 복구)은 두 경로가 같이 생성형으로 내고 제브를 거친다. 유연 대화 경로는
 * 설정 하나로 되돌릴 수 있게 그대로 둔다.
 *
 * <p>규칙 요약.
 *
 * <ul>
 *   <li>시계는 모두 확장점 시계({@code schedule}/{@code cancel})다: {@code no-response} · {@code child-speech} · {@code focus-max} ·
 *       {@code goal-limit} · {@code slot-guard-<correlationId>}(§3.2). 시간 값은 {@link PracticeFlowSettings}가 설정 키에서 읽은 것뿐이고 한도는
 *       만료 표지로 본다(D3). 아이 말이 시작되면(실행부 알림) 무응답 시계를 멈추고 확정 전사를 기다린다(4회차 R1).
 *   <li>판정은 {@code requestJudgment}로 요청한다(J2 · J3 · J5에만 판정 기준을 싣고 J4에는 싣지 않는다, D2). 거절(거짓) · 보류 · 장애는 판독
 *       불가와 같이 교정 없이 같은 질문을 한 번 다시 안내하고, 두 번째면 닫지 않고 도움 단계로 넘긴다(BR3.7 · FT-1023-g). 연습이 멈추지 않는다.
 *       C안은 지금 단계에서 같은 다음 걸음으로 처리하는 선택지 묶음({@link PracticeOutput#SAME_STEP_GROUPS})도 실어, 1 · 2순위가 같은 묶음이면
 *       실행부 검증기가 하한을 두 확률의 합으로 보게 한다(판정 기준이 아니라 공급자로 가지 않는다, 편차 FT-1024-f). C안 기회(J2) 판정에는 설정의 낮춘
 *       하한({@link PracticeOutput#MIN_CONFIDENCE})도 싣는다. C안의 판정 보류(받아쓰기는 됐는데 확률 하한 · 최소 차이에 못 미침)는 확인 질문 대신 그
 *       단계의 묻는 말로 다시 묻는다(기회는 되묻기 단계로, 편차 FT-1024-h).
 *   <li>GPT-Live가 만드는 모든 역할 발화는 {@code speakGenerated} 한 길로만 낸다(D6). 낼 수 없으면(거짓) 그 슬롯을 실패로 닫고 이어 간다.
 *       역할 전환 · 복귀 알림은 승인 문장이 있으면 {@code announceRoleSwitch}로, 없거나 못 내면 생성형으로 낸다(BR5.4).
 *   <li>발화 오류로 연습을 끝내지 않는다(FT-1023-g): 끝내 못 들려준 발화(재요청 뒤 실패 · 막힘 · 시한 초과)는 건너뛰고 다음 단계로 간다. 연습을 끝내는
 *       것은 종료 의사 · 시간 상한 · 모든 목표 완료와 실행부의 끊김 · 공백 처리뿐이다.
 *   <li>조각 단위 승인 낭독(B1)이 원문 중간에서 끊겨 앞부분만 들렸다는 실행부 사실(부분 재생)이 오면, C안은 들리지 않은 나머지(끊긴 곳이 든 문장부터)를
 *       낭독 하나에 한 번 다시 읽고 그 재생이 끝난 뒤에 다음으로 간다. 그것도 끊기면 지금 있는 복구 길을 그대로 탄다. 유연 대화는 지금처럼 재생 끝으로
 *       본다(docs/practice-direct-ask.md 7.7).
 *   <li>장면 시작(FT-1023-g): 목표 안내 → 장면 도입 → 상대의 여는 말(승인 문장, 있으면)과 대답 한 차례 → 기회 문장. 여는 말에 대한 아이
 *       말은 판정하지 않는다. 이전 대화 돌아보기 · 요약은 하지 않는다. 여는 말은 상대 역할의 말이라 유연 대화 경로만 쓰고, C안은 상황 안내 뒤 곧바로
 *       전하기 + 묻기로 간다(docs/practice-direct-ask.md 13.6).
 *   <li>슬롯 종료 사건은 correlationId마다 첫 사건만 처리하고(중복 무시), 확장이 스스로 닫은 슬롯은 곧바로 처리 완료로 둔다(§3.5).
 *   <li>레거시 평가 · 단서 포트에 기대지 않는다: 이 클래스는 {@code practice} 패키지를 참조하지 않는다(BR2.5 · BR10.2).
 *   <li>아이 전사 · 대본 문장 · 판정 입력은 로그 · 기록 · 예외 메시지에 쓰지 않는다. 오류는 고정 사유 코드로만 알린다(R-F14).
 * </ul>
 */
public final class PracticeConversation implements PracticeSessionExtension {

    static final String TIMER_NO_RESPONSE = "no-response";
    /**
     * 4회차 R1: 아이 말이 시작된 뒤 그 말의 확정 전사를 기다리는 안전 시계(무응답 대기와 같은 길이). 확정 전사가 오면 거두고, 끝내 오지 않으면(빈 묶음 ·
     * 실행부가 맡은 말) 만료 때 무응답 대기를 처음부터 다시 건다.
     */
    static final String TIMER_CHILD_SPEECH = "child-speech";
    static final String TIMER_FOCUS_MAX = "focus-max";
    static final String TIMER_GOAL_LIMIT = "goal-limit";
    static final String TIMER_SLOT_GUARD = "slot-guard-";
    /** announceRoleSwitch는 실행부가 슬롯을 만들어 correlationId를 모르므로 알림 슬롯은 이 이름 하나의 안전 시계를 쓴다. */
    static final String TIMER_NOTICE_GUARD = "slot-guard-notice";
    /** 다음 목표 대본 비동기 결과의 post 이름. */
    static final String ASYNC_SCRIPT = "practice-script";
    /** 진단(INFO, 원문 없음): C안에서 부분 재생된 승인 낭독의 들리지 않은 나머지를 다시 읽었다(docs/practice-direct-ask.md 7.7). */
    static final String DIAG_REMAINDER_REREAD = "PracticeApprovedRemainderReread";
    /** 진단(INFO): 다시 읽은 나머지도 끊겨 지금 있는 복구 길(같은 글 재시도 → '뜻 전하기' 복구 → 건너뛰기)로 갔다. */
    static final String DIAG_REMAINDER_CUT_AGAIN = "PracticeApprovedRemainderCutAgain";
    /** 진단(INFO): 부분 재생이지만 들리지 않은 글이 문장부호 · 공백뿐이라 다 들린 것으로 봤다. */
    static final String DIAG_REMAINDER_WITHOUT_WORDS = "PracticeApprovedRemainderWithoutWords";
    /**
     * 진단(INFO, 뒤에 지점 이름, 예: {@code PracticeUnsureJudgmentAskedAgain_J2}): C안에서 판정 보류(받아쓰기는 됐는데 확률 하한 · 최소 차이에 못
     * 미침)를 확인 질문 대신 그 단계의 묻는 말로 다시 물었다(J2는 되묻기 단계로, 편차 FT-1024-h). 되묻기가 판정 결과(PARTIAL)가 아니라 보류에서
     * 왔다는 사실을 남기는 고정 코드다(전사 · 선택지 · 확률 없음, R-F14). 판정 기록 행에는 그 판정이 보류(사유 포함)로 남는다.
     */
    static final String DIAG_UNSURE_ASKED_AGAIN = "PracticeUnsureJudgmentAskedAgain_";
    /** 실행부 판정 보류 사유 가운데 받아쓰기는 됐는데 1순위 확률이 하한 · 최소 차이에 못 미친 것(판독 불가 · 장애와 구분한다). */
    private static final Set<String> LOW_SCORE_ABSTAINS = Set.of("LOW_CONFIDENCE", "LOW_MARGIN");

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
    /** C안 장면 안 말(시작 패키지 approvedSpeech의 고정 문장 + 대본 칸 + 대체 문장). */
    private DirectAskLines lines = new DirectAskLines(Map.of());
    private PracticeScript currentScript;
    private Scene currentScene;
    private UUID sceneRunId;
    private boolean sceneRowCreated;
    private UUID openSlot;
    private boolean openSlotPlaying;
    private boolean approvedReadingRetried;
    /** C안: 이 낭독(speakApproved 한 번)에서 부분 재생 뒤 나머지 다시 읽기를 이미 했다(낭독마다 한 번). */
    private boolean remainderReread;
    private boolean generatedAudioRetried;
    private boolean awaitingNotice;
    private boolean inputReady;
    /** 4회차 R1: 아이 말이 시작됐고 그 말의 확정 전사가 아직 오지 않았다. 참인 동안 무응답 시계를 걸지 않는다. */
    private boolean childSpeaking;
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
    /** C안: 장면이 수행으로 정리될 때 정한 시점. 마무리 문장 중의 종료 · 재생 실패가 이룬 수행을 미수행(NONE)으로 덮지 않게 남긴다. */
    private LiveTiming sceneAchievement;
    private int clarificationAttempts;
    private int uncertainAttempts;
    private boolean openingAwaiting;
    private boolean openingAnswered;
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
        this.transitions = new FlowTransitions(settings.retryMax(), settings.reexplainMax(), settings.directAsk());
    }

    /** 이 상태 기계는 출력권을 쥔다(실행부가 첫 응답 요청 전에 묻는다, D1). */
    @Override
    public boolean controlsOutput() {
        return true;
    }

    /** C안은 장면 속 역할이 없어 무중의 말이 모두 무중의 말이다(판정 입력 화자 MUJOONG, docs/practice-direct-ask.md 6장). */
    @Override
    public boolean speaksAsMujoong() {
        return settings.directAsk();
    }

    @Override
    public boolean collectingIntroduction() {
        return state.phase() == Phase.SCENE_INTRO && openSlot != null && !stopping;
    }
    @Override public boolean canYieldUnplayedSpeech() {
        // No goal achievement, consent, coaching or closing transition may be inferred from an unheard output.
        return !stopping && state.phase() == Phase.OPPORTUNITY
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
        this.lines = new DirectAskLines(context.approvedSpeechTexts());
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
        // 시작된 말의 확정 전사가 왔다: 아래에서 판정 · 보관 · 무시 어느 쪽으로 가든 '말하는 중'은 끝났다(4회차 R1)
        endChildSpeech();
        Phase phase = state.phase();
        if (state.sub() == Sub.OPENING) {
            openingReply();
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

    /**
     * 여는 말에 대한 아이의 대답(내용과 상관없이 한 차례). 판정 · 무응답 재안내 횟수 · 장면 결과에 넣지 않는다(전사는 실행부가 남긴다). 여는 말이 아직
     * 재생 중이면 끝나는 대로 기회 문장으로 간다.
     */
    private void openingReply() {
        if (openingAwaiting) {
            openingDone();
        } else {
            openingAnswered = true;
        }
    }

    /** 여는 대화를 마치고 기회 문장으로 간다(판정 없음). */
    private void openingDone() {
        openingAwaiting = false;
        openingAnswered = false;
        output.cancel(TIMER_NO_RESPONSE);
        applyStep(Step.enter(Phase.OPPORTUNITY, Sub.NONE));
    }

    /**
     * 4회차 R1: 아이 말이 시작됐다(실행부가 새 입력 전사 묶음의 첫 조각마다 한 번 알린다). 확정 전사는 말이 끝나고 조용 시간이 지나야 오므로, 그 사이
     * 무응답 시계가 말 도중에 울리지 않게 멈추고 안전 시계만 건다. 우리 말이 아직 재생 중일 때 시작된 말도 같다 — 재생이 끝나 입력을 기다리기 시작해도
     * 그 말의 확정 전사가 올 때까지 무응답 시계를 걸지 않는다. 확정 전사는 지금처럼 {@link #onChildUtterance}로 판정한다. 두 경로가 같다.
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

    /**
     * 4회차 R1: 말이 시작됐는데 확정 전사가 끝내 오지 않았다(빈 묶음 · 종료 의도로 실행부가 맡은 말 · 안내 중의 말). 아직 아이 말을 기다리는 중이면
     * 무응답 대기를 처음부터 다시 건다 — 그 말을 대답으로 치지 않고, 무응답 재안내도 그만큼 늦춘다. 여는 말 대답을 기다리는 중(유연 대화)이면 여는 말의
     * 무응답 대기를 다시 건다(만기면 판정 없이 기회 문장으로 간다).
     */
    private void onChildSpeechGuard() {
        childSpeaking = false;
        if (state.sub() == Sub.OPENING) {
            if (openingAwaiting) {
                output.schedule(TIMER_NO_RESPONSE, settings.noResponseWait());
            }
            return;
        }
        if (state.phase().awaitsInput() && state.sub() == Sub.NONE && inputReady) {
            armNoResponse();
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
                } else if ("practice-audio-incomplete".equals(event.name()) && event.value() instanceof PracticeSpeech speech) {
                    onIncompleteAudio(speech);
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

    @Override public boolean handlesAudioRecovery() { return true; }

    /** Read-only diagnostic state; does not update display progress. */
    @Override
    public String diagnosticPhase() { return state.phase().name(); }
    public String diagnosticSub() { return state.sub().name(); }

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
        sceneAchievement = null;
        clarificationAttempts = 0;
        uncertainAttempts = 0;
        openingAwaiting = false;
        openingAnswered = false;
        // 목표 안내 · 목표 회고는 장면 밖 말이라 두 경로가 같이 낸다(생성형, 제브 필수). C안은 문구만 바꾼다(docs/practice-direct-ask.md 7.3).
        // 이전 대화 돌아보기 · 요약은 두 경로 모두 하지 않고 목표 안내 뒤 바로 장면 도입으로 간다(FT-1023-g)
        if (kind == SceneKind.BASE && !goalBriefPlayed) {
            state.phase(Phase.SCENE_INTRO, Sub.GOAL_BRIEF);
            speakGenerated(FlowRole.PEER, PracticeInstructions.goalBrief(pack.goals().get(state.goalIndex()).goalName(),
                    state.goalIndex() + 1, pack.goals().size(), settings.directAsk()));
            return;
        }
        beginIntroduction();
    }

    private void beginIntroduction() {
        state.phase(Phase.SCENE_INTRO, Sub.NONE);
        Scene scene = currentScene;
        if (settings.directAsk()) speakApproved(lines.introduction(scene));
        else speakApproved(PracticeInstructions.spokenIntroduction(scene));
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

    private void closing(boolean byRequest) {
        if (!byRequest && beginGoalRecap(false)) return;
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

    private boolean beginGoalRecap(boolean advances) {
        if (goalRecapped || !sceneRowCreated || stopping) return false;
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
                reviewPerformed, reviewAfterHelp, reviewClarified, reviewHelpProvided, reviewInterrupted, !advances,
                settings.directAsk()));
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
        if (settings.flexibleDialogue() && state.phase() == Phase.OPPORTUNITY && utterance.uncertain()) {
            confirmUncertainInput(false);
            return;
        }
        JudgmentPoint point = expectedJudgmentPoint();
        UUID correlation = UUID.randomUUID();
        output.cancel(TIMER_NO_RESPONSE);
        judgmentCorrelation = correlation;
        judgmentAsksProcedure = PracticeProcedureQuestion.matches(utterance.text());
        state.judgmentOpen(true);
        boolean accepted = output.requestJudgment(point, utterance.seq(), correlation, criteriaFor(point, judgmentAsksProcedure));
        if (!accepted) {
            // 거절(열린 턴 · 공급자 없음 · 전사 범위 밖 등)은 보류와 같게 이어 간다. 연습은 멈추지 않는다(BR3.7)
            judgmentCorrelation = null;
            judgmentAsksProcedure = false;
            state.judgmentOpen(false);
            unavailableJudgment(point);
        }
    }

    /**
     * J2 · J3 · J5에만 판정 기준을 싣는다(D2). coreFunction은 목표의 판정 기준 문자열에서 만든다(상한 안으로 줄인다). C안은 같은 걸음 묶음이 있으면
     * 함께 싣고, 기준이 없는 J4에는 묶음만 든 맵을 보낸다(묶음이 없으면 지금처럼 null). C안 기회(J2)에는 설정의 낮춘 하한도 싣는다(판정 기준이 아니라
     * 검증기만 읽는다, 편차 FT-1024-h).
     */
    private Map<String, Object> criteriaFor(JudgmentPoint point, boolean asksProcedure) {
        if (settings.flexibleDialogue() && goalAchieved && state.phase() == Phase.OPPORTUNITY) {
            return Map.of("coreFunction", "ROLE_DIALOGUE_CLOSURE", "judgmentCriteria",
                    "친구 역할 대화에서 제안에 동의하거나 대안을 합의하는 등 현재 상황이 정리됐는지 본다. "
                    + "새 질문·이유 묻기·조건 제시·반대·진행 방법 질문은 대화가 더 필요하다. 짧은 응도 직전 제안을 받아들이는 맥락이면 정리될 수 있다.");
        }
        List<List<String>> groups = sameStepGroups(point, asksProcedure);
        if (point != JudgmentPoint.J2 && point != JudgmentPoint.J3 && point != JudgmentPoint.J5) {
            return groups.isEmpty() ? null : Map.of(PracticeOutput.SAME_STEP_GROUPS, groups);
        }
        String criteria = pack.goals().get(state.goalIndex()).judgmentCriteria();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("coreFunction", criteria.length() <= CORE_FUNCTION_MAX ? criteria : criteria.substring(0, CORE_FUNCTION_MAX));
        map.put("judgmentCriteria", criteria);
        if (!groups.isEmpty()) {
            map.put(PracticeOutput.SAME_STEP_GROUPS, groups);
        }
        if (point == JudgmentPoint.J2 && settings.directAsk() && settings.directAskJ2MinConfidence() != null) {
            map.put(PracticeOutput.MIN_CONFIDENCE, settings.directAskJ2MinConfidence());
        }
        return map;
    }

    /**
     * C안: 지금 단계에서 이 흐름이 같은 다음 걸음으로 처리하는 선택지 묶음. 결과를 받을 때와 같은 바꾸기({@link #handledOption})를 거쳐 전이표에
     * 넣어 본 전이로 정한다(손으로 정한 목록이 아니다). 판정 중에는 단계가 바뀌지 않는다 — 시계 만료 · 종료로 단계가 바뀌면 열린 판정을 거두므로 늦은
     * 결과는 이 묶음과 다른 상태에 적용되지 않는다. 유연 대화는 기회 단계의 결과를 따로 처리해 묶음을 보내지 않는다(지금 동작 그대로).
     */
    private List<List<String>> sameStepGroups(JudgmentPoint point, boolean asksProcedure) {
        if (!settings.directAsk() || point == null) {
            return List.of();
        }
        return transitions.sameStepGroups(state, point, code -> handledOption(point, code, asksProcedure));
    }

    /**
     * 판정 결과 코드를 전이표에 넣기 전에 흐름이 바꾸는 규칙: 아이가 할 말을 정하지 못하고 방법을 물었으면(절차 질문) 수행 코드도 도움 요청(HELP)으로
     * 본다. 모델이 낸 결과(판정 기록)는 그대로다.
     */
    private static String handledOption(JudgmentPoint point, String option, boolean asksProcedure) {
        boolean answerPoint = point == JudgmentPoint.J2 || point == JudgmentPoint.J3 || point == JudgmentPoint.J5;
        boolean performed = "FULL".equals(option) || "DELIVERED".equals(option) || "COPIED".equals(option);
        return asksProcedure && answerPoint && performed ? "HELP" : option;
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
        if (settings.flexibleDialogue() && flexibleJudgment(point, option, asksProcedure)) return;
        // Preserve the model's recorded result; the flow must not finish on a recognized question about how to practise.
        option = handledOption(point, option, asksProcedure);
        afterJudgment(point == null ? Step.ignore() : transitions.onJudgment(state, point, option));
    }

    private void onJudgmentAbstained(ExtensionEvent event) {
        if (!closeJudgment(event.correlationId())) {
            return;
        }
        Map<?, ?> value = event.value() instanceof Map<?, ?> map ? map : null;
        JudgmentPoint point = value != null ? pointOf(value.get("point")) : expectedJudgmentPoint();
        if (settings.directAsk() && value != null && LOW_SCORE_ABSTAINS.contains(String.valueOf(value.get("abstainReason")))) {
            unsureJudgment(point);
            return;
        }
        // 판독 불가 입력 · 판정 장애(시한 · 공급자 오류 · 응답 검증 위반 등)와 유연 대화는 지금처럼 확인 질문 · 같은 질문 재안내다
        unavailableJudgment(point);
    }

    /**
     * C안 판정 보류(받아쓰기는 됐는데 1순위 확률이 하한 · 최소 차이에 못 미쳤다): 첫 번째는 확인 질문 대신 그 단계의 묻는 말로 다시 묻고(기회는 되묻기
     * 단계로), 두 번째는 장애와 같이 도움 단계로 간다({@link FlowTransitions#onJudgmentUnsure}, 편차 FT-1024-h). 첫 번째에는 그 되묻기 · 다시 묻기가
     * 판정 결과가 아니라 보류에서 왔다는 고정 사유 코드를 남긴다.
     */
    private void unsureJudgment(JudgmentPoint point) {
        Step step = point == null ? Step.ignore() : transitions.onJudgmentUnsure(state, point);
        if (step.guard() == FlowTransitions.Guard.UNSURE) {
            output.diagnostic(DIAG_UNSURE_ASKED_AGAIN + point.name());
        }
        afterJudgment(step);
    }

    private JudgmentPoint expectedJudgmentPoint() {
        return settings.flexibleDialogue() && goalAchieved && state.phase() == Phase.OPPORTUNITY
                ? JudgmentPoint.J3 : FlowTransitions.expectedPoint(state.phase());
    }

    private void unavailableJudgment(JudgmentPoint point) {
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

    /** 확인 질문은 한 번, 두 번째면 장면을 닫지 않고 도움 제안으로 넘긴다(FT-1023-g). 판독 불가 사실은 그대로 남긴다. */
    private void confirmUncertainInput(boolean abstained) {
        output.cancel(TIMER_NO_RESPONSE);
        if (++uncertainAttempts > 1) {
            uncertainAttempts = 0;
            applyStep(Step.enter(Phase.HELP_OFFER, Sub.NONE).fact(abstained ? null : FactKind.TRANSCRIPT_FAILED));
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
                if (!output.managesSpeechTimeouts()) output.schedule(slotGuardName(), settings.slotGuard());
            }
            return;
        }
        // 부분 재생(B1)은 C안만 나머지를 다시 읽는다. 유연 대화는 지금처럼 재생 끝으로 본다(docs/practice-direct-ask.md 7.7)
        if (fact.partial() && settings.directAsk() && resumePartialReading(fact, correlation)) {
            return;
        }
        SlotEnd end = fact.played() ? SlotEnd.PLAYED : fact.interrupted() ? SlotEnd.INTERRUPTED : SlotEnd.FAILED;
        handleSlotEnd(correlation, end);
    }

    /**
     * C안: 조각 단위 승인 낭독(B1)이 원문 중간에서 끊겨 앞부분만 들렸다(실행부의 부분 재생 사실). 아이가 듣지 못한 나머지(끊긴 곳이 든 문장부터 끝까지,
     * {@link DirectAskLines#unheardRest})를 새 승인 원문으로 낭독 하나에 한 번 다시 읽는다. 지금 슬롯은 처리 완료로 두고 단계를 바꾸지 않으므로 다음
     * 걸음(판정 열기 · 무응답 시계 · 다음 말)은 나머지까지 재생이 끝난 뒤에 간다. 다시 읽은 나머지도 끊기면 지금 있는 복구 길({@link #retryOrRecoverReading})을
     * 그대로 탄다 — 재시도 · 복구 횟수는 늘리지 않는다. 진단은 고정 사유 코드뿐이다(R-F14).
     *
     * @return 여기서 처리했으면 참(거짓이면 재생 끝으로 처리한다)
     */
    private boolean resumePartialReading(PlaybackFact fact, UUID correlation) {
        PracticeSpeech speech = fact.speech();
        if (speech == null || speech.generated() || correlation == null || !correlation.equals(openSlot)
                || state.isHandled(correlation)) {
            return false;
        }
        String rest = DirectAskLines.unheardRest(speech.text(), fact.heardEnd());
        if (rest == null) {
            // 들리지 않은 글에 말이 없다(전사가 말끝 부호만 빠뜨림 등): 다 들린 것으로 보고 다시 읽지 않는다
            output.diagnostic(DIAG_REMAINDER_WITHOUT_WORDS);
            return false;
        }
        if (remainderReread) {
            output.diagnostic(DIAG_REMAINDER_CUT_AGAIN);
            retryOrRecoverReading(speech);
            return true;
        }
        remainderReread = true;
        output.diagnostic(DIAG_REMAINDER_REREAD);
        cancelOpenSlot();
        submitApproved(rest);
        return true;
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
            output.cancel(slotGuardName());
            openSlot = null;
            openSlotPlaying = false;
        } else {
            output.cancel(TIMER_NOTICE_GUARD);
            awaitingNotice = false;
        }
        // FT-1023-g: 끝내 못 들려준 발화(heard=false)는 건너뛰고 잇는다. 실패 원인은 실행부 진단이 이미 남긴다
        boolean heard = end == SlotEnd.PLAYED || end == SlotEnd.INTERRUPTED;
        if (mine && state.sub() == Sub.GOAL_RECAP) {
            // No retry or approved-speech fallback: a refused or unheard recap is skipped, never replayed.
            if (recapAdvances) advanceGoal();
            else closing(false);
            return;
        }
        if (mine && state.sub() == Sub.GOAL_BRIEF) {
            // A played, interrupted or unheard brief all continue to the reviewed scene start; never repeat it.
            goalBriefPlayed = true;
            beginIntroduction();
            return;
        }
        boolean introEnded = mine && state.phase() == Phase.SCENE_INTRO && state.sub() == Sub.NONE && !sceneRowCreated;
        if (introEnded) {
            // 들려주지 못한 도입도 장면 행을 만들고(PLAYBACK_FAILED 사실이 붙는다) 여는 말 · 기회 문장으로 간다. 막힌 도입은 다시 내지 않는다.
            // 여는 말은 상대 역할의 말이라 유연 대화만 읽는다. C안은 곧바로 전하기 + 묻기다(docs/practice-direct-ask.md 13.6)
            createSceneRow();
            applyStep(transitions.onIntroductionEnded(end, settings.flexibleDialogue() && currentScene.openingLine() != null));
            return;
        }
        if (mine && state.sub() == Sub.OPENING) {
            if (!heard) {
                recordFact(end == SlotEnd.FAILED ? FactKind.PLAYBACK_FAILED : null);
                openingDone();
            } else if (openingAnswered) {
                openingDone();
            } else {
                // 대답 한 차례 또는 무응답 대기 만기까지 기다린다. 무응답 재안내 횟수에는 넣지 않는다. 아이가 이미 말하는 중이면 그 말의 확정 전사(또는
                // 안전 시계)를 기다린다(4회차 R1)
                openingAwaiting = true;
                if (!childSpeaking) output.schedule(TIMER_NO_RESPONSE, settings.noResponseWait());
            }
            return;
        }
        if (settings.flexibleDialogue() && state.phase() == Phase.OPPORTUNITY) {
            if ((state.sub() == Sub.DIALOGUE_REPLY || state.sub() == Sub.CLARIFICATION_REPLY || state.sub() == Sub.INPUT_CONFIRMATION)
                    && heard) {
                applyStep(Step.enter(Phase.OPPORTUNITY, Sub.NONE));
                return;
            }
            if (state.sub() == Sub.RETURN_NOTICE) {
                returnToDialogue(end);
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

    /** 코치 도움 뒤 친구 역할 대화로 돌아간다. 복귀 알림을 못 들려줬어도 돌아간다(FT-1023-g). */
    private void returnToDialogue(SlotEnd end) {
        applyStep(Step.enter(Phase.OPPORTUNITY, Sub.DIALOGUE_REPLY).role(FlowRole.PEER).fact(FactKind.COACH_RETURN)
                .fact(FactKind.ORIGINAL_SCENE_RETURN).fact(end == SlotEnd.FAILED ? FactKind.PLAYBACK_FAILED : null));
    }

    private void onTimer(String name) {
        if (TIMER_NO_RESPONSE.equals(name) && state.sub() == Sub.OPENING) {
            // 여는 말 뒤 무응답: 재안내 없이 기회 문장으로 간다(재안내 횟수 불변). 재생 중에 남은 늦은 만기는 무시한다
            if (openingAwaiting) openingDone();
            return;
        }
        switch (name) {
            case TIMER_NO_RESPONSE -> applyStep(transitions.onNoResponse(state));
            case TIMER_CHILD_SPEECH -> onChildSpeechGuard();
            case TIMER_FOCUS_MAX -> onFocusMax();
            case TIMER_GOAL_LIMIT -> onGoalLimit();
            case TIMER_NOTICE_GUARD -> {
                if (awaitingNotice) {
                    awaitingNotice = false;
                    if (settings.flexibleDialogue() && state.phase() == Phase.OPPORTUNITY && state.sub() == Sub.RETURN_NOTICE) {
                        returnToDialogue(SlotEnd.FAILED);
                    } else {
                        applyStep(transitions.onSlotEnded(state, SlotEnd.FAILED));
                    }
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
            case AWAIT -> {
                step.facts().forEach(this::recordFact);
                awaitInput();
            }
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

    private void reguide(Step step) {
        output.cancel(TIMER_NO_RESPONSE);
        earlyUtterance = null;
        switch (step.guard()) {
            case NO_RESPONSE -> state.noResponseGuided(true);
            case UNREADABLE, UNSURE -> state.unreadableGuided(true);
            case NONE -> {
                // 재안내 표지 없음
            }
        }
        if (settings.directAsk()) {
            reguideDirectAsk(step.guard());
            return;
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

    /**
     * C안 재안내(docs/practice-direct-ask.md 3.2): 같은 단계의 질문을 교정 없이 승인 문장으로 한 번 더 낸다.
     *
     * <ul>
     *   <li>무응답({@code NO_RESPONSE}): 기다리기 + 그 단계의 질문(다시 해 보기는 고정 RETRY_PROMPT).
     *   <li>판독 불가 · 판정 장애 · 불명확({@code UNREADABLE}): 고정 INPUT_CONFIRM.
     *   <li>판정 보류(확률 하한 · 최소 차이, {@code UNSURE}, 편차 FT-1024-h): 그 단계의 묻는 말 — 되묻기 단계는 대본 되묻기(없으면 대체 문장), 다시 해
     *       보기는 고정 RETRY_PROMPT다. 기회(J2)의 보류는 표가 되묻기 단계로 보내므로 기회에서 이 재안내를 하지 않는다(한다면 그 단계의 묻는 말이다).
     *   <li>도움 · 변형 제안은 어느 경우든 같은 제안 문장을 다시 읽는다(불명확 대답 포함).
     * </ul>
     */
    private void reguideDirectAsk(FlowTransitions.Guard guard) {
        boolean noResponse = guard == FlowTransitions.Guard.NO_RESPONSE;
        boolean unsure = guard == FlowTransitions.Guard.UNSURE;
        String confirm = lines.fixed(DirectAskLines.INPUT_CONFIRM);
        switch (state.phase()) {
            case OPPORTUNITY -> speakApproved(noResponse ? lines.waitAndAsk(currentScene) : unsure ? lines.ask(currentScene) : confirm);
            case REASK -> speakApproved(noResponse ? lines.waitAndReask(currentScene) : unsure ? lines.reask(currentScene) : confirm);
            case RETRY -> speakApproved(noResponse || unsure ? lines.fixed(DirectAskLines.RETRY_PROMPT) : confirm);
            case HELP_OFFER -> speakApproved(lines.fixed(DirectAskLines.HELP_OFFER));
            case VARIANT_OFFER -> speakApproved(lines.fixed(DirectAskLines.VARIANT_OFFER));
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
        // C안은 다시 설명 뒤 묻는 말을 같은 슬롯으로 다시 읽는다(main은 다시 설명만 읽고 듣는다)
        speakApproved(settings.directAsk() ? lines.reexplanation(currentScene) : currentScene.reexplanation());
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
            if (settings.directAsk() && step.cause() == FlowTransitions.EndCause.NORMAL && sceneAchievement == null) {
                sceneAchievement = step.timing();
            }
        }
        if (step.endsByRequest()) {
            state.endRequested(true);
        }
        state.phase(step.phase(), step.sub());
        state.noResponseGuided(false);
        state.unreadableGuided(false);
        if (step.guard() == FlowTransitions.Guard.UNSURE || step.guard() == FlowTransitions.Guard.UNREADABLE) {
            // 앞 단계에서 쓴 확인 · 보류 재안내를 새 단계로 잇는다(J2 판정 보류 → 되묻기 단계, 편차 FT-1024-h): 그 단계의 판독 불가 · 보류는 두 번째
            // 불명확이라 지금처럼 도움 단계로 간다(횟수 · 상한을 늘리지 않는다)
            state.unreadableGuided(true);
        }
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
                else if (step.sub() == Sub.OPENING) speakApproved(currentScene.openingLine());
                else if (previousSub == Sub.REEXPLAIN || previousSub == Sub.DIALOGUE_REPLY
                        || previousSub == Sub.CLARIFICATION_REPLY || previousSub == Sub.INPUT_CONFIRMATION) awaitInput();
                // C안: 무중이 상대 말을 자기 말로 전하고 곧바로 묻는다(한 슬롯). 유연 대화는 상대의 직접 대사를 읽는다
                else speakApproved(settings.directAsk() ? lines.opportunity(currentScene) : currentScene.opportunitySentence());
            }
            case REASK -> {
                if (settings.directAsk()) speakApproved(lines.reask(currentScene));
                else speakGenerated(FlowRole.PEER, PracticeInstructions.peerReask(currentScene, false));
            }
            case HELP_OFFER -> {
                if (settings.directAsk()) speakApproved(lines.fixed(DirectAskLines.HELP_OFFER));
                else speakGenerated(FlowRole.PEER, PracticeInstructions.peerHelpOffer(currentScene, false));
            }
            case FOCUS -> {
                if (step.sub() == Sub.ROLE_NOTICE) {
                    announce(FlowRole.COACH);
                } else if (settings.directAsk()) {
                    helpUtterance();
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
                } else if (step.sub() == Sub.FRIEND_REACTION) {
                    speakApproved(lines.friendReaction(currentScene));
                } else if (step.sub() == Sub.SCENE_CLOSE && sceneRowCreated) {
                    speakApproved(lines.fixed(DirectAskLines.SCENE_CLOSE));
                } else {
                    // 변형 평가(들려준 장면이 없으면 마무리 문장 없이 곧바로 평가한다)
                    recordOutcome(null, state.sceneTiming());
                    applyStep(transitions.evaluateVariant(state, currentScript.variantScene().isPresent()));
                }
            }
            case VARIANT_OFFER -> {
                if (settings.directAsk()) speakApproved(lines.fixed(DirectAskLines.VARIANT_OFFER));
                else speakGenerated(FlowRole.PEER, PracticeInstructions.peerVariantOffer(currentScene, false));
            }
            case NEXT_GOAL -> advanceGoal();
            case CLOSING -> {
                boolean sceneWasOpen = (previous != Phase.SCENE_WRAP || previousSub == Sub.PEER_REPLY
                        || previousSub == Sub.RETURN_NOTICE || previousSub == Sub.FRIEND_REACTION)
                        && previous != Phase.NEXT_GOAL && previous != Phase.CLOSING;
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

    /**
     * C안 도움(FOCUS 진입): 역할 전환 없이 대본 helpSpeech(옛 대본은 대체 문장)를 승인 낭독한다. 첫 도움부터 표현 예시가 있다 — 도움은 장면마다 한 번이고
     * 동의와 회차를 따지는 {@link RolePolicy#mayGiveExample}을 쓰지 않는다(docs/practice-direct-ask.md 1.3, 편차 FT-1024-d). 집중 시계는 처음 도울
     * 때 한 번 건다. 도움 행의 역할은 '도운 쪽'이라 COACH 그대로 둔다(역할 전환 행 · 사실은 남기지 않는다).
     */
    private void helpUtterance() {
        if (state.focusStartedAt() == null) {
            state.focusStartedAt(clock.now());
            output.schedule(TIMER_FOCUS_MAX, settings.focusMax());
        }
        DirectAskLines.Help speech = lines.help(currentScene);
        recordFact(FactKind.FOCUS_HELP);
        if (speech.exampleGiven()) {
            recordFact(FactKind.EXPRESSION_EXAMPLE);
        }
        help(HelpKind.FOCUS_HELP, FlowRole.COACH, speech.exampleGiven(), state.retryCount() == 0 ? pendingConsent : null,
                speech.text().length());
        speakApproved(speech.text());
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
        remainderReread = false;
        submitApproved(text);
    }

    private void onIncompleteAudio(PracticeSpeech speech) {
        if (!speech.correlationId().equals(openSlot) || stopping) return;
        if (openSlotPlaying) {
            // FT-1023-e: 이미 재생이 시작된 생성형 발화의 꼬리가 비었다 — 같은 지시를 다시 요청하지 않고(아이가 같은 말을 두 번 듣는다) 나간 부분까지
            // 재생됨으로 닫는다. 실행부가 이 경우를 먼저 닫으므로 보통 오지 않지만, 무시하면 실행부가 시한을 쥔 슬롯이 닫히지 않고 남는다. 승인 원문 읽기는 지금처럼
            // 무시한다(실행부가 송신과 함께 슬롯을 놓아 재생 뒤 이 사건이 오지 않는다)
            if (speech.generated()) handleSlotEnd(speech.correlationId(), SlotEnd.PLAYED);
            return;
        }
        if (!speech.generated()) { onApprovedTextMismatch(speech); return; }
        if (generatedAudioRetried) { handleSlotEnd(speech.correlationId(), SlotEnd.FAILED); return; }
        cancelOpenSlot();
        // Retry the trusted server instruction, never the rejected model output or an inferred child answer.
        speakGenerated("COACH".equals(speech.partnerRole()) ? FlowRole.COACH : FlowRole.PEER, speech.text());
        generatedAudioRetried = true;
    }

    private void onApprovedTextMismatch(PracticeSpeech speech) {
        if (speech.generated() || !speech.correlationId().equals(openSlot) || openSlotPlaying) return;
        retryOrRecoverReading(speech);
    }

    /**
     * 들려주지 못한 승인 낭독의 복구 길(원문 불일치, C안에서는 다시 읽은 나머지가 또 끊긴 때도 같다): 같은 글을 한 번 다시 읽고, 이미 다시 읽었으면 생성형
     * '뜻 전하기'를 한 번 내며, 그것도 만들 수 없으면 못 들려준 말로 건너뛴다(FT-1023-g). 낭독마다 같은 글 재시도 한 번 · 복구 한 번이다.
     */
    private void retryOrRecoverReading(PracticeSpeech speech) {
        if (approvedReadingRetried) {
            String recovery = PracticeInstructions.recoverApprovedNarration(speech.text());
            if (recovery == null) {
                handleSlotEnd(speech.correlationId(), SlotEnd.FAILED);
                return;
            }
            cancelOpenSlot();
            // One fresh generated narration; normal gate + Jev and slot timeout still apply.
            // Generated slots cannot enter this mismatch retry path, so this cannot loop.
            speakGenerated(state.role(), recovery);
            return;
        }
        // 원문과 다른 말은 들려주지 않았다(불일치), 또는 C안에서 다시 읽은 나머지가 또 끊겼다. 새 슬롯 하나에 같은 승인 글을 한 번만 더 읽힌다
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
                    settings.directAsk() ? RolePolicy.MUJOONG_LABEL : RolePolicy.partnerLabel(state.role(), currentScene.partnerRole()), text,
                    state.phase() == Phase.SCENE_INTRO ? PracticeSpeech.ReadingCue.SCENE
                            : state.sub() == Sub.REEXPLAIN ? PracticeSpeech.ReadingCue.REEXPLANATION : PracticeSpeech.ReadingCue.PEER);
        } catch (IllegalArgumentException invalid) {
            // 승인 문장이 재생 형식(길이 · 제어 문자)을 못 맞춘다: 문장 내용은 남기지 않고 재생 실패로 닫는다
            speech = null;
        }
        launch(correlation, speech != null && output.speak(speech));
    }

    private void speakGenerated(FlowRole role, String instruction) {
        generatedAudioRetried = false;
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
        if (!output.managesSpeechTimeouts()) output.schedule(slotGuardName(), settings.slotGuard());
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
        if (kind == null) return;
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
            LiveTiming recordedTiming = settings.flexibleDialogue() ? (goalAchieved ? goalAchievementTiming : timing)
                    : sceneAchievement != null ? sceneAchievement : timing;
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
