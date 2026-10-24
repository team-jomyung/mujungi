package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practiceflow.FlowState.Phase;
import kr.mujoong.realtime.practiceflow.FlowState.Sub;
import kr.mujoong.realtime.practiceflow.FlowTransitions.Action;
import kr.mujoong.realtime.practiceflow.FlowTransitions.EndCause;
import kr.mujoong.realtime.practiceflow.FlowTransitions.Guard;
import kr.mujoong.realtime.practiceflow.FlowTransitions.SlotEnd;
import kr.mujoong.realtime.practiceflow.FlowTransitions.Step;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * C안 '무중이 직접 묻기' 전이표 시험 (docs/practice-direct-ask.md 2장 3A ~ 3E′ · 3.3 · 7.2). 순수 함수라 DB · AI · 시계가 없다. 역할 대화 표
 * ({@link FlowTransitionsTest})와 같은 입력에서 C안이 다른 네 곳 — 역할 전환 없이 곧바로 도움, 친구 반응 · 고정 마무리 문장, 아이가 청한 도움을
 * 동의로 남김, 끊긴 마무리 문장은 전달로 봄 — 과 다시 해 보기 한도 1을 행마다 단언한다.
 */
@Tag("practice-flow")
class DirectAskTransitionsTest {

    private static final Instant T0 = Instant.parse("2026-10-24T09:00:00Z");
    /** C안 운영 기본(application.yml): 다시 해 보기 1 · 다시 설명 1. */
    private final FlowTransitions table = new FlowTransitions(1, 1, true);

    /** 지점마다 선택지 코드 전부(J2 6 · J3 4 · J4 3 · J5 7). */
    private static final Map<Phase, List<String>> CODES = Map.of(
            Phase.OPPORTUNITY, List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE"),
            Phase.REASK, List.of("DELIVERED", "STILL_SHORT", "HELP", "UNREADABLE"),
            Phase.HELP_OFFER, List.of("ACCEPT", "REFUSE", "UNCLEAR"),
            Phase.RETRY, List.of("FULL", "COPIED", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE"));

    private static FlowState at(Phase phase, Sub sub) {
        FlowState state = FlowState.initial();
        state.goal(0, true, T0);
        state.phase(phase, sub);
        return state;
    }

    /** 도움 한 번을 받고 다시 해 보기를 기다리는 상태(retryCount 1). */
    private static FlowState retrying() {
        FlowState state = at(Phase.RETRY, Sub.NONE);
        state.incrementRetry();
        return state;
    }

    private static void assertEnters(Step step, Phase phase, Sub sub) {
        assertThat(step.action()).isEqualTo(Action.ENTER);
        assertThat(step.phase()).isEqualTo(phase);
        assertThat(step.sub()).isEqualTo(sub);
    }

    @Test
    void theTwoArgumentConstructorKeepsTheRoleDialogueTable() {
        assertThat(new FlowTransitions(2, 1).directAsk()).isFalse();
        assertThat(table.directAsk()).isTrue();
    }

    // ---- 3A 바로 수행 · 3B 되묻기 ----

    @Test
    void aFullFirstAnswerGoesToTheApprovedFriendReactionWithImmediateTiming() {
        Step step = table.onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, "FULL");

        assertEnters(step, Phase.SCENE_WRAP, Sub.FRIEND_REACTION);
        assertThat(step.cause()).isEqualTo(EndCause.NORMAL);
        assertThat(step.timing()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(step.facts()).isEmpty();
        assertThat(step.role()).as("역할을 바꾸지 않는다").isNull();
    }

    @Test
    void aPartialFirstAnswerIsReaskedOnceAndADeliveredReaskEndsWithTheFriendReaction() {
        assertEnters(table.onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, "PARTIAL"), Phase.REASK, Sub.NONE);

        Step resolved = table.onJudgment(at(Phase.REASK, Sub.NONE), JudgmentPoint.J3, "DELIVERED");
        assertEnters(resolved, Phase.SCENE_WRAP, Sub.FRIEND_REACTION);
        assertThat(resolved.facts()).containsExactly(FactKind.REASK_RESOLVED);
        assertThat(resolved.timing()).isEqualTo(LiveTiming.AFTER_REASK);

        assertEnters(table.onJudgment(at(Phase.REASK, Sub.NONE), JudgmentPoint.J3, "STILL_SHORT"), Phase.HELP_OFFER, Sub.NONE);
    }

    // ---- 3C 도움 제안 · 아이가 청한 도움 ----

    @Test
    void acceptingTheHelpOfferGoesStraightToMujoongsHelpWithoutARoleNotice() {
        Step step = table.onJudgment(at(Phase.HELP_OFFER, Sub.NONE), JudgmentPoint.J4, "ACCEPT");

        assertEnters(step, Phase.FOCUS, Sub.COACH_UTTERANCE);
        assertThat(step.facts()).containsExactly(FactKind.HELP_ACCEPTED);
        assertThat(step.consent()).isEqualTo(ConsentAnswer.ACCEPT);
        assertThat(step.consentsToHelp()).isTrue();
        assertThat(step.role()).isNull();
    }

    @Test
    void helpTheChildAsksForIsRecordedAsConsentSoTheReportDoesNotCountItAsNoAnswer() {
        for (Phase phase : List.of(Phase.OPPORTUNITY, Phase.REASK)) {
            Step step = table.onJudgment(at(phase, Sub.NONE), FlowTransitions.expectedPoint(phase), "HELP");

            assertEnters(step, Phase.FOCUS, Sub.COACH_UTTERANCE);
            assertThat(step.facts()).as(phase.name()).containsExactly(FactKind.HELP_ACCEPTED);
            assertThat(step.consent()).as("요청도 동의로 남긴다(설계 7.2)").isEqualTo(ConsentAnswer.ACCEPT);
            assertThat(step.consentsToHelp()).isTrue();
        }
        assertThat(new FlowTransitions(2, 1).onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, "HELP").consent())
                .as("역할 대화 표는 그대로 REQUESTED").isEqualTo(ConsentAnswer.REQUESTED);
    }

