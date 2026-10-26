package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_ASK;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_HELP;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_INTRO;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_OPPORTUNITY;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_REACTION;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_REASK;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_REEXPLAIN;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.judgment.AbstainReason;
import kr.mujoong.realtime.judgment.DecisionsJudgeClient;
import kr.mujoong.realtime.judgment.JudgeClient;
import kr.mujoong.realtime.judgment.JudgeOutcome;
import kr.mujoong.realtime.judgment.JudgeOutcomeValidator;
import kr.mujoong.realtime.judgment.JudgeRequest;
import kr.mujoong.realtime.judgment.JudgeRequest.Speaker;
import kr.mujoong.realtime.judgment.JudgmentLiveRecord;
import kr.mujoong.realtime.judgment.JudgmentOutcomeKind;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.judgment.JudgmentPrompt;
import kr.mujoong.realtime.luna.LunaDecision;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.JudgmentSupport;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.session.NoOpPracticeSessionExtension;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.FakeJudgeClient;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 새 연습 흐름 끝-끝 최소 범위 (U12 FR8.1, 계획 열린 질문 6): 운영 기본 경로인 C안 '무중이 직접 묻기'(docs/practice-direct-ask.md 2장 공룡 그림
 * 장면 "손 아파? → 되묻기 → 손 괜찮아? 그럼 다음에 같이 그리자")와 설정으로 되돌린 유연 대화 장면을 실행부({@code SessionExecutor}) · 라우터 · 상태
 * 기계 · 운영 기록기(실제 DB V7 표)로 시작부터 {@code record.practice}까지 본다. 공급자 · 제브 · 판정은 모두 시험이 손으로 푸는 가짜이다(R-F1). 이
 * 시험의 성공은 테스트 대역 · 합성 대화의 성공이며 실제 API · 실제 기기 성공이 아니다(R-M11). 기기 시뮬레이터 · {@code verify.sh} 단계 확장은 하지
 * 않고 후속 과제다.
 */
@Tag("practice-flow")
@Tag("db")
@org.junit.jupiter.api.Tag("service-guardrail")
class PracticeFlowIT {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String OPPORTUNITY = "친구가 말했어. 그거 줘.";
    private static final String MARKER = "표식QX93";

    private static RealtimeDb db;

    private final SessionHarness h = new SessionHarness();
    private final FakeJudgeClient judge = h.useJudge();
    private final FakeGuardrailClient guard = h.useGuardrail();
    private final List<Object> settled = new ArrayList<>();
    private final List<RuntimeException> failures = new ArrayList<>();
    private PracticeFlowRepository repository;
    private boolean flexible;

    @BeforeAll
    static void openDatabase() {
        db = RealtimeDb.open();
    }

