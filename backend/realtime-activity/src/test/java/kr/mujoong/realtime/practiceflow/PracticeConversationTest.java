package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.fullScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.sceneScript;
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
        return new PracticeFlowSettings(hour, focusMax, goalLimit, hour, 2, 1);
    }

    private static void assertRecordMatchesTheContract(Object practice) {
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(practice))).isEmpty();
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
        assertThat(h.result("J3", "DELIVERED")).as("친구 반응(D_CONTINUE)이 끝나기 전에는 정리하지 않는다").isEqualTo(Signal.NONE);
        Slot reaction = h.out.lastSlot();
        assertThat(reaction.generated()).isTrue();
        assertThat(reaction.role()).isEqualTo("PEER");
        assertThat(reaction.text()).contains("방금 한 말에 자연스럽게 반응해", "칭찬하거나 고쳐 주지 않고");
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

        assertThat(end).as("도입이 끝내 안 들려도 연습은 멈추지 않고 정리한다").isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.list()).as("도입 재생이 성공하지 못했으니 행이 없다").isEmpty();
        assertThat(h.out.cancelledSpeech).containsExactly(h.out.slots.getFirst().correlationId());
    }

    // ---- 재시도 한도 · 코치 예시 ----

    @Test
    void twoRetriesAreAllowedAndTheThirdPartialClosesAsFocusLimitWithAFrameFirstAndTheExampleInTheSecondCoachRound() {
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
        assertThat(coach.get(0).text()).as("첫 도움은 요점 + 핵심 낱말을 비운 빈칸 예문(기준 연습 대화 D_FOCUS, 빈칸 예문이 없는 옛 대본)")
                .contains("이 요점 하나만 쉬운 말로 짧게 알려 준다: 어느 것인지 말하기", "그대로 말하지 말고", "'뭐뭐'로 비운 빈칸 예문",
                        "'빈칸 예문'이라는 말은 소리 내어 하지 않는다")
                .doesNotContain("표현 예시를 하나만 들어도 된다", "말틀");
        assertThat(coach.get(1).text()).as("다시 해 보기 뒤의 도움은 대본의 표현 예시 하나")
                .contains("표현 예시를 하나만 들어도 된다: 파란 상자를 줘").doesNotContain("말틀");
        assertThat(h.out.announces).as("둘째 한도에서 정리하며 복귀 알림").containsExactly("COACH", "PEER");
        h.announcedNotice(Playback.COMPLETED);
        assertThat(h.out.lastSlot().role()).as("복귀 알림 뒤 친구가 아이 말에 반응한다(D_RESUME)").isEqualTo("PEER");
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.retryCount()).isEqualTo(2);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(h.recorder.factKinds(row)).contains(FactKind.EXPRESSION_EXAMPLE);
        assertThat(h.recorder.helps).filteredOn(help -> help.kind() == HelpKind.FOCUS_HELP)
                .extracting(PracticeFlowTypes.HelpRecord::exampleGiven).as("빈칸 예문도 표현 예시를 준 도움으로 남긴다").containsExactly(true, true);
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
        Slot reaction = h.out.lastSlot();
        assertThat(reaction.generated()).isTrue();
        assertThat(reaction.role()).as("복귀 뒤 친구 반응(D_RESUME)").isEqualTo("PEER");
        assertThat(reaction.text()).contains("방금 한 말에 자연스럽게 반응해");
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

    // ---- 머뭇거림(확신이 모자란 보류) → 도움 제안 → 도움 → 도움 뒤 반응 (docs/practice-live-fixes.md) ----

    @Test
    void aHesitantAnswerWhoseUnsureLeadingOptionIsHelpIsOfferedHelpAndTheHelpedRetryIsRecordedAfterHelp() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("음, 잘 모르겠어.");
        int slots = h.out.slots.size();

        h.unsure("J2", "LOW_CONFIDENCE", "HELP");
        assertThat(h.ext.state().phase()).as("장면을 닫지 않고 도움 제안으로 간다").isEqualTo(FlowState.Phase.HELP_OFFER);
        assertThat(h.out.slots).hasSize(slots + 1);
        Slot offer = h.out.lastSlot();
        assertThat(offer.generated()).as("승인 문장(HELP_OFFER)이 없으면 생성형").isTrue();
        assertThat(offer.role()).as("도움 제안은 친구가 아니라 무중이 역할 밖에서 한다(D_OFFER_HELP)").isEqualTo("COACH");
        assertThat(offer.text()).contains("너는 무중이다", "친구 역할에서 잠깐 나와", "도와줘도 되는지")
                .doesNotContain("HELP", "LOW_CONFIDENCE", "친구 역할이다");
        assertThat(h.out.announces).as("동의 전에는 코치로 바꾸지 않는다").isEmpty();
        h.played();

        h.child("응, 도와줘.");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J4);
        h.result("J4", "ACCEPT");
        assertThat(h.out.announces).containsExactly("COACH");
        h.announcedNotice(Playback.COMPLETED);
        assertThat(h.out.lastSlot().role()).isEqualTo("COACH");
        h.played();
        h.child("파란 상자를 줘");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        h.result("J5", "FULL");
        h.announcedNotice(Playback.COMPLETED);
        assertThat(h.out.lastSlot().role()).isEqualTo("PEER");
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.judgments).extracting(PracticeFlowTestKit.Judgment::point)
                .containsExactly(JudgmentPoint.J2, JudgmentPoint.J4, JudgmentPoint.J5);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
        assertThat(h.recorder.factKinds(row)).containsSubsequence(FactKind.HELP_ACCEPTED, FactKind.COACH_SWITCH, FactKind.FOCUS_HELP,
                FactKind.COACH_RETURN, FactKind.ORIGINAL_SCENE_RETURN);
        assertThat(h.recorder.helps).extracting(PracticeFlowTypes.HelpRecord::kind)
                .containsExactly(HelpKind.ROLE_SWITCH, HelpKind.FOCUS_HELP, HelpKind.ROLE_RETURN);
        assertThat(h.recorder.helps.getFirst().consentAnswer()).as("도움 동의는 J4 수락으로 남는다(보류를 도움 요청으로 적지 않는다)")
                .isEqualTo(PracticeFlowTypes.ConsentAnswer.ACCEPT);
        assertThat(h.recorder.helps.get(1).exampleGiven()).as("첫 코치 발화는 예시 없이 단서만").isFalse();
        assertThat(h.failures).isEmpty();
        assertRecordMatchesTheContract(h.ext.practiceRecord());
    }

    @Test
    void twoUnsureAbstainsAtTheSameOpportunityOfferHelpInsteadOfClosingAndADeclinedOfferIsNotMarkedAbstained() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("음...");
        int slots = h.out.slots.size();

        h.unsure("J2", "LOW_CONFIDENCE", "PARTIAL");
        assertThat(h.out.slots).as("첫 보류는 지금처럼 같은 기회 문장을 다시 안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);
        h.played();
        h.child("음, 잘 모르겠어.");
        h.unsure("J2", "LOW_MARGIN", "IRRELEVANT");
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.HELP_OFFER);
        assertThat(h.out.lastSlot().text()).contains("도와줘도 되는지");
        h.played();

        h.child("아니, 괜찮아.");
        Signal end = h.result("J4", "REFUSE");

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.liveTiming()).as("보류로 닫히지 않았다").isEqualTo(LiveTiming.NONE);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.HELP_DECLINED, FactKind.VARIANT_SKIPPED);
        assertThat(h.recorder.helps).as("거절하면 도움 기록이 없다").isEmpty();
        assertRecordMatchesTheContract(h.ext.practiceRecord());
    }

    @Test
    void aSlowOrFailedJudgeAtTheOpportunityStillClosesAbstainedEvenWhenItLeanedToHelp() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("음");
        h.unsure("J2", "TIMEOUT", "HELP");
        h.played();
        h.child("음");
        Signal end = h.unsure("J2", "TIMEOUT", "HELP");

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.judgments).extracting(PracticeFlowTestKit.Judgment::point).containsOnly(JudgmentPoint.J2);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.ABSTAINED);
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
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.result(UUID.randomUUID(), "J2", "PARTIAL")).as("닫힌 판정 뒤 사건").isEqualTo(Signal.NONE);
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
        assertThat(h.out.generatedSlots()).as("되묻기 · 도움 제안 · 알림 · 코치 · 재안내 · 코치 · 복귀 알림 · 친구 반응").hasSize(8);
        assertThat(offer.out.generatedSlots()).as("친구 반응 · 변형 제안 · 같은 말 다시 제안").hasSize(3);
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
        assertThat(coach.stream().filter(slot -> slot.text().contains("'뭐뭐'로 비운 빈칸 예문")))
                .as("동의 뒤 첫 코치 도움은 빈칸 예문(예시를 그대로 말하지 않는다)").hasSize(1)
                .allSatisfy(slot -> assertThat(slot.text()).contains("그대로 말하지 말고"));
        List<String> praise = List.of("잘 마쳤", "잘했", "훌륭", "멋지", "최고", "정답이야", "맞았");
        for (Slot slot : generated) {
            assertThat(slot.text()).as("칭찬 · 채점 말을 지시문이 미리 넣지 않는다").doesNotContain(praise.toArray(new String[0]));
        }
        assertThat(generated).filteredOn(slot -> slot.text().contains("도와줘도 되는지"))
                .as("도움 제안은 무중(코치 슬롯)이 한다").isNotEmpty().allSatisfy(slot -> assertThat(slot.role()).isEqualTo("COACH"));
        assertThat(generated).filteredOn(slot -> slot.text().contains("이어서 해 볼지"))
                .as("옛 대본의 변형 제안도 무중(코치 슬롯)이 한다").hasSize(2).allSatisfy(slot -> assertThat(slot.role()).isEqualTo("COACH"));
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

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(late).isEqualTo(Signal.NONE);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aGeneratedUtteranceTheOutputCannotMakeFailsItsSlotAndThePracticeContinues() {
        Harness h = minimal();
        h.out.generatedResult = false;
        h.toOpportunity();
        h.child("그거 줘");

        Signal end = h.result("J2", "PARTIAL");

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.PLAYBACK_FAILED, FactKind.VARIANT_SKIPPED);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void anApprovedSpeechTheOutputRefusesSkipsTheSceneButTheGoalListStillEnds() {
        Harness h = minimal();
        h.out.speakResult = false;

        Signal end = h.activate();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
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

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
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

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
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
        assertThat(h.out.lastSlot().role()).as("친구 반응 먼저").isEqualTo("PEER");
        h.played();
        Slot offer = h.out.lastSlot();
        assertThat(offer.generated()).as("옛 대본(첫 대사 없음)의 변형 제안은 무중의 생성형 질문").isTrue();
        assertThat(offer.role()).isEqualTo("COACH");
        assertThat(offer.text()).contains("너는 무중이다", "이어서 해 볼지").doesNotContain("동생이 책을 들고 있어.");
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
        h.result("J2", "FULL");
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        List<SceneRunRecord> rows = h.recorder.list();
        assertThat(rows).extracting(SceneRunRecord::sceneKind).containsExactly(SceneKind.BASE, SceneKind.VARIANT);
        assertThat(rows.get(0).variantSkipReason()).isNull();
        assertThat(rows.get(0).liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.factKinds(rows.get(1))).containsExactly(FactKind.VARIANT_PLAYED);
        assertThat(h.out.generatedSlots()).extracting(Slot::role).as("친구 반응 · 변형 제안 · 변형 장면의 친구 반응(다시 제안하지 않는다)")
                .containsExactly("PEER", "COACH", "PEER");
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
        h.result("J2", "FULL");

        Signal advanced = h.played();

        assertThat(advanced).as("첫 목표가 끝났고 다음 대본은 아직 안 닿았다").isEqualTo(Signal.NEXT_GOAL);
        assertThat(h.out.slots).as("도입 · 기회 문장 · 친구 반응").hasSize(3);
        h.deliverPosted();
        assertThat(h.out.slots).as("대본이 닿으면 다음 목표의 도입").hasSize(4);
        assertThat(h.out.lastSlot().text()).isEqualTo(INTRO);
        assertThat(h.ext.progress().goalIndex()).isEqualTo(2);
        assertThat(h.ext.progress().goalCount()).isEqualTo(2);
        assertThat(h.ext.progress().goalName()).isEqualTo("거절하기");
        h.played();
        h.played();
        h.child("싫어");
        h.result("J2", "FULL");
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
        assertThat(h.played()).as("친구 반응 뒤 다음 대본을 기다린다").isEqualTo(Signal.NEXT_GOAL);

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
        h.result("J2", "FULL");

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
        assertThat(h.ext.progress().stage()).as("친구 반응은 직전 값을 유지한다").isEqualTo("AFTER_CUE_RESPONSE");
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);

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
        assertThat(h.out.lastSlot().role()).as("복귀 알림 뒤 친구 반응").isEqualTo("PEER");
        assertThat(h.out.lastSlot().text()).contains("방금 한 말에 자연스럽게 반응해");
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("친구 반응 중에는 무응답 재안내를 하지 않는다").hasSize(slots);
        h.played();
        assertThat(h.out.lastSlot().role()).as("변형 제안은 무중").isEqualTo("COACH");
        assertThat(h.out.lastSlot().text()).as("변형 제안").contains("이어서 해 볼지");
        h.played();
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("변형 제안 단계 재안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).contains("이어서 해 볼지", "같은 말로 한 번 더");
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
        h.played();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.VARIANT_OFFER);

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
        h.result("J2", "FULL");

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

    // ---- 도입이 기회 문장을 담는 대본(core 규칙): 기회 문장은 입력 대기까지 한 번만 ----

    private static final String CARRYING_INTRO = "블록 놀이 중이야. 친구가 멈추려 해. 이제 그만할래.";
    private static final String STOP = "이제 그만할래.";

    private static Harness carrying(String partnerRole) {
        return new Harness(packageJson(GOAL, sceneScript(SCENARIO, CARRYING_INTRO, STOP, partnerRole)), APPROVED);
    }

    private static long spokenTimes(Harness h, String sentence) {
        String target = SceneIntro.normalize(sentence);
        return h.out.slots.stream().filter(slot -> !slot.generated())
                .filter(slot -> SceneIntro.normalize(slot.text()).contains(target)).count();
    }

    @Test
    void anIntroThatCarriesTheOpportunitySentenceIsPlayedWholeAndTheSentenceIsNotPlayedAgainBeforeInput() {
        Harness h = carrying("친한 친구");

        h.activate();
        assertThat(h.out.lastSlot().text()).as("도입은 승인 원문 그대로(자르지 않는다)").isEqualTo(CARRYING_INTRO);
        assertThat(h.out.lastSlot().speech().matches(CARRYING_INTRO.replace(" ", ""))).isTrue();
        int slots = h.out.slots.size();
        assertThat(h.out.timers).doesNotContainKey("no-response");
        h.played();

        assertThat(h.out.slots).as("기회 문장을 따로 재생하지 않는다").hasSize(slots);
        assertThat(h.out.timers).as("곧바로 아이 말을 기다린다").containsKey("no-response");
        assertThat(h.ext.progress().stage()).isEqualTo("FIRST_RESPONSE");
        h.child("그래 쉬자");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(spokenTimes(h, STOP)).as("입력 대기까지 기회 문장 재생 1회").isEqualTo(1);
        assertThat(h.out.slots.stream().filter(slot -> !slot.generated()).map(Slot::text))
                .as("승인 발화는 대본 문장 원문뿐").containsOnly(CARRYING_INTRO);
        assertThat(h.recorder.only().sceneKind()).isEqualTo(SceneKind.BASE);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void anInterruptedCarryingIntroStillPlaysTheOpportunitySentenceSeparately() {
        Harness h = carrying("친한 친구");
        h.activate();

        h.end(Playback.INTERRUPTED);

        assertThat(h.out.lastSlot().text()).as("끊긴 도입 뒤에는 기회 문장을 못 들었을 수 있다").isEqualTo(STOP);
        assertThat(h.out.timers).doesNotContainKey("no-response");
        h.played();
        assertThat(h.out.timers).containsKey("no-response");
        h.child("그래 쉬자");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
    }

    @Test
    void anAnswerThatArrivesBeforeACarryingIntroFinishesIsJudgedAsTheOpportunityAnswerOnceTheIntroPlayed() {
        Harness h = carrying("친한 친구");
        h.activate();

        h.child("그래 쉬자");
        assertThat(h.out.judgments).as("도입 재생 중에는 판정하지 않는다").isEmpty();
        int slots = h.out.slots.size();
        h.played();

        assertThat(h.out.judgments).hasSize(1);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.lastJudgment().seq()).isEqualTo(h.seq);
        assertThat(h.out.slots).as("기회 문장을 따로 재생하지 않는다").hasSize(slots);
        assertThat(h.out.timers).as("판정 중이라 무응답으로 기다리지 않는다").doesNotContainKey("no-response");
        assertThat(spokenTimes(h, STOP)).isEqualTo(1);
    }

    @Test
    void onlyTheLastUtteranceHeardDuringACarryingIntroIsJudged() {
        Harness h = carrying("친한 친구");
        h.activate();
        h.child("음");
        h.child("그래 쉬자");
        int last = h.seq;

        h.played();

        assertThat(h.out.judgments).hasSize(1);
        assertThat(h.out.lastJudgment().seq()).isEqualTo(last);
    }

    @Test
    void anUtteranceDuringAnInterruptedCarryingIntroIsNotTakenAsTheAnswer() {
        Harness h = carrying("친한 친구");
        h.activate();
        h.child("그래");

        h.end(Playback.INTERRUPTED);
        assertThat(h.out.lastSlot().text()).isEqualTo(STOP);
        h.played();

        assertThat(h.out.judgments).as("기회 문장을 다시 듣기 전의 말은 대답으로 보지 않는다").isEmpty();
        assertThat(h.out.timers).containsKey("no-response");
    }

    @Test
    void anUtteranceDuringAnIntroWithoutTheSentenceIsStillIgnored() {
        Harness h = minimal();
        h.activate();
        h.child("그래");

        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);
        h.played();

        assertThat(h.out.judgments).isEmpty();
        assertThat(h.out.timers).containsKey("no-response");
    }

    @Test
    void theNoResponseReguideStillReplaysTheOpportunitySentenceAfterACarryingIntro() {
        Harness h = carrying("친한 친구");
        h.activate();
        h.played();
        int slots = h.out.slots.size();

        h.advance(Duration.ofSeconds(10));

        assertThat(h.out.slots).hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).as("무응답 재안내는 의도된 재생").isEqualTo(STOP);
        assertThat(h.out.lastSlot().generated()).isFalse();
    }

    @Test
    void aPlainIntroWithoutTheSentenceKeepsTheSeparateOpportunityPlayback() {
        Harness h = minimal();
        h.activate();
        h.played();

        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);
        assertThat(spokenTimes(h, OPPORTUNITY)).isEqualTo(1);
    }

    // ---- 친구 역할 이름(partnerRole = 짧은 역할 이름, 금지 규칙은 고정 지시) ----

    @Test
    void aLabelledScriptNamesThePartnerInThePeerInstructionAndKeepsEveryPeerRule() {
        Harness h = carrying("친한 친구");
        h.activate();
        h.played();
        h.child("그래");

        h.result("J2", "PARTIAL");

        Slot reask = h.out.lastSlot();
        assertThat(reask.generated()).isTrue();
        assertThat(reask.role()).isEqualTo("PEER");
        assertThat(reask.text()).contains("너는 아이의 친한 친구 역할이다", "아이 대신 대답하지 않는다",
                "정답이나 모범 문장을 먼저 말하지 말고, 아이의 말을 고쳐 주거나 평가하지 않는다.");
        assertThat(h.out.slots.getFirst().speech().partnerRole()).as("장면 설명은 무중이 역할 밖에서 한다(말하는 사람 표시)")
                .isEqualTo(RolePolicy.NARRATOR_LABEL);
    }

    @Test
    void anOldInstructionValueInPartnerRoleBecomesFriendInInstructionsAndApprovedSpeech() {
        String oldInstruction = "제시된 상황의 상대 역할을 맡아. 아이의 답을 대신 말하거나 평가하지 마.";
        Harness h = new Harness(packageJson(GOAL, sceneScript(SCENARIO, INTRO, OPPORTUNITY, oldInstruction)), APPROVED);
        h.activate();
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(OPPORTUNITY);
        assertThat(h.out.lastSlot().speech().partnerRole()).as("친구 대사의 말하는 사람 표시").isEqualTo("친구");
        h.played();
        h.child("그래");

        h.result("J2", "PARTIAL");

        assertThat(h.out.lastSlot().text()).contains("너는 아이의 친구 역할이다", "아이 대신 대답하지 않는다")
                .doesNotContain("제시된 상황의");
        assertThat(h.out.slots.getFirst().speech().partnerRole()).isEqualTo(RolePolicy.NARRATOR_LABEL);
    }

    @Test
    void peerNameFallsBackForBlankOverlongOrSentenceLikeValuesOnly() {
        assertThat(RolePolicy.peerName("같은 반 친구")).isEqualTo("같은 반 친구");
        assertThat(RolePolicy.peerName("  동생 ")).isEqualTo("동생");
        assertThat(RolePolicy.peerName("가".repeat(20))).isEqualTo("가".repeat(20));
        for (String bad : new String[] {null, "", "  ", "가".repeat(21), "친구야.", "누구?", "친구!", "친구。", "친\u0007구"}) {
            assertThat(RolePolicy.peerName(bad)).as(String.valueOf(bad)).isEqualTo("친구");
        }
        assertThat(RolePolicy.NARRATOR_LABEL).as("역할 밖 설명의 말하는 사람 표시").isEqualTo("무중");
    }

    // ---- 친구 첫 대사(partnerOpener): 무중의 설명 → 친구 첫 대사 → 친구 기회 문장 (docs/practice-dialogue-standard.md) ----

    private static final String NARRATION = "무중이랑 상자 정리를 한다고 생각해 보자. 무중이가 같은 반 친구야. 네가 친구한테 파란 상자를 달라고 했어.";
    private static final String OPENER = "알았어, 잠깐만!";
    private static final String HANDOVER = "자, 여기 빨간 상자!";

    private static Harness withOpener() {
        return new Harness(packageJson(GOAL, PracticeScriptFixtures.openerScript(SCENARIO, NARRATION, OPENER, HANDOVER, "같은 반 친구")),
                APPROVED);
    }

    @Test
    void aSceneWithAnOpenerPlaysTheNarrationAsMujoongThenTheOpenerAndTheOpportunityAsTheFriend() {
        Harness h = withOpener();

        h.activate();
        Slot narration = h.out.lastSlot();
        assertThat(narration.text()).isEqualTo(NARRATION);
        assertThat(narration.speech().partnerRole()).isEqualTo(RolePolicy.NARRATOR_LABEL);
        assertThat(h.recorder.list()).isEmpty();
        h.played();
        assertThat(h.recorder.list()).as("장면 행은 설명이 들린 뒤에 생긴다").hasSize(1);
        Slot opener = h.out.lastSlot();
        assertThat(opener.text()).isEqualTo(OPENER);
        assertThat(opener.generated()).isFalse();
        assertThat(opener.speech().partnerRole()).isEqualTo("같은 반 친구");
        assertThat(h.ext.progress().stage()).isEqualTo("SCENARIO_INTRO");
        assertThat(h.out.timers).doesNotContainKey("no-response");
        h.played();
        Slot opportunity = h.out.lastSlot();
        assertThat(opportunity.text()).isEqualTo(HANDOVER);
        assertThat(opportunity.speech().partnerRole()).isEqualTo("같은 반 친구");
        assertThat(h.out.timers).as("기회 문장이 끝나기 전에는 입력을 기다리지 않는다").doesNotContainKey("no-response");
        h.played();

        assertThat(h.out.timers).containsKey("no-response");
        h.child("어, 이거 아닌데");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.out.slots).extracting(Slot::text).containsExactly(NARRATION, OPENER, HANDOVER);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void anUtteranceDuringTheNarrationOrTheOpenerIsNotTakenAsTheAnswer() {
        Harness h = withOpener();
        h.activate();
        h.child("음");
        h.played();
        h.child("뭐라고?");
        h.played();
        h.played();

        assertThat(h.out.judgments).as("기회 문장을 듣기 전의 말은 대답이 아니다").isEmpty();
        assertThat(h.out.timers).containsKey("no-response");
    }

    @Test
    void anInterruptedOpenerStillPlaysTheOpportunityLineAndAFailedOneWrapsTheStartedScene() {
        Harness interrupted = withOpener();
        interrupted.activate();
        interrupted.played();
        interrupted.end(Playback.INTERRUPTED);
        assertThat(interrupted.out.lastSlot().text()).isEqualTo(HANDOVER);

        Harness failed = withOpener();
        failed.activate();
        failed.played();
        Signal end = failed.end(Playback.FAILED);

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = failed.recorder.only();
        assertThat(failed.recorder.factKinds(row)).containsExactly(FactKind.PLAYBACK_FAILED, FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
        assertThat(failed.out.slots).extracting(Slot::text).doesNotContain(HANDOVER);
    }

    @Test
    void whenAnOpenerFollowsANarrationThatEndsWithTheOpportunityLineTheLineIsStillPlayedAfterTheOpener() {
        Harness h = new Harness(packageJson(GOAL, PracticeScriptFixtures.openerScript(SCENARIO, CARRYING_INTRO, OPENER, STOP,
                "친한 친구")), APPROVED);
        h.activate();
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(OPENER);
        h.played();

        assertThat(h.out.lastSlot().text()).as("마지막으로 들은 말이 첫 대사라 기회 문장을 다시 들려준다").isEqualTo(STOP);
        assertThat(h.out.timers).doesNotContainKey("no-response");
    }

    /**
     * 실측 F3: 단서(부탁)가 첫 대사에 있으면 다시 설명 뒤 기회 문장만으로는 상황이 다시 서지 않았다. 다시 설명(무중) 뒤에 친구 첫 대사 → 기회 문장을
     * 다시 들려주고, 다시 들려주는 첫 대사 중의 아이 말은 대답으로 보지 않는다.
     */
    @Test
    void theReexplanationIsNarratedByMujoongAndThenTheFriendReplaysTheOpenerAndTheOpportunityLine() {
        Harness h = withOpener();
        h.toOpportunityThroughOpener();
        h.child("공룡 좋아");
        h.result("J2", "IRRELEVANT");

        Slot reexplanation = h.out.lastSlot();
        assertThat(reexplanation.text()).isEqualTo("친구는 빨간 상자와 파란 상자를 들고 있어.");
        assertThat(reexplanation.speech().partnerRole()).isEqualTo(RolePolicy.NARRATOR_LABEL);
        h.played();
        assertThat(h.out.lastSlot().text()).as("다시 설명 뒤에는 첫 대사부터 다시 들려준다").isEqualTo(OPENER);
        assertThat(h.out.lastSlot().speech().partnerRole()).isEqualTo("같은 반 친구");
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PARTNER_OPENER);
        int judgments = h.out.judgments.size();
        h.child("응?");
        assertThat(h.out.judgments).as("다시 들려주는 첫 대사 중의 말은 대답이 아니다").hasSize(judgments);
        assertThat(h.out.timers).doesNotContainKey("no-response");
        h.played();

        assertThat(h.out.lastSlot().text()).isEqualTo(HANDOVER);
        assertThat(h.out.lastSlot().speech().partnerRole()).isEqualTo("같은 반 친구");
        assertThat(h.out.slots).extracting(Slot::text).containsExactly(NARRATION, OPENER, HANDOVER,
                "친구는 빨간 상자와 파란 상자를 들고 있어.", OPENER, HANDOVER);
        h.played();
        assertThat(h.out.timers).containsKey("no-response");
        h.child("파란 상자 줘");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void theNoResponseReguideReplaysOnlyTheOpportunityLineEvenWhenTheSceneHasAnOpener() {
        Harness h = withOpener();
        h.toOpportunityThroughOpener();
        int slots = h.out.slots.size();

        h.advance(Duration.ofSeconds(10));

        assertThat(h.out.slots).hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).as("무응답 재안내는 기회 문장만 다시").isEqualTo(HANDOVER);
        assertThat(h.out.slots).extracting(Slot::text).containsOnlyOnce(OPENER);
    }

    @Test
    void aReplayedOpenerThatFailsWrapsTheSceneAsAPlaybackFailure() {
        Harness h = withOpener();
        h.toOpportunityThroughOpener();
        h.child("공룡 좋아");
        h.result("J2", "IRRELEVANT");
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(OPENER);

        Signal end = h.end(Playback.FAILED);

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).contains(FactKind.SITUATION_EXPLAINED, FactKind.PLAYBACK_FAILED);
        assertThat(h.out.slots).extracting(Slot::text).as("실패한 첫 대사 뒤에 기회 문장을 내지 않는다").containsOnlyOnce(HANDOVER);
    }

    @Test
    void thePeerRuleSurvivesTheInstructionLengthLimitWithLongestFields() {
        String longDescription = "놀".repeat(400) + " " + STOP;
        Harness h = new Harness(packageJson(GOAL, sceneScript(SCENARIO, longDescription, STOP, "가".repeat(20))), APPROVED);
        h.activate();
        h.played();
        h.child("그래");
        h.result("J2", "PARTIAL");

        String reask = h.out.lastSlot().text();
        assertThat(reask.codePointCount(0, reask.length())).isLessThanOrEqualTo(PracticeInstructions.MAX_LENGTH);
        assertThat(reask).endsWith("한두 문장으로 말한다. 아이 대신 대답하지 않는다.");
    }

    // ---- 친구 반응(D_CONTINUE · D_RESUME): 칭찬 · 채점 대신 역할 안 반응 한 번 (docs/practice-dialogue-standard.md) ----

    @Test
    void aJudgedAnswerThatClosesTheSceneGetsOneFriendReactionAndChildWordsDuringItAreNotJudged() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("파란 상자 줘");

        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        Slot reaction = h.out.lastSlot();
        assertThat(reaction.generated()).isTrue();
        assertThat(reaction.role()).isEqualTo("PEER");
        assertThat(reaction.text()).contains("너는 아이의 같은 반 친구 역할이다", "방금 한 말에 자연스럽게 반응해", "새 질문을 하지 않는다",
                "정답이나 모범 문장을 먼저 말하지 말고");
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.SCENE_WRAP);
        assertThat(h.recorder.only().liveTiming()).as("장면 결과는 반응 전에 이미 남는다").isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.out.timers).doesNotContainKey("no-response");
        int judgments = h.out.judgments.size();

        h.child("고마워");
        assertThat(h.out.judgments).as("반응 중의 아이 말은 판정하지 않는다").hasSize(judgments);
        Signal end = h.played();

        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.out.generatedSlots()).as("반응은 한 번").hasSize(1);
        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.VARIANT_SKIPPED);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.helps).as("친구 반응은 도움 기록이 아니다").isEmpty();
        assertThat(h.failures).isEmpty();
        assertRecordMatchesTheContract(h.ext.practiceRecord());
    }

    @Test
    void aFriendReactionThatFailsIsBlockedOrCannotBeMadeStillMovesOnAndOnlyAFailureLeavesAFact() {
        Harness failed = minimal();
        failed.toOpportunity();
        failed.child("파란 상자 줘");
        failed.result("J2", "FULL");
        assertThat(failed.end(Playback.FAILED)).isEqualTo(Signal.LAST_GOAL);
        assertThat(failed.recorder.factKinds(failed.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED, FactKind.VARIANT_SKIPPED);
        assertThat(failed.recorder.only().liveTiming()).as("재생 실패가 장면 결과를 바꾸지 않는다").isEqualTo(LiveTiming.IMMEDIATE);

        Harness blocked = minimal();
        blocked.toOpportunity();
        blocked.child("파란 상자 줘");
        blocked.result("J2", "FULL");
        Slot reaction = blocked.out.lastSlot();
        Signal end = blocked.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", reaction.correlationId(),
                new GuardrailBlockedEvent(reaction.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_PEER,
                        "BLOCK", true));
        assertThat(end).isEqualTo(Signal.LAST_GOAL);
        assertThat(blocked.recorder.factKinds(blocked.recorder.only())).containsExactly(FactKind.VARIANT_SKIPPED);
        assertThat(blocked.slotEvent(reaction.correlationId(), Playback.FAILED, null, reaction.text())).isEqualTo(Signal.NONE);

        Harness refused = minimal();
        refused.toOpportunity();
        refused.child("파란 상자 줘");
        refused.out.generatedResult = false;
        assertThat(refused.result("J2", "FULL")).as("반응을 낼 수 없어도 연습은 멈추지 않는다").isEqualTo(Signal.LAST_GOAL);
        assertThat(refused.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(failed.failures).isEmpty();
        assertThat(blocked.failures).isEmpty();
    }

    @Test
    void aRestRequestDuringTheFriendReactionKeepsTheSceneOutcomeAndOnlyAddsTheSkipReason() {
        Harness h = minimal();
        h.toOpportunity();
        h.child("그거 줘");
        h.result("J2", "PARTIAL");
        h.played();
        h.child("파란 상자");
        h.result("J3", "DELIVERED");
        Slot reaction = h.out.lastSlot();

        Signal signal = h.event(ExtensionEvent.Kind.REST_REQUESTED, "judgment", null, null);

        assertThat(signal).isEqualTo(Signal.NONE);
        assertThat(h.out.cancelledSpeech).contains(reaction.correlationId());
        assertThat(h.out.holds).containsExactly(true);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.liveTiming()).as("되묻기 뒤 전달은 지워지지 않는다").isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(row.variantSkipReason()).as("변형 장면이 없는 대본은 변형 없음이 먼저다(BR6.1 순서)").isEqualTo(VariantSkipReason.NO_VARIANT);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.REASK_RESOLVED, FactKind.END_REQUEST);
        assertThat(h.slotEvent(reaction.correlationId(), Playback.INTERRUPTED, null, reaction.text())).isEqualTo(Signal.NONE);
    }

    @Test
    void stoppingDuringTheFriendReactionOrTheVariantOfferKeepsTheFinishedSceneTimingAndAddsOnlyTheSkipReason() {
        Harness reacting = full();
        reacting.toOpportunity();
        reacting.child("파란 상자 줘");
        reacting.result("J2", "FULL");
        assertThat(reacting.ext.state().sub()).isEqualTo(FlowState.Sub.PEER_REACTION);
        int calls = reacting.out.calls;
        reacting.ext.onStopping();
        SceneRunRecord row = reacting.recorder.only();
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(row.variantSkipReason()).as("세션 종료도 변형 평가에는 종료 · 휴식이다").isEqualTo(VariantSkipReason.END_OR_REST);
        assertThat(reacting.recorder.factKinds(row)).as("끝난 장면에 종료 사실을 더하지 않는다").isEmpty();
        assertThat(reacting.out.calls).isEqualTo(calls);

        Harness withoutVariant = minimal();
        withoutVariant.toOpportunity();
        withoutVariant.child("파란 상자 줘");
        withoutVariant.result("J2", "FULL");
        withoutVariant.ext.onStopping();
        assertThat(withoutVariant.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(withoutVariant.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);

        Harness offered = full();
        offered.toOpportunity();
        offered.child("그거 줘");
        offered.result("J2", "PARTIAL");
        offered.played();
        offered.child("파란 상자");
        offered.result("J3", "DELIVERED");
        offered.played();
        assertThat(offered.ext.state().phase()).isEqualTo(FlowState.Phase.VARIANT_OFFER);
        offered.ext.onSettle(null);
        assertThat(offered.recorder.only().liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(offered.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.END_OR_REST);
        assertRecordMatchesTheContract(offered.ext.practiceRecord());
    }

    @Test
    void aRestRequestAtTheVariantOfferKeepsTheFinishedSceneTiming() {
        Harness h = full();
        h.toOpportunity();
        h.child("그거 줘");
        h.result("J2", "FULL");
        h.played();
        h.played();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.VARIANT_OFFER);

        h.event(ExtensionEvent.Kind.REST_REQUESTED, "judgment", null, null);

        SceneRunRecord row = h.recorder.only();
        assertThat(row.liveTiming()).as("이미 끝난 장면의 바로 전달을 휴식 요청이 지우지 않는다").isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.END_OR_REST);
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.END_REQUEST);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.CLOSING);
    }

    // ---- 도움 제안 · 변형 제안은 무중이 역할 밖에서 (AI 동작 명세 §3.1 D_OFFER_HELP · D_VARIANT_OFFER) ----

    @Test
    void anApprovedHelpOfferIsSpokenVerbatimByMujoongOutOfRoleAndRepeatedAsIsOnReguide() {
        Map<String, String> approved = Map.of("COACH_SWITCH", "잠깐, 무중이 도와줄게", "PEER_RETURN", "다시 친구 역할로 돌아갈게",
                "HELP_OFFER", "무중이가 조금 도와줄까?");
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), approved);
        h.toOpportunity();
        h.child("그거");
        h.result("J2", "PARTIAL");
        h.played();
        h.child("그거 줘");
        h.result("J3", "STILL_SHORT");

        Slot offer = h.out.lastSlot();
        assertThat(offer.generated()).as("승인 문장은 원문 그대로 낸다(원문 일치라 제브를 부르지 않는다)").isFalse();
        assertThat(offer.text()).isEqualTo("무중이가 조금 도와줄까?");
        assertThat(offer.speech().partnerRole()).isEqualTo(RolePolicy.NARRATOR_LABEL);
        assertThat(offer.speech().matches("무중이가 조금 도와줄까?")).isTrue();
        assertThat(h.ext.progress().stage()).isEqualTo("HELP_OFFER");
        h.played();
        int slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).hasSize(slots + 1);
        assertThat(h.out.lastSlot().text()).as("재안내도 같은 승인 문장").isEqualTo("무중이가 조금 도와줄까?");
        h.played();
        h.child("응");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J4);
        assertThat(h.out.generatedSlots()).as("도움 제안에 생성형 발화를 쓰지 않았다").extracting(Slot::role).containsOnly("PEER");
    }

    @Test
    void aV6VariantIsIntroducedInTheOfferByMujoongAndStartsWithTheFriendLineOnceAccepted() {
        Harness h = new Harness(packageJson(GOAL, PracticeScriptFixtures.v6Script(SCENARIO, null)), APPROVED);
        h.toOpportunityThroughOpener();
        h.child("아니, 나 파란 상자 달라고 했어.");
        h.result("J2", "FULL");
        h.played();

        Slot offer = h.out.lastSlot();
        String expected = PracticeScriptFixtures.V6_VARIANT_DESCRIPTION + " " + PracticeInstructions.VARIANT_OFFER_QUESTION;
        assertThat(offer.generated()).as("변형 설명 + 고정 꼬리는 승인 발화(칭찬이 끼어들 자리가 없다)").isFalse();
        assertThat(offer.text()).isEqualTo(expected).endsWith("이어서 해 볼까?");
        assertThat(offer.speech().partnerRole()).isEqualTo(RolePolicy.NARRATOR_LABEL);
        assertThat(offer.speech().matches(expected.replace(" ", ""))).isTrue();
        h.played();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.lastSlot().text()).as("재안내도 같은 문장").isEqualTo(expected);
        h.played();
        h.child("응");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J4);

        h.result("J4", "ACCEPT");

        assertThat(h.recorder.list()).as("제안에서 설명을 들었으니 변형 행은 수락과 함께 생긴다").hasSize(2);
        Slot first = h.out.lastSlot();
        assertThat(first.text()).as("설명을 다시 읽지 않고 친구 기회 문장으로 시작한다(D_VARIANT_START)")
                .isEqualTo(PracticeScriptFixtures.V6_VARIANT_OPPORTUNITY);
        assertThat(first.speech().partnerRole()).isEqualTo("같은 반 친구");
        assertThat(h.ext.progress().stage()).isEqualTo("NEW_SCENARIO_RESPONSE");
        assertThat(h.out.timers).doesNotContainKey("no-response");
        h.played();
        assertThat(h.out.timers).containsKey("no-response");
        h.child("아니, 나 사과 주스 달라고 했어.");
        h.result("J2", "FULL");
        assertThat(h.out.lastSlot().role()).as("변형 장면도 친구 반응으로 닫는다").isEqualTo("PEER");
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);

        assertThat(h.out.slots).filteredOn(slot -> !slot.generated()).extracting(Slot::text)
                .as("변형 설명은 제안(과 그 재안내)에서만 들린다").filteredOn(text -> text.contains("간식 시간")).hasSize(2);
        List<SceneRunRecord> rows = h.recorder.list();
        assertThat(rows).extracting(SceneRunRecord::sceneKind).containsExactly(SceneKind.BASE, SceneKind.VARIANT);
        assertThat(rows.get(1).liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.factKinds(rows.get(1))).containsExactly(FactKind.VARIANT_PLAYED);
        assertThat(h.failures).isEmpty();
        assertRecordMatchesTheContract(h.ext.practiceRecord());
    }

    @Test
    void aV6VariantWithItsOwnOpenerPlaysTheOpenerBeforeTheOpportunityLineAndIgnoresWordsDuringIt() {
        Harness h = new Harness(packageJson(GOAL, PracticeScriptFixtures.v6Script(SCENARIO, "간식 먹자!")), APPROVED);
        h.toOpportunityThroughOpener();
        h.child("아니, 나 파란 상자 달라고 했어.");
        h.result("J2", "FULL");
        h.played();
        h.played();
        h.child("응");
        h.result("J4", "ACCEPT");

        assertThat(h.out.lastSlot().text()).isEqualTo("간식 먹자!");
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PARTNER_OPENER);
        h.child("음");
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(PracticeScriptFixtures.V6_VARIANT_OPPORTUNITY);
        h.played();

        assertThat(h.out.judgments).as("첫 대사 중의 말은 대답이 아니다").extracting(PracticeFlowTestKit.Judgment::point)
                .containsExactly(JudgmentPoint.J2, JudgmentPoint.J4);
        assertThat(h.out.timers).containsKey("no-response");
    }

    @Test
    void theExampleStageFollowsConsentAndTheRetryCount() {
        assertThat(RolePolicy.example(false, 0)).isEqualTo(RolePolicy.Example.NONE);
        assertThat(RolePolicy.example(false, 1)).isEqualTo(RolePolicy.Example.NONE);
        assertThat(RolePolicy.example(true, 0)).isEqualTo(RolePolicy.Example.FRAME);
        assertThat(RolePolicy.example(true, 1)).isEqualTo(RolePolicy.Example.FULL);
        assertThat(RolePolicy.example(true, 2)).isEqualTo(RolePolicy.Example.FULL);
        PracticeScript.Scene scene = PracticeScriptReader.read(fullScript(SCENARIO)).baseScene();
        PracticeScript.Scene bare = PracticeScriptReader.read(minimalScript(SCENARIO)).baseScene();
        assertThat(PracticeInstructions.coachHelp(scene, RolePolicy.Example.NONE)).contains("단서만 준다").doesNotContain("파란 상자를 줘");
        assertThat(PracticeInstructions.coachGivesExample(scene, RolePolicy.Example.NONE)).isFalse();
        assertThat(PracticeInstructions.coachGivesExample(scene, RolePolicy.Example.FRAME)).isTrue();
        assertThat(PracticeInstructions.coachGivesExample(bare, RolePolicy.Example.FULL)).as("대본에 예시가 없으면 예시를 준 것이 아니다").isFalse();
        assertThat(PracticeInstructions.coachHelp(bare, RolePolicy.Example.FULL)).contains("단서만 준다");
    }

    // ---- 코치 첫 도움의 빈칸 예문 · 되묻기 · 생성형 말의 음성 규칙 (실측 F4 · F5, docs/practice-dialogue-standard.md 14절) ----

    @Test
    void aV7ScriptMakesTheFirstCoachHelpSayTheScriptFrameVerbatimBetweenTheFocusPointAndTheInvitation() {
        Harness h = new Harness(packageJson(GOAL, PracticeScriptFixtures.v7Script(SCENARIO)), APPROVED);
        h.toOpportunityThroughOpener();
        h.child("어떻게 말해?");
        h.result("J2", "HELP");
        h.announcedNotice(Playback.COMPLETED);

        Slot coach = h.out.lastSlot();
        assertThat(coach.role()).isEqualTo("COACH");
        String text = coach.text();
        assertThat(text).as("요점 → 대본의 빈칸 예문 글자 그대로 → 네 말로 해 볼래? 를 빠짐없이 이 순서로")
                .contains("첫째, 요점을 쉬운 말 한 문장으로 말한다: 달라고 한 것과 받은 것이 같은지 보기",
                        "둘째, 이 빈칸 예문을 '뭐뭐'까지 그대로 말하고 '처럼 말할 수 있어'를 붙인다: " + PracticeScriptFixtures.V7_FRAME,
                        "셋째, 네 말로 해 볼래? 하고 묻는다", "요점과 빈칸 예문을 꼭 넣고", "'빈칸 예문'이라는 말은 소리 내어 하지 않는다")
                .as("완성 예시는 지시문에 없다 — 빈칸은 core가 만든 것이다").doesNotContain("아니, 나 파란 상자 달라고 했어", "말틀");
        assertThat(text.indexOf("첫째")).isLessThan(text.indexOf("둘째"));
        assertThat(text.indexOf("둘째")).isLessThan(text.indexOf("셋째"));
        assertThat(h.recorder.helps).filteredOn(help -> help.kind() == HelpKind.FOCUS_HELP)
                .extracting(PracticeFlowTypes.HelpRecord::exampleGiven).as("빈칸 예문도 표현 예시를 준 도움이다").containsExactly(true);

        h.played();
        h.child("음");
        h.result("J5", "PARTIAL");
        h.played();
        assertThat(h.out.generatedSlots()).filteredOn(slot -> slot.role().equals("COACH")).last()
                .satisfies(second -> assertThat(second.text()).as("다시 해 보기 뒤의 도움은 지금처럼 표현 예시 하나")
                        .contains("표현 예시를 하나만 들어도 된다: 아니, 나 파란 상자 달라고 했어").doesNotContain("둘째"));
    }

    @Test
    void aFrameTooLongForTheInstructionOrWithoutTheBlankIsNotUsedAndTheOldFramePathIsTaken() {
        PracticeScript.Scene base = PracticeScriptReader.read(PracticeScriptFixtures.v7Script(SCENARIO)).baseScene();
        PracticeScript.Scene longFrame = withFrames(base, List.of("뭐뭐 " + "가".repeat(PracticeInstructions.FIELD_MAX)));
        PracticeScript.Scene noBlank = withFrames(base, List.of("아니, 나 그거 달라고 했어."));

        assertThat(PracticeInstructions.frame(base)).isEqualTo(PracticeScriptFixtures.V7_FRAME);
        for (PracticeScript.Scene scene : List.of(longFrame, noBlank)) {
            assertThat(PracticeInstructions.frame(scene)).isEmpty();
            assertThat(PracticeInstructions.coachHelp(scene, RolePolicy.Example.FRAME))
                    .as("글자 그대로 넣을 수 없는 빈칸 예문은 쓰지 않고 표현 예시에서 빈칸을 만들게 한다")
                    .contains("그대로 말하지 말고", "'뭐뭐'로 비운 빈칸 예문으로 바꿔 하나만 들어 준다").doesNotContain("둘째");
        }
        assertThat(PracticeInstructions.coachHelp(base, RolePolicy.Example.NONE)).as("동의 전에는 빈칸 예문도 없다")
                .contains("단서만 준다").doesNotContain(PracticeScriptFixtures.V7_FRAME);
    }

    @Test
    void theReaskNeverNamesWhatTheChildWantsAndAsksOneOpenQuestionInsideTheScene() {
        Harness h = new Harness(packageJson(GOAL, PracticeScriptFixtures.v7Script(SCENARIO)), APPROVED);
        h.toOpportunityThroughOpener();
        h.child("어, 이거 아닌데");
        h.result("J2", "PARTIAL");

        String reask = h.out.lastSlot().text();
        assertThat(reask).as("설명에 '파란 상자'가 있어도 친구가 먼저 말하거나 그 낱말로 확인하지 않게 막는다(실측 F4)")
                .contains("상황에 나와 있어도 먼저 말하지 않고, 그 낱말을 넣어 맞는지 묻지도 않는다", "열린 질문 하나",
                        "지금 장면과 상관없는 새 질문은 하지 않는다", PracticeInstructions.VOICE_RULE)
                .endsWith("한두 문장으로 말한다. 아이 대신 대답하지 않는다.");
        assertThat(PracticeInstructions.peerReask(PracticeScriptReader.read(PracticeScriptFixtures.v7Script(SCENARIO)).baseScene(), true))
                .as("같은 말 다시 묻기에도 같은 금지").contains("같은 질문을 같은 말투로", "상황에 나와 있어도 먼저 말하지 않고");
    }

    @Test
    void everyGeneratedInstructionForbidsEmojisSymbolsAndBracketsAndKeepsItsClosingRuleWithTheLongestFields() {
        PracticeScript.Scene longest = new PracticeScript.Scene("base-1", SceneKind.BASE, "설".repeat(8000), "첫".repeat(300),
                "기".repeat(2000), "다".repeat(2000), "가".repeat(RolePolicy.PEER_NAME_MAX), List.of("요".repeat(500)),
                List.of("예".repeat(500)), List.of("뭐뭐" + "틀".repeat(PracticeInstructions.FIELD_MAX - 2)), "친구", "교실", "놀이");
        PracticeScript.Scene oldLongest = withFrames(longest, List.of());
        Map<String, String> instructions = new java.util.LinkedHashMap<>();
        instructions.put("reask", PracticeInstructions.peerReask(longest, false));
        instructions.put("reaskAgain", PracticeInstructions.peerReask(longest, true));
        instructions.put("reaction", PracticeInstructions.peerReaction(longest));
        instructions.put("helpOffer", PracticeInstructions.mujoongHelpOffer(true));
        instructions.put("variantOffer", PracticeInstructions.mujoongVariantOffer(true));
        instructions.put("coachFrameV7", PracticeInstructions.coachHelp(longest, RolePolicy.Example.FRAME));
        instructions.put("coachFrameOld", PracticeInstructions.coachHelp(oldLongest, RolePolicy.Example.FRAME));
        instructions.put("coachFull", PracticeInstructions.coachHelp(longest, RolePolicy.Example.FULL));
        instructions.put("coachNone", PracticeInstructions.coachHelp(longest, RolePolicy.Example.NONE));
        instructions.put("coachRetry", PracticeInstructions.coachRetryPrompt(longest));
        instructions.put("noticeCoach", PracticeInstructions.roleNotice("COACH", longest));
        instructions.put("noticePeer", PracticeInstructions.roleNotice("PEER", longest));

        assertThat(instructions.get("coachFrameV7")).as("가장 긴 칸에서도 빈칸 예문 길로 간다").contains("둘째");
        instructions.forEach((name, text) -> {
            assertThat(text.codePointCount(0, text.length())).as(name).isLessThanOrEqualTo(PracticeInstructions.MAX_LENGTH);
            assertThat(text).as(name + ": 생성형 말은 이모지 · 기호 · 괄호 없이 말로만(실측 F4)").contains(PracticeInstructions.VOICE_RULE);
            assertThat(text).as(name + ": 끝의 금지 규칙이 잘리지 않는다")
                    .satisfiesAnyOf(t -> assertThat(t).endsWith("아이 대신 대답하지 않는다."), t -> assertThat(t).endsWith("따뜻하게 말한다."));
            assertThat(text).as(name + ": 지시문 낱말 '말틀'을 쓰지 않는다(실측 F5)").doesNotContain("말틀");
        });
    }

    private static PracticeScript.Scene withFrames(PracticeScript.Scene scene, List<String> frames) {
        return new PracticeScript.Scene(scene.sceneKey(), scene.sceneKind(), scene.sceneDescription(), scene.partnerOpener(),
                scene.opportunitySentence(), scene.reexplanation(), scene.partnerRole(), scene.focusPoints(), scene.expressionExamples(),
                frames, scene.relation(), scene.place(), scene.topic());
    }
}
