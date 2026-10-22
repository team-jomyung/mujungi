package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.*;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.session.PracticeSessionExtension.*;
import org.junit.jupiter.api.Test;

class FlexiblePracticeConversationTest {
    private Harness fresh(boolean variant) {
        return new Harness(packageJson(UUID.randomUUID(), variant ? fullScript(UUID.randomUUID()) : minimalScript(UUID.randomUUID())),
                Map.of("COACH_SWITCH", "이제 코치가 도와줄게.", "PEER_RETURN", "다시 친구로 돌아왔어."),
                new PracticeFlowSettings(Duration.ofSeconds(10), Duration.ofSeconds(90), Duration.ofMinutes(10),
                        Duration.ofSeconds(20), 2, 1, true));
    }

    private Harness dialogue() {
        Harness h = fresh(false);
        h.toOpportunity();
        return h;
    }

    @Test
    void aReviewedSpokenIntroductionIsReadInsteadOfInternalDescription() {
        String intro="친구랑 쉬는 시간에 놀이를 고르려고 해. 무중이가 친구 역할을 해 볼게.";
        String script=minimalScript(UUID.randomUUID()).replace("\"sceneKey\":", "\"spokenIntroduction\":\""+intro+"\",\"sceneKey\":");
        Harness h=new Harness(packageJson(UUID.randomUUID(),script),Map.of(),
                new PracticeFlowSettings(Duration.ofSeconds(10),Duration.ofSeconds(90),Duration.ofMinutes(10),Duration.ofSeconds(20),2,1));
        h.activate();
        assertThat(h.out.lastSlot().text()).isEqualTo(intro);
        assertThat(h.out.lastSlot().generated()).isFalse();
        assertThat(h.out.lastSlot().speech().matches(intro)).isTrue();
    }

    @Test
    void olderScriptsUseABoundedNaturalIntroductionWithoutReadingInternalProse() {
        Harness h = fresh(false); h.activate();
        assertThat(h.out.lastSlot().generated()).isFalse();
        assertThat(h.out.lastSlot().text()).contains("상상 속 상황", "상대 역할").doesNotContain("상자 두 개");
        assertThat(h.recorder.rows).isEmpty();
        h.played();
        assertThat(h.recorder.rows).hasSize(1);
        assertThat(h.out.lastSlot().generated()).isFalse(); // One original opening line, then flexible role replies.
    }