    @AfterAll
    static void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        db.wipe();
        repository = new PracticeFlowRepository(db.jdbc());
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession row = new NewActivitySession(h.sessionId, h.guardianId, UUID.randomUUID(), h.deviceId,
                ActivityType.PRACTICE, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("하늘", 8, List.of("공룡")), null, null);
        assertThat(db.inTransaction(() -> sessions.insert(row, h.clock.now()))).isTrue();

        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = packageJson(GOAL, minimalScript(SCENARIO));
        h.settlement = (sessionId, practice, cutoffAt) -> settled.add(practice);
        PracticeScriptRequester requester = new PracticeScriptRequester(mock(InternalHttpClient.class), Runnable::run,
                Duration.ofSeconds(1));
        h.practice = session -> new PracticeFlowRouter(NoOpPracticeSessionExtension.FACTORY.create(session),
                () -> new PracticeConversation(session, h.guardianId, h.clock,
                        flexible ? new PracticeFlowSettings(Duration.ofSeconds(10), Duration.ofSeconds(90), Duration.ofMinutes(10),
                                Duration.ofSeconds(20), 2, 1, true) : PracticeFlowTestKit.defaults(), requester,
                        new PracticeFlowRecorder(repository, db.transactions()), failures::add));
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDirectAskReaskThatResolvesTheSceneRunsFromStartToTheSettledPracticeRecord() {
        directAsk();
        h.activate();
        // 장면 밖 목표 안내는 생성형이라 제브(PRACTICE_PEER)를 거친다
        playGenerated("오늘 연습할 목표는 부탁하기야. 짧게 이야기 연습을 해 볼 거야.");
        assertThat(guard.count()).isEqualTo(1);
        // 장면 안 말은 모두 대본 칸의 승인 낭독이다: 원문 그대로 재생되면 제브를 부르지 않고, 재생이 끝나면 다음으로 간다
        playApproved(V2_INTRO);
        assertThat(repository.findSceneRuns(h.sessionId)).as("상황 안내 재생 성공 뒤에야 장면 행이 생긴다").hasSize(1);
        playApproved(V2_OPPORTUNITY);
        assertThat(guard.count()).as("친구 말 전하기 + 묻기도 승인 낭독").isEqualTo(1);

        // 아이 대답이 일부만 전했다 → J2 PARTIAL → 무중이 대본의 되묻기를 그대로 읽는다(생성형 · 제브 없음)
        h.childSays("child-1", "손 아파? " + MARKER);
        assertThat(judge.last().request().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(judge.last().request().recentTurns()).as("판정 입력의 무중 줄은 화자 MUJOONG(역할 없음)")
                .allSatisfy(turn -> assertThat(turn.speaker()).isIn(Speaker.MUJOONG, Speaker.CHILD))
                .anySatisfy(turn -> {
                    assertThat(turn.speaker()).isEqualTo(Speaker.MUJOONG);
                    assertThat(turn.text()).isEqualTo(V2_OPPORTUNITY);
                });
        judge.confirm("PARTIAL", 0.8, "FULL", 0.1);
        h.drain();
        playApproved(V2_REASK);
        assertThat(guard.count()).isEqualTo(1);

        // 되물은 뒤 충분히 전했다 → J3 DELIVERED → 대본의 친구 반응을 승인 낭독하고 재생이 끝나야 장면이 끝난다
        h.childSays("child-2", "손 괜찮아? 그럼 다음에 같이 그리자");
        assertThat(judge.last().request().point()).isEqualTo(JudgmentPoint.J3);
        assertThat(judge.last().request().recentTurns()).anySatisfy(turn -> {
            assertThat(turn.speaker()).isEqualTo(Speaker.MUJOONG);
            assertThat(turn.text()).isEqualTo(V2_REASK);
        }).noneSatisfy(turn -> assertThat(turn.speaker()).isIn(Speaker.PARTNER, Speaker.COACH));
        judge.confirm("DELIVERED", 0.9, "STILL_SHORT", 0.05);
        h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(repository.findSceneRuns(h.sessionId).getFirst().endedAt()).isNull();
        playApproved(V2_REACTION);
        assertThat(guard.count()).as("친구 반응도 승인 낭독").isEqualTo(1);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);

        // 장면 밖 목표 회고(생성형 · 제브)가 끝나면 연습이 끝난다
        playGenerated("오늘은 부탁하기를 무중이 다시 물었을 때 네 말로 해 봤어. 오늘 연습은 여기까지야.");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endDetail()).isEqualTo("PRACTICE_COMPLETED");
        assertThat(h.provider.last().requests(kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend.class))
                .as("역할 전환 안내 없이 장면 안 다섯 문장을 승인 낭독으로 보냈다").isNotEmpty();

        // 종료 뒤 정산: 같은 값이 표에도, 정산 입구에도 있다
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
        h.advance(SessionHarness.LIFECYCLE.recordSettle());
        assertThat(settled).hasSize(1);
        Map<String, Object> practice = (Map<String, Object>) settled.getFirst();
        String json = JsonCodec.shared().write(practice);
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", json)).isEmpty();
        assertThat(practice).containsEntry("flowVersion", "practice-conversation-v1");
        assertThat((List<Object>) practice.get("scenes")).hasSize(1);

        SceneRunRecord stored = repository.findSceneRuns(h.sessionId).getFirst();
        assertThat(stored.goalRef()).isEqualTo(GOAL);
        assertThat(stored.scenarioRef()).isEqualTo(SCENARIO);
        assertThat(stored.liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(stored.facts()).extracting(PracticeFlowTypes.Fact::kind).contains(FactKind.REASK_RESOLVED)
                .as("C안은 역할 사실을 내지 않는다").doesNotContain(FactKind.COACH_SWITCH, FactKind.COACH_RETURN,
                        FactKind.ORIGINAL_SCENE_RETURN);
        assertThat(repository.findHelps(h.sessionId)).as("집중 도움 0건").isEmpty();
        assertThat(PracticeFlowRecordAssembler.assemble(repository.findSceneRuns(h.sessionId))).isEqualTo(practice);
        assertThat(failures).isEmpty();

        // 표식: 아이가 한 말은 기록 · 정산 값 · 앱 오류 · 이벤트 · 제브 요청 밖의 어디에도 나가지 않는다(대화 전사 행은 별도 기능)
        h.recorder.tick();
        assertThat(json).doesNotContain(MARKER);
        assertThat(h.store.sessionEvents).extracting(SessionEvent::getPayloadJson).noneMatch(payload -> payload != null && payload.contains(MARKER));
        assertThat(h.logs.anyLineContains(MARKER)).isFalse();
        assertThat(h.store.guardrails.toString()).doesNotContain(MARKER);
    }

