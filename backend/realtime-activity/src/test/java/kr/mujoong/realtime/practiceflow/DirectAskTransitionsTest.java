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
 * ({@link FlowTransitionsTest})와 같은 입력에서 C안이 다른 다섯 곳 — 역할 전환 없이 곧바로 도움, 친구 반응 · 고정 마무리 문장, 아이가 청한 도움을
 * 동의로 남김, 끊긴 마무리 문장은 전달로 봄, 판정 보류의 첫 재안내는 그 단계의 묻는 말(기회는 되묻기 단계로, 편차 FT-1024-h) — 과 다시 해 보기 한도
 * 1을 행마다 단언한다. 발화 · 판정 · 전사 오류는 두 표가 같이 main FT-1023-g를 따른다(못 들려준 말은 건너뛰고, 판독 불가 · 보류 두 번째는 도움
 * 단계로, 13.6 main 합치기).
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
    void anUnclearHelpAnswerIsReguidedOnceThenGoesToMujoongsHelpWithoutConsent() {
        assertThat(table.onJudgment(at(Phase.HELP_OFFER, Sub.NONE), JudgmentPoint.J4, "UNCLEAR").guard()).isEqualTo(Guard.UNREADABLE);

        FlowState guided = at(Phase.HELP_OFFER, Sub.NONE);
        guided.unreadableGuided(true);
        Step step = table.onJudgment(guided, JudgmentPoint.J4, "UNCLEAR");
        // FT-1023-g: 두 번째 불명확은 장면을 닫지 않고 동의 없는 도움으로 간다. C안은 역할 전환 알림 없이 곧바로 무중의 도움이다
        assertEnters(step, Phase.FOCUS, Sub.COACH_UTTERANCE);
        assertThat(step.cause()).isNull();
        assertThat(step.consent()).isEqualTo(ConsentAnswer.UNCLEAR);
        assertThat(step.consentsToHelp()).as("동의한 것은 아니다").isFalse();
        assertThat(step.facts()).isEmpty();
        assertThat(step.role()).isNull();
    }

    @Test
    void theHelpSpeechEndingStartsTheOnlyRetryEvenWhenTheHelpSpeechFailedOrWasBlocked() {
        for (SlotEnd end : List.of(SlotEnd.PLAYED, SlotEnd.INTERRUPTED)) {
            Step step = table.onSlotEnded(at(Phase.FOCUS, Sub.COACH_UTTERANCE), end);
            assertEnters(step, Phase.RETRY, Sub.NONE);
            assertThat(step.incrementsRetry()).as(end.name()).isTrue();
            assertThat(step.role()).isNull();
            assertThat(step.facts()).isEmpty();
        }
        // FT-1023-g: 못 들려준 도움(실패 · 막힘)도 장면을 닫지 않고 다시 해 보기로 간다(실패면 사실 PLAYBACK_FAILED)
        Step failed = table.onSlotEnded(at(Phase.FOCUS, Sub.COACH_UTTERANCE), SlotEnd.FAILED);
        assertEnters(failed, Phase.RETRY, Sub.NONE);
        assertThat(failed.incrementsRetry()).isTrue();
        assertThat(failed.cause()).isNull();
        assertThat(failed.facts()).containsExactly(FactKind.PLAYBACK_FAILED);
        Step blocked = table.onSlotEnded(at(Phase.FOCUS, Sub.COACH_UTTERANCE), SlotEnd.BLOCKED);
        assertEnters(blocked, Phase.RETRY, Sub.NONE);
        assertThat(blocked.cause()).isNull();
        assertThat(blocked.facts()).as("막힘은 재생 실패 사실이 아니다").isEmpty();
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
    void theSecondUnreadableOrAbstainedAnswerGoesToTheHelpOfferInsteadOfClosingTheScene() {
        // FT-1023-g: 두 번째 판독 불가 · 보류는 장면을 닫지 않고 도움 제안으로 간다(판독 불가 사실은 남긴다)
        for (Phase phase : List.of(Phase.OPPORTUNITY, Phase.REASK)) {
            FlowState guided = at(phase, Sub.NONE);
            guided.unreadableGuided(true);
            Step unreadable = table.onJudgment(guided, FlowTransitions.expectedPoint(phase), "UNREADABLE");
            assertEnters(unreadable, Phase.HELP_OFFER, Sub.NONE);
            assertThat(unreadable.cause()).as(phase.name()).isNull();
            assertThat(unreadable.facts()).containsExactly(FactKind.TRANSCRIPT_FAILED);

            Step abstained = table.onJudgmentUnavailable(guided, FlowTransitions.expectedPoint(phase));
            assertEnters(abstained, Phase.HELP_OFFER, Sub.NONE);
            assertThat(abstained.cause()).as(phase.name()).isNull();
            assertThat(abstained.facts()).isEmpty();
        }
        // 다시 해 보기에서 두 번째면 남은 횟수가 없어(retry-max 1) 도움을 다시 하지 않고 집중 한도로 고정 마무리 문장을 낸다
        FlowState retry = retrying();
        retry.unreadableGuided(true);
        Step limit = table.onJudgment(retry, JudgmentPoint.J5, "UNREADABLE");
        assertEnters(limit, Phase.SCENE_WRAP, Sub.SCENE_CLOSE);
        assertThat(limit.cause()).isEqualTo(EndCause.FOCUS_LIMIT);
        assertThat(limit.facts()).containsExactly(FactKind.TRANSCRIPT_FAILED);
    }

    // ---- 판정 보류는 그 단계의 묻는 말로 (ai-io.md 10.18, 편차 FT-1024-h) ----

    @Test
    void aFirstUnsureJudgmentAtTheOpportunityEntersTheReaskAndCarriesTheConfirmCount() {
        Step step = table.onJudgmentUnsure(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2);

        assertEnters(step, Phase.REASK, Sub.NONE);
        assertThat(step.guard()).as("확인 · 보류 표지를 되묻기 단계로 잇는다").isEqualTo(Guard.UNSURE);
        assertThat(step.facts()).as("되묻기 사실(REASK_RESOLVED)은 되묻기가 풀릴 때 남는다").isEmpty();
        assertThat(step.timing()).isNull();
        assertThat(step.cause()).isNull();
        assertThat(step.consent()).isNull();
        // 결과 처리는 J2 PARTIAL 뒤 되묻기와 같다: 다음 판정은 J3이고, 풀리면 AFTER_REASK · REASK_RESOLVED다
        assertThat(FlowTransitions.expectedPoint(step.phase())).isEqualTo(JudgmentPoint.J3);
        Step partial = table.onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, "PARTIAL");
        assertThat(List.of(step.phase(), step.sub())).isEqualTo(List.of(partial.phase(), partial.sub()));
        FlowState reask = at(Phase.REASK, Sub.NONE);
        reask.unreadableGuided(true);
        Step resolved = table.onJudgment(reask, JudgmentPoint.J3, "DELIVERED");
        assertEnters(resolved, Phase.SCENE_WRAP, Sub.FRIEND_REACTION);
        assertThat(resolved.timing()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(resolved.facts()).containsExactly(FactKind.REASK_RESOLVED);
    }

    @Test
    void aFirstUnsureJudgmentInTheOtherPhasesAsksAgainInTheSamePhase() {
        for (Phase phase : List.of(Phase.REASK, Phase.HELP_OFFER, Phase.VARIANT_OFFER, Phase.RETRY)) {
            FlowState state = phase == Phase.RETRY ? retrying() : at(phase, Sub.NONE);

            Step step = table.onJudgmentUnsure(state, FlowTransitions.expectedPoint(phase));

            assertThat(step.action()).as(phase.name()).isEqualTo(Action.REGUIDE);
            assertThat(step.guard()).as(phase.name()).isEqualTo(Guard.UNSURE);
            assertThat(step.facts()).as(phase.name()).isEmpty();
            assertThat(step.cause()).as(phase.name()).isNull();
        }
    }

    @Test
    void aSecondUnsureJudgmentTakesTheSameHelpStepAsAnUnavailableOneSoNoCountGrows() {
        for (Phase phase : List.of(Phase.OPPORTUNITY, Phase.REASK, Phase.HELP_OFFER, Phase.VARIANT_OFFER, Phase.RETRY)) {
            FlowState guided = phase == Phase.RETRY ? retrying() : at(phase, Sub.NONE);
            guided.unreadableGuided(true);
            JudgmentPoint point = FlowTransitions.expectedPoint(phase);

            assertThat(table.onJudgmentUnsure(guided, point).effect()).as(phase.name())
                    .isEqualTo(table.onJudgmentUnavailable(guided, point).effect());
        }
        // 되묻기 단계로 이어 간 표지: 되묻기의 보류 · 판독 불가는 두 번째 불명확이라 곧바로 도움 제안이다
        FlowState carried = at(Phase.REASK, Sub.NONE);
        carried.unreadableGuided(true);
        assertEnters(table.onJudgmentUnsure(carried, JudgmentPoint.J3), Phase.HELP_OFFER, Sub.NONE);
        Step unreadable = table.onJudgment(carried, JudgmentPoint.J3, "UNREADABLE");
        assertEnters(unreadable, Phase.HELP_OFFER, Sub.NONE);
        assertThat(unreadable.facts()).containsExactly(FactKind.TRANSCRIPT_FAILED);
    }

    @Test
    void theRoleDialogueTableKeepsTreatingAnUnsureJudgmentLikeAnUnavailableOneAndOtherPointsAreIgnored() {
        FlowTransitions roleDialogue = new FlowTransitions(2, 1);
        for (Phase phase : List.of(Phase.OPPORTUNITY, Phase.REASK, Phase.HELP_OFFER, Phase.RETRY)) {
            for (boolean guided : List.of(false, true)) {
                FlowState state = phase == Phase.RETRY ? retrying() : at(phase, Sub.NONE);
                state.unreadableGuided(guided);
                JudgmentPoint point = FlowTransitions.expectedPoint(phase);
                assertThat(roleDialogue.onJudgmentUnsure(state, point).effect()).as(phase + " guided=" + guided)
                        .isEqualTo(roleDialogue.onJudgmentUnavailable(state, point).effect());
            }
        }
        assertThat(table.onJudgmentUnsure(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J3).action()).as("단계와 다른 지점")
                .isEqualTo(Action.IGNORE);
        assertThat(table.onJudgmentUnsure(at(Phase.OPPORTUNITY, Sub.REEXPLAIN), JudgmentPoint.J2).action()).as("다시 설명 재생 중")
                .isEqualTo(Action.IGNORE);
        assertThat(table.onJudgmentUnsure(at(Phase.SCENE_WRAP, Sub.FRIEND_REACTION), JudgmentPoint.J5).action())
                .isEqualTo(Action.IGNORE);
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
    void outputFailuresAreSkippedWithoutAClosingLineAndEndRequestsClose() {
        // FT-1023-g: 못 들려준 질문은 다시 내지 않고 그 단계의 입력을 기다린다(장면을 닫지 않고 마무리 문장도 없다)
        Step failed = table.onSlotEnded(at(Phase.OPPORTUNITY, Sub.NONE), SlotEnd.FAILED);
        assertThat(failed.action()).isEqualTo(Action.AWAIT);
        assertThat(failed.cause()).isNull();
        assertThat(failed.facts()).containsExactly(FactKind.PLAYBACK_FAILED);
        Step blocked = table.onSlotEnded(at(Phase.REASK, Sub.NONE), SlotEnd.BLOCKED);
        assertThat(blocked.action()).isEqualTo(Action.AWAIT);
        assertThat(blocked.facts()).as("막힘은 재생 실패 사실이 아니다").isEmpty();
        Step reexplained = table.onSlotEnded(at(Phase.OPPORTUNITY, Sub.REEXPLAIN), SlotEnd.FAILED);
        assertEnters(reexplained, Phase.OPPORTUNITY, Sub.NONE);
        assertThat(reexplained.facts()).as("못 들려준 다시 설명은 소단계만 닫는다").containsExactly(FactKind.PLAYBACK_FAILED);
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
    void aClosingLineTheOutputCouldNotPlayIsSkippedWithAPlaybackFailureAndKeepsTheSceneCause(Sub sub) {
        FlowState state = at(Phase.SCENE_WRAP, sub);
        state.sceneEnd(EndCause.NORMAL, LiveTiming.AFTER_REASK);

        // FT-1023-g: 못 들려준 마무리 문장은 다시 내지 않고 변형 평가로 간다. 장면의 원인 · 시점은 바꾸지 않는다(전이에 원인이 없다)
        Step failed = table.onSlotEnded(state, SlotEnd.FAILED);
        assertEnters(failed, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(failed.cause()).isNull();
        assertThat(failed.facts()).containsExactly(FactKind.PLAYBACK_FAILED);

        Step blocked = table.onSlotEnded(state, SlotEnd.BLOCKED);
        assertEnters(blocked, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(blocked.cause()).isNull();
        assertThat(blocked.facts()).isEmpty();
        assertThat(table.decideVariant(state, true).offer()).as("정상 수행 장면은 마무리를 못 들려줘도 변형을 권한다").isTrue();
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

    // ---- 같은 걸음 묶음 (ai-io.md 10.10, 편차 FT-1024-f) ----

    /** 흐름이 결과를 받을 때처럼 코드를 바꾸지 않는다(절차 질문이 아닌 대답). */
    private static final java.util.function.UnaryOperator<String> AS_IS = code -> code;
    /** 절차 질문(방법을 묻는 말)이면 수행 코드를 HELP로 본다 — PracticeConversation의 결과 처리와 같은 규칙. */
    private static final java.util.function.UnaryOperator<String> PROCEDURE = code ->
            List.of("FULL", "DELIVERED", "COPIED").contains(code) ? "HELP" : code;

    @Test
    void theOnlyRetryGroupsTheTwoSuccessesAndTheFourAnswersThatCloseTheScene() {
        List<List<String>> groups = table.sameStepGroups(retrying(), JudgmentPoint.J5, AS_IS);

        // 5회차 S6(FULL 0.38 · COPIED 0.29)이 한 묶음: 둘 다 도움 뒤 성공(AFTER_HELP). 다시 해 보기가 한 번뿐이라 나머지 넷은 모두 장면 닫기
        assertThat(groups).containsExactly(List.of("FULL", "COPIED"), List.of("PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT"));
        assertThat(groups).noneMatch(group -> group.contains("UNREADABLE"));
    }

    @Test
    void withRetriesLeftTheRetryGroupsFollowTheTableIntoHelpAgainAndReexplain() {
        List<List<String>> groups = new FlowTransitions(2, 1, true).sameStepGroups(retrying(), JudgmentPoint.J5, AS_IS);

        assertThat(groups).containsExactly(List.of("FULL", "COPIED"), List.of("PARTIAL", "HELP"), List.of("NOT_UNDERSTOOD", "IRRELEVANT"));
    }

    @Test
    void theFirstAnswerGroupsOnlyTheTwoAnswersThatAreReexplainedAndKeepsHelpApart() {
        FlowState first = at(Phase.OPPORTUNITY, Sub.NONE);
        assertThat(table.sameStepGroups(first, JudgmentPoint.J2, AS_IS)).containsExactly(List.of("NOT_UNDERSTOOD", "IRRELEVANT"));

        FlowState reexplained = at(Phase.OPPORTUNITY, Sub.NONE);
        reexplained.incrementReexplain();
        assertThat(table.sameStepGroups(reexplained, JudgmentPoint.J2, AS_IS)).as("다시 설명 한도 뒤에는 둘 다 도움 제안")
                .containsExactly(List.of("NOT_UNDERSTOOD", "IRRELEVANT"));
        FlowState guided = at(Phase.OPPORTUNITY, Sub.NONE);
        guided.unreadableGuided(true);
        assertThat(table.sameStepGroups(guided, JudgmentPoint.J2, AS_IS)).as("판독 불가는 사실이 달라 묶지 않는다")
                .containsExactly(List.of("NOT_UNDERSTOOD", "IRRELEVANT"));
    }

    @Test
    void theReaskAndTheHelpOfferHaveNoGroupsAndTheVariantOfferGroupsOnlyAfterOneReguide() {
        assertThat(table.sameStepGroups(at(Phase.REASK, Sub.NONE), JudgmentPoint.J3, AS_IS)).isEmpty();
        FlowState offer = at(Phase.HELP_OFFER, Sub.NONE);
        assertThat(table.sameStepGroups(offer, JudgmentPoint.J4, AS_IS)).isEmpty();
        offer.unreadableGuided(true);
        assertThat(table.sameStepGroups(offer, JudgmentPoint.J4, AS_IS)).as("두 번째 불명확은 동의 없는 도움, 거절은 장면 닫기").isEmpty();

        FlowState variant = at(Phase.VARIANT_OFFER, Sub.NONE);
        assertThat(table.sameStepGroups(variant, JudgmentPoint.J4, AS_IS)).as("첫 불명확은 다시 묻기").isEmpty();
        variant.unreadableGuided(true);
        assertThat(table.sameStepGroups(variant, JudgmentPoint.J4, AS_IS)).as("다시 물은 뒤의 불명확은 거절처럼 변형을 건너뛴다")
                .containsExactly(List.of("REFUSE", "UNCLEAR"));
    }

    @Test
    void aProcedureQuestionPutsTheAnswerCodesInTheSameStepAsHelp() {
        assertThat(table.sameStepGroups(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, PROCEDURE))
                .containsExactly(List.of("FULL", "HELP"), List.of("NOT_UNDERSTOOD", "IRRELEVANT"));
        assertThat(table.sameStepGroups(at(Phase.REASK, Sub.NONE), JudgmentPoint.J3, PROCEDURE))
                .containsExactly(List.of("DELIVERED", "HELP"));
        assertThat(table.sameStepGroups(retrying(), JudgmentPoint.J5, PROCEDURE))
                .containsExactly(List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "COPIED"));
    }

    @Test
    void everyGroupedCodeReallyTakesTheSameStepAndCodesOutsideAGroupDoNot() {
        int pairs = 0;
        for (FlowTransitions any : List.of(table, new FlowTransitions(2, 1, true))) {
            for (Map.Entry<Phase, List<String>> entry : CODES.entrySet()) {
                for (boolean guided : List.of(false, true)) {
                    FlowState state = entry.getKey() == Phase.RETRY ? retrying() : at(entry.getKey(), Sub.NONE);
                    state.unreadableGuided(guided);
                    JudgmentPoint point = FlowTransitions.expectedPoint(entry.getKey());
                    List<List<String>> groups = any.sameStepGroups(state, point, AS_IS);
                    for (String a : entry.getValue()) {
                        for (String b : entry.getValue()) {
                            if (a.equals(b)) {
                                continue;
                            }
                            boolean grouped = groups.stream().anyMatch(group -> group.contains(a) && group.contains(b));
                            Step sa = any.onJudgment(state, point, a);
                            Step sb = any.onJudgment(state, point, b);
                            String row = entry.getKey() + " " + a + "/" + b + " guided=" + guided;
                            assertThat(sa.effect().equals(sb.effect())).as(row).isEqualTo(grouped);
                            if (grouped) {
                                assertThat(sa.phase()).as(row).isEqualTo(sb.phase());
                                assertThat(sa.sub()).as(row).isEqualTo(sb.sub());
                                assertThat(sa.facts()).as(row).isEqualTo(sb.facts());
                                assertThat(sa.timing()).as(row).isEqualTo(sb.timing());
                                assertThat(sa.cause()).as(row).isEqualTo(sb.cause());
                            }
                            pairs++;
                        }
                    }
                }
            }
        }
        assertThat(pairs).as("빈 대조로 통과하지 않음").isGreaterThan(100);
    }

    @Test
    void aStateThatIsNotWaitingForThatPointGroupsNothing() {
        assertThat(table.sameStepGroups(at(Phase.SCENE_WRAP, Sub.FRIEND_REACTION), JudgmentPoint.J5, AS_IS)).isEmpty();
        assertThat(table.sameStepGroups(at(Phase.OPPORTUNITY, Sub.REEXPLAIN), JudgmentPoint.J2, AS_IS)).as("다시 설명 재생 중").isEmpty();
        assertThat(table.sameStepGroups(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J5, AS_IS)).as("지점이 단계와 다르다").isEmpty();
        assertThat(FlowTransitions.judgmentCodes(JudgmentPoint.J1)).isEmpty();
        assertThat(FlowTransitions.judgmentCodes(JudgmentPoint.J6)).isEmpty();
    }

    @Test
    void theTableReadsExactlyTheOptionCodesOfBothJudgmentOptionSets() {
        for (boolean flexible : List.of(false, true)) {
            var optionSet = kr.mujoong.realtime.content.LiveContentResources.loadFor(flexible).judgment();
            for (JudgmentPoint point : List.of(JudgmentPoint.J2, JudgmentPoint.J3, JudgmentPoint.J4, JudgmentPoint.J5)) {
                assertThat(FlowTransitions.judgmentCodes(point)).as(optionSet.version() + " " + point)
                        .containsExactlyElementsOf(optionSet.points()
                                .get(kr.mujoong.realtime.content.LiveContentResources.JudgmentPoint.valueOf(point.name())).optionCodes());
            }
        }
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