    @Test
    void refusingTheHelpOfferClosesTheSceneWithTheFixedClosingLine() {
        Step step = table.onJudgment(at(Phase.HELP_OFFER, Sub.NONE), JudgmentPoint.J4, "REFUSE");

        assertEnters(step, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
        assertThat(step.cause()).isEqualTo(EndCause.HELP_DECLINED);
        assertThat(step.facts()).containsExactly(FactKind.HELP_DECLINED);
        assertThat(step.consent()).isEqualTo(ConsentAnswer.DECLINE);
        assertThat(step.timing()).isEqualTo(LiveTiming.NONE);
    }

    @Test
    void anUnclearHelpAnswerIsReguidedOnceThenClosesWithTheFixedClosingLine() {
        assertThat(table.onJudgment(at(Phase.HELP_OFFER, Sub.NONE), JudgmentPoint.J4, "UNCLEAR").guard()).isEqualTo(Guard.UNREADABLE);

        FlowState guided = at(Phase.HELP_OFFER, Sub.NONE);
        guided.unreadableGuided(true);
        Step step = table.onJudgment(guided, JudgmentPoint.J4, "UNCLEAR");
        assertEnters(step, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
        assertThat(step.cause()).isEqualTo(EndCause.HELP_UNCLEAR);
        assertThat(step.consent()).isEqualTo(ConsentAnswer.UNCLEAR);
    }

    @Test
    void theHelpSpeechEndingStartsTheOnlyRetryAndAFailedHelpSpeechWrapsSilently() {
        for (SlotEnd end : List.of(SlotEnd.PLAYED, SlotEnd.INTERRUPTED)) {
            Step step = table.onSlotEnded(at(Phase.FOCUS, Sub.COACH_UTTERANCE), end);
            assertEnters(step, Phase.RETRY, Sub.NONE);
            assertThat(step.incrementsRetry()).as(end.name()).isTrue();
            assertThat(step.role()).isNull();
        }
        Step failed = table.onSlotEnded(at(Phase.FOCUS, Sub.COACH_UTTERANCE), SlotEnd.FAILED);
        assertEnters(failed, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(failed.cause()).isEqualTo(EndCause.PLAYBACK_FAILED);
        assertThat(failed.facts()).containsExactly(FactKind.PLAYBACK_FAILED);
        Step blocked = table.onSlotEnded(at(Phase.FOCUS, Sub.COACH_UTTERANCE), SlotEnd.BLOCKED);
        assertEnters(blocked, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(blocked.cause()).isEqualTo(EndCause.SLOT_BLOCKED);
    }

    // ---- 3D 다시 해 보기(한 번) ----

    @Test
    void aSuccessfulRetryEndsWithTheFriendReactionAfterHelp() {
        for (String code : List.of("FULL", "COPIED")) {
            Step step = table.onJudgment(retrying(), JudgmentPoint.J5, code);
            assertEnters(step, Phase.SCENE_WRAP, Sub.FRIEND_REACTION);
            assertThat(step.cause()).as(code).isEqualTo(EndCause.NORMAL);
            assertThat(step.timing()).isEqualTo(LiveTiming.AFTER_HELP);
        }
    }

    @Test
    void aShortRetryDoesNotHelpAgainButClosesTheSceneBecauseThereIsOnlyOneRetry() {
        for (String code : List.of("PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT")) {
            Step step = table.onJudgment(retrying(), JudgmentPoint.J5, code);
            assertEnters(step, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
            assertThat(step.cause()).as(code).isEqualTo(EndCause.FOCUS_LIMIT);
            assertThat(step.timing()).isEqualTo(LiveTiming.NONE);
        }
        assertEnters(new FlowTransitions(2, 1, true).onJudgment(retrying(), JudgmentPoint.J5, "PARTIAL"), Phase.FOCUS,
                Sub.COACH_UTTERANCE);
    }

    // ---- 3E · 3E′ 답 없음 · 판독 불가 · 보류 · 한도 ----

    @Test
    void theSecondNoResponseClosesTheSceneWithTheFixedClosingLine() {
        FlowState first = at(Phase.OPPORTUNITY, Sub.NONE);
        assertThat(table.onNoResponse(first).guard()).isEqualTo(Guard.NO_RESPONSE);

        for (Phase phase : List.of(Phase.OPPORTUNITY, Phase.REASK, Phase.HELP_OFFER, Phase.RETRY)) {
            FlowState guided = at(phase, Sub.NONE);
            guided.noResponseGuided(true);
            Step step = table.onNoResponse(guided);
            assertEnters(step, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
            assertThat(step.cause()).as(phase.name()).isEqualTo(EndCause.TIMEOUT);
            assertThat(step.facts()).containsExactly(FactKind.TIMEOUT);
        }
    }

    @Test
    void theSecondUnreadableOrAbstainedAnswerClosesTheSceneWithTheFixedClosingLine() {
        FlowState guided = at(Phase.OPPORTUNITY, Sub.NONE);
        guided.unreadableGuided(true);
        Step unreadable = table.onJudgment(guided, JudgmentPoint.J2, "UNREADABLE");
        assertEnters(unreadable, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
        assertThat(unreadable.facts()).containsExactly(FactKind.TRANSCRIPT_FAILED);

        Step abstained = table.onJudgmentUnavailable(guided, JudgmentPoint.J2);
        assertEnters(abstained, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
        assertThat(abstained.cause()).isEqualTo(EndCause.ABSTAINED);
        assertThat(abstained.timing()).isEqualTo(LiveTiming.ABSTAINED);
    }

    @Test
    void theFocusAndGoalLimitsCloseTheSceneWithTheFixedClosingLine() {
        Step focus = table.onFocusLimit(at(Phase.FOCUS, Sub.COACH_UTTERANCE));
        assertEnters(focus, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
        assertThat(focus.cause()).isEqualTo(EndCause.FOCUS_LIMIT);

        Step goal = table.onGoalLimit(at(Phase.REASK, Sub.NONE));
        assertEnters(goal, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
        assertThat(goal.cause()).isEqualTo(EndCause.TIME_LIMIT);
    }

    @Test
    void outputFailuresAndEndRequestsSayNoClosingLine() {
        Step failed = table.onSlotEnded(at(Phase.OPPORTUNITY, Sub.NONE), SlotEnd.FAILED);
        assertEnters(failed, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(failed.cause()).isEqualTo(EndCause.PLAYBACK_FAILED);
        Step blocked = table.onSlotEnded(at(Phase.REASK, Sub.NONE), SlotEnd.BLOCKED);
        assertEnters(blocked, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(blocked.cause()).isEqualTo(EndCause.SLOT_BLOCKED);
        Step end = table.onEndRequested(at(Phase.OPPORTUNITY, Sub.NONE));
        assertEnters(end, Phase.CLOSING, Sub.NONE);
        assertThat(end.endsByRequest()).isTrue();
    }

    // ---- 마무리 문장 ----

    @ParameterizedTest
    @EnumSource(value = Sub.class, names = {"FRIEND_REACTION", "SCENE_CLOSE"})
    void aClosingLineThatPlayedOrWasInterruptedByTheChildCountsAsDeliveredAndMovesOn(Sub sub) {
        for (SlotEnd end : List.of(SlotEnd.PLAYED, SlotEnd.INTERRUPTED)) {
            Step step = table.onSlotEnded(at(Phase.SCENE_WRAP, sub), end);
            assertEnters(step, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
            assertThat(step.cause()).as(sub + " " + end).isNull();
            assertThat(step.facts()).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Sub.class, names = {"FRIEND_REACTION", "SCENE_CLOSE"})
    void aClosingLineTheOutputCouldNotPlayIsAPlaybackFailureThatKeepsTheSceneTiming(Sub sub) {
        FlowState state = at(Phase.SCENE_WRAP, sub);
        state.sceneEnd(EndCause.NORMAL, LiveTiming.AFTER_REASK);

        Step failed = table.onSlotEnded(state, SlotEnd.FAILED);
        assertEnters(failed, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(failed.cause()).isEqualTo(EndCause.PLAYBACK_FAILED);
        assertThat(failed.facts()).containsExactly(FactKind.PLAYBACK_FAILED);
        assertThat(failed.timing()).isEqualTo(LiveTiming.AFTER_REASK);

        Step blocked = table.onSlotEnded(state, SlotEnd.BLOCKED);
        assertThat(blocked.cause()).isEqualTo(EndCause.SLOT_BLOCKED);
        assertThat(blocked.facts()).isEmpty();
    }

    // ---- 변형 ----

    @Test
    void aSceneSolvedAfterTheOnlyHelpStillOffersTheVariantInTheDirectAskTable() {
        FlowState helped = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        helped.incrementRetry();
        helped.sceneEnd(EndCause.NORMAL, LiveTiming.AFTER_HELP);

        assertEnters(table.evaluateVariant(helped, true), Phase.VARIANT_OFFER, Sub.NONE);
        assertThat(new FlowTransitions(1, 1).decideVariant(helped, true).skip())
                .as("역할 대화 표는 다시 해 보기를 다 쓴 장면을 집중 한도로 생략한다").isEqualTo(VariantSkipReason.FOCUS_LIMIT);
    }

    @Test
    void theVariantIsSkippedForTheLimitCausesAndAfterAVariantScene() {
        FlowState limited = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        limited.incrementRetry();
        limited.sceneEnd(EndCause.FOCUS_LIMIT, LiveTiming.NONE);
        assertThat(table.decideVariant(limited, true).skip()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);

        FlowState focusTimer = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        focusTimer.focusLimitReached(true);
        focusTimer.sceneEnd(EndCause.NORMAL, LiveTiming.AFTER_HELP);
        assertThat(table.decideVariant(focusTimer, true).skip()).as("집중 시계 만료는 C안에서도 한도").isEqualTo(VariantSkipReason.FOCUS_LIMIT);

        FlowState declined = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        declined.sceneEnd(EndCause.HELP_DECLINED, LiveTiming.NONE);
        assertThat(table.decideVariant(declined, true).offer()).as("도움 거절 뒤에도 비슷한 이야기는 권한다").isTrue();

        FlowState variant = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        variant.resetScene(SceneKind.VARIANT);
        variant.phase(Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(table.evaluateVariant(variant, true).facts()).containsExactly(FactKind.VARIANT_PLAYED);
    }

    // ---- 표 전체 ----

    @Test
    void noRowOfTheDirectAskTableSwitchesRolesOrUsesTheRoleNoticesOrThePeerReply() {
        List<Sub> roleSubs = List.of(Sub.ROLE_NOTICE, Sub.RETURN_NOTICE, Sub.PEER_REPLY);
        List<FactKind> roleFacts = List.of(FactKind.COACH_SWITCH, FactKind.COACH_RETURN, FactKind.ORIGINAL_SCENE_RETURN);
        int rows = 0;
        for (Map.Entry<Phase, List<String>> entry : CODES.entrySet()) {
            for (String code : entry.getValue()) {
                for (boolean guided : List.of(false, true)) {
                    FlowState state = entry.getKey() == Phase.RETRY ? retrying() : at(entry.getKey(), Sub.NONE);
                    state.unreadableGuided(guided);
                    Step step = table.onJudgment(state, FlowTransitions.expectedPoint(entry.getKey()), code);
                    String row = entry.getKey() + " " + code + " guided=" + guided;
                    assertThat(step.action()).as(row).isNotEqualTo(Action.IGNORE);
                    assertThat(step.sub()).as(row).isNotIn(roleSubs);
                    assertThat(step.role()).as(row).isNull();
                    assertThat(step.facts()).as(row).doesNotContainAnyElementsOf(roleFacts);
                    rows++;
                }
            }
        }
        for (Sub sub : List.of(Sub.COACH_UTTERANCE, Sub.FRIEND_REACTION, Sub.SCENE_CLOSE)) {
            Phase phase = sub == Sub.COACH_UTTERANCE ? Phase.FOCUS : Phase.SCENE_WRAP;
            for (SlotEnd end : SlotEnd.values()) {
                Step step = table.onSlotEnded(at(phase, sub), end);
                assertThat(step.sub()).as(sub + " " + end).isNotIn(roleSubs);
                assertThat(step.role()).isNull();
                assertThat(step.facts()).doesNotContainAnyElementsOf(roleFacts);
                rows++;
            }
        }
        assertThat(rows).as("빈 대조로 통과하지 않음: 판정 20코드 × 2 + 슬롯 3 × 4").isEqualTo(52);
    }
}
