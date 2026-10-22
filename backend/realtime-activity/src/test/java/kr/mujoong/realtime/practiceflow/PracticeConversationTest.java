package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.fullScript;
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
 * 새 연습 상태 기계 시험 (U12 FR2 · FR3 · FR5 · BR1 ~ BR6). 기록하는 가짜 출력 · 가짜 판정 결과 사건 · 수동 시계 · 메모리 기록기 위에서 확장점
 * 시점(onActive · onChildUtterance · onExtensionEvent)만으로 장면을 끝까지 움직인다(테스트 대역 · 합성 대화 증거이고 실제 AI · 실제 기기가
 * 아니다). 표 행 자체는 {@code FlowTransitionsTest}가 보고 여기서는 시계 경계 · 출력 능력 호출 · 중복 사건 · 실패 이어 가기 · 지시문 위생을 본다.
 */
@Tag("practice-flow")
class PracticeConversationTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID GOAL_TWO = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID SCENARIO_TWO = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final String INTRO = "친구가 상자 두 개를 들고 있어.";
    private static final String OPPORTUNITY = "친구가 말했어. 그거 줘.";
    private static final String CRITERIA = "원하는 것을 말로 요청한다";
    private static final Map<String, String> APPROVED = Map.of("COACH_SWITCH", "이제 코치가 도와줄게.", "PEER_RETURN", "다시 친구로 돌아왔어.");

    private static Harness minimal() {
        return new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED);
    }

    private static Harness full() {
        return new Harness(packageJson(GOAL, fullScript(SCENARIO)), APPROVED);
    }

    private static String twoGoalPackage() {
        return "{\"goals\":[{\"goalSelectionId\":\"" + GOAL + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"judgmentCriteria\":\"" + CRITERIA + "\",\"firstScenario\":" + minimalScript(SCENARIO) + "},"
                + "{\"goalSelectionId\":\"" + GOAL_TWO + "\",\"order\":2,\"displayName\":\"거절하기\","
                + "\"judgmentCriteria\":\"싫다는 뜻을 말로 전한다\"}]}";
    }

    private static PracticeFlowSettings quiet(Duration focusMax, Duration goalLimit) {
        Duration hour = Duration.ofHours(1);
        return new PracticeFlowSettings(hour, focusMax, goalLimit, hour, 2, 1, false);
    }

    private static void assertRecordMatchesTheContract(Object practice) {
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(practice))).isEmpty();
    }

    @Test
    void answerDuringReexplanationIsJudgedOnceAfterPlaybackWithoutRepeatingTheOpportunity() {
        Harness h=minimal();h.toOpportunity();h.child("상황을 잘 모르겠어");h.result("J2","NOT_UNDERSTOOD");
        Slot explanation=h.out.lastSlot();int slots=h.out.slots.size();
        h.child("무슨 놀이 하고 싶어?");
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
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
    }

    @Test
    void reexplanationWithoutAnEarlyAnswerListensInsteadOfReadingTheSameOpportunityAgain() {
        Harness h=minimal();h.toOpportunity();h.child("무슨 뜻이야");h.result("J2","NOT_UNDERSTOOD");
        int slots=h.out.slots.size();h.played();
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.out.timers).containsKey("no-response");
        h.child("어떤 놀이 하고 싶어?");
        assertThat(h.out.judgments).hasSize(2);
    }

    @Test
    void failedReexplanationCannotJudgeTheBufferedAnswerOrFinishSuccessfully() {
        Harness h=minimal();h.toOpportunity();h.child("모르겠어");h.result("J2","NOT_UNDERSTOOD");
        h.child("어떤 놀이 하고 싶어?");
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.out.judgments).hasSize(1);
    }

    @Test
    void anIncorrectFullClassificationOfAProcedureQuestionOffersHelpInsteadOfCompleting() {
        Harness h=minimal();h.toOpportunity();h.child("무슨 놀이를 하고 싶은지 물어봐야 하는 거야");
        assertThat(h.result("J2","FULL")).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isNotEqualTo(FlowState.Phase.CLOSING);
        assertThat(h.out.announces).containsExactly("COACH");
        assertThat(h.recorder.only().liveTiming()).isNull();
    }

    @Test
    void aRealRoleQuestionCanStillCompleteTheGoal() {
        Harness h=minimal();h.toOpportunity();h.child("무슨 놀이를 하고 싶어?");
        assertThat(h.result("J2","FULL")).isEqualTo(Signal.NONE);
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
    }

    @Test
    void anAnswerDuringRetryReexplanationReturnsToJ5WithoutAnotherCoachSpeechOrRetryIncrement() {
        Harness h=minimal();h.toOpportunity();h.child("도와줘");h.result("J2","HELP");
        h.announcedNotice(Playback.COMPLETED);h.played();h.child("상황을 모르겠어");h.result("J5","NOT_UNDERSTOOD");
        int slots=h.out.slots.size(), retries=h.ext.state().retryCount();
        h.child("파란 블록 하나 줘");h.played();
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        assertThat(h.out.lastJudgment().seq()).isEqualTo(3);
        assertThat(h.ext.state().retryCount()).isEqualTo(retries);
    }

    @Test
    void aProcedureQuestionCannotCompleteTheReaskOrCoachedRetryEither() {
        Harness peer=minimal();peer.toOpportunity();peer.child("그거 줘");peer.result("J2","PARTIAL");peer.played();
        peer.child("뭐라고 대답해야 해?");
        assertThat(peer.result("J3","DELIVERED")).isEqualTo(Signal.NONE);
        assertThat(peer.ext.state().phase()).isNotEqualTo(FlowState.Phase.CLOSING);
        Harness coach=minimal();coach.toOpportunity();coach.child("도와줘");coach.result("J2","HELP");
        coach.announcedNotice(Playback.COMPLETED);coach.played();coach.child("이렇게 말하면 되는 거야?");
        assertThat(coach.result("J5","COPIED")).isEqualTo(Signal.NONE);
        assertThat(coach.ext.state().phase()).isNotEqualTo(FlowState.Phase.CLOSING);
    }

    @Test
    void unheardSceneIntroductionDoesNotCompleteThePracticeOrWaitForChildInput() {
        Harness h = minimal();
        h.activate();
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.rows).isEmpty();
        assertThat(h.out.timers).doesNotContainKey("no-response");
        assertThat(h.out.slots).hasSize(1);
    }

    @Test
    void blockedSceneIntroductionRemainsBlockedAndIsNotACompletedPractice() {
        Harness h = minimal();
        h.activate();
        Slot intro = h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", intro.correlationId(),
                new GuardrailBlockedEvent(intro.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_PEER, "BLOCK", true))).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.rows).isEmpty();
    }

    @Test
    void failedReexplanationIsSavedAsPlaybackFailureAndCannotCompleteThePractice() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("무슨 말인지 모르겠어");
        h.result("J2", "NOT_UNDERSTOOD");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.REEXPLAIN);
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PLAYBACK_FAILED);
        assertThat(h.recorder.only().endedAt()).isNotNull();
    }

    @Test
    void aTextMismatchRetriesOnlyTheSameApprovedBodyOnceAndRejectsLateMismatchEvents() {
        Harness h = minimal();h.activate();Slot first=h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech())).isEqualTo(Signal.NONE);
        Slot retry=h.out.lastSlot();
        assertThat(retry.correlationId()).isNotEqualTo(first.correlationId());
        assertThat(retry.text()).isEqualTo(first.text());
        assertThat(retry.generated()).isFalse();
        int slots=h.out.slots.size();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech());
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",retry.correlationId(),retry.speech())).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.out.slots).hasSize(slots);
        assertThat(h.recorder.rows).isEmpty();
    }

    @Test
    void anExactRetryMayPlayAndContinueWithoutMarkingThePracticeAsFailed() {
        Harness h=minimal();h.activate();Slot first=h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech());
        h.played();h.played();h.child("파란 블록 하나 줘");
        assertThat(h.result("J2","FULL")).isEqualTo(Signal.NONE);
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void aRetryWithoutOutputStillTimesOutAndDoesNotRetryForever() {
        Harness h=minimal();h.activate();Slot first=h.out.lastSlot();
        h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",first.correlationId(),first.speech());
        int slots=h.out.slots.size();
        assertThat(h.advance(Duration.ofSeconds(20))).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.out.slots).hasSize(slots);
    }

    @Test
    void generatedRoleSpeechCannotUseTheApprovedReadingRetryPath() {
        Harness h=minimal();h.toOpportunity();h.child("그거 줘");h.result("J2","PARTIAL");
        Slot generated=h.out.lastSlot();int count=h.out.slots.size();
        assertThat(generated.generated()).isTrue();
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT,"approved-text-mismatch",generated.correlationId(),generated.speech())).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).hasSize(count);
    }

    // ---- AC1.2.3: 되묻기로 해결 ----

    @Test
    void oneFriendReaskResolvesTheSceneAfterTheReaskWithNoFocusHelpAndTheRecordFollowsTheContract() {
        Harness h = minimal();

        assertThat(h.activate()).isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().text()).as("도입은 대본 상황 문장 승인 발화").isEqualTo(INTRO);
        assertThat(h.out.lastSlot().generated()).isFalse();
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);
        assertThat(h.out.timers).as("기회 문장 재생이 끝나기 전에는 무응답 시계가 없다").doesNotContainKey("no-response");
        h.played();
        assertThat(h.out.timers).containsKey("no-response");

        h.child("그거 줘");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.lastJudgment().criteria()).containsEntry("coreFunction", CRITERIA).containsEntry("judgmentCriteria", CRITERIA);
        assertThat(h.out.timers).as("판정 중에는 무응답 시계를 걸지 않는다").doesNotContainKey("no-response");
        h.result("J2", "PARTIAL");
        Slot reask = h.out.lastSlot();
        assertThat(reask.generated()).isTrue();
        assertThat(reask.role()).isEqualTo("PEER");
        h.played();

        h.child("파란색");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J3);
        assertThat(h.out.lastJudgment().criteria()).isNotNull();
        assertThat(h.result("J3", "DELIVERED")).isEqualTo(Signal.NONE);
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.sceneKind()).isEqualTo(SceneKind.BASE);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.REASK_RESOLVED, FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
        assertThat(row.endedAt()).isNotNull();
        assertThat(h.recorder.helps).as("집중 도움 0건").isEmpty();
        assertThat(h.out.generatedSlots()).as("코치 발화 없음").extracting(Slot::role).containsOnly("PEER");
        assertThat(h.failures).isEmpty();
        assertRecordMatchesTheContract(h.ext.practiceRecord());
    }

    // ---- 시계 경계 (수동 시계) ----

    @Test
    void theNoResponseClockReguidesExactlyAtTenSecondsAndNotAtNineAndTheSecondExpiryClosesTheScene() {
        Harness h = minimal();
        h.toOpportunity();
        assertThat(h.out.scheduleLog).contains("no-response:PT10S");
        int before = h.out.slots.size();

        h.advance(Duration.ofSeconds(9));
        assertThat(h.out.slots).as("9초에는 재안내하지 않는다").hasSize(before);

        h.advance(Duration.ofSeconds(1));
        assertThat(h.out.slots).as("10초에 같은 질문을 한 번 다시 안내한다").hasSize(before + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);
        assertThat(h.out.lastSlot().generated()).isFalse();
        h.played();

        Signal end = h.advance(Duration.ofSeconds(10));

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.TIMEOUT, FactKind.VARIANT_SKIPPED);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.NONE);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.END_OR_REST);
    }

    @Test
    void theFocusClockFiresAtNinetySecondsNotAtEightyNineAndClosesTheSceneWithAReturnNotice() {
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED, quiet(Duration.ofSeconds(90), Duration.ofHours(2)));
        h.toOpportunity();
        h.child("어떻게 말해?");
        h.result("J2", "HELP");
        assertThat(h.out.announces).containsExactly("COACH");
        h.announcedNotice(Playback.COMPLETED);
        assertThat(h.out.lastSlot().role()).isEqualTo("COACH");
        assertThat(h.out.scheduleLog).contains("focus-max:PT1M30S");
        h.played();

        h.advance(Duration.ofSeconds(89));
        assertThat(h.out.announces).as("89초에는 정리하지 않는다").containsExactly("COACH");

        h.advance(Duration.ofSeconds(1));
        assertThat(h.out.announces).as("90초에 장면을 정리하며 복귀 알림을 낸다").containsExactly("COACH", "PEER");
        Signal end = h.announcedNotice(Playback.COMPLETED);

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.COACH_SWITCH, FactKind.FOCUS_HELP, FactKind.COACH_RETURN,
                FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.NONE);
        assertThat(row.retryCount()).isEqualTo(1);
        assertThat(h.recorder.helps).extracting(PracticeFlowTypes.HelpRecord::kind).containsExactly(HelpKind.ROLE_SWITCH, HelpKind.FOCUS_HELP,
                HelpKind.ROLE_RETURN);
        assertThat(h.recorder.helps.getFirst().consentAnswer()).isEqualTo(PracticeFlowTypes.ConsentAnswer.REQUESTED);
        assertThat(h.recorder.helps.get(1).exampleGiven()).as("첫 코치 발화는 예시 없이 단서만").isFalse();
        assertThat(h.failures).isEmpty();
    }

    @Test
    void theGoalClockFiresAtTenMinutesNotOneSecondBeforeAndClosesTheGoalWithTimeLimit() {
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED, quiet(Duration.ofHours(1), Duration.ofMinutes(10)));
        h.toOpportunity();
        assertThat(h.out.scheduleLog).contains("goal-limit:PT10M");

        assertThat(h.advance(Duration.ofMinutes(10).minusSeconds(1))).isEqualTo(Signal.NONE);
        Signal end = h.advance(Duration.ofSeconds(1));

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.TIME_LIMIT);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.VARIANT_SKIPPED);
    }

    @Test
    void aSlotThatNeverReportsAnEndIsTreatedAsFailedWhenItsSafetyClockExpires() {
        Harness h = minimal();
        h.activate();
        assertThat(h.out.scheduleLog).anyMatch(entry -> entry.startsWith("slot-guard-") && entry.endsWith(":PT20S"));

        assertThat(h.advance(Duration.ofSeconds(19))).isEqualTo(Signal.NONE);
        Signal end = h.advance(Duration.ofSeconds(1));

        assertThat(end).as("도입을 듣지 못한 연습은 완료가 아니다").isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.list()).as("도입 재생이 성공하지 못했으니 행이 없다").isEmpty();
        assertThat(h.out.cancelledSpeech).containsExactly(h.out.slots.getFirst().correlationId());
    }

    // ---- 재시도 한도 · 코치 예시 ----

    @Test
    void generationTimeDoesNotConsumeTheDevicePlaybackGuard() {
        Harness h = minimal();
        h.activate();
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
        Harness h = minimal();
        h.activate();
        h.advance(Duration.ofSeconds(12));
        h.end(Playback.STARTED);
        h.advance(Duration.ofSeconds(10));
        h.end(Playback.STARTED);
        assertThat(h.advance(Duration.ofSeconds(10))).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.list()).isEmpty();
        assertThat(h.out.cancelledSpeech).hasSize(1);
    }

    @Test
    void anUnknownPlaybackStartDoesNotExtendTheGenerationGuard() {
        Harness h = minimal();
        h.activate();
        h.advance(Duration.ofSeconds(12));
        h.slotEvent(UUID.randomUUID(), Playback.STARTED, null, "unrelated");
        assertThat(h.advance(Duration.ofSeconds(8))).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.list()).isEmpty();
    }

    @Test
    void twoRetriesAreAllowedAndTheThirdPartialClosesAsFocusLimitWithTheExampleOnlyInTheSecondCoachRound() {
        Harness h = full();
        h.toOpportunity();
        h.child("어떻게 말해?");
        h.result("J2", "HELP");
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        h.child("음");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        h.result("J5", "PARTIAL");
        h.played();
        h.child("음음");
        h.result("J5", "PARTIAL");

        List<Slot> coach = h.out.generatedSlots().stream().filter(slot -> slot.role().equals("COACH")).toList();
        assertThat(coach).hasSize(2);
        assertThat(coach.get(0).text()).doesNotContain("파란 상자를 줘");
        assertThat(coach.get(1).text()).as("동의한 아이에게 둘째 코치 발화는 대본의 표현 예시를 쓴다").contains("파란 상자를 줘");
        assertThat(h.out.announces).as("둘째 한도에서 정리하며 복귀 알림").containsExactly("COACH", "PEER");
        h.announcedNotice(Playback.COMPLETED);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.retryCount()).isEqualTo(2);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(h.recorder.factKinds(row)).contains(FactKind.EXPRESSION_EXAMPLE);
    }

    @Test
    void aCopiedRetryAnswerCountsAsAfterHelpAndReturnsToThePeerWithAnOriginalSceneReturn() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        h.child("파란 상자를 줘");
        h.result("J5", "COPIED");
        assertThat(h.announcedNotice(Playback.COMPLETED)).isEqualTo(Signal.NONE);
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
        assertThat(h.recorder.factKinds(row)).containsSubsequence(FactKind.COACH_SWITCH, FactKind.FOCUS_HELP, FactKind.COACH_RETURN,
                FactKind.ORIGINAL_SCENE_RETURN);
    }

    // ---- 판정 거절 · 보류 · 이어 가기 ----

    @Test
    void whenRequestJudgmentReturnsFalseThePracticeDoesNotStopItReguidesOnceAndThenClosesAbstained() {
        Harness h = minimal();
        h.out.judgmentResult = false;
        h.toOpportunity();
        int slots = h.out.slots.size();

        h.child("음");
        assertThat(h.out.judgments).hasSize(1);
        assertThat(h.out.slots).as("교정 없이 같은 질문을 한 번 다시 안내한다").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);
        h.played();

        Signal end = h.child("음");

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.judgments).hasSize(2);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.ABSTAINED);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
    }

    @Test
    void anAbstainedJudgmentEventTakesTheSameReguidePathAndTheSecondOneClosesTheScene() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("음");
        int slots = h.out.slots.size();

        h.abstained("J2");
        assertThat(h.out.slots).hasSize(slots + 1);
        h.played();
        h.child("음");
        Signal end = h.abstained("J2");

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.ABSTAINED);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aLateOrForeignJudgmentResultIsIgnored() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("그거 줘");
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
    void generatedInstructionsCarryNoLabelsProbabilitiesCriteriaOrChildWordsAndFollowTheRolePolicy() {
        Harness h = new Harness(packageJson(GOAL, fullScript(SCENARIO)), Map.of());
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
        h.played();
        h.advance(Duration.ofSeconds(10));
        h.played();
        h.child(marker + "4");
        h.result("J5", "PARTIAL");
        h.played();
        h.child(marker + "5");
        h.result("J5", "FULL");
        h.played();
        // 다시 해 보기 2회를 채운 뒤라 변형은 제안하지 않는다. 변형 제안 지시문은 아래 둘째 진행에서 본다
        Harness offer = new Harness(packageJson(GOAL, fullScript(SCENARIO)), Map.of());
        offer.toOpportunity();
        offer.child(marker + "6");
        offer.result("J2", "FULL");
        offer.played();
        offer.played();
        offer.child(marker + "7");
        offer.result("J4", "UNCLEAR");

        List<Slot> generated = new java.util.ArrayList<>(h.out.generatedSlots());
        generated.addAll(offer.out.generatedSlots());
        assertThat(h.out.announces).as("승인 문장이 없으면 알림 능력을 부르지 않고 생성형으로 낸다").isEmpty();
        assertThat(h.out.generatedSlots()).hasSize(8);
        assertThat(offer.out.generatedSlots()).as("친구 답변 뒤 변형 제안과 같은 말 다시 제안").hasSize(3);
        List<String> forbidden = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE", "DELIVERED",
                "STILL_SHORT", "ACCEPT", "REFUSE", "UNCLEAR", "COPIED", "%", "확률", CRITERIA, marker, "판정", "J2", "J3", "J4", "J5");
        for (Slot slot : generated) {
            assertThat(slot.text()).as(slot.role()).isNotBlank().doesNotContain(forbidden.toArray(new String[0]));
            assertThat(slot.text().codePointCount(0, slot.text().length())).isLessThanOrEqualTo(500);
            assertThat(slot.text().chars().noneMatch(Character::isISOControl)).isTrue();
        }
        for (Slot slot : generated.stream().filter(slot -> slot.role().equals("PEER")).toList()) {
            assertThat(slot.text()).as("친구는 정답 · 모범 문장을 미리 말하지 않는다").contains("정답이나 모범 문장을 먼저 말하지 말고");
        }
        List<Slot> coach = generated.stream().filter(slot -> slot.role().equals("COACH")).toList();
        assertThat(coach).isNotEmpty();
        assertThat(coach.stream().filter(slot -> slot.text().contains("표현 예시를 하나만 들어도 된다")))
                .as("코치는 아이가 도움에 동의한 뒤에만 표현 예시").isNotEmpty();
        assertThat(coach.stream().filter(slot -> slot.text().contains("단서만 준다")))
                .as("첫 코치 도움은 예시 없이 단서만").isNotEmpty().allSatisfy(slot -> assertThat(slot.text()).doesNotContain("파란 상자를 줘"));
        assertThat(h.recorder.rows.toString() + h.recorder.helps + h.ext).doesNotContain(marker);
        assertThat(JsonCodec.shared().write(h.ext.practiceRecord())).doesNotContain(marker);
    }

    // ---- 중복 · 늦은 슬롯 사건 ----

    @Test
    void aSecondEndEventForTheSameSlotIsIgnored() {
        Harness h = minimal();
        h.activate();
        Slot intro = h.out.slots.getFirst();

        h.slotEvent(intro.correlationId(), Playback.COMPLETED, intro.speech(), intro.text());
        int slots = h.out.slots.size();
        Signal again = h.slotEvent(intro.correlationId(), Playback.COMPLETED, intro.speech(), intro.text());
        h.slotEvent(intro.correlationId(), Playback.FAILED, intro.speech(), intro.text());

        assertThat(again).isEqualTo(Signal.NONE);
        assertThat(h.out.slots).as("도입 종료 사건이 두 번 와도 기회 문장은 한 번만").hasSize(slots);
        assertThat(h.recorder.list()).hasSize(1);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aSlotTheExtensionClosedItselfIsHandledAtOnceSoLateEventsAreIgnored() {
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED, quiet(Duration.ofHours(1), Duration.ofMinutes(10)));
        h.activate();
        h.played();
        Slot opportunity = h.out.lastSlot();

        Signal end = h.advance(Duration.ofMinutes(10));

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.cancelledSpeech).containsExactly(opportunity.correlationId());
        assertThat(h.slotEvent(opportunity.correlationId(), Playback.INTERRUPTED, opportunity.speech(), opportunity.text()))
                .isEqualTo(Signal.NONE);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aSecondGuardrailBlockWrapsTheSceneAndTheFollowingFailedEventIsIgnored() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("그거 줘");
        h.result("J2", "PARTIAL");
        Slot reask = h.out.lastSlot();

        Signal end = h.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", reask.correlationId(),
                new GuardrailBlockedEvent(reask.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_PEER,
                        "BLOCK", true));
        Signal late = h.slotEvent(reask.correlationId(), Playback.FAILED, null, reask.text());

        assertThat(end).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(late).isEqualTo(Signal.NONE);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aGeneratedUtteranceTheOutputCannotMakeIsNotReportedAsPracticeCompleted() {
        Harness h = minimal();
        h.out.generatedResult = false;
        h.toOpportunity();
        h.child("그거 줘");

        Signal end = h.result("J2", "PARTIAL");

        assertThat(end).isEqualTo(Signal.OUTPUT_FAILED);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.PLAYBACK_FAILED, FactKind.VARIANT_SKIPPED);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void anApprovedSpeechTheOutputRefusesEndsAsTechnicalFailure() {
        Harness h = minimal();
        h.out.speakResult = false;

        Signal end = h.activate();

        assertThat(end).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.list()).isEmpty();
    }

    // ---- 역할 알림 (BR5.1 · BR5.4 · BR5.5) ----

    @ParameterizedTest
    @EnumSource(value = Playback.class, names = {"FAILED", "INTERRUPTED"})
    void aRoleSwitchNoticeThatDoesNotPlayLeavesTheRoleWithThePeerAndNeverStartsTheCoach(Playback status) {
        Harness h = minimal();
        h.toOpportunity();
        h.child("도와줘");

        h.result("J2", "HELP");
        Signal end = h.announcedNotice(status);

        assertThat(end).isEqualTo(status == Playback.FAILED ? Signal.OUTPUT_FAILED : Signal.LAST_GOAL);
        assertThat(h.out.generatedSlots()).as("코치 발화 없음").isEmpty();
        assertThat(h.recorder.helps).as("역할 전환 기록 없음").isEmpty();
        assertThat(h.ext.state().role()).isEqualTo(PracticeFlowTypes.FlowRole.PEER);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.COACH_SWITCH, FactKind.FOCUS_HELP);
    }

    @Test
    void whenTheOutputCannotAnnounceTheSwitchTheNoticeIsGeneratedAndTheCoachFollowsOnlyAfterItPlayed() {
        Harness h = minimal();
        h.out.announceResult = false;
        h.toOpportunity();
        h.child("도와줘");

        h.result("J2", "HELP");
        assertThat(h.out.announces).containsExactly("COACH");
        Slot notice = h.out.lastSlot();
        assertThat(notice.generated()).isTrue();
        assertThat(notice.role()).isEqualTo("COACH");
        assertThat(h.out.generatedSlots()).hasSize(1);

        h.played();

        assertThat(h.out.generatedSlots()).as("알림이 끝난 뒤에만 코치 발화").hasSize(2);
        assertThat(h.ext.state().role()).isEqualTo(PracticeFlowTypes.FlowRole.COACH);
    }

    @Test
    void aGeneratedNoticeThatIsBlockedTwiceWrapsWithoutSwitchingTheRole() {
        Harness h = minimal();
        h.out.announceResult = false;
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        Slot notice = h.out.lastSlot();

        Signal end = h.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", notice.correlationId(),
                new GuardrailBlockedEvent(notice.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_COACH,
                        "BLOCK", true));

        assertThat(end).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.ext.state().role()).isEqualTo(PracticeFlowTypes.FlowRole.PEER);
        assertThat(h.out.generatedSlots()).hasSize(1);
    }

    // ---- 변형 · 다음 목표 ----

    @Test
    void anAcceptedVariantRunsTheVariantSceneAsANewRowAndIsNotOfferedAnotherVariant() {
        Harness h = full();
        h.toOpportunity();
        h.child("그거 줘");
        h.result("J2", "FULL");
        h.played();
        Slot offer = h.out.lastSlot();
        assertThat(offer.generated()).isTrue();
        assertThat(offer.role()).isEqualTo("PEER");
        assertThat(h.ext.progress().stage()).isEqualTo("AFTER_CUE_RESPONSE");
        h.played();
        h.child("응");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J4);
        assertThat(h.out.lastJudgment().criteria()).as("J4에는 판정 기준을 싣지 않는다").isNull();

        h.result("J4", "ACCEPT");
        assertThat(h.out.lastSlot().text()).isEqualTo("동생이 책을 들고 있어.");
        assertThat(h.ext.progress().stage()).isEqualTo("NEW_SCENARIO_RESPONSE");
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo("동생이 말했어. 그거 줘.");
        h.played();
        h.child("그거 줘");
        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        List<SceneRunRecord> rows = h.recorder.list();
        assertThat(rows).extracting(SceneRunRecord::sceneKind).containsExactly(SceneKind.BASE, SceneKind.VARIANT);
        assertThat(rows.get(0).variantSkipReason()).isNull();
        assertThat(rows.get(0).liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.factKinds(rows.get(1))).containsExactly(FactKind.VARIANT_PLAYED);
        assertThat(h.out.generatedSlots()).as("두 장면의 친구 답변과 한 번의 변형 제안").hasSize(3);
        assertRecordMatchesTheContract(h.ext.practiceRecord());
    }

    @Test
    void aDeclinedVariantRecordsDeclinedAndEndsThePractice() {
        Harness h = full();
        h.toOpportunity();
        h.child("그거 줘");
        h.result("J2", "FULL");
        h.played();
        h.played();
        h.child("싫어");

        Signal end = h.result("J4", "REFUSE");

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.DECLINED);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.VARIANT_SKIPPED);
    }

    @Test
    void theNextGoalScriptIsRequestedInTheBackgroundAndTheNextGoalStartsWhenItArrives() throws Exception {
        Harness h = new Harness(twoGoalPackage(), APPROVED);
        when(h.http.send(eq(InternalHttpClient.Target.PRACTICE_SCENARIO), any()))
                .thenReturn(new InternalHttpClient.Response(200, minimalScript(SCENARIO_TWO)));
        h.toOpportunity();
        h.child("그거 줘");

        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Signal advanced = h.played();

        assertThat(advanced).as("첫 목표가 끝났고 다음 대본은 아직 안 닿았다").isEqualTo(Signal.NEXT_GOAL);
        assertThat(h.out.slots).hasSize(3);
        h.deliverPosted();
        assertThat(h.out.slots).as("대본이 닿으면 다음 목표의 도입").hasSize(4);
        assertThat(h.out.lastSlot().text()).isEqualTo(INTRO);
        assertThat(h.ext.progress().goalIndex()).isEqualTo(2);
        assertThat(h.ext.progress().goalCount()).isEqualTo(2);
        assertThat(h.ext.progress().goalName()).isEqualTo("거절하기");
        h.played();
        h.played();
        h.child("싫어");
        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Signal end = h.played();

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
        h.child("그거 줘");
        h.result("J2", "FULL");
        h.played();

        Signal end = h.deliverPosted();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.list()).as("거절된 목표는 행이 없다").hasSize(1);
        assertThat(h.failures).extracting(Throwable::getMessage).containsExactly("PRACTICE_SCRIPT_REQUEST_FAILED");
    }

    @Test
    void aRejectedFirstScriptSkipsTheFirstGoalAndEndsAtOnce() {
        String broken = "{\"scenarioId\":\"" + SCENARIO + "\",\"kind\":\"ORIGINAL\",\"scriptVersion\":\"scenario-script.v1\",\"scenes\":[]}";
        Harness h = new Harness(packageJson(GOAL, broken), APPROVED);

        Signal end = h.activate();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.slots).isEmpty();
        assertThat(h.failures).hasSize(1);
    }

    // ---- 끼어든 전사 · 종료 · 기록 실패 · 진행 표시 ----

    @Test
    void aChildAnswerGivenWhileTheCoachSpeaksIsJudgedAsTheRetryAnswerOnceTheCoachWasInterrupted() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        h.announcedNotice(Playback.COMPLETED);
        int judgments = h.out.judgments.size();

        h.child("파란 상자");
        assertThat(h.out.judgments).as("코치 발화 중에는 판정하지 않는다").hasSize(judgments);
        h.end(Playback.INTERRUPTED);

        assertThat(h.out.judgments).hasSize(judgments + 1);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        assertThat(h.ext.state().retryCount()).isEqualTo(1);
    }

    @Test
    void anAnswerThatArrivesWhileTheQuestionIsStillPlayingIsJudgedWhenThePlaybackEnds() {
        Harness h = minimal();
        h.activate();
        h.played();

        h.child("그거 줘");
        assertThat(h.out.judgments).isEmpty();
        h.played();

        assertThat(h.out.judgments).hasSize(1);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.timers).doesNotContainKey("no-response");
    }

    @Test
    void aRestRequestStopsTheSceneHoldsTheOutputAndLeavesTheEndingToTheExecutor() {
        Harness h = minimal();
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
        Harness h = minimal();
        h.toOpportunity();
        int calls = h.out.calls;

        h.ext.onStopping();
        h.ext.onStopping();

        assertThat(h.out.calls).as("종료 중에는 능력 호출이 거절되므로 부르지 않는다").isEqualTo(calls);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.END_REQUEST);
        assertThat(h.child("그거 줘")).isEqualTo(Signal.NONE);
        assertThat(h.advance(Duration.ofMinutes(11))).isEqualTo(Signal.NONE);
        h.ext.onSettle(null);
        assertThat(h.out.calls).isEqualTo(calls);
    }

    @Test
    void aFailingRecorderNeverStopsThePracticeAndOnlyAFixedAppErrorIsReported() {
        Harness h = minimal();
        h.recorder.failOnSceneStarted = true;
        h.toOpportunity();
        h.child("그거 줘");

        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.failures).hasSize(1);
        assertThat(h.failures.getFirst()).hasMessage("기록 실패(시험)");
    }

    @Test
    void theProgressStageFoldsTheNewStagesIntoTheSixGuardianValues() {
        Harness h = minimal();
        assertThat(h.ext.progress()).as("시작 전").isNull();
        h.activate();
        assertThat(h.ext.progress().stage()).isEqualTo("SCENARIO_INTRO");
        assertThat(h.ext.progress().goalIndex()).isEqualTo(1);
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
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        assertThat(h.ext.progress().stage()).isEqualTo("AFTER_CUE_RESPONSE");
        h.child("파란 상자를 줘");
        h.result("J5", "FULL");
        h.announcedNotice(Playback.COMPLETED);

        assertThat(h.ext.progress().stage()).as("CLOSING은 직전 값을 유지한다").isEqualTo("AFTER_CUE_RESPONSE");
    }

    @Test
    void theFirstNoResponseExpiryReguidesEveryInputStageWithItsOwnRoleAndTheSecondDeclinesTheVariant() {
        Harness h = full();
        h.toOpportunity();
        h.child("가");
        h.result("J2", "PARTIAL");
        h.played();
        int slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("되묻기 단계 재안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().role()).isEqualTo("PEER");
        assertThat(h.out.lastSlot().text()).contains("같은 말투로 한 번 더");
        h.played();
        h.child("나");
        h.result("J3", "STILL_SHORT");
        h.played();
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("도움 제안 단계 재안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).contains("같은 말로 한 번 더");
        h.played();
        h.child("다");
        h.result("J4", "ACCEPT");
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("다시 해 보기 단계 재안내는 코치가 한다").hasSize(slots + 1);
        assertThat(h.out.lastSlot().role()).isEqualTo("COACH");
        assertThat(h.out.lastSlot().text()).contains("자기 말로 다시 한 번 해 보자고");
        h.played();
        h.child("라");
        h.result("J5", "FULL");
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        assertThat(h.out.lastSlot().text()).as("변형 제안").contains("새 상황");
        h.played();
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("변형 제안 단계 재안내").hasSize(slots + 1);
        h.played();

        Signal end = h.advance(Duration.ofSeconds(10));

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.DECLINED);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
        assertThat(h.recorder.factKinds(row)).endsWith(FactKind.VARIANT_SKIPPED);
    }

    @Test
    void theGoalClockClosesAnOpenVariantOfferWithTimeLimit() {
        Harness h = new Harness(packageJson(GOAL, fullScript(SCENARIO)), APPROVED, quiet(Duration.ofHours(1), Duration.ofMinutes(10)));
        h.toOpportunity();
        h.child("그거 줘");
        h.result("J2", "FULL");
        h.played();

        Signal end = h.advance(Duration.ofMinutes(10));

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.TIME_LIMIT);
    }

    @Test
    void theFocusClockCancelsTheSpeakingCoachAndIgnoresItsLateEnd() {
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED, quiet(Duration.ofSeconds(90), Duration.ofHours(2)));
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        h.announcedNotice(Playback.COMPLETED);
        Slot coach = h.out.lastSlot();

        h.advance(Duration.ofSeconds(90));

        assertThat(h.out.cancelledSpeech).containsExactly(coach.correlationId());
        assertThat(h.slotEvent(coach.correlationId(), Playback.INTERRUPTED, null, coach.text())).isEqualTo(Signal.NONE);
        assertThat(h.announcedNotice(Playback.COMPLETED)).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aJudgmentOpenWhenTheFocusClockFiresIsDroppedAndItsLateResultIsIgnored() {
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED, quiet(Duration.ofSeconds(90), Duration.ofHours(2)));
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        h.child("파란 상자를 줘");
        UUID open = h.out.lastJudgment().correlationId();

        h.advance(Duration.ofSeconds(90));
        Signal late = h.result(open, "J5", "FULL");

        assertThat(late).isEqualTo(Signal.NONE);
        assertThat(h.recorder.only().liveTiming()).as("한도로 닫힌 장면은 늦은 결과로 바뀌지 않는다").isEqualTo(LiveTiming.NONE);
    }

    @Test
    void anAnnouncedNoticeThatNeverEndsFailsWhenItsSafetyClockExpiresAndTheRoleStaysPeer() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("도와줘");
        h.result("J2", "HELP");
        assertThat(h.out.scheduleLog).contains("slot-guard-notice:PT20S");

        Signal end = h.advance(Duration.ofSeconds(20));

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED, FactKind.VARIANT_SKIPPED);
        assertThat(h.out.generatedSlots()).isEmpty();
    }

    @Test
    void aLongJudgmentCriteriaIsCutForCoreFunctionAndSentWholeAsTheCriteria() {
        String criteria = "가".repeat(600);
        String json = "{\"goals\":[{\"goalSelectionId\":\"" + GOAL + "\",\"order\":1,\"displayName\":\"부탁하기\","
                + "\"judgmentCriteria\":\"" + criteria + "\",\"firstScenario\":" + minimalScript(SCENARIO) + "}]}";
        Harness h = new Harness(json, APPROVED);
        h.toOpportunity();

        h.child("그거 줘");

        assertThat(((String) h.out.lastJudgment().criteria().get("coreFunction"))).hasSize(500);
        assertThat(((String) h.out.lastJudgment().criteria().get("judgmentCriteria"))).hasSize(600);
    }

    @Test
    void aMalformedJudgmentEventIsHandledWithoutStallingThePractice() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("가");
        h.event(ExtensionEvent.Kind.JUDGMENT_RESULT, "judgment", h.out.lastJudgment().correlationId(),
                Map.of("point", "J9", "firstOption", "FULL"));

        assertThat(h.failures).extracting(Throwable::getMessage).containsExactly("PRACTICE_FLOW_UNEXPECTED_INPUT");
        assertThat(h.out.timers).as("무응답 시계를 다시 건다").containsKey("no-response");

        h.child("나");
        int slots = h.out.slots.size();
        h.event(ExtensionEvent.Kind.JUDGMENT_RESULT, "judgment", h.out.lastJudgment().correlationId(), "모양이 틀림");
        assertThat(h.out.slots).as("모양이 틀린 결과는 보류처럼 한 번 재안내").hasSize(slots + 1);
        h.played();
        h.child("다");
        Signal end = h.event(ExtensionEvent.Kind.JUDGMENT_ABSTAINED, "judgment", h.out.lastJudgment().correlationId(), "모양이 틀림");

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
    }

    @Test
    void strayEventsTimersAndUnknownSlotsAreIgnoredWithoutChangingTheStage() {
        Harness h = minimal();
        h.activate();
        h.played();
        h.end(Playback.STARTED);
        assertThat(h.out.timers).as("재생 시작 사실은 종료가 아니다").doesNotContainKey("no-response");

        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "slot-guard-" + UUID.randomUUID(), null, null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "mystery", null, null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.TIMER_FIRED, "focus-max", null, null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT, "other", null, "x")).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.ASYNC_RESULT, "practice-script", null, "not a result")).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.PLAYBACK_FACT, "u5b-playback", null, "bad")).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.PLAYBACK_DONE, "code", UUID.randomUUID(), null)).isEqualTo(Signal.NONE);
        assertThat(h.event(ExtensionEvent.Kind.HELD_OUTPUT_TRANSCRIPT, "judgment", null, Map.of("text", "x"))).isEqualTo(Signal.NONE);
        assertThat(h.slotEvent(UUID.randomUUID(), Playback.COMPLETED, null, "모르는 슬롯")).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);

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
        h.child("그거 줘");

        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Signal end = h.played();

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
        Harness h = minimal();
        PracticeConversation withNone = new PracticeConversation(UUID.randomUUID(), UUID.randomUUID(), h.clock,
                PracticeFlowTestKit.defaults(), new PracticeScriptRequester(h.http, Runnable::run, Duration.ofSeconds(1)), none,
                h.failures::add);

        assertThat(withNone.onActive(h.context, h.out)).isEqualTo(Signal.NONE);
        assertThat(withNone.practiceRecord()).isNull();
        assertThat(h.out.slots).hasSize(1);
    }

    // ---- 시작과 구조 ----

    @Test
    void achievementWaitsForOneGeneratedFriendReplyAndItsPlaybackBeforeFinishing() {
        Harness h = minimal(); h.toOpportunity(); h.child("파란 상자를 줘");
        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Slot reply = h.out.lastSlot();
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PEER_REPLY);
        assertThat(reply.generated()).isTrue();
        assertThat(reply.role()).isEqualTo("PEER");
        assertThat(reply.text()).contains("직접 답하며", "추가 답변을 요구하지 않는다");
        assertThat(h.out.timers).doesNotContainKey("no-response");
        int judgments = h.out.judgments.size();
        h.child("응"); // Acknowledgement during the closing reply must not restart judgment.
        assertThat(h.out.judgments).hasSize(judgments);
        assertThat(h.end(Playback.STARTED)).isEqualTo(Signal.NONE);
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.slotEvent(reply.correlationId(), Playback.COMPLETED, reply.speech(), "응"))
                .isEqualTo(Signal.NONE);
        assertThat(h.out.generatedSlots()).hasSize(1);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
    }

    @ParameterizedTest
    @EnumSource(value = Playback.class, names = {"FAILED", "INTERRUPTED"})
    void failedOrCutOffFriendReplyCannotBeReportedAsNormalCompletion(Playback playback) {
        Harness h = minimal(); h.toOpportunity(); h.child("파란 상자를 줘"); h.result("J2", "FULL");
        assertThat(h.end(playback)).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PLAYBACK_FAILED);
        assertThat(h.out.generatedSlots()).hasSize(1);
    }

    @Test
    void jevBlockedFriendReplyIsNotRetriedAsApprovedSpeechOrOfferedAsAnotherScene() {
        Harness h = full(); h.toOpportunity(); h.child("파란 상자를 줘"); h.result("J2", "FULL");
        Slot reply = h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", reply.correlationId(),
                new GuardrailBlockedEvent(reply.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_PEER,
                        "BLOCK", true))).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.out.generatedSlots()).hasSize(1);
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
    }

    @Test
    void silentFriendReplyTimesOutInsteadOfHangingOrLooping() {
        Harness h = minimal(); h.toOpportunity(); h.child("파란 상자를 줘"); h.result("J2", "FULL");
        assertThat(h.advance(Duration.ofSeconds(20))).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.out.generatedSlots()).hasSize(1);
    }

    @Test
    void childStopCancelsTheClosingFriendReplyImmediately() {
        Harness h = minimal(); h.toOpportunity(); h.child("파란 상자를 줘"); h.result("J2", "FULL");
        Slot reply = h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.REST_REQUESTED, "judgment", null, null)).isEqualTo(Signal.NONE);
        assertThat(h.out.holds).contains(true);
        assertThat(h.out.cancelledSpeech).contains(reply.correlationId());
        assertThat(h.recorder.only().endedAt()).isNotNull();
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.END_OR_REST);
        assertThat(h.slotEvent(reply.correlationId(), Playback.COMPLETED, reply.speech(), "응"))
                .isEqualTo(Signal.NONE);
        assertThat(h.out.generatedSlots()).hasSize(1);
    }

    @Test
    void externalStopDuringTheFriendReplyStillClosesTheStoredScene() {
        Harness h = minimal(); h.toOpportunity(); h.child("파란 상자를 줘"); h.result("J2", "FULL");
        assertThat(h.recorder.only().endedAt()).isNull();
        h.ext.onStopping();
        assertThat(h.recorder.only().endedAt()).isNotNull();
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.END_REQUEST);
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
        assertThatThrownBy(blank::activate).isInstanceOf(PracticeScriptException.class);
        assertThatThrownBy(() -> new Harness("not json", APPROVED).activate()).isInstanceOf(PracticeScriptException.class);

        Harness once = minimal();
        once.activate();
        int slots = once.out.slots.size();
        assertThat(once.activate()).as("두 번째 onActive는 아무것도 하지 않는다").isEqualTo(Signal.NONE);
        assertThat(once.out.slots).hasSize(slots);
        assertThat(once.ext.controlsOutput()).isTrue();
        assertThat(once.ext.toString()).doesNotContain(INTRO);
    }
}
