package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.UNREADABLE_GOAL_NAME;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_ASK;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_GOAL_BRIEF;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_HELP;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_INTRO;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_OPPORTUNITY;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_REACTION;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_REASK;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_REEXPLAIN;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_VARIANT_ASK;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_VARIANT_INTRO;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_VARIANT_REACTION;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_VARIANT_RELAY;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScriptWithVariant;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Slot;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.GuardrailBlockedEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.testing.InternalApi;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 새 연습 상태 기계 시험 — 운영 기본 경로인 C안 '무중이 직접 묻기'(docs/practice-direct-ask.md, U12 FR2 · FR3 · FR5 · BR1 ~ BR6). 기록하는 가짜
 * 출력 · 가짜 판정 결과 사건 · 수동 시계 · 메모리 기록기 위에서 확장점 시점(onActive · onChildUtterance · onExtensionEvent)만으로 장면을 끝까지
 * 움직인다(테스트 대역 · 합성 대화 증거이고 실제 AI · 실제 기기가 아니다). 대본은 C안 판 v2이고 고정 문장은 시작 패키지 approvedSpeech로 온다.
 * 표 행 자체는 {@code FlowTransitionsTest} · {@code DirectAskTransitionsTest}가 보고 여기서는 시계 경계 · 출력 능력 호출 · 중복 사건 · 실패 이어
 * 가기 · 지시문 위생을 본다. 장면 안 말은 모두 승인 낭독이다. 장면 밖 말 가운데 목표 안내도 서버가 정한 승인 문장의 낭독이고(목표 이름을 승인 문장으로
 * 다듬을 수 없을 때만 생성형, 편차 FT-1024-g), 목표 회고는 생성형이다.
 */
