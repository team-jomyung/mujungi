package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_REACTION;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScriptWithVariant;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.fullScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Slot;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C안 '무중이 직접 묻기' 대화 시험 (docs/practice-direct-ask.md 2장 · 5장 · 7.2 · 7.4). 옛 대본(v1)은 대체 문장으로, 고정 문장은 시작 패키지
 * approvedSpeech(없으면 같은 문장의 상수)로 읽히고, 판독 불가 · 불명확은 고정 확인(또는 같은 제안) 한 번 뒤 장면을 닫지 않고 도움 제안 · 도움으로
 * 넘어가며(FT-1023-g), 아이 말로 끊긴 마무리 문장은 전달된 것으로 보고 다음으로 가는지 본다. 가짜 출력 · 가짜 판정 · 수동 시계 위의 합성
 * 대화이다(테스트 대역 증거).
 */
@Tag("practice-flow")
class DirectAskConversationTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String SCENE_CLOSE = FIXED_SPEECH.get("SCENE_CLOSE");
    private static final String INPUT_CONFIRM = FIXED_SPEECH.get("INPUT_CONFIRM");
    private static final String HELP_OFFER = FIXED_SPEECH.get("HELP_OFFER");
    private static final String VARIANT_OFFER = FIXED_SPEECH.get("VARIANT_OFFER");

    private static Harness with(String script, Map<String, String> approved) {
        return new Harness(packageJson(GOAL, script), approved);
    }

    /** 첫 대답 → 되묻기 → 여전히 짧음 → 도움 제안이 열린 상태. */
    private static void toHelpOffer(Harness h) {
        h.toOpportunity();
        h.child("음");
        h.result("J2", "PARTIAL");
        h.played();
        h.child("음");
        h.result("J3", "STILL_SHORT");
    }

    // ---- 옛 대본 v1 ----

    @Test
    void anOldV1ScriptIsSpokenWithTheFallbackSentencesAndStillRecordsTheExampleHelp() {
        Harness h = with(fullScript(SCENARIO), Map.of());
        h.toIntroduction();
        assertThat(h.out.lastSlot().text()).isEqualTo("상상 속 이야기를 떠올려 봐. 무중이가 이야기를 들려줄게.");
        h.played();
        assertThat(h.out.lastSlot().text()).as("친구 말 전하기 대체 + 고정 묻기(ASK_DEFAULT 상수)")
                .isEqualTo("친구가 이렇게 말했어. 친구가 말했어. 그거 줘. 너라면 친구한테 뭐라고 말할래?");
        h.played();
        h.child("어떻게 말해?");
        h.result("J2", "HELP");
        assertThat(h.out.lastSlot().text()).as("요점 + 표현 예시 + 꼬리")
                .isEqualTo("어느 것인지 말하기. 예를 들면 파란 상자를 줘처럼 말할 수 있어. 네 말로 해 볼래?");
        h.played();
        h.child("파란 상자 줄래?");
        h.result("J5", "FULL");
        assertThat(h.out.lastSlot().text()).isEqualTo("그렇게 말해 주면 친구도 네 마음을 알 거야.");

        List<Slot> inScene = h.out.slots.subList(1, h.out.slots.size());
        assertThat(inScene).allSatisfy(slot -> {
            assertThat(slot.generated()).as("장면 안 말은 모두 승인 낭독").isFalse();
            assertThat(slot.role()).isEqualTo(RolePolicy.MUJOONG_LABEL);
        });
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.HELP_ACCEPTED, FactKind.FOCUS_HELP,
                FactKind.EXPRESSION_EXAMPLE);
    }

    @Test
    void anOldScriptWithoutReaskOrFocusPointsUsesTheFixedReaskAndAHelpWithoutAnExample() {
        Harness h = with(minimalScript(SCENARIO), Map.of());
        h.toOpportunity();
        h.child("그거");
        h.result("J2", "PARTIAL");
        assertThat(h.out.lastSlot().text()).isEqualTo("조금 더 자세히 말해 줄래?");
        h.played();
        h.child("도와줘");
        h.result("J3", "HELP");

        assertThat(h.out.lastSlot().text()).isEqualTo("친구는 빨간 상자와 파란 상자를 들고 있어. 네 말로 해 볼래?");
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.FOCUS_HELP).doesNotContain(FactKind.EXPRESSION_EXAMPLE);
        assertThat(h.recorder.helps).filteredOn(help -> help.kind() == HelpKind.FOCUS_HELP).singleElement()
                .satisfies(help -> assertThat(help.exampleGiven()).isFalse());
    }

    // ---- core가 서버 검사에 걸린 칸만 뺀 v2 대본 ----

    /** v2 대본에서 칸 하나를 지운다(core가 발급 때 뺀 칸은 키가 없다). */
    private static String without(String script, String field) {
        return script.replaceFirst("\"" + field + "\":\"[^\"]*\",", "");
    }

    @Test
    void aV2ScriptMissingOnlyTheRelayAndTheReactionUsesTheFallbacksForThoseFieldsAndTheScriptForTheRest() {
        String script = without(without(directAskScript(SCENARIO), "opportunityRelay"), "friendReaction");
        Harness h = with(script, FIXED_SPEECH);
        h.toIntroduction();
        assertThat(h.out.lastSlot().text()).isEqualTo(PracticeScriptFixtures.V2_INTRO);
        h.played();
        assertThat(h.out.lastSlot().text()).as("전하기만 대체 문장, 묻기는 대본 칸")
                .isEqualTo("친구가 이렇게 말했어. " + PracticeScriptFixtures.V2_DIRECT_LINE + " " + PracticeScriptFixtures.V2_ASK);
        h.played();
        h.child("손 괜찮아?");

        h.result("J2", "FULL");

        assertThat(h.out.lastSlot().text()).isEqualTo(DirectAskLines.FRIEND_REACTION_FALLBACK);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aTeacherSceneMissingTheRelayAskAndReactionSpeaksTheHonorificFallbacks() {
        String script = without(without(without(directAskScript(SCENARIO), "opportunityRelay"), "askPrompt"), "friendReaction")
                .replace("\"relation\":\"친구\"", "\"relation\":\"TEACHER\"");
        Harness h = with(script, FIXED_SPEECH);
        h.toOpportunity();
        assertThat(h.out.lastSlot().text()).isEqualTo(
                "선생님이 이렇게 말씀하셨어. " + PracticeScriptFixtures.V2_DIRECT_LINE + " 너라면 선생님께 뭐라고 말씀드릴래?");
        h.child("선생님 손 괜찮으세요?");

        h.result("J2", "FULL");

        assertThat(h.out.lastSlot().text()).isEqualTo("그렇게 말씀드리면 선생님도 네 마음을 아실 거야.");
        assertThat(h.failures).isEmpty();
    }

    // ---- 고정 문장 ----

    @Test
    void theFixedSentencesComeFromTheStartPackageWhenItHasThem() {
        Map<String, String> approved = new HashMap<>(FIXED_SPEECH);
        approved.put("HELP_OFFER", "무중이가 도와줄까?");
        approved.put("SCENE_CLOSE", "알겠어. 이 이야기는 여기서 마칠게.");
        Harness h = with(directAskScript(SCENARIO), approved);
        toHelpOffer(h);
        assertThat(h.out.lastSlot().text()).isEqualTo("무중이가 도와줄까?");
        h.played();
        h.child("싫어");

        h.result("J4", "REFUSE");

        assertThat(h.out.lastSlot().text()).isEqualTo("알겠어. 이 이야기는 여기서 마칠게.");
    }

    @Test
    void withoutApprovedSpeechTheSameSentencesComeFromTheRealtimeConstants() {
        Harness h = with(directAskScript(SCENARIO), Map.of());
        toHelpOffer(h);
        assertThat(h.out.lastSlot().text()).isEqualTo(HELP_OFFER);
        h.played();
        h.child("싫어");

        h.result("J4", "REFUSE");

        assertThat(h.out.lastSlot().text()).isEqualTo(SCENE_CLOSE);
        assertThat(h.failures).isEmpty();
    }

    // ---- 판독 불가 · 불명확 ----

    @Test
    void aSecondUnreadableAnswerOffersHelpAfterOneInputConfirmAndKeepsTheTranscriptFailedFact() {
        Harness h = with(directAskScriptWithVariant(SCENARIO), FIXED_SPEECH);
        h.toOpportunity();
        h.child("웅얼웅얼");
        h.result("J2", "UNREADABLE");
        assertThat(h.out.lastSlot().text()).isEqualTo(INPUT_CONFIRM);
        h.played();
        h.child("웅얼");

        h.result("J2", "UNREADABLE");

        // FT-1023-g: 두 번째 판독 불가는 장면을 닫지 않고 도움 제안으로 간다(판독 불가 사실은 남긴다)
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.HELP_OFFER);
        assertThat(h.out.lastSlot().text()).isEqualTo(HELP_OFFER);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.endedAt()).isNull();
        assertThat(row.liveTiming()).isNull();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.TRANSCRIPT_FAILED);
    }

    @Test
    void anUnclearAnswerToTheHelpOfferRereadsTheOfferOnceThenHelpsWithoutConsent() {
        Harness h = with(directAskScript(SCENARIO), FIXED_SPEECH);
        toHelpOffer(h);
        h.played();
        h.child("몰라");
        h.result("J4", "UNCLEAR");
        assertThat(h.out.lastSlot().text()).as("교정 없이 도움 제안을 한 번 더 읽는다").isEqualTo(HELP_OFFER);
        h.played();
        h.child("음");

        h.result("J4", "UNCLEAR");

        // FT-1023-g: 두 번째 불명확은 장면을 닫지 않고 동의 없이 도움으로 간다. C안 도움은 장면마다 한 번이고 대본 helpSpeech라 표현 예시가
        // 함께 나간다(docs/practice-direct-ask.md 13.6 main 합치기)
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.FOCUS);
        assertThat(h.out.lastSlot().text()).isEqualTo(PracticeScriptFixtures.V2_HELP);
        assertThat(h.out.announces).isEmpty();
        assertThat(h.recorder.helps).filteredOn(help -> help.kind() == HelpKind.FOCUS_HELP).singleElement()
                .satisfies(help -> assertThat(help.consentAnswer()).isEqualTo(ConsentAnswer.UNCLEAR));
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.FOCUS_HELP).doesNotContain(FactKind.HELP_ACCEPTED);
        assertThat(h.recorder.only().endedAt()).isNull();
    }

    @Test
    void acceptingTheHelpOfferRecordsTheConsentOnTheHelpRow() {
        Harness h = with(directAskScript(SCENARIO), FIXED_SPEECH);
        toHelpOffer(h);
        h.played();
        h.child("응");

        h.result("J4", "ACCEPT");

        assertThat(h.out.lastSlot().text()).isEqualTo(PracticeScriptFixtures.V2_HELP);
        assertThat(h.out.announces).isEmpty();
        assertThat(h.recorder.helps).filteredOn(help -> help.kind() == HelpKind.FOCUS_HELP).singleElement()
                .satisfies(help -> assertThat(help.consentAnswer()).isEqualTo(ConsentAnswer.ACCEPT));
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.HELP_ACCEPTED);
    }

    // ---- 끊긴 마무리 문장 ----

    @Test
    void aClosingLineTheChildTalksOverStillCountsAsDeliveredAndTheVariantIsOffered() {
        Harness h = with(directAskScriptWithVariant(SCENARIO), FIXED_SPEECH);
        toHelpOffer(h);
        h.played();
        h.child("싫어");
        h.result("J4", "REFUSE");
        assertThat(h.out.lastSlot().text()).isEqualTo(SCENE_CLOSE);

        assertThat(h.end(Playback.INTERRUPTED)).isEqualTo(Signal.NONE);

        assertThat(h.out.lastSlot().text()).as("도움을 거절해도 비슷한 이야기는 권한다").isEqualTo(VARIANT_OFFER);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.HELP_DECLINED).doesNotContain(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void aFriendReactionTheChildTalksOverStillOffersTheVariantAndKeepsTheAchievement() {
        Harness h = with(directAskScriptWithVariant(SCENARIO), FIXED_SPEECH);
        h.toOpportunity();
        h.child("손 괜찮아?");
        h.result("J2", "FULL");
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REACTION);
        h.child("응 고마워");

        assertThat(h.end(Playback.INTERRUPTED)).as("아이 말로 끊긴 친구 반응은 전달된 것으로 본다").isEqualTo(Signal.NONE);

        assertThat(h.out.lastSlot().text()).isEqualTo(VARIANT_OFFER);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.PLAYBACK_FAILED);
        assertThat(h.out.judgments).as("마무리 위의 말은 판정하지 않는다").hasSize(1);
    }

    @Test
    void aClosingLineTheOutputCouldNotPlayIsSkippedAndThePracticeMovesOn() {
        Harness h = with(directAskScript(SCENARIO), FIXED_SPEECH);
        toHelpOffer(h);
        h.played();
        h.child("싫어");
        h.result("J4", "REFUSE");

        // FT-1023-g: 못 들려준 마무리 문장은 다시 내지 않고 재생 실패 사실만 남긴 채 회고 · 다음으로 간다
        assertThat(h.end(Playback.FAILED)).as("마무리 문장 뒤 목표 회고").isEqualTo(Signal.NONE);
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);

        assertThat(h.out.slots.stream().filter(slot -> slot.text().equals(SCENE_CLOSE))).as("마무리 문장은 다시 내지 않는다").hasSize(1);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.HELP_DECLINED, FactKind.PLAYBACK_FAILED);
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
    }

    // ---- 같은 걸음 묶음 (ai-io.md 10.10, 편차 FT-1024-f) ----

    private static final String GROUPS = PracticeSessionExtension.PracticeOutput.SAME_STEP_GROUPS;

    @Test
    void theRetryJudgmentCarriesTheSuccessAndCloseGroupsBesideTheCriteriaAndASuccessEndsAfterHelp() {
        Harness h = with(directAskScript(SCENARIO), FIXED_SPEECH);
        h.toOpportunity();
        h.child("몰라, 뭐라고 해야 돼?");
        Map<String, Object> first = h.out.lastJudgment().criteria();
        assertThat(first).containsKeys("coreFunction", "judgmentCriteria")
                .containsEntry(GROUPS, List.of(List.of("NOT_UNDERSTOOD", "IRRELEVANT")));
        h.result("J2", "HELP");
        assertThat(h.out.lastSlot().text()).isEqualTo(PracticeScriptFixtures.V2_HELP);
        h.played();

        h.child("내가 필요한 건 파란색이야");

        assertThat(h.out.lastJudgment().point()).isEqualTo(kr.mujoong.realtime.judgment.JudgmentPoint.J5);
        assertThat(h.out.lastJudgment().criteria()).containsKeys("coreFunction", "judgmentCriteria").containsEntry(GROUPS,
                List.of(List.of("FULL", "COPIED"), List.of("PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT")));
        // 실행부가 묶음 합으로 확정해 1순위(모델 그대로)만 보낸다: COPIED여도 FULL과 같은 성공이다
        h.result("J5", "COPIED");
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REACTION);
        h.playedThroughRecap();
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
    }

    @Test
    void aProcedureQuestionIsGroupedWithHelpBecauseTheFlowTreatsItAsAHelpRequest() {
        Harness h = with(directAskScript(SCENARIO), FIXED_SPEECH);
        h.toOpportunity();

        h.child("뭐라고 말해야 돼?");

        assertThat(h.out.lastJudgment().criteria()).containsEntry(GROUPS,
                List.of(List.of("FULL", "HELP"), List.of("NOT_UNDERSTOOD", "IRRELEVANT")));
        h.result("J2", "FULL");
        assertThat(h.out.lastSlot().text()).as("방법을 물은 말의 FULL은 도움 요청으로 처리한다(지금 동작)")
                .isEqualTo(PracticeScriptFixtures.V2_HELP);
    }

    @Test
    void theVariantOfferSendsNoCriteriaFirstAndOnlyTheGroupsAfterOneUnclearAnswer() {
        Harness h = with(directAskScriptWithVariant(SCENARIO), FIXED_SPEECH);
        h.toOpportunity();
        h.child("손 괜찮아?");
        h.result("J2", "FULL");
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(VARIANT_OFFER);
        h.played();
        h.child("음");
        assertThat(h.out.lastJudgment().criteria()).as("묶음이 없으면 J4는 지금처럼 기준 없음").isNull();
        h.result("J4", "UNCLEAR");
        assertThat(h.out.lastSlot().text()).isEqualTo(VARIANT_OFFER);
        h.played();

        h.child("음");

        assertThat(h.out.lastJudgment().criteria()).as("판정 기준 없이 묶음만").containsOnlyKeys(GROUPS)
                .containsEntry(GROUPS, List.of(List.of("REFUSE", "UNCLEAR")));
    }

    @Test
    void theFlexibleDialogueSendsNoGroups() {
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), Map.of(), PracticeFlowTestKit.flexible());
        h.toOpportunity();

        h.child("무슨 말이야?");

        assertThat(h.out.lastJudgment().criteria()).containsKeys("coreFunction", "judgmentCriteria").doesNotContainKey(GROUPS);
    }

    // ---- 화자 ----

    @Test
    void onlyTheDirectAskPathSpeaksAsMujoongForTheJudgmentInput() {
        assertThat(with(directAskScript(SCENARIO), FIXED_SPEECH).ext.speaksAsMujoong()).isTrue();
        assertThat(new Harness(packageJson(GOAL, minimalScript(SCENARIO)), Map.of(), PracticeFlowTestKit.flexible()).ext
                .speaksAsMujoong()).isFalse();
    }
}