    @Test
    void aBlockedRecoveryNarrationSendsZeroBytesIsSkippedAndThePracticeKeepsListening() {
        directAsk();
        h.activate();
        playGenerated("오늘 연습할 목표는 부탁하기야.");
        playApproved(V2_INTRO);
        playApproved(V2_OPPORTUNITY);
        h.childSays("child-1", "손 아파?");
        judge.confirm("PARTIAL", 0.8, "FULL", 0.1);
        h.drain();
        int sent = h.link.outputs().size();

        // 승인 되묻기가 두 번 다른 말로 읽히면 버리고, 생성형 '뜻 전하기' 복구를 한 번 낸다
        speakWithoutPlaying("친구한테 다른 걸 물어볼게.");
        speakWithoutPlaying("손이 아프면 어떻게 할까?");
        assertThat(h.link.outputs()).as("다르게 읽힌 승인 낭독은 0바이트").hasSize(sent);
        // 복구 발화는 제브(PRACTICE_PEER)가 두 번 막는다
        speakWithoutPlaying("친구 손이 아프대. 어떤 말을 해 주면 좋을까?");
        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        guard.block();
        h.drain();
        speakWithoutPlaying("다시 한 번 물어볼게");
        guard.block();
        h.drain();

        assertThat(h.link.outputs()).as("막힌 복구 발화도 0바이트, 마무리 문장도 내지 않는다").hasSize(sent);
        assertThat(h.executor.status()).as("두 번째 막힘도 연습을 닫지 않는다(FT-1023-g)").isEqualTo(SessionStatus.ACTIVE);
        SceneRunRecord open = repository.findSceneRuns(h.sessionId).getFirst();
        assertThat(open.facts()).extracting(PracticeFlowTypes.Fact::kind).as("막힘은 재생 실패 사실이 아니다")
                .doesNotContain(FactKind.PLAYBACK_FAILED);
        assertThat(open.endedAt()).as("장면은 아이 말을 계속 기다린다").isNull();
        assertThat(failures).isEmpty();
    }

    // ---- 같은 걸음 묶음: 운영 Decisions 구현 + 실제 검증기 (ai-io.md 10.10, 편차 FT-1024-f) ----

    /** 판정 요청(실행부가 만든 JudgeRequest)과 그 공급자 응답 약속. 응답(선택지 확률)은 시험이 손으로 준다. */
    private final List<JudgeRequest> judged = new ArrayList<>();
    private final List<CompletableFuture<LunaDecision>> decisions = new ArrayList<>();