@Tag("practice-flow")
class PracticeConversationTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID GOAL_TWO = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID SCENARIO_TWO = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final String CRITERIA = "원하는 것을 말로 요청한다";
    private static final Map<String, String> APPROVED = FIXED_SPEECH;
    private static final String SCENE_CLOSE = FIXED_SPEECH.get("SCENE_CLOSE");
    private static final String INPUT_CONFIRM = FIXED_SPEECH.get("INPUT_CONFIRM");
    private static final String HELP_OFFER = FIXED_SPEECH.get("HELP_OFFER");
    private static final String VARIANT_OFFER = FIXED_SPEECH.get("VARIANT_OFFER");
    private static final String RETRY_PROMPT = FIXED_SPEECH.get("RETRY_PROMPT");
    private static final String WAIT_LEAD = FIXED_SPEECH.get("WAIT_LEAD");

    private static Harness direct() {
        return new Harness(packageJson(GOAL, directAskScript(SCENARIO)), APPROVED);
    }

    private static Harness withVariant() {
        return new Harness(packageJson(GOAL, directAskScriptWithVariant(SCENARIO)), APPROVED);
    }

    /** 목표 이름을 승인 문장으로 다듬을 수 없어(숫자) C안도 생성형 목표 안내를 내는 패키지(편차 FT-1024-g). 생성형 슬롯의 처리를 보는 시험이 쓴다. */
    private static Harness generatedBrief() {
        return new Harness(packageJson(GOAL, directAskScript(SCENARIO), UNREADABLE_GOAL_NAME), APPROVED);
    }

    private static String twoGoalPackage() {
        return "{\"goals\":[{\"goalSelectionId\":\"" + GOAL + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"judgmentCriteria\":\"" + CRITERIA + "\",\"firstScenario\":" + directAskScript(SCENARIO) + "},"
                + "{\"goalSelectionId\":\"" + GOAL_TWO + "\",\"order\":2,\"displayName\":\"거절하기\","
                + "\"judgmentCriteria\":\"싫다는 뜻을 말로 전한다\"}]}";
    }

    private static PracticeFlowSettings quiet(Duration focusMax, Duration goalLimit) {
        Duration hour = Duration.ofHours(1);
        return new PracticeFlowSettings(hour, focusMax, goalLimit, hour, 1, 1, false);
    }

    private static void assertRecordMatchesTheContract(Object practice) {
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(practice))).isEmpty();
    }

    @Test
    void answerDuringReexplanationIsJudgedOnceAfterPlaybackWithoutRepeatingTheOpportunity() {
        Harness h=direct();h.toOpportunity();h.child("상황을 잘 모르겠어");h.result("J2","NOT_UNDERSTOOD");
        Slot explanation=h.out.lastSlot();int slots=h.out.slots.size();
        assertThat(explanation.text()).as("C안: 다시 설명 뒤 묻는 말을 같은 슬롯으로 다시 읽는다").isEqualTo(V2_REEXPLAIN + " " + V2_ASK);
        assertThat(explanation.generated()).isFalse();
        h.child("손 괜찮아?");
        assertThat(h.out.judgments).hasSize(1);
        h.played();
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.out.judgments).hasSize(2);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.lastJudgment().seq()).isEqualTo(2);
        assertThat(h.out.timers).doesNotContainKey("no-response");
        h.slotEvent(explanation.correlationId(),Playback.COMPLETED,explanation.speech(),explanation.text());
        assertThat(h.out.judgments).hasSize(2);
        assertThat(h.result("J2","FULL")).isEqualTo(Signal.NONE);
        assertThat(h.playedThroughRecap()).isEqualTo(Signal.LAST_GOAL);
    }

    @Test
    void reexplanationWithoutAnEarlyAnswerListensInsteadOfReadingTheSameOpportunityAgain() {
        Harness h=direct();h.toOpportunity();h.child("무슨 뜻이야");h.result("J2","NOT_UNDERSTOOD");
        int slots=h.out.slots.size();h.played();
        assertThat(h.out.slots).as("묻는 말은 다시 설명과 같은 슬롯에서 이미 읽었다").hasSize(slots);
        assertThat(h.out.timers).containsKey("no-response");
        h.child("손 괜찮아?");
        assertThat(h.out.judgments).hasSize(2);
    }

    @Test
    void failedReexplanationIsSkippedAndTheBufferedAnswerIsJudgedWithoutEndingThePractice() {
        // FT-1023-g: 못 들려준 다시 설명(+ 묻기)은 건너뛰고, 아이가 실제로 한 말은 그대로 판정한다
        Harness h=direct();h.toOpportunity();h.child("모르겠어");h.result("J2","NOT_UNDERSTOOD");
        h.child("손 괜찮아?");
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.NONE);
        assertThat(h.out.judgments).hasSize(2);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void anIncorrectFullClassificationOfAProcedureQuestionOffersHelpInsteadOfCompleting() {
        Harness h=direct();h.toOpportunity();h.child("무슨 놀이를 하고 싶은지 물어봐야 하는 거야");
        assertThat(h.result("J2","FULL")).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.FOCUS);
        assertThat(h.out.announces).as("C안은 역할 전환 알림이 없다").isEmpty();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_HELP);
        assertThat(h.out.lastSlot().generated()).isFalse();
        assertThat(h.recorder.only().liveTiming()).isNull();
    }

    @Test
    void aQuestionThatIsItselfTheGoalFunctionCanStillCompleteTheGoal() {
        Harness h=direct();h.toOpportunity();h.child("손 괜찮아?");
        assertThat(h.result("J2","FULL")).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REACTION);
        assertThat(h.playedThroughRecap()).isEqualTo(Signal.LAST_GOAL);
    }

    @Test
    void anAnswerDuringRetryReexplanationReturnsToJ5WithoutAnotherHelpOrRetryIncrementWhenTwoRetriesAreConfigured() {
        // 다시 해 보기 2회 설정(C안 기본은 1회)에서만 RETRY 다시 설명이 열린다
        Harness h=new Harness(packageJson(GOAL, directAskScript(SCENARIO)), APPROVED, new PracticeFlowSettings(Duration.ofSeconds(10),
                Duration.ofSeconds(90), Duration.ofMinutes(10), Duration.ofSeconds(20), 2, 1, false));
        h.toOpportunity();h.child("도와줘");h.result("J2","HELP");
        h.played();h.child("상황을 모르겠어");h.result("J5","NOT_UNDERSTOOD");
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REEXPLAIN + " " + V2_ASK);
        int slots=h.out.slots.size(), retries=h.ext.state().retryCount();
        h.child("손 괜찮아? 다음에 그리자");h.played();
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        assertThat(h.out.lastJudgment().seq()).isEqualTo(3);
        assertThat(h.ext.state().retryCount()).isEqualTo(retries);
    }

    @Test
    void aNotUnderstoodRetryAnswerClosesTheSceneBecauseThereIsOnlyOneRetry() {
        Harness h=direct();h.toOpportunity();h.child("도와줘");h.result("J2","HELP");
        h.played();h.child("상황을 모르겠어");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        h.result("J5","NOT_UNDERSTOOD");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.SCENE_CLOSE);
        assertThat(h.out.lastSlot().text()).isEqualTo(SCENE_CLOSE);
        assertThat(h.ext.state().reexplainCount()).as("다시 해 보기가 남지 않아 다시 설명하지 않는다").isZero();
        assertThat(h.playedThroughRecap()).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row=h.recorder.only();
        assertThat(row.retryCount()).isEqualTo(1);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.NONE);
    }

    @Test
    void aProcedureQuestionCannotCompleteTheReaskOrTheRetryEither() {
        Harness reask=direct();reask.toOpportunity();reask.child("그럼 그만해");reask.result("J2","PARTIAL");reask.played();
        reask.child("뭐라고 대답해야 해?");
        assertThat(reask.result("J3","DELIVERED")).isEqualTo(Signal.NONE);
        assertThat(reask.ext.state().phase()).as("방법만 묻는 말은 도움으로 간다").isEqualTo(FlowState.Phase.FOCUS);
        Harness retry=direct();retry.toOpportunity();retry.child("도와줘");retry.result("J2","HELP");
        retry.played();retry.child("이렇게 말하면 되는 거야?");
        assertThat(retry.result("J5","COPIED")).isEqualTo(Signal.NONE);
        assertThat(retry.ext.state().phase()).isNotEqualTo(FlowState.Phase.CLOSING);
        assertThat(retry.recorder.only().liveTiming()).as("방법을 묻는 말은 수행이 아니다").isEqualTo(LiveTiming.NONE);
    }

    @Test
    void unheardSceneIntroductionIsSkippedToTheOpportunityWithAPlaybackFailureFact() {
        // FT-1023-g: 도입을 못 들려줘도 연습을 닫지 않는다. 장면 행을 만들고 사실을 남긴 뒤 전하기 + 묻기로 간다
        Harness h = direct();
        h.toIntroduction();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_INTRO);
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.NONE);
        assertThat(h.recorder.rows).hasSize(1);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED);
        assertThat(h.out.slots).as("목표 안내 + 도입 + 전하기 · 묻기").hasSize(3);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        assertThat(h.out.timers).doesNotContainKey("no-response");
    }

    @Test
    void aBlockedGoalBriefOrIntroductionIsNeverReplayedButThePracticeContinues() {
        // FT-1023-g: 막힌 목표 안내는 다시 내지 않고 도입으로, 막힌 도입은 다시 내지 않고 전하기 + 묻기로 간다
        // C안 목표 안내는 승인 낭독이다(FT-1024-g): 출력 게이트 1차 검사에 걸리면 실행부가 그 슬롯을 재생 실패로 닫는다
        Harness brief = direct();
        brief.activate();
        Slot notice = brief.out.lastSlot();
        assertThat(notice.generated()).as("C안 목표 안내는 승인 낭독").isFalse();
        assertThat(notice.text()).isEqualTo(V2_GOAL_BRIEF);
        assertThat(brief.end(Playback.FAILED)).isEqualTo(Signal.NONE);
        assertThat(brief.out.slots).as("목표 안내 + 도입").hasSize(2);
        assertThat(brief.out.slots).filteredOn(slot -> V2_GOAL_BRIEF.equals(slot.text())).as("막힌 목표 안내는 다시 내지 않는다")
                .hasSize(1);
        assertThat(brief.out.lastSlot().text()).isEqualTo(V2_INTRO);
        assertThat(brief.recorder.rows).as("장면 행은 도입이 끝날 때 만든다").isEmpty();
        // 승인 문장으로 다듬을 수 없는 목표 이름이면 생성형 목표 안내(제브를 거친다)다. 제브가 막아도 다시 내지 않는다
        Harness generated = generatedBrief();
        generated.activate();
        Slot generatedNotice = generated.out.lastSlot();
        assertThat(generatedNotice.generated()).as("생성형 목표 안내는 제브를 거친다").isTrue();
        assertThat(generated.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", generatedNotice.correlationId(),
                new GuardrailBlockedEvent(generatedNotice.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_PEER,
                        "BLOCK", true))).isEqualTo(Signal.NONE);
        assertThat(generated.out.slots).as("목표 안내 + 도입").hasSize(2);
        assertThat(generated.out.generatedSlots()).as("막힌 목표 안내는 다시 내지 않는다").hasSize(1);
        assertThat(generated.out.lastSlot().text()).isEqualTo(V2_INTRO);
        assertThat(generated.recorder.rows).as("장면 행은 도입이 끝날 때 만든다").isEmpty();
        Harness h = direct();
        h.toIntroduction();
        Slot intro = h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", intro.correlationId(),
                new GuardrailBlockedEvent(intro.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_PEER, "BLOCK", true))).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("목표 안내 + 도입 + 전하기 · 묻기").hasSize(3);
        assertThat(h.out.slots).filteredOn(slot -> V2_INTRO.equals(slot.text())).as("막힌 도입은 다시 내지 않는다").hasSize(1);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        assertThat(h.recorder.factKinds(h.recorder.only())).as("막힘은 재생 실패 사실이 아니다").isEmpty();
    }

    @Test
    void failedReexplanationIsSavedAsPlaybackFailureAndTheSceneKeepsListening() {
        Harness h = direct();
        h.toOpportunity();
        h.child("무슨 말인지 모르겠어");
        h.result("J2", "NOT_UNDERSTOOD");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.REEXPLAIN);
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.NONE);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PLAYBACK_FAILED);
        assertThat(h.recorder.only().endedAt()).as("장면은 닫히지 않는다").isNull();
        assertThat(h.out.timers).containsKey("no-response");
    }

    @Test
    void mismatchThenEmptyAudioRecoversWithFreshNarrationAndContinuesOnlyAfterPlayback() {
        Harness h=direct();h.toIntroduction();Slot first=h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech());
        Slot retry=h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"practice-audio-incomplete",retry.correlationId(),retry.speech())).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.recorder.rows).isEmpty();
        h.played();
        assertThat(h.recorder.rows).hasSize(1);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.PLAYBACK_FAILED);
    }

    /**
     * FT-1023-e: 재생이 시작된(STARTED) 생성형 발화(승인 문장으로 다듬을 수 없는 목표 이름의 생성형 목표 안내, FT-1024-g)의 꼬리가 비었다는 알림은
     * 재요청도 실패도 아니라 재생됨으로 닫는다.
     */
    @Test
    void anIncompleteTailAfterPlaybackStartedClosesTheGeneratedSlotAsPlayedWithoutARetry() {
        Harness h=generatedBrief();h.activate();
        Slot first=h.out.lastSlot();
        assertThat(first.generated()).isTrue();
        var speech=kr.mujoong.realtime.voice.PracticeSpeech.generatedSlot(first.correlationId(), SCENARIO, 1, first.role(), first.text());
        h.slotEvent(first.correlationId(), Playback.STARTED, speech, first.text());
        int count=h.out.slots.size();
        Signal signal=h.event(ExtensionEvent.Kind.ASYNC_RESULT,"practice-audio-incomplete",first.correlationId(),speech);
        assertThat(signal).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("같은 지시를 다시 요청하지 않고 재생됨으로 닫아 도입으로 간다").hasSize(count + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_INTRO);
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"practice-audio-incomplete",first.correlationId(),speech))
                .as("한 번 닫힌 슬롯의 늦은 알림은 무시한다").isEqualTo(Signal.NONE);
        assertThat(h.out.slots).hasSize(count + 1);
        assertThat(h.recorder.rows.values().stream().flatMap(row -> h.recorder.factKinds(row).stream())).doesNotContain(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void generatedTechnicalRecoveryRetriesTrustedInstructionsOnceAndIgnoresStaleFailures() {
        // 생성형 슬롯은 승인 문장으로 다듬을 수 없는 목표 이름의 생성형 목표 안내다(FT-1024-g)
        Harness h=generatedBrief();h.activate();
        Slot first=h.out.lastSlot();
        var speech=kr.mujoong.realtime.voice.PracticeSpeech.generatedSlot(first.correlationId(), SCENARIO, 1, first.role(), first.text());
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"practice-audio-incomplete",first.correlationId(),speech)).isEqualTo(Signal.NONE);
        Slot retry=h.out.lastSlot();
        assertThat(retry.text()).isEqualTo(first.text());
        int count=h.out.slots.size();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"practice-audio-incomplete",first.correlationId(),speech);
        assertThat(h.out.slots).hasSize(count);
        var retrySpeech=kr.mujoong.realtime.voice.PracticeSpeech.generatedSlot(retry.correlationId(), SCENARIO, 2, retry.role(), retry.text());
        // 재요청 뒤에도 실패: 같은 지시를 세 번째로 내지 않고 이 단계의 다음(생성형으로 대신한 목표 안내 → 장면 도입)으로 간다(FT-1023-g)
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"practice-audio-incomplete",retry.correlationId(),retrySpeech)).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("목표 안내는 다시 내지 않고 도입 승인 낭독 하나만 연다").hasSize(count + 1);
        assertThat(h.out.generatedSlots()).hasSize(2);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_INTRO);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.SCENE_INTRO);
        assertThat(h.recorder.rows).as("장면 행은 도입이 끝날 때 만든다").isEmpty();
    }

    @Test
    void aTextMismatchRetriesOnlyTheSameApprovedBodyOnceAndRejectsLateMismatchEvents() {
        Harness h = direct();h.toIntroduction();Slot first=h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech())).isEqualTo(Signal.NONE);
        Slot retry=h.out.lastSlot();
        assertThat(retry.correlationId()).isNotEqualTo(first.correlationId());
        assertThat(retry.text()).isEqualTo(first.text());
        assertThat(retry.generated()).isFalse();
        int slots=h.out.slots.size();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech());
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",retry.correlationId(),retry.speech())).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).hasSize(slots + 1);
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.out.lastSlot().text()).contains("승인 자료=");
        assertThat(h.recorder.rows).isEmpty();
        assertThat(h.played()).isEqualTo(Signal.NONE);
        assertThat(h.recorder.rows).hasSize(1);
    }

    @Test
    void recoveryNarrationDoesNotLoopOnFailureOrAdvanceWithoutPlayback() {
        Harness h=direct();h.toIntroduction();Slot first=h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech());
        Slot retry=h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",retry.correlationId(),retry.speech());
        assertThat(h.out.lastSlot().generated()).isTrue();
        int count=h.out.slots.size();
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("복구 낭독은 되풀이하지 않고 전하기 + 묻기로 간다").hasSize(count + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void anExactRetryMayPlayAndContinueWithoutMarkingThePracticeAsFailed() {
        Harness h=direct();h.toIntroduction();Slot first=h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech());
        h.played();h.played();h.child("다음에 같이 그리자고 할래");
        assertThat(h.result("J2","FULL")).isEqualTo(Signal.NONE);
        assertThat(h.playedThroughRecap()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void aRetryWithoutOutputStillTimesOutAndDoesNotRetryForever() {
        Harness h=direct();h.toIntroduction();Slot first=h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech());
        int slots=h.out.slots.size();
        assertThat(h.advance(Duration.ofSeconds(20))).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("시한이 지나도 도입을 다시 내지 않고 전하기 + 묻기 하나만 연다").hasSize(slots + 1);
        assertThat(h.out.slots).filteredOn(slot -> V2_INTRO.equals(slot.text())).as("도입 재요청은 한 번뿐이다(처음 + 재요청)").hasSize(2);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void generatedSpeechCannotUseTheApprovedReadingRetryPath() {
        // 생성형 슬롯은 승인 문장으로 다듬을 수 없는 목표 이름의 생성형 목표 안내다(FT-1024-g)
        Harness h=generatedBrief();h.activate();
        Slot generated=h.out.lastSlot();int count=h.out.slots.size();
        assertThat(generated.generated()).isTrue();
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",generated.correlationId(),generated.speech())).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).hasSize(count);
    }

    // ---- 2장 3B → 3B′: 되묻기로 해결 ----

    @Test
    void aReaskResolvesTheSceneAfterTheReaskWithNoHelpAndTheRecordFollowsTheContract() {
        Harness h = direct();

        assertThat(h.activate()).isEqualTo(Signal.NONE);
        Slot brief = h.out.lastSlot();
        assertThat(brief.generated()).as("C안 목표 안내는 서버가 정한 문장의 승인 낭독(FT-1024-g)").isFalse();
        assertThat(brief.text()).isEqualTo(V2_GOAL_BRIEF).contains("짧게 이야기 연습을").doesNotContain("역할놀이", "‘", "’");
        assertThat(brief.speech().partnerRole()).isEqualTo(RolePolicy.MUJOONG_LABEL);
        h.played();
        assertThat(h.out.lastSlot().text()).as("도입은 대본 상황 안내 승인 낭독").isEqualTo(V2_INTRO);
        assertThat(h.out.lastSlot().generated()).isFalse();
        h.played();
        assertThat(h.out.lastSlot().text()).as("친구 말 전하기 + 묻기 한 슬롯").isEqualTo(V2_OPPORTUNITY);
        assertThat(h.out.timers).as("묻는 말 재생이 끝나기 전에는 무응답 시계가 없다").doesNotContainKey("no-response");
        h.played();
        assertThat(h.out.timers).containsKey("no-response");

        h.child("그럼 그만해");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.lastJudgment().criteria()).containsEntry("coreFunction", CRITERIA).containsEntry("judgmentCriteria", CRITERIA);
        assertThat(h.out.timers).as("판정 중에는 무응답 시계를 걸지 않는다").doesNotContainKey("no-response");
        h.result("J2", "PARTIAL");
        Slot reask = h.out.lastSlot();
        assertThat(reask.text()).isEqualTo(V2_REASK);
        assertThat(reask.generated()).isFalse();
        h.played();

        h.child("손 괜찮아? 오늘은 쉬고 다음에 그리자");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J3);
        assertThat(h.out.lastJudgment().criteria()).isNotNull();
        assertThat(h.result("J3", "DELIVERED")).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REACTION);
        assertThat(h.played()).as("친구 반응 뒤 목표 회고").isEqualTo(Signal.NONE);
        Slot recap = h.out.lastSlot();
        assertThat(recap.generated()).isTrue();
        assertThat(recap.text()).contains("무중이 다시 물었을 때").doesNotContain("상대가 다시 물었을 때");
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.sceneKind()).isEqualTo(SceneKind.BASE);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.REASK_RESOLVED, FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
        assertThat(row.endedAt()).isNotNull();
        assertThat(h.recorder.helps).as("도움 0건").isEmpty();
        assertThat(h.out.generatedSlots()).as("생성형은 장면 밖 목표 회고뿐(목표 안내는 승인 낭독)").containsExactly(recap);
        assertThat(h.failures).isEmpty();
        assertRecordMatchesTheContract(h.ext.practiceRecord());
    }

    // ---- 시계 경계 (수동 시계) ----

    @Test
    void theNoResponseClockReguidesExactlyAtTenSecondsAndNotAtNineAndTheSecondExpiryClosesTheScene() {
        Harness h = direct();
        h.toOpportunity();
        assertThat(h.out.scheduleLog).contains("no-response:PT10S");
        int before = h.out.slots.size();

        h.advance(Duration.ofSeconds(9));
        assertThat(h.out.slots).as("9초에는 재안내하지 않는다").hasSize(before);

        h.advance(Duration.ofSeconds(1));
        assertThat(h.out.slots).as("10초에 기다리기 + 묻기를 한 번 읽는다").hasSize(before + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(WAIT_LEAD + " " + V2_ASK);
        assertThat(h.out.lastSlot().generated()).isFalse();
        h.played();

        assertThat(h.advance(Duration.ofSeconds(10))).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).as("둘째 만료는 고정 마무리 문장으로 장면을 닫는다").isEqualTo(SCENE_CLOSE);
        Signal end = h.playedThroughRecap();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.TIMEOUT, FactKind.VARIANT_SKIPPED);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.NONE);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.END_OR_REST);
    }

    @Test
    void theFocusClockFiresAtNinetySecondsNotAtEightyNineAndClosesTheSceneWithTheClosingLine() {
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO)), APPROVED, quiet(Duration.ofSeconds(90), Duration.ofHours(2)));
        h.toOpportunity();
        h.child("어떻게 말해?");
        h.result("J2", "HELP");
        assertThat(h.out.announces).as("역할 전환 알림 없이 곧바로 도움").isEmpty();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_HELP);
        assertThat(h.out.scheduleLog).contains("focus-max:PT1M30S");
        h.played();
        int slots = h.out.slots.size();

        h.advance(Duration.ofSeconds(89));
        assertThat(h.out.slots).as("89초에는 정리하지 않는다").hasSize(slots);

        h.advance(Duration.ofSeconds(1));
        assertThat(h.out.lastSlot().text()).as("90초에 고정 마무리 문장으로 장면을 닫는다").isEqualTo(SCENE_CLOSE);
        Signal end = h.playedThroughRecap();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.HELP_ACCEPTED, FactKind.FOCUS_HELP,
                FactKind.EXPRESSION_EXAMPLE, FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.NONE);
        assertThat(row.retryCount()).isEqualTo(1);
        assertThat(h.recorder.helps).extracting(PracticeFlowTypes.HelpRecord::kind).containsExactly(HelpKind.FOCUS_HELP);
        assertThat(h.recorder.helps.getFirst().consentAnswer()).as("먼저 청한 도움도 동의로 남긴다")
                .isEqualTo(PracticeFlowTypes.ConsentAnswer.ACCEPT);
        assertThat(h.recorder.helps.getFirst().exampleGiven()).as("첫 도움부터 표현 예시").isTrue();
        assertThat(h.failures).isEmpty();
    }

    @Test
    void theGoalClockFiresAtTenMinutesNotOneSecondBeforeAndClosesTheGoalWithTimeLimit() {
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO)), APPROVED, quiet(Duration.ofHours(1), Duration.ofMinutes(10)));
        h.toOpportunity();
        assertThat(h.out.scheduleLog).contains("goal-limit:PT10M");

        assertThat(h.advance(Duration.ofMinutes(10).minusSeconds(1))).isEqualTo(Signal.NONE);
        assertThat(h.advance(Duration.ofSeconds(1))).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).isEqualTo(SCENE_CLOSE);
        Signal end = h.playedThroughRecap();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.TIME_LIMIT);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.VARIANT_SKIPPED);
    }

    @Test
    void aSlotThatNeverReportsAnEndIsTreatedAsFailedWhenItsSafetyClockExpires() {
        Harness h = direct();
        h.toIntroduction();
        Slot intro = h.out.lastSlot();
        assertThat(h.out.scheduleLog).anyMatch(entry -> entry.startsWith("slot-guard-") && entry.endsWith(":PT20S"));

        assertThat(h.advance(Duration.ofSeconds(19))).isEqualTo(Signal.NONE);
        Signal end = h.advance(Duration.ofSeconds(1));

        // FT-1023-g: 도입을 끝내 못 들려줘도 연습을 닫지 않고 사실을 남긴 뒤 전하기 + 묻기로 간다
        assertThat(end).as("도입을 듣지 못한 연습은 완료도 종료도 아니다").isEqualTo(Signal.NONE);
        assertThat(h.recorder.factKinds(h.recorder.only())).as("못 들려준 도입은 사실로 남는다").containsExactly(FactKind.PLAYBACK_FAILED);
        assertThat(h.out.cancelledSpeech).containsExactly(intro.correlationId());
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
    }

    @Test
    void generationTimeDoesNotConsumeTheDevicePlaybackGuard() {
        Harness h = direct();
        h.toIntroduction();
        UUID intro = h.out.lastSlot().correlationId();
        h.advance(Duration.ofSeconds(12));
        h.end(Playback.STARTED);
        assertThat(h.advance(Duration.ofSeconds(8))).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "slot-guard-" + intro, null, null)).isEqualTo(Signal.NONE);
        assertThat(h.advance(Duration.ofSeconds(6))).isEqualTo(Signal.NONE);
        assertThat(h.out.cancelledSpeech).isEmpty();
        h.played();
        assertThat(h.recorder.list()).hasSize(1);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
    }

    @Test
    void duplicatePlaybackStartsCannotKeepAnUnfinishedIntroAlive() {
        Harness h = direct();
        h.toIntroduction();
        h.advance(Duration.ofSeconds(12));
        h.end(Playback.STARTED);
        h.advance(Duration.ofSeconds(10));
        h.end(Playback.STARTED);
        assertThat(h.advance(Duration.ofSeconds(10))).isEqualTo(Signal.NONE);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED);
        assertThat(h.out.cancelledSpeech).hasSize(1);
    }

    @Test
    void anUnknownPlaybackStartDoesNotExtendTheGenerationGuard() {
        Harness h = direct();
        h.toIntroduction();
        h.advance(Duration.ofSeconds(12));
        h.slotEvent(UUID.randomUUID(), Playback.STARTED, null, "unrelated");
        assertThat(h.advance(Duration.ofSeconds(8))).isEqualTo(Signal.NONE);
        assertThat(h.recorder.factKinds(h.recorder.only())).as("도입 안전 시계가 늘지 않고 만료됐다").containsExactly(FactKind.PLAYBACK_FAILED);
    }

    // ---- 다시 해 보기 한도 · 도움 예시 (FT-1024-d) ----

    @Test
    void theOnlyRetryFollowsAFirstHelpWithAnExampleAndAStillShortAnswerClosesTheScene() {
        Harness h = withVariant();
        h.toOpportunity();
        h.child("어떻게 말해?");
        h.result("J2", "HELP");
        assertThat(h.out.lastSlot().text()).as("첫 도움부터 대본의 표현 예시가 있다").isEqualTo(V2_HELP).contains("예를 들면");
        h.played();
        h.child("음");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        h.result("J5", "PARTIAL");

        assertThat(h.out.lastSlot().text()).as("다시 해 보기가 한 번뿐이라 도움을 다시 하지 않고 닫는다").isEqualTo(SCENE_CLOSE);
        assertThat(h.out.slots.stream().filter(slot -> slot.text().equals(V2_HELP))).as("도움은 장면마다 한 번").hasSize(1);
        assertThat(h.playedThroughRecap()).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.retryCount()).isEqualTo(1);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(h.recorder.factKinds(row)).contains(FactKind.HELP_ACCEPTED, FactKind.FOCUS_HELP, FactKind.EXPRESSION_EXAMPLE);
        assertThat(h.recorder.helps).singleElement().satisfies(help -> assertThat(help.exampleGiven()).isTrue());
    }

    @Test
    void aCopiedRetryAnswerCountsAsAfterHelpAndEndsWithTheFriendReactionWithoutRoleFacts() {
        Harness h = direct();
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        h.played();
        h.child("손 괜찮아, 그럼 다음에 같이 그리자");
        h.result("J5", "COPIED");
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REACTION);
        Signal end = h.playedThroughRecap();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.HELP_ACCEPTED, FactKind.FOCUS_HELP,
                FactKind.EXPRESSION_EXAMPLE, FactKind.VARIANT_SKIPPED);
        assertThat(h.recorder.factKinds(row)).doesNotContain(FactKind.COACH_SWITCH, FactKind.COACH_RETURN, FactKind.ORIGINAL_SCENE_RETURN);
    }

    // ---- 판정 거절 · 보류 · 이어 가기 ----

    @Test
    void whenRequestJudgmentReturnsFalseThePracticeDoesNotStopItConfirmsOnceAndThenOffersHelp() {
        Harness h = direct();
        h.out.judgmentResult = false;
        h.toOpportunity();
        int slots = h.out.slots.size();

        h.child("음");
        assertThat(h.out.judgments).hasSize(1);
        assertThat(h.out.slots).as("교정 없이 고정 확인 문장을 한 번 읽는다").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(INPUT_CONFIRM);
        h.played();

        // FT-1023-g: 두 번째에도 장면을 닫지 않고 같은 안내를 되풀이하지도 않는다. 도움 제안(C안은 고정 문장)으로 간다
        assertThat(h.child("음")).isEqualTo(Signal.NONE);
        assertThat(h.out.judgments).hasSize(2);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.HELP_OFFER);
        assertThat(h.out.lastSlot().text()).isEqualTo(HELP_OFFER);
        assertThat(h.out.lastSlot().generated()).as("장면 안 말은 승인 낭독").isFalse();
        SceneRunRecord row = h.recorder.only();
        assertThat(row.endedAt()).isNull();
        assertThat(h.recorder.factKinds(row)).isEmpty();
    }

    @Test
    void anAbstainedJudgmentEventTakesTheSameConfirmPathAndTheSecondOneOffersHelp() {
        Harness h = direct();
        h.toOpportunity();
        h.child("음");
        int slots = h.out.slots.size();

        h.abstained("J2");
        assertThat(h.out.slots).hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(INPUT_CONFIRM);
        h.played();
        h.child("음");
        assertThat(h.abstained("J2")).isEqualTo(Signal.NONE);

        assertThat(h.ext.state().phase()).as("두 번째 보류는 장면을 닫지 않고 도움 제안으로 간다(FT-1023-g)").isEqualTo(FlowState.Phase.HELP_OFFER);
        assertThat(h.out.lastSlot().text()).isEqualTo(HELP_OFFER);
        assertThat(h.recorder.only().endedAt()).isNull();
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aLateOrForeignJudgmentResultIsIgnored() {
        Harness h = direct();
        h.toOpportunity();
        h.child("다음에 같이 그리자고 할래");
        int slots = h.out.slots.size();

        h.result(UUID.randomUUID(), "J2", "FULL");

        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.recorder.only().endedAt()).isNull();
        h.result("J2", "FULL");
        assertThat(h.recorder.only().endedAt()).isNull();
        assertThat(h.result(UUID.randomUUID(), "J2", "PARTIAL")).as("닫힌 판정 뒤 사건").isEqualTo(Signal.NONE);
        h.played();
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
    }

    // ---- 지시문 위생 ----

    @Test
    void inSceneSpeechIsAllApprovedReadingAndTheGeneratedInstructionsOutsideTheSceneCarryNoLabelsCriteriaOrChildWords() {
        Harness h = withVariant();
        String marker = "표식문장매우특이함";
        h.toOpportunity();
        h.child(marker + "1");
        h.result("J2", "PARTIAL");
        h.played();
        h.child(marker + "2");
        h.result("J3", "STILL_SHORT");
        h.played();
        h.child(marker + "3");
        h.result("J4", "ACCEPT");
        h.played();
        h.advance(Duration.ofSeconds(10));
        h.played();
        h.child(marker + "4");
        h.result("J5", "FULL");
        h.played();
        h.played();
        h.child(marker + "5");
        h.result("J4", "UNCLEAR");
        h.played();
        h.child(marker + "6");
        assertThat(h.result("J4", "REFUSE")).isEqualTo(Signal.NONE);
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);

        assertThat(h.out.announces).as("C안은 역할 전환 알림 능력을 부르지 않는다").isEmpty();
        List<Slot> generated = h.out.generatedSlots();
        assertThat(generated).as("생성형은 장면 밖 목표 회고뿐(목표 안내는 승인 낭독, FT-1024-g)").hasSize(1);
        assertThat(generated.getLast()).isEqualTo(h.out.lastSlot());
        assertThat(h.out.slots.subList(0, h.out.slots.size() - 1)).extracting(Slot::text).containsExactly(V2_GOAL_BRIEF, V2_INTRO,
                V2_OPPORTUNITY, V2_REASK, HELP_OFFER, V2_HELP, RETRY_PROMPT, V2_REACTION, VARIANT_OFFER, VARIANT_OFFER);
        assertThat(h.out.slots.subList(0, h.out.slots.size() - 1)).allSatisfy(slot -> {
            assertThat(slot.generated()).isFalse();
            assertThat(slot.speech().partnerRole()).as("장면 속 역할 없이 무중이 말한다").isEqualTo(RolePolicy.MUJOONG_LABEL);
        });
        List<String> forbidden = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE", "DELIVERED",
                "STILL_SHORT", "ACCEPT", "REFUSE", "UNCLEAR", "COPIED", "%", "확률", CRITERIA, marker, "판정", "J2", "J3", "J4", "J5");
        // 장면 밖 말 둘(승인 낭독 목표 안내 · 생성형 목표 회고)에 판정 라벨 · 기준 · 아이 말이 없다
        for (Slot slot : List.of(h.out.slots.getFirst(), generated.getLast())) {
            assertThat(slot.text()).as(slot.role()).isNotBlank().doesNotContain(forbidden.toArray(new String[0]));
            assertThat(slot.text().codePointCount(0, slot.text().length())).isLessThanOrEqualTo(500);
            assertThat(slot.text().chars().noneMatch(Character::isISOControl)).isTrue();
        }
        assertThat(h.out.slots).extracting(Slot::text).noneMatch(text -> text.contains("역할") || text.contains("코치"));
        assertThat(h.recorder.rows.toString() + h.recorder.helps + h.ext).doesNotContain(marker);
        assertThat(JsonCodec.shared().write(h.ext.practiceRecord())).doesNotContain(marker);
    }

    // ---- 중복 · 늦은 슬롯 사건 ----

    @Test
    void aSecondEndEventForTheSameSlotIsIgnored() {
        Harness h = direct();
        h.toIntroduction();
        Slot intro = h.out.lastSlot();

        h.slotEvent(intro.correlationId(), Playback.COMPLETED, intro.speech(), intro.text());
        int slots = h.out.slots.size();
        Signal again = h.slotEvent(intro.correlationId(), Playback.COMPLETED, intro.speech(), intro.text());
        h.slotEvent(intro.correlationId(), Playback.FAILED, intro.speech(), intro.text());

        assertThat(again).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("도입 종료 사건이 두 번 와도 묻는 말은 한 번만").hasSize(slots);
        assertThat(h.recorder.list()).hasSize(1);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aSlotTheExtensionClosedItselfIsHandledAtOnceSoLateEventsAreIgnored() {
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO)), APPROVED, quiet(Duration.ofHours(1), Duration.ofMinutes(10)));
        h.toIntroduction();
        h.played();
        Slot opportunity = h.out.lastSlot();

        assertThat(h.advance(Duration.ofMinutes(10))).isEqualTo(Signal.NONE);

        assertThat(h.out.cancelledSpeech).containsExactly(opportunity.correlationId());
        assertThat(h.slotEvent(opportunity.correlationId(), Playback.INTERRUPTED, opportunity.speech(), opportunity.text()))
                .isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).isEqualTo(SCENE_CLOSE);
        assertThat(h.playedThroughRecap()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aBlockedRecoveryNarrationIsSkippedKeepsTheSceneAndIgnoresTheFollowingFailedEvent() {
        // FT-1023-g: 막힌 말(C안 장면 안에서 제브를 거치는 것은 낭독 실패 복구뿐)은 다시 내지 않고 마무리 문장도 없이 이 단계의 입력을 기다린다
        Harness h = direct();
        h.toIntroduction();
        h.played();
        Slot first = h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT, "approved-text-mismatch", first.correlationId(), first.speech());
        Slot retry = h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT, "approved-text-mismatch", retry.correlationId(), retry.speech());
        Slot recovery = h.out.lastSlot();
        assertThat(recovery.generated()).isTrue();
        int slots = h.out.slots.size();

        Signal end = h.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", recovery.correlationId(),
                new GuardrailBlockedEvent(recovery.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_PEER,
                        "BLOCK", true));
        Signal late = h.slotEvent(recovery.correlationId(), Playback.FAILED, null, recovery.text());

        assertThat(end).isEqualTo(Signal.NONE);
        assertThat(late).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("막힌 말은 다시 내지 않고 마무리 문장도 내지 않는다").hasSize(slots);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(h.out.timers).containsKey("no-response");
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).as("막힘은 재생 실패 사실이 아니다").isEmpty();
        assertThat(row.endedAt()).isNull();
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aGoalBriefTheOutputCannotMakeIsSkippedAndTheSceneStillStarts() {
        // FT-1023-g: 못 낸 목표 안내는 다시 내지 않고 장면 도입으로 간다. 연습을 닫지 않는다
        // C안 승인 낭독 목표 안내(FT-1024-g): 재생에 실패해도 다시 읽지 않는다
        Harness approved = direct();
        assertThat(approved.activate()).isEqualTo(Signal.NONE);
        assertThat(approved.out.lastSlot().text()).isEqualTo(V2_GOAL_BRIEF);
        assertThat(approved.end(Playback.FAILED)).isEqualTo(Signal.NONE);
        assertThat(approved.out.slots).filteredOn(slot -> V2_GOAL_BRIEF.equals(slot.text())).as("목표 안내는 한 번만 요청한다")
                .hasSize(1);
        assertThat(approved.out.lastSlot().text()).isEqualTo(V2_INTRO);
        assertThat(approved.recorder.list()).as("장면 행은 도입이 끝날 때 만든다").isEmpty();
        assertThat(approved.played()).isEqualTo(Signal.NONE);
        assertThat(approved.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(approved.recorder.factKinds(approved.recorder.only())).as("장면 밖 말의 실패는 장면 사실로 남지 않는다").isEmpty();
        assertThat(approved.failures).isEmpty();

        // 생성형으로 대신한 목표 안내(승인 문장으로 다듬을 수 없는 목표 이름): 출력이 만들지 못해도 같다
        Harness h = generatedBrief();
        h.out.generatedResult = false;

        assertThat(h.activate()).isEqualTo(Signal.NONE);
        assertThat(h.out.generatedSlots()).as("목표 안내는 한 번만 요청한다").hasSize(1);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_INTRO);
        assertThat(h.recorder.list()).as("장면 행은 도입이 끝날 때 만든다").isEmpty();
        assertThat(h.played()).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(h.recorder.factKinds(h.recorder.only())).as("장면 밖 말의 실패는 장면 사실로 남지 않는다").isEmpty();
        assertThat(h.failures).isEmpty();
    }

    @Test
    void anApprovedReadingTheOutputRefusesMidSceneIsSkippedAndNeverReportedAsCompleted() {
        Harness h = direct();
        h.toOpportunity();
        h.child("그럼 그만해");
        h.out.speakResult = false;

        Signal end = h.result("J2", "PARTIAL");

        assertThat(end).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.REASK);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.PLAYBACK_FAILED);
        assertThat(row.liveTiming()).isNull();
        assertThat(h.failures).isEmpty();
    }

    @Test
    void approvedSpeechTheOutputRefusesIsSkippedAndTheSceneWaitsForTheChild() {
        // FT-1023-g: 도입 · 전하기 + 묻기를 끝내 못 내도 연습을 닫지 않고 아이 말을 받는다
        Harness h = direct();
        h.out.speakResult = false;

        assertThat(h.activate()).as("C안 목표 안내도 승인 낭독이라 같이 못 내고 건너뛴다(FT-1024-g)").isEqualTo(Signal.NONE);
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED, FactKind.PLAYBACK_FAILED);
        assertThat(h.out.timers).as("아이 말은 계속 받는다").containsKey("no-response");
    }

    // ---- 도움 말 (역할 전환 없음) ----

    @Test
    void aHelpSpeechThatFailsIsSkippedAndTheRetryStillListens() {
        // FT-1023-g: 못 들려준 도움은 다시 내지 않고 마무리 문장도 없이 다시 해 보기의 아이 말을 기다린다(유연 대화의 코치 도움 실패와 같다)
        Harness h = direct();
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        int slots = h.out.slots.size();

        Signal end = h.end(Playback.FAILED);

        assertThat(end).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("도움을 다시 내지 않고 마무리 문장도 내지 않는다").hasSize(slots);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.RETRY);
        assertThat(h.ext.state().retryCount()).isEqualTo(1);
        assertThat(h.out.timers).containsKey("no-response");
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PLAYBACK_FAILED);
        assertThat(h.recorder.only().endedAt()).isNull();
    }

    @Test
    void aHelpSpeechInterruptedByTheChildStillMovesToTheRetry() {
        Harness h = direct();
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");

        assertThat(h.end(Playback.INTERRUPTED)).isEqualTo(Signal.NONE);

        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.RETRY);
        assertThat(h.ext.state().retryCount()).isEqualTo(1);
        assertThat(h.out.timers).containsKey("no-response");
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void theDirectAskFlowNeverUsesTheRoleSwitchNoticeEvenWhenNoticeSentencesAreApproved() {
        Map<String, String> withNotices = new java.util.HashMap<>(APPROVED);
        withNotices.put("COACH_SWITCH", "잠깐, 무중이 도와줄게");
        withNotices.put("PEER_RETURN", "다시 친구 역할로 돌아갈게");
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO)), withNotices);
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.COACH_UTTERANCE);
        h.played();
        h.child("손 괜찮아? 다음에 같이 그리자");
        h.result("J5", "FULL");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.FRIEND_REACTION);
        h.playedThroughRecap();

        assertThat(h.out.announces).isEmpty();
        assertThat(h.out.slots).extracting(Slot::text).doesNotContain("잠깐, 무중이 도와줄게", "다시 친구 역할로 돌아갈게");
        assertThat(h.ext.state().role()).isEqualTo(PracticeFlowTypes.FlowRole.PEER);
        assertThat(h.recorder.helps).extracting(PracticeFlowTypes.HelpRecord::kind).containsExactly(HelpKind.FOCUS_HELP);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.COACH_SWITCH, FactKind.COACH_RETURN,
                FactKind.ORIGINAL_SCENE_RETURN);
    }

    // ---- 변형 · 다음 목표 ----

    @Test
    void anAcceptedVariantRunsTheVariantSceneAsANewRowAndIsNotOfferedAnotherVariant() {
        Harness h = withVariant();
        h.toOpportunity();
        h.child("손 괜찮아?");
        h.result("J2", "FULL");
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REACTION);
        h.played();
        Slot offer = h.out.lastSlot();
        assertThat(offer.text()).isEqualTo(VARIANT_OFFER);
        assertThat(offer.generated()).isFalse();
        assertThat(h.ext.progress().stage()).isEqualTo("AFTER_CUE_RESPONSE");
        h.played();
        h.child("응");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J4);
        assertThat(h.out.lastJudgment().criteria()).as("J4에는 판정 기준을 싣지 않는다").isNull();

        h.result("J4", "ACCEPT");
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_VARIANT_INTRO);
        assertThat(h.ext.progress().stage()).isEqualTo("NEW_SCENARIO_RESPONSE");
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_VARIANT_RELAY + " " + V2_VARIANT_ASK);
        h.played();
        h.child("졸리구나, 내일 또 하자");
        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_VARIANT_REACTION);
        Signal end = h.playedThroughRecap();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        List<SceneRunRecord> rows = h.recorder.list();
        assertThat(rows).extracting(SceneRunRecord::sceneKind).containsExactly(SceneKind.BASE, SceneKind.VARIANT);
        assertThat(rows.get(0).variantSkipReason()).isNull();
        assertThat(rows.get(0).liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.factKinds(rows.get(1))).containsExactly(FactKind.VARIANT_PLAYED);
        assertThat(h.out.generatedSlots()).as("생성형은 회고뿐(목표 안내는 승인 낭독, FT-1024-g)").hasSize(1);
        assertThat(h.out.slots.getFirst().text()).isEqualTo(V2_GOAL_BRIEF);
        assertRecordMatchesTheContract(h.ext.practiceRecord());
    }

    @Test
    void aDeclinedVariantRecordsDeclinedAndEndsThePractice() {
        Harness h = withVariant();
        h.toOpportunity();
        h.child("손 괜찮아?");
        h.result("J2", "FULL");
        h.played();
        h.played();
        h.child("싫어");

        assertThat(h.result("J4", "REFUSE")).isEqualTo(Signal.NONE);
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);

        SceneRunRecord row = h.recorder.only();
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.DECLINED);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.VARIANT_SKIPPED);
    }

    @Test
    void theNextGoalScriptIsRequestedInTheBackgroundAndTheNextGoalStartsWhenItArrives() throws Exception {
        Harness h = new Harness(twoGoalPackage(), APPROVED);
        when(h.http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any()))
                .thenReturn(new InternalHttpClient.Response(200, directAskScript(SCENARIO_TWO)));
        h.toOpportunity();
        h.child("손 괜찮아?");

        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        assertThat(h.played()).as("친구 반응 뒤 이 목표의 회고").isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).contains("이 목표 연습은 여기까지");
        Signal advanced = h.played();

        assertThat(advanced).as("첫 목표가 끝났고 다음 대본은 아직 안 닿았다").isEqualTo(Signal.NEXT_GOAL);
        assertThat(h.out.slots).as("안내 · 도입 · 묻기 · 친구 반응 · 회고").hasSize(5);
        h.deliverPosted();
        assertThat(h.out.slots).as("대본이 닿으면 다음 목표의 안내").hasSize(6);
        // C안 목표 안내 승인 문장: 받침 없는 이름 + '야', 목표 수 · 순서는 숫자 대신 말(FT-1024-g)
        assertThat(h.out.slots.getFirst().text()).isEqualTo("오늘 연습할 목표는 부탁하기야. 오늘 고른 목표는 두 개이고, 지금은 첫 번째야. "
                + "이 목표로 짧게 이야기 연습을 해 볼 거야. 필요하면 도움을 받아도 되고, 언제든 쉬거나 끝내도 돼.");
        assertThat(h.out.lastSlot().generated()).isFalse();
        assertThat(h.out.lastSlot().text()).isEqualTo("오늘 연습할 목표는 거절하기야. 오늘 고른 목표는 두 개이고, 지금은 두 번째야. "
                + "이 목표로 짧게 이야기 연습을 해 볼 거야. 필요하면 도움을 받아도 되고, 언제든 쉬거나 끝내도 돼.");
        assertThat(h.ext.progress().goalIndex()).isEqualTo(2);
        assertThat(h.ext.progress().goalCount()).isEqualTo(2);
        assertThat(h.ext.progress().goalName()).isEqualTo("거절하기");
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_INTRO);
        h.played();
        h.played();
        h.child("싫어");
        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Signal end = h.playedThroughRecap();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.list()).hasSize(2);
        assertThat(h.recorder.list().get(1).scenarioRef()).isEqualTo(SCENARIO_TWO);
        assertThat(h.recorder.list().get(1).goalRef()).isEqualTo(GOAL_TWO);
        assertThat(h.recorder.list().get(1).goalIndex()).isEqualTo(1);
    }

    @Test
    void aRejectedNextGoalScriptMakesThatGoalUnperformedAndThePracticeEnds() throws Exception {
        Harness h = new Harness(twoGoalPackage(), APPROVED);
        when(h.http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any()))
                .thenReturn(new InternalHttpClient.Response(422, "{}"));
        h.toOpportunity();
        h.child("손 괜찮아?");
        h.result("J2", "FULL");
        h.played();
        assertThat(h.played()).as("회고 뒤 다음 대본을 기다린다").isEqualTo(Signal.NEXT_GOAL);

        Signal end = h.deliverPosted();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.list()).as("거절된 목표는 행이 없다").hasSize(1);
        assertThat(h.failures).extracting(Throwable::getMessage).containsExactly("PRACTICE_SCRIPT_REQUEST_FAILED");
    }

    @Test
    void aRejectedFirstScriptSkipsTheFirstGoalAndEndsAtOnce() {
        String broken = "{\"scenarioId\":\"" + SCENARIO + "\",\"kind\":\"ORIGINAL\",\"scriptVersion\":\"scenario-script.v2\",\"scenes\":[]}";
        Harness h = new Harness(packageJson(GOAL, broken), APPROVED);

        Signal end = h.activate();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.slots).isEmpty();
        assertThat(h.failures).hasSize(1);
    }

    // ---- 끼어든 전사 · 종료 · 기록 실패 · 진행 표시 ----

    @Test
    void aChildAnswerGivenWhileTheHelpIsReadIsJudgedAsTheRetryAnswerOnceTheHelpWasInterrupted() {
        Harness h = direct();
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        int judgments = h.out.judgments.size();

        h.child("손 괜찮아? 다음에 그리자");
        assertThat(h.out.judgments).as("도움을 읽는 중에는 판정하지 않는다").hasSize(judgments);
        h.end(Playback.INTERRUPTED);

        assertThat(h.out.judgments).hasSize(judgments + 1);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        assertThat(h.ext.state().retryCount()).isEqualTo(1);
    }

    @Test
    void anAnswerThatArrivesWhileTheQuestionIsStillPlayingIsJudgedWhenThePlaybackEnds() {
        Harness h = direct();
        h.toIntroduction();
        h.played();

        h.child("손 괜찮아?");
        assertThat(h.out.judgments).isEmpty();
        h.played();

        assertThat(h.out.judgments).hasSize(1);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.timers).doesNotContainKey("no-response");
    }

    @Test
    void aRestRequestStopsTheSceneHoldsTheOutputAndLeavesTheEndingToTheExecutor() {
        Harness h = direct();
        h.toOpportunity();

        Signal signal = h.event(ExtensionEvent.Kind.REST_REQUESTED, "judgment", null, null);

        assertThat(signal).as("종료 · 휴식 판정은 실행부 소유라 신호를 내지 않는다").isEqualTo(Signal.NONE);
        assertThat(h.out.holds).containsExactly(true);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.END_REQUEST);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.END_OR_REST);
        assertThat(h.out.timers).isEmpty();
        assertThat(h.event(ExtensionEvent.Kind.REST_REQUESTED, "judgment", null, null)).isEqualTo(Signal.NONE);
    }

    @Test
    void stoppingMidSceneRecordsTheEndRequestWithoutCallingTheOutputAndIgnoresLaterEvents() {
        Harness h = direct();
        h.toOpportunity();
        int calls = h.out.calls;

        h.ext.onStopping();
        h.ext.onStopping();

        assertThat(h.out.calls).as("종료 중에는 능력 호출이 거절되므로 부르지 않는다").isEqualTo(calls);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.END_REQUEST);
        assertThat(h.child("손 괜찮아?")).isEqualTo(Signal.NONE);
        assertThat(h.advance(Duration.ofMinutes(11))).isEqualTo(Signal.NONE);
        h.ext.onSettle(null);
        assertThat(h.out.calls).isEqualTo(calls);
    }

    @Test
    void aFailingRecorderNeverStopsThePracticeAndOnlyAFixedAppErrorIsReported() {
        Harness h = direct();
        h.recorder.failOnSceneStarted = true;
        h.toOpportunity();
        h.child("손 괜찮아?");

        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Signal end = h.played();

        assertThat(end).as("기록하지 못한 장면은 회고 없이 끝난다").isEqualTo(Signal.LAST_GOAL);
        assertThat(h.failures).hasSize(1);
        assertThat(h.failures.getFirst()).hasMessage("기록 실패(시험)");
    }

    @Test
    void theProgressStageFoldsTheNewStagesIntoTheSixGuardianValues() {
        Harness h = direct();
        assertThat(h.ext.progress()).as("시작 전").isNull();
        h.activate();
        assertThat(h.ext.progress().stage()).as("목표 안내").isEqualTo("SCENARIO_INTRO");
        assertThat(h.ext.progress().goalIndex()).isEqualTo(1);
        h.played();
        assertThat(h.ext.progress().stage()).isEqualTo("SCENARIO_INTRO");
        h.played();
        assertThat(h.ext.progress().stage()).isEqualTo("FIRST_RESPONSE");
        h.played();
        h.child("음");
        h.result("J2", "PARTIAL");
        assertThat(h.ext.progress().stage()).isEqualTo("FIRST_RESPONSE");
        h.played();
        h.child("음");
        h.result("J3", "STILL_SHORT");
        assertThat(h.ext.progress().stage()).isEqualTo("HELP_OFFER");
        h.played();
        h.child("응");
        h.result("J4", "ACCEPT");
        assertThat(h.ext.progress().stage()).isEqualTo("CUE");
        h.played();
        assertThat(h.ext.progress().stage()).isEqualTo("AFTER_CUE_RESPONSE");
        h.child("손 괜찮아? 다음에 같이 그리자");
        h.result("J5", "FULL");
        assertThat(h.ext.progress().stage()).isEqualTo("AFTER_CUE_RESPONSE");
        h.playedThroughRecap();

        assertThat(h.ext.progress().stage()).as("CLOSING은 직전 값을 유지한다").isEqualTo("AFTER_CUE_RESPONSE");
    }

    @Test
    void theFirstNoResponseExpiryRereadsTheQuestionOfEveryInputStageAndTheSecondDeclinesTheVariant() {
        Harness h = withVariant();
        h.toOpportunity();
        h.child("가");
        h.result("J2", "PARTIAL");
        h.played();
        int slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("되묻기 단계 재안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(WAIT_LEAD + " " + V2_REASK);
        h.played();
        h.child("나");
        h.result("J3", "STILL_SHORT");
        h.played();
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("도움 제안 단계 재안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(HELP_OFFER);
        h.played();
        h.child("다");
        h.result("J4", "ACCEPT");
        h.played();
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("다시 해 보기 단계 재안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(RETRY_PROMPT);
        h.played();
        h.child("라");
        h.result("J5", "FULL");
        h.played();
        assertThat(h.out.lastSlot().text()).as("도움을 받고 해낸 장면에도 변형을 제안한다").isEqualTo(VARIANT_OFFER);
        h.played();
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("변형 제안 단계 재안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(VARIANT_OFFER);
        h.played();

        assertThat(h.advance(Duration.ofSeconds(10))).isEqualTo(Signal.NONE);
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.DECLINED);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
        assertThat(h.recorder.factKinds(row)).endsWith(FactKind.VARIANT_SKIPPED);
    }

    @Test
    void theGoalClockClosesAnOpenVariantOfferWithTimeLimit() {
        Harness h = new Harness(packageJson(GOAL, directAskScriptWithVariant(SCENARIO)), APPROVED,
                quiet(Duration.ofHours(1), Duration.ofMinutes(10)));
        h.toOpportunity();
        h.child("손 괜찮아?");
        h.result("J2", "FULL");
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(VARIANT_OFFER);

        assertThat(h.advance(Duration.ofMinutes(10))).isEqualTo(Signal.NONE);
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);

        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.TIME_LIMIT);
    }

    @Test
    void theFocusClockCancelsTheHelpBeingReadAndIgnoresItsLateEnd() {
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO)), APPROVED, quiet(Duration.ofSeconds(90), Duration.ofHours(2)));
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        Slot help = h.out.lastSlot();

        h.advance(Duration.ofSeconds(90));

        assertThat(h.out.cancelledSpeech).containsExactly(help.correlationId());
        assertThat(h.slotEvent(help.correlationId(), Playback.INTERRUPTED, help.speech(), help.text())).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).isEqualTo(SCENE_CLOSE);
        assertThat(h.playedThroughRecap()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aJudgmentOpenWhenTheFocusClockFiresIsDroppedAndItsLateResultIsIgnored() {
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO)), APPROVED, quiet(Duration.ofSeconds(90), Duration.ofHours(2)));
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        h.played();
        h.child("손 괜찮아? 다음에 같이 그리자");
        UUID open = h.out.lastJudgment().correlationId();

        h.advance(Duration.ofSeconds(90));
        Signal late = h.result(open, "J5", "FULL");

        assertThat(late).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).isEqualTo(SCENE_CLOSE);
        assertThat(h.recorder.only().liveTiming()).as("한도로 닫힌 장면은 늦은 결과로 바뀌지 않는다").isEqualTo(LiveTiming.NONE);
    }

    @Test
    void aLongJudgmentCriteriaIsCutForCoreFunctionAndSentWholeAsTheCriteria() {
        String criteria = "가".repeat(600);
        String json = "{\"goals\":[{\"goalSelectionId\":\"" + GOAL + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"judgmentCriteria\":\"" + criteria + "\",\"firstScenario\":" + directAskScript(SCENARIO) + "}]}";
        Harness h = new Harness(json, APPROVED);
        h.toOpportunity();

        h.child("손 괜찮아?");

        assertThat(((String) h.out.lastJudgment().criteria().get("coreFunction"))).hasSize(500);
        assertThat(((String) h.out.lastJudgment().criteria().get("judgmentCriteria"))).hasSize(600);
    }

    @Test
    void aMalformedJudgmentEventIsHandledWithoutStallingThePractice() {
        Harness h = direct();
        h.toOpportunity();
        h.child("가");
        h.event(ExtensionEvent.Kind.JUDGMENT_RESULT, "judgment", h.out.lastJudgment().correlationId(),
                Map.of("point", "J9", "firstOption", "FULL"));

        assertThat(h.failures).extracting(Throwable::getMessage).containsExactly("PRACTICE_FLOW_UNEXPECTED_INPUT");
        assertThat(h.out.timers).as("무응답 시계를 다시 건다").containsKey("no-response");

        h.child("나");
        int slots = h.out.slots.size();
        h.event(ExtensionEvent.Kind.JUDGMENT_RESULT, "judgment", h.out.lastJudgment().correlationId(), "모양이 틀림");
        assertThat(h.out.slots).as("모양이 틀린 결과는 보류처럼 한 번 확인한다").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(INPUT_CONFIRM);
        h.played();
        h.child("다");
        assertThat(h.event(ExtensionEvent.Kind.JUDGMENT_ABSTAINED, "judgment", h.out.lastJudgment().correlationId(), "모양이 틀림"))
                .isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).as("두 번째 보류는 도움 제안으로 간다(FT-1023-g)").isEqualTo(FlowState.Phase.HELP_OFFER);
        assertThat(h.out.lastSlot().text()).isEqualTo(HELP_OFFER);
        assertThat(h.recorder.only().endedAt()).as("장면을 닫지 않는다").isNull();
    }

    @Test
    void strayEventsTimersAndUnknownSlotsAreIgnoredWithoutChangingTheStage() {
        Harness h = direct();
        h.toIntroduction();
        h.played();
        h.end(Playback.STARTED);
        assertThat(h.out.timers).as("재생 시작 사실은 종료가 아니다").doesNotContainKey("no-response");

        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "slot-guard-" + UUID.randomUUID(), null, null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "mystery", null, null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "focus-max", null, null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "child-speech", null, null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT, "other", null, "x")).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT, "practice-script", null, "not a result")).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.PLAYBACK_FACT, "u5b-playback", null, "bad")).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.PLAYBACK_DONE, "code", UUID.randomUUID(), null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.HELD_OUTPUT_TRANSCRIPT, "judgment", null, Map.of("text", "x"))).isEqualTo(Signal.NONE);
        assertThat(h.slotEvent(UUID.randomUUID(), Playback.COMPLETED, null, "모르는 슬롯")).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(h.out.timers).doesNotContainKey("no-response");

        h.played();

        assertThat(h.out.timers).containsKey("no-response");
        assertThat(h.failures).isEmpty();
    }

    @Test
    void ifTheNextGoalScriptCallCannotEvenStartThatGoalIsUnperformedAndNoWaitingHappens() {
        Harness h = new Harness(twoGoalPackage(), APPROVED, PracticeFlowTestKit.defaults(), command -> {
            throw new java.util.concurrent.RejectedExecutionException("시험: 실행기 거절");
        });
        h.toOpportunity();
        h.child("손 괜찮아?");

        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Signal end = h.playedThroughRecap();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.failures).extracting(Throwable::getMessage).containsExactly("PRACTICE_SCRIPT_REQUEST_FAILED");
    }

    @Test
    void theDoNothingRecorderKeepsNothingAndTheConversationWithItStillRuns() {
        FlowRecorder none = FlowRecorder.NONE;
        UUID id = UUID.randomUUID();
        none.sceneStarted(null);
        none.help(null);
        none.fact(id, FactKind.TIMEOUT, java.time.Instant.EPOCH);
        none.sceneOutcome(id, java.time.Instant.EPOCH, LiveTiming.NONE, null, 0, 0);
        assertThat(none.practiceRecord()).isNull();
        Harness h = direct();
        PracticeConversation withNone = new PracticeConversation(UUID.randomUUID(), UUID.randomUUID(), h.clock,
                PracticeFlowTestKit.defaults(), new PracticeScriptRequester(h.http, Runnable::run, Duration.ofSeconds(1)), none,
                h.failures::add);

        assertThat(withNone.onActive(h.context, h.out)).isEqualTo(Signal.NONE);
        assertThat(withNone.practiceRecord()).isNull();
        assertThat(h.out.slots).hasSize(1);
    }

    // ---- 2장 3A: 바로 수행 → 친구 반응(승인 낭독) ----

    @Test
    void achievementWaitsForTheApprovedFriendReactionAndItsPlaybackBeforeFinishing() {
        Harness h = direct(); h.toOpportunity(); h.child("다음에 같이 그리자고 할래");
        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Slot reaction = h.out.lastSlot();
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.FRIEND_REACTION);
        assertThat(reaction.generated()).as("친구 반응은 대본에 미리 쓴 승인 문장").isFalse();
        assertThat(reaction.text()).isEqualTo(V2_REACTION);
        assertThat(h.out.timers).doesNotContainKey("no-response");
        int judgments = h.out.judgments.size();
        h.child("응"); // Acknowledgement during the closing line must not restart judgment.
        assertThat(h.out.judgments).hasSize(judgments);
        assertThat(h.end(Playback.STARTED)).isEqualTo(Signal.NONE);
        assertThat(h.played()).as("친구 반응 뒤 목표 회고").isEqualTo(Signal.NONE);
        assertThat(h.slotEvent(reaction.correlationId(), Playback.COMPLETED, reaction.speech(), "응"))
                .isEqualTo(Signal.NONE);
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.slots.stream().filter(slot -> slot.text().equals(V2_REACTION))).hasSize(1);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
    }

    @ParameterizedTest
    @EnumSource(value = Playback.class, names = {"FAILED", "INTERRUPTED"})
    void aFailedOrCutOffFriendReactionIsNotRetriedAndThePracticeMovesOn(Playback playback) {
        // FT-1023-g: 아이가 이미 해낸 장면은 그대로 두고, 못 들려준(끊긴) 친구 반응은 다시 내지 않은 채 회고 · 다음으로 간다
        Harness h = direct(); h.toOpportunity(); h.child("다음에 같이 그리자고 할래"); h.result("J2", "FULL");
        assertThat(h.end(playback)).as("친구 반응 뒤 목표 회고").isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.slots.stream().filter(slot -> slot.text().equals(V2_REACTION))).as("친구 반응은 다시 내지 않는다").hasSize(1);
        assertThat(h.recorder.factKinds(h.recorder.only()).contains(FactKind.PLAYBACK_FAILED)).isEqualTo(playback == Playback.FAILED);
        assertThat(h.recorder.only().liveTiming()).as("이룬 수행은 지우지 않는다").isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
    }

    @Test
    void aFailedFriendReactionKeepsTheAchievedSceneSoTheVariantIsStillOffered() {
        // FT-1023-g: 못 들려준 친구 반응은 장면의 원인(정상 수행)을 바꾸지 않아 변형 제안이 그대로 나간다
        Harness h = withVariant(); h.toOpportunity(); h.child("다음에 같이 그리자고 할래"); h.result("J2", "FULL");
        Slot reaction = h.out.lastSlot();
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.VARIANT_OFFER);
        assertThat(h.out.lastSlot().text()).isEqualTo(VARIANT_OFFER);
        assertThat(h.out.slots).filteredOn(slot -> reaction.text().equals(slot.text())).as("친구 반응은 다시 내지 않는다").hasSize(1);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void silentFriendReactionTimesOutInsteadOfHangingOrLooping() {
        // FT-1023-g: 시한이 지난 친구 반응은 다시 내지 않고 재생 실패 사실만 남긴 채 회고 · 다음으로 간다
        Harness h = direct(); h.toOpportunity(); h.child("다음에 같이 그리자고 할래"); h.result("J2", "FULL");
        int slots = h.out.slots.size();
        assertThat(h.advance(Duration.ofSeconds(20))).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("친구 반응을 다시 내지 않고 목표 회고만 연다").hasSize(slots + 1);
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PLAYBACK_FAILED);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
    }

    @Test
    void childStopCancelsTheFriendReactionImmediatelyAndKeepsTheAchievedTiming() {
        Harness h = direct(); h.toOpportunity(); h.child("다음에 같이 그리자고 할래"); h.result("J2", "FULL");
        Slot reaction = h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.REST_REQUESTED, "judgment", null, null)).isEqualTo(Signal.NONE);
        assertThat(h.out.holds).contains(true);
        assertThat(h.out.cancelledSpeech).contains(reaction.correlationId());
        assertThat(h.recorder.only().endedAt()).isNotNull();
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.END_OR_REST);
        assertThat(h.recorder.only().liveTiming()).as("마무리 중의 종료가 이룬 수행을 미수행으로 덮지 않는다").isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.slotEvent(reaction.correlationId(), Playback.COMPLETED, reaction.speech(), "응"))
                .isEqualTo(Signal.NONE);
        assertThat(h.out.generatedSlots()).as("종료 요청이면 회고도 없다(목표 안내는 승인 낭독, FT-1024-g)").isEmpty();
    }

    @Test
    void externalStopDuringTheFriendReactionStillClosesTheStoredScene() {
        Harness h = direct(); h.toOpportunity(); h.child("다음에 같이 그리자고 할래"); h.result("J2", "FULL");
        assertThat(h.recorder.only().endedAt()).isNull();
        h.ext.onStopping();
        assertThat(h.recorder.only().endedAt()).isNotNull();
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.END_REQUEST);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
    }

    @Test
    void longestApprovedFieldsDoNotTruncateTheClosingReplySafetyRules() {
        String field = "가".repeat(80);
        var scene = new PracticeScript.Scene("base", SceneKind.BASE, field, field, field, field,
                List.of(), List.of(), "", "", "");
        String instruction = PracticeInstructions.peerCompletion(scene);
        assertThat(instruction.codePointCount(0, instruction.length())).isLessThanOrEqualTo(500);
        assertThat(instruction).contains("추가 답변을 요구하지 않는다", "정답이나 모범 문장을 먼저 말하지 말고")
                .endsWith("한두 문장으로 말한다.");
    }

    @Test
    void anEmptyOrUnreadablePracticeFragmentFailsTheStartAndEventsBeforeActivationAreIgnored() {
        Harness blank = new Harness(" ", APPROVED);
        assertThat(blank.ext.onChildUtterance(null, blank.out)).isEqualTo(Signal.NONE);
        assertThat(blank.event(ExtensionEvent.Kind.TIMER_FIRED, "no-response", null, null)).isEqualTo(Signal.NONE);
        assertThat(blank.speechStarted()).isEqualTo(Signal.NONE);
        assertThatThrownBy(blank::activate).isInstanceOf(PracticeScriptException.class);
        assertThatThrownBy(() -> new Harness("not json", APPROVED).activate()).isInstanceOf(PracticeScriptException.class);

        Harness once = direct();
        once.activate();
        int slots = once.out.slots.size();
        assertThat(once.activate()).as("두 번째 onActive는 아무것도 하지 않는다").isEqualTo(Signal.NONE);
        assertThat(once.out.slots).hasSize(slots);
        assertThat(once.ext.controlsOutput()).isTrue();
        assertThat(once.ext.toString()).doesNotContain(V2_INTRO);
        assertThat(new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED).ext.toString()).doesNotContain("상자");
    }
}