    @Test
    void goalAchievementDoesNotEndTheConversationOrOfferAVariant() {
        Harness h = dialogue(); h.child("무슨 놀이를 하고 싶어?");
        assertThat(h.result("J2", "FULL")).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.DIALOGUE_REPLY);
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.out.lastSlot().text()).contains("아이의 질문에는 먼저 답하고", "정해진 첫 대사나 상황 안내를 반복하지 마");
        assertThat(h.recorder.only().endedAt()).isNull();
    }

    @Test
    void negotiationContinuesUntilAnAgreementAndTheFinalReplyHasPlayed() {
        Harness h = dialogue(); h.child("무슨 놀이를 하고 싶어?"); h.result("J2", "FULL"); h.played();
        h.child("교실에서는 못 하잖아");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J3);
        assertThat(h.out.lastJudgment().criteria()).containsEntry("coreFunction", "ROLE_DIALOGUE_CLOSURE");
        h.result("J3", "STILL_SHORT"); h.played();
        h.child("같이 블록 만들자"); h.result("J3", "STILL_SHORT"); h.played();
        h.child("응, 같이 만들자");
        assertThat(h.result("J3", "DELIVERED")).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().sub()).isEqualTo(FlowState.Sub.PEER_REPLY);
        assertThat(h.recorder.only().endedAt()).isNull();
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.factKinds(h.recorder.only())).doesNotContain(FactKind.REASK_RESOLVED);
    }

    @Test
    void partialOrUnclearRoleSpeechGetsAContextualReplyInsteadOfAScriptedRepeat() {
        for (String option : List.of("PARTIAL", "IRRELEVANT", "NOT_UNDERSTOOD", "UNREADABLE")) {
            Harness h = dialogue(); h.child("왜 그 놀이가 싫어?"); h.result("J2", option);
            assertThat(h.out.lastSlot().generated()).isTrue();
            assertThat(h.out.lastSlot().text()).contains("제안·거절·수정", "필요할 때만").doesNotContain("같은 질문을 같은 말투로");
            assertThat(h.out.announces).isEmpty();
            assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        }
    }

    @Test
    void abstentionDoesNotInventGoalSuccessAndKeepsTheRoleConversationOpen() {
        Harness h = dialogue(); h.child("다른 건?");
        h.event(ExtensionEvent.Kind.JUDGMENT_ABSTAINED, "judgment", h.out.lastJudgment().correlationId(), Map.of("point", "J2"));
        assertThat(h.out.lastSlot().generated()).isTrue();
        h.played(); h.child("블록은 어때?");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J2);
        assertThat(h.recorder.only().endedAt()).isNull();
    }

    @Test
    void aMethodsQuestionStillOffersConsentedCoachHelpAndReturnsToDialogue() {
        Harness h = dialogue(); h.child("뭐라고 말하면 되는 거야?"); h.result("J2", "FULL");
        assertThat(h.out.announces).containsExactly("COACH");
        h.announcedNotice(Playback.COMPLETED); h.played(); h.child("무슨 놀이 하고 싶어?");
        h.result("J5", "FULL");
        assertThat(h.out.announces).containsExactly("COACH", "PEER");
        h.announcedNotice(Playback.COMPLETED);
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.ext.state().phase()).isEqualTo(FlowState.Phase.OPPORTUNITY);
        h.played(); h.child("좋아"); h.result("J3", "DELIVERED"); h.played();
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.AFTER_HELP);
    }

    @Test
    void anEarlyAnswerDuringThePeerReplyIsJudgedAfterPlayback() {
        Harness h = dialogue(); h.child("무슨 놀이 하고 싶어?"); h.result("J2", "FULL");
        h.child("실내에서는 못 하잖아");
        assertThat(h.out.judgments).hasSize(1);
        h.played();
        assertThat(h.out.judgments).hasSize(2);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J3);
    }

    @Test
    void silenceInvitesTimeToThinkInsteadOfRepeatingTheOpeningLine() {
        Harness h = dialogue(); h.advance(Duration.ofSeconds(10));
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.out.lastSlot().text()).contains("생각할 시간", "상황 안내를 반복하지 마");
        h.played();
        assertThat(h.advance(Duration.ofSeconds(10))).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.TIMEOUT);
    }

    @Test
    void blockedIntroductionIsNotRecordedAsAnExposedScene() {
        Harness h = fresh(false); h.activate(); var slot = h.out.lastSlot();
        assertThat(h.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", slot.correlationId(),
                new GuardrailBlockedEvent(slot.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_PEER,
                        "BLOCK", true))).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.rows).isEmpty();
    }

    @Test
    void aPeerReplyFailureKeepsAchievementButDoesNotClaimNormalCompletion() {
        Harness h = dialogue(); h.child("무슨 놀이 하고 싶어?"); h.result("J2", "FULL");
        assertThat(h.end(Playback.FAILED)).isEqualTo(Signal.OUTPUT_FAILED);
        assertThat(h.recorder.only().liveTiming()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void naturalIntroductionAndDialogueKeepInstructionsBoundedAndDoNotQuoteAnswers() {
        var scene = new PracticeScript.Scene("x", SceneKind.BASE, "가".repeat(1000), "나".repeat(500), "다시", "친구",
                List.of("답변 단서"), List.of("정답 비밀 표식"), "친구", "교실", "놀이");
        for (String instruction : List.of(PracticeInstructions.peerDialogue(scene))) {
            assertThat(instruction.length()).isLessThanOrEqualTo(500);
            assertThat(instruction).startsWith("지금 '음,'").doesNotContain("정답 비밀 표식");
        }
    }
}
