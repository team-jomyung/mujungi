package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
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
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 장면 전이표 시험 (U12 BR3 ~ BR6 · functional-spec §3.3 · §3.3a). 순수 함수라 DB · AI · 시계가 없다. 표의 행마다 입력 → 다음 단계 · 사실 · 사유 ·
 * 진행 중 판정 시점을 단언하고, J2 6 · J3 4 · J4 3 · J5 7 선택지 코드가 전부 한 행씩 있는지 본다. 한도(재시도 2회 · 상황 다시 설명 1회)는 횟수와 시계
 * 만료 표지로만 판단한다(시각 경계 9초/10초 · 90초 · 목표 10분은 수동 시계로 {@code PracticeConversationTest}가 본다).
 */
@Tag("practice-flow")
class FlowTransitionsTest {

    private static final Instant T0 = Instant.parse("2026-10-18T09:00:00Z");
    private static final int RETRY_MAX = 2;
    private static final int REEXPLAIN_MAX = 1;

    private final FlowTransitions table = new FlowTransitions(RETRY_MAX, REEXPLAIN_MAX);

    private static FlowState at(Phase phase, Sub sub) {
        return at(phase, sub, true);
    }

    private static FlowState at(Phase phase, Sub sub, boolean lastGoal) {
        FlowState state = FlowState.initial();
        state.goal(0, lastGoal, T0);
        state.phase(phase, sub);
        return state;
    }

    private static void assertEnters(Step step, Phase phase, Sub sub) {
        assertThat(step.action()).isEqualTo(Action.ENTER);
        assertThat(step.phase()).isEqualTo(phase);
        assertThat(step.sub()).isEqualTo(sub);
    }

    // ---- J2 OPPORTUNITY (6코드) ----

    @Test
    void j2FullWaitsForTheFriendReplyBeforeWrapping() {
        Step step = table.onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, "FULL");