    /**
     * 진행 중 판정을 운영 구현({@link DecisionsJudgeClient})과 실제 검증기(하한 0.6 · 최소 차이 0.15, application.yml 기본값)로 돌린다. 공급자 호출만
     * 시험이 확률을 손으로 주는 대역이다(R-F1). 판정 판은 경로에 맞춘다(C안 judgment-service-v2, 유연 대화 v1).
     */
    private void judgeWithTheRealValidator() {
        LiveContentResources.JudgmentOptionSet optionSet = LiveContentResources.loadFor(flexible).judgment();
        JudgmentPrompt prompt = JudgmentPrompt.load(optionSet);
        DecisionsJudgeClient client = new DecisionsJudgeClient((request, question) -> {
            CompletableFuture<LunaDecision> pending = new CompletableFuture<>();
            decisions.add(pending);
            return pending;
        }, prompt, new JudgeOutcomeValidator(0.6, 0.15), h.clock);
        JudgeClient recording = new JudgeClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public CompletionStage<JudgeOutcome> judge(JudgeRequest request, UUID turnId) {
                judged.add(request);
                return client.judge(request, turnId);
            }
        };
        h.judgment = new JudgmentSupport(recording, h.judgmentRecorder, Duration.ofSeconds(4), prompt.version(), optionSet);
    }

    /** 마지막 판정에 공급자 응답을 준다(코드 · 확률 짝, 확률이 가장 높은 코드를 공급자가 고른다). */
    private void decide(Object... codeAndProbability) {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (int i = 0; i < codeAndProbability.length; i += 2) {
            probabilities.put((String) codeAndProbability[i], (Double) codeAndProbability[i + 1]);
        }
        String choice = probabilities.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        decisions.getLast().complete(new LunaDecision(choice, probabilities.entrySet().stream()
                .map(entry -> new LunaDecision.Probability(entry.getKey(), entry.getValue())).toList(), "luna-test", "prompt-service-v4",
                Duration.ofMillis(900), 1));
        h.drain();
    }

    private List<JudgmentLiveRecord> judgmentRecords() {
        h.judgmentRecorder.tick();
        return List.copyOf(h.store.judgments);
    }

    /** 공급자에 보낸 승인 낭독 지시(낭독 태그가 든 것)들. */
    private List<String> readings() {
        return h.provider.last().requests(kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend.class).stream()
                .map(kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend::content)
                .filter(content -> content.contains("<approved_speech>")).toList();
    }

    @Test
    void aDirectAskRetryWhoseTopTwoAreBothSuccessesIsConfirmedByTheirSumAndEndsAfterHelp() {
        directAsk();
        judgeWithTheRealValidator();
        h.activate();
        playGenerated("오늘 연습할 목표는 부탁하기야. 짧게 이야기 연습을 해 볼 거야.");
        playApproved(V2_INTRO);
        playApproved(V2_OPPORTUNITY);
        h.childSays("child-1", "몰라. 뭐라고 해야 돼?");
        decide("FULL", 0.02, "PARTIAL", 0.02, "HELP", 0.9, "NOT_UNDERSTOOD", 0.02, "IRRELEVANT", 0.02, "UNREADABLE", 0.02);
        playApproved(V2_HELP);

        // 5회차 S6(실제 API + 합성 아이 음성): 도움 뒤 맞게 말했는데 FULL 0.38 · COPIED 0.29라 LOW_CONFIDENCE 보류였다
        h.childSays("child-2", "내가 필요한 건 파란색이야");
        assertThat(judged.getLast().point()).isEqualTo(JudgmentPoint.J5);
        assertThat(judged.getLast().sameStepGroups()).as("흐름이 정한 묶음이 판정 요청까지 온다")
                .containsExactly(List.of("FULL", "COPIED"), List.of("PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT"));
        decide("FULL", 0.38, "PARTIAL", 0.2, "HELP", 0.05, "NOT_UNDERSTOOD", 0.03, "IRRELEVANT", 0.02, "UNREADABLE", 0.03,
                "COPIED", 0.29);

        // 두 확률의 합 0.67로 확정 → 확인 질문 없이 대본의 친구 반응으로 장면이 끝난다
        playApproved(V2_REACTION);
        playGenerated("오늘은 부탁하기를 무중이 도와준 뒤에 네 말로 해 봤어. 오늘 연습은 여기까지야.");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        SceneRunRecord stored = repository.findSceneRuns(h.sessionId).getFirst();
        assertThat(stored.liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
        assertThat(readings()).as("확인(INPUT_CONFIRM)을 읽지 않았다").noneMatch(content -> content.contains(FIXED_SPEECH.get("INPUT_CONFIRM")));
        JudgmentLiveRecord retry = judgmentRecords().getLast();
        assertThat(retry.point()).isEqualTo(JudgmentPoint.J5);
        assertThat(retry.outcome()).isEqualTo(JudgmentOutcomeKind.CONFIRMED);
        assertThat(retry.firstOption()).as("기록은 모델이 낸 1순위 그대로").isEqualTo("FULL");
        assertThat(retry.firstProbability()).isEqualTo(0.38);
        assertThat(retry.secondOption()).isEqualTo("COPIED");
        assertThat(retry.secondProbability()).isEqualTo(0.29);
        assertThat(h.logs.anyLineContains("JudgmentConfirmedBySameStepSum_J5")).isTrue();
        assertThat(h.logs.anyLineContains("JudgmentConfirmedBySameStepSum_J2")).as("1순위 혼자 확정한 J2는 표지가 없다").isFalse();
        // 장면 안 승인 낭독 지시에는 배경 역할 · 역할극 · 따옴표 문구가 없다(설계 7.6)
        assertThat(readings()).hasSizeGreaterThanOrEqualTo(4).allSatisfy(content -> assertThat(content)
                .doesNotContain("따옴표", "partnerRole", "역할극", "배경 역할"));
        assertThat(failures).isEmpty();
    }

    @Test
    void aFirstAnswerBetweenTheTwoReexplainCodesIsReexplainedAndHelpOverNotUnderstoodNowPassesTheLoweredJ2Floor() {
        directAsk();
        judgeWithTheRealValidator();
        h.activate();
        playGenerated("오늘 연습할 목표는 부탁하기야.");
        playApproved(V2_INTRO);
        playApproved(V2_OPPORTUNITY);

        // 다시 설명으로 가는 두 코드 사이의 망설임은 합 0.7로 확정해 다시 설명 + 묻기를 읽는다(1순위 0.4는 낮춘 하한 0.45에도 못 미친다)
        h.childSays("child-1", "공룡 그림이 뭐야?");
        assertThat(judged.getLast().sameStepGroups()).containsExactly(List.of("NOT_UNDERSTOOD", "IRRELEVANT"));
        assertThat(judged.getLast().minConfidence()).as("C안 기회 판정에는 낮춘 하한(설정 0.45)이 실린다").isEqualTo(0.45);
        decide("FULL", 0.05, "PARTIAL", 0.1, "HELP", 0.1, "NOT_UNDERSTOOD", 0.4, "IRRELEVANT", 0.3, "UNREADABLE", 0.05);
        playApproved(V2_REEXPLAIN + " " + V2_ASK);

        // 5회차 J2: HELP 0.54 · NOT_UNDERSTOOD 0.30 — 전역 하한 0.6에서는 보류 · 확인이었지만, 낮춘 하한 0.45와 최소 차이 0.15를 넘어 곧바로 도움이다
        h.childSays("child-2", "무중아, 친구한테 어떻게 부탁하면 좋을까?");
        decide("FULL", 0.03, "PARTIAL", 0.05, "HELP", 0.54, "NOT_UNDERSTOOD", 0.3, "IRRELEVANT", 0.05, "UNREADABLE", 0.03);
        playApproved(V2_HELP);

        List<JudgmentLiveRecord> records = judgmentRecords();
        assertThat(records).extracting(JudgmentLiveRecord::outcome)
                .containsExactly(JudgmentOutcomeKind.CONFIRMED, JudgmentOutcomeKind.CONFIRMED);
        assertThat(records.getFirst().firstOption()).isEqualTo("NOT_UNDERSTOOD");
        assertThat(records.getFirst().secondOption()).isEqualTo("IRRELEVANT");
        assertThat(records.getLast().firstOption()).isEqualTo("HELP");
        assertThat(records.getLast().firstProbability()).as("기록 표에서는 1순위 확률이 0.6 미만인 J2 확정으로 알아본다").isEqualTo(0.54);
        assertThat(h.logs.anyLineContains("JudgmentConfirmedBySameStepSum_J2")).isTrue();
        assertThat(readings()).as("확인(INPUT_CONFIRM)을 읽지 않았다").noneMatch(content -> content.contains(FIXED_SPEECH.get("INPUT_CONFIRM")));
        SceneRunRecord open = repository.findSceneRuns(h.sessionId).getFirst();
        assertThat(open.facts()).extracting(PracticeFlowTypes.Fact::kind).containsSubsequence(FactKind.SITUATION_EXPLAINED,
                FactKind.HELP_ACCEPTED);
        assertThat(open.endedAt()).as("도움 뒤 다시 해 보기를 기다린다").isNull();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(failures).isEmpty();
    }

    // ---- 판정 보류 · J2 하한: 운영 Decisions 구현 + 실제 검증기 (ai-io.md 10.10 · 10.18, 편차 FT-1024-h) ----

    @Test
    void aJ2AnswerBelowTheLoweredFloorIsAskedAgainWithTheScriptReaskAndResolvesAfterReask() {
        directAsk();
        judgeWithTheRealValidator();
        h.activate();
        playGenerated("오늘 연습할 목표는 부탁하기야.");
        playApproved(V2_INTRO);
        playApproved(V2_OPPORTUNITY);

        // J2 PARTIAL 0.44: 낮춘 하한 0.45에도 못 미쳐 보류 → 확인 질문 대신 되묻기 단계로 가서 대본의 되묻기를 읽는다
        h.childSays("child-1", "손 아파? " + MARKER);
        decide("FULL", 0.2, "PARTIAL", 0.44, "HELP", 0.16, "NOT_UNDERSTOOD", 0.1, "IRRELEVANT", 0.06, "UNREADABLE", 0.04);
        playApproved(V2_REASK);
        assertThat(h.logs.anyLineContains("PracticeUnsureJudgmentAskedAgain_J2")).isTrue();

        // 되물은 뒤 충분히 전했다 → J3 DELIVERED → 친구 반응, 시점은 J2 PARTIAL 뒤 되묻기와 같은 AFTER_REASK
        h.childSays("child-2", "손 괜찮아? 그럼 다음에 같이 그리자");
        assertThat(judged.getLast().point()).isEqualTo(JudgmentPoint.J3);
        assertThat(judged.getLast().minConfidence()).as("되묻기 판정은 전역 하한 그대로").isNull();
        decide("DELIVERED", 0.9, "STILL_SHORT", 0.05, "HELP", 0.03, "UNREADABLE", 0.02);
        playApproved(V2_REACTION);
        playGenerated("오늘은 부탁하기를 무중이 다시 물었을 때 네 말로 해 봤어. 오늘 연습은 여기까지야.");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        SceneRunRecord stored = repository.findSceneRuns(h.sessionId).getFirst();
        assertThat(stored.liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(stored.facts()).extracting(PracticeFlowTypes.Fact::kind).contains(FactKind.REASK_RESOLVED)
                .doesNotContain(FactKind.TRANSCRIPT_FAILED);
        List<JudgmentLiveRecord> records = judgmentRecords();
        assertThat(records).extracting(JudgmentLiveRecord::point).containsExactly(JudgmentPoint.J2, JudgmentPoint.J3);
        assertThat(records).extracting(JudgmentLiveRecord::outcome)
                .containsExactly(JudgmentOutcomeKind.ABSTAINED, JudgmentOutcomeKind.CONFIRMED);
        assertThat(records.getFirst().abstainReason()).as("판정 기록에는 보류와 그 사유로 남는다").isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(readings()).noneMatch(content -> content.contains(FIXED_SPEECH.get("INPUT_CONFIRM")));
        assertThat(h.logs.anyLineContains(MARKER)).as("진단 코드에 전사가 없다").isFalse();
        assertThat(failures).isEmpty();
    }

    @Test
    void aJ2HelpBetweenTheTwoFloorsGoesStraightToHelpWhileAnUnsureRetryHearsTheRetryPrompt() {
        directAsk();
        judgeWithTheRealValidator();
        h.activate();
        playGenerated("오늘 연습할 목표는 부탁하기야.");
        playApproved(V2_INTRO);
        playApproved(V2_OPPORTUNITY);

        // J2 HELP 0.48 · 2순위와 차이 0.20: 낮춘 하한 0.45로 확정 → 확인 질문 없이 곧바로 도움
        h.childSays("child-1", "어떻게 말해야 할지 모르겠어");
        assertThat(judged.getLast().minConfidence()).isEqualTo(0.45);
        decide("FULL", 0.05, "PARTIAL", 0.1, "HELP", 0.48, "NOT_UNDERSTOOD", 0.28, "IRRELEVANT", 0.05, "UNREADABLE", 0.04);
        playApproved(V2_HELP);

        // 다시 해 보기(J5)는 전역 하한 0.6 그대로: FULL 0.48 · PARTIAL 0.30(다른 걸음)은 보류 → 확인 질문 대신 다시 해 보기의 묻는 말
        h.childSays("child-2", "파란 상자 줄래?");
        assertThat(judged.getLast().point()).isEqualTo(JudgmentPoint.J5);
        assertThat(judged.getLast().minConfidence()).as("다른 지점에는 낮춘 하한을 싣지 않는다").isNull();
        decide("FULL", 0.48, "PARTIAL", 0.3, "HELP", 0.05, "NOT_UNDERSTOOD", 0.05, "IRRELEVANT", 0.04, "UNREADABLE", 0.04, "COPIED",
                0.04);
        playApproved(FIXED_SPEECH.get("RETRY_PROMPT"));
        assertThat(h.logs.anyLineContains("PracticeUnsureJudgmentAskedAgain_J5")).isTrue();

        // 다시 말한 대답이 분명하면 도움 뒤 성공이다
        h.childSays("child-3", "파란 상자 줄래? 높이 쌓고 싶어");
        decide("FULL", 0.9, "PARTIAL", 0.04, "HELP", 0.01, "NOT_UNDERSTOOD", 0.01, "IRRELEVANT", 0.01, "UNREADABLE", 0.01, "COPIED",
                0.02);
        playApproved(V2_REACTION);
        playGenerated("오늘은 부탁하기를 무중이 도와준 뒤에 네 말로 해 봤어. 오늘 연습은 여기까지야.");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(repository.findSceneRuns(h.sessionId).getFirst().liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
        List<JudgmentLiveRecord> records = judgmentRecords();
        assertThat(records).extracting(JudgmentLiveRecord::point).containsExactly(JudgmentPoint.J2, JudgmentPoint.J5, JudgmentPoint.J5);
        assertThat(records).extracting(JudgmentLiveRecord::outcome).containsExactly(JudgmentOutcomeKind.CONFIRMED,
                JudgmentOutcomeKind.ABSTAINED, JudgmentOutcomeKind.CONFIRMED);
        assertThat(records.getFirst().firstProbability()).isEqualTo(0.48);
        assertThat(records.get(1).abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(readings()).noneMatch(content -> content.contains(FIXED_SPEECH.get("INPUT_CONFIRM")));
        assertThat(failures).isEmpty();
    }

    @Test
    void theFlexibleDialogueKeepsTheGlobalJ2FloorAndConfirmsAnUnsureAnswerWithItsGeneratedQuestion() {
        flexible = true;
        judgeWithTheRealValidator();
        h.activate();
        playGenerated("오늘은 부탁하기를 짧은 역할놀이로 연습해 볼 거야.");
        playApproved("상상 속 상황을 떠올려 봐. 무중이가 상황 속 상대 역할을 해 볼게.");
        playApproved(OPPORTUNITY);

        h.childSays("flex-unsure-1", "그거 줘");
        assertThat(judged.getLast().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(judged.getLast().minConfidence()).as("유연 대화는 낮춘 하한을 싣지 않는다").isNull();
        decide("FULL", 0.2, "PARTIAL", 0.48, "HELP", 0.12, "NOT_UNDERSTOOD", 0.1, "IRRELEVANT", 0.06, "UNREADABLE", 0.04);

        // 전역 하한 0.6 그대로 보류 → 지금처럼 생성형 확인 질문(제브를 거친다)
        playGenerated("잘 못 들었어. 다시 한 번 말해 줄래?");
        assertThat(judgmentRecords().getLast().abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(h.logs.anyLineContains("PracticeUnsureJudgmentAskedAgain")).isFalse();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(failures).isEmpty();
    }

    /** C안 대본 v2와 고정 승인 문장(시작 패키지 approvedSpeech)으로 연다. 운영 기본 경로다. */
    private void directAsk() {
        h.practiceJson = packageJson(GOAL, directAskScript(SCENARIO));
        h.approvedSpeech.putAll(FIXED_SPEECH);
    }

    /** 공급자가 말하지만 기기로는 나가지 않는 발화(원문이 다르거나 제브를 기다림). */
    private void speakWithoutPlaying(String text) {
        h.settleSend();
        h.speak(text, 4800);
        h.advance(Duration.ofMillis(801));
    }

    private void playApproved(String text) {
        h.settleSend();
        h.speak(text, 4800);
        h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).isNotEmpty();
        complete(h.link.outputs().getLast().playbackId());
    }

    private void playGenerated(String text) {
        int before = h.link.outputs().size();
        h.settleSend(); h.speak(text, 4800); h.advance(Duration.ofMillis(801));
        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        assertThat(h.link.outputs()).hasSize(before);
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(before + 1);
        complete(h.link.outputs().getLast().playbackId());
    }

    @Test
    void routedGoalBriefSurvivesOverlappingInputAndTheActualJevGateBeforeTheSceneStarts() {
        flexible=true;h.activate();h.settleSend();
        h.speak("오늘 연습할 목표는",4800);
        h.provider.last().emit(new kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.InputTranscriptDelta("intro-overlap","응, 알겠어"));
        h.drain();h.advance(Duration.ofMillis(501));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(repository.findSceneRuns(h.sessionId)).isEmpty();
        assertThat(h.link.outputs()).isEmpty();
        h.speak(" 부탁하기야. 짧게 해 보자.",4800);h.advance(Duration.ofMillis(801));
        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        guard.allow();h.drain();assertThat(h.link.outputs()).hasSize(1);
        complete(h.link.outputs().getLast().playbackId());
        playApproved("상상 속 상황을 떠올려 봐. 무중이가 상황 속 상대 역할을 해 볼게.");playApproved(OPPORTUNITY);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(repository.findSceneRuns(h.sessionId)).hasSize(1);
        assertThat(failures).isEmpty();
    }

    @Test
    void stoppingAPlayingGoalBriefKeepsChildStopPriorityOverTheNextIntroduction() {
        flexible=true;h.activate();h.settleSend();h.speak("오늘은 부탁하기를 연습할 거야.",4800);
        h.advance(Duration.ofMillis(801));guard.allow();h.drain();
        UUID id=h.link.outputs().getLast().playbackId();
        for(var status:List.of(PlaybackMark.Status.STARTED,PlaybackMark.Status.STOPPED)) {
            h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                    new PlaybackMark(id,status,null,h.clock.now(),status==PlaybackMark.Status.STOPPED
                            ? PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY:null,null),h.clock.now()));h.drain();
        }
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.childSays("stop-playing-intro","무중아 그만할래");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endReason()).isEqualTo(kr.mujoong.contract.status.EndReason.CHILD_STOP);
        assertThat(repository.findSceneRuns(h.sessionId)).isEmpty();
    }

    @Test
    void flexibleDialogueSeparatesTargetAchievementFromAgreementAndGuardsEveryGeneratedReply() {
        flexible = true; h.activate();
        playGenerated("오늘은 부탁하기를 짧은 역할놀이로 연습해 볼 거야. 원하면 쉬거나 끝내도 돼.");
        playApproved("상상 속 상황을 떠올려 봐. 무중이가 상황 속 상대 역할을 해 볼게.");
        playApproved(OPPORTUNITY);
        h.childSays("flex-child-1", "파란색 상자를 줘");
        assertThat(judge.last().request().point()).isEqualTo(JudgmentPoint.J2);
        judge.confirm("FULL", 0.95, "PARTIAL", 0.03); h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        playGenerated("파란 상자를 줄게. 같이 정리할까?");
        h.childSays("flex-child-2", "이 상자에는 뭐가 들어 있어?");
        assertThat(judge.last().request().point()).isEqualTo(JudgmentPoint.J3);
        assertThat(judge.last().request().criteria().coreFunction()).isEqualTo("ROLE_DIALOGUE_CLOSURE");
        assertThat(judge.last().request().recentTurns()).anySatisfy(turn -> {
            assertThat(turn.speaker()).isEqualTo(kr.mujoong.realtime.judgment.JudgeRequest.Speaker.PARTNER);
            assertThat(turn.text()).isEqualTo("파란 상자를 줄게. 같이 정리할까?");
        });
        assertThat(judge.last().request().recentTurns()).noneSatisfy(turn ->
                assertThat(turn.text()).isEqualTo("이 상자에는 뭐가 들어 있어?"));
        judge.confirm("STILL_SHORT", 0.95, "DELIVERED", 0.03); h.drain();
        playGenerated("여기에는 블록이 들어 있어. 같이 담아 보자.");
        assertThat(repository.findSceneRuns(h.sessionId).getFirst().endedAt()).isNull();
        h.childSays("flex-child-3", "응, 같이 담자");
        judge.confirm("DELIVERED", 0.95, "STILL_SHORT", 0.03); h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        playGenerated("좋아, 같이 담자.");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        playGenerated("오늘은 원하는 상자를 말로 부탁했어. 오늘 연습은 여기까지야.");
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endDetail()).isEqualTo("PRACTICE_COMPLETED");
        assertThat(repository.findSceneRuns(h.sessionId).getFirst().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(repository.findSceneRuns(h.sessionId).getFirst().endedAt()).isNotNull();
        assertThat(failures).isEmpty();
    }

    @Test
    void contextualClarificationIsRecordedDurablyAndShortAnswersUseTheActualPartnerQuestion() {
        flexible=true;h.activate();
        playGenerated("오늘은 부탁하기를 짧게 연습할 거야. 필요하면 도움을 받고 언제든 끝내도 돼.");
        playApproved("상상 속 상황을 떠올려 봐. 무중이가 상황 속 상대 역할을 해 볼게.");playApproved(OPPORTUNITY);
        h.childSays("clarify-1","그거 줘");judge.confirm("PARTIAL",0.95,"FULL",0.03);h.drain();
        playGenerated("어느 색 상자를 말하는 거야?");
        h.childSays("clarify-2","파란색");
        assertThat(judge.last().request().recentTurns()).anySatisfy(turn->{
            assertThat(turn.speaker()).isEqualTo(kr.mujoong.realtime.judgment.JudgeRequest.Speaker.PARTNER);
            assertThat(turn.text()).isEqualTo("어느 색 상자를 말하는 거야?");
        });
        judge.confirm("FULL",0.95,"PARTIAL",0.03);h.drain();playGenerated("응, 여기 있어. 같이 정리할까?");
        h.childSays("clarify-3","응, 같이 정리하자");judge.confirm("DELIVERED",0.95,"STILL_SHORT",0.03);h.drain();playGenerated("좋아, 같이 정리하자.");
        playGenerated("오늘은 친구가 어느 색인지 물었을 때 파란색이라고 알려줬어. 오늘 연습은 여기까지야.");
        var row=repository.findSceneRuns(h.sessionId).getFirst();
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(row.facts()).extracting(PracticeFlowTypes.Fact::kind).contains(PracticeFlowTypes.FactKind.REASK_RESOLVED)
                .doesNotContain(PracticeFlowTypes.FactKind.FOCUS_HELP);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(failures).isEmpty();
    }

    private void complete(UUID playbackId) {
        for (PlaybackMark.Status status : List.of(PlaybackMark.Status.STARTED, PlaybackMark.Status.COMPLETED)) {
            h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                    new PlaybackMark(playbackId, status, null, h.clock.now(), null, null), h.clock.now()));
            h.drain();
        }
    }
}