        assertEnters(step, Phase.SCENE_WRAP, Sub.PEER_REPLY);
        assertThat(step.cause()).isEqualTo(EndCause.NORMAL);
        assertThat(step.timing()).isEqualTo(LiveTiming.IMMEDIATE);
        assertThat(step.facts()).isEmpty();
    }

    @Test
    void j2PartialGoesToOneFriendReask() {
        assertEnters(table.onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, "PARTIAL"), Phase.REASK, Sub.NONE);
    }

    @Test
    void j2HelpSwitchesToTheCoachWithNoticeFirstAndRemembersTheConsent() {
        Step step = table.onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, "HELP");

        assertEnters(step, Phase.FOCUS, Sub.ROLE_NOTICE);
        assertThat(step.consent()).isEqualTo(ConsentAnswer.REQUESTED);
        assertThat(step.consentsToHelp()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"NOT_UNDERSTOOD", "IRRELEVANT"})
    void j2NotUnderstoodAndIrrelevantReexplainOnceThenOfferHelp(String code) {
        FlowState state = at(Phase.OPPORTUNITY, Sub.NONE);

        Step first = table.onJudgment(state, JudgmentPoint.J2, code);
        assertThat(first.action()).isEqualTo(Action.REEXPLAIN);
        assertThat(first.phase()).isEqualTo(Phase.OPPORTUNITY);
        assertThat(first.sub()).isEqualTo(Sub.REEXPLAIN);
        assertThat(first.incrementsReexplain()).isTrue();
        assertThat(first.fact()).isEqualTo(FactKind.SITUATION_EXPLAINED);

        state.incrementReexplain();
        assertEnters(table.onJudgment(state, JudgmentPoint.J2, code), Phase.HELP_OFFER, Sub.NONE);
    }

    @Test
    void j2UnreadableReguidesOnceThenOffersHelpKeepingTheTranscriptFailedFact() {
        FlowState state = at(Phase.OPPORTUNITY, Sub.NONE);

        Step first = table.onJudgment(state, JudgmentPoint.J2, "UNREADABLE");
        assertThat(first.action()).isEqualTo(Action.REGUIDE);
        assertThat(first.guard()).isEqualTo(Guard.UNREADABLE);

        state.unreadableGuided(true);
        Step second = table.onJudgment(state, JudgmentPoint.J2, "UNREADABLE");
        // FT-1023-g: 두 번째도 장면을 닫지 않는다. 같은 안내를 되풀이하지 않고 도움 제안으로 간다
        assertEnters(second, Phase.HELP_OFFER, Sub.NONE);
        assertThat(second.cause()).isNull();
        assertThat(second.fact()).isEqualTo(FactKind.TRANSCRIPT_FAILED);
    }

    // ---- J3 REASK (4코드) ----

    @Test
    void j3DeliveredResolvesAfterTheReaskWithoutFocusHelp() {
        Step step = table.onJudgment(at(Phase.REASK, Sub.NONE), JudgmentPoint.J3, "DELIVERED");

        assertEnters(step, Phase.SCENE_WRAP, Sub.PEER_REPLY);
        assertThat(step.fact()).isEqualTo(FactKind.REASK_RESOLVED);
        assertThat(step.timing()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(step.consent()).isNull();
    }

    @Test
    void j3StillShortOffersHelpAndHelpSwitchesToTheCoach() {
        assertEnters(table.onJudgment(at(Phase.REASK, Sub.NONE), JudgmentPoint.J3, "STILL_SHORT"), Phase.HELP_OFFER, Sub.NONE);
        assertEnters(table.onJudgment(at(Phase.REASK, Sub.NONE), JudgmentPoint.J3, "HELP"), Phase.FOCUS, Sub.ROLE_NOTICE);
    }

    @Test
    void j3UnreadableReguidesOnceThenOffersHelp() {
        FlowState state = at(Phase.REASK, Sub.NONE);
        assertThat(table.onJudgment(state, JudgmentPoint.J3, "UNREADABLE").action()).isEqualTo(Action.REGUIDE);

        state.unreadableGuided(true);
        Step second = table.onJudgment(state, JudgmentPoint.J3, "UNREADABLE");

        assertEnters(second, Phase.HELP_OFFER, Sub.NONE);
        assertThat(second.cause()).isNull();
        assertThat(second.fact()).isEqualTo(FactKind.TRANSCRIPT_FAILED);
    }

    // ---- J4 HELP_OFFER · VARIANT_OFFER (3코드) ----

    @Test
    void j4AcceptInHelpOfferEntersFocusWithTheAcceptedFactAndConsent() {
        Step step = table.onJudgment(at(Phase.HELP_OFFER, Sub.NONE), JudgmentPoint.J4, "ACCEPT");

        assertEnters(step, Phase.FOCUS, Sub.ROLE_NOTICE);
        assertThat(step.fact()).isEqualTo(FactKind.HELP_ACCEPTED);
        assertThat(step.consent()).isEqualTo(ConsentAnswer.ACCEPT);
        assertThat(step.consentsToHelp()).isTrue();
    }

    @Test
    void j4RefuseInHelpOfferClosesTheSceneWithoutMarkingItIncomplete() {
        Step step = table.onJudgment(at(Phase.HELP_OFFER, Sub.NONE), JudgmentPoint.J4, "REFUSE");

        assertEnters(step, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(step.cause()).isEqualTo(EndCause.HELP_DECLINED);
        assertThat(step.fact()).isEqualTo(FactKind.HELP_DECLINED);
        assertThat(step.timing()).isEqualTo(LiveTiming.NONE);
        assertThat(step.consent()).isEqualTo(ConsentAnswer.DECLINE);
    }

    @Test
    void j4UnclearInHelpOfferOffersAgainOnceThenGivesCoachHelpWithoutConsent() {
        FlowState state = at(Phase.HELP_OFFER, Sub.NONE);
        Step first = table.onJudgment(state, JudgmentPoint.J4, "UNCLEAR");
        assertThat(first.action()).isEqualTo(Action.REGUIDE);

        state.unreadableGuided(true);
        Step second = table.onJudgment(state, JudgmentPoint.J4, "UNCLEAR");

        assertEnters(second, Phase.FOCUS, Sub.ROLE_NOTICE);
        assertThat(second.cause()).isNull();
        assertThat(second.consent()).isEqualTo(ConsentAnswer.UNCLEAR);
        assertThat(second.consentsToHelp()).as("동의가 없으니 표현 예시는 주지 않는다").isFalse();
    }

    @Test
    void j4InVariantOfferReusesTheThreeCodesAcceptStartsTheVariantRefuseAndSecondUnclearDecline() {
        FlowState state = at(Phase.VARIANT_OFFER, Sub.NONE, false);

        Step accept = table.onJudgment(state, JudgmentPoint.J4, "ACCEPT");
        assertEnters(accept, Phase.SCENE_INTRO, Sub.NONE);
        assertThat(accept.startsVariant()).isTrue();

        Step refuse = table.onJudgment(state, JudgmentPoint.J4, "REFUSE");
        assertEnters(refuse, Phase.NEXT_GOAL, Sub.NONE);
        assertThat(refuse.skip()).isEqualTo(VariantSkipReason.DECLINED);
        assertThat(refuse.fact()).isEqualTo(FactKind.VARIANT_SKIPPED);

        assertThat(table.onJudgment(state, JudgmentPoint.J4, "UNCLEAR").action()).isEqualTo(Action.REGUIDE);
        state.unreadableGuided(true);
        assertThat(table.onJudgment(state, JudgmentPoint.J4, "UNCLEAR").skip()).isEqualTo(VariantSkipReason.DECLINED);
    }

    // ---- J5 RETRY (7코드) ----

    @ParameterizedTest
    @CsvSource({"FULL", "COPIED"})
    void j5FullAndCopiedBothCloseAsAfterHelpAndCopiedCountsAsFollowingTheCoach(String code) {
        Step step = table.onJudgment(at(Phase.RETRY, Sub.NONE), JudgmentPoint.J5, code);

        assertEnters(step, Phase.SCENE_WRAP, Sub.PEER_REPLY);
        assertThat(step.cause()).isEqualTo(EndCause.NORMAL);
        assertThat(step.timing()).isEqualTo(LiveTiming.AFTER_HELP);
    }

    @ParameterizedTest
    @CsvSource({"PARTIAL", "HELP"})
    void j5PartialAndHelpRetryAgainWhileRetriesAndTheFocusClockRemain(String code) {
        FlowState state = at(Phase.RETRY, Sub.NONE);
        state.incrementRetry();

        assertEnters(table.onJudgment(state, JudgmentPoint.J5, code), Phase.FOCUS, Sub.COACH_UTTERANCE);
    }

    @ParameterizedTest
    @CsvSource({"PARTIAL", "HELP"})
    void j5PartialAndHelpCloseAsFocusLimitWhenTwoRetriesAreUsedOrTheFocusClockExpired(String code) {
        FlowState twoRetries = at(Phase.RETRY, Sub.NONE);
        twoRetries.incrementRetry();
        twoRetries.incrementRetry();
        FlowState expired = at(Phase.RETRY, Sub.NONE);
        expired.focusLimitReached(true);

        for (FlowState state : List.of(twoRetries, expired)) {
            Step step = table.onJudgment(state, JudgmentPoint.J5, code);
            assertEnters(step, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
            assertThat(step.cause()).isEqualTo(EndCause.FOCUS_LIMIT);
            assertThat(step.timing()).isEqualTo(LiveTiming.NONE);
        }
    }

    @ParameterizedTest
    @CsvSource({"NOT_UNDERSTOOD", "IRRELEVANT"})
    void j5NotUnderstoodReexplainsThenReturnsToTheCoachOnlyWhileTheBudgetsRemain(String code) {
        FlowState state = at(Phase.RETRY, Sub.NONE);
        state.incrementRetry();
        Step first = table.onJudgment(state, JudgmentPoint.J5, code);
        assertThat(first.action()).isEqualTo(Action.REEXPLAIN);
        assertThat(first.phase()).isEqualTo(Phase.RETRY);

        FlowState reexplained = at(Phase.RETRY, Sub.NONE);
        reexplained.incrementRetry();
        reexplained.incrementReexplain();
        assertThat(table.onJudgment(reexplained, JudgmentPoint.J5, code).cause()).isEqualTo(EndCause.FOCUS_LIMIT);

        FlowState noRetryLeft = at(Phase.RETRY, Sub.NONE);
        noRetryLeft.incrementRetry();
        noRetryLeft.incrementRetry();
        assertThat(table.onJudgment(noRetryLeft, JudgmentPoint.J5, code).cause()).isEqualTo(EndCause.FOCUS_LIMIT);
    }

    @Test
    void j5UnreadableReguidesOnceThenCoachesAgainUntilTheRetryLimit() {
        FlowState state = at(Phase.RETRY, Sub.NONE);
        assertThat(table.onJudgment(state, JudgmentPoint.J5, "UNREADABLE").action()).isEqualTo(Action.REGUIDE);

        state.unreadableGuided(true);
        Step second = table.onJudgment(state, JudgmentPoint.J5, "UNREADABLE");
        assertEnters(second, Phase.FOCUS, Sub.COACH_UTTERANCE);
        assertThat(second.fact()).isEqualTo(FactKind.TRANSCRIPT_FAILED);

        state.incrementRetry();
        state.incrementRetry();
        Step exhausted = table.onJudgment(state, JudgmentPoint.J5, "UNREADABLE");
        assertThat(exhausted.cause()).as("남은 횟수가 없으면 기존 집중 한도로 정리한다").isEqualTo(EndCause.FOCUS_LIMIT);
    }

    @Test
    void everyOptionCodeOfJ2ToJ5HasARowAndNoCodeFallsOutOfTheTable() {
        List<String> j2 = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE");
        List<String> j3 = List.of("DELIVERED", "STILL_SHORT", "HELP", "UNREADABLE");
        List<String> j4 = List.of("ACCEPT", "REFUSE", "UNCLEAR");
        List<String> j5 = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE", "COPIED");
        assertThat(j2).hasSize(6);
        assertThat(j3).hasSize(4);
        assertThat(j4).hasSize(3);
        assertThat(j5).hasSize(7);

        j2.forEach(code -> assertThat(table.onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J2, code).action())
                .as("J2 " + code).isNotEqualTo(Action.IGNORE));
        j3.forEach(code -> assertThat(table.onJudgment(at(Phase.REASK, Sub.NONE), JudgmentPoint.J3, code).action())
                .as("J3 " + code).isNotEqualTo(Action.IGNORE));
        j4.forEach(code -> assertThat(table.onJudgment(at(Phase.HELP_OFFER, Sub.NONE), JudgmentPoint.J4, code).action())
                .as("J4 " + code).isNotEqualTo(Action.IGNORE));
        j5.forEach(code -> assertThat(table.onJudgment(at(Phase.RETRY, Sub.NONE), JudgmentPoint.J5, code).action())
                .as("J5 " + code).isNotEqualTo(Action.IGNORE));
    }

    // ---- 보류 · 장애 · 모르는 코드 ----

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"OPPORTUNITY", "REASK", "HELP_OFFER", "RETRY"})
    void anAbstainedJudgmentReguidesOnceThenMovesToTheNextHelpStepWithNoFact(Phase phase) {
        FlowState state = at(phase, Sub.NONE);
        JudgmentPoint point = FlowTransitions.expectedPoint(phase);

        Step first = table.onJudgmentUnavailable(state, point);
        assertThat(first.action()).isEqualTo(Action.REGUIDE);

        state.unreadableGuided(true);
        Step second = table.onJudgmentUnavailable(state, point);
        Phase next = switch (phase) {
            case OPPORTUNITY, REASK -> Phase.HELP_OFFER;
            default -> Phase.FOCUS;
        };
        assertThat(second.action()).isEqualTo(Action.ENTER);
        assertThat(second.phase()).isEqualTo(next);
        assertThat(second.cause()).as("보류 두 번째도 장면을 닫지 않는다(FT-1023-g)").isNull();
        assertThat(second.facts()).isEmpty();
    }

    @Test
    void anUnknownOptionCodeIsTreatedLikeAnAbstention() {
        FlowState state = at(Phase.OPPORTUNITY, Sub.NONE);

        assertThat(table.onJudgment(state, JudgmentPoint.J2, "SOMETHING_ELSE").action()).isEqualTo(Action.REGUIDE);
        assertThat(table.onJudgment(state, JudgmentPoint.J2, null).action()).isEqualTo(Action.REGUIDE);
    }

    @Test
    void anAbstainedVariantOfferDeclinesOnTheSecondTime() {
        FlowState state = at(Phase.VARIANT_OFFER, Sub.NONE, false);
        assertThat(table.onJudgmentUnavailable(state, JudgmentPoint.J4).action()).isEqualTo(Action.REGUIDE);

        state.unreadableGuided(true);

        assertThat(table.onJudgmentUnavailable(state, JudgmentPoint.J4).skip()).isEqualTo(VariantSkipReason.DECLINED);
    }

    @Test
    void judgmentsForTheWrongPointOrStageOrSubStepAreIgnored() {
        assertThat(table.onJudgment(at(Phase.OPPORTUNITY, Sub.NONE), JudgmentPoint.J3, "FULL").action()).isEqualTo(Action.IGNORE);
        assertThat(table.onJudgment(at(Phase.OPPORTUNITY, Sub.REEXPLAIN), JudgmentPoint.J2, "FULL").action()).isEqualTo(Action.IGNORE);
        assertThat(table.onJudgment(at(Phase.FOCUS, Sub.COACH_UTTERANCE), JudgmentPoint.J5, "FULL").action()).isEqualTo(Action.IGNORE);
        assertThat(table.onJudgment(at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL), JudgmentPoint.J2, "FULL").action()).isEqualTo(Action.IGNORE);
        assertThat(table.onJudgmentUnavailable(at(Phase.CLOSING, Sub.NONE), JudgmentPoint.J2).action()).isEqualTo(Action.IGNORE);
    }

    // ---- 무응답 시계 (§3.2 · BR3.6) ----

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"OPPORTUNITY", "REASK", "HELP_OFFER", "RETRY", "VARIANT_OFFER"})
    void theFirstNoResponseExpiryReguidesInTheSameStage(Phase phase) {
        Step step = table.onNoResponse(at(phase, Sub.NONE, false));

        assertThat(step.action()).isEqualTo(Action.REGUIDE);
        assertThat(step.guard()).isEqualTo(Guard.NO_RESPONSE);
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"OPPORTUNITY", "REASK", "HELP_OFFER", "RETRY"})
    void theSecondNoResponseExpiryClosesTheSceneAsTimeoutWithNoVariant(Phase phase) {
        FlowState state = at(phase, Sub.NONE);
        state.noResponseGuided(true);

        Step step = table.onNoResponse(state);

        assertEnters(step, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
        assertThat(step.cause()).isEqualTo(EndCause.TIMEOUT);
        assertThat(step.fact()).isEqualTo(FactKind.TIMEOUT);
        assertThat(step.timing()).isEqualTo(LiveTiming.NONE);
        assertThat(table.decideVariant(stateWithCause(EndCause.TIMEOUT), true).skip()).isEqualTo(VariantSkipReason.END_OR_REST);
    }

    @Test
    void theSecondNoResponseExpiryInAVariantOfferDeclines() {
        FlowState state = at(Phase.VARIANT_OFFER, Sub.NONE, false);
        state.noResponseGuided(true);

        Step step = table.onNoResponse(state);

        assertEnters(step, Phase.NEXT_GOAL, Sub.NONE);
        assertThat(step.skip()).isEqualTo(VariantSkipReason.DECLINED);
    }

    @Test
    void aNoResponseExpiryIsIgnoredWhileAJudgmentIsOpenOrAReexplanationPlaysOrNoInputIsExpected() {
        FlowState judging = at(Phase.OPPORTUNITY, Sub.NONE);
        judging.judgmentOpen(true);

        assertThat(table.onNoResponse(judging).action()).isEqualTo(Action.IGNORE);
        assertThat(table.onNoResponse(at(Phase.OPPORTUNITY, Sub.REEXPLAIN)).action()).isEqualTo(Action.IGNORE);
        assertThat(table.onNoResponse(at(Phase.SCENE_INTRO, Sub.NONE)).action()).isEqualTo(Action.IGNORE);
    }

    // ---- 한도 시계 만료 표지 ----

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"FOCUS", "RETRY"})
    void theFocusClockExpiryClosesTheSceneAsFocusLimitOnlyInFocusAndRetry(Phase phase) {
        FlowState state = at(phase, phase == Phase.FOCUS ? Sub.COACH_UTTERANCE : Sub.NONE);
        state.role(FlowRole.COACH);

        Step step = table.onFocusLimit(state);

        assertEnters(step, Phase.SCENE_WRAP, Sub.RETURN_NOTICE);
        assertThat(step.cause()).isEqualTo(EndCause.FOCUS_LIMIT);
        assertThat(table.onFocusLimit(at(Phase.OPPORTUNITY, Sub.NONE)).action()).isEqualTo(Action.IGNORE);
    }

    @Test
    void theGoalClockExpiryClosesTheSceneFromAnyStageButIsOnlyAFlagInsideTheWrap() {
        for (Phase phase : List.of(Phase.SCENE_INTRO, Phase.OPPORTUNITY, Phase.REASK, Phase.HELP_OFFER, Phase.FOCUS, Phase.RETRY)) {
            Step step = table.onGoalLimit(at(phase, Sub.NONE));
            assertEnters(step, Phase.SCENE_WRAP, Sub.VARIANT_EVAL);
            assertThat(step.cause()).as(phase.name()).isEqualTo(EndCause.TIME_LIMIT);
        }
        Step offer = table.onGoalLimit(at(Phase.VARIANT_OFFER, Sub.NONE, false));
        assertEnters(offer, Phase.NEXT_GOAL, Sub.NONE);
        assertThat(offer.skip()).isEqualTo(VariantSkipReason.TIME_LIMIT);
        assertThat(table.onGoalLimit(at(Phase.SCENE_WRAP, Sub.RETURN_NOTICE)).action()).isEqualTo(Action.IGNORE);
        assertThat(table.onGoalLimit(at(Phase.CLOSING, Sub.NONE)).action()).isEqualTo(Action.IGNORE);
    }

    @Test
    void anEndOrRestRequestClosesFromAnyStageExceptWhenAlreadyEnding() {
        Step step = table.onEndRequested(at(Phase.OPPORTUNITY, Sub.NONE));

        assertEnters(step, Phase.CLOSING, Sub.NONE);
        assertThat(step.fact()).isEqualTo(FactKind.END_REQUEST);
        assertThat(step.endsByRequest()).isTrue();
        assertThat(table.onEndRequested(at(Phase.CLOSING, Sub.NONE)).action()).isEqualTo(Action.IGNORE);
        assertThat(table.onEndRequested(at(Phase.NEXT_GOAL, Sub.NONE)).action()).isEqualTo(Action.IGNORE);
    }

    // ---- 슬롯 종료 (§3.5) ----

    @Test
    void anyIntroEndMovesToTheOpportunityAndOnlyAFailedIntroLeavesAPlaybackFailure() {
        FlowState state = at(Phase.SCENE_INTRO, Sub.NONE, false);

        for (SlotEnd end : SlotEnd.values()) {
            Step step = table.onSlotEnded(state, end);
            assertEnters(step, Phase.OPPORTUNITY, Sub.NONE);
            assertThat(step.cause()).as("도입 실패도 장면을 닫지 않는다 " + end).isNull();
            assertThat(step.fact()).isEqualTo(end == SlotEnd.FAILED ? FactKind.PLAYBACK_FAILED : null);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"OPPORTUNITY", "REASK", "HELP_OFFER", "RETRY"})
    void aFinishedOrSkippedQuestionWaitsForInputAndNeverClosesTheScene(Phase phase) {
        FlowState state = at(phase, Sub.NONE);

        assertThat(table.onSlotEnded(state, SlotEnd.PLAYED).action()).isEqualTo(Action.AWAIT);
        assertThat(table.onSlotEnded(state, SlotEnd.INTERRUPTED).action()).isEqualTo(Action.AWAIT);
        Step failed = table.onSlotEnded(state, SlotEnd.FAILED);
        assertThat(failed.action()).isEqualTo(Action.AWAIT);
        assertThat(failed.cause()).isNull();
        assertThat(failed.fact()).isEqualTo(FactKind.PLAYBACK_FAILED);
        Step blocked = table.onSlotEnded(state, SlotEnd.BLOCKED);
        assertThat(blocked.action()).isEqualTo(Action.AWAIT);
        assertThat(blocked.cause()).isNull();
        assertThat(blocked.facts()).isEmpty();
    }

    @Test
    void aFinishedReexplanationReturnsToTheOpportunityOrTheCoach() {
        assertEnters(table.onSlotEnded(at(Phase.OPPORTUNITY, Sub.REEXPLAIN), SlotEnd.PLAYED), Phase.OPPORTUNITY, Sub.NONE);
        assertEnters(table.onSlotEnded(at(Phase.RETRY, Sub.REEXPLAIN), SlotEnd.PLAYED), Phase.RETRY, Sub.NONE);
        Step failed = table.onSlotEnded(at(Phase.RETRY, Sub.REEXPLAIN), SlotEnd.FAILED);
        assertEnters(failed, Phase.RETRY, Sub.NONE);
        assertThat(failed.fact()).isEqualTo(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void aVariantOfferSlotWaitsWhenPlayedAndDeclinesWhenItFails() {
        FlowState state = at(Phase.VARIANT_OFFER, Sub.NONE, false);

        assertThat(table.onSlotEnded(state, SlotEnd.PLAYED).action()).isEqualTo(Action.AWAIT);
        Step failed = table.onSlotEnded(state, SlotEnd.FAILED);
        assertEnters(failed, Phase.NEXT_GOAL, Sub.NONE);
        assertThat(failed.skip()).isEqualTo(VariantSkipReason.NO_VARIANT);
    }

    @Test
    void focusEntryOneSwitchesToTheCoachEvenWhenTheNoticeWasNotHeard() {
        FlowState state = at(Phase.FOCUS, Sub.ROLE_NOTICE);

        Step played = table.onSlotEnded(state, SlotEnd.PLAYED);
        assertEnters(played, Phase.FOCUS, Sub.COACH_UTTERANCE);
        assertThat(played.role()).isEqualTo(FlowRole.COACH);
        assertThat(played.fact()).isEqualTo(FactKind.COACH_SWITCH);

        // FT-1023-g: 알림을 못 들려줘도 도움 단계는 닫지 않는다(BR5.1 · BR5.5의 '도움 없이 정리'를 대체)
        for (SlotEnd end : List.of(SlotEnd.INTERRUPTED, SlotEnd.FAILED, SlotEnd.BLOCKED)) {
            Step step = table.onSlotEnded(state, end);
            assertEnters(step, Phase.FOCUS, Sub.COACH_UTTERANCE);
            assertThat(step.role()).as(end.name()).isEqualTo(FlowRole.COACH);
            assertThat(step.cause()).isNull();
        }
        assertThat(table.onSlotEnded(state, SlotEnd.FAILED).facts()).containsExactly(FactKind.COACH_SWITCH, FactKind.PLAYBACK_FAILED);
        assertThat(table.onSlotEnded(state, SlotEnd.INTERRUPTED).facts()).containsExactly(FactKind.COACH_SWITCH);
    }

    @Test
    void focusEntryTwoAlwaysMovesToRetryCountingOneRetry() {
        FlowState state = at(Phase.FOCUS, Sub.COACH_UTTERANCE);
        state.role(FlowRole.COACH);

        for (SlotEnd end : List.of(SlotEnd.PLAYED, SlotEnd.INTERRUPTED)) {
            Step step = table.onSlotEnded(state, end);
            assertEnters(step, Phase.RETRY, Sub.NONE);
            assertThat(step.incrementsRetry()).isTrue();
        }
        Step failed = table.onSlotEnded(state, SlotEnd.FAILED);
        assertEnters(failed, Phase.RETRY, Sub.NONE);
        assertThat(failed.incrementsRetry()).as("다시 해 보기 횟수 한도가 반복을 끝낸다").isTrue();
        assertThat(failed.fact()).isEqualTo(FactKind.PLAYBACK_FAILED);
        Step blocked = table.onSlotEnded(state, SlotEnd.BLOCKED);
        assertEnters(blocked, Phase.RETRY, Sub.NONE);
        assertThat(blocked.facts()).isEmpty();
    }

    @Test
    void theReturnNoticeAlwaysReturnsTheRoleToPeerEvenWhenItFailsAndOnlyANormalSceneRecordsTheOriginalSceneReturn() {
        FlowState normal = at(Phase.SCENE_WRAP, Sub.RETURN_NOTICE);
        normal.role(FlowRole.COACH);
        normal.sceneEnd(EndCause.NORMAL, LiveTiming.AFTER_HELP);
        FlowState limited = at(Phase.SCENE_WRAP, Sub.RETURN_NOTICE);
        limited.role(FlowRole.COACH);
        limited.sceneEnd(EndCause.FOCUS_LIMIT, LiveTiming.NONE);

        for (SlotEnd end : SlotEnd.values()) {
            Step step = table.onSlotEnded(normal, end);
            assertEnters(step, Phase.SCENE_WRAP, end == SlotEnd.PLAYED ? Sub.PEER_REPLY : Sub.VARIANT_EVAL);
            assertThat(step.role()).isEqualTo(FlowRole.PEER);
            assertThat(step.facts()).startsWith(FactKind.COACH_RETURN).contains(FactKind.ORIGINAL_SCENE_RETURN);
        }
        assertThat(table.onSlotEnded(limited, SlotEnd.PLAYED).facts()).containsExactly(FactKind.COACH_RETURN);
        assertThat(table.onSlotEnded(limited, SlotEnd.FAILED).facts())
                .containsExactly(FactKind.COACH_RETURN, FactKind.PLAYBACK_FAILED);
    }

    @Test
    void slotEventsOutsideTheTableAreIgnored() {
        assertThat(table.onSlotEnded(at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL), SlotEnd.PLAYED).action()).isEqualTo(Action.IGNORE);
        assertThat(table.onSlotEnded(at(Phase.NEXT_GOAL, Sub.NONE), SlotEnd.PLAYED).action()).isEqualTo(Action.IGNORE);
        assertThat(table.onSlotEnded(at(Phase.CLOSING, Sub.NONE), SlotEnd.FAILED).action()).isEqualTo(Action.IGNORE);
        assertThat(table.onSlotEnded(at(Phase.FOCUS, Sub.NONE), SlotEnd.PLAYED).action()).isEqualTo(Action.IGNORE);
    }

    // ---- 변형 4조건과 종료 원인별 사유 (§3.3a · BR6.1 · BR6.2) ----

    private static FlowState stateWithCause(EndCause cause) {
        FlowState state = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL, false);
        state.sceneEnd(cause, LiveTiming.NONE);
        return state;
    }

    @ParameterizedTest
    @EnumSource(value = EndCause.class, names = {"NORMAL", "HELP_DECLINED"})
    void aNormalSceneOffersAVariantWhenAllFourConditionsHold(EndCause cause) {
        FlowState state = stateWithCause(cause);

        assertThat(table.decideVariant(state, true).offer()).isTrue();
        Step step = table.evaluateVariant(state, true);
        assertEnters(step, Phase.VARIANT_OFFER, Sub.NONE);
    }

    @Test
    void eachFalseConditionSkipsTheVariantWithItsOwnReason() {
        assertThat(table.decideVariant(stateWithCause(EndCause.NORMAL), false).skip()).isEqualTo(VariantSkipReason.NO_VARIANT);

        FlowState rest = stateWithCause(EndCause.NORMAL);
        rest.endRequested(true);
        assertThat(table.decideVariant(rest, true).skip()).isEqualTo(VariantSkipReason.END_OR_REST);

        FlowState tenMinutes = stateWithCause(EndCause.NORMAL);
        tenMinutes.goalLimitReached(true);
        assertThat(table.decideVariant(tenMinutes, true).skip()).isEqualTo(VariantSkipReason.TIME_LIMIT);

        FlowState focusExpired = stateWithCause(EndCause.NORMAL);
        focusExpired.focusLimitReached(true);
        assertThat(table.decideVariant(focusExpired, true).skip()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);

        FlowState retriesUsed = stateWithCause(EndCause.NORMAL);
        retriesUsed.incrementRetry();
        retriesUsed.incrementRetry();
        assertThat(table.decideVariant(retriesUsed, true).skip()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
    }

    @Test
    void oneRetryOfTwoStillOffersTheVariantAndZeroRetriesNeverCountAsTheFocusLimit() {
        FlowState oneRetry = stateWithCause(EndCause.NORMAL);
        oneRetry.incrementRetry();
        assertThat(table.decideVariant(oneRetry, true).offer()).isTrue();

        FlowTransitions noRetries = new FlowTransitions(0, 0);
        assertThat(noRetries.decideVariant(stateWithCause(EndCause.NORMAL), true).offer()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "TIMEOUT,END_OR_REST", "TRANSCRIPT_FAILED,END_OR_REST", "END_REQUEST,END_OR_REST",
            "ABSTAINED,NO_VARIANT", "HELP_UNCLEAR,NO_VARIANT", "SLOT_BLOCKED,NO_VARIANT", "PLAYBACK_FAILED,NO_VARIANT",
            "TIME_LIMIT,TIME_LIMIT", "FOCUS_LIMIT,FOCUS_LIMIT"})
    void theEndCauseDecidesTheSkipReasonAndNeverOffersAVariant(EndCause cause, VariantSkipReason reason) {
        FlowTransitions.VariantDecision decision = table.decideVariant(stateWithCause(cause), true);

        assertThat(decision.offer()).isFalse();
        assertThat(decision.skip()).isEqualTo(reason);
        Step step = table.evaluateVariant(stateWithCause(cause), true);
        assertEnters(step, Phase.NEXT_GOAL, Sub.NONE);
        assertThat(step.fact()).isEqualTo(FactKind.VARIANT_SKIPPED);
        assertThat(step.skip()).isEqualTo(reason);
    }

    @Test
    void aVariantSceneIsNeverOfferedAVariantAgainAndRecordsVariantPlayed() {
        FlowState state = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL, false);
        state.resetScene(SceneKind.VARIANT);
        state.sceneEnd(EndCause.NORMAL, LiveTiming.IMMEDIATE);

        Step step = table.evaluateVariant(state, true);

        assertEnters(step, Phase.NEXT_GOAL, Sub.NONE);
        assertThat(step.fact()).isEqualTo(FactKind.VARIANT_PLAYED);
        assertThat(step.skip()).isNull();
    }

    @Test
    void theLastGoalClosesAndOtherGoalsAdvanceAfterTheScene() {
        FlowState more = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL, false);
        more.sceneEnd(EndCause.TIME_LIMIT, LiveTiming.NONE);
        FlowState lastGoal = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL, true);
        lastGoal.sceneEnd(EndCause.TIME_LIMIT, LiveTiming.NONE);

        assertThat(table.evaluateVariant(more, true).phase()).isEqualTo(Phase.NEXT_GOAL);
        assertThat(table.evaluateVariant(lastGoal, true).phase()).isEqualTo(Phase.CLOSING);
    }

    @Test
    void anAbsentCauseIsTreatedAsNormalAndNegativeLimitsAreRejected() {
        FlowState state = at(Phase.SCENE_WRAP, Sub.VARIANT_EVAL, false);

        assertThat(table.decideVariant(state, true).offer()).isTrue();
        assertThatThrownBy(() -> new FlowTransitions(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowTransitions(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theContractEnumsTheTableUsesHaveExactlyTheContractSizes() {
        assertThat(FactKind.values()).contains(FactKind.PREPRACTICE_PROVIDED, FactKind.PREPRACTICE_ATTEMPTED);
        assertThat(VariantSkipReason.values()).hasSize(5);
        assertThat(LiveTiming.values()).hasSize(5);
    }
}
